"""The AI-A07 route is opt-in, internally authenticated and read-only."""

import json
import re
from contextlib import asynccontextmanager
from pathlib import Path
from unittest.mock import patch

import pytest
from fastapi.testclient import TestClient
from test_assignment_recommendations import Provider, ranked_output, request_data

from app.core.config import Settings, get_settings
from app.main import app
from app.services.llm_client import LLMClient

PATH = "/v1/bee/assignments"
HEADERS = {"Authorization": "Bearer internal", "X-Request-Id": "assignment-test"}


@pytest.fixture
def provider():
    return Provider()


@pytest.fixture
def api(provider):
    @asynccontextmanager
    async def client(_settings):
        yield LLMClient(provider)

    app.dependency_overrides[get_settings] = lambda: Settings(
        _env_file=None,
        ai_internal_token="internal",
        assignment_recommendation_enabled=True,
    )
    try:
        with (
            patch("app.main.EmbeddingTokenCounter"),
            patch(
                "app.routers.assignment_recommendations.get_assignment_client", client
            ),
            TestClient(app) as http,
        ):
            yield http
    finally:
        app.dependency_overrides.clear()


def test_internal_token_and_opt_in_are_required(api, provider):
    assert api.post(PATH, json=request_data()).status_code == 401
    assert not provider.calls
    app.dependency_overrides[get_settings] = lambda: Settings(
        _env_file=None, ai_internal_token="internal"
    )
    response = api.post(PATH, json=request_data(), headers=HEADERS)
    assert response.status_code == 503
    assert (
        response.json()["error"]["code"] == "assignment_recommendation_not_configured"
    )
    assert not provider.calls


def test_endpoint_returns_reasons_and_alternatives_without_mutating_request(
    api, provider
):
    body = request_data()
    response = api.post(PATH, json=body, headers=HEADERS)
    assert response.status_code == 200
    assert response.headers["X-Request-Id"] == "assignment-test"
    result = response.json()
    suggestion = result["result"]["suggestions"][0]
    assert (suggestion["task_id"], suggestion["member_id"]) == ("501", "21")
    assert suggestion["reason"].strip()
    assert suggestion["alternatives"][0]["member_id"] == "22"
    assert suggestion["alternatives"][0]["reason"].strip()
    assert "assignee_member_id" not in body["tasks"][0]
    assert (
        result["origin"] == "MODEL"
        and result["usage"][0]["feature"] == "TASK_ASSIGNMENT"
    )
    assert len(provider.calls) == 1


def test_unknown_members_and_cross_project_tasks_are_rejected(api, provider):
    body = request_data()
    body["current_tasks"][0]["project_id"] = "other"
    response = api.post(PATH, json=body, headers=HEADERS)
    assert response.status_code == 422
    assert response.json()["error"]["code"] == "invalid_ai_input"
    assert not provider.calls
    provider.output = ranked_output("21", "foreign")
    response = api.post(PATH, json=request_data(), headers=HEADERS)
    assert response.status_code == 502
    assert response.json()["usage"][0]["failed"] is True
    assert "foreign" not in response.text


def test_empty_team_requires_no_model_call(api, provider):
    body = request_data()
    body["members"] = []
    response = api.post(PATH, json=body, headers=HEADERS)
    assert response.status_code == 200
    assert response.json()["origin"] == "TEMPLATE"
    assert response.json()["result"]["suggestions"] == []
    assert response.json()["result"]["unresolved"]
    assert not provider.calls


def test_body_limits_media_type_and_invalid_configuration(api, provider):
    response = api.post(
        PATH, content="{}", headers={**HEADERS, "Content-Type": "text/plain"}
    )
    assert response.status_code == 415
    app.dependency_overrides[get_settings] = lambda: Settings(
        _env_file=None,
        ai_internal_token="internal",
        assignment_recommendation_enabled=True,
        assignment_recommendation_max_body_bytes="1024",
    )
    body = request_data()
    body["tasks"][0]["current_plan_revision"]["plan"]["goal"] = "한" * 500
    response = api.post(PATH, json=body, headers=HEADERS)
    assert response.status_code == 413
    app.dependency_overrides[get_settings] = lambda: Settings(
        _env_file=None,
        ai_internal_token="internal",
        assignment_recommendation_enabled=True,
        assignment_recommendation_timeout_seconds="nan",
    )
    assert api.post(PATH, json=request_data(), headers=HEADERS).status_code == 503
    assert not provider.calls


def test_openapi_documents_internal_request_schema(api):
    operation = api.get("/openapi.json").json()["paths"][PATH]["post"]
    schema = operation["requestBody"]["content"]["application/json"]["schema"]
    assert "tasks" in schema["properties"]
    assert "members" in schema["properties"]
    assert "current_tasks" in schema["properties"]


def test_documented_request_and_response_match_route(api):
    path = (
        Path(__file__).resolve().parents[2]
        / "docs/design/role-recommendation-S15P31A108-136.md"
    )
    blocks = re.findall(
        r"```json\s*(.*?)\s*```", path.read_text(encoding="utf-8"), re.DOTALL
    )
    assert len(blocks) == 2
    request, expected = (json.loads(block) for block in blocks)
    response = api.post(PATH, json=request, headers=HEADERS)
    assert response.status_code == 200
    assert response.json()["result"] == expected["result"]
