"""HTTP boundary uses only fake selectors, never network or paid providers."""

import asyncio
import json
import logging
from concurrent.futures import ThreadPoolExecutor

import httpx
import pytest
from fastapi.testclient import TestClient

from app.core.config import Settings, get_settings
from app.main import app
from app.routers import context_selection as selection_route
from app.routers.context_selection import get_context_selector
from app.schemas.retrieval import EmbeddingUsage
from app.services.retrieval.context_selection import (
    ContextSelectionError,
    DirectMatchTruncation,
    SelectionProposal,
)


def request_body():
    return {
        "request_key": "development-context-test",
        "project_id": "p1",
        "baseline_id": "b1",
        "request": "비공개 요청 업로드",
        "task_id": None,
        "document_version_ids": ["d1"],
        "items": [
            {
                "design_item_id": "i1",
                "design_item_version_id": "v1",
                "project_id": "p1",
                "baseline_id": "b1",
                "status": "APPROVED",
                "text": "비공개 설계 업로드",
            }
        ],
        "evidence": [
            {
                "evidence_id": "e1",
                "document_version_id": "d1",
                "project_id": "p1",
                "baseline_id": "b1",
                "text": "비공개 원문",
            }
        ],
        "relations": [],
        "task_design_refs": [],
        "search_status": "COMPLETE",
    }


def usage():
    return EmbeddingUsage(
        request_key="opaque-call",
        model="fake",
        latency_ms=1,
        failed=False,
        tokens_known=True,
        input_tokens=12,
        output_tokens=0,
    )


class Selector:
    def __init__(self, failure=None):
        self.calls = []
        self.failure = failure

    async def select(self, data):
        self.calls.append(data)
        if self.failure:
            raise self.failure
        return SelectionProposal(
            design_item_version_ids=("v1",),
            evidence_ids=("e1",),
            usage=(usage(),),
            direct_match_truncation=DirectMatchTruncation(items=0, evidence=0),
        )


@pytest.fixture
def api():
    settings = Settings(
        _env_file=None,
        ai_internal_token="test-internal",
        gms_key="fake",
        context_selection_enabled=True,
        context_selection_min_similarity="0.6",
        context_selection_max_items="5",
        context_selection_max_evidence="5",
        context_selection_total_timeout_seconds="10",
    )
    selector = Selector()
    app.dependency_overrides[get_settings] = lambda: settings
    app.dependency_overrides[get_context_selector] = lambda: lambda _: selector
    with TestClient(app) as client:
        yield client, selector
    app.dependency_overrides.clear()


def test_real_json_arrays_are_converted_and_only_ids_and_usage_return(api):
    client, selector = api
    response = client.post(
        "/v1/development-context-selections",
        json=request_body(),
        headers={"Authorization": "Bearer test-internal"},
    )
    assert response.status_code == 200
    assert isinstance(selector.calls[0].items, tuple)
    body = response.json()
    assert body["result"]["design_items"] == [
        {"design_item_version_id": "v1", "reasons": ["MATCHED"]}
    ]
    assert body["result"]["evidence_ids"] == ["e1"]
    assert body["result"]["direct_match_truncation"] == {"items": 0, "evidence": 0}
    assert body["usage"][0]["input_tokens"] == 12
    assert "snapshot_binding" not in response.text
    assert "비공개" not in response.text


def test_unverified_dependency_is_exposed_without_missing_identifier(api):
    client, selector = api
    payload = request_body()
    payload["relations"] = [
        {
            "from_design_item_id": "i1",
            "from_design_item_version_id": "v1",
            "to_design_item_id": "private-missing",
            "to_design_item_version_id": "v2",
            "relation": "DEPENDS_ON",
            "origin": "EXPLICIT",
            "status": "CONFIRMED",
        }
    ]
    response = client.post(
        "/v1/development-context-selections",
        json=payload,
        headers={"Authorization": "Bearer test-internal"},
    )
    assert response.status_code == 200
    assert len(selector.calls) == 1
    assert response.json()["result"]["search_status"] == "COMPLETE"
    assert response.json()["result"]["unresolved"] == ["DEPENDENCY_UNVERIFIED"]
    assert response.json()["usage"][0]["input_tokens"] == 12
    assert "private-missing" not in response.text


@pytest.mark.parametrize("header", [None, "Bearer wrong", "Basic test-internal"])
def test_unauthorized_never_calls_provider(api, header):
    client, selector = api
    response = client.post(
        "/v1/development-context-selections",
        json=request_body(),
        headers={"Authorization": header} if header else {},
    )
    assert response.status_code == 401 and not selector.calls
    assert response.json()["error"]["code"] == "invalid_internal_token"


def test_disabled_and_missing_configuration_fail_closed(api):
    client, selector = api
    app.dependency_overrides[get_settings] = lambda: Settings(
        _env_file=None, ai_internal_token="test-internal"
    )
    response = client.post(
        "/v1/development-context-selections",
        json=request_body(),
        headers={"Authorization": "Bearer test-internal"},
    )
    assert response.status_code == 503 and not selector.calls
    assert response.json()["error"]["code"] == "selection_not_configured"
    app.dependency_overrides[get_settings] = lambda: Settings(
        _env_file=None,
        ai_internal_token="test-internal",
        context_selection_enabled=True,
    )
    response = client.post(
        "/v1/development-context-selections",
        json=request_body(),
        headers={"Authorization": "Bearer test-internal"},
    )
    assert response.status_code == 503 and not selector.calls


@pytest.mark.parametrize(
    "defect",
    ["malformed", "scope", "extra", "too_many", "oversized", "media", "duplicate"],
)
def test_invalid_transport_never_calls_selector_or_leaks_input(api, defect):
    client, selector = api
    body = request_body()
    if defect == "scope":
        body["items"][0]["project_id"] = "other"
    elif defect == "extra":
        body["min_similarity"] = 0.9
    elif defect == "too_many":
        body["items"] = body["items"] * 201
    elif defect == "duplicate":
        body["items"] = body["items"] * 2
    text = json.dumps(body, ensure_ascii=False)
    if defect == "malformed":
        text = '{"request":"비공개",'
    elif defect == "oversized":
        text = " " * 262145
    headers = {
        "Authorization": "Bearer test-internal",
        "Content-Type": "text/plain" if defect == "media" else "application/json",
    }
    response = client.post(
        "/v1/development-context-selections",
        content=text.encode("utf-8"),
        headers=headers,
    )
    assert response.status_code == (
        413 if defect == "oversized" else 415 if defect == "media" else 400
    )
    assert not selector.calls
    assert "비공개" not in response.text


@pytest.mark.parametrize(
    ("search_status", "status", "code"),
    [("NOT_READY", 503, "context_not_ready"), ("FAILED", 502, "search_failed")],
)
def test_unavailable_search_never_becomes_no_match(api, search_status, status, code):
    client, selector = api
    body = request_body()
    body["search_status"] = search_status
    response = client.post(
        "/v1/development-context-selections",
        json=body,
        headers={"Authorization": "Bearer test-internal"},
    )
    assert response.status_code == status and response.json()["error"]["code"] == code
    assert not selector.calls and "result" not in response.json()


def test_partial_or_failed_selection_keeps_usage_and_sanitizes_errors(api):
    client, selector = api
    selector.failure = ContextSelectionError("selection_incomplete", (usage(),))
    response = client.post(
        "/v1/development-context-selections",
        json=request_body(),
        headers={"Authorization": "Bearer test-internal"},
    )
    assert response.status_code == 502
    assert response.json()["error"]["code"] == "selection_incomplete"
    assert response.json()["usage"][0]["input_tokens"] == 12
    assert "result" not in response.json()
    selector.failure = RuntimeError("비공개 공급자 토큰")
    response = client.post(
        "/v1/development-context-selections",
        json=request_body(),
        headers={"Authorization": "Bearer test-internal"},
    )
    assert response.status_code == 502
    assert response.json()["error"]["code"] == "selection_failed"
    assert "비공개" not in response.text


@pytest.mark.asyncio
async def test_limit_is_enforced_during_chunked_body_without_content_length(api):
    _, selector = api
    yielded = []

    async def chunks():
        for part in (b" " * 131072, b" " * 131073, b"unread"):
            yielded.append(len(part))
            yield part

    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url="http://test"
    ) as client:
        response = await client.post(
            "/v1/development-context-selections",
            content=chunks(),
            headers={
                "Authorization": "Bearer test-internal",
                "Content-Type": "application/json",
            },
        )
    assert response.status_code == 413 and yielded == [131072, 131073]
    assert not selector.calls


@pytest.mark.asyncio
@pytest.mark.parametrize("mode", ["unauthorized", "disabled"])
async def test_auth_and_disabled_checks_do_not_read_body(api, mode):
    _, selector = api
    if mode == "disabled":
        app.dependency_overrides[get_settings] = lambda: Settings(
            _env_file=None, ai_internal_token="test-internal"
        )
    yielded = []

    async def chunks():
        yielded.append(True)
        yield b"secret body"

    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url="http://test"
    ) as client:
        response = await client.post(
            "/v1/development-context-selections",
            content=chunks(),
            headers={
                "Authorization": "Bearer wrong"
                if mode == "unauthorized"
                else "Bearer test-internal",
                "Content-Type": "application/json",
            },
        )
    assert response.status_code == (401 if mode == "unauthorized" else 503)
    assert not yielded and not selector.calls


@pytest.mark.parametrize(
    "setting",
    [
        {"gms_key": None},
        {"context_selection_min_similarity": None},
        {"context_selection_min_similarity": "nan"},
        {"context_selection_min_similarity": "1.1"},
        {"context_selection_max_items": None},
        {"context_selection_max_items": "no"},
        {"context_selection_max_evidence": "201"},
        {"context_selection_total_timeout_seconds": None},
        {"context_selection_total_timeout_seconds": "inf"},
        {"context_selection_total_timeout_seconds": "0"},
        {"context_selection_max_items": "0", "context_selection_max_evidence": "0"},
    ],
)
def test_missing_or_invalid_server_limits_are_disabled(api, setting):
    client, selector = api
    settings = {
        "ai_internal_token": "test-internal",
        "gms_key": "fake",
        "context_selection_enabled": True,
        "context_selection_min_similarity": "0.6",
        "context_selection_max_items": "5",
        "context_selection_max_evidence": "5",
        "context_selection_total_timeout_seconds": "10",
    }
    settings.update(setting)
    app.dependency_overrides[get_settings] = lambda: Settings(
        _env_file=None, **settings
    )
    response = client.post(
        "/v1/development-context-selections",
        json=request_body(),
        headers={"Authorization": "Bearer test-internal"},
    )
    assert response.status_code == 503 and not selector.calls
    assert response.json()["error"]["code"] == "selection_not_configured"


def test_zero_results_are_success_without_provider_call(api):
    client, selector = api
    body = request_body()
    body["items"] = []
    body["evidence"] = []
    response = client.post(
        "/v1/development-context-selections",
        json=body,
        headers={"Authorization": "Bearer test-internal"},
    )
    assert response.status_code == 200 and not selector.calls
    assert response.json()["result"]["unresolved"] == ["NO_MATCH"]
    assert response.json()["usage"] == []


def test_configured_real_selector_uses_fake_provider_only(api, monkeypatch):
    from app.schemas.retrieval import (
        EmbeddingProfile,
        EmbeddingVector,
        ProviderEmbeddingBatch,
    )

    client, _ = api
    app.dependency_overrides.pop(get_context_selector)
    calls = []

    class Counter:
        tokenizer_id = "fake"
        max_input_tokens = 2048

        def count(self, text):
            return len(text)

    class Provider:
        async def embed(self, texts, profile, purpose, request_key):
            calls.append(purpose)
            return ProviderEmbeddingBatch(
                profile=profile,
                vectors=tuple(
                    EmbeddingVector(key=x.key, embedding=(1.0, 0.0)) for x in texts
                ),
                usage=EmbeddingUsage(
                    request_key=request_key,
                    model="fake",
                    latency_ms=1,
                    failed=False,
                    tokens_known=True,
                    input_tokens=4,
                    output_tokens=0,
                ),
            )

    monkeypatch.setattr(selection_route, "get_counter", Counter)
    monkeypatch.setattr(selection_route, "GmsEmbeddingProvider", lambda *_: Provider())
    monkeypatch.setattr(
        selection_route,
        "embedding_profile",
        lambda _: EmbeddingProfile(
            profile_id="fixture",
            provider="fake",
            model="fake",
            dimension=2,
            tokenizer_id="fake",
            max_input_tokens=2048,
        ),
    )
    response = client.post(
        "/v1/development-context-selections",
        json=request_body(),
        headers={"Authorization": "Bearer test-internal"},
    )
    assert response.status_code == 200
    assert calls == ["query", "document"]
    assert len(response.json()["usage"]) == 2


@pytest.mark.parametrize("key", ["x" * 128, "식별 :emb: 값"])
def test_http_request_key_correlates_unique_actual_calls_and_repeated_requests(
    api, monkeypatch, key
):
    from app.schemas.retrieval import (
        EmbeddingProfile,
        EmbeddingVector,
        ProviderEmbeddingBatch,
    )

    client, _ = api
    app.dependency_overrides.pop(get_context_selector)
    calls = []

    class Counter:
        tokenizer_id = "fake"
        max_input_tokens = 2048

        def count(self, text):
            return len(text)

    class Provider:
        async def embed(self, texts, profile, purpose, request_key):
            calls.append((purpose, request_key))
            return ProviderEmbeddingBatch(
                profile=profile,
                vectors=tuple(
                    EmbeddingVector(key=x.key, embedding=(1.0, 0.0)) for x in texts
                ),
                usage=EmbeddingUsage(
                    request_key=request_key,
                    model=profile.model,
                    latency_ms=1,
                    failed=False,
                    tokens_known=True,
                    input_tokens=4,
                    output_tokens=0,
                ),
            )

    monkeypatch.setattr(selection_route, "get_counter", Counter)
    monkeypatch.setattr(selection_route, "GmsEmbeddingProvider", lambda *_: Provider())
    monkeypatch.setattr(
        selection_route,
        "embedding_profile",
        lambda _: EmbeddingProfile(
            profile_id="fixture",
            provider="fake",
            model="fake",
            dimension=2,
            tokenizer_id="fake",
            max_input_tokens=2048,
        ),
    )
    body = request_body()
    body["request_key"] = key
    headers = {"Authorization": "Bearer test-internal", "X-Request-Id": "trace-only"}
    first = client.post(
        "/v1/development-context-selections", json=body, headers=headers
    )
    second = client.post(
        "/v1/development-context-selections", json=body, headers=headers
    )
    assert first.status_code == second.status_code == 200
    assert first.headers["x-request-id"] == "trace-only"
    assert [purpose for purpose, _ in calls] == ["query", "document"] * 2
    keys = [value for _, value in calls]
    assert len(set(keys)) == 4
    assert all(value.startswith(key + ":emb:") and len(value) <= 256 for value in keys)
    assert [u["request_key"] for u in first.json()["usage"]] == keys[:2]
    assert [u["request_key"] for u in second.json()["usage"]] == keys[2:]
    # The selector factory must bind the key per request, even with overlap.
    with ThreadPoolExecutor(max_workers=2) as executor:
        futures = [
            executor.submit(
                client.post,
                "/v1/development-context-selections",
                json=body,
                headers=headers,
            )
            for _ in range(2)
        ]
        responses = [future.result(timeout=10) for future in futures]
    assert all(response.status_code == 200 for response in responses)
    keys = [value for _, value in calls]
    assert len(keys) == len(set(keys)) == 8
    assert all(value.startswith(key + ":emb:") for value in keys)
    assert {
        entry["request_key"]
        for response in responses
        for entry in response.json()["usage"]
    } == set(keys[4:])


@pytest.mark.parametrize(
    "value", [None, "", "   ", "x" * 129, 1, ["id"], "a\x00b", "a\rb", "a\nb", "a\tb"]
)
def test_missing_or_invalid_request_key_is_rejected_before_selection(api, value):
    client, selector = api
    body = request_body()
    if value is None:
        del body["request_key"]
    else:
        body["request_key"] = value
    response = client.post(
        "/v1/development-context-selections",
        json=body,
        headers={"Authorization": "Bearer test-internal"},
    )
    assert response.status_code == 400 and not selector.calls
    assert response.json()["error"]["code"] == "validation_failed"


def test_unpaired_surrogate_in_request_key_is_rejected_before_selection(api):
    client, selector = api
    body = request_body()
    body["request_key"] = "\ud800"
    response = client.post(
        "/v1/development-context-selections",
        content=json.dumps(body).encode("ascii"),
        headers={
            "Authorization": "Bearer test-internal",
            "Content-Type": "application/json",
        },
    )
    assert response.status_code == 400 and not selector.calls


@pytest.mark.parametrize("value", ["", "  ", "x" * 129, "x\ny", "x\x00y"])
def test_invalid_key_never_constructs_configured_provider(api, monkeypatch, value):
    client, _ = api
    app.dependency_overrides.pop(get_context_selector)
    constructed = []
    monkeypatch.setattr(
        selection_route, "GmsEmbeddingProvider", lambda *_: constructed.append(True)
    )
    body = request_body()
    body["request_key"] = value
    response = client.post(
        "/v1/development-context-selections",
        json=body,
        headers={"Authorization": "Bearer test-internal"},
    )
    assert response.status_code == 400 and not constructed


def test_configured_zero_limit_fails_without_embed_call(api, monkeypatch):
    from app.schemas.retrieval import EmbeddingProfile

    client, _ = api
    app.dependency_overrides.pop(get_context_selector)
    app.dependency_overrides[get_settings] = lambda: Settings(
        _env_file=None,
        ai_internal_token="test-internal",
        gms_key="fake",
        context_selection_enabled=True,
        context_selection_min_similarity="0.6",
        context_selection_max_items="0",
        context_selection_max_evidence="5",
        context_selection_total_timeout_seconds="10",
    )
    calls = []

    class Counter:
        tokenizer_id = "fake"
        max_input_tokens = 2048

        def count(self, text):
            return len(text)

    class Provider:
        async def embed(self, *args, **kwargs):
            calls.append(True)
            raise AssertionError("must not call provider")

    monkeypatch.setattr(selection_route, "get_counter", Counter)
    monkeypatch.setattr(selection_route, "GmsEmbeddingProvider", lambda *_: Provider())
    monkeypatch.setattr(
        selection_route,
        "embedding_profile",
        lambda _: EmbeddingProfile(
            profile_id="fixture",
            provider="fake",
            model="fake",
            dimension=2,
            tokenizer_id="fake",
            max_input_tokens=2048,
        ),
    )
    response = client.post(
        "/v1/development-context-selections",
        json=request_body(),
        headers={"Authorization": "Bearer test-internal"},
    )
    assert response.status_code == 502
    assert response.json()["error"]["code"] == "selection_incomplete"
    assert not calls


def test_openapi_exposes_json_array_fields_and_no_unresolved_refs(api):
    client, _ = api
    schema = client.get("/openapi.json").json()["paths"][
        "/v1/development-context-selections"
    ]["post"]["requestBody"]["content"]["application/json"]["schema"]
    assert schema["properties"]["items"]["type"] == "array"
    assert schema["properties"]["items"]["maxItems"] == 200
    assert (
        "design_item_version_id" in schema["properties"]["items"]["items"]["properties"]
    )
    assert "#/$defs/" not in json.dumps(schema)


def test_request_id_is_sanitized_in_success_and_error_responses(api):
    client, _ = api
    headers = {"Authorization": "Bearer test-internal", "X-Request-Id": "valid-131"}
    response = client.post(
        "/v1/development-context-selections", json=request_body(), headers=headers
    )
    assert response.headers["x-request-id"] == "valid-131"
    headers["X-Request-Id"] = "not valid"
    response = client.post(
        "/v1/development-context-selections", content=b"bad", headers=headers
    )
    assert response.headers["x-request-id"] != "not valid"
    assert len(response.headers["x-request-id"]) == 32


def test_total_timeout_retains_cancelled_selector_usage(api):
    client, _ = api
    app.dependency_overrides[get_settings] = lambda: Settings(
        _env_file=None,
        ai_internal_token="test-internal",
        gms_key="fake",
        context_selection_enabled=True,
        context_selection_min_similarity="0.6",
        context_selection_max_items="5",
        context_selection_max_evidence="5",
        context_selection_total_timeout_seconds="0.01",
    )

    class BlockingSelector:
        async def select(self, data):
            try:
                await asyncio.Event().wait()
            except asyncio.CancelledError as exc:
                exc.embedding_usage = (usage(),)
                raise

    app.dependency_overrides[get_context_selector] = lambda: (
        lambda _: BlockingSelector()
    )
    response = client.post(
        "/v1/development-context-selections",
        json=request_body(),
        headers={"Authorization": "Bearer test-internal"},
    )
    assert response.status_code == 504
    assert response.json()["error"]["code"] == "embedding_timeout"
    assert response.json()["usage"][0]["input_tokens"] == 12


def test_candidate_token_error_stops_before_query_call(api, monkeypatch):
    from app.schemas.retrieval import (
        EmbeddingProfile,
        EmbeddingVector,
        ProviderEmbeddingBatch,
    )

    client, _ = api
    app.dependency_overrides.pop(get_context_selector)
    calls = []

    class Counter:
        tokenizer_id = "fake"
        max_input_tokens = 16

        def count(self, text):
            return len(text)

    class Provider:
        async def embed(self, texts, profile, purpose, request_key):
            calls.append(purpose)
            return ProviderEmbeddingBatch(
                profile=profile,
                vectors=tuple(
                    EmbeddingVector(key=x.key, embedding=(1.0, 0.0)) for x in texts
                ),
                usage=EmbeddingUsage(
                    request_key=request_key,
                    model="fake",
                    latency_ms=1,
                    failed=False,
                    tokens_known=True,
                    input_tokens=4,
                    output_tokens=0,
                ),
            )

    monkeypatch.setattr(selection_route, "get_counter", Counter)
    monkeypatch.setattr(selection_route, "GmsEmbeddingProvider", lambda *_: Provider())
    monkeypatch.setattr(
        selection_route,
        "embedding_profile",
        lambda _: EmbeddingProfile(
            profile_id="fixture",
            provider="fake",
            model="fake",
            dimension=2,
            tokenizer_id="fake",
            max_input_tokens=16,
        ),
    )
    body = request_body()
    body["items"][0]["text"] = "x" * 17
    response = client.post(
        "/v1/development-context-selections",
        json=body,
        headers={"Authorization": "Bearer test-internal"},
    )
    assert response.status_code == 422
    assert response.json()["error"]["code"] == "embedding_limit_exceeded"
    assert calls == []
    assert "usage" not in response.json()


@pytest.mark.parametrize("blocked", ["query", "document"])
def test_real_selector_timeout_keeps_correlated_started_call_usage(
    api, monkeypatch, blocked
):
    from app.schemas.retrieval import (
        EmbeddingProfile,
        EmbeddingVector,
        ProviderEmbeddingBatch,
    )

    client, _ = api
    app.dependency_overrides.pop(get_context_selector)
    app.dependency_overrides[get_settings] = lambda: Settings(
        _env_file=None,
        ai_internal_token="test-internal",
        gms_key="fake",
        context_selection_enabled=True,
        context_selection_min_similarity="0.6",
        context_selection_max_items="5",
        context_selection_max_evidence="5",
        context_selection_total_timeout_seconds="0.1",
    )
    calls = []

    class Counter:
        tokenizer_id = "fake"
        max_input_tokens = 2048

        def count(self, text):
            return len(text)

    class Provider:
        async def embed(self, texts, profile, purpose, request_key):
            calls.append((purpose, request_key))
            if purpose == blocked:
                await asyncio.Event().wait()
            return ProviderEmbeddingBatch(
                profile=profile,
                vectors=tuple(
                    EmbeddingVector(key=x.key, embedding=(1.0, 0.0)) for x in texts
                ),
                usage=EmbeddingUsage(
                    request_key=request_key,
                    model=profile.model,
                    latency_ms=1,
                    failed=False,
                    tokens_known=True,
                    input_tokens=4,
                    output_tokens=0,
                ),
            )

    monkeypatch.setattr(selection_route, "get_counter", Counter)
    monkeypatch.setattr(selection_route, "GmsEmbeddingProvider", lambda *_: Provider())
    monkeypatch.setattr(
        selection_route,
        "embedding_profile",
        lambda _: EmbeddingProfile(
            profile_id="fixture",
            provider="fake",
            model="fake",
            dimension=2,
            tokenizer_id="fake",
            max_input_tokens=2048,
        ),
    )
    body = request_body()
    body["request_key"] = "x" * 128
    response = client.post(
        "/v1/development-context-selections",
        json=body,
        headers={"Authorization": "Bearer test-internal"},
    )
    assert response.status_code == 504
    usage_rows = response.json()["usage"]
    assert [entry["request_key"] for entry in usage_rows] == [key for _, key in calls]
    assert all(
        entry["request_key"].startswith(body["request_key"] + ":emb:")
        for entry in usage_rows
    )
    assert usage_rows[-1]["failed"] and not usage_rows[-1]["tokens_known"]
    if blocked == "document":
        assert len(usage_rows) == 2 and not usage_rows[0]["failed"]
    else:
        assert len(usage_rows) == 1


def test_inconsistent_selector_proposal_is_a_server_fault_not_an_input_error(api):
    client, selector = api

    class InconsistentSelector:
        async def select(self, data):
            return SelectionProposal(
                design_item_version_ids=("not-in-snapshot",),
                evidence_ids=(),
                usage=(usage(),),
            )

    app.dependency_overrides[get_context_selector] = lambda: (
        lambda _: InconsistentSelector()
    )
    response = client.post(
        "/v1/development-context-selections",
        json=request_body(),
        headers={"Authorization": "Bearer test-internal"},
    )
    assert response.status_code == 502
    assert response.json()["error"]["code"] == "invalid_selection"
    assert response.json()["usage"][0]["input_tokens"] == 12
    assert "not-in-snapshot" not in response.text
    assert not selector.calls


def test_unexpected_failure_is_logged_but_never_echoed(api, caplog):
    client, selector = api

    def failing_factory(_request_key):
        raise RuntimeError("비공개 내부 상태")

    app.dependency_overrides[get_context_selector] = lambda: failing_factory
    with caplog.at_level(logging.ERROR, logger="app.routers.context_selection"):
        response = client.post(
            "/v1/development-context-selections",
            json=request_body(),
            headers={"Authorization": "Bearer test-internal"},
        )
    assert response.status_code == 502
    assert response.json()["error"]["code"] == "selection_failed"
    assert "비공개" not in response.text
    assert not selector.calls
    assert any(
        "Unexpected development context selection failure" in record.message
        and record.exc_info is not None
        for record in caplog.records
    )
