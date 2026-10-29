import json
from contextlib import asynccontextmanager
from pathlib import Path
from types import SimpleNamespace

import httpx
import pytest
from pydantic import SecretStr

from app.core.config import Settings, get_settings
from app.main import app
from app.routers import validation as validation_router
from app.schemas.validation import ValidateRequest
from app.services.llm_client import LLMClient, LLMError, ProviderResponse
from app.services.validation import analyze, structured_response_format

REQUEST = (
    Path(__file__).resolve().parents[2] / "docs/api/examples/validation/request.json"
)


def request_body():
    return json.loads(REQUEST.read_text(encoding="utf-8"))


class FakeProvider:
    def __init__(self, content):
        self.content = content
        self.requests = []

    async def complete(self, request, *, timeout_seconds):
        self.requests.append(request)
        return ProviderResponse(json.dumps(self.content), request.model, 12, 5)


def test_strict_schema_requires_all_object_fields_and_null_for_optional_ids():
    envelope = structured_response_format()
    assert envelope["type"] == "json_schema"
    assert envelope["json_schema"]["strict"] is True
    schema = envelope["json_schema"]["schema"]

    def verify(value):
        if isinstance(value, list):
            for child in value:
                verify(child)
        if isinstance(value, dict):
            assert not ({"default", "minLength", "minimum", "pattern"} & value.keys())
            if value.get("type") == "object":
                assert value["additionalProperties"] is False
                assert set(value["required"]) == set(value["properties"])
            for child in value.values():
                verify(child)

    verify(schema)
    finding = schema["$defs"]["Finding"]
    assert {"type": "null"} in finding["properties"]["design_item_id"]["anyOf"]
    assert set(schema["$defs"]["Source"]["required"]) == {
        "document_id",
        "document_version_id",
        "version_no",
        "location",
    }
    assert set(schema["$defs"]["Unassessed"]["properties"]["reason"]["enum"]) == {
        "EVIDENCE_INSUFFICIENT",
        "UNSUPPORTED_CONTENT",
    }


@pytest.mark.asyncio
async def test_opt_in_sends_schema_once_and_keeps_usage():
    body = request_body()
    result = {
        "analysis_status": "COMPLETE",
        "checked_keys": [c["key"] for c in body["checks"]],
        "unassessed": [],
        "findings": [],
    }
    provider = FakeProvider(result)
    response = await analyze(
        ValidateRequest.model_validate(body),
        LLMClient(provider),
        "test-model",
        response_format="json_schema",
    )
    assert len(provider.requests) == 1
    assert provider.requests[0].response_format == structured_response_format()
    assert provider.requests[0].max_attempts == 1
    assert response["usage"][0]["input_tokens"] == 12


@pytest.mark.asyncio
async def test_schema_does_not_bypass_semantic_or_coverage_validation():
    body = request_body()
    result = {
        "analysis_status": "COMPLETE",
        "checked_keys": [],
        "unassessed": [],
        "findings": [],
    }
    provider = FakeProvider(result)
    with pytest.raises(LLMError) as caught:
        await analyze(
            ValidateRequest.model_validate(body),
            LLMClient(provider),
            "test-model",
            response_format="json_schema",
        )
    assert caught.value.code == "invalid_output"
    assert caught.value.usage[0].failed is True
    assert len(provider.requests) == 1


def test_default_provider_mode_remains_json_object():
    assert Settings(_env_file=None).validation_response_format == "json_object"


@pytest.mark.asyncio
async def test_http_setting_selects_schema_without_changing_auth(monkeypatch):
    body = request_body()
    provider = FakeProvider(
        {
            "analysis_status": "COMPLETE",
            "checked_keys": [c["key"] for c in body["checks"]],
            "unassessed": [],
            "findings": [],
        }
    )
    settings = SimpleNamespace(
        ai_internal_token=SecretStr("synthetic-token"),
        bee_default_model="test-model",
        validation_response_format="json_schema",
    )

    @asynccontextmanager
    async def fake_client(_settings):
        yield LLMClient(provider)

    monkeypatch.setattr(validation_router, "get_validation_client", fake_client)
    app.dependency_overrides[get_settings] = lambda: settings
    try:
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app), base_url="http://test"
        ) as client:
            rejected = await client.post("/v1/validate", json=body)
            assert rejected.status_code == 401
            assert not provider.requests
            response = await client.post(
                "/v1/validate",
                json=body,
                headers={"Authorization": "Bearer synthetic-token"},
            )
        assert response.status_code == 200
        assert provider.requests[0].response_format["type"] == "json_schema"
    finally:
        app.dependency_overrides.pop(get_settings, None)
