import json
from pathlib import Path

import pytest
from pydantic import ValidationError

from app.services.retrieval.context_selection import (
    ApprovedEvidence,
    ApprovedItem,
    ContextSelectionError,
    DesignRelation,
    SelectionInput,
    SelectionProposal,
    TaskDesignRef,
    select_context,
)


def item(item_id, version_id, **changes):
    values = {
        "design_item_id": item_id,
        "design_item_version_id": version_id,
        "project_id": "project",
        "baseline_id": "baseline",
        "status": "APPROVED",
        "text": "approved",
    }
    values.update(changes)
    return ApprovedItem(**values)


def relation(source, target, **changes):
    values = {
        "from_design_item_id": source,
        "to_design_item_id": target,
        "from_design_item_version_id": source + "-v1",
        "to_design_item_version_id": target + "-v1",
        "relation": "DEPENDS_ON",
        "origin": "EXPLICIT",
        "status": "CONFIRMED",
    }
    values.update(changes)
    return DesignRelation(**values)


def snapshot(**changes):
    values = {
        "project_id": "project",
        "baseline_id": "baseline",
        "request": "로그인 구현",
        "task_id": None,
        "document_version_ids": ("document-v1",),
        "items": (item("A", "A-v1"), item("B", "B-v1"), item("C", "C-v1")),
        "evidence": (
            ApprovedEvidence(
                evidence_id="e1",
                document_version_id="document-v1",
                project_id="project",
                baseline_id="baseline",
                text="원문",
            ),
        ),
        "relations": (),
        "task_design_refs": (),
        "search_status": "COMPLETE",
    }
    values.update(changes)
    return SelectionInput(**values)


class FakeSelector:
    def __init__(self, items=(), evidence=()):
        self.proposal = SelectionProposal(
            design_item_version_ids=items, evidence_ids=evidence
        )
        self.calls = 0

    async def select(self, request):
        self.calls += 1
        return self.proposal


@pytest.mark.asyncio
async def test_matching_and_task_refs_expand_only_confirmed_forward_one_hop():
    request = snapshot(
        task_id="task",
        task_design_refs=(
            TaskDesignRef(design_item_id="B", design_item_version_id="B-v1"),
        ),
        relations=(
            relation("A", "B"),
            relation("B", "C"),
            relation("C", "A"),
            relation("A", "C", status="NEEDS_RECHECK"),
            relation("A", "C", origin="AI_CANDIDATE"),
            relation("A", "outside"),
            relation("C", "outside"),
        ),
    )
    result = await select_context(request, FakeSelector(("A-v1",), ("e1",)))
    assert [(x.design_item_version_id, x.reasons) for x in result.design_items] == [
        ("A-v1", ("MATCHED",)),
        ("B-v1", ("TASK_PLAN", "DEPENDENCY")),
        ("C-v1", ("DEPENDENCY",)),
    ]
    assert result.evidence_ids == ("e1",)
    assert not result.unresolved


@pytest.mark.asyncio
async def test_cycle_cannot_expand_past_one_hop_or_reverse():
    result = await select_context(
        snapshot(
            relations=(
                relation("A", "B"),
                relation("B", "C"),
                relation("B", "A"),
                relation("C", "A"),
            )
        ),
        FakeSelector(("A-v1",)),
    )
    assert [x.design_item_version_id for x in result.design_items] == ["A-v1", "B-v1"]
    assert result.design_items[0].reasons == ("MATCHED",)


@pytest.mark.asyncio
async def test_unconfirmed_or_stale_edges_cannot_expand():
    result = await select_context(
        snapshot(
            relations=(
                relation("A", "B", from_design_item_version_id="A-old"),
                relation("A", "B", to_design_item_version_id="B-old"),
                relation("A", "B", status="NEEDS_RECHECK"),
                relation("A", "B", origin="AI_CANDIDATE"),
                relation("A", "B", relation="RELATES_TO"),
            )
        ),
        FakeSelector(("A-v1",)),
    )
    assert [x.design_item_version_id for x in result.design_items] == ["A-v1"]


@pytest.mark.asyncio
async def test_evidence_only_and_successful_empty_are_distinct_from_failure():
    only = await select_context(snapshot(items=()), FakeSelector(evidence=("e1",)))
    assert (
        not only.design_items and only.evidence_ids == ("e1",) and not only.unresolved
    )
    fake = FakeSelector()
    empty = await select_context(snapshot(items=(), evidence=()), fake)
    assert empty.unresolved == ("NO_MATCH",) and fake.calls == 0
    for status, code in (
        ("NOT_READY", "context_not_ready"),
        ("FAILED", "search_failed"),
    ):
        with pytest.raises(ContextSelectionError, match=code):
            await select_context(snapshot(search_status=status), fake)
    assert fake.calls == 0


@pytest.mark.asyncio
async def test_rejects_unknown_or_duplicate_model_ids_and_provider_failure():
    for fake in (
        FakeSelector(("unknown",)),
        FakeSelector(evidence=("unknown",)),
        FakeSelector(("A-v1", "A-v1")),
    ):
        with pytest.raises(ContextSelectionError, match="invalid_selection"):
            await select_context(snapshot(), fake)

    class Failure:
        async def select(self, request):
            raise RuntimeError("provider response with sensitive data")

    with pytest.raises(ContextSelectionError, match="selection_failed"):
        await select_context(snapshot(), Failure())


def test_invalid_scope_or_task_ref_rejected_before_model_call():
    with pytest.raises(ValidationError):
        snapshot(items=(item("A", "A-v1", baseline_id="old"),))
    with pytest.raises(ValidationError):
        snapshot(
            evidence=(
                ApprovedEvidence(
                    evidence_id="foreign",
                    document_version_id="other",
                    project_id="project",
                    baseline_id="baseline",
                    text="원문",
                ),
            )
        )
    with pytest.raises(ValidationError):
        snapshot(
            task_design_refs=(
                TaskDesignRef(design_item_id="A", design_item_version_id="A-v1"),
            )
        )
    with pytest.raises(ValidationError):
        snapshot(
            task_id="task",
            task_design_refs=(
                TaskDesignRef(design_item_id="outside", design_item_version_id="old"),
            ),
        )
    with pytest.raises(ValidationError):
        snapshot(
            task_id="task",
            task_design_refs=(
                TaskDesignRef(design_item_id="A", design_item_version_id="old"),
            ),
        )
    with pytest.raises(ValidationError):
        snapshot(request="   ")


@pytest.mark.asyncio
async def test_recorded_nara_fake_scenarios_are_contract_regressions():
    fixture_path = (
        Path(__file__).parent / "fixtures/retrieval/context-selection-131.json"
    )
    data = json.loads(fixture_path.read_text(encoding="utf-8"))
    assert data["label_author"] == "agent-authored; human review pending"
    for case in data["scenarios"]:
        values = dict(data["snapshot"])
        values["request"] = case["request"]
        for key in ("task_id", "task_design_refs", "search_status"):
            if key in case:
                values[key] = case[key]
        if "evidence_override" in case:
            values["evidence"] = case["evidence_override"]
        request = SelectionInput.model_validate_json(
            json.dumps(values, ensure_ascii=False)
        )
        proposal = case.get("fake_proposal", {})
        fake = FakeSelector(
            tuple(proposal.get("design_item_version_ids", ())),
            tuple(proposal.get("evidence_ids", ())),
        )
        if "expected_error" in case:
            with pytest.raises(ContextSelectionError, match=case["expected_error"]):
                await select_context(request, fake)
            assert fake.calls == case["expect_provider_calls"], case["id"]
            continue
        result = await select_context(request, fake)
        selected = [x.model_dump(mode="json") for x in result.design_items]
        if "expected_irrelevant_ids" in case:
            # A valid ID alone cannot prove semantic relevance; deliberately
            # preserve the incorrect proposal as a negative control.
            ids = {x.design_item_version_id for x in result.design_items} | set(
                result.evidence_ids
            )
            assert set(case["expected_irrelevant_ids"]).issubset(ids)
            continue
        assert selected == case["expected_design_items"], case["id"]
        assert list(result.evidence_ids) == case["expected_evidence_ids"], case["id"]
        assert list(result.unresolved) == case.get("expected_unresolved", []), case[
            "id"
        ]
        ids = {x.design_item_version_id for x in result.design_items} | set(
            result.evidence_ids
        )
        assert ids.isdisjoint(case.get("excluded_ids", [])), case["id"]
