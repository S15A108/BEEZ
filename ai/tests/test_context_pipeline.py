"""Exercise the composed internal flow with fake providers only."""

import asyncio
import json

import pytest

from app.schemas.retrieval import (
    EmbeddingPolicy,
    EmbeddingProfile,
    EmbeddingUsage,
    EmbeddingVector,
    ProviderEmbeddingBatch,
)
from app.services.llm_client import LLMClient, LLMContext, LLMError, ProviderResponse
from app.services.retrieval.context_pipeline import (
    ContextPipelineError,
    build_development_context,
)
from app.services.retrieval.context_request_analysis import (
    LLMRequestAnalyzer,
    SearchHints,
)
from app.services.retrieval.context_selection import (
    ApprovedEvidence,
    ApprovedItem,
    ContextSelectionError,
    SelectionInput,
    SelectionProposal,
)
from app.services.retrieval.context_semantic_selector import EmbeddingContextSelector
from app.services.retrieval.context_unresolved import LLMUnresolvedAssessor
from app.services.retrieval.embeddings import EmbeddingProviderError


class Counter:
    tokenizer_id = "fake"
    max_input_tokens = 2048

    def count(self, text):
        return len(text)


PROFILE = EmbeddingProfile(
    profile_id="fixture",
    provider="fake",
    model="fake",
    dimension=2,
    tokenizer_id="fake",
    max_input_tokens=2048,
)


def snapshot(status="COMPLETE"):
    return SelectionInput(
        project_id="p",
        baseline_id="b",
        task_id=None,
        request="업로드 제한 확인",
        document_version_ids=("d1", "d2"),
        items=(
            ApprovedItem(
                design_item_id="i1",
                design_item_version_id="v1",
                project_id="p",
                baseline_id="b",
                status="APPROVED",
                text="업로드 한도 30 MB",
            ),
        ),
        evidence=(
            ApprovedEvidence(
                evidence_id="e1",
                document_version_id="d1",
                project_id="p",
                baseline_id="b",
                text="최대 20 MB",
            ),
            ApprovedEvidence(
                evidence_id="e2",
                document_version_id="d2",
                project_id="p",
                baseline_id="b",
                text="민감한 비선택 원문",
            ),
        ),
        relations=(),
        task_design_refs=(),
        search_status=status,
    )


class Model:
    def __init__(self, stage, events, output, block=None):
        self.stage, self.events, self.output, self.block = stage, events, output, block
        self.requests = []

    async def complete(self, request, *, timeout_seconds):
        self.events.append(self.stage)
        self.requests.append(request)
        if self.block == self.stage:
            await asyncio.Event().wait()
        return ProviderResponse(
            content=json.dumps(self.output, ensure_ascii=False),
            model="fake",
            input_tokens=10,
            output_tokens=5,
        )


class Embeddings:
    def __init__(self, events, fail=False, block=None):
        self.events, self.fail, self.block = events, fail, block
        self.calls = []

    async def embed(self, texts, profile, purpose, request_key):
        self.events.append(purpose)
        self.calls.append((texts, purpose))
        if self.block == purpose:
            await asyncio.Event().wait()
        usage = EmbeddingUsage(
            request_key=request_key,
            model=profile.model,
            latency_ms=1,
            failed=self.fail and purpose == "document",
            tokens_known=True,
            input_tokens=4,
            output_tokens=0,
        )
        if self.fail and purpose == "document":
            raise EmbeddingProviderError(
                "provider_unavailable", "secret provider message", usage=usage
            )
        return ProviderEmbeddingBatch(
            profile=profile,
            usage=usage,
            vectors=tuple(
                EmbeddingVector(
                    key=x.key,
                    embedding=(0.0, 1.0) if x.key == "evidence-1" else (1.0, 0.0),
                )
                for x in texts
            ),
        )


def components(
    *, bad_analysis=False, bad_review=False, fail_embedding=False, block=None
):
    events = []
    analysis_output = {"functions": ["업로드"], "domains": [], "keywords": ["제한"]}
    if bad_analysis:
        analysis_output["functions"] = ["원문 밖 후보"]
    quote = "없는 문장" if bad_review else "20 MB"
    review_output = {
        "candidates": [
            {
                "kind": "POSSIBLE_CONFLICT",
                "description": "한도 검토",
                "references": [
                    {"kind": "design_item", "id": "v1", "quote": "30 MB"},
                    {"kind": "evidence", "id": "e1", "quote": quote},
                ],
            }
        ]
    }
    a = Model("analysis", events, analysis_output, block)
    r = Model("review", events, review_output, block)
    e = Embeddings(events, fail_embedding, block)
    context = LLMContext(
        project_id="p", task_id=None, feature="BEE_CHAT", request_key="fake-request"
    )
    analyzer = LLMRequestAnalyzer(
        LLMClient(a),
        context=context,
        model="fake",
        call_key="analyze",
        timeout_seconds=5,
    )
    assessor = LLMUnresolvedAssessor(
        LLMClient(r),
        context=context,
        model="fake",
        call_key="review",
        timeout_seconds=5,
    )
    selector = EmbeddingContextSelector(
        e,
        PROFILE,
        Counter(),
        min_similarity=0.8,
        max_items=2,
        max_evidence=2,
        policy=EmbeddingPolicy(max_attempts=1),
    )
    return events, a, e, r, analyzer, selector, assessor


@pytest.mark.asyncio
async def test_composes_real_components_with_original_query_scope_and_usage():
    events, _a, e, r, analyzer, selector, assessor = components()
    result = await build_development_context(snapshot(), analyzer, selector, assessor)
    assert events == ["analysis", "query", "document", "review"]
    assert result.hints.query == snapshot().request
    assert result.hints.functions == ("업로드",)
    assert e.calls[0][0][0].text == snapshot().request
    assert result.selection.evidence_ids == ("e1",)
    assert "민감한 비선택 원문" not in r.requests[0].messages[1].content
    assert result.review.result["candidates"][0]["kind"] == "POSSIBLE_CONFLICT"
    assert [u.request_key for u in result.llm_usage] == ["analyze", "review"]
    assert len(result.embedding_usage) == 2
    assert "업로드" not in repr(result)


@pytest.mark.parametrize("status", ["FAILED", "NOT_READY"])
@pytest.mark.asyncio
async def test_search_failure_stops_before_any_paid_stage(status):
    events, _, _, _, analyzer, selector, assessor = components()
    with pytest.raises(ContextPipelineError) as error:
        await build_development_context(snapshot(status), analyzer, selector, assessor)
    assert error.value.code == (
        "search_failed" if status == "FAILED" else "context_not_ready"
    )
    assert error.value.phase == "input" and not events


@pytest.mark.asyncio
async def test_invalid_hints_stop_before_selection_and_preserve_failed_model_usage():
    events, _, _, _, analyzer, selector, assessor = components(bad_analysis=True)
    with pytest.raises(ContextPipelineError) as error:
        await build_development_context(snapshot(), analyzer, selector, assessor)
    assert error.value.code == "analysis_failed"
    assert events == ["analysis"]
    assert (
        error.value.llm_usage[0].failed and error.value.llm_usage[0].input_tokens == 10
    )
    assert not error.value.embedding_usage


@pytest.mark.asyncio
async def test_changed_query_is_rejected_before_selector():
    events, _, _, _, analyzer, selector, assessor = components()

    class ChangesQuery:
        async def analyze(self, request):
            original = await analyzer.analyze(request)
            return SearchHints(
                "수정된 검색어",
                original.functions,
                original.domains,
                original.keywords,
                original.model,
                original.usage,
            )

    with pytest.raises(ContextPipelineError) as error:
        await build_development_context(snapshot(), ChangesQuery(), selector, assessor)
    assert error.value.code == "analysis_failed" and events == ["analysis"]
    assert error.value.llm_usage[0].input_tokens == 10


@pytest.mark.asyncio
async def test_embedding_failure_keeps_analysis_and_all_started_embedding_usage():
    events, _, _, _, analyzer, selector, assessor = components(fail_embedding=True)
    with pytest.raises(ContextPipelineError) as error:
        await build_development_context(snapshot(), analyzer, selector, assessor)
    assert events == ["analysis", "query", "document"]
    assert error.value.phase == "selection"
    assert [u.failed for u in error.value.embedding_usage] == [False, True]
    assert len(error.value.llm_usage) == 1
    assert "secret" not in str(error.value)


@pytest.mark.asyncio
async def test_forged_review_is_rejected_and_all_usage_is_preserved():
    events, _, _, _, analyzer, selector, assessor = components(bad_review=True)
    with pytest.raises(ContextPipelineError) as error:
        await build_development_context(snapshot(), analyzer, selector, assessor)
    assert events == ["analysis", "query", "document", "review"]
    assert error.value.code == "review_failed"
    assert error.value.cause_code == "invalid_output"
    assert [u.failed for u in error.value.llm_usage] == [False, True]
    assert len(error.value.embedding_usage) == 2


@pytest.mark.parametrize("phase", ["analysis", "query", "document", "review"])
@pytest.mark.asyncio
async def test_cancellation_preserves_completed_and_inflight_usage(phase):
    events, _, _, _, analyzer, selector, assessor = components(block=phase)
    task = asyncio.create_task(
        build_development_context(snapshot(), analyzer, selector, assessor)
    )

    async def wait_for_phase():
        while phase not in events:
            await asyncio.sleep(0)

    await asyncio.wait_for(wait_for_phase(), 2)
    task.cancel()
    with pytest.raises(asyncio.CancelledError) as error:
        await task
    llm, embedding = error.value.llm_usage, error.value.embedding_usage
    if phase == "analysis":
        assert len(llm) == 1 and llm[0].failed and not llm[0].tokens_known
        assert not embedding
    elif phase == "query":
        assert len(llm) == 1 and not llm[0].failed
        assert (
            len(embedding) == 1
            and embedding[-1].failed
            and not embedding[-1].tokens_known
        )
    elif phase == "document":
        assert len(llm) == 1 and not llm[0].failed
        assert (
            len(embedding) == 2
            and embedding[-1].failed
            and not embedding[-1].tokens_known
        )
    else:
        assert len(llm) == 2 and llm[-1].failed and not llm[-1].tokens_known
        assert len(embedding) == 2 and all(not u.failed for u in embedding)


@pytest.mark.asyncio
async def test_no_match_stays_unresolved_after_real_review():
    events, _, _, review_model, analyzer, _, assessor = components()
    review_model.output = {"candidates": []}

    class EmptySelector:
        async def select(self, request):
            return SelectionProposal(design_item_version_ids=(), evidence_ids=())

    result = await build_development_context(
        snapshot(), analyzer, EmptySelector(), assessor
    )
    assert result.selection.unresolved == ("NO_MATCH",)
    assert result.review.result["selection_unresolved"] == ["NO_MATCH"]
    assert events == ["analysis", "review"]


@pytest.mark.parametrize(
    "code",
    [
        "invalid_input",
        "invalid_output",
        "model_timeout",
        "model_unavailable",
        "secret from provider",
    ],
)
@pytest.mark.asyncio
async def test_llm_failure_preserves_only_allowed_cause_codes(code):
    events, _, _, _, _, selector, assessor = components()

    class Failure:
        async def analyze(self, request):
            raise LLMError(code)

    with pytest.raises(ContextPipelineError) as error:
        await build_development_context(snapshot(), Failure(), selector, assessor)
    assert error.value.code == "analysis_failed"
    assert error.value.cause_code == (None if code.startswith("secret") else code)
    assert "secret" not in str(error.value) and not events


@pytest.mark.parametrize("kind", ["pipeline", "selection"])
@pytest.mark.asyncio
async def test_untrusted_component_exception_codes_are_sanitized(kind):
    events, _, _, _, _, selector, assessor = components()

    class Failure:
        async def analyze(self, request):
            if kind == "pipeline":
                raise ContextPipelineError("secret text", "review")
            raise ContextSelectionError("secret text")

    with pytest.raises(ContextPipelineError) as error:
        await build_development_context(snapshot(), Failure(), selector, assessor)
    assert error.value.code == "analysis_failed" and error.value.phase == "analysis"
    assert "secret" not in str(error.value) and not events


@pytest.mark.parametrize("phase", ["analysis", "query", "document", "review"])
@pytest.mark.asyncio
async def test_outer_timeout_keeps_usage_on_cancelled_cause(phase):
    events, _, _, _, analyzer, selector, assessor = components(block=phase)

    async def run_with_timeout():
        async with asyncio.timeout(2) as timeout:

            async def trigger_after_provider_entry():
                while phase not in events:
                    await asyncio.sleep(0)
                timeout.reschedule(asyncio.get_running_loop().time())

            trigger = asyncio.create_task(trigger_after_provider_entry())
            try:
                await build_development_context(
                    snapshot(), analyzer, selector, assessor
                )
            finally:
                trigger.cancel()

    with pytest.raises(TimeoutError) as error:
        await run_with_timeout()
    cancelled = error.value.__cause__
    assert isinstance(cancelled, asyncio.CancelledError)
    assert phase in events
    if phase == "analysis":
        assert len(cancelled.llm_usage) == 1 and not cancelled.embedding_usage
    elif phase == "query":
        assert len(cancelled.llm_usage) == 1 and len(cancelled.embedding_usage) == 1
    elif phase == "document":
        assert len(cancelled.llm_usage) == 1 and len(cancelled.embedding_usage) == 2
    else:
        assert len(cancelled.llm_usage) == 2 and len(cancelled.embedding_usage) == 2
