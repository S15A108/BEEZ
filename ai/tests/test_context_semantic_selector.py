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


@pytest.mark.asyncio
async def test_threshold_allows_real_zero_results_and_candidate_limits():
    provider = Provider()
    result = await select_context(
        inputs(), selector(provider, min_similarity=1.0, max_items=0)
    )
    assert result.design_items == ()
    assert result.evidence_ids == ()
    assert result.unresolved == ("NO_MATCH",)
    assert all(t.key.startswith("evidence-") for t in provider.calls[1][0])
    assert len(result.usage) == 2


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
