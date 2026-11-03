"""AI-A03 HTTP contract tests with an injected fake model, never a live call."""

import json

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.core.config import Settings, get_settings
from app.routers.impact import get_impact_client, router
from app.services.llm_client import LLMClient, ProviderError, ProviderResponse


class FakeProvider:
    def __init__(self):
        self.requests = []
        self.output = {"candidates": [], "evidence_status": "SUFFICIENT"}
        self.failure = None
        self.actual_model = None

    async def complete(self, request, *, timeout_seconds):
        self.requests.append(request)
        if self.failure:
            raise self.failure
        return ProviderResponse(
            model=self.actual_model or request.model,
            content=json.dumps(self.output),
            input_tokens=12,
            output_tokens=7,
        )


@pytest.fixture(scope="module")
def api():
    app = FastAPI()
    app.include_router(router)
    provider = FakeProvider()
    settings = Settings(
        _env_file=None, ai_internal_token="test-internal", impact_llm_model="test-model"
    )
    app.dependency_overrides[get_settings] = lambda: settings
    app.dependency_overrides[get_impact_client] = lambda: LLMClient(provider)
    with TestClient(app) as client:
        yield client, provider


@pytest.fixture(autouse=True)
def reset_fake(api):
    _, provider = api
    provider.requests.clear()
    provider.output = {"candidates": [], "evidence_status": "SUFFICIENT"}
    provider.failure = None
    provider.actual_model = None


def body():
    return {
        "project_id": "101",
        "request_key": "impact-request-1",
        "proposal_id": "71",
        "changes": [
            {
                "operation": "UPDATE",
                "design_item_id": "301",
                "base_design_item_version_id": "311",
                "schema_version": 1,
                "proposed_content": {"path": "/members/{memberId}"},
            }
        ],
        "approved_items": [
            {
                "id": "311",
                "design_item_id": "301",
                "status": "APPROVED",
                "content": {"path": "/members/{userId}"},
            }
        ],
        "explicit_dependencies": [],
        "open_tasks": [
            {
                "id": "501",
                "project_id": "101",
                "assignee_member_id": "21",
                "validation": {"id": "601"},
            }
        ],
    }


def test_success_returns_candidate_and_usage_with_unique_call_key(api):
    client, provider = api
    provider.output["candidates"] = [
        {
            "task_id": "501",
            "member_id": "21",
            "design_item_id": "301",
            "validation_id": "601",
            "reason": "Check change",
            "sources": [],
        }
    ]
    headers = {"Authorization": "Bearer test-internal", "X-Request-Id": "req-test"}
    response = client.post("/v1/impact", json=body(), headers=headers)
    assert response.status_code == 200
    assert response.headers["x-request-id"] == "req-test"
    payload = response.json()
    assert payload["result"]["candidates"][0]["validation_id"] == "601"
    assert payload["usage"][0]["feature"] == "CHANGE_IMPACT"
    assert payload["usage"][0]["input_tokens"] == 12
    assert provider.requests[0].timeout_seconds == 15.0
    again = client.post("/v1/impact", json=body(), headers=headers)
    assert payload["usage"][0]["request_key"] != again.json()["usage"][0]["request_key"]


@pytest.mark.parametrize("authorization", [None, "Bearer wrong", "Basic test-internal"])
def test_rejects_unauthorized_before_call(api, authorization):
    client, provider = api
    headers = {"Authorization": authorization} if authorization else {}
    response = client.post("/v1/impact", json=body(), headers=headers)
    assert response.status_code == 401
    assert response.json()["error"]["code"] == "invalid_internal_token"
    assert not provider.requests


def test_invalid_body_is_sanitized_and_does_not_call_provider(api):
    client, provider = api
    payload = body()
    payload["open_tasks"][0]["project_id"] = "other-project"
    response = client.post(
        "/v1/impact", json=payload, headers={"Authorization": "Bearer test-internal"}
    )
    assert response.status_code == 422
    assert response.json()["error"]["code"] == "invalid_ai_input"
    assert "other-project" not in response.text
    assert not provider.requests


def test_model_error_keeps_failed_usage(api):
    client, provider = api
    provider.failure = ProviderError("model_timeout", input_tokens=3, output_tokens=1)
    response = client.post(
        "/v1/impact", json=body(), headers={"Authorization": "Bearer test-internal"}
    )
    assert response.status_code == 504
    assert response.json()["error"]["code"] == "model_timeout"
    assert response.json()["usage"][0]["failed"]


def test_unprovided_validation_rejected_and_usage_kept(api):
    client, provider = api
    provider.output["candidates"] = [
        {"validation_id": "wrong", "reason": "Check", "sources": []}
    ]
    response = client.post(
        "/v1/impact", json=body(), headers={"Authorization": "Bearer test-internal"}
    )
    assert response.status_code == 502
    assert response.json()["error"]["code"] == "model_unavailable"
    assert response.json()["usage"][0]["failed"]


def test_body_larger_than_configured_limit_is_rejected_without_provider(api):
    client, provider = api
    settings = Settings(
        _env_file=None,
        ai_internal_token="test-internal",
        impact_llm_model="test-model",
        impact_max_body_bytes="1024",
    )
    original = client.app.dependency_overrides[get_settings]
    client.app.dependency_overrides[get_settings] = lambda: settings
    try:
        payload = body()
        payload["changes"][0]["proposed_content"]["padding"] = "x" * 1200
        response = client.post(
            "/v1/impact",
            json=payload,
            headers={"Authorization": "Bearer test-internal"},
        )
        assert response.status_code == 413
        assert not provider.requests
    finally:
        client.app.dependency_overrides[get_settings] = original


def test_list_larger_than_configured_limit_is_rejected_without_provider(api):
    client, provider = api
    settings = Settings(
        _env_file=None,
        ai_internal_token="test-internal",
        impact_llm_model="test-model",
        impact_max_items_per_list="1",
    )
    original = client.app.dependency_overrides[get_settings]
    client.app.dependency_overrides[get_settings] = lambda: settings
    try:
        payload = body()
        payload["explicit_dependencies"] = [{"id": "1"}, {"id": "2"}]
        response = client.post(
            "/v1/impact",
            json=payload,
            headers={"Authorization": "Bearer test-internal"},
        )
        assert response.status_code == 422
        assert not provider.requests
    finally:
        client.app.dependency_overrides[get_settings] = original


def test_streamed_body_without_content_length_is_bounded(api):
    client, provider = api
    settings = Settings(
        _env_file=None,
        ai_internal_token="test-internal",
        impact_llm_model="test-model",
        impact_max_body_bytes="1024",
    )
    original = client.app.dependency_overrides[get_settings]
    client.app.dependency_overrides[get_settings] = lambda: settings
    try:
        response = client.post(
            "/v1/impact",
            content=iter([b"{", b"a" * 1024, b"}"]),
            headers={
                "Authorization": "Bearer test-internal",
                "Content-Type": "application/json",
            },
        )
        assert response.status_code == 413
        assert not provider.requests
    finally:
        client.app.dependency_overrides[get_settings] = original


def test_only_configured_response_alias_is_accepted(api):
    client, provider = api
    provider.actual_model = "test-model-versioned"
    settings = Settings(
        _env_file=None,
        ai_internal_token="test-internal",
        impact_llm_model="test-model",
        impact_llm_allowed_response_models="test-model-versioned",
    )
    original = client.app.dependency_overrides[get_settings]
    client.app.dependency_overrides[get_settings] = lambda: settings
    try:
        response = client.post(
            "/v1/impact", json=body(), headers={"Authorization": "Bearer test-internal"}
        )
        assert response.status_code == 200
        assert response.json()["model"] == "test-model-versioned"
        assert provider.requests[0].allowed_response_models == ("test-model-versioned",)
    finally:
        client.app.dependency_overrides[get_settings] = original
    response = client.post(
        "/v1/impact", json=body(), headers={"Authorization": "Bearer test-internal"}
    )
    assert response.status_code == 502


def test_configured_output_token_option_reaches_provider(api):
    client, provider = api
    original = client.app.dependency_overrides[get_settings]
    settings = Settings(
        _env_file=None,
        ai_internal_token="test-internal",
        impact_llm_model="test-model",
        impact_llm_output_token_limit="256",
        impact_llm_output_token_parameter="max_tokens",
    )
    client.app.dependency_overrides[get_settings] = lambda: settings
    try:
        response = client.post(
            "/v1/impact", json=body(), headers={"Authorization": "Bearer test-internal"}
        )
        assert response.status_code == 200
        assert provider.requests[0].model_options == {"max_tokens": 256}
    finally:
        client.app.dependency_overrides[get_settings] = original


@pytest.mark.parametrize(
    "limit, parameter",
    [
        ("0", "max_tokens"),
        ("4097", "max_tokens"),
        ("bad", "max_tokens"),
        ("100", "unsupported"),
    ],
)
def test_invalid_output_token_setting_is_isolated_and_does_not_call_provider(
    api, limit, parameter
):
    client, provider = api
    original = client.app.dependency_overrides[get_settings]
    settings = Settings(
        _env_file=None,
        ai_internal_token="test-internal",
        impact_llm_model="test-model",
        impact_llm_output_token_limit=limit,
        impact_llm_output_token_parameter=parameter,
    )
    client.app.dependency_overrides[get_settings] = lambda: settings
    try:
        response = client.post(
            "/v1/impact", json=body(), headers={"Authorization": "Bearer test-internal"}
        )
        assert response.status_code == 502
        assert response.json()["error"]["code"] == "model_unavailable"
        assert "usage" not in response.json()
        assert not provider.requests
    finally:
        client.app.dependency_overrides[get_settings] = original


def test_provider_settings_missing_fail_without_creating_usage():
    app = FastAPI()
    app.include_router(router)
    app.dependency_overrides[get_settings] = lambda: Settings(
        _env_file=None, ai_internal_token="test-internal"
    )
    with TestClient(app) as client:
        response = client.post(
            "/v1/impact", json=body(), headers={"Authorization": "Bearer test-internal"}
        )
    assert response.status_code == 502
    assert response.json()["error"]["code"] == "model_unavailable"
    assert "usage" not in response.json()


def test_provider_is_shared_then_closed_at_shutdown(monkeypatch):
    from app import main
    from app.routers import impact

    created = []

    class Provider(FakeProvider):
        def __init__(self, endpoint, api_key):
            super().__init__()
            self.closed = False
            created.append(self)

        async def aclose(self):
            self.closed = True

    monkeypatch.setattr(main, "EmbeddingTokenCounter", lambda: None)
    monkeypatch.setattr(impact, "OpenAICompatibleProvider", Provider)
    app = FastAPI(lifespan=main.lifespan)
    app.include_router(router)
    settings = Settings(
        _env_file=None,
        ai_internal_token="test-internal",
        impact_llm_model="test-model",
        impact_llm_endpoint="https://example.com/v1/chat/completions",
        impact_llm_api_key="test-key",
    )
    app.dependency_overrides[get_settings] = lambda: settings
    with TestClient(app) as client:
        for _ in range(2):
            response = client.post(
                "/v1/impact",
                json=body(),
                headers={"Authorization": "Bearer test-internal"},
            )
            assert response.status_code == 200
        assert len(created) == 1
        assert len(created[0].requests) == 2
        assert not created[0].closed
    assert created[0].closed


@pytest.mark.parametrize(
    "field",
    [
        "design_item_id",
        "base_design_item_version_id",
        "schema_version",
        "proposed_content",
    ],
)
def test_incomplete_change_is_rejected_without_model_call(api, field):
    client, provider = api
    payload = body()
    del payload["changes"][0][field]
    response = client.post(
        "/v1/impact", json=payload, headers={"Authorization": "Bearer test-internal"}
    )
    assert response.status_code == 422
    assert response.json()["error"]["code"] == "invalid_ai_input"
    assert not provider.requests


def test_invalid_plan_type_is_rejected_without_model_call(api):
    client, provider = api
    payload = body()
    payload["open_tasks"][0]["current_plan_revision"] = {
        "id": "511",
        "plan": {"title": True},
    }
    response = client.post(
        "/v1/impact", json=payload, headers={"Authorization": "Bearer test-internal"}
    )
    assert response.status_code == 422
    assert response.json()["error"]["code"] == "invalid_ai_input"
    assert not provider.requests


def test_configured_strict_response_format_reaches_provider(api):
    client, provider = api
    settings = Settings(
        _env_file=None,
        ai_internal_token="test-internal",
        impact_llm_model="test-model",
        impact_llm_response_format="json_schema",
    )
    original = client.app.dependency_overrides[get_settings]
    client.app.dependency_overrides[get_settings] = lambda: settings
    try:
        response = client.post(
            "/v1/impact", json=body(), headers={"Authorization": "Bearer test-internal"}
        )
        assert response.status_code == 200
        assert provider.requests[0].response_format["type"] == "json_schema"
    finally:
        client.app.dependency_overrides[get_settings] = original


def test_invalid_response_format_is_rejected_without_provider(api):
    client, provider = api
    settings = Settings(
        _env_file=None,
        ai_internal_token="test-internal",
        impact_llm_model="test-model",
        impact_llm_response_format="unknown",
    )
    original = client.app.dependency_overrides[get_settings]
    client.app.dependency_overrides[get_settings] = lambda: settings
    try:
        response = client.post(
            "/v1/impact", json=body(), headers={"Authorization": "Bearer test-internal"}
        )
        assert response.status_code == 502
        assert response.json()["error"]["code"] == "model_unavailable"
        assert not provider.requests
    finally:
        client.app.dependency_overrides[get_settings] = original


def test_create_with_existing_reference_is_rejected_without_model_call(api):
    client, provider = api
    payload = body()
    payload["changes"] = [
        {
            "operation": "CREATE",
            "kind": "API_CONTRACT",
            "schema_version": 1,
            "proposed_content": {"name": "new"},
            "design_item_id": "301",
            "base_design_item_version_id": "311",
        }
    ]
    response = client.post(
        "/v1/impact", json=payload, headers={"Authorization": "Bearer test-internal"}
    )
    assert response.status_code == 422
    assert response.json()["error"]["code"] == "invalid_ai_input"
    assert not provider.requests
