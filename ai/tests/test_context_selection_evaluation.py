"""The bounded evaluation must preserve failures without paid test calls."""

from types import SimpleNamespace

import pytest
from pydantic import SecretStr

from app.services.retrieval.context_selection import (
    ContextSelectionError,
    SelectionResult,
)
from evaluations import context_selection_131 as evaluation


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
