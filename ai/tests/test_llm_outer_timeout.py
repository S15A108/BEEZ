"""External time limits preserve started calls' available usage on cancellation."""

import asyncio

import pytest

from app.services.llm_client import (
    LLMCancelledError,
    LLMClient,
    LLMContext,
    LLMMessage,
    LLMRequest,
)


@pytest.mark.parametrize("timeout_style", ["context", "wait_for"])
def test_outer_timeout_propagates_cancelled_call_usage(timeout_style):
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
        else:
            await asyncio.wait_for(client.generate(request), timeout=0.05)

    # Python 3.12's timeout scopes convert only the exact CancelledError type.
    # The client raises its LLMCancelledError subclass, which must be propagated.
    with pytest.raises(LLMCancelledError) as caught:
        asyncio.run(invoke())

    usage = caught.value.usage
    assert len(usage) == 1
    assert usage[0].request_key == "call-1"
    assert usage[0].failed is True
    assert usage[0].tokens_known is False
