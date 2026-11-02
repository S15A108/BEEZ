"""HTTP contract of AI-A08; no external model or persistent data is used."""

import json
from contextlib import asynccontextmanager
from unittest.mock import patch

import pytest
from fastapi.testclient import TestClient

from app.core.config import Settings, get_settings
from app.main import app
from app.routers import meeting_analysis as route
from app.services.llm_client import LLMClient, ProviderError, ProviderResponse


def request_body():
    return {
        "project_id": "101",
        "request_key": "meeting-review-1",
        "mode": "MINUTES",
        "entries": [
            {
                "id": "911",
                "meeting_id": "901",
                "kind": "TEXT",
                "text": "응답 구조는 유지하기로 합의했습니다.",
                "author_member_id": "21",
                "created_at": "2026-10-23T03:00:00Z",
            }
        ],
        "approved_items": [],
    }


@pytest.fixture
def api(monkeypatch):
    state = {
        "calls": [],
        "error": None,
        "output": {
            "text": "응답 구조를 유지하기로 했습니다.",
            "decisions": ["응답 구조 유지"],
            "unresolved": [],
            "action_items": [],
            "sources": [],
        },
    }

    class Provider:
        async def complete(self, request, *, timeout_seconds):
            state["calls"].append(request)
            if state["error"]:
                raise state["error"]
            return ProviderResponse(
                json.dumps(state["output"], ensure_ascii=False), request.model, 8, 4
            )

    @asynccontextmanager
    async def client(_settings):
        yield LLMClient(Provider())

    monkeypatch.setattr(route, "get_meeting_client", client)
    monkeypatch.setitem(
        app.dependency_overrides,
        get_settings,
        lambda: Settings(_env_file=None, ai_internal_token="internal"),
    )
    # The meeting route does not depend on the embedding vocabulary.
    with patch("app.main.EmbeddingTokenCounter"), TestClient(app) as test_client:
        yield test_client, state


def test_meeting_route_calls_model_and_returns_feature_usage(api):
    client, state = api
    response = client.post(
        "/v1/bee/meeting-analysis",
        json=request_body(),
        headers={"Authorization": "Bearer internal", "X-Request-Id": "request-7"},
    )
    assert response.status_code == 200
    assert response.headers["X-Request-Id"] == "request-7"
    result = response.json()
    assert result["result"]["decisions"] == ["응답 구조 유지"]
    assert result["usage"][0]["feature"] == "MEETING_ANALYSIS"
    assert state["calls"][0].context.request_key == "meeting-review-1"


def design_source(location):
    return {
        "id": "311",
        "design_item_id": "301",
        "key": "API-001",
        "kind": "API_CONTRACT",
        "version_no": 1,
        "status": "APPROVED",
        "schema_version": 1,
        "content": {"method": "POST", "path": "/login"},
        "source": {
            "document_id": "201",
            "document_version_id": "211",
            "version_no": 1,
            "location": location,
        },
    }


@pytest.mark.parametrize("location", ["", "  "])
def test_be_source_without_location_does_not_block_model_call(api, location):
    client, state = api
    body = request_body()
    body["approved_items"] = [design_source(location)]
    response = client.post(
        "/v1/bee/meeting-analysis",
        json=body,
        headers={"Authorization": "Bearer internal"},
    )
    assert response.status_code == 200, response.text
    assert response.json()["result"]["sources"] == []
    assert response.json()["usage"][0]["failed"] is False
    assert len(state["calls"]) == 1
    sent = json.loads(state["calls"][0].messages[1].content)
    assert sent["approved_items"][0]["source"] == body["approved_items"][0]["source"]


@pytest.mark.parametrize("location", ["", "  ", "lines 1-2"])
def test_unlocated_source_cannot_be_returned_or_fabricated(api, location):
    client, state = api
    body = request_body()
    body["approved_items"] = [design_source("")]
    state["output"]["sources"] = [design_source(location)["source"]]
    response = client.post(
        "/v1/bee/meeting-analysis",
        json=body,
        headers={"Authorization": "Bearer internal"},
    )
    assert response.status_code == 502, response.text
    assert response.json()["error"]["code"] == "model_unavailable"
    assert response.json()["usage"][0]["failed"] is True
    assert len(state["calls"]) == 1


def test_invalid_token_and_unsupported_summary_do_not_call_provider(api):
    client, state = api
    assert (
        client.post("/v1/bee/meeting-analysis", json=request_body()).status_code == 401
    )
    body = request_body()
    body["mode"] = "SUMMARY"
    response = client.post(
        "/v1/bee/meeting-analysis",
        json=body,
        headers={"Authorization": "Bearer internal"},
    )
    assert response.status_code == 422
    assert response.json()["error"]["code"] == "invalid_ai_input"
    assert not state["calls"]


def test_missing_required_field_is_400_and_does_not_call_model(api):
    client, state = api
    body = request_body()
    del body["entries"]
    response = client.post(
        "/v1/bee/meeting-analysis",
        json=body,
        headers={"Authorization": "Bearer internal"},
    )
    assert response.status_code == 400
    assert response.json()["error"]["code"] == "validation_failed"
    assert not state["calls"]


def test_invalid_model_output_and_timeout_are_distinct_and_record_usage(api):
    client, state = api
    state["output"] = {"text": "incomplete"}
    response = client.post(
        "/v1/bee/meeting-analysis",
        json=request_body(),
        headers={"Authorization": "Bearer internal"},
    )
    assert response.status_code == 502
    assert response.json()["error"]["code"] == "model_unavailable"
    assert response.json()["usage"][0]["failed"] is True
    state["error"] = ProviderError("model_timeout")
    response = client.post(
        "/v1/bee/meeting-analysis",
        json=request_body(),
        headers={"Authorization": "Bearer internal"},
    )
    assert response.status_code == 504
    assert response.json()["error"]["code"] == "model_timeout"
    assert response.json()["usage"][0]["failed"] is True
