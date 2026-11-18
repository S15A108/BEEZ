"""Summarize saved synthetic validation results without network or model calls."""

import argparse
import json
import re
from collections import Counter, defaultdict
from decimal import Decimal, InvalidOperation
from pathlib import Path

from pydantic import ValidationError

from app.schemas.validation import ValidateResult

CASES = {"normal", "mismatch", "policy_unresolved", "document_conflict"}
EXPECTED_CHECKS = {"plan-completeness", "referenced-design-311"}


def _valid_result(value, name):
    try:
        parsed = ValidateResult.model_validate(value)
    except ValidationError:
        return False
    checked, unassessed, findings = (
        parsed.checked_keys,
        parsed.unassessed,
        parsed.findings,
    )
    unassessed_keys = [item.check_key for item in unassessed]
    keys = checked + unassessed_keys
    return (
        len(keys) == len(set(keys))
        and set(keys) == EXPECTED_CHECKS
        and (parsed.analysis_status == "COMPLETE") == (not unassessed)
        and (name != "policy_unresolved" or bool(unassessed))
        and all(item.check_key in checked for item in findings)
    )


def _assess(name, result):
    """Mirror the four scenario criteria; a failed criterion is not a population rate."""
    findings, unassessed = result["findings"], result["unassessed"]
    if name == "normal":
        return result["analysis_status"] == "COMPLETE" and not any(
            item.get("severity") == "BLOCKING" for item in findings
        )
    if name == "mismatch":
        return any(
            item.get("kind") == "MISMATCH"
            and item.get("severity") == "BLOCKING"
            and item.get("design_item_version_id") == "311"
            and isinstance(item.get("message"), str)
            and "userid" in item["message"].lower()
            and "memberid" in item["message"].lower()
            for item in findings
        )
    if name == "policy_unresolved":
        return result["analysis_status"] == "PARTIAL" and any(
            item.get("reason") == "EVIDENCE_INSUFFICIENT" for item in unassessed
        )
    return any(
        item.get("kind") in {"CONFLICT", "MISMATCH"}
        and item.get("design_item_version_id") == "311"
        and isinstance(item.get("evidence_ids"), list)
        and "e-member-api" in item["evidence_ids"]
        for item in findings
    )


def _number(value):
    return isinstance(value, int) and not isinstance(value, bool) and value >= 0


def _measurement():
    return {"known_sum": 0, "known_records": 0, "unknown_records": 0}


def _add_number(target, value):
    if _number(value):
        target["known_sum"] += value
        target["known_records"] += 1
    else:
        target["unknown_records"] += 1


def summarize(report):
    if not isinstance(report, dict) or not isinstance(report.get("cases"), list):
        raise TypeError("Expected an evaluation report with a cases array")
    records = report["cases"]
    if any(not isinstance(case, dict) for case in records):
        raise TypeError("Each case must be an object")
    result = {
        "scope": "Synthetic tuning cases; not an independent or representative quality estimate",
        "source_created_at": report.get("created_at"),
        "requested_model": report.get("requested_model"),
        "cases": [],
        "outcomes": {"passed": 0, "criterion_failed": 0, "unobservable": 0},
        "failure_types": {},
        "criteria": {
            "normal_blocking_false_positive": _measurement(),
            "mismatch_criterion_not_met": _measurement(),
            "document_conflict_criterion_not_met": _measurement(),
            "policy_unresolved_criterion_not_met": _measurement(),
        },
        "usage": {
            "reported_records": 0,
            "cases_without_reported_usage": 0,
            "input_tokens": _measurement(),
            "output_tokens": _measurement(),
            "latency_ms": _measurement(),
            "failed_records": 0,
            "failure_status_unknown_records": 0,
            "cost_by_currency": {},
            "cost_unknown_records": 0,
        },
    }
    failure_types = Counter()
    costs = defaultdict(Decimal)
    for case in records:
        name = case.get("name")
        response = case.get("response")
        response = response if isinstance(response, dict) else {}
        status, payload = case.get("http_status"), response.get("result")
        error = response.get("error")
        error_code = error.get("code") if isinstance(error, dict) else None
        if not isinstance(name, str) or name not in CASES:
            outcome, failure_type = "unobservable", "unknown_case"
        elif (
            status == 200
            and response.get("origin") == "MODEL"
            and "error" not in response
            and _valid_result(payload, name)
        ):
            outcome = "passed" if _assess(name, payload) else "criterion_failed"
            failure_type = (
                "semantic_mismatch" if outcome == "criterion_failed" else None
            )
        elif status == 200:
            outcome, failure_type = "unobservable", "invalid_response_shape"
        elif error_code == "evaluation_transport_failure":
            outcome, failure_type = "unobservable", "transport_failure"
        elif status is None:
            outcome, failure_type = "unobservable", "missing_http_status"
        elif not isinstance(status, int) or isinstance(status, bool):
            outcome, failure_type = "unobservable", "invalid_http_status"
        elif error_code == "model_unavailable" and case.get("raw_model_content"):
            # A provider response was captured, but the public error does not
            # reveal whether parsing or another validation stage rejected it.
            outcome, failure_type = "unobservable", "model_output_then_rejected"
        else:
            outcome, failure_type = "unobservable", "model_or_server_error"
        result["outcomes"][outcome] += 1
        if failure_type:
            failure_types[failure_type] += 1
        result["cases"].append(
            {"name": name, "outcome": outcome, "failure_type": failure_type}
        )

        criterion = (
            {
                "normal": "normal_blocking_false_positive",
                "mismatch": "mismatch_criterion_not_met",
                "document_conflict": "document_conflict_criterion_not_met",
                "policy_unresolved": "policy_unresolved_criterion_not_met",
            }.get(name)
            if isinstance(name, str)
            else None
        )
        if criterion:
            measure = result["criteria"][criterion]
            if outcome == "unobservable":
                measure["unknown_records"] += 1
            else:
                measure["known_records"] += 1
                if name == "normal":
                    measure["known_sum"] += int(
                        any(
                            f.get("severity") == "BLOCKING" for f in payload["findings"]
                        )
                    )
                else:
                    measure["known_sum"] += int(outcome == "criterion_failed")

        usage = response.get("usage")
        if not isinstance(usage, list) or not usage:
            result["usage"]["cases_without_reported_usage"] += 1
            continue
        for item in usage:
            result["usage"]["reported_records"] += 1
            item = item if isinstance(item, dict) else {}
            for field in ("input_tokens", "output_tokens"):
                _add_number(
                    result["usage"][field],
                    item.get(field) if item.get("tokens_known") is True else None,
                )
            _add_number(result["usage"]["latency_ms"], item.get("latency_ms"))
            if item.get("failed") is True:
                result["usage"]["failed_records"] += 1
            elif item.get("failed") is not False:
                result["usage"]["failure_status_unknown_records"] += 1
            amount, currency = item.get("estimated_cost_amount"), item.get("currency")
            try:
                if (
                    isinstance(amount, bool)
                    or amount is None
                    or not isinstance(currency, str)
                    or re.fullmatch(r"[A-Z]{3}", currency) is None
                ):
                    raise InvalidOperation
                cost = Decimal(str(amount))
                if not cost.is_finite() or cost < 0:
                    raise InvalidOperation
                costs[currency] += cost
            except (InvalidOperation, ValueError):
                result["usage"]["cost_unknown_records"] += 1
    result["failure_types"] = dict(sorted(failure_types.items()))
    result["usage"]["cost_by_currency"] = {
        key: str(value) for key, value in sorted(costs.items())
    }
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path, help="Saved synthetic evaluation JSON")
    parser.add_argument(
        "--output", type=Path, help="Write summary to this separate file"
    )
    args = parser.parse_args()
    if args.output and args.output.resolve() == args.input.resolve():
        parser.error("Output must not overwrite the source evaluation")
    report = json.loads(args.input.read_text(encoding="utf-8"))
    rendered = json.dumps(summarize(report), ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered, encoding="utf-8")
    else:
        print(rendered, end="")


if __name__ == "__main__":
    main()
