"""The bounded evaluation must preserve failures without paid test calls."""

from types import SimpleNamespace

import pytest
from pydantic import SecretStr

from app.schemas.retrieval import (
    EmbeddingPolicy,
    EmbeddingProfile,
    EmbeddingUsage,
    EmbeddingVector,
    ProviderEmbeddingBatch,
)
from app.services.retrieval.context_selection import (
    ApprovedItem,
    ContextSelectionError,
    SelectedItem,
    SelectionInput,
    SelectionResult,
)
from evaluations import context_selection_131 as evaluation


def test_report_distinguishes_direct_match_from_task_and_dependency():
    case = {
        "id": "test",
        "expected_design_items": [
            {"design_item_version_id": "v1", "reasons": ["MATCHED"]},
            {"design_item_version_id": "v2", "reasons": ["DEPENDENCY"]},
            {"design_item_version_id": "v3", "reasons": ["TASK_PLAN"]},
        ],
        "expected_evidence_ids": [],
    }
    result = SelectionResult(
        design_items=(
            SelectedItem(design_item_version_id="v1", reasons=("TASK_PLAN",)),
            SelectedItem(design_item_version_id="v2", reasons=("DEPENDENCY",)),
            SelectedItem(design_item_version_id="v3", reasons=("MATCHED", "TASK_PLAN")),
        ),
        evidence_ids=(),
        unresolved=(),
    )
    report = evaluation.assess(case, result)
    # The final set is correct, but v1 was not directly matched.
    assert report["missing_items"] == report["extra_items"] == []
    assert report["items_by_reason"] == {
        "MATCHED": ["v3"],
        "TASK_PLAN": ["v1", "v3"],
        "DEPENDENCY": ["v2"],
    }
    assert report["fixture_expected_items_by_reason"]["MATCHED"] == ["v1"]
    assert report["direct_match_truncation"] is None
    assert "unresolved_matches_fixture" not in report


@pytest.mark.parametrize(
    ("unresolved", "matches"), [((), False), (("NO_MATCH",), True)]
)
def test_report_checks_no_match_only_when_fixture_specifies_it(unresolved, matches):
    case = {
        "id": "no-match",
        "expected_design_items": [],
        "expected_evidence_ids": [],
        "expected_unresolved": ["NO_MATCH"],
    }
    result = SelectionResult(design_items=(), evidence_ids=(), unresolved=unresolved)
    report = evaluation.assess(case, result)
    assert report["unresolved_matches_fixture"] is matches


@pytest.mark.asyncio
async def test_threshold_comparison_reuses_actual_calls_and_recomputes_selection(
    monkeypatch,
):
    class Counter:
        tokenizer_id = "characters"
        max_input_tokens = 1000

        def count(self, text):
            return len(text)

    class Provider:
        def __init__(self):
            self.calls = []

        async def embed(self, texts, profile, purpose, request_key):
            self.calls.append(purpose)
            vectors = {"query": (1.0, 0.0), "target": (0.8, 0.6)}
            return ProviderEmbeddingBatch(
                profile=profile,
                usage=EmbeddingUsage(
                    request_key=request_key,
                    model=profile.model,
                    latency_ms=1,
                    failed=False,
                    tokens_known=True,
                    input_tokens=3,
                    output_tokens=0,
                ),
                vectors=tuple(
                    EmbeddingVector(key=t.key, embedding=vectors[t.text]) for t in texts
                ),
            )

    profile = EmbeddingProfile(
        profile_id="fixture",
        provider="fake",
        model="fake",
        dimension=2,
        tokenizer_id="characters",
        max_input_tokens=1000,
    )
    item = ApprovedItem(
        design_item_id="1",
        design_item_version_id="v1",
        project_id="p",
        baseline_id="b",
        status="APPROVED",
        text="target",
    )
    snapshot = SelectionInput(
        project_id="p",
        baseline_id="b",
        request="query",
        document_version_ids=(),
        items=(item,),
        evidence=(),
        relations=(),
        task_design_refs=(),
        search_status="COMPLETE",
    )
    provider = Provider()
    primary, comparisons = await evaluation.evaluate_thresholds(
        snapshot,
        provider,
        profile,
        Counter(),
        EmbeddingPolicy(max_attempts=1),
        threshold=0.9,
        comparisons=(0.7,),
        max_items=1,
        max_evidence=1,
    )
    assert primary.unresolved == ("NO_MATCH",)
    assert [x.design_item_version_id for x in comparisons[0][1].design_items] == ["v1"]
    assert provider.calls == ["query", "document"]
    assert len(primary.usage) == 2
    assert comparisons[0][1].usage == ()

    async def fail_replay(*args, **kwargs):
        raise ValueError("sensitive replay error")

    monkeypatch.setattr(evaluation._ReplayProvider, "embed", fail_replay)
    provider.calls.clear()
    with pytest.raises(ContextSelectionError) as exc:
        await evaluation.evaluate_thresholds(
            snapshot,
            provider,
            profile,
            Counter(),
            EmbeddingPolicy(max_attempts=1),
            threshold=0.9,
            comparisons=(0.7,),
            max_items=1,
            max_evidence=1,
        )
    assert exc.value.code == "selection_failed"
    assert len(exc.value.usage) == 2
    assert "sensitive" not in str(exc.value)
    assert provider.calls == ["query", "document"]


@pytest.mark.asyncio
async def test_invalid_comparison_is_rejected_before_provider_calls():
    with pytest.raises(ValueError, match="threshold"):
        await evaluation.evaluate_thresholds(
            None,
            None,
            None,
            None,
            None,
            threshold=0.6,
            comparisons=(float("nan"),),
            max_items=1,
            max_evidence=1,
        )


@pytest.mark.asyncio
async def test_comparison_run_reports_each_case_without_more_provider_calls(
    tmp_path, monkeypatch
):
    class Counter:
        tokenizer_id = "characters"
        max_input_tokens = 10000

        def count(self, text):
            return len(text)

    class Provider:
        def __init__(self):
            self.calls = []

        async def embed(self, texts, profile, purpose, request_key):
            self.calls.append(purpose)
            vector = (1.0, 0.0) if purpose == "query" else (0.0, 1.0)
            return ProviderEmbeddingBatch(
                profile=profile,
                usage=EmbeddingUsage(
                    request_key=request_key,
                    model=profile.model,
                    latency_ms=1,
                    failed=False,
                    tokens_known=True,
                    input_tokens=1,
                    output_tokens=0,
                ),
                vectors=tuple(
                    EmbeddingVector(key=t.key, embedding=vector) for t in texts
                ),
            )

    provider = Provider()
    profile = EmbeddingProfile(
        profile_id="fixture",
        provider="fake",
        model="fake",
        dimension=2,
        tokenizer_id="characters",
        max_input_tokens=10000,
    )
    monkeypatch.setattr(
        evaluation, "Settings", lambda **_: SimpleNamespace(gms_key=SecretStr("test"))
    )
    monkeypatch.setattr(evaluation, "embedding_profile", lambda _: profile)
    monkeypatch.setattr(evaluation, "EmbeddingTokenCounter", Counter)
    monkeypatch.setattr(evaluation, "GmsEmbeddingProvider", lambda *_: provider)
    output = tmp_path / "comparison.json"
    args = SimpleNamespace(
        env_file=tmp_path / "none",
        output=output,
        threshold=0.8,
        compare_threshold=[0.0, 0.5],
        max_items=2,
        max_evidence=2,
    )
    assert await evaluation.run(args) == 0
    saved = evaluation.json.loads(output.read_text(encoding="utf-8"))
    assert provider.calls == ["query", "document"] * 5
    assert saved["compare_thresholds"] == [0.0, 0.5]
    assert saved["report_version"] == 4
    assert len(saved["cases"]) == 5
    assert all(len(case["usage"]) == 2 for case in saved["cases"])
    assert all(len(case["threshold_comparisons"]) == 2 for case in saved["cases"])
    assert all(
        "items_by_reason" in comparison
        for case in saved["cases"]
        for comparison in case["threshold_comparisons"]
    )
    assert all(
        comparison["direct_match_truncation"] is not None
        for case in saved["cases"]
        for comparison in case["threshold_comparisons"]
    )
    assert all(
        "usage" not in comparison
        for case in saved["cases"]
        for comparison in case["threshold_comparisons"]
    )


@pytest.mark.asyncio
async def test_failed_case_is_checkpointed_and_no_later_call_is_made(
    tmp_path, monkeypatch
):
    calls = []

    async def select(snapshot, selector):
        calls.append(snapshot.request)
        if len(calls) == 2:
            raise ContextSelectionError("model_unavailable")
        return SelectionResult(
            design_items=(), evidence_ids=(), unresolved=("NO_MATCH",)
        )

    monkeypatch.setattr(
        evaluation, "Settings", lambda **_: SimpleNamespace(gms_key=SecretStr("test"))
    )
    monkeypatch.setattr(
        evaluation,
        "embedding_profile",
        lambda _: SimpleNamespace(model="fake", dimension=2, max_input_tokens=1000),
    )
    monkeypatch.setattr(
        evaluation, "EmbeddingTokenCounter", lambda: SimpleNamespace(count=len)
    )
    monkeypatch.setattr(evaluation, "GmsEmbeddingProvider", lambda *_: object())
    monkeypatch.setattr(
        evaluation, "EmbeddingContextSelector", lambda *_, **__: object()
    )
    monkeypatch.setattr(evaluation, "select_context", select)
    output = tmp_path / "result.json"
    args = SimpleNamespace(
        env_file=tmp_path / "none",
        output=output,
        threshold=0.65,
        max_items=2,
        max_evidence=2,
    )
    assert await evaluation.run(args) == 1
    stored = evaluation.json.loads(output.read_text(encoding="utf-8"))
    assert len(calls) == 2
    assert len(stored["cases"]) == 1
    assert stored["error"]["case"] == "task-plan-adds-current-reference"
    assert stored["error"]["code"] == "model_unavailable"
    assert stored["in_flight"] is None
    with pytest.raises(FileExistsError):
        await evaluation.run(args)
