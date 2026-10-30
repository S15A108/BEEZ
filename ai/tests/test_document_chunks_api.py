import json
import threading
from concurrent.futures import ThreadPoolExecutor
from hashlib import sha256
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.core.config import Settings, get_settings
from app.main import app


class CharacterCounter:
    tokenizer_id = "test:character-v1"
    max_input_tokens = 8192

    def count(self, text):
        return len(text)


def chunk_request(content, **changes):
    normalized = (
        content.removeprefix("\ufeff").replace("\r\n", "\n").replace("\r", "\n")
    )
    body = {
        "project_id": "101",
        "document_version_id": "202",
        "request_key": "test-chunk-1",
        "kind": "PLAN",
        "media_type": "text/markdown",
        "content": content,
        "content_hash": sha256(content.encode()).hexdigest(),
        "text_hash": sha256(normalized.encode()).hexdigest(),
        "normalization_profile": "utf8-lf-v1",
        "chunking_profile_id": "md-structure-v1",
    }
    return body | changes


@pytest.mark.parametrize("kind", ["PLAN", "REQUIREMENTS", "API_SPEC", "ERD"])
def test_render_preserves_precise_unicode_spans_and_manifest(kind):
    from app.schemas.document_chunks import DocumentChunksRequest
    from app.services.retrieval.document_chunks import render_document_chunks

    content = "\ufeff# 제목 😀\r\n\r\n" + "긴 문장 😀 " * 200 + "\r\n\r\n---\r\n"
    body = DocumentChunksRequest(**chunk_request(content, kind=kind))
    result = json.loads(render_document_chunks(body, CharacterCounter()))
    assert result["origin"] == "PARSER"
    assert result["usage"] == []
    data = result["result"]
    assert data["contract_version"] == "document-chunks-v1"
    assert data["parser_version"]
    assert data["manifest"]["chunking_profile"]["profile_id"] == "md-structure-v1"
    assert data["manifest"]["expected_chunk_keys"] == [
        c["chunk_key"] for c in data["chunks"]
    ]
    source = content.removeprefix("\ufeff").replace("\r\n", "\n")
    spans = [span for chunk in data["chunks"] for span in chunk["source_spans"]]
    assert len([span for span in spans if span["locator"]["start_line"] == 3]) > 1
    for chunk in data["chunks"]:
        assert chunk["text"] == "".join(
            source[s["start_offset"] : s["end_offset"]] for s in chunk["source_spans"]
        )
        assert chunk["embedding_text"] == chunk["text"]
    assert any(item["reason"] == "thematic_break" for item in data["excluded_ranges"])
    owners = [0] * len(source)
    for span in spans:
        for index in range(span["start_offset"], span["end_offset"]):
            owners[index] += 1
    line_starts = [0] + [i + 1 for i, ch in enumerate(source) if ch == "\n"]
    for excluded in data["excluded_ranges"]:
        locator = excluded["locator"]
        start = line_starts[locator["start_line"] - 1]
        end = (
            line_starts[locator["end_line"]]
            if locator["end_line"] < len(line_starts)
            else len(source)
        )
        for index in range(start, end):
            owners[index] += 1
    assert all(owner == 1 for owner in owners)


def test_render_keys_are_stable_and_document_scoped():
    from app.schemas.document_chunks import DocumentChunksRequest
    from app.services.retrieval.document_chunks import render_document_chunks

    first = chunk_request("# 계획\n\n본문입니다.\n")
    second = first | {"document_version_id": "203"}
    result = lambda value: json.loads(
        render_document_chunks(DocumentChunksRequest(**value), CharacterCounter())
    )["result"]
    assert (
        result(first)["manifest"]["expected_chunk_keys"]
        == result(first)["manifest"]["expected_chunk_keys"]
    )
    assert (
        result(first)["manifest"]["expected_chunk_keys"]
        != result(second)["manifest"]["expected_chunk_keys"]
    )


@pytest.fixture(scope="module")
def client():
    app.dependency_overrides[get_settings] = lambda: Settings(
        _env_file=None, ai_internal_token="test-internal"
    )
    with TestClient(app) as instance:
        yield instance
    app.dependency_overrides.clear()


def test_chunk_http_contract_and_authentication(client):
    payload = chunk_request("# 계약\n\n본문 😀\n")
    url = "/v1/document-chunks"
    unauthorized = client.post(url, json=payload)
    assert unauthorized.status_code == 401
    assert unauthorized.json()["error"]["code"] == "invalid_internal_token"
    response = client.post(
        url,
        json=payload,
        headers={
            "Authorization": "Bearer test-internal",
            "X-Request-Id": "contract-214",
        },
    )
    assert response.status_code == 200
    assert response.headers["x-request-id"] == "contract-214"
    data = response.json()
    assert data["origin"] == "PARSER" and data["usage"] == []
    assert data["result"]["document_version_id"] == "202"
    assert data["result"]["chunks"][0]["source_spans"]


def test_be_prefixed_hashes_are_accepted_and_text_hash_is_echoed(client):
    payload = chunk_request("# 계약\n\n원문\n")
    bare = client.post(
        "/v1/document-chunks",
        json=payload,
        headers={"Authorization": "Bearer test-internal"},
    )
    assert bare.status_code == 200
    payload["content_hash"] = "sha256:" + payload["content_hash"]
    payload["text_hash"] = "sha256:" + payload["text_hash"]
    response = client.post(
        "/v1/document-chunks",
        json=payload,
        headers={"Authorization": "Bearer test-internal"},
    )
    assert response.status_code == 200
    assert response.json()["result"]["text_hash"] == payload["text_hash"]
    assert response.json()["result"]["manifest"]["text_hash"] == payload[
        "text_hash"
    ].removeprefix("sha256:")
    assert (
        response.json()["result"]["manifest"]["expected_chunk_keys"]
        == bare.json()["result"]["manifest"]["expected_chunk_keys"]
    )


def test_be_prefixed_incorrect_text_hash_is_rejected(client):
    payload = chunk_request("# 계약\n")
    payload["text_hash"] = "sha256:" + "0" * 64
    response = client.post(
        "/v1/document-chunks",
        json=payload,
        headers={"Authorization": "Bearer test-internal"},
    )
    assert response.status_code == 422
    assert response.json()["error"]["code"] == "invalid_ai_input"


def test_published_fixture_matches_http_contract(client):
    fixtures = Path(__file__).resolve().parents[2] / "docs/api/examples/document-chunks"
    payload = json.loads((fixtures / "request.json").read_text(encoding="utf-8"))
    expected = json.loads((fixtures / "response.json").read_text(encoding="utf-8"))
    response = client.post(
        "/v1/document-chunks",
        json=payload,
        headers={"Authorization": "Bearer test-internal"},
    )
    assert response.status_code == 200
    assert response.json() == expected


@pytest.mark.parametrize(
    "changes,status,code",
    [
        ({"project_id": 101}, 400, "validation_failed"),
        ({"unexpected": "extra"}, 400, "validation_failed"),
        ({"media_type": "text/html"}, 415, "unsupported_media_type"),
        ({"kind": "OTHER"}, 415, "unsupported_media_type"),
        ({"chunking_profile_id": "unknown"}, 422, "invalid_ai_input"),
        ({"normalization_profile": "unknown"}, 422, "invalid_ai_input"),
        ({"text_hash": "0" * 64}, 422, "invalid_ai_input"),
        ({"content": "a" * 1_048_577}, 413, "file_too_large"),
        ({"content": "a" * 100_001}, 422, "invalid_ai_input"),
        ({"content": "\ud800"}, 422, "invalid_ai_input"),
    ],
)
def test_chunk_http_rejects_invalid_input_without_echoing_source(
    client, changes, status, code
):
    payload = chunk_request("# private document\n") | changes
    response = client.post(
        "/v1/document-chunks",
        content=json.dumps(payload, ensure_ascii=True).encode(),
        headers={
            "Authorization": "Bearer test-internal",
            "Content-Type": "application/json",
        },
    )
    assert response.status_code == status
    assert response.json()["error"]["code"] == code
    assert "result" not in response.json()
    assert "private document" not in response.text
    assert response.headers["x-request-id"]


def test_chunk_http_limits_streamed_body_without_trusting_length(client):
    response = client.post(
        "/v1/document-chunks",
        content=iter([b" " * 4_194_305, b" " * 4_194_305]),
        headers={"Authorization": "Bearer test-internal", "Content-Length": "1"},
    )
    assert response.status_code == 413
    assert response.json()["error"]["code"] == "file_too_large"


def test_chunk_http_invalid_json_is_400(client):
    response = client.post(
        "/v1/document-chunks",
        content=b"{",
        headers={"Authorization": "Bearer test-internal"},
    )
    assert response.status_code == 400
    assert response.json()["error"]["code"] == "validation_failed"


def test_chunk_http_large_json_integer_is_validation_error(client):
    response = client.post(
        "/v1/document-chunks",
        content=b'{"project_id":' + b"9" * 5000 + b"}",
        headers={
            "Authorization": "Bearer test-internal",
            "X-Request-Id": "large-number",
        },
    )
    assert response.status_code == 400
    assert response.json()["error"]["code"] == "validation_failed"
    assert response.headers["x-request-id"] == "large-number"


def test_parser_complexity_limit_is_422_not_input_size_413(client):
    content = "|" + "x|" * 129 + "\n|" + "---|" * 129 + "\n|" + "y|" * 129 + "\n"
    response = client.post(
        "/v1/document-chunks",
        json=chunk_request(content),
        headers={"Authorization": "Bearer test-internal"},
    )
    assert response.status_code == 422
    assert response.json()["error"]["code"] == "invalid_ai_input"


def test_chunk_count_limit_rejects_all_results(client):
    content = "".join(f"# Section {i}\n" for i in range(1025))
    response = client.post(
        "/v1/document-chunks",
        json=chunk_request(content),
        headers={"Authorization": "Bearer test-internal"},
    )
    assert response.status_code == 422
    assert response.json()["error"]["details"]["reason"] == "chunk_limit_exceeded"
    assert "result" not in response.json()


def test_chunk_http_rejects_oversized_final_json_without_partial_result(
    client, monkeypatch
):
    from app.services.retrieval import document_chunks

    monkeypatch.setattr(document_chunks, "MAX_RESPONSE_BYTES", 64)
    response = client.post(
        "/v1/document-chunks",
        json=chunk_request("# private content\n"),
        headers={"Authorization": "Bearer test-internal"},
    )
    assert response.status_code == 422
    assert "result" not in response.json()
    assert "private content" not in response.text


def test_health_remains_available_while_chunk_worker_times_out(client, monkeypatch):
    from app.routers import document_chunks

    started = threading.Event()
    release = threading.Event()

    def blocked_worker(raw, counter):
        started.set()
        assert release.wait(5)
        return b'{"result":"late"}'

    monkeypatch.setattr(document_chunks, "_parse_and_render", blocked_worker)
    monkeypatch.setattr(document_chunks, "PROCESSING_TIMEOUT_SECONDS", 0.2)
    try:
        with ThreadPoolExecutor(max_workers=1) as pool:
            future = pool.submit(
                client.post,
                "/v1/document-chunks",
                json=chunk_request("# content\n"),
                headers={"Authorization": "Bearer test-internal"},
            )
            assert started.wait(3)
            assert client.get("/health").status_code == 200
            response = future.result(timeout=3)
            assert response.status_code == 504
            assert response.json()["error"]["code"] == "processing_timeout"
            assert "result" not in response.json()
    finally:
        release.set()
