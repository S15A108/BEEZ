"""Build the published #135 HTTP examples through the real routes with fakes only."""

import json
import time
from contextlib import asynccontextmanager
from unittest.mock import patch
from uuid import UUID

from fastapi.testclient import TestClient

from app.core.config import Settings, get_settings
from app.main import app
from app.routers import bee_chat as route
from app.services.bee_chat import VERIFY_CALL_KEY_PREFIX
from app.services.llm_client import LLMClient, ProviderError, ProviderResponse
from app.services.llm_client import client as llm_client_module
from evaluations.bee_chat.cases import (
    PROJECT,
    build_request,
    question_by_text,
    question_entry,
    scripted_answer,
    scripted_verdicts,
)
from evaluations.planning_questions.cases import PROJECT_ID

TOKEN = "example-internal-token"
HEADERS = {"Authorization": f"Bearer {TOKEN}", "X-Request-Id": "bee-chat-example"}
# Synthetic usage numbers; the real provider reports actual tokens. The judge
# call reads only the sentences and their quotes, so it is the smaller one.
EXAMPLE_TOKENS = (1800, 320)
EXAMPLE_VERIFY_TOKENS = (640, 90)
# Fixed call key so the published examples are reproducible byte for byte.
EXAMPLE_CALL_UUID = UUID("00000000-0000-4000-8000-000000000135")
CHAT_PATH = "/v1/bee/chat"
INSTANCE_PATH = "/v1/bee/instances"


class _FrozenClock:
    """`time` for the LLM client only, so `latency_ms` is 0 in every run.

    The scripted provider answers instantly, but Windows' monotonic clock
    ticks in ~16 ms steps and occasionally reports one, which would make the
    published example differ from a fresh build.
    """

    def __getattr__(self, name):
        return getattr(time, name)

    @staticmethod
    def monotonic() -> float:
        return 0.0


class ScriptedChatProvider:
    """Answers with the scripted answer and judges with the scripted verdicts.

    The two calls are told apart by their call key: the judge's starts with
    `VERIFY_CALL_KEY_PREFIX`.
    """

    def __init__(self, failure: Exception | None = None) -> None:
        self.failure = failure
        # When set, this JSON is returned instead of the scripted answer.
        self.output: dict | None = None
        # When set, this JSON is returned instead of the scripted verdicts.
        self.verdicts: dict | None = None
        self.calls = []

    async def complete(self, request, *, timeout_seconds):
        self.calls.append(request)
        if self.failure is not None:
            raise self.failure
        payload = json.loads(request.messages[1].content)
        if request.call_keys[0].startswith(VERIFY_CALL_KEY_PREFIX):
            verdicts = (
                self.verdicts
                if self.verdicts is not None
                else scripted_verdicts(payload)
            )
            return ProviderResponse(
                json.dumps(verdicts, ensure_ascii=False),
                request.model,
                *EXAMPLE_VERIFY_TOKENS,
            )
        if self.output is not None:
            return ProviderResponse(
                json.dumps(self.output, ensure_ascii=False),
                request.model,
                *EXAMPLE_TOKENS,
            )
        question = question_by_text(payload["question"])
        answer = scripted_answer(question, payload["evidence"])
        return ProviderResponse(
            json.dumps(answer, ensure_ascii=False), request.model, *EXAMPLE_TOKENS
        )


def settings(**overrides) -> Settings:
    values = {"ai_internal_token": TOKEN, "gms_key": "fake", "bee_chat_enabled": True}
    values.update(overrides)
    return Settings(_env_file=None, **values)


class ExampleApp:
    """A TestClient over the real app whose model client is a scripted fake."""

    def __init__(self) -> None:
        self.provider = ScriptedChatProvider()

    def __enter__(self):
        self._patches = [
            patch("app.main.EmbeddingTokenCounter"),
            patch.object(route, "uuid4", return_value=EXAMPLE_CALL_UUID),
            patch.object(llm_client_module, "time", _FrozenClock()),
        ]
        for item in self._patches:
            item.start()
        self.client = TestClient(app).__enter__()
        self._original = route.get_bee_chat_client
        holder = self

        @asynccontextmanager
        async def scripted_client(_settings):
            yield LLMClient(holder.provider)

        route.get_bee_chat_client = scripted_client
        self.use(settings())
        return self

    def __exit__(self, *exc):
        route.get_bee_chat_client = self._original
        app.dependency_overrides.clear()
        self.client.__exit__(*exc)
        for item in reversed(self._patches):
            item.stop()
        return False

    def use(self, value: Settings) -> None:
        app.dependency_overrides[get_settings] = lambda: value

    def post(
        self, body=None, *, path=CHAT_PATH, headers=HEADERS, content=None, **kwargs
    ):
        if content is not None:
            return self.client.post(
                path,
                content=content,
                headers={"Content-Type": "application/json", **headers},
                **kwargs,
            )
        return self.client.post(path, json=body, headers=headers, **kwargs)


def example_request(question_id: str = "api-create") -> dict:
    return build_request(question_entry(question_id))


def instance_request() -> dict:
    return {
        "project_id": PROJECT_ID,
        "request_key": f"bee-instance-{PROJECT_ID}",
        "project": PROJECT,
    }


def build_examples() -> dict[str, object]:
    """Return {file name: JSON} for docs/api/examples/bee-chat."""
    request = example_request()
    with ExampleApp() as api:
        instance = api.post(instance_request(), path=INSTANCE_PATH)
        assert instance.status_code == 200, instance.text
        response = api.post(request)
        assert response.status_code == 200, response.text
        insufficient = api.post(example_request("password-absent"))
        assert insufficient.status_code == 200, insufficient.text
        out_of_scope = api.post(example_request("out-of-scope"))
        assert out_of_scope.status_code == 200, out_of_scope.text

        errors = {}
        errors["401_invalid_internal_token"] = api.post(
            request, headers={"Authorization": "Bearer wrong"}
        ).json()
        api.use(settings(bee_chat_enabled=False))
        errors["503_bee_chat_not_configured"] = api.post(request).json()
        api.use(settings(bee_chat_max_body_bytes="1024"))
        errors["413_request_too_large"] = api.post(request).json()
        api.use(settings())
        errors["415_unsupported_media_type"] = api.client.post(
            CHAT_PATH,
            content=b"{}",
            headers={"Content-Type": "text/plain", **HEADERS},
        ).json()
        errors["400_validation_failed"] = api.post(content=b"{").json()
        outside = {
            **request,
            "retrieved_evidence": [
                {**request["retrieved_evidence"][0], "document_version_id": "999"}
            ],
        }
        errors["422_invalid_ai_input_evidence_outside_scope"] = api.post(outside).json()
        # A review scope looks at the named document versions only; approved
        # design is not mixed in, so the BE sends none.
        review = {
            **request,
            "scope": {
                "mode": "DOCUMENT_REVIEW",
                "document_version_ids": request["scope"]["document_version_ids"],
            },
        }
        errors["422_invalid_ai_input_review_scope_with_approved_items"] = api.post(
            review
        ).json()
        # The scripted model answers this one with a quote the document does
        # not hold: the whole output is rejected instead of shown.
        errors["502_model_unavailable_unverifiable_quote"] = api.post(
            example_request("req-absent")
        ).json()
        # Here the quote is real (30 MB) but the text says 50 MB: a fact the
        # citation does not support rejects the output too.
        errors["502_model_unavailable_unsupported_fact"] = api.post(
            example_request("req-contradiction")
        ).json()
        # Every token matches ("리더만" → "누구나" changes none), so only the
        # judge call catches it; both calls' usage is returned.
        errors["502_model_unavailable_contradicted_claim"] = api.post(
            example_request("plan-negation")
        ).json()
        api.provider.output = {
            "status": "ANSWERED",
            "text": "삭제는 DELETE /api/documents/{id}로 합니다 [1].",
            "citations": [{"evidence_id": "ev-none", "quote": "DELETE /api/documents"}],
            "design_refs": [],
            "task_refs": [],
            "unresolved": [],
            "next_actions": [],
        }
        errors["502_model_unavailable_unknown_evidence_id"] = api.post(request).json()
        api.provider.output = None
        api.provider.failure = ProviderError("model_timeout", retryable=True)
        errors["504_model_timeout"] = api.post(request).json()
        api.provider.failure = None
    return {
        "instance-request.json": instance_request(),
        "instance-response.json": instance.json(),
        "request.json": request,
        "response.json": response.json(),
        "insufficient-response.json": insufficient.json(),
        "out-of-scope-response.json": out_of_scope.json(),
        "errors.json": errors,
    }
