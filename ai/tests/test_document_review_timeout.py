"""The model-call limit of document review is a setting, 30 seconds by default (S15P31A108-382)."""

import json
from pathlib import Path

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.core.config import Settings, get_settings
from app.routers.document_review import get_review_client, router
from app.services.llm_client import LLMClient, ProviderResponse

FIXTURE = (
    Path(__file__).resolve().parents[2]
    / "backend/src/test/resources/document/review-contract-partial.json"
)


class RecordingProvider:
    def __init__(self, output):
        self.output = output
        self.timeouts = []

    async def complete(self, request, *, timeout_seconds):
        self.timeouts.append(timeout_seconds)
        return ProviderResponse(
            json.dumps(self.output, ensure_ascii=False), request.model, 1, 1
        )


def call(settings_kwargs):
    fixture = json.loads(FIXTURE.read_text(encoding="utf-8"))
    request = fixture["input"]
    result = fixture["response"]["result"]
    output = {
        "summary": result["summary"],
        "suggestions": [],
        "body_reviewed": True,
        "reviewed_reference_ids": [],
    }
    provider = RecordingProvider(output)
    app = FastAPI()
    app.include_router(router)
    app.dependency_overrides[get_settings] = lambda: Settings(
        _env_file=None, ai_internal_token="test-internal", **settings_kwargs
    )
    app.dependency_overrides[get_review_client] = lambda: LLMClient(provider)
    with TestClient(app) as client:
        response = client.post(
            "/v1/document-reviews",
            json=request,
            headers={"Authorization": "Bearer test-internal"},
        )
    return response, provider


def test_default_limit_is_thirty_seconds():
    assert Settings(_env_file=None).document_review_llm_timeout_seconds == "30"
    _, provider = call({})
    assert provider.timeouts == [30.0]


def test_configured_limit_reaches_the_model_call():
    _, provider = call({"document_review_llm_timeout_seconds": "45"})
    assert provider.timeouts == [45.0]


@pytest.mark.parametrize("value", ["0", "-1", "121", "abc", "nan", "inf", ""])
def test_invalid_limit_is_rejected_before_any_model_call(value):
    response, provider = call({"document_review_llm_timeout_seconds": value})
    assert response.status_code == 503
    assert response.json()["error"]["code"] == "document_review_not_configured"
    assert provider.timeouts == []
