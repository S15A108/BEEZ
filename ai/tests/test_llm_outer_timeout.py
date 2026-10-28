"""External time limits preserve started calls' available usage on cancellation."""

import asyncio

import pytest

from app.services.llm_client import (
    LLMClient,
    LLMContext,
    LLMMessage,
    LLMRequest,
    ProviderError,
)


@pytest.mark.parametrize("timeout_style", ["context", "wait_for", "wait_for_task"])
def test_outer_timeout_raises_timeout_error_with_cancelled_call_usage(timeout_style):
    class SlowProvider:
        async def complete(self, request, *, timeout_seconds):
            await asyncio.Event().wait()

    request = LLMRequest(
        model="test-model",
        messages=(LLMMessage(role="user", content="test"),),
        context=LLMContext(
            project_id="101", feature="BEE_CHAT", request_key="request-1"
        ),
        call_keys=("call-1",),
        timeout_seconds=5.0,
    )
    client = LLMClient(SlowProvider())

    async def invoke():
        if timeout_style == "context":
            async with asyncio.timeout(0.05):
                await client.generate(request)
        elif timeout_style == "wait_for":
            await asyncio.wait_for(client.generate(request), timeout=0.05)
        else:
            task = asyncio.create_task(client.generate(request))
            await asyncio.wait_for(task, timeout=0.05)

    with pytest.raises(TimeoutError) as caught:
        asyncio.run(invoke())

    cause = caught.value.__cause__
    assert type(cause) is asyncio.CancelledError
    usage = cause.llm_usage
    assert len(usage) == 1
    assert usage[0].request_key == "call-1"
    assert usage[0].failed is True
    assert usage[0].tokens_known is False


@pytest.mark.parametrize("timeout_style", ["context", "wait_for", "cancel"])
def test_cancel_during_retry_wait_keeps_only_started_call_usage(timeout_style):
    class RetryingProvider:
        calls = 0

        async def complete(self, request, *, timeout_seconds):
            self.calls += 1
            raise ProviderError(
                "model_unavailable",
                retryable=True,
                retry_after_seconds=1.0,
                input_tokens=3,
                output_tokens=2,
            )

    request = LLMRequest(
        model="test-model",
        messages=(LLMMessage(role="user", content="test"),),
        context=LLMContext(project_id="101", feature="BEE_CHAT", request_key="req"),
        call_keys=("call-1", "call-2"),
        max_attempts=2,
        timeout_seconds=5.0,
    )

    async def invoke():
        provider = RetryingProvider()
        client = LLMClient(provider)
        if timeout_style == "cancel":
            task = asyncio.create_task(client.generate(request))
            await asyncio.sleep(0.05)
            task.cancel()
            with pytest.raises(asyncio.CancelledError) as caught:
                await task
            cause = caught.value
        else:
            with pytest.raises(TimeoutError) as caught:
                if timeout_style == "context":
                    async with asyncio.timeout(0.05):
                        await client.generate(request)
                else:
                    await asyncio.wait_for(client.generate(request), timeout=0.05)
            cause = caught.value.__cause__

        assert type(cause) is asyncio.CancelledError
        assert provider.calls == 1
        assert len(cause.llm_usage) == 1
        usage = cause.llm_usage[0]
        assert usage.request_key == "call-1"
        assert usage.failed is True
        assert (usage.input_tokens, usage.output_tokens) == (3, 2)

    asyncio.run(invoke())
