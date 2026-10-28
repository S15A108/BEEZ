"""Provider connection lifecycle, Retry-After and model metadata behavior."""

import asyncio
from dataclasses import replace
from datetime import UTC, datetime, timedelta
from email.utils import format_datetime

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
from app.services.llm_client.errors import ProviderError


def make_request(*, message="hello", allowed=()):
    return LLMRequest(
        model="gpt-4o-mini",
        messages=(LLMMessage(role="user", content=message),),
        context=LLMContext(project_id="1", feature="BEE_CHAT", request_key="req-1"),
        call_keys=("call-1",),
        timeout_seconds=5,
        allowed_response_models=allowed,
    )


class RecordingTransport(httpx.AsyncBaseTransport):
    def __init__(self):
        self.requests = []
        self.closed = False

    async def handle_async_request(self, request):
        self.requests.append(request)
        await asyncio.sleep(0)
        return httpx.Response(
            200,
            json={
                "model": "gpt-4o-mini-2024-07-18",
                "choices": [{"message": {"content": "ok"}, "finish_reason": "stop"}],
                "usage": {"prompt_tokens": 1, "completion_tokens": 1},
            },
        )

    async def aclose(self):
        self.closed = True


def test_provider_reuses_owned_client_for_concurrent_calls_and_closes_transport():
    async def run():
        transport = RecordingTransport()
        provider = OpenAICompatibleProvider(
            "https://llm.example/v1/chat/completions",
            "key-for-testing",
            transport=transport,
        )
        request = make_request()
        shared_client = provider._client
        responses = await asyncio.gather(
            provider.complete(request, timeout_seconds=2),
            provider.complete(request, timeout_seconds=3),
        )
        assert [response.content for response in responses] == ["ok", "ok"]
        assert provider._client is shared_client
        assert len(transport.requests) == 2
        assert [r.extensions["timeout"]["read"] for r in transport.requests] == [2, 3]
        assert not transport.closed
        await provider.aclose()
        assert shared_client.is_closed
        assert transport.closed
        await provider.aclose()  # Closing twice is safe.

    asyncio.run(run())


def test_context_manager_closes_owned_transport_after_exception():
    async def run():
        transport = RecordingTransport()
        provider = OpenAICompatibleProvider(
            "https://llm.example/v1/chat/completions",
            "key-for-testing",
            transport=transport,
        )
        with pytest.raises(ValueError):
            async with provider as entered:
                assert entered is provider
                raise ValueError("expected")
        assert transport.closed
        with pytest.raises(RuntimeError):
            async with provider:
                pass

    asyncio.run(run())


def test_allowed_response_model_can_contain_user_message():
    async def run():
        async with OpenAICompatibleProvider(
            "https://llm.example/v1/chat/completions",
            "key-for-testing",
            transport=RecordingTransport(),
        ) as provider:
            request = make_request(message="mini", allowed=("gpt-4o-mini-2024-07-18",))
            result = await LLMClient(provider).generate(request)
            assert result.result == "ok"
            assert result.model == "gpt-4o-mini-2024-07-18"

    asyncio.run(run())


def test_credential_in_response_model_remains_rejected_even_when_allowed():
    async def run():
        secret = "key-for-testing"

        def handler(request):
            return httpx.Response(
                200,
                json={
                    "model": f"gpt-{secret}",
                    "choices": [
                        {"message": {"content": "ok"}, "finish_reason": "stop"}
                    ],
                },
            )

        async with OpenAICompatibleProvider(
            "https://llm.example/v1/chat/completions",
            secret,
            transport=httpx.MockTransport(handler),
        ) as provider:
            with pytest.raises(LLMError) as caught:
                await LLMClient(provider).generate(
                    make_request(allowed=(f"gpt-{secret}",))
                )
            assert caught.value.code == "invalid_output"
            assert secret not in str(caught.value)
            assert caught.value.usage[0].model == "gpt-4o-mini"

    asyncio.run(run())


@pytest.mark.parametrize("status", [429, 503])
@pytest.mark.parametrize(
    ("header", "expected"),
    [
        ("2", 2.0),
        ("1.5", 1.5),
        ("-1", None),
        ("NaN", None),
        ("inf", None),
        ("bad", None),
    ],
)
def test_retry_after_seconds_parsed_for_retriable_responses(status, header, expected):
    async def run():
        async with OpenAICompatibleProvider(
            "https://llm.example/v1/chat/completions",
            "key-for-testing",
            transport=httpx.MockTransport(
                lambda request: httpx.Response(status, headers={"Retry-After": header})
            ),
        ) as provider:
            with pytest.raises(ProviderError) as caught:
                await provider.complete(make_request(), timeout_seconds=1)
            assert caught.value.retryable
            assert caught.value.retry_after_seconds == expected

    asyncio.run(run())


def test_retry_after_http_date_parsed():
    async def run():
        date = format_datetime(datetime.now(UTC) + timedelta(seconds=30), usegmt=True)
        async with OpenAICompatibleProvider(
            "https://llm.example/v1/chat/completions",
            "key-for-testing",
            transport=httpx.MockTransport(
                lambda request: httpx.Response(429, headers={"Retry-After": date})
            ),
        ) as provider:
            with pytest.raises(ProviderError) as caught:
                await provider.complete(make_request(), timeout_seconds=1)
            assert 28 <= caught.value.retry_after_seconds <= 30

    asyncio.run(run())


def test_http_retry_after_is_honored_and_preserves_each_call_usage(monkeypatch):
    delays = []
    responses = iter(
        [
            httpx.Response(
                429,
                headers={"Retry-After": "2"},
                json={"usage": {"prompt_tokens": 3, "completion_tokens": 2}},
            ),
            httpx.Response(
                200,
                json={
                    "model": "gpt-4o-mini",
                    "choices": [
                        {"message": {"content": "ok"}, "finish_reason": "stop"}
                    ],
                    "usage": {"prompt_tokens": 5, "completion_tokens": 4},
                },
            ),
        ]
    )
    calls = []

    def handler(request):
        calls.append(request)
        return next(responses)

    async def fake_sleep(delay):
        delays.append(delay)

    monkeypatch.setattr(asyncio, "sleep", fake_sleep)

    async def run():
        async with OpenAICompatibleProvider(
            "https://llm.example/v1/chat/completions",
            "key-for-testing",
            transport=httpx.MockTransport(handler),
        ) as provider:
            request = replace(
                make_request(),
                call_keys=("call-1", "call-2"),
                max_attempts=2,
                timeout_seconds=10,
            )
            return await LLMClient(provider).generate(request)

    result = asyncio.run(run())
    assert len(calls) == 2
    assert delays == [2.0]
    assert result.result == "ok"
    assert [usage.request_key for usage in result.usage] == ["call-1", "call-2"]
    assert [usage.failed for usage in result.usage] == [True, False]
    assert [(usage.input_tokens, usage.output_tokens) for usage in result.usage] == [
        (3, 2),
        (5, 4),
    ]
