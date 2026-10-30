import asyncio
import threading

import pytest

from app.schemas.retrieval import (
    EmbeddingPolicy,
    EmbeddingProfile,
    EmbeddingText,
    EmbeddingUsage,
    EmbeddingVector,
    ProviderEmbeddingBatch,
)
from app.services.retrieval.embeddings import (
    EmbeddingError,
    EmbeddingProviderError,
    embed_texts,
)


class Counter:
    tokenizer_id = "test:characters-v1"
    max_input_tokens = 100

    def count(self, text):
        return len(text)


def profile(**changes):
    values = {
        "profile_id": "test-v1",
        "provider": "fake",
        "model": "test-model",
        "dimension": 3,
        "tokenizer_id": Counter.tokenizer_id,
        "max_input_tokens": 100,
    }
    values.update(changes)
    return EmbeddingProfile(**values)


def texts(*values):
    return tuple(
        EmbeddingText(key=f"k{i}", text=value) for i, value in enumerate(values)
    )


class Provider:
    def __init__(self, behavior=None):
        self.calls = []
        self.behavior = behavior

    async def embed(self, inputs, selected, purpose, call_key):
        self.calls.append((inputs, selected, purpose, call_key))
        usage = EmbeddingUsage(
            request_key=call_key,
            model=selected.model,
            input_tokens=sum(len(t.text) for t in inputs),
            output_tokens=0,
            latency_ms=1,
            failed=False,
            tokens_known=True,
        )
        response = ProviderEmbeddingBatch(
            profile=selected,
            usage=usage,
            vectors=tuple(
                EmbeddingVector(key=t.key, embedding=(1.0, 0.0, 0.5))
                for t in reversed(inputs)
            ),
        )
        return await self.behavior(self, response) if self.behavior else response


@pytest.mark.asyncio
async def test_reordered_vectors_map_by_key_and_usage_is_per_actual_call():
    provider = Provider()
    inputs = texts("first", "second", "third")
    result = await embed_texts(
        inputs,
        profile(),
        "req",
        provider,
        Counter(),
        policy=EmbeddingPolicy(batch_size=2),
    )
    assert [v.key for v in result.vectors] == [t.key for t in inputs]
    assert len(result.usage) == len(provider.calls) == 2
    assert len({u.request_key for u in result.usage}) == 2
    assert result.profile == profile()
    assert result.purpose == "document"


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "case",
    [
        "missing",
        "duplicate",
        "unknown",
        "dimension",
        "nan",
        "infinity",
        "zero",
        "profile",
        "float32-overflow",
        "float32-underflow",
    ],
)
async def test_bad_provider_response_rejects_all_vectors_and_keeps_usage(case):
    async def corrupt(provider, response):
        vectors = response.vectors
        if case == "missing":
            vectors = vectors[:1]
        elif case == "duplicate":
            vectors = (vectors[0], vectors[0])
        elif case == "unknown":
            vectors = (vectors[0].model_copy(update={"key": "absent"}), vectors[1])
        elif case == "profile":
            return response.model_copy(update={"profile": profile(model="other-model")})
        else:
            values = {
                "dimension": (1.0, 2.0),
                "nan": (float("nan"), 1.0, 2.0),
                "infinity": (float("inf"), 1.0, 2.0),
                "zero": (0.0, 0.0, 0.0),
                "float32-overflow": (1e100, 1.0, 2.0),
                "float32-underflow": (1e-100, 1e-100, 1e-100),
            }
            vectors = (
                vectors[0].model_copy(update={"embedding": values[case]}),
                vectors[1],
            )
        return response.model_copy(update={"vectors": vectors})

    provider = Provider(corrupt)
    with pytest.raises(EmbeddingError) as exc:
        await embed_texts(texts("a", "b"), profile(), "req", provider, Counter())
    assert exc.value.code == "invalid_embedding_output"
    assert len(exc.value.usage) == 1
    assert exc.value.usage[0].input_tokens == 2
    assert len(provider.calls) == 1
    assert not hasattr(exc.value, "vectors")


@pytest.mark.asyncio
async def test_final_batch_failure_does_not_expose_first_batch_vectors():
    async def fail_second(provider, response):
        if len(provider.calls) == 2:
            raise EmbeddingProviderError(
                "provider_unavailable",
                "failed",
                usage=response.usage.model_copy(update={"failed": True}),
            )
        return response

    provider = Provider(fail_second)
    with pytest.raises(EmbeddingError) as exc:
        await embed_texts(
            texts("first", "second"),
            profile(),
            "req",
            provider,
            Counter(),
            policy=EmbeddingPolicy(batch_size=1),
        )
    assert [u.failed for u in exc.value.usage] == [False, True]
    assert not hasattr(exc.value, "vectors")


@pytest.mark.asyncio
async def test_retry_has_distinct_call_key_and_preserves_failed_attempt_usage():
    async def fail_first(provider, response):
        if len(provider.calls) == 1:
            raise EmbeddingProviderError(
                "rate_limited",
                "busy",
                retryable=True,
                usage=response.usage.model_copy(update={"failed": True}),
            )
        return response

    provider = Provider(fail_first)
    result = await embed_texts(
        texts("input"),
        profile(),
        "req",
        provider,
        Counter(),
        policy=EmbeddingPolicy(max_attempts=2, retry_delay_seconds=0.0),
    )
    assert len(provider.calls) == 2
    assert [u.failed for u in result.usage] == [True, False]
    assert result.usage[0].request_key != result.usage[1].request_key


@pytest.mark.asyncio
async def test_unknown_timeout_usage_is_not_invented_as_zero_tokens():
    async def stall(provider, response):
        await asyncio.Event().wait()

    provider = Provider(stall)
    with pytest.raises(EmbeddingError) as exc:
        await embed_texts(
            texts("input"),
            profile(),
            "req",
            provider,
            Counter(),
            policy=EmbeddingPolicy(call_timeout_seconds=0.01),
        )
    assert exc.value.code == "embedding_timeout"
    assert len(provider.calls) == 1
    usage = exc.value.usage[0]
    assert usage.failed and not usage.tokens_known
    assert usage.input_tokens is None and usage.output_tokens is None


@pytest.mark.asyncio
async def test_token_budget_batches_by_sum_and_query_purpose_is_explicit():
    provider = Provider()
    result = await embed_texts(
        texts("aaaa", "bbbb", "cc"),
        profile(),
        "req",
        provider,
        Counter(),
        purpose="query",
        policy=EmbeddingPolicy(batch_size=10, max_batch_tokens=6),
    )
    assert [len(call[0]) for call in provider.calls] == [1, 2]
    assert all(call[2] == "query" for call in provider.calls)
    assert result.purpose == "query"


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "case", ["duplicate", "oversize", "profile", "empty", "calls", "whitespace"]
)
async def test_invalid_request_is_rejected_before_any_paid_call(case):
    provider = Provider()
    inputs, selected, policy = texts("one", "two"), profile(), EmbeddingPolicy()
    if case == "duplicate":
        inputs = (inputs[0], inputs[0])
    elif case == "oversize":
        inputs = texts("x" * 101)
    elif case == "profile":
        selected = profile(tokenizer_id="different")
    elif case == "empty":
        inputs = ()
    elif case == "whitespace":
        inputs = texts(" \t\n")
    else:
        policy = EmbeddingPolicy(batch_size=1, max_batches=1)
    with pytest.raises(EmbeddingError):
        await embed_texts(inputs, selected, "req", provider, Counter(), policy=policy)
    assert not provider.calls


@pytest.mark.asyncio
async def test_retry_exhaustion_keeps_all_attempts_but_no_partial_vectors():
    async def always_fail(provider, response):
        raise EmbeddingProviderError(
            "rate_limited",
            "busy",
            retryable=True,
            usage=response.usage.model_copy(update={"failed": True}),
        )

    provider = Provider(always_fail)
    with pytest.raises(EmbeddingError) as exc:
        await embed_texts(
            texts("input"),
            profile(),
            "req",
            provider,
            Counter(),
            policy=EmbeddingPolicy(max_attempts=2, retry_delay_seconds=0.0),
        )
    assert len(provider.calls) == len(exc.value.usage) == 2
    assert all(u.failed for u in exc.value.usage)


@pytest.mark.asyncio
async def test_total_deadline_stops_retry_before_another_call():
    async def always_fail(provider, response):
        raise EmbeddingProviderError("rate_limited", "busy", retryable=True)

    provider = Provider(always_fail)
    with pytest.raises(EmbeddingError) as exc:
        await embed_texts(
            texts("input"),
            profile(),
            "req",
            provider,
            Counter(),
            policy=EmbeddingPolicy(total_timeout_seconds=1.0, retry_delay_seconds=2.0),
        )
    assert exc.value.code == "embedding_timeout"
    assert len(provider.calls) == 1
    assert not exc.value.usage[0].tokens_known


@pytest.mark.asyncio
async def test_repeated_dispatch_does_not_reuse_actual_call_usage_key():
    provider = Provider()
    first = await embed_texts(
        texts("input"), profile(), "same-request", provider, Counter()
    )
    second = await embed_texts(
        texts("input"), profile(), "same-request", provider, Counter()
    )
    assert first.usage[0].request_key != second.usage[0].request_key


@pytest.mark.asyncio
async def test_token_validation_allows_the_event_loop_to_progress():
    loop = asyncio.get_running_loop()
    released = threading.Event()

    class ResponsiveCounter(Counter):
        def count(self, text):
            loop.call_soon_threadsafe(released.set)
            if not released.wait(1):
                raise ValueError("The event loop could not release token validation")
            return super().count(text)

    result = await embed_texts(
        texts("input"), profile(), "req", Provider(), ResponsiveCounter()
    )
    assert result.vectors[0].key == "k0"


@pytest.mark.asyncio
async def test_token_validation_uses_the_total_deadline_before_provider_calls():
    released = threading.Event()
    provider = Provider()

    class BlockingCounter(Counter):
        def count(self, text):
            released.wait(1)
            return super().count(text)

    try:
        with pytest.raises(EmbeddingError) as exc:
            await embed_texts(
                texts("input"),
                profile(),
                "req",
                provider,
                BlockingCounter(),
                policy=EmbeddingPolicy(total_timeout_seconds=0.01),
            )
        assert exc.value.code == "embedding_timeout"
        assert exc.value.usage == ()
        assert provider.calls == []
    finally:
        released.set()


@pytest.mark.asyncio
async def test_cancelled_token_validation_never_dispatches_a_provider_call():
    loop = asyncio.get_running_loop()
    started = asyncio.Event()
    released = threading.Event()
    provider = Provider()

    class BlockingCounter(Counter):
        def count(self, text):
            loop.call_soon_threadsafe(started.set)
            released.wait(1)
            return super().count(text)

    task = asyncio.create_task(
        embed_texts(texts("input"), profile(), "req", provider, BlockingCounter())
    )
    try:
        await asyncio.wait_for(started.wait(), timeout=2)
        task.cancel()
        with pytest.raises(asyncio.CancelledError) as exc:
            await task
        assert exc.value.embedding_usage == ()
        assert provider.calls == []
    finally:
        released.set()


@pytest.mark.asyncio
async def test_cancelled_provider_preserves_previous_and_unknown_inflight_usage():
    entered = asyncio.Event()

    async def block_second(provider, response):
        if len(provider.calls) == 2:
            entered.set()
            await asyncio.Event().wait()
        return response

    provider = Provider(block_second)
    task = asyncio.create_task(
        embed_texts(
            texts("first", "second"),
            profile(),
            "req",
            provider,
            Counter(),
            policy=EmbeddingPolicy(batch_size=1),
        )
    )
    await asyncio.wait_for(entered.wait(), timeout=2)
    task.cancel()
    with pytest.raises(asyncio.CancelledError) as exc:
        await task
    usage = exc.value.embedding_usage
    assert len(usage) == 2
    assert usage[0].tokens_known and not usage[0].failed
    assert usage[1].request_key == provider.calls[1][3]
    assert usage[1].failed and not usage[1].tokens_known
    assert usage[1].input_tokens is None and usage[1].output_tokens is None


@pytest.mark.asyncio
async def test_external_timeout_preserves_usage_on_cancellation_cause():
    entered = asyncio.Event()

    async def stall(provider, response):
        entered.set()
        await asyncio.Event().wait()

    task = asyncio.create_task(
        embed_texts(texts("input"), profile(), "req", Provider(stall), Counter())
    )
    await asyncio.wait_for(entered.wait(), timeout=2)
    with pytest.raises(TimeoutError) as exc:
        await asyncio.wait_for(task, timeout=0.01)
    assert isinstance(exc.value.__cause__, asyncio.CancelledError)
    usage = exc.value.__cause__.embedding_usage
    assert len(usage) == 1 and usage[0].failed and not usage[0].tokens_known


@pytest.mark.asyncio
async def test_cancellation_during_retry_wait_does_not_invent_an_extra_call():
    entered = asyncio.Event()

    async def fail(provider, response):
        entered.set()
        raise EmbeddingProviderError(
            "rate_limited",
            "busy",
            retryable=True,
            usage=response.usage.model_copy(update={"failed": True}),
        )

    provider = Provider(fail)
    task = asyncio.create_task(
        embed_texts(
            texts("input"),
            profile(),
            "req",
            provider,
            Counter(),
            policy=EmbeddingPolicy(max_attempts=2, retry_delay_seconds=5),
        )
    )
    await asyncio.wait_for(entered.wait(), timeout=2)
    await asyncio.sleep(0)
    task.cancel()
    with pytest.raises(asyncio.CancelledError) as exc:
        await task
    assert len(provider.calls) == 1
    assert len(exc.value.embedding_usage) == 1
    assert exc.value.embedding_usage[0].failed
    assert exc.value.embedding_usage[0].tokens_known
