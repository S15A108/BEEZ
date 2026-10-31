"""Compatibility examples for the current #159 worker's AI-A02 request.

The producer fixture follows #159's buildRequest and ValidationRules, but does
not execute the Java producer; run a cross-service test after #159 is merged.
"""

import json
from contextlib import asynccontextmanager
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.core.config import Settings, get_settings
from app.main import app
from app.routers import validation as validation_router
from app.services.llm_client import LLMClient, ProviderResponse


@pytest.mark.parametrize("include_source", [True, False])
def test_159_request_accepts_partial_analysis_without_fabricated_evidence(
    monkeypatch, include_source
):
    body = json.loads(
        (
            Path(__file__).resolve().parents[2]
            / "docs/api/examples/validation/request.json"
        ).read_text(encoding="utf-8")
    )
    # #159 currently provides structured design and related plans, but no excerpts.
    body["request_key"] = "validate-601-attempt-1"
    body["unresolved"] = []
    body["evidence"] = []
    body["plan_revision"]["plan"]["unlinked"] = [{"description": "추가 작업"}]
    for plan in [body["plan_revision"], *body["related_plans"]]:
        plan["agent_token_id"] = None
        for ref in plan["plan"]["design_refs"]:
            ref["proposed_content"] = None
    item = body["approved_items"][0]
    item["content"] = {
        "method": "POST",
        "path": "/api/auth/login",
        "description": "이메일·비밀번호 로그인",
    }
    if not include_source:
        item.pop("source")
    body["checks"] = [
        {
            "key": "plan-completeness",
            "kind": "PLAN_COMPLETENESS",
            "design_item_version_ids": [],
            "related_plan_revision_ids": [],
        },
        {
            "key": "design-301",
            "kind": "DESIGN_ALIGNMENT",
            "design_item_version_ids": ["311"],
            "related_plan_revision_ids": [],
        },
        {
            "key": "related-task-502",
            "kind": "RELATED_PLAN_CONFLICT",
            "design_item_version_ids": [],
            "related_plan_revision_ids": ["512"],
        },
    ]
    result = {
        "analysis_status": "PARTIAL",
        "checked_keys": ["plan-completeness", "related-task-502"],
        "unassessed": [
            {
                "check_key": "design-301",
                "reason": "EVIDENCE_INSUFFICIENT",
                "message": "API 응답 필드 근거가 부족합니다.",
            }
        ],
        "findings": [],
    }

    class Provider:
        async def complete(self, request, *, timeout_seconds):
            return ProviderResponse(json.dumps(result), request.model, 11, 7)

    @asynccontextmanager
    async def fake_client(_settings):
        yield LLMClient(Provider())

    settings = Settings(_env_file=None, ai_internal_token="test-token")
    monkeypatch.setattr(validation_router, "get_validation_client", fake_client)
    monkeypatch.setitem(app.dependency_overrides, get_settings, lambda: settings)
    with TestClient(app) as client:
        response = client.post(
            "/v1/validate", json=body, headers={"Authorization": "Bearer test-token"}
        )
    assert response.status_code == 200, response.text
    payload = response.json()
    assert payload["result"] == result
    assert payload["usage"][0]["request_key"] == "llm-validate-601-attempt-1-call-1"
    assert payload["usage"][0]["input_tokens"] == 11
