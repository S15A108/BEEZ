"""Build the published #133 HTTP examples through the real route with fakes only."""

import json
from contextlib import asynccontextmanager
from unittest.mock import patch
from uuid import UUID

from fastapi.testclient import TestClient

from app.core.config import Settings, get_settings
from app.main import app
from app.routers import planning_questions as route
from app.services.llm_client import LLMClient, ProviderError, ProviderResponse
from evaluations.planning_questions.cases import (
    DOCUMENTS,
    block_ids_from_payload,
    request_body,
    scripted_proposal,
)

TOKEN = "example-internal-token"
HEADERS = {"Authorization": f"Bearer {TOKEN}", "X-Request-Id": "planning-example"}
# Synthetic usage numbers; the real provider reports actual tokens.
EXAMPLE_TOKENS = (1800, 420)
# Fixed call key so the published examples are reproducible byte for byte.
EXAMPLE_CALL_UUID = UUID("00000000-0000-4000-8000-000000000133")


class ScriptedProvider:
    """Answers with the scripted proposal for the document in the request."""

    def __init__(self, failure: Exception | None = None) -> None:
        self.failure = failure
        # When set, this JSON is returned instead of the scripted proposal.
        self.output: dict | None = None
        self.calls = []

    async def complete(self, request, *, timeout_seconds):
        self.calls.append(request)
        if self.failure is not None:
            raise self.failure
        if self.output is not None:
            return ProviderResponse(
                json.dumps(self.output, ensure_ascii=False),
                request.model,
                *EXAMPLE_TOKENS,
            )
        payload = json.loads(request.messages[1].content)
        version_id = payload["document"]["document_version_id"]
        name = next(
            entry["name"]
            for entry in DOCUMENTS
            if entry["document_version_id"] == version_id
        )
        proposal = scripted_proposal(name, block_ids_from_payload(payload))
        return ProviderResponse(
            json.dumps(proposal, ensure_ascii=False), request.model, *EXAMPLE_TOKENS
        )


def settings(**overrides) -> Settings:
    values = {
        "ai_internal_token": TOKEN,
        "gms_key": "fake",
        "planning_questions_enabled": True,
    }
    values.update(overrides)
    return Settings(_env_file=None, **values)


class ExampleApp:
    """A TestClient over the real app whose model client is a scripted fake."""

    def __init__(self) -> None:
        self.provider = ScriptedProvider()

    def __enter__(self):
        self._patches = [
            patch("app.main.EmbeddingTokenCounter"),
            patch.object(route, "uuid4", return_value=EXAMPLE_CALL_UUID),
        ]
        for item in self._patches:
            item.start()
        self.client = TestClient(app).__enter__()
        self._original = route.get_planning_question_client
        provider_holder = self

        @asynccontextmanager
        async def scripted_client(_settings):
            yield LLMClient(provider_holder.provider)

        route.get_planning_question_client = scripted_client
        self.use(settings())
        return self

    def __exit__(self, *exc):
        route.get_planning_question_client = self._original
        app.dependency_overrides.clear()
        self.client.__exit__(*exc)
        for item in reversed(self._patches):
            item.stop()
        return False

    def use(self, value: Settings) -> None:
        app.dependency_overrides[get_settings] = lambda: value

    def post(self, body=None, *, headers=HEADERS, content=None, **kwargs):
        if content is not None:
            return self.client.post(
                "/v1/planning-questions",
                content=content,
                headers={"Content-Type": "application/json", **headers},
                **kwargs,
            )
        return self.client.post(
            "/v1/planning-questions", json=body, headers=headers, **kwargs
        )


def build_examples() -> dict[str, object]:
    """Return {file name: JSON} for docs/api/examples/planning-questions."""
    with ExampleApp() as api:
        request = request_body("plan.md")
        response = api.post(request)
        assert response.status_code == 200, response.text
        rules_only = api.post(request_body("plan.md", mode="RULES_ONLY"))
        assert rules_only.status_code == 200, rules_only.text

        errors = {}
        errors["401_invalid_internal_token"] = api.post(
            request, headers={"Authorization": "Bearer wrong"}
        ).json()
        api.use(settings(planning_questions_enabled=False))
        errors["503_planning_questions_not_configured"] = api.post(request).json()
        api.use(settings(planning_questions_max_body_bytes="1024"))
        errors["413_request_too_large"] = api.post(request).json()
        api.use(settings())
        errors["415_unsupported_media_type"] = api.client.post(
            "/v1/planning-questions",
            content=b"{}",
            headers={"Content-Type": "text/plain", **HEADERS},
        ).json()
        errors["400_validation_failed"] = api.post(content=b"{").json()
        errors["422_invalid_ai_input_source_hash_mismatch"] = api.post(
            {**request, "text_hash": "0" * 64}
        ).json()
        api.provider.failure = ProviderError(
            "model_unavailable", model="gpt-6-luna", input_tokens=1800, output_tokens=0
        )
        errors["502_model_unavailable"] = api.post(request).json()
        api.provider.failure = ProviderError("model_timeout", retryable=True)
        errors["504_model_timeout"] = api.post(request).json()
        api.provider.failure = None
    return {
        "request.json": request,
        "response.json": response.json(),
        "rules-only-response.json": rules_only.json(),
        "errors.json": errors,
    }
