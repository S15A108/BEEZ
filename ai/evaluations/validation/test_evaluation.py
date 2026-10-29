"""Offline checks for the synthetic evaluation harness."""

import json
from copy import deepcopy
from hashlib import sha256

import pytest

from app.schemas.validation import ValidateRequest
from app.services.llm_client import ProviderResponse
from app.services.validation import validate_input
from evaluations.validation import run as evaluation
from evaluations.validation.fixtures import fixtures


def test_all_fixtures_match_contract_and_source_hash():
    cases = fixtures()
    assert {c["name"] for c in cases} == {
        "normal",
        "mismatch",
        "policy_unresolved",
        "document_conflict",
    }
    for case in cases:
        body = ValidateRequest.model_validate(case["request"])
        validate_input(body)
        evidence = body.evidence[0]
        assert sha256(evidence.text.encode("utf-8")).hexdigest() == evidence.text_hash
        assert evidence.locator.end_line == evidence.text.count("\n")


def test_mismatch_requires_both_field_names_and_real_version():
    finding = {
        "kind": "MISMATCH",
        "severity": "BLOCKING",
        "design_item_version_id": "311",
        "message": "계획 userId는 승인된 memberId와 다릅니다.",
        "evidence_ids": [],
    }
    response = {
        "result": {
            "analysis_status": "COMPLETE",
            "findings": [finding],
            "unassessed": [],
        }
    }
    assert evaluation.assess("mismatch", response)[0]
    wrong = deepcopy(response)
    wrong["result"]["findings"][0]["message"] = "불일치"
    assert not evaluation.assess("mismatch", wrong)[0]


def test_unresolved_and_document_conflict_require_expected_evidence():
    partial = {
        "result": {
            "analysis_status": "PARTIAL",
            "findings": [],
            "unassessed": [{"reason": "EVIDENCE_INSUFFICIENT"}],
        }
    }
    assert evaluation.assess("policy_unresolved", partial)[0]
    complete = {
        "result": {"analysis_status": "COMPLETE", "findings": [], "unassessed": []}
    }
    assert not evaluation.assess("policy_unresolved", complete)[0]
    complete["result"]["findings"] = [
        {"kind": "CONFLICT", "design_item_version_id": "311", "evidence_ids": []}
    ]
    assert not evaluation.assess("document_conflict", complete)[0]
    complete["result"]["findings"][0]["evidence_ids"] = ["e-member-api"]
    assert evaluation.assess("document_conflict", complete)[0]


def test_dry_run_never_calls_provider(monkeypatch, tmp_path):
    monkeypatch.setattr("sys.argv", ["run", "--output", str(tmp_path / "out.json")])
    monkeypatch.setattr(
        evaluation,
        "configuration",
        lambda: pytest.fail("provider called during dry run"),
    )
    evaluation.main()
    assert not (tmp_path / "out.json").exists()


def test_key_must_match_endpoint_host(monkeypatch):
    monkeypatch.setattr(evaluation, "dotenv_values", lambda *args: {})
    monkeypatch.setenv(
        "VALIDATION_EVAL_ENDPOINT", "https://gms.ssafy.io/v1/chat/completions"
    )
    monkeypatch.setenv("VALIDATION_EVAL_MODEL", "test-model")
    monkeypatch.setenv("VALIDATION_EVAL_KEY_NAME", "OPENAI_API_KEY")
    monkeypatch.setenv("OPENAI_API_KEY", "secret")
    with pytest.raises(ValueError, match="do not match"):
        evaluation.configuration()


@pytest.mark.asyncio
async def test_asgi_route_uses_injected_provider_and_restores_overrides(monkeypatch):
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
            case_index = data["request_key"].rsplit("-", 1)[-1]
            if case_index == "2":
                result["analysis_status"] = "PARTIAL"
                result["checked_keys"] = ["referenced-design-311"]
                result["unassessed"] = [
                    {
                        "check_key": "plan-completeness",
                        "reason": "EVIDENCE_INSUFFICIENT",
                        "message": "정책 미정",
                    }
                ]
            elif case_index in {"1", "3"}:
                result["findings"] = [
                    {
                        "check_key": "referenced-design-311",
                        "kind": "MISMATCH",
                        "severity": "BLOCKING",
                        "message": "userId와 memberId가 서로 다릅니다",
                        "design_item_id": "301",
                        "design_item_version_id": "311",
                        "sources": [data["evidence"][0]["source"]],
                        "evidence_ids": ["e-member-api"],
                        "suggested_action": "설계를 확인하세요",
                    }
                ]
            return ProviderResponse(json.dumps(result), request.model, 100, 10)

        async def aclose(self):
            pass

    before = evaluation.app.dependency_overrides.copy()
    previous_provider = evaluation.validation_router.get_validation_client
    monkeypatch.setattr(evaluation, "OpenAICompatibleProvider", Provider)
    report = await evaluation.run(
        "https://api.openai.com/v1/chat/completions", "test-model", "unused"
    )
    assert len(report["cases"]) == 4
    assert all(
        case["http_status"] == 200 and case["passed"] for case in report["cases"]
    )
    assert report["cases"][0]["response"]["usage"][0]["input_tokens"] == 100
    assert evaluation.app.dependency_overrides == before
    assert evaluation.validation_router.get_validation_client is previous_provider
