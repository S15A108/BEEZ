"""Meeting model budgets and timeout usage must match the BE integration contract."""

import asyncio
import json
from contextlib import asynccontextmanager
from unittest.mock import patch

import pytest
from fastapi.testclient import TestClient

from app.core.config import Settings, get_settings
from app.main import app
from app.routers import meeting_analysis as route
from app.schemas.meeting_analysis import MeetingAnalysisRequest
from app.services import meeting_analysis as service
from app.services.llm_client import LLMClient, LLMError, ProviderResponse


def request_body():
    return {
        "project_id": "101",
        "request_key": "meeting-timeout-1",
        "mode": "MINUTES",
        "entries": [
            {
                "id": "911",
                "meeting_id": "901",
                "kind": "TEXT",
                "text": "재시도 기준을 논의했습니다.",
                "created_at": "2026-10-23T03:00:00Z",
            }
        ],
        "approved_items": [],
    }


class SuccessfulProvider:
    def __init__(self):
        self.call = None
        self.remaining_seconds = None

    async def complete(self, request, *, timeout_seconds):
        self.call = request
        self.remaining_seconds = timeout_seconds
        return ProviderResponse(
            json.dumps(
                {
                    "text": "재시도 기준은 논의 중입니다.",
                    "decisions": [],
                    "unresolved": ["재시도 기준"],
                    "action_items": [],
                    "sources": [],
                },
                ensure_ascii=False,
            ),
            request.model,
            10,
            5,
        )


class SlowProvider:
    def __init__(self):
        self.started = False
        self.cancelled = False

    async def complete(self, request, *, timeout_seconds):
        self.started = True
        try:
            await asyncio.sleep(1)
        except asyncio.CancelledError:
            self.cancelled = True
            raise
        raise AssertionError(
            "The provider should have been cancelled before this point"
        )


@pytest.mark.asyncio
async def test_default_meeting_model_budget_leaves_time_below_be_default():
    # The current BE AiClient default is 20 seconds; deployment overrides still
    # require an explicit contract and integration test with the actual value.
    provider = SuccessfulProvider()
    await service.analyze(
        MeetingAnalysisRequest.model_validate(request_body()),
        LLMClient(provider),
        "test-model",
    )
    assert service._MODEL_TIMEOUT_SECONDS == 15.0
    assert 0 < provider.call.timeout_seconds < 20
    assert provider.call.timeout_seconds == service._MODEL_TIMEOUT_SECONDS
    assert 0 < provider.remaining_seconds <= service._MODEL_TIMEOUT_SECONDS


@pytest.mark.asyncio
async def test_model_deadline_cancels_slow_provider_and_preserves_failed_usage(
    monkeypatch,
):
    monkeypatch.setattr(service, "_MODEL_TIMEOUT_SECONDS", 0.05)
    provider = SlowProvider()
    with pytest.raises(LLMError) as caught:
        await service.analyze(
            MeetingAnalysisRequest.model_validate(request_body()),
            LLMClient(provider),
            "test-model",
        )
    assert caught.value.code == "model_timeout"
    assert provider.started and provider.cancelled
    assert len(caught.value.usage) == 1
    usage = caught.value.usage[0]
    assert usage.feature == "MEETING_ANALYSIS"
    assert usage.failed is True
    assert usage.tokens_known is False
    assert usage.request_key.startswith("llm-meeting-")


def test_http_timeout_returns_504_and_started_call_usage(monkeypatch):
    monkeypatch.setattr(service, "_MODEL_TIMEOUT_SECONDS", 0.05)
    provider = SlowProvider()

    @asynccontextmanager
    async def client(_settings):
        yield LLMClient(provider)

    monkeypatch.setattr(route, "get_meeting_client", client)
    monkeypatch.setitem(
        app.dependency_overrides,
        get_settings,
        lambda: Settings(_env_file=None, ai_internal_token="internal"),
    )
    # This test exercises AI-A08, not startup provisioning of the tokenizer.
    with patch("app.main.EmbeddingTokenCounter"), TestClient(app) as http:
        response = http.post(
            "/v1/bee/meeting-analysis",
            json=request_body(),
            headers={"Authorization": "Bearer internal"},
        )
    assert response.status_code == 504
    assert response.json()["error"]["code"] == "model_timeout"
    assert len(response.json()["usage"]) == 1
    assert response.json()["usage"][0]["feature"] == "MEETING_ANALYSIS"
    assert response.json()["usage"][0]["failed"] is True
    assert response.json()["usage"][0]["tokens_known"] is False
    assert provider.started and provider.cancelled
