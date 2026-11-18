"""Offline contract checks; these do not measure real-model accuracy."""

import json
from copy import deepcopy

import pytest

from app.schemas.validation import ValidateRequest
from app.services.llm_client import ProviderResponse
from app.services.validation import validate_input
from evaluations.validation import acceptance_209 as acceptance
from evaluations.validation import run as evaluation


def valid_finding():
    return {
        "kind": "MISMATCH",
        "severity": "BLOCKING",
        "design_item_id": "301",
        "design_item_version_id": "311",
        "message": "승인 userId와 계획 memberId가 다릅니다.",
        "suggested_action": "승인 설계에 맞추거나 변경을 협의하세요.",
        "evidence_ids": ["e-member-api"],
        "sources": [{"document_id": "201", "document_version_id": "211"}],
    }


def test_ac02_input_is_the_requested_direction_and_has_valid_hashes():
    case = acceptance.acceptance_cases()[0]
    request = case["request"]
    assert (
        request["approved_items"][0]["content"]["request_fields"][0]["name"] == "userId"
    )
    assert "memberId" in request["plan_revision"]["plan"]["approach"]
    assert "요청 JSON: userId" in request["evidence"][0]["text"]
    validate_input(ValidateRequest.model_validate(request))


def test_ac02_assessment_requires_action_and_the_approved_document_evidence():
    response = {
        "result": {"analysis_status": "COMPLETE", "findings": [valid_finding()]}
    }
    assert acceptance.assess("ac02_mismatch", response)[0]
    for field, replacement in (
        ("suggested_action", ""),
        ("evidence_ids", []),
        ("sources", []),
        ("design_item_version_id", "999"),
        ("message", "필드가 다릅니다"),
    ):
        invalid = deepcopy(response)
        invalid["result"]["findings"][0][field] = replacement
        assert not acceptance.assess("ac02_mismatch", invalid)[0], field
    response["result"]["analysis_status"] = "PARTIAL"
    assert not acceptance.assess("ac02_mismatch", response)[0]


def test_ac03_assessment_does_not_treat_a_complete_empty_result_as_evidence_of_safety():
    partial = {
        "result": {
            "analysis_status": "PARTIAL",
            "unassessed": [{"reason": "EVIDENCE_INSUFFICIENT"}],
        }
    }
    assert acceptance.assess("ac03_policy_unresolved", partial)[0]
    fabricated = deepcopy(partial)
    fabricated["result"]["findings"] = [{"kind": "MISSING_POLICY"}]
    assert not acceptance.assess("ac03_policy_unresolved", fabricated)[0]
    partial["result"]["analysis_status"] = "COMPLETE"
    assert not acceptance.assess("ac03_policy_unresolved", partial)[0]


def test_dry_run_never_reads_credentials_or_writes_results(monkeypatch, tmp_path):
    output = tmp_path / "result.json"
    monkeypatch.setattr("sys.argv", ["acceptance_209", "--output", str(output)])
    monkeypatch.setattr(
        evaluation, "configuration", lambda: pytest.fail("live mode not requested")
    )
    acceptance.main()
    assert not output.exists()


@pytest.mark.asyncio
async def test_two_case_route_uses_a_fake_provider_and_records_per_case_duration(
    monkeypatch,
):
    class Provider:
        def __init__(self, **kwargs):
            pass

        async def complete(self, request, *, timeout_seconds):
            data = json.loads(request.messages[1].content)
            keys = [item["key"] for item in data["checks"]]
            result = {
                "analysis_status": "COMPLETE",
                "checked_keys": keys,
                "unassessed": [],
                "findings": [],
            }
            if data["request_key"].endswith("-0"):
                finding = valid_finding()
                finding.update(
                    {
                        "check_key": "referenced-design-311",
                        "sources": [data["evidence"][0]["source"]],
                    }
                )
                result["findings"] = [finding]
            else:
                result["analysis_status"] = "PARTIAL"
                result["checked_keys"] = ["referenced-design-311"]
                result["unassessed"] = [
                    {
                        "check_key": "plan-completeness",
                        "reason": "EVIDENCE_INSUFFICIENT",
                        "message": "정책 근거 부족",
                    }
                ]
            return ProviderResponse(json.dumps(result), request.model, 100, 10)

        async def aclose(self):
            pass

    monkeypatch.setattr(evaluation, "OpenAICompatibleProvider", Provider)
    cases = acceptance.acceptance_cases()
    original_cases = deepcopy(cases)
    report = await evaluation.run(
        "https://api.openai.com/v1/chat/completions",
        "test-model",
        "unused",
        cases=cases,
        assessor=acceptance.assess,
    )
    assert [case["name"] for case in report["cases"]] == [
        "ac02_mismatch",
        "ac03_policy_unresolved",
    ]
    assert all(
        case["passed"] and case["http_status"] == 200 for case in report["cases"]
    )
    assert all(
        isinstance(case["duration_ms"], int) and case["duration_ms"] >= 0
        for case in report["cases"]
    )
    assert report["cases"][0]["response"]["usage"][0]["input_tokens"] == 100
    assert cases == original_cases
