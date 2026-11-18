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


@pytest.fixture(scope="module")
def http_client():
    # The meeting route does not depend on the embedding vocabulary.
    with patch("app.main.EmbeddingTokenCounter"), TestClient(app) as test_client:
        yield test_client


@pytest.fixture
def api(monkeypatch, http_client):
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
    yield http_client, state


def test_meeting_route_calls_model_and_returns_feature_usage(api, monkeypatch):
    monkeypatch.delenv("MEETING_ANALYSIS_LLM_TIMEOUT_SECONDS", raising=False)
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
    assert state["calls"][0].timeout_seconds == 35.0


def test_summary_route_accepts_backend_snapshot_and_returns_document_change(
    api, monkeypatch
):
    monkeypatch.setenv("MEETING_ANALYSIS_LLM_TIMEOUT_SECONDS", "42.5")
    client, state = api
    body = request_body()
    body.update(
        mode="SUMMARY",
        related_tasks=[],
        documents=[
            {"document_id": "201", "document_version_id": "211", "text": "원문\n"}
        ],
    )
    state["output"].update(
        conflicts=[],
        document_changes=[
            {
                "document_id": "201",
                "base_version_id": "211",
                "locator": {"type": "text_lines", "start_line": 1, "end_line": 1},
                "before": "원문\n",
                "after": "수정 문안\n",
                "evidence_entry_ids": ["911"],
            }
        ],
    )
    response = client.post(
        "/v1/bee/meeting-analysis",
        json=body,
        headers={"Authorization": "Bearer internal"},
    )
    assert response.status_code == 200, response.text
    assert (
        response.json()["result"]["document_changes"]
        == state["output"]["document_changes"]
    )
    assert response.json()["usage"][0]["failed"] is False
    assert state["calls"][0].timeout_seconds == 42.5


@pytest.mark.parametrize("value", ["", "invalid", "0", "-1", "nan", "inf", "60", "90"])
def test_invalid_meeting_model_timeout_fails_before_calling_provider(
    api, monkeypatch, value
):
    monkeypatch.setenv("MEETING_ANALYSIS_LLM_TIMEOUT_SECONDS", value)
    client, state = api
    response = client.post(
        "/v1/bee/meeting-analysis",
        json=request_body(),
        headers={"Authorization": "Bearer internal"},
    )
    assert response.status_code == 502
    assert response.json()["error"]["code"] == "model_unavailable"
    assert "usage" not in response.json()
    assert not state["calls"]


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


@pytest.mark.parametrize("location", ["원문에 없는 위치", "지난 기준의 문서 5행"])
def test_stored_source_with_location_is_not_a_verified_citation(api, location):
    client, state = api
    body = request_body()
    body["approved_items"] = [design_source(location)]
    # The model has used the exact supplied source; its position is still unverified.
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


def test_located_stored_source_is_kept_as_context_without_citation(api):
    client, state = api
    body = request_body()
    body["approved_items"] = [design_source("3~5행")]
    response = client.post(
        "/v1/bee/meeting-analysis",
        json=body,
        headers={"Authorization": "Bearer internal"},
    )
    assert response.status_code == 200, response.text
    assert response.json()["result"]["sources"] == []
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
