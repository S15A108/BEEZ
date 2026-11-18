"""Internal block selection API exposes only blocks from a validated snapshot."""

from hashlib import sha256

from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.core.config import Settings, get_settings
from app.routers.revision_blocks import router
from app.schemas.documents import SourceInput
from app.services.document_parsing.pipeline import parse_source, resolve_source


def source():
    text = "# 권한\n\n프로젝트는 팀장만 삭제합니다.\n"
    digest = sha256(text.encode()).hexdigest()
    return SourceInput(
        project_id="1",
        document_version_id="21",
        kind="PLAN",
        media_type="text/markdown",
        content=text,
        content_hash=digest,
        text_hash=digest,
        normalization_version="utf8-lf-v1",
        parser_profile="markdown-v1",
    )


def api():
    app = FastAPI()
    app.include_router(router)
    app.dependency_overrides[get_settings] = lambda: Settings(
        _env_file=None, ai_internal_token="test-internal"
    )
    return TestClient(app)


HEADERS = {"Authorization": "Bearer test-internal"}


def test_authorized_response_contains_exact_selectable_blocks():
    snapshot = source()
    parsed = parse_source(snapshot)
    with api() as client:
        response = client.post(
            "/v1/document-revision-blocks", json=snapshot.model_dump(), headers=HEADERS
        )
    assert response.status_code == 200
    data = response.json()
    assert data["document_version_id"] == snapshot.document_version_id
    assert data["text_hash"] == snapshot.text_hash
    assert data["parser_version"] == parsed.parser_version
    assert [item["block_id"] for item in data["blocks"]] == [
        block.block_id for block in parsed.blocks
    ]
    for item, block in zip(data["blocks"], parsed.blocks, strict=True):
        assert item["text"] == resolve_source(parsed, block.locator)
        assert item["locator"] == block.locator.model_dump(mode="json")


def test_unauthorized_and_invalid_snapshot_are_rejected():
    snapshot = source().model_dump()
    with api() as client:
        assert (
            client.post("/v1/document-revision-blocks", json=snapshot).status_code
            == 401
        )
        invalid = {**snapshot, "text_hash": "0" * 64}
        assert (
            client.post(
                "/v1/document-revision-blocks", json=invalid, headers=HEADERS
            ).status_code
            == 422
        )
        unnormalized = {
            **snapshot,
            "content": snapshot["content"].replace("\n", "\r\n"),
        }
        assert (
            client.post(
                "/v1/document-revision-blocks", json=unnormalized, headers=HEADERS
            ).status_code
            == 422
        )
        assert (
            client.post(
                "/v1/document-revision-blocks",
                content=b"x" * (2 * 1024 * 1024 + 1),
                headers=HEADERS,
            ).status_code
            == 413
        )
