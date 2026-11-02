"""Blind review uses only synthetic fixtures and stored outputs, without models."""

import copy
import json
from pathlib import Path

import pytest

from evaluations import context_selection_blind_131 as blind

ROOT = Path(__file__).resolve().parents[2]
FIXTURE = ROOT / "ai/tests/fixtures/retrieval/context-selection-131.json"
MEASUREMENT = (
    ROOT / "docs/experiments/results/S15P31A108-131-thresholds-2026-11-02.json"
)


def unfinished_form():
    return blind.prepare(FIXTURE, ROOT)[1]


def review():
    form = unfinished_form()
    # Test identity is a dummy; it is not a human review.
    form["reviewer"] = "test-only synthetic reviewer"
    for row in form["cases"]:
        row["uncertain"] = True
    form["cases"][0].update(
        uncertain=False,
        direct_item_ids=["1102"],
        expected_evidence_ids=["e-post"],
        no_match=False,
    )
    return form


def measurement():
    return json.loads(MEASUREMENT.read_text(encoding="utf-8"))


def test_sheet_exposes_actual_candidates_without_answers_or_original_case_names():
    sheet, form = blind.prepare(FIXTURE, ROOT)
    assert sheet.endswith("\n") and not sheet.endswith("\n\n")
    assert all(line == line.rstrip(" \t") for line in sheet.splitlines())
    assert len(form["cases"]) == 5
    assert all(row["uncertain"] is None for row in form["cases"])
    assert form["reviewer"] is None
    assert "업로드 생성 API" in sheet and "EXPLICIT, CONFIRMED" in sheet
    assert "ai/evaluations/data/requirements.md:7" in sheet
    assert "다른 원문 후보는 포함되지 않았다" in sheet
    for secret in (
        "fake_proposal",
        "expected_design_items",
        "unknown-retention",
        "upload-create-one-hop",
    ):
        assert secret not in sheet
    assert "1102" in sheet  # Candidate ID, not an expected answer.


def test_delivered_review_sheet_and_blank_form_match_current_fixture():
    sheet, form = blind.prepare(FIXTURE, ROOT)
    folder = ROOT / "docs/experiments/review-131"
    assert (folder / "blind-review.md").read_text(encoding="utf-8") == sheet
    assert json.loads((folder / "review-form.json").read_text(encoding="utf-8")) == form


def test_real_saved_threshold_compares_final_ids_and_skips_uncertainty():
    result = blind.compare(FIXTURE, ROOT, review(), measurement(), threshold=0.45)
    assert result["reviewer_identity_verified"] is False
    assert "do not contain per-case input hashes" in result["measurement_input_note"]
    assert result["cases"][0] == {
        "case": "CASE-01",
        "status": "compared",
        "missing_items": [],
        "extra_items": [],
        "missing_evidence": ["e-post"],
        "extra_evidence": ["e-get"],
        "no_match_agrees": True,
    }
    assert all(x["status"] == "uncertain_skipped" for x in result["cases"][1:])
    assert "MATCHED" not in json.dumps(result)


def test_primary_threshold_no_match_is_checked_separately():
    result = blind.compare(FIXTURE, ROOT, review(), measurement())
    assert result["threshold"] == 0.65
    assert result["cases"][0]["no_match_agrees"] is False


@pytest.mark.parametrize(
    "mutation",
    [
        "no_reviewer",
        "wrong_hash",
        "wrong_source",
        "wrong_case_input",
        "missing_case",
        "duplicate_case",
        "no_uncertainty",
        "invalid_id",
        "duplicate_id",
        "missing_no_match",
        "contradiction",
        "empty_direct_not_no_match",
        "uncertain_with_labels",
    ],
)
def test_invalid_or_incomplete_review_is_never_scored(mutation):
    form = review()
    row = form["cases"][0]
    if mutation == "no_reviewer":
        form["reviewer"] = None
    elif mutation == "wrong_hash":
        form["fixture_sha256"] = "0" * 64
    elif mutation == "wrong_source":
        form["source_sha256"] = {}
    elif mutation == "wrong_case_input":
        form["case_input_sha256"]["CASE-01"] = "0" * 64
    elif mutation == "missing_case":
        form["cases"].pop()
    elif mutation == "duplicate_case":
        form["cases"][1]["case"] = row["case"]
    elif mutation == "no_uncertainty":
        row["uncertain"] = None
    elif mutation == "invalid_id":
        row["direct_item_ids"] = ["foreign"]
    elif mutation == "duplicate_id":
        row["expected_evidence_ids"] = ["e-post", "e-post"]
    elif mutation == "missing_no_match":
        row["no_match"] = None
    elif mutation == "contradiction":
        row["no_match"] = True
    elif mutation == "empty_direct_not_no_match":
        row["direct_item_ids"] = []
        row["expected_evidence_ids"] = []
    else:
        row["uncertain"] = True
    with pytest.raises(ValueError):
        blind.compare(FIXTURE, ROOT, form, measurement())


def test_template_is_not_a_completed_review_and_no_match_can_keep_task_refs():
    with pytest.raises(ValueError):
        blind.compare(FIXTURE, ROOT, unfinished_form(), measurement())
    form = review()
    # A task reference can remain in the final set even with no direct match.
    form["cases"][1].update(
        uncertain=False,
        direct_item_ids=[],
        expected_evidence_ids=[],
        no_match=True,
    )
    result = blind.compare(FIXTURE, ROOT, form, measurement())
    assert result["cases"][1]["status"] == "compared"


def test_expected_expansion_uses_only_confirmed_explicit_one_hop():
    _digest, _sources, cases = blind._load_fixture(FIXTURE, ROOT)
    snapshot = cases[0][1]
    # 1102 -> 1101 is confirmed; 1101 -> 1100 is a second hop.
    # 1102 -> 1104 needs recheck; 1102 -> 1103 is an AI candidate.
    assert blind._expected_final_items(snapshot, {"1102"}) == {"1102", "1101"}
    with_task = cases[1][1]
    assert blind._expected_final_items(with_task, {"1102"}) == {"1101", "1102", "1104"}
    assert blind._expected_final_items(with_task, set()) == {"1104"}


@pytest.mark.parametrize(
    "mutation",
    [
        "wrong_hash",
        "missing_case",
        "duplicate_case",
        "in_flight",
        "unknown_id",
        "missing_threshold",
    ],
)
def test_incompatible_or_incomplete_measurement_is_never_compared(mutation):
    data = copy.deepcopy(measurement())
    threshold = None
    if mutation == "wrong_hash":
        data["fixture_sha256"] = "0" * 64
    elif mutation == "missing_case":
        data["cases"].pop()
    elif mutation == "duplicate_case":
        data["cases"][1]["id"] = data["cases"][0]["id"]
    elif mutation == "in_flight":
        data["in_flight"] = "test"
    elif mutation == "unknown_id":
        data["cases"][0]["items"] = ["foreign"]
    else:
        threshold = 0.99
    with pytest.raises(ValueError):
        blind.compare(FIXTURE, ROOT, review(), data, threshold)


def test_existing_outputs_are_never_overwritten(tmp_path):
    destination = tmp_path / "review.json"
    destination.write_text("original", encoding="utf-8")
    with pytest.raises(FileExistsError):
        blind._write_new(destination, "replacement")
    assert destination.read_text(encoding="utf-8") == "original"
