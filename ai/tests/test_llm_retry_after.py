"""The retry wait respects provider hints and the request's total budget."""

import asyncio
from dataclasses import replace

import pytest

from app.services.llm_client import (
    LLMClient,
    LLMContext,
    LLMError,
    LLMMessage,
    LLMRequest,
    ProviderError,
    ProviderResponse,
)


@pytest.fixture(scope="module")
def run_async():
    loop = asyncio.new_event_loop()
    yield loop.run_until_complete
    loop.close()


def request(**changes):
    base = LLMRequest(
        model="model",
        messages=(LLMMessage(role="user", content="hello"),),
        context=LLMContext(project_id="1", feature="BEE_CHAT", request_key="r"),
        call_keys=("call-1", "call-2"),
        max_attempts=2,
        timeout_seconds=10,
    )
    return replace(base, **changes)


class Provider:
    def __init__(self, retry_after):
        self.retry_after = retry_after
        self.calls = 0

    async def complete(self, request, *, timeout_seconds):
        self.calls += 1
        if self.calls == 1:
            raise ProviderError(
                "model_unavailable",
                retryable=True,
                retry_after_seconds=self.retry_after,
                input_tokens=3,
                output_tokens=2,
            )
        return ProviderResponse(
            content="ok", model="model", input_tokens=5, output_tokens=4
        )


@pytest.mark.parametrize(
    ("hint", "expected"),
    [(0.5, 0.5), (0.01, 0.1), (None, 0.1), (-1, 0.1), (float("nan"), 0.1), (True, 0.1)],
)
def test_retry_uses_larger_valid_wait_and_preserves_usage(
    run_async, monkeypatch, hint, expected
):
    delays = []

    async def fake_sleep(delay):
        delays.append(delay)

    monkeypatch.setattr(asyncio, "sleep", fake_sleep)
    provider = Provider(hint)
    result = run_async(LLMClient(provider).generate(request()))

    assert delays == [expected]
    assert provider.calls == 2
    assert [item.request_key for item in result.usage] == ["call-1", "call-2"]
    assert [item.failed for item in result.usage] == [True, False]
    assert (result.usage[0].input_tokens, result.usage[0].output_tokens) == (3, 2)


def test_retry_hint_exceeding_budget_times_out_without_a_second_call(run_async):
    provider = Provider(60)
    with pytest.raises(LLMError) as caught:
        run_async(LLMClient(provider).generate(request(timeout_seconds=0.5)))

    assert caught.value.code == "model_timeout"
    assert provider.calls == 1
    assert len(caught.value.usage) == 1
    assert caught.value.usage[0].request_key == "call-1"
    assert caught.value.usage[0].failed
