import json
from contextlib import asynccontextmanager
from unittest.mock import patch

import pytest
from fastapi.testclient import TestClient

from app.core.config import Settings, get_settings
from app.main import app
from app.routers import design_recommendations as router
from app.services.llm_client import LLMClient, ProviderError, ProviderResponse


def candidate():
    return {
        "design_item_version": {
            "id": "311",
            "design_item_id": "301",
            "key": "API-001",
            "kind": "API_CONTRACT",
            "version_no": 1,
            "status": "APPROVED",
            "schema_version": 1,
            "content": {"description": "로그인 API", "path": "/api/auth/login"},
            "source": {
                "document_id": "201",
                "document_version_id": "211",
                "version_no": 1,
                "location": "3~5행",
            },
        }
    }


def request_body(**changes):
    body = {
        "project_id": "101",
        "request_key": "request-1",
        "call_key": "globally-unique-call-1",
        "request": "로그인 화면을 만들고 싶어요",
        "task_id": "501",
        "search_status": "READY",
        "search_truncated": False,
        "mapping_complete": True,
        "execution_budget_ms": 15000,
        "candidates": [candidate()],
    }
    body.update(changes)
    return body


@pytest.fixture(scope="module")
def http_client():
    # Recommendation tests do not exercise tokenizer readiness.
    with patch("app.main.EmbeddingTokenCounter"), TestClient(app) as client:
        yield client


@pytest.fixture
def api(monkeypatch, http_client):
    state = {
        "calls": [],
        "response": {
            "recommendations": [
                {"design_item_version_id": "311", "reason": "로그인 API 계약입니다."}
            ]
        },
        "error": None,
    }
    settings = Settings(
        _env_file=None, ai_internal_token="internal", design_recommendation_enabled=True
    )

    class Provider:
        async def complete(self, request, *, timeout_seconds):
            state["calls"].append(request)
            if state["error"]:
                raise state["error"]
            return ProviderResponse(
                json.dumps(state["response"], ensure_ascii=False), request.model, 5, 3
            )

    @asynccontextmanager
    async def client(_settings):
        yield LLMClient(Provider())

    monkeypatch.setattr(router, "get_recommendation_client", client)
    monkeypatch.setitem(app.dependency_overrides, get_settings, lambda: settings)
    yield http_client, state


def post(client, body):
    return client.post(
        "/v1/design-reference-recommendations",
        json=body,
        headers={"Authorization": "Bearer internal"},
    )


def test_model_selects_only_supplied_design_and_server_preserves_its_source(api):
    client, state = api
    response = post(client, request_body(execution_budget_ms=8000))
    assert response.status_code == 200
    body = response.json()
    assert body["result"]["status"] == "RECOMMENDED"
    result = body["result"]["candidates"][0]
    assert result["design_item_version"] == candidate()["design_item_version"]
    assert result["sources"] == [candidate()["design_item_version"]["source"]]
    assert result["reason"] == "로그인 API 계약입니다."
    assert body["usage"][0]["request_key"] == "globally-unique-call-1"
    assert body["usage"][0]["feature"] == "DESIGN_RECOMMENDATION"
    assert state["calls"][0].context.task_id == "501"
    assert state["calls"][0].timeout_seconds == 8


def test_empty_search_and_truncated_empty_are_distinct_without_model(api):
    client, state = api
    empty = post(client, request_body(candidates=[]))
    incomplete = post(client, request_body(candidates=[], search_truncated=True))
    assert empty.json()["result"]["status"] == "EMPTY"
    assert incomplete.json()["result"]["status"] == "INSUFFICIENT"
    assert incomplete.json()["result"]["follow_up"]
    assert not state["calls"]


def test_unmapped_document_is_not_treated_as_zero_result_success(api):
    client, state = api
    response = post(client, request_body(candidates=[], mapping_complete=False))
    assert response.status_code == 200
    assert response.json()["result"]["status"] == "INSUFFICIENT"
    assert response.json()["result"]["follow_up"]
    assert not state["calls"]


@pytest.mark.parametrize(
    "status, http_status, error",
    [("NOT_READY", 503, "search_not_ready"), ("FAILED", 502, "search_failed")],
)
def test_search_errors_are_not_empty_success(api, status, http_status, error):
    client, state = api
    response = post(client, request_body(search_status=status, candidates=[]))
    assert response.status_code == http_status
    assert response.json()["error"]["code"] == error
    assert not state["calls"]


def test_missing_source_and_truncation_never_claim_complete(api):
    client, state = api
    item = candidate()
    item["design_item_version"].pop("source")
    missing = post(client, request_body(candidates=[item]))
    assert missing.json()["result"]["status"] == "INSUFFICIENT"
    assert not state["calls"]
    truncated = post(client, request_body(search_truncated=True))
    assert truncated.json()["result"]["status"] == "INSUFFICIENT"
    assert len(state["calls"]) == 1


@pytest.mark.parametrize(
    "output",
    [
        {"recommendations": [{"design_item_version_id": "999", "reason": "확인"}]},
        {"recommendations": [{"design_item_version_id": "311", "reason": "확인"}] * 2},
        {
            "recommendations": [
                {"design_item_version_id": "311", "reason": "확인", "sources": []}
            ]
        },
        {"recommendations": [{"design_item_version_id": "311", "reason": ""}]},
    ],
)
def test_invented_or_invalid_model_output_is_rejected_and_usage_preserved(api, output):
    client, state = api
    state["response"] = output
    response = post(client, request_body())
    assert response.status_code == 502
    assert response.json()["usage"][0]["failed"] is True
    assert response.json()["usage"][0]["request_key"] == "globally-unique-call-1"
    assert "result" not in response.json()


def test_timeout_reports_failure_and_usage(api):
    client, state = api
    state["error"] = ProviderError("model_timeout")
    response = post(client, request_body())
    assert response.status_code == 504
    assert response.json()["error"]["code"] == "model_timeout"
    assert response.json()["usage"][0]["failed"]


def test_input_and_auth_rejected_before_provider(api):
    client, state = api
    duplicate = post(client, request_body(candidates=[candidate(), candidate()]))
    assert duplicate.status_code == 400
    unapproved = candidate()
    unapproved["design_item_version"]["status"] = "DRAFT"
    assert post(client, request_body(candidates=[unapproved])).status_code == 400
    assert post(client, request_body(execution_budget_ms=0)).status_code == 400
    forbidden = client.post("/v1/design-reference-recommendations", json=request_body())
    assert forbidden.status_code == 401
    assert not state["calls"]


def test_rollout_guard_blocks_model_until_explicitly_enabled(api, monkeypatch):
    client, state = api
    monkeypatch.setitem(
        app.dependency_overrides,
        get_settings,
        lambda: Settings(_env_file=None, ai_internal_token="internal"),
    )
    response = post(client, request_body())
    assert response.status_code == 503
    assert response.json()["error"]["code"] == "recommendation_not_enabled"
    assert not state["calls"]


@pytest.mark.parametrize("location", ["", "   ", None, "__MISSING__"])
def test_missing_source_location_is_insufficient_without_model(api, location):
    client, state = api
    item = candidate()
    source = item["design_item_version"]["source"]
    if location == "__MISSING__":
        source.pop("location")
    else:
        source["location"] = location

    response = post(client, request_body(candidates=[item]))

    assert response.status_code == 200
    assert response.json()["result"]["status"] == "INSUFFICIENT"
    assert response.json()["result"]["follow_up"]
    assert response.json()["result"]["candidates"] == []
    assert not state["calls"]


def test_be_design_hash_is_preserved_and_incomplete_source_is_not_recommended(api):
    client, state = api
    usable = candidate()
    usable["design_item_version"]["content_hash"] = "sha256:" + "a" * 64
    incomplete = candidate()
    incomplete["design_item_version"]["id"] = "312"
    incomplete["design_item_version"]["design_item_id"] = "302"
    incomplete["design_item_version"]["source"]["location"] = ""
    incomplete["design_item_version"]["content_hash"] = "sha256:" + "b" * 64

    response = post(client, request_body(candidates=[usable, incomplete]))

    assert response.status_code == 200
    result = response.json()["result"]
    assert result["status"] == "INSUFFICIENT"
    assert result["follow_up"]
    assert len(result["candidates"]) == 1
    assert (
        result["candidates"][0]["design_item_version"] == usable["design_item_version"]
    )
    assert result["candidates"][0]["sources"] == [
        usable["design_item_version"]["source"]
    ]
    model_payload = json.loads(state["calls"][0].messages[1].content)
    assert [item["id"] for item in model_payload["candidates"]] == ["311"]


def test_model_cannot_select_a_candidate_without_a_usable_location(api):
    client, state = api
    incomplete = candidate()
    incomplete["design_item_version"]["id"] = "312"
    incomplete["design_item_version"]["design_item_id"] = "302"
    incomplete["design_item_version"]["source"]["location"] = ""
    state["response"] = {
        "recommendations": [{"design_item_version_id": "312", "reason": "확인"}]
    }

    response = post(client, request_body(candidates=[candidate(), incomplete]))

    assert response.status_code == 502
    assert response.json()["usage"][0]["failed"] is True
    assert "result" not in response.json()


def test_unsupported_candidate_fields_still_fail_before_model(api):
    client, state = api
    item = candidate()
    item["design_item_version"]["unexpected"] = "not part of the contract"

    response = post(client, request_body(candidates=[item]))

    assert response.status_code == 400
    assert not state["calls"]
