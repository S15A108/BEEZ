"""Document review's provider URL is configured without making network calls."""

import importlib
import json
from pathlib import Path

import httpx
import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.core.config import Settings, get_settings
from app.services.llm_client import OpenAICompatibleProvider

review = importlib.import_module("app.routers.document_review")
FIXTURE = (
    Path(__file__).resolve().parents[2]
    / "backend/src/test/resources/document/review-contract-partial.json"
)
DEFAULT_ENDPOINT = "https://gms.ssafy.io/gmsapi/api.openai.com/v1/chat/completions"
OVERRIDE_ENDPOINT = "https://gms.ssafy.io/alternate/v1/chat/completions"


def test_default_endpoint_is_unchanged_without_environment_override(monkeypatch):
    monkeypatch.delenv("GMS_CHAT_ENDPOINT", raising=False)
    assert Settings(_env_file=None).gms_chat_endpoint == DEFAULT_ENDPOINT


@pytest.mark.parametrize(
    "override",
    [None, OVERRIDE_ENDPOINT, "https://gms.ssafy.io:443/v1/chat/completions"],
)
def test_endpoint_configuration_reaches_real_adapter_without_network(
    monkeypatch, override
):
    monkeypatch.delenv("GMS_CHAT_ENDPOINT", raising=False)
    if override is not None:
        monkeypatch.setenv("GMS_CHAT_ENDPOINT", override)
    settings = Settings(
        _env_file=None, gms_key="fake-key", ai_internal_token="test-internal"
    )
    actual_requests = []

    def respond(request):
        actual_requests.append(request)
        output = {
            "summary": "검토 완료",
            "suggestions": [],
            "body_reviewed": True,
            "reviewed_reference_ids": [],
        }
        return httpx.Response(
            200,
            json={
                "model": settings.bee_default_model,
                "choices": [
                    {
                        "finish_reason": "stop",
                        "message": {"content": json.dumps(output)},
                    }
                ],
                "usage": {"prompt_tokens": 10, "completion_tokens": 5},
            },
        )

    def provider(*, endpoint, api_key):
        return OpenAICompatibleProvider(
            endpoint, api_key, transport=httpx.MockTransport(respond)
        )

    monkeypatch.setattr(review, "OpenAICompatibleProvider", provider)
    app = FastAPI()
    app.include_router(review.router)
    app.dependency_overrides[get_settings] = lambda: settings
    request = json.loads(FIXTURE.read_text(encoding="utf-8"))["input"]
    with TestClient(app) as client:
        response = client.post(
            "/v1/document-reviews",
            json=request,
            headers={"Authorization": "Bearer test-internal"},
        )
    assert response.status_code == 200
    assert len(actual_requests) == 1
    assert actual_requests[0].url == httpx.URL(override or DEFAULT_ENDPOINT)
    assert actual_requests[0].headers["Authorization"] == "Bearer fake-key"


@pytest.mark.parametrize(
    "invalid_endpoint",
    [
        "http://example.com/v1/chat/completions",
        "http://localhost/v1/chat/completions",
        "https://example.com/v1/chat/completions",
        "https://gms.ssafy.io:8443/v1/chat/completions",
        "https://gms.ssafy.io.attacker.example/v1/chat/completions",
        "https://gms.ssafy.io@attacker.example/v1/chat/completions",
        " https://gms.ssafy.io/v1/chat/completions",
        "https://user:pass@gms.ssafy.io/v1/chat/completions",
        "https://gms.ssafy.io/v1/chat/completions?secret=123",
        "https://gms.ssafy.io/v1/chat/completions#fragment",
        "https://gms.ssafy.io/v1/embeddings",
        "not a URL",
    ],
)
def test_unsafe_or_invalid_endpoint_fails_without_provider_call(
    monkeypatch, invalid_endpoint
):
    settings = Settings(
        _env_file=None,
        gms_chat_endpoint=invalid_endpoint,
        gms_key="fake-key",
        ai_internal_token="test-internal",
    )
    requests = []

    def provider(*, endpoint, api_key):
        return OpenAICompatibleProvider(
            endpoint,
            api_key,
            transport=httpx.MockTransport(lambda request: requests.append(request)),
        )

    monkeypatch.setattr(review, "OpenAICompatibleProvider", provider)
    app = FastAPI()
    app.include_router(review.router)
    app.dependency_overrides[get_settings] = lambda: settings
    request = json.loads(FIXTURE.read_text(encoding="utf-8"))["input"]
    with TestClient(app) as client:
        response = client.post(
            "/v1/document-reviews",
            json=request,
            headers={"Authorization": "Bearer test-internal"},
        )
    assert response.status_code == 502
    assert response.json()["error"]["code"] == "model_unavailable"
    assert not requests
    assert "fake-key" not in response.text
    assert invalid_endpoint not in response.text
