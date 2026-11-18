"""HTTP contract of /v1/design-drafts; disabled by default, fakes only."""

import json
from pathlib import Path
from unittest.mock import patch

import pytest
from fastapi.testclient import TestClient
from pydantic import BaseModel, Field

from app.core.config import Settings, get_settings
from app.core.openapi import inline_request_schema
from app.main import app
from app.services.llm_client import ProviderError
from evaluations.design_drafts.examples import (
    HEADERS,
    ExampleApp,
    build_examples,
    example_request,
    settings,
)

EXAMPLES = Path(__file__).resolve().parents[2] / "docs" / "api" / "examples"
EXAMPLES = EXAMPLES / "design-drafts"


@pytest.fixture(scope="module")
def request_body():
    return example_request()


@pytest.fixture
def api():
    with ExampleApp() as example_app:
        yield example_app


def test_route_is_disabled_by_default(request_body):
    app.dependency_overrides[get_settings] = lambda: Settings(
        _env_file=None, ai_internal_token="internal"
    )
    try:
        with patch("app.main.EmbeddingTokenCounter"), TestClient(app) as client:
            response = client.post(
                "/v1/design-drafts",
                json=request_body,
                headers={"Authorization": "Bearer internal"},
            )
    finally:
        app.dependency_overrides.clear()
    assert response.status_code == 503
    assert response.json()["error"]["code"] == "design_drafts_not_configured"


def test_internal_token_is_required(api, request_body):
    response = api.post(request_body, headers={"Authorization": "Bearer x"})
    assert response.status_code == 401
    assert response.json()["error"]["code"] == "invalid_internal_token"
    assert not api.provider.calls


def test_drafts_come_back_unapproved_with_changes_and_usage(api, request_body):
    response = api.post(request_body)
    assert response.status_code == 200
    assert response.headers["X-Request-Id"] == "design-draft-example"
    body = response.json()
    assert body["origin"] == "MODEL" and body["model"] == "gpt-6-luna"
    usage = body["usage"][0]
    assert usage["feature"] == "BEE_DRAFT" and not usage["failed"]
    assert usage["request_key"].startswith("llm-design-drafts-")
    call = api.provider.calls[0]
    assert call.context.request_key == request_body["request_key"]
    result = body["result"]
    assert result["document"] == request_body["document"]
    assert {item["status"] for item in result["items"]} == {"DRAFT"}
    assert len(result["changes"]) == len(result["items"])
    updates = [c for c in result["changes"] if c["change"] == "UPDATE"]
    approved = next(c for c in updates if c["base_design_item_version_id"] == "1101")
    assert (
        approved["before"]["description"]
        == "문서의 최종 발행은 프로젝트 리더만 수행한다."
    )
    assert approved["changed_fields"][0]["field"] == "description"
    assert "1102" in result["untouched_existing_ids"]
    # The fabricated quote from the scripted draft is never echoed.
    assert "2027년 1월" not in response.text


def test_body_limit_media_type_and_json_errors(api, request_body):
    api.use(settings(design_drafts_max_body_bytes="1024"))
    assert api.post(request_body).status_code == 413
    api.use(settings())
    wrong_type = api.client.post(
        "/v1/design-drafts",
        content=b"{}",
        headers={"Content-Type": "text/plain", **HEADERS},
    )
    assert wrong_type.status_code == 415
    assert api.post(content=b"{").status_code == 400
    semantic = api.post(
        {**request_body, "existing_items": request_body["existing_items"] * 2}
    )
    assert semantic.status_code == 422
    assert semantic.json()["error"]["code"] == "invalid_ai_input"
    assert not api.provider.calls


def test_parser_failures_are_invalid_ai_input_with_a_reason(api, request_body):
    response = api.post({**request_body, "text_hash": "0" * 64})
    assert response.status_code == 422
    error = response.json()["error"]
    assert error["code"] == "invalid_ai_input"
    assert error["details"]["reason"] == "source_hash_mismatch"
    assert not api.provider.calls


def test_model_failures_and_contradictions_keep_usage(api, request_body):
    api.provider.failure = ProviderError(
        "model_unavailable", model="gpt-6-luna", input_tokens=9, output_tokens=0
    )
    response = api.post(request_body)
    assert response.status_code == 502
    body = response.json()
    assert body["error"]["code"] == "model_unavailable"
    assert body["usage"][0]["failed"] and body["usage"][0]["input_tokens"] == 9
    api.provider.failure = ProviderError("model_timeout", retryable=True)
    assert api.post(request_body).status_code == 504
    api.provider.failure = None
    api.provider.output = {
        "items": [
            {
                "kind": "POLICY",
                "content": {"description": "x"},
                "base_design_item_version_id": "9999",
                "sources": [],
                "answer_refs": [],
                "rationale": "r",
            }
        ],
        "unresolved": [],
    }
    response = api.post(request_body)
    assert response.status_code == 502
    assert response.json()["error"]["code"] == "model_unavailable"
    assert response.json()["usage"][0]["failed"]
    assert "9999" not in response.text


def test_openapi_request_schema_is_self_contained(api):
    schema = api.client.get("/openapi.json").json()["paths"]["/v1/design-drafts"][
        "post"
    ]["requestBody"]["content"]["application/json"]["schema"]
    # Nothing points at `#/$defs/…`, which the OpenAPI document never holds.
    assert "$defs" not in schema and "#/$defs/" not in json.dumps(schema)
    items = schema["properties"]["existing_items"]["items"]
    assert "description" in items["properties"]["content"]["properties"]
    question = schema["properties"]["questions"]["items"]["properties"]["question"]
    assert "question_key" in question["properties"]
    # The answer format union is inlined branch by branch, each with its tag.
    formats = question["properties"]["answer_format"]["oneOf"]
    assert {branch["properties"]["type"]["const"] for branch in formats} == {
        "CHOICE",
        "SHORT_ANSWER",
        "ESSAY",
    }


def test_inline_request_schema_expands_references_and_keeps_siblings():
    class Inner(BaseModel):
        value: int

    class Outer(BaseModel):
        inner: Inner = Field(description="described reference")
        many: list[Inner]

    schema = inline_request_schema(Outer)
    assert "$defs" not in schema and "#/$defs/" not in json.dumps(schema)
    assert schema["properties"]["inner"]["description"] == "described reference"
    assert schema["properties"]["inner"]["properties"]["value"]["type"] == "integer"
    assert schema["properties"]["many"]["items"]["properties"]["value"]

    class Node(BaseModel):
        children: list["Node"] = []

    with pytest.raises(ValueError):
        inline_request_schema(Node)


def test_published_examples_match_the_http_contract():
    for name, payload in build_examples().items():
        published = json.loads((EXAMPLES / name).read_text(encoding="utf-8"))
        assert published == payload, name


# S15P31A108-388: the settings defaults (strings) and the proposer defaults are
# written in two places; real documents needed up to 8,825 tokens and 77 s.
def test_default_settings_match_the_measured_limits():
    from app.routers.design_drafts import require_design_draft_config
    from app.services import design_drafts

    config = require_design_draft_config(
        Settings(_env_file=None, design_drafts_enabled=True)
    )
    assert config.output_token_limit == design_drafts.DEFAULT_OUTPUT_TOKEN_LIMIT
    assert config.timeout_seconds == design_drafts.DEFAULT_TIMEOUT_SECONDS
