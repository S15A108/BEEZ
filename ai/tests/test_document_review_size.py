"""The AI size check must agree with BE's canonical UTF-8 JSON limit."""

import hashlib
import json
from pathlib import Path

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.core.config import Settings, get_settings
from app.routers.document_review import get_review_client, router
from app.services.llm_client import LLMClient, ProviderResponse

LIMIT = 1_048_576
FIXTURE = (
    Path(__file__).resolve().parents[2]
    / "backend/src/test/resources/document/review-contract-partial.json"
)


class Provider:
    def __init__(self, output):
        self.output = output

    async def complete(self, request, *, timeout_seconds):
        return ProviderResponse(
            json.dumps(self.output, ensure_ascii=False), request.model, 1, 1
        )


def make_case(count):
    fixture = json.loads(FIXTURE.read_text(encoding="utf-8"))
    request = fixture["input"]
    result = fixture["response"]["result"]
    markers = [f"[unique-{i:02d}]" for i in range(count)]
    # A fixed nonblank section survives every possible subset of deletions.
    request["content"] = "ANCHOR\n" + "\n".join(markers)
    request["content_hash"] = (
        "sha256:" + hashlib.sha256(request["content"].encode()).hexdigest()
    )
    patches = [
        {
            "type": "PATCH",
            "step": "BODY",
            "explanation": "보완",
            "sources": [],
            "category": "READABILITY",
            "severity": "MINOR",
            "original_text": marker,
            "replacement_text": "",
        }
        for marker in markers
    ]
    result.update(
        content_hash=request["content_hash"],
        suggestions=patches,
        contract_version=2,
        unavailable_references=request["unavailable_references"],
    )
    output = {
        "summary": result["summary"],
        "suggestions": patches,
        "body_reviewed": True,
        "reviewed_reference_ids": [],
    }
    return request, result, output


def canonical_size(result):
    return len(
        json.dumps(
            result, ensure_ascii=False, sort_keys=True, separators=(",", ":")
        ).encode("utf-8")
    )


def review(request, output):
    app = FastAPI()
    app.include_router(router)
    app.dependency_overrides[get_settings] = lambda: Settings(
        _env_file=None, ai_internal_token="test-internal"
    )
    app.dependency_overrides[get_review_client] = lambda: LLMClient(Provider(output))
    with TestClient(app) as client:
        return client.post(
            "/v1/document-reviews",
            json=request,
            headers={"Authorization": "Bearer test-internal"},
        )


@pytest.mark.parametrize(("size", "status"), [(LIMIT, 200), (LIMIT + 1, 502)])
def test_canonical_utf8_size_limit_is_inclusive(size, status):
    request, saved, output = make_case(30)
    # Reach the result JSON limit without producing an oversized patched document.
    # The explanations are Korean UTF-8; PATCH text stays within the BE limit.
    for suggestion in output["suggestions"]:
        suggestion["explanation"] = "가" * 9_500
    remaining = size - canonical_size(saved)
    for suggestion in output["suggestions"]:
        length = min(2_500, remaining // 3)
        suggestion["replacement_text"] = "나" * length
        remaining -= length * 3
        if remaining < 3 and length < 2_500:
            suggestion["replacement_text"] += "x" * remaining
            remaining = 0
    assert remaining == 0
    assert canonical_size(saved) == size
    response = review(request, output)
    assert response.status_code == status
    if status == 502:
        assert response.json()["usage"][0]["failed"] is True


def test_korean_result_below_be_limit_is_not_counted_as_ascii_escapes():
    request, saved, output = make_case(20)
    for suggestion in output["suggestions"]:
        suggestion["explanation"] = "가" * 9_500
        suggestion["replacement_text"] = "나" * 2_000
    assert canonical_size(saved) < LIMIT
    assert (
        len(
            json.dumps(
                saved, ensure_ascii=True, sort_keys=True, separators=(",", ":")
            ).encode("utf-8")
        )
        > LIMIT
    )
    assert review(request, output).status_code == 200
