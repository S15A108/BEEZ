"""Build the published #134 HTTP examples through the real route with fakes only."""

import asyncio
import json
import re
from contextlib import asynccontextmanager
from unittest.mock import patch
from uuid import UUID

from fastapi.testclient import TestClient

from app.core.config import Settings, get_settings
from app.main import app
from app.routers import design_drafts as route
from app.services.llm_client import LLMClient, ProviderError, ProviderResponse
from evaluations.design_drafts.cases import build_request, scripted_draft

TOKEN = "example-internal-token"
HEADERS = {"Authorization": f"Bearer {TOKEN}", "X-Request-Id": "design-draft-example"}
# Synthetic usage numbers; the real provider reports actual tokens.
EXAMPLE_TOKENS = (2600, 900)
# Fixed call key so the published examples are reproducible byte for byte.
EXAMPLE_CALL_UUID = UUID("00000000-0000-4000-8000-000000000134")
_SPAN = re.compile(r"L(\d+)-L(\d+)$")


class ScriptedDraftProvider:
    """Answers with the scripted draft, resolving anchors from the payload."""

    def __init__(self, failure: Exception | None = None) -> None:
        self.failure = failure
        # When set, this JSON is returned instead of the scripted draft.
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
        spans = []
        for block in payload["blocks"]:
            match = _SPAN.search(block["location"])
            spans.append((int(match.group(1)), int(match.group(2)), block["block_id"]))

        def block_for_line(line: int) -> str:
            for start, end, block_id in spans:
                if start <= line <= end:
                    return block_id
            raise KeyError(line)

        questions = [
            {"question_key": q["question_key"], "text": q["question"]}
            for q in payload["answered_questions"]
        ]

        def question_key_for(reference: str) -> str:
            if reference.startswith("q-"):
                return reference
            return next(q["question_key"] for q in questions if q["text"] == reference)

        draft = scripted_draft(block_for_line, question_key_for)
        return ProviderResponse(
            json.dumps(draft, ensure_ascii=False), request.model, *EXAMPLE_TOKENS
        )


def settings(**overrides) -> Settings:
    values = {
        "ai_internal_token": TOKEN,
        "gms_key": "fake",
        "design_drafts_enabled": True,
    }
    values.update(overrides)
    return Settings(_env_file=None, **values)


class ExampleApp:
    """A TestClient over the real app whose model client is a scripted fake."""

    def __init__(self) -> None:
        self.provider = ScriptedDraftProvider()

    def __enter__(self):
        self._patches = [
            patch("app.main.EmbeddingTokenCounter"),
            patch.object(route, "uuid4", return_value=EXAMPLE_CALL_UUID),
        ]
        for item in self._patches:
            item.start()
        self.client = TestClient(app).__enter__()
        self._original = route.get_design_draft_client
        holder = self

        @asynccontextmanager
        async def scripted_client(_settings):
            yield LLMClient(holder.provider)

        route.get_design_draft_client = scripted_client
        self.use(settings())
        return self

    def __exit__(self, *exc):
        route.get_design_draft_client = self._original
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
                "/v1/design-drafts",
                content=content,
                headers={"Content-Type": "application/json", **headers},
                **kwargs,
            )
        return self.client.post(
            "/v1/design-drafts", json=body, headers=headers, **kwargs
        )


def example_request() -> dict:
    return asyncio.run(build_request())


def build_examples() -> dict[str, object]:
    """Return {file name: JSON} for docs/api/examples/design-drafts."""
    request = example_request()
    with ExampleApp() as api:
        response = api.post(request)
        assert response.status_code == 200, response.text

        errors = {}
        errors["401_invalid_internal_token"] = api.post(
            request, headers={"Authorization": "Bearer wrong"}
        ).json()
        api.use(settings(design_drafts_enabled=False))
        errors["503_design_drafts_not_configured"] = api.post(request).json()
        api.use(settings(design_drafts_max_body_bytes="1024"))
        errors["413_request_too_large"] = api.post(request).json()
        api.use(settings())
        errors["415_unsupported_media_type"] = api.client.post(
            "/v1/design-drafts",
            content=b"{}",
            headers={"Content-Type": "text/plain", **HEADERS},
        ).json()
        errors["400_validation_failed"] = api.post(content=b"{").json()
        errors["422_invalid_ai_input_source_hash_mismatch"] = api.post(
            {**request, "text_hash": "0" * 64}
        ).json()
        api.provider.output = {
            "items": [
                {
                    "kind": "POLICY",
                    "content": {"description": "x"},
                    "base_design_item_version_id": "9999",
                    "sources": [],
                    "answer_refs": [],
                    "rationale": "없는 기준 항목",
                }
            ],
            "unresolved": [],
        }
        errors["502_model_unavailable_unknown_base_item"] = api.post(request).json()
        api.provider.output = None
        api.provider.failure = ProviderError("model_timeout", retryable=True)
        errors["504_model_timeout"] = api.post(request).json()
        api.provider.failure = None
    return {
        "request.json": request,
        "response.json": response.json(),
        "errors.json": errors,
    }
