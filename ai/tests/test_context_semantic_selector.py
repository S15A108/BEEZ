"""Provider-backed context ranking without paid or network calls."""

import asyncio

import pytest

from app.schemas.retrieval import (
    EmbeddingPolicy,
    EmbeddingProfile,
    EmbeddingUsage,
    EmbeddingVector,
    ProviderEmbeddingBatch,
)
from app.services.retrieval.context_selection import (
    ApprovedEvidence,
    ApprovedItem,
    ContextSelectionError,
    SelectionInput,
    TaskDesignRef,
    select_context,
)
from app.services.retrieval.context_semantic_selector import EmbeddingContextSelector
from app.services.retrieval.embeddings import EmbeddingProviderError


class Counter:
    tokenizer_id = "characters-v1"
    max_input_tokens = 2048

    def count(self, text):
        return len(text)


PROFILE = EmbeddingProfile(
    profile_id="fake-v1",
    provider="fake",
    model="model",
    dimension=2,
    tokenizer_id=Counter.tokenizer_id,
    max_input_tokens=Counter.max_input_tokens,
)


class Provider:
    def __init__(self, fail_purpose=None):
        self.calls = []
        self.fail_purpose = fail_purpose

    async def embed(self, texts, profile, purpose, request_key):
        self.calls.append((texts, profile, purpose, request_key))
        usage = EmbeddingUsage(
            request_key=request_key,
            model=profile.model,
            latency_ms=1,
            failed=False,
            tokens_known=True,
            input_tokens=2,
            output_tokens=0,
        )
        if purpose == self.fail_purpose:
            raise EmbeddingProviderError(
                "provider_unavailable",
                "sensitive provider detail",
                usage=usage.model_copy(update={"failed": True}),
            )
        vectors = {
            "login": (1.0, 0.0),
            "login api": (1.0, 0.0),
            "billing": (0.0, 1.0),
            "login evidence": (0.9, 0.1),
            "unrelated evidence": (-1.0, 0.0),
        }
        return ProviderEmbeddingBatch(
            profile=profile,
            usage=usage,
            # Reverse to prove key mapping is independent of response order.
            vectors=tuple(
                EmbeddingVector(key=t.key, embedding=vectors[t.text])
                for t in reversed(texts)
            ),
        )


def inputs():
    def item(item_id, text):
        return ApprovedItem(
            design_item_id=item_id,
            design_item_version_id=f"v-{item_id}",
            project_id="1",
            baseline_id="2",
            status="APPROVED",
            text=text,
        )

    def evidence(evidence_id, text):
        return ApprovedEvidence(
            evidence_id=evidence_id,
            document_version_id="3",
            project_id="1",
            baseline_id="2",
            text=text,
        )

    return SelectionInput(
        project_id="1",
        baseline_id="2",
        request="login",
        document_version_ids=("3",),
        items=(item("10", "login api"), item("11", "billing")),
        evidence=(
            evidence("e-1", "login evidence"),
            evidence("e-2", "unrelated evidence"),
        ),
        relations=(),
        task_design_refs=(),
        search_status="COMPLETE",
    )


def selector(provider, *, min_similarity=0.8, max_items=2, max_evidence=2):
    return EmbeddingContextSelector(
        provider,
        PROFILE,
        Counter(),
        min_similarity=min_similarity,
        max_items=max_items,
        max_evidence=max_evidence,
        policy=EmbeddingPolicy(max_attempts=1),
    )


@pytest.mark.asyncio
async def test_real_selection_keeps_scope_and_separate_query_document_usage():
    provider = Provider()
    result = await select_context(inputs(), selector(provider))
    assert [item.design_item_version_id for item in result.design_items] == ["v-10"]
    assert result.evidence_ids == ("e-1",)
    assert [call[2] for call in provider.calls] == ["query", "document"]
    assert all(call[1] == PROFILE for call in provider.calls)
    assert len(result.usage) == 2
    assert len({usage.request_key for usage in result.usage}) == 2
    assert result.unresolved == ()
    assert result.direct_match_truncation.items == 0
    assert result.direct_match_truncation.evidence == 0


@pytest.mark.asyncio
async def test_candidate_preflight_reuses_batches_without_retokenizing():
    class CountingCounter(Counter):
        def __init__(self):
            self.texts = []

        def count(self, text):
            self.texts.append(text)
            return super().count(text)

    request = inputs()
    provider = Provider()
    counter = CountingCounter()
    configured = EmbeddingContextSelector(
        provider,
        PROFILE,
        counter,
        min_similarity=0.8,
        max_items=2,
        max_evidence=2,
        policy=EmbeddingPolicy(max_attempts=1),
    )
    result = await select_context(request, configured)
    expected = [item.text for item in request.items] + [
        item.text for item in request.evidence
    ]
    assert counter.texts == expected + [request.request]
    assert [call[2] for call in provider.calls] == ["query", "document"]
    assert [text.text for text in provider.calls[1][0]] == expected
    assert len(result.usage) == 2


@pytest.mark.asyncio
@pytest.mark.parametrize("exhausted", [False, True])
async def test_document_preparation_budget_excludes_query_wait(monkeypatch, exhausted):
    from app.services.retrieval import embeddings

    class Clock:
        now = 0.0

        def monotonic(self):
            return self.now

    clock = Clock()

    class TimedCounter(Counter):
        def count(self, text):
            if text != "login":
                # Four candidate texts consume 0.75 or 1.0 seconds in total.
                clock.now += 0.25 if exhausted else 0.1875
            return super().count(text)

    class TimedQueryProvider(Provider):
        async def embed(self, texts, profile, purpose, request_key):
            if purpose == "query":
                clock.now += 0.5
            return await super().embed(texts, profile, purpose, request_key)

    # Replace only the embedding module's clock, not asyncio's event-loop clock.
    monkeypatch.setattr(embeddings, "time", clock)
    provider = TimedQueryProvider()
    configured = EmbeddingContextSelector(
        provider,
        PROFILE,
        TimedCounter(),
        min_similarity=0.8,
        max_items=2,
        max_evidence=2,
        policy=EmbeddingPolicy(max_attempts=1, total_timeout_seconds=1.0),
    )
    if exhausted:
        with pytest.raises(ContextSelectionError) as exc:
            await select_context(inputs(), configured)
        assert exc.value.code == "embedding_timeout"
        assert exc.value.usage == ()
        assert provider.calls == []
    else:
        result = await select_context(inputs(), configured)
        assert len(result.usage) == 2
        assert [call[2] for call in provider.calls] == ["query", "document"]


@pytest.mark.asyncio
async def test_direct_limit_reports_threshold_passing_omissions_separately():
    provider = Provider()
    result = await select_context(
        inputs(), selector(provider, min_similarity=-1.0, max_items=1, max_evidence=1)
    )
    assert [item.design_item_version_id for item in result.design_items] == ["v-10"]
    assert result.evidence_ids == ("e-1",)
    assert result.direct_match_truncation.items == 1
    assert result.direct_match_truncation.evidence == 1
    assert len(result.usage) == 2
    assert result.model_dump(mode="json")["direct_match_truncation"] == {
        "items": 1,
        "evidence": 1,
    }


@pytest.mark.asyncio
async def test_truncated_direct_item_can_still_appear_via_task_reference():
    provider = Provider()
    request = inputs().model_copy(
        update={
            "task_id": "task",
            "task_design_refs": (
                TaskDesignRef(design_item_id="11", design_item_version_id="v-11"),
            ),
        }
    )
    result = await select_context(
        request, selector(provider, min_similarity=-1.0, max_items=1, max_evidence=2)
    )
    assert [
        (item.design_item_version_id, item.reasons) for item in result.design_items
    ] == [("v-10", ("MATCHED",)), ("v-11", ("TASK_PLAN",))]
    assert result.direct_match_truncation.items == 1
    assert result.direct_match_truncation.evidence == 0


@pytest.mark.asyncio
async def test_threshold_allows_real_zero_results_and_candidate_limits():
    provider = Provider()
    result = await select_context(
        inputs().model_copy(update={"items": ()}),
        selector(provider, min_similarity=1.0, max_items=0),
    )
    assert result.design_items == ()
    assert result.evidence_ids == ()
    assert result.unresolved == ("NO_MATCH",)
    assert all(t.key.startswith("evidence-") for t in provider.calls[1][0])
    assert len(result.usage) == 2


@pytest.mark.parametrize("disabled_type", ["items", "evidence"])
@pytest.mark.asyncio
async def test_zero_limit_is_safe_when_disabled_candidate_type_is_empty(disabled_type):
    request = inputs().model_copy(update={disabled_type: ()})
    provider = Provider()
    options = {"max_items": 0} if disabled_type == "items" else {"max_evidence": 0}
    result = await select_context(request, selector(provider, **options))
    assert not result.unresolved
    if disabled_type == "items":
        assert result.evidence_ids == ("e-1",)
    else:
        assert [item.design_item_version_id for item in result.design_items] == ["v-10"]
    assert [call[2] for call in provider.calls] == ["query", "document"]


@pytest.mark.parametrize(
    ("disabled_type", "only_disabled"),
    [("items", False), ("items", True), ("evidence", False), ("evidence", True)],
)
@pytest.mark.asyncio
async def test_cannot_report_no_match_when_candidates_were_skipped(
    disabled_type, only_disabled
):
    request = inputs()
    if only_disabled:
        other_type = "evidence" if disabled_type == "items" else "items"
        request = request.model_copy(update={other_type: ()})
    provider = Provider()
    options = {"max_items": 0} if disabled_type == "items" else {"max_evidence": 0}
    with pytest.raises(ContextSelectionError) as exc:
        await select_context(request, selector(provider, **options))
    assert exc.value.code == "selection_incomplete"
    assert exc.value.usage == ()
    assert not provider.calls


@pytest.mark.parametrize("failure", ["oversized_document", "too_many_candidates"])
@pytest.mark.asyncio
async def test_invalid_document_batch_never_calls_query_provider(failure):
    request = inputs()
    policy = EmbeddingPolicy(max_attempts=1)
    if failure == "oversized_document":
        item = request.items[0].model_copy(update={"text": "x" * 2049})
        request = request.model_copy(update={"items": (item,)})
        expected = "embedding_limit_exceeded"
    else:
        policy = EmbeddingPolicy(max_attempts=1, max_texts=1)
        expected = "invalid_embedding_input"
    provider = Provider()
    configured = EmbeddingContextSelector(
        provider,
        PROFILE,
        Counter(),
        min_similarity=0.8,
        max_items=2,
        max_evidence=2,
        policy=policy,
    )
    with pytest.raises(ContextSelectionError) as exc:
        await select_context(request, configured)
    assert exc.value.code == expected
    assert exc.value.usage == ()
    assert provider.calls == []


@pytest.mark.asyncio
async def test_failure_keeps_all_completed_usage_without_becoming_no_match():
    provider = Provider(fail_purpose="document")
    with pytest.raises(ContextSelectionError) as exc:
        await select_context(inputs(), selector(provider))
    assert exc.value.code == "selection_failed"
    assert [entry.failed for entry in exc.value.usage] == [False, True]
    assert "sensitive provider detail" not in str(exc.value)


@pytest.mark.asyncio
async def test_cancellation_keeps_query_and_inflight_document_usage():
    entered = asyncio.Event()

    class BlockingProvider(Provider):
        async def embed(self, texts, profile, purpose, request_key):
            if purpose == "document":
                entered.set()
                await asyncio.Event().wait()
            return await super().embed(texts, profile, purpose, request_key)

    provider = BlockingProvider()
    task = asyncio.create_task(select_context(inputs(), selector(provider)))
    await asyncio.wait_for(entered.wait(), timeout=2)
    task.cancel()
    with pytest.raises(asyncio.CancelledError) as exc:
        await task
    usage = exc.value.embedding_usage
    assert len(usage) == 2
    assert usage[0].tokens_known and not usage[0].failed
    assert usage[1].failed and not usage[1].tokens_known


@pytest.mark.asyncio
async def test_empty_candidates_and_failed_search_never_call_provider():
    provider = Provider()
    request = inputs().model_copy(update={"items": (), "evidence": ()})
    result = await select_context(request, selector(provider))
    assert result.unresolved == ("NO_MATCH",)
    assert result.usage == ()
    assert not provider.calls
    with pytest.raises(ContextSelectionError) as exc:
        await select_context(
            inputs().model_copy(update={"search_status": "FAILED"}), selector(provider)
        )
    assert exc.value.code == "search_failed"
    assert not provider.calls


@pytest.mark.parametrize("threshold", [float("nan"), -1.1, 1.1, True])
def test_threshold_must_be_explicit_finite_cosine(threshold):
    with pytest.raises(ValueError):
        selector(Provider(), min_similarity=threshold)


@pytest.mark.parametrize("key", ["", "  ", "x" * 129, "x\ny", "x\x00y", "\ud800", 4])
def test_invalid_in_process_correlation_key_is_rejected_without_provider_call(key):
    provider = Provider()
    with pytest.raises(ValueError, match="Invalid request key"):
        EmbeddingContextSelector(
            provider,
            PROFILE,
            Counter(),
            min_similarity=0.8,
            max_items=2,
            max_evidence=2,
            policy=EmbeddingPolicy(max_attempts=1),
            request_key=key,
        )
    assert not provider.calls


@pytest.mark.asyncio
async def test_in_process_retry_has_distinct_usage_keys_with_same_correlation_key():
    class RetryOnce(Provider):
        def __init__(self):
            super().__init__()
            self.attempted = []

        async def embed(self, texts, profile, purpose, request_key):
            self.attempted.append(request_key)
            if len(self.attempted) == 1:
                raise EmbeddingProviderError(
                    "provider_unavailable",
                    "private",
                    retryable=True,
                    usage=EmbeddingUsage(
                        request_key=request_key,
                        model=profile.model,
                        latency_ms=1,
                        failed=True,
                        tokens_known=False,
                    ),
                )
            return await super().embed(texts, profile, purpose, request_key)

    provider = RetryOnce()
    key = "x" * 128
    configured = EmbeddingContextSelector(
        provider,
        PROFILE,
        Counter(),
        min_similarity=0.8,
        max_items=2,
        max_evidence=2,
        policy=EmbeddingPolicy(max_attempts=2, retry_delay_seconds=0),
        request_key=key,
    )
    result = await select_context(inputs(), configured)
    usage_keys = [entry.request_key for entry in result.usage]
    assert usage_keys == provider.attempted
    assert len(usage_keys) == len(set(usage_keys)) == 3
    assert all(value.startswith(key + ":emb:") for value in usage_keys)
    assert [entry.failed for entry in result.usage] == [True, False, False]


@pytest.mark.asyncio
async def test_in_process_cancellation_keeps_correlated_call_usage():
    entered = asyncio.Event()

    class BlockDocument(Provider):
        async def embed(self, texts, profile, purpose, request_key):
            if purpose == "document":
                entered.set()
                await asyncio.Event().wait()
            return await super().embed(texts, profile, purpose, request_key)

    key = "연관:emb:값"
    configured = EmbeddingContextSelector(
        BlockDocument(),
        PROFILE,
        Counter(),
        min_similarity=0.8,
        max_items=2,
        max_evidence=2,
        policy=EmbeddingPolicy(max_attempts=1),
        request_key=key,
    )
    task = asyncio.create_task(select_context(inputs(), configured))
    await asyncio.wait_for(entered.wait(), timeout=2)
    task.cancel()
    with pytest.raises(asyncio.CancelledError) as error:
        await task
    records = error.value.embedding_usage
    assert len(records) == 2 and records[-1].failed and not records[-1].tokens_known
    assert len({record.request_key for record in records}) == 2
    assert all(record.request_key.startswith(key + ":emb:") for record in records)
