import copy
import json
from contextlib import asynccontextmanager
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.core.config import Settings, get_settings
from app.main import app
from app.routers import validation as validation_router
from app.schemas.validation import ValidateRequest
from app.services.llm_client import LLMClient, LLMError, ProviderError, ProviderResponse
from app.services.validation import (
    InvalidInput,
    analyze,
    validate_input,
    validate_output,
)

REQUEST_FILE = (
    Path(__file__).resolve().parents[2] / "docs/api/examples/validation/request.json"
)


def fixture_request():
    return json.loads(REQUEST_FILE.read_text(encoding="utf-8"))


def result_for(request):
    return {
        "analysis_status": "COMPLETE",
        "checked_keys": [check["key"] for check in request["checks"]],
        "unassessed": [],
        "findings": [],
    }


@pytest.mark.parametrize(
    "text", ["- 재시도 정책: 미정", "Policy: TBD", "권한 정책: 결정되지 않음"]
)
def test_missing_policy_requires_explicit_cited_original_text(text):
    body = fixture_request()
    body["evidence"][0]["text"] = "# 정책\n" + text + "\n"
    result = result_for(body)
    result["findings"] = [
        {
            "check_key": "design-api-login",
            "kind": "MISSING_POLICY",
            "severity": "BLOCKING",
            "message": "정책 결정이 필요합니다",
            "design_item_id": "301",
            "design_item_version_id": "311",
            "sources": [body["evidence"][0]["source"]],
            "evidence_ids": ["evidence-login-response"],
            "suggested_action": "정책을 확정하세요",
        }
    ]
    assert (
        validate_output(result, ValidateRequest.model_validate(body)).findings[0].kind
        == "MISSING_POLICY"
    )


@pytest.mark.parametrize(
    "text",
    [
        "정책은 관리자만 변경할 수 있다.",
        "재시도 정책: 미정이 아니다",
        "재시도 정책: 미정이었으나 확정됨",
        "설계 문서에 정책 내용이 없다",
    ],
)
def test_missing_policy_does_not_infer_absence_from_document(text):
    body = fixture_request()
    body["evidence"][0]["text"] = text
    result = result_for(body)
    result["findings"] = [
        {
            "check_key": "design-api-login",
            "kind": "MISSING_POLICY",
            "severity": "BLOCKING",
            "message": "정책 결정이 필요합니다",
            "design_item_id": "301",
            "design_item_version_id": "311",
            "sources": [body["evidence"][0]["source"]],
            "evidence_ids": ["evidence-login-response"],
            "suggested_action": "정책을 확정하세요",
        }
    ]
    with pytest.raises(ValueError, match="policy absence is not established"):
        validate_output(result, ValidateRequest.model_validate(body))


def test_missing_policy_rejects_uncited_evidence_and_unmatched_design():
    body = fixture_request()
    body["evidence"][0]["text"] = "재시도 정책: 미정"
    result = result_for(body)
    finding = {
        "check_key": "design-api-login",
        "kind": "MISSING_POLICY",
        "severity": "BLOCKING",
        "message": "정책 결정이 필요합니다",
        "design_item_id": "301",
        "design_item_version_id": "311",
        "sources": [body["evidence"][0]["source"]],
        "evidence_ids": [],
        "suggested_action": "정책을 확정하세요",
    }
    result["findings"] = [finding]
    with pytest.raises(ValueError, match="policy absence is not established"):
        validate_output(result, ValidateRequest.model_validate(body))
    finding["evidence_ids"] = ["evidence-login-response"]
    finding["sources"] = []
    with pytest.raises(ValueError, match="unknown evidence"):
        validate_output(result, ValidateRequest.model_validate(body))
    finding["sources"] = [body["evidence"][0]["source"]]
    finding["design_item_id"] = None
    finding["design_item_version_id"] = None
    with pytest.raises(ValueError, match="policy absence is not established"):
        validate_output(result, ValidateRequest.model_validate(body))


class Provider:
    def __init__(self, result=None, error=None):
        self.result = result
        self.error = error
        self.calls = []

    async def complete(self, request, *, timeout_seconds):
        self.calls.append(request)
        if self.error:
            raise self.error
        return ProviderResponse(json.dumps(self.result), request.model, 11, 7)


@pytest.mark.asyncio
async def test_one_call_has_check_coverage_and_attempt_usage_key():
    body = fixture_request()
    provider = Provider(result_for(body))
    response = await analyze(
        ValidateRequest.model_validate(body), LLMClient(provider), "test-model"
    )
    assert response["result"]["analysis_status"] == "COMPLETE"
    assert response["result"]["checked_keys"] == [
        check["key"] for check in body["checks"]
    ]
    assert response["usage"][0]["request_key"] == "llm-validate-601-attempt-1-call-1"
    assert response["usage"][0]["feature"] == "TASK_VALIDATION"
    assert response["usage"][0]["input_tokens"] == 11
    assert provider.calls[0].max_attempts == 1


@pytest.mark.asyncio
async def test_context_packet_constraints_are_sent_as_untrusted_context():
    body = fixture_request()
    body["context_packet_constraints"] = ["접근 로그를 기록한다"]
    provider = Provider(result_for(body))
    await analyze(
        ValidateRequest.model_validate(body), LLMClient(provider), "test-model"
    )
    assert (
        json.loads(provider.calls[0].messages[1].content)["context_packet_constraints"]
        == body["context_packet_constraints"]
    )
    assert "untrusted contextual data" in provider.calls[0].messages[0].content


def unlinked_document_request():
    body = fixture_request()
    body["evidence"].append(
        {
            "evidence_id": "document-222-2-2",
            "source": {
                "document_id": "202",
                "document_version_id": "222",
                "version_no": 1,
                "location": "2~2행",
            },
            "locator": {"type": "text_lines", "start_line": 2, "end_line": 2},
            "text": "기획 원문\n",
            "text_hash": "a" * 64,
            "normalization_version": "utf8-lf-v1",
        }
    )
    return body


@pytest.mark.asyncio
async def test_unlinked_document_is_sent_as_evidence_and_may_be_cited_for_plan():
    body = unlinked_document_request()
    result = result_for(body)
    result["findings"] = [
        {
            "check_key": "plan-completeness",
            "kind": "MISMATCH",
            "severity": "WARNING",
            "message": "기획 원문과 계획의 범위가 다릅니다",
            "sources": [body["evidence"][-1]["source"]],
            "evidence_ids": ["document-222-2-2"],
            "suggested_action": "계획 범위를 확인하세요",
        }
    ]
    provider = Provider(result)
    response = await analyze(
        ValidateRequest.model_validate(body), LLMClient(provider), "test-model"
    )
    sent = json.loads(provider.calls[0].messages[1].content)
    assert sent["evidence"][-1] == body["evidence"][-1]
    assert response["result"]["findings"][0]["evidence_ids"] == ["document-222-2-2"]


def test_unlinked_document_cannot_be_attributed_to_design_or_prove_missing_policy():
    body = unlinked_document_request()
    body["evidence"][-1]["text"] = "정책: 미정\n"
    parsed = ValidateRequest.model_validate(body)
    validate_input(parsed)
    result = result_for(body)
    finding = {
        "check_key": "design-api-login",
        "kind": "MISMATCH",
        "severity": "WARNING",
        "message": "문서 확인",
        "design_item_id": "301",
        "design_item_version_id": "311",
        "sources": [body["evidence"][-1]["source"]],
        "evidence_ids": ["document-222-2-2"],
        "suggested_action": "확인하세요",
    }
    result["findings"] = [finding]
    with pytest.raises(ValueError, match="unknown source"):
        validate_output(result, parsed)
    finding.update(
        check_key="plan-completeness",
        kind="MISSING_POLICY",
        severity="BLOCKING",
        design_item_id=None,
        design_item_version_id=None,
    )
    with pytest.raises(ValueError, match="policy absence is not established"):
        validate_output(result, parsed)


def delta_request():
    body = fixture_request()
    body["completion_delta"] = [
        {
            "description": "완료 결과에서 필수 토큰을 반환하지 않았습니다",
            "files": ["auth.py"],
        }
    ]
    body["checks"].append(
        {
            "key": "completion-delta",
            "kind": "PLAN_COMPLETENESS",
            "design_item_version_ids": [],
            "related_plan_revision_ids": [],
        }
    )
    return body


@pytest.mark.asyncio
async def test_completion_delta_is_sent_to_model_and_plan_mismatch_is_retained():
    body = delta_request()
    result = result_for(body)
    result["findings"].append(
        {
            "check_key": "completion-delta",
            "kind": "MISMATCH",
            "severity": "BLOCKING",
            "message": "완료 결과가 계획의 완료 기준과 다릅니다",
            "sources": [],
            "evidence_ids": [],
            "suggested_action": "완료 결과를 확인하세요",
        }
    )
    provider = Provider(result)
    response = await analyze(
        ValidateRequest.model_validate(body), LLMClient(provider), "test-model"
    )
    assert (
        json.loads(provider.calls[0].messages[1].content)["completion_delta"]
        == body["completion_delta"]
    )
    assert "For the completion-delta check" in provider.calls[0].messages[0].content
    assert response["result"]["findings"][0]["check_key"] == "completion-delta"
    assert response["result"]["findings"][0]["kind"] == "MISMATCH"


@pytest.mark.asyncio
async def test_completion_delta_can_cite_the_approved_design_and_its_evidence():
    body = delta_request()
    body["checks"][-1]["design_item_version_ids"] = ["311"]
    result = result_for(body)
    result["findings"].append(
        {
            "check_key": "completion-delta",
            "kind": "MISMATCH",
            "severity": "BLOCKING",
            "message": "완료 보고가 승인된 응답과 다릅니다",
            "design_item_id": "301",
            "design_item_version_id": "311",
            "sources": [body["evidence"][0]["source"]],
            "evidence_ids": ["evidence-login-response"],
            "suggested_action": "승인 설계에 맞춰 확인하세요",
        }
    )
    provider = Provider(result)
    response = await analyze(
        ValidateRequest.model_validate(body), LLMClient(provider), "test-model"
    )
    sent = json.loads(provider.calls[0].messages[1].content)
    assert sent["checks"][-1]["design_item_version_ids"] == ["311"]
    finding = response["result"]["findings"][0]
    assert finding["design_item_version_id"] == "311"
    assert finding["evidence_ids"] == ["evidence-login-response"]


@pytest.mark.parametrize("case", ["outside_check", "other_source", "other_evidence"])
def test_completion_delta_rejects_unrelated_design_or_mismatched_evidence(case):
    body = delta_request()
    other = copy.deepcopy(body["approved_items"][0])
    other.update(id="312", design_item_id="302", key="API-002")
    other["source"].update(document_id="202", document_version_id="212")
    body["approved_items"].append(other)
    other_evidence = copy.deepcopy(body["evidence"][0])
    other_evidence.update(
        evidence_id="evidence-other",
        design_item_version_id="312",
        source=other["source"],
    )
    body["evidence"].append(other_evidence)
    body["checks"][-1]["design_item_version_ids"] = ["311", "312"]
    result = result_for(body)
    finding = {
        "check_key": "completion-delta",
        "kind": "MISMATCH",
        "severity": "BLOCKING",
        "message": "설계와 다릅니다",
        "design_item_id": "301",
        "design_item_version_id": "311",
        "sources": [body["evidence"][0]["source"]],
        "evidence_ids": ["evidence-login-response"],
        "suggested_action": "확인",
    }
    if case == "outside_check":
        body["checks"][-1]["design_item_version_ids"] = ["312"]
    elif case == "other_source":
        finding["sources"] = [other["source"]]
        finding["evidence_ids"] = []
    else:
        finding["evidence_ids"] = ["evidence-other"]
    result["findings"] = [finding]
    with pytest.raises(ValueError, match="unknown (design reference|source|evidence)"):
        validate_output(result, ValidateRequest.model_validate(body))


@pytest.mark.parametrize(
    "case", ["missing_delta", "empty_delta", "missing_check", "wrong_kind"]
)
def test_completion_delta_and_its_check_must_be_supplied_together(case):
    body = delta_request()
    if case == "missing_delta":
        del body["completion_delta"]
    elif case == "empty_delta":
        body["completion_delta"] = []
    elif case == "missing_check":
        body["checks"].pop()
    else:
        body["checks"][-1]["kind"] = "DESIGN_ALIGNMENT"
    with pytest.raises(InvalidInput):
        validate_input(ValidateRequest.model_validate(body))


@pytest.mark.asyncio
async def test_known_mismatch_preserves_supplied_evidence():
    examples = json.loads(
        (REQUEST_FILE.parent / "responses.json").read_text(encoding="utf-8")
    )
    mismatch = next(case for case in examples["cases"] if case["name"] == "mismatch")
    provider = Provider(mismatch["response"]["result"])
    response = await analyze(
        ValidateRequest.model_validate(fixture_request()),
        LLMClient(provider),
        "test-model",
    )
    finding = response["result"]["findings"][0]
    assert finding["design_item_version_id"] == "311"
    assert finding["evidence_ids"] == ["evidence-login-response"]


@pytest.mark.asyncio
async def test_partial_does_not_claim_complete():
    body = fixture_request()
    result = result_for(body)
    key = result["checked_keys"].pop()
    result["analysis_status"] = "PARTIAL"
    result["unassessed"] = [
        {"check_key": key, "reason": "EVIDENCE_INSUFFICIENT", "message": "근거 부족"}
    ]
    output = await analyze(
        ValidateRequest.model_validate(body), LLMClient(Provider(result)), "test-model"
    )
    assert output["result"]["unassessed"][0]["check_key"] == key


@pytest.mark.asyncio
async def test_api_with_only_method_path_can_be_reported_unassessed():
    body = fixture_request()
    body["approved_items"][0]["content"] = {
        "method": "GET",
        "path": "/members",
        "description": "회원",
    }
    body["evidence"] = []
    result = result_for(body)
    result["checked_keys"].remove("design-api-login")
    result["analysis_status"] = "PARTIAL"
    result["unassessed"] = [
        {
            "check_key": "design-api-login",
            "reason": "EVIDENCE_INSUFFICIENT",
            "message": "요청·응답 필드 근거 부족",
        }
    ]
    provider = Provider(result)
    response = await analyze(
        ValidateRequest.model_validate(body), LLMClient(provider), "test-model"
    )
    assert response["result"]["analysis_status"] == "PARTIAL"
    assert (
        "fields required for this specific check"
        in provider.calls[0].messages[0].content
    )


@pytest.mark.asyncio
async def test_unresolved_policy_cannot_be_complete_with_empty_findings():
    body = fixture_request()
    body["unresolved"] = ["회원 조회 권한 정책 미정"]
    provider = Provider(result_for(body))
    with pytest.raises(LLMError) as caught:
        await analyze(
            ValidateRequest.model_validate(body), LLMClient(provider), "test-model"
        )
    assert caught.value.code == "invalid_output"
    assert caught.value.usage[0].failed


@pytest.mark.parametrize(
    "change",
    [
        {"description": "   "},
        {"method": "get"},
        {"path": "/members?secret=1"},
        {"path": "/a b"},
        {"path": "/a\u0001b"},
    ],
)
def test_api_content_v1_follows_be_validation(change):
    body = fixture_request()
    body["approved_items"][0]["content"].update(change)
    with pytest.raises(InvalidInput):
        validate_input(ValidateRequest.model_validate(body))


@pytest.mark.asyncio
async def test_related_plan_conflict_can_use_plan_as_evidence_without_document():
    body = fixture_request()
    result = result_for(body)
    result["findings"].append(
        {
            "check_key": "related-task-502",
            "kind": "CONFLICT",
            "severity": "BLOCKING",
            "message": "관련 계획과 충돌",
            "related_task_id": "502",
            "sources": [],
            "evidence_ids": [],
            "suggested_action": "담당자와 확인",
        }
    )
    output = await analyze(
        ValidateRequest.model_validate(body), LLMClient(Provider(result)), "test-model"
    )
    assert output["result"]["findings"][0]["related_task_id"] == "502"


@pytest.mark.asyncio
async def test_check_cannot_cite_another_check_source():
    body = fixture_request()
    other = copy.deepcopy(body["approved_items"][0])
    other.update(id="312", design_item_id="302", key="API-002")
    other["source"].update(document_id="202", document_version_id="212")
    body["approved_items"].append(other)
    body["checks"][2]["design_item_version_ids"].append("312")
    result = result_for(body)
    result["findings"].append(
        {
            "check_key": "design-api-login",
            "kind": "MISMATCH",
            "severity": "BLOCKING",
            "message": "다른 출처 인용",
            "design_item_id": "301",
            "design_item_version_id": "311",
            "sources": [other["source"]],
            "evidence_ids": [],
            "suggested_action": "확인",
        }
    )
    with pytest.raises(LLMError) as caught:
        await analyze(
            ValidateRequest.model_validate(body),
            LLMClient(Provider(result)),
            "test-model",
        )
    assert caught.value.code == "invalid_output"


@pytest.mark.asyncio
async def test_comparison_finding_must_reference_input_object():
    body = fixture_request()
    result = result_for(body)
    result["findings"].append(
        {
            "check_key": "design-api-login",
            "kind": "MISMATCH",
            "severity": "BLOCKING",
            "message": "근거 없는 불일치",
            "sources": [],
            "evidence_ids": [],
            "suggested_action": "확인",
        }
    )
    with pytest.raises(LLMError) as caught:
        await analyze(
            ValidateRequest.model_validate(body),
            LLMClient(Provider(result)),
            "test-model",
        )
    assert caught.value.code == "invalid_output"


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "fault",
    [
        "omitted",
        "duplicated",
        "unknown_source",
        "unknown_evidence",
        "foreign_task",
        "partial_claim",
        "unproven_policy",
    ],
)
async def test_bad_model_output_fails_with_usage(fault):
    body = fixture_request()
    result = result_for(body)
    if fault == "omitted":
        result["checked_keys"].pop()
    elif fault == "duplicated":
        result["checked_keys"].append(result["checked_keys"][0])
    elif fault == "partial_claim":
        result["analysis_status"] = "PARTIAL"
    else:
        finding = {
            "check_key": "design-api-login",
            "kind": "MISMATCH",
            "severity": "BLOCKING",
            "message": "다름",
            "sources": [],
            "evidence_ids": [],
            "suggested_action": "확인",
        }
        if fault == "unknown_source":
            finding["sources"] = [
                {
                    "document_id": "other",
                    "document_version_id": "999",
                    "version_no": 1,
                    "location": "x",
                }
            ]
        if fault == "unknown_evidence":
            finding["evidence_ids"] = ["unknown"]
        if fault == "foreign_task":
            finding["related_task_id"] = "999"
        if fault == "unproven_policy":
            finding["kind"] = "MISSING_POLICY"
        result["findings"].append(finding)
    with pytest.raises(LLMError) as caught:
        await analyze(
            ValidateRequest.model_validate(body),
            LLMClient(Provider(result)),
            "test-model",
        )
    assert caught.value.code == "invalid_output"
    assert len(caught.value.usage) == 1 and caught.value.usage[0].failed


def test_invalid_check_reference_and_unknown_schema_are_rejected_before_call():
    body = fixture_request()
    body["checks"][1]["design_item_version_ids"] = ["not-in-request"]
    with pytest.raises(InvalidInput):
        validate_input(ValidateRequest.model_validate(body))
    body = fixture_request()
    body["evidence"][0]["source"]["document_version_id"] = "different"
    with pytest.raises(InvalidInput):
        validate_input(ValidateRequest.model_validate(body))


@pytest.mark.asyncio
async def test_timeout_keeps_usage():
    import asyncio

    class SlowProvider:
        async def complete(self, request, *, timeout_seconds):
            await asyncio.sleep(1)

    body = fixture_request()
    body["execution_budget_ms"] = 10
    with pytest.raises(LLMError) as caught:
        await analyze(
            ValidateRequest.model_validate(body),
            LLMClient(SlowProvider()),
            "test-model",
        )
    assert caught.value.code == "model_timeout"
    assert len(caught.value.usage) == 1 and caught.value.usage[0].failed
    body = fixture_request()
    body["approved_items"][0]["schema_version"] = 99
    with pytest.raises(InvalidInput):
        validate_input(ValidateRequest.model_validate(body))


@pytest.mark.asyncio
async def test_provider_failure_keeps_one_failed_usage_without_retry():
    body = ValidateRequest.model_validate(fixture_request())
    provider = Provider(error=ProviderError("model_unavailable", retryable=True))
    with pytest.raises(LLMError) as caught:
        await analyze(body, LLMClient(provider), "test-model")
    assert len(provider.calls) == 1
    assert len(caught.value.usage) == 1 and caught.value.usage[0].failed


def test_http_auth_input_and_output_error_mapping(monkeypatch):
    # This test exercises the validation route; tokenizer readiness has its own tests.
    monkeypatch.setattr("app.main.EmbeddingTokenCounter", lambda: None)
    settings = Settings(_env_file=None, ai_internal_token="test-token")
    provider = Provider(result_for(fixture_request()))

    @asynccontextmanager
    async def fake_client(_settings):
        yield LLMClient(provider)

    monkeypatch.setattr(validation_router, "get_validation_client", fake_client)
    app.dependency_overrides[get_settings] = lambda: settings
    try:
        with TestClient(app) as client:
            assert (
                client.post("/v1/validate", json=fixture_request()).status_code == 401
            )
            headers = {"Authorization": "Bearer test-token"}
            invalid = copy.deepcopy(fixture_request())
            invalid["checks"][1]["design_item_version_ids"] = ["missing"]
            assert (
                client.post("/v1/validate", json=invalid, headers=headers).status_code
                == 422
            )
            malformed = copy.deepcopy(fixture_request())
            malformed["evidence"][0]["text_hash"] = "not-a-hash"
            assert (
                client.post("/v1/validate", json=malformed, headers=headers).status_code
                == 422
            )
            empty_checks = copy.deepcopy(fixture_request())
            empty_checks["checks"] = []
            assert (
                client.post(
                    "/v1/validate", json=empty_checks, headers=headers
                ).status_code
                == 422
            )
            scalar = copy.deepcopy(fixture_request())
            scalar["attempt"] = "bad"
            assert (
                client.post("/v1/validate", json=scalar, headers=headers).status_code
                == 400
            )
            assert not provider.calls
            response = client.post(
                "/v1/validate", json=fixture_request(), headers=headers
            )
            assert response.status_code == 200
            assert (
                response.json()["usage"][0]["request_key"]
                == "llm-validate-601-attempt-1-call-1"
            )
            provider.result["checked_keys"].pop()
            response = client.post(
                "/v1/validate", json=fixture_request(), headers=headers
            )
            assert response.status_code == 502
            assert response.json()["usage"][0]["failed"] is True
    finally:
        app.dependency_overrides.clear()

@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("kind", "check_key", "expected_path"),
    [
        ("MISSING_INPUT", "plan-completeness", "계획 보완 검토"),
        ("STALE_REF", "plan-completeness", "참조 갱신 검토"),
        ("MISMATCH", "design-api-login", "계획 보완 또는 설계 변경 제안 검토"),
        ("CONFLICT", "related-task-502", "담당자 협의"),
        ("MISSING_POLICY", "design-api-login", "정책 협의 및 설계 변경 제안 검토"),
    ],
)
async def test_followup_proposals_use_validated_references_and_preserve_contract(
    kind, check_key, expected_path
):
    body = fixture_request()
    result = result_for(body)
    finding = {
        "check_key": check_key,
        "kind": kind,
        "severity": "BLOCKING",
        "message": "검토할 발견 내용",
        "sources": [],
        "evidence_ids": [],
        "suggested_action": "관련 내용을 검토해 주세요.",
    }
    if kind in {"MISMATCH", "MISSING_POLICY"}:
        finding.update(design_item_id="301", design_item_version_id="311")
    if kind == "MISSING_POLICY":
        body["evidence"][0]["text"] = "재시도 정책: 미정\n"
        body["evidence"][0]["locator"]["end_line"] = body["evidence"][0]["locator"][
            "start_line"
        ]
        finding.update(
            sources=[body["evidence"][0]["source"]],
            evidence_ids=["evidence-login-response"],
        )
    if kind == "CONFLICT":
        finding["related_task_id"] = "502"
    result["findings"] = [finding]
    provider = Provider(result)
    output = await analyze(
        ValidateRequest.model_validate(body), LLMClient(provider), "test-model"
    )
    actual = output["result"]["findings"][0]
    action = actual["suggested_action"]
    assert expected_path in action
    assert "AI 제안" in action and "관련 내용을 검토해 주세요" in action
    assert "현재 작업 담당자 확인 필요" in action
    assert "이유:" in action and "승인 여부는 별도 절차" in action
    assert ("관련 작업 502 담당자 확인 필요" in action) == (kind == "CONFLICT")
    assert ("연결된 설계의 담당 역할 확인 필요" in action) == (
        kind in {"MISMATCH", "MISSING_POLICY"}
    )
    for field in ("kind", "check_key", "message", "sources", "evidence_ids"):
        assert actual[field] == finding[field]
    assert "Do not invent a person, member" in provider.calls[0].messages[0].content


@pytest.mark.asyncio
async def test_unassessed_policy_does_not_generate_a_missing_policy_suggestion():
    body = fixture_request()
    result = result_for(body)
    result["checked_keys"].remove("design-api-login")
    result["analysis_status"] = "PARTIAL"
    result["unassessed"] = [
        {
            "check_key": "design-api-login",
            "reason": "EVIDENCE_INSUFFICIENT",
            "message": "근거 부족",
        }
    ]
    output = await analyze(
        ValidateRequest.model_validate(body), LLMClient(Provider(result)), "test-model"
    )
    assert output["result"]["findings"] == []
    assert output["result"]["unassessed"][0]["reason"] == "EVIDENCE_INSUFFICIENT"


@pytest.mark.asyncio
@pytest.mark.parametrize("action", ["   ", "확인\x00필요", "😀" * 2000])
async def test_followup_rejects_actions_that_cannot_be_stored(action):
    body = fixture_request()
    result = result_for(body)
    result["findings"] = [{
        "check_key": "plan-completeness", "kind": "MISSING_INPUT",
        "severity": "BLOCKING", "message": "계획 입력 부족", "sources": [],
        "evidence_ids": [], "suggested_action": action,
    }]
    with pytest.raises(LLMError) as caught:
        await analyze(ValidateRequest.model_validate(body), LLMClient(Provider(result)), "test-model")
    assert caught.value.code == "invalid_output"
