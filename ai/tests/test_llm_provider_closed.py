"""Do not report a model attempt when a provider is already closed."""

import asyncio

import httpx
import pytest

from app.services.llm_client import (
    LLMClient,
    LLMContext,
    LLMError,
    LLMMessage,
    LLMRequest,
    OpenAICompatibleProvider,
)
from app.services.llm_client.errors import ProviderNotStartedError


def request(*, attempts=1):
    return LLMRequest(
        model="test-model",
        messages=(LLMMessage(role="user", content="test prompt"),),
        context=LLMContext(project_id="1", feature="BEE_CHAT", request_key="req-1"),
        call_keys=tuple(f"call-{i}" for i in range(1, attempts + 1)),
        timeout_seconds=3,
        max_attempts=attempts,
    )


def test_closed_provider_raises_before_http_request_without_leaking_secrets():
    async def run():
        calls = []
        secret = "test-secret-not-to-expose"
        provider = OpenAICompatibleProvider(
            "https://llm.example/v1/chat/completions",
            secret,
            transport=httpx.MockTransport(lambda req: calls.append(req)),
        )
        await provider.aclose()
        with pytest.raises(ProviderNotStartedError) as caught:
            await provider.complete(request(), timeout_seconds=1)
        assert not calls
        assert secret not in str(caught.value)
        assert "test prompt" not in str(caught.value)

    asyncio.run(run())


def test_client_does_not_record_or_retry_closed_provider():
    async def run():
        calls = []
        provider = OpenAICompatibleProvider(
            "https://llm.example/v1/chat/completions",
            "test-secret",
            transport=httpx.MockTransport(lambda req: calls.append(req)),
        )
        await provider.aclose()
        with pytest.raises(LLMError) as caught:
            await LLMClient(provider).generate(request(attempts=2))
        assert caught.value.code == "model_unavailable"
        assert caught.value.usage == ()
        assert not calls

    asyncio.run(run())


def test_closed_provider_on_retry_preserves_previous_attempt_only():
    async def run():
        calls = []
        provider = None

        async def handler(req):
            calls.append(req)
            await provider.aclose()
            return httpx.Response(
                503,
                json={"usage": {"prompt_tokens": 3, "completion_tokens": 2}},
            )

        provider = OpenAICompatibleProvider(
            "https://llm.example/v1/chat/completions",
            "test-secret",
            transport=httpx.MockTransport(handler),
        )
        with pytest.raises(LLMError) as caught:
            await LLMClient(provider).generate(request(attempts=2))
        assert caught.value.code == "model_unavailable"
        assert len(calls) == 1
        assert len(caught.value.usage) == 1
        previous = caught.value.usage[0]
        assert previous.request_key == "call-1"
        assert previous.failed is True
        assert previous.input_tokens == 3
        assert previous.output_tokens == 2

    asyncio.run(run())


def test_ambiguous_runtime_error_during_transport_still_records_attempt():
    async def run():
        calls = []

        def handler(req):
            calls.append(req)
            raise RuntimeError("Do not leak this provider failure")

        async with OpenAICompatibleProvider(
            "https://llm.example/v1/chat/completions",
            "test-secret",
            transport=httpx.MockTransport(handler),
        ) as provider:
            with pytest.raises(LLMError) as caught:
                await LLMClient(provider).generate(request())
            assert len(calls) == 1
            assert caught.value.code == "model_unavailable"
            assert len(caught.value.usage) == 1
            assert caught.value.usage[0].failed is True
            assert caught.value.usage[0].tokens_known is False
            assert "provider failure" not in str(caught.value)

    asyncio.run(run())
