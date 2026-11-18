import httpx
import pytest
from fastapi.testclient import TestClient

from app.core.config import Settings, get_settings
from app.main import app
from app.routers.embeddings import get_provider
from app.schemas.retrieval import (
    EmbeddingUsage,
    EmbeddingVector,
    ProviderEmbeddingBatch,
)
from app.services.retrieval.gms import GmsEmbeddingProvider


@pytest.fixture
def api():
    calls = []
    settings = Settings(
        _env_file=None,
        gms_key="test-gms",
        ai_internal_token="test-internal",
        gms_embedding_dimension=3,
    )

    def handler(request):
        calls.append(request)
        return httpx.Response(
            200,
            json={
                "object": "list",
                "model": "text-embedding-3-small",
                "data": [
                    {"object": "embedding", "index": 0, "embedding": [1.0, 0.0, 0.0]}
                ],
                "usage": {"prompt_tokens": 3, "total_tokens": 3},
            },
        )

    async def provider():
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            yield GmsEmbeddingProvider(client, settings.gms_key)

    app.dependency_overrides[get_settings] = lambda: settings
    app.dependency_overrides[get_provider] = provider
    with TestClient(app) as client:
        yield client, calls
    app.dependency_overrides.clear()


def request_body(**changes):
    body = {
        "project_id": "101",
        "request_key": "request-1",
        "texts": [{"key": "chunk-1", "text": "사용자 조회"}],
    }
    body.update(changes)
    return body


def test_existing_embedding_contract_returns_vectors_and_usage(api):
    client, calls = api
    response = client.post(
        "/v1/embeddings",
        json=request_body(),
        headers={"Authorization": "Bearer test-internal", "X-Request-Id": "test-129"},
    )
    assert response.status_code == 200
    body = response.json()
    assert set(body) == {"result", "origin", "model", "usage"}
    assert body["result"] == {
        "model": "text-embedding-3-small",
        "dimension": 3,
        "vectors": [{"key": "chunk-1", "embedding": [1.0, 0.0, 0.0]}],
    }
    assert body["usage"][0]["input_tokens"] == 3
    assert response.headers["x-request-id"] == "test-129"
    assert len(calls) == 1


@pytest.mark.parametrize(
    "authorization", [None, "Bearer wrong", "Basic test-internal", "Bearer test-gms"]
)
def test_internal_auth_prevents_all_provider_calls(api, authorization):
    client, calls = api
    headers = {"Authorization": authorization} if authorization else {}
    response = client.post("/v1/embeddings", json=request_body(), headers=headers)
    assert response.status_code == 401
    assert response.json()["error"]["code"] == "invalid_internal_token"
    assert not calls


@pytest.mark.parametrize(
    "changes, status",
    [
        ({"project_id": 101}, 400),
        ({"texts": []}, 400),
        ({"texts": [{"key": "a", "text": "one"}, {"key": "a", "text": "two"}]}, 422),
        ({"texts": [{"key": "a", "text": "  "}]}, 422),
        ({"dimension": 1024}, 422),
        ({"model": "text-embedding-3-large"}, 422),
        ({"texts": [{"key": "a", "text": "long " * 9000}]}, 422),
    ],
)
def test_invalid_input_does_not_spend_credits(api, changes, status):
    client, calls = api
    response = client.post(
        "/v1/embeddings",
        json=request_body(**changes),
        headers={"Authorization": "Bearer test-internal"},
    )
    assert response.status_code == status
    assert "error" in response.json()
    assert not calls


def test_upstream_error_keeps_usage_without_any_vectors(api):
    client, _ = api

    def handler(request):
        return httpx.Response(401, json={"error": "secret provider details"})

    async def provider():
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as http:
            yield GmsEmbeddingProvider(
                http, Settings(_env_file=None, gms_key="test").gms_key
            )

    app.dependency_overrides[get_provider] = provider
    response = client.post(
        "/v1/embeddings",
        json=request_body(),
        headers={"Authorization": "Bearer test-internal"},
    )
    assert response.status_code == 502
    assert response.json()["error"]["code"] == "model_unavailable"
    assert response.json()["usage"][0]["tokens_known"] is False
    assert "input_tokens" not in response.json()["usage"][0]
    assert "result" not in response.json()
    assert "secret provider details" not in response.text


def test_missing_server_internal_token_fails_closed(api):
    client, calls = api
    app.dependency_overrides[get_settings] = lambda: Settings(_env_file=None)
    response = client.post(
        "/v1/embeddings",
        json=request_body(),
        headers={"Authorization": "Bearer test-internal"},
    )
    assert response.status_code == 401
    assert not calls


@pytest.mark.parametrize("purpose", ["DOCUMENT", "QUERY"])
def test_profile_and_purpose_use_actual_profile_and_reach_provider(api, purpose):
    client, _ = api
    calls = []

    class Provider:
        async def embed(self, texts, profile, actual_purpose, request_key):
            calls.append((profile.profile_id, actual_purpose))
            return ProviderEmbeddingBatch(
                profile=profile,
                vectors=(EmbeddingVector(key=texts[0].key, embedding=(1.0, 0.0, 0.0)),),
                usage=EmbeddingUsage(
                    request_key=request_key,
                    model=profile.model,
                    input_tokens=3,
                    output_tokens=0,
                    latency_ms=1,
                    failed=False,
                    tokens_known=True,
                ),
            )

    app.dependency_overrides[get_provider] = lambda: Provider()
    response = client.post(
        "/v1/embeddings",
        json=request_body(embedding_profile_id="gms-small-3-v1", purpose=purpose),
        headers={"Authorization": "Bearer test-internal"},
    )
    assert response.status_code == 200
    assert response.json()["result"]["embedding_profile_id"] == "gms-small-3-v1"
    assert response.json()["result"]["purpose"] == purpose
    assert calls == [("gms-small-3-v1", purpose.lower())]


@pytest.mark.parametrize(
    "changes,status",
    [
        ({"embedding_profile_id": "gms-small-3-v1"}, 400),
        ({"purpose": "DOCUMENT"}, 400),
        ({"embedding_profile_id": None, "purpose": None}, 400),
        ({"embedding_profile_id": None, "purpose": "QUERY"}, 400),
        ({"embedding_profile_id": "gms-small-3-v1", "purpose": "query"}, 400),
        ({"embedding_profile_id": "wrong-profile", "purpose": "DOCUMENT"}, 422),
    ],
)
def test_invalid_profile_and_purpose_never_call_provider(api, changes, status):
    client, calls = api
    response = client.post(
        "/v1/embeddings",
        json=request_body(**changes),
        headers={"Authorization": "Bearer test-internal"},
    )
    assert response.status_code == status
    assert "result" not in response.json()
    assert not calls
