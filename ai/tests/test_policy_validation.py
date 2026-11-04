import copy
import json
from pathlib import Path

import pytest

from app.schemas.validation import ValidateRequest
from app.services.llm_client import LLMClient, ProviderResponse
from app.services.validation import (
    InvalidInput,
    analyze,
    structured_response_format,
    validate_input,
    validate_output,
)

BASE = Path(__file__).resolve().parents[2] / "docs/api/examples/validation/request.json"
KEY = "policy-gap-v1"


def request():
    body = json.loads(BASE.read_text(encoding="utf-8"))
    body["plan_revision"]["plan"]["approach"] = "로그인 실패 시 잠금 정책을 적용한다."
    body["approved_items"][0]["content"]["description"] = (
        "연속 5회 실패하면 계정을 30분 잠근다."
    )
    body["checks"] = [
        {
            "key": KEY,
            "kind": "POLICY_ALIGNMENT",
            "design_item_version_ids": ["311"],
            "related_plan_revision_ids": [],
        }
    ]
    body["related_plans"] = []
    body["unresolved"] = []
    return body


def assessment(state="SUPPORTED"):
    return {
        "need_id": "lockout",
        "topic": "로그인 실패 잠금",
        "plan_evidence": {"field": "approach", "quote": "로그인 실패 시 잠금 정책"},
        "state": state,
        "design_item_version_ids": ["311"],
        "approved_content_refs": [
            {
                "design_item_version_id": "311",
                "path": ["description"],
                "quote": "5회 실패하면",
            }
        ],
        "evidence_ids": [],
        "explanation": "승인된 잠금 정책을 확인했습니다.",
    }


def complete(a=None):
    return {
        "analysis_status": "COMPLETE",
        "checked_keys": [KEY],
        "unassessed": [],
        "findings": [],
        "policy_assessments": [a or assessment()],
        "policy_assessments_truncated": False,
    }


def check(body, result):
    parsed = ValidateRequest.model_validate(body)
    validate_input(parsed)
    return validate_output(result, parsed)


def test_supported_policy_carries_exact_plan_and_design_provenance():
    assert check(request(), complete()).policy_assessments[0].state == "SUPPORTED"


@pytest.mark.parametrize(
    "change",
    [
        "plan_quote",
        "plan_index",
        "content_quote",
        "content_path",
        "version",
        "no_evidence",
        "duplicate",
        "missing_fields",
    ],
)
def test_invalid_policy_provenance_is_rejected(change):
    body, result = request(), complete()
    item = result["policy_assessments"][0]
    if change == "plan_quote":
        item["plan_evidence"]["quote"] = "다른 계획"
    if change == "plan_index":
        item["plan_evidence"]["index"] = 0
    if change == "content_quote":
        item["approved_content_refs"][0]["quote"] = "승인되지 않은 정책"
    if change == "content_path":
        item["approved_content_refs"][0]["path"] = ["unknown"]
    if change == "version":
        item["design_item_version_ids"] = ["999"]
    if change == "no_evidence":
        item["approved_content_refs"] = []
    if change == "duplicate":
        result["policy_assessments"].append(copy.deepcopy(item))
    if change == "missing_fields":
        result.pop("policy_assessments_truncated")
    with pytest.raises(ValueError):
        check(body, result)


@pytest.mark.parametrize("cause", ["insufficient", "truncated"])
def test_incomplete_policy_analysis_cannot_be_complete(cause):
    body, result = request(), complete()
    if cause == "insufficient":
        result["policy_assessments"][0]["state"] = "INSUFFICIENT"
    else:
        result["policy_assessments_truncated"] = True
    with pytest.raises(ValueError):
        check(body, result)
    result.update(
        analysis_status="PARTIAL",
        checked_keys=[],
        unassessed=[
            {
                "check_key": KEY,
                "reason": "EVIDENCE_INSUFFICIENT",
                "message": "추가 근거가 필요합니다",
            }
        ],
    )
    assert check(body, result).analysis_status == "PARTIAL"


def test_empty_assessment_requires_reason_and_plan_evidence_or_partial():
    body, result = request(), complete()
    result["policy_assessments"] = []
    with pytest.raises(ValueError):
        check(body, result)
    result["no_policy_requirements_reason"] = "정책 요구가 없습니다"
    result["no_policy_requirements_evidence"] = {
        "field": "goal",
        "quote": body["plan_revision"]["plan"]["goal"],
    }
    assert check(body, result).analysis_status == "COMPLETE"
    result.pop("no_policy_requirements_reason")
    result.pop("no_policy_requirements_evidence")
    result.update(
        analysis_status="PARTIAL",
        checked_keys=[],
        unassessed=[
            {
                "check_key": KEY,
                "reason": "EVIDENCE_INSUFFICIENT",
                "message": "계획이 모호합니다",
            }
        ],
    )
    assert check(body, result).analysis_status == "PARTIAL"


def undecided_case():
    body, result = request(), complete(assessment("EXPLICITLY_UNDECIDED"))
    body["evidence"][0]["text"] = "잠금 정책: 미정\n"
    body["evidence"][0]["locator"]["end_line"] = body["evidence"][0]["locator"][
        "start_line"
    ]
    result["policy_assessments"][0]["evidence_ids"] = ["evidence-login-response"]
    result["findings"] = [
        {
            "check_key": KEY,
            "kind": "MISSING_POLICY",
            "severity": "BLOCKING",
            "message": "잠금 정책 미정",
            "design_item_id": "301",
            "design_item_version_id": "311",
            "sources": [body["evidence"][0]["source"]],
            "evidence_ids": ["evidence-login-response"],
            "suggested_action": "정책을 확정하세요",
            "policy_need_id": "lockout",
        }
    ]
    return body, result


def test_explicit_undecided_requires_a_cited_original_line():
    body, result = undecided_case()
    assert check(body, result).findings[0].kind == "MISSING_POLICY"
    body["evidence"][0]["text"] = "잠금 정책: 미정이 아니다\n"
    with pytest.raises(ValueError):
        check(body, result)


def conflict_case():
    body, result = request(), complete(assessment("CONTRADICTORY"))
    second = copy.deepcopy(body["approved_items"][0])
    second.update(
        id="312",
        design_item_id="302",
        kind="POLICY",
        content={"description": "연속 3회 실패하면 계정을 10분 잠근다."},
    )
    second.pop("source", None)
    body["approved_items"].append(second)
    body["checks"][0]["design_item_version_ids"].append("312")
    a = result["policy_assessments"][0]
    a["design_item_version_ids"].append("312")
    a["approved_content_refs"].append(
        {
            "design_item_version_id": "312",
            "path": ["description"],
            "quote": "3회 실패하면",
        }
    )
    result["findings"] = [
        {
            "check_key": KEY,
            "kind": "CONFLICT",
            "severity": "BLOCKING",
            "message": "서로 다른 잠금 정책",
            "design_item_id": "301",
            "design_item_version_id": "311",
            "sources": [],
            "evidence_ids": [],
            "suggested_action": "정책을 검토하세요",
            "policy_need_id": "lockout",
            "conflicting_design_refs": [
                {
                    "design_item_id": "302",
                    "design_item_version_id": "312",
                    "evidence_ids": [],
                    "content_paths": [["description"]],
                }
            ],
        }
    ]
    return body, result


def test_two_approved_items_conflict_preserves_both_sides():
    body, result = conflict_case()
    finding = check(body, result).findings[0]
    assert finding.conflicting_design_refs[0].design_item_version_id == "312"
    result["findings"][0]["conflicting_design_refs"][0]["content_paths"] = [["forged"]]
    with pytest.raises(ValueError):
        check(body, result)


def test_contradiction_without_a_finding_cannot_be_complete():
    with pytest.raises(ValueError):
        check(request(), complete(assessment("CONTRADICTORY")))


def test_policy_check_cannot_exclude_retrieved_items_or_impersonate_other_checks():
    body = request()
    body["checks"][0]["design_item_version_ids"] = []
    with pytest.raises(InvalidInput):
        validate_input(ValidateRequest.model_validate(body))
    body["checks"][0]["kind"] = "PLAN_COMPLETENESS"
    with pytest.raises(InvalidInput):
        validate_input(ValidateRequest.model_validate(body))


def test_policy_schema_is_opt_in():
    assert (
        "policy_assessments"
        not in structured_response_format()["json_schema"]["schema"]["properties"]
    )
    assert (
        "policy_assessments"
        in structured_response_format(policy=True)["json_schema"]["schema"][
            "properties"
        ]
    )


@pytest.mark.asyncio
async def test_policy_analysis_uses_one_bounded_model_call():
    class Provider:
        def __init__(self):
            self.requests = []

        async def complete(self, model_request, *, timeout_seconds):
            self.requests.append(model_request)
            return ProviderResponse(json.dumps(complete()), model_request.model, 10, 10)

    provider = Provider()
    result = await analyze(
        ValidateRequest.model_validate(request()), LLMClient(provider), "test-model"
    )
    assert result["result"]["policy_assessments"][0]["state"] == "SUPPORTED"
    assert len(provider.requests) == 1
    assert provider.requests[0].max_attempts == 1
    assert provider.requests[0].model_options == {"max_completion_tokens": 4096}
