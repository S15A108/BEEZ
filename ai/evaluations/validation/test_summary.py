"""Check conservative offline aggregation of saved evaluation artifacts."""

import json
from copy import deepcopy
from pathlib import Path

import pytest

from evaluations.validation.summary import main, summarize

HERE = Path(__file__).parent


def fixture(name="results-live-gpt41-final.json"):
    return json.loads((HERE / name).read_text(encoding="utf-8"))


def test_final_fixture_recomputes_criteria_and_counts_actual_usage():
    report = summarize(fixture())
    assert report["outcomes"] == {"passed": 4, "criterion_failed": 0, "unobservable": 0}
    assert all(item["known_sum"] == 0 for item in report["criteria"].values())
    assert report["usage"]["input_tokens"] == {
        "known_sum": 7284,
        "known_records": 4,
        "unknown_records": 0,
    }
    assert report["usage"]["output_tokens"]["known_sum"] == 567
    assert report["usage"]["latency_ms"]["known_records"] == 4
    assert report["usage"]["cost_by_currency"] == {}
    assert report["usage"]["cost_unknown_records"] == 4
    assert "not an independent" in report["scope"]


def test_expected_finding_miss_and_normal_blocking_are_explicit():
    source = fixture()
    normal, mismatch, policy, conflict = source["cases"]
    finding = deepcopy(mismatch["response"]["result"]["findings"][0])
    normal["response"]["result"]["findings"] = [finding]
    mismatch["response"]["result"]["findings"][0]["message"] = "불일치"
    policy["response"]["result"]["unassessed"][0]["reason"] = "UNSUPPORTED_CONTENT"
    conflict["response"]["result"]["findings"][0]["evidence_ids"] = []
    # Stored result was produced earlier; the summary must recompute criteria.
    assert all(case["passed"] for case in source["cases"])
    report = summarize(source)
    assert report["outcomes"]["criterion_failed"] == 4
    assert report["failure_types"]["semantic_mismatch"] == 4
    assert all(item["known_sum"] == 1 for item in report["criteria"].values())


def test_nonblocking_finding_is_not_called_blocking_false_positive():
    source = fixture()
    finding = deepcopy(source["cases"][1]["response"]["result"]["findings"][0])
    finding["severity"] = "WARNING"
    source["cases"][0]["response"]["result"]["findings"] = [finding]
    result = summarize(source)
    assert result["criteria"]["normal_blocking_false_positive"]["known_sum"] == 0
    assert result["cases"][0]["outcome"] == "passed"


def test_failures_and_missing_measurements_are_unknown_not_zero():
    source = fixture()
    cases = source["cases"]
    cases[0]["http_status"] = None
    cases[0]["response"] = {"error": {"code": "evaluation_transport_failure"}}
    cases[1]["http_status"] = 502
    cases[1]["raw_model_content"] = ["not valid"]
    cases[1]["response"] = {
        "error": {"code": "model_unavailable"},
        "usage": [{"failed": True, "tokens_known": False, "input_tokens": 10}],
    }
    cases[2]["response"]["result"]["findings"] = [{}]
    cases[3]["http_status"] = 504
    cases[3]["response"] = {"error": {"code": "model_timeout"}}
    report = summarize(source)
    assert report["outcomes"] == {"passed": 0, "criterion_failed": 0, "unobservable": 4}
    assert report["failure_types"] == {
        "invalid_response_shape": 1,
        "model_or_server_error": 1,
        "model_output_then_rejected": 1,
        "transport_failure": 1,
    }
    assert all(item["unknown_records"] == 1 for item in report["criteria"].values())
    assert report["usage"]["input_tokens"]["unknown_records"] == 1
    assert report["usage"]["input_tokens"]["known_sum"] == 1843
    assert report["usage"]["cases_without_reported_usage"] == 2
    assert report["usage"]["failed_records"] == 1


def test_currency_totals_are_not_mixed_and_unknown_values_stay_unknown():
    source = fixture()
    usage = source["cases"][0]["response"]["usage"][0]
    usage.update(estimated_cost_amount="0.123", currency="USD")
    source["cases"][1]["response"]["usage"][0].update(
        estimated_cost_amount="100", currency="KRW"
    )
    source["cases"][2]["response"]["usage"][0].update(
        estimated_cost_amount="0.007", currency="USD"
    )
    source["cases"][3]["response"]["usage"][0].update(
        estimated_cost_amount="NaN", currency="USD"
    )
    report = summarize(source)
    assert report["usage"]["cost_by_currency"] == {"KRW": "100", "USD": "0.130"}
    assert report["usage"]["cost_unknown_records"] == 1


def test_input_file_is_never_overwritten(monkeypatch, tmp_path):
    source = tmp_path / "input.json"
    source.write_text(json.dumps(fixture()), encoding="utf-8")
    original = source.read_bytes()
    monkeypatch.setattr("sys.argv", ["summary", str(source), "--output", str(source)])
    with pytest.raises(SystemExit):
        main()
    assert source.read_bytes() == original


def test_malformed_case_result_is_not_an_observed_success():
    source = fixture()
    source["cases"][0]["response"]["result"]["findings"] = [{}]
    source["cases"][1]["name"] = []
    report = summarize(source)
    assert report["cases"][0]["failure_type"] == "invalid_response_shape"
    assert report["cases"][1]["failure_type"] == "unknown_case"


@pytest.mark.parametrize("field", ["check_key", "sources", "suggested_action"])
def test_incomplete_finding_is_unobservable(field):
    source = fixture()
    source["cases"][1]["response"]["result"]["findings"][0].pop(field)
    report = summarize(source)
    assert report["cases"][1]["failure_type"] == "invalid_response_shape"


def test_incomplete_unassessed_message_is_unobservable():
    source = fixture()
    source["cases"][2]["response"]["result"]["unassessed"][0].pop("message")
    assert summarize(source)["cases"][2]["failure_type"] == "invalid_response_shape"


@pytest.mark.parametrize("invalid", ["no_origin", "other_origin", "error"])
def test_success_requires_model_origin_and_no_error(invalid):
    source = fixture()
    response = source["cases"][0]["response"]
    if invalid == "no_origin":
        response.pop("origin")
    elif invalid == "other_origin":
        response["origin"] = "TEMPLATE"
    else:
        response["error"] = {"code": "model_timeout"}
    assert summarize(source)["cases"][0]["failure_type"] == "invalid_response_shape"


def test_missing_usage_does_not_change_success_observation():
    source = fixture()
    source["cases"][0]["response"].pop("usage")
    report = summarize(source)
    assert report["cases"][0]["outcome"] == "passed"
    assert report["usage"]["cases_without_reported_usage"] == 1


@pytest.mark.parametrize("currency", ["usd", "US", "USDD", "12A", " USD", "한글화"])
def test_invalid_currency_is_not_aggregated(currency):
    source = fixture()
    source["cases"][0]["response"]["usage"][0].update(
        estimated_cost_amount="0.1", currency=currency
    )
    report = summarize(source)
    assert report["usage"]["cost_by_currency"] == {}
    assert report["usage"]["cost_unknown_records"] == 4


@pytest.mark.parametrize(
    "problem",
    [
        "missing_checks",
        "duplicate_checked",
        "unknown_check",
        "duplicate_unassessed",
        "checked_and_unassessed",
        "complete_with_unassessed",
        "partial_without_unassessed",
        "finding_unassessed",
    ],
)
def test_incomplete_or_contradictory_check_coverage_is_unobservable(problem):
    source = fixture()
    payload = source["cases"][0]["response"]["result"]
    unassessed = {
        "check_key": "plan-completeness",
        "reason": "EVIDENCE_INSUFFICIENT",
        "message": "증거 부족",
    }
    if problem == "missing_checks":
        payload["checked_keys"] = []
    elif problem == "duplicate_checked":
        payload["checked_keys"].append("plan-completeness")
    elif problem == "unknown_check":
        payload["checked_keys"][0] = "unknown"
    elif problem == "duplicate_unassessed":
        payload["analysis_status"] = "PARTIAL"
        payload["checked_keys"] = ["referenced-design-311"]
        payload["unassessed"] = [unassessed, unassessed.copy()]
    elif problem == "checked_and_unassessed":
        payload["analysis_status"] = "PARTIAL"
        payload["unassessed"] = [unassessed]
    elif problem == "complete_with_unassessed":
        payload["checked_keys"] = ["referenced-design-311"]
        payload["unassessed"] = [unassessed]
    elif problem == "partial_without_unassessed":
        payload["analysis_status"] = "PARTIAL"
    else:
        payload["analysis_status"] = "PARTIAL"
        payload["checked_keys"] = ["referenced-design-311"]
        payload["unassessed"] = [unassessed]
        finding = deepcopy(source["cases"][1]["response"]["result"]["findings"][0])
        finding["check_key"] = "plan-completeness"
        payload["findings"] = [finding]
    report = summarize(source)
    assert report["cases"][0]["outcome"] == "unobservable"
    assert report["cases"][0]["failure_type"] == "invalid_response_shape"
    assert report["criteria"]["normal_blocking_false_positive"]["unknown_records"] == 1


def test_unresolved_input_marked_complete_is_unobservable():
    source = fixture()
    payload = source["cases"][2]["response"]["result"]
    payload["analysis_status"] = "COMPLETE"
    payload["checked_keys"] = ["plan-completeness", "referenced-design-311"]
    payload["unassessed"] = []
    report = summarize(source)
    assert report["cases"][2]["outcome"] == "unobservable"
    assert (
        report["criteria"]["policy_unresolved_criterion_not_met"]["unknown_records"]
        == 1
    )


def test_missing_http_status_is_not_invented_as_transport_failure():
    source = fixture()
    source["cases"][0].pop("http_status")
    report = summarize(source)
    assert report["cases"][0]["outcome"] == "unobservable"
    assert report["cases"][0]["failure_type"] == "missing_http_status"


def test_zero_usage_can_be_known_and_unreported_failure_is_unknown():
    source = fixture()
    item = source["cases"][0]["response"]["usage"][0]
    item.update(input_tokens=0, output_tokens=0, latency_ms=0)
    item.pop("failed")
    report = summarize(source)
    assert report["usage"]["input_tokens"]["known_records"] == 4
    assert report["usage"]["latency_ms"]["known_records"] == 4
    assert report["usage"]["failure_status_unknown_records"] == 1
