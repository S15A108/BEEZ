"""MCP-01 packet composition over a #131 selection; no model or network calls."""

import json

import pytest

from app.schemas.context_packet import (
    MAX_PACKET_BYTES,
    SUMMARY_CODE_POINTS,
    DesignItemMeta,
    EvidenceMeta,
    PacketMetadata,
    PacketSource,
    TaskMeta,
)
from app.services.llm_client import LLMResult
from app.services.retrieval.context_packet import (
    ContextPacketError,
    check_packet_metadata,
    compose_development_context,
    compose_from_pipeline,
    packet_json,
    packet_size,
    truncate_summary,
)
from app.services.retrieval.context_pipeline import ContextPipelineResult
from app.services.retrieval.context_request_analysis import SearchHints
from app.services.retrieval.context_selection import (
    ApprovedEvidence,
    ApprovedItem,
    DesignRelation,
    DirectMatchTruncation,
    SelectionInput,
    SelectionProposal,
    TaskDesignRef,
    select_context,
)

RAW_TEXT = "비공개 업로드 설계 원문"
SOURCE = PacketSource(
    document_id="201",
    document_version_id="d1",
    version_no=1,
    location="3. 업로드 / UP-01",
)


class Selector:
    def __init__(self, items=("v1",), evidence=("e1",)):
        self.items, self.evidence = items, evidence

    async def select(self, data):
        return SelectionProposal(
            design_item_version_ids=self.items,
            evidence_ids=self.evidence,
            direct_match_truncation=DirectMatchTruncation(items=0, evidence=0),
        )


def item(item_id, version, text):
    return ApprovedItem(
        design_item_id=item_id,
        design_item_version_id=version,
        project_id="p",
        baseline_id="b",
        status="APPROVED",
        text=text,
    )


def snapshot(
    *, task=True, relation_from=("i1", "v1"), relation_target="i3", evidence_count=2
):
    evidence = [
        ApprovedEvidence(
            evidence_id=f"e{i}",
            document_version_id="d1" if i == 1 else "d2",
            project_id="p",
            baseline_id="b",
            text=f"{RAW_TEXT} {i}",
        )
        for i in range(1, evidence_count + 1)
    ]
    return SelectionInput(
        project_id="p",
        baseline_id="b",
        request="업로드 제한 구현",
        task_id="t1" if task else None,
        document_version_ids=("d1", "d2"),
        items=(
            item("i1", "v1", f"{RAW_TEXT} API"),
            item("i2", "v2", "업로드는 30 MB까지 허용한다."),
            item("i3", "v3", f"{RAW_TEXT} 데이터"),
        ),
        evidence=tuple(evidence),
        relations=(
            DesignRelation(
                from_design_item_id=relation_from[0],
                to_design_item_id=relation_target,
                from_design_item_version_id=relation_from[1],
                to_design_item_version_id="v3",
                relation="DEPENDS_ON",
                origin="EXPLICIT",
                status="CONFIRMED",
            ),
        ),
        task_design_refs=(
            (TaskDesignRef(design_item_id="i2", design_item_version_id="v2"),)
            if task
            else ()
        ),
        search_status="COMPLETE",
    )


def metadata(*, task=True, evidence_count=2, location="5. 데이터 / DAT-01"):
    return PacketMetadata(
        design_items=(
            DesignItemMeta(
                design_item_version_id="v1",
                design_item_id="i1",
                key="API-01",
                kind="API_CONTRACT",
                version_no=1,
                source=SOURCE,
                description="업로드 API",
            ),
            DesignItemMeta(
                design_item_version_id="v2",
                design_item_id="i2",
                key="POL-01",
                kind="POLICY",
                version_no=2,
                description="업로드는 30 MB까지 허용한다.",
            ),
            DesignItemMeta(
                design_item_version_id="v3",
                design_item_id="i3",
                key="DAT-01",
                kind="DATA_CONTRACT",
                version_no=1,
                source=PacketSource(
                    document_id="202",
                    document_version_id="d2",
                    version_no=1,
                    location=location,
                ),
            ),
        ),
        evidence=tuple(
            EvidenceMeta(
                evidence_id=f"e{i}",
                document_id="201" if i == 1 else "202",
                document_version_id="d1" if i == 1 else "d2",
                version_no=1,
                location=SOURCE.location if i == 1 else f"{location} #{i}",
            )
            for i in range(1, evidence_count + 1)
        ),
        task=TaskMeta(task_id="t1", plan_revision_id="pr1", status="IN_PROGRESS")
        if task
        else None,
    )


def review(candidates):
    return LLMResult(
        result={
            "scope": "selected_context_only",
            "selection_unresolved": [],
            "candidates": candidates,
        },
        model="fake",
        usage=(),
    )


@pytest.mark.asyncio
async def test_packet_joins_selection_with_metadata_in_snapshot_order():
    data = snapshot()
    selection = await select_context(data, Selector())
    packet = compose_development_context(data, selection, metadata())

    assert [(i.design_item_version_id, i.reasons) for i in packet.design_items] == [
        ("v1", ("MATCHED",)),
        ("v2", ("TASK_PLAN",)),
        ("v3", ("DEPENDENCY",)),
    ]
    assert packet.design_item_version_ids == ("v1", "v2", "v3")
    assert packet.design_items[0].key == "API-01"
    assert packet.constraints == ("업로드는 30 MB까지 허용한다.",)
    assert packet.unresolved == () and packet.unresolved_details == ()
    # Only verified evidence sources become packet sources; the stored item
    # sources stay on design_items[].source and are never merged.
    assert [s.location for s in packet.sources] == [SOURCE.location]
    assert packet.design_items[2].source.location == "5. 데이터 / DAT-01"
    assert [t.model_dump() for t in packet.related_tasks] == [
        {"task_id": "t1", "plan_revision_id": "pr1", "status": "IN_PROGRESS"}
    ]
    assert packet.direct_match_truncation == DirectMatchTruncation(items=0, evidence=0)
    assert RAW_TEXT not in packet_json(packet)

    args = packet.to_save_packet_args()
    assert set(args) == {
        "request",
        "project_id",
        "task_id",
        "design_item_version_ids",
        "constraints",
        "unresolved",
        "related_tasks",
        "sources",
    }
    assert args["design_item_version_ids"] == ["v1", "v2", "v3"]
    assert args["sources"][0] == SOURCE.model_dump()
    json.dumps(args)


@pytest.mark.asyncio
async def test_no_match_and_unverified_dependency_become_fixed_entries():
    # Only edges leaving a selected or task-referenced item can be unverified.
    data = snapshot(relation_from=("i2", "v2"), relation_target="missing")
    selection = await select_context(data, Selector(items=(), evidence=()))
    packet = compose_development_context(data, selection, metadata())

    assert packet.unresolved == ("NO_MATCH", "DEPENDENCY_UNVERIFIED")
    assert [d.kind for d in packet.unresolved_details] == list(packet.unresolved)
    assert all(d.references == () for d in packet.unresolved_details)
    assert packet.design_item_version_ids == ("v2",)
    assert packet.sources == () and packet.constraints == (
        "업로드는 30 MB까지 허용한다.",
    )
    assert "missing" not in packet_json(packet)


@pytest.mark.asyncio
async def test_review_candidates_keep_references_but_never_quotes():
    data = snapshot()
    selection = await select_context(data, Selector())
    long_description = "근거가 부족합니다. " * 60
    reviewed = review(
        [
            {
                "kind": "POSSIBLE_CONFLICT",
                "description": "업로드 한도가 서로 다릅니다.",
                "references": [
                    {"kind": "design_item", "id": "v1", "quote": RAW_TEXT},
                    {"kind": "evidence", "id": "e1", "quote": RAW_TEXT},
                ],
            },
            {
                "kind": "INSUFFICIENT_EVIDENCE",
                "description": long_description,
                "references": [],
            },
        ]
    )
    packet = compose_development_context(data, selection, metadata(), review=reviewed)

    assert packet.unresolved[0] == "POSSIBLE_CONFLICT: 업로드 한도가 서로 다릅니다."
    assert len(packet.unresolved[1]) == SUMMARY_CODE_POINTS
    assert packet.unresolved[1].startswith("INSUFFICIENT_EVIDENCE: ")
    assert packet.unresolved[1].endswith("…")
    assert packet.unresolved_details[1].description == long_description.strip()
    assert [r.model_dump() for r in packet.unresolved_details[0].references] == [
        {"kind": "design_item", "id": "v1"},
        {"kind": "evidence", "id": "e1"},
    ]
    assert RAW_TEXT not in packet_json(packet)
    assert len(packet.to_save_packet_args()["unresolved"]) == 2


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "candidate",
    [
        {
            "kind": "POSSIBLE_CONFLICT",
            "description": "x",
            "references": [{"kind": "design_item", "id": "v9", "quote": "q"}],
        },
        {
            "kind": "POSSIBLE_CONFLICT",
            "description": "x",
            "references": [{"kind": "evidence", "id": "e2", "quote": "q"}],
        },
        {"kind": "NO_MATCH", "description": "x", "references": []},
        {"kind": "POSSIBLE_CONFLICT", "description": "", "references": []},
    ],
)
async def test_review_candidates_outside_selection_or_shape_are_rejected(candidate):
    data = snapshot()
    selection = await select_context(data, Selector())
    with pytest.raises(ContextPacketError) as error:
        compose_development_context(
            data, selection, metadata(), review=review([candidate])
        )
    assert error.value.code == "validation_failed"


@pytest.mark.parametrize("defect", ["missing_item", "evidence_version", "task"])
def test_metadata_mismatch_is_rejected_before_any_selection(defect):
    data = snapshot()
    meta = metadata()
    if defect == "missing_item":
        meta = meta.model_copy(update={"design_items": meta.design_items[:2]})
    elif defect == "evidence_version":
        changed = meta.evidence[0].model_copy(update={"document_version_id": "d2"})
        meta = meta.model_copy(update={"evidence": (changed, meta.evidence[1])})
    else:
        meta = meta.model_copy(update={"task": None})
    with pytest.raises(ContextPacketError) as error:
        check_packet_metadata(data, meta)
    assert error.value.code == "validation_failed"


@pytest.mark.asyncio
async def test_selection_from_another_snapshot_or_serialized_is_rejected():
    data = snapshot()
    selection = await select_context(data, Selector())
    other = data.model_copy(update={"request": "다른 요청"})
    with pytest.raises(ContextPacketError) as error:
        compose_development_context(other, selection, metadata())
    assert error.value.code == "validation_failed"

    serialized = type(selection).model_validate_json(selection.model_dump_json())
    with pytest.raises(ContextPacketError):
        compose_development_context(data, serialized, metadata())


@pytest.mark.asyncio
async def test_hints_fill_request_summary_only_for_the_same_request():
    data = snapshot()
    selection = await select_context(data, Selector())
    hints = SearchHints(
        query=data.request,
        functions=("업로드",),
        domains=(),
        keywords=("제한",),
        model="fake",
        usage=(),
    )
    packet = compose_development_context(data, selection, metadata(), hints=hints)
    assert packet.request_summary.model_dump(mode="json") == {
        "functions": ["업로드"],
        "domains": [],
        "keywords": ["제한"],
    }
    with pytest.raises(ContextPacketError):
        compose_development_context(
            data,
            selection,
            metadata(),
            hints=SearchHints("다른 요청", (), (), (), "fake", ()),
        )


@pytest.mark.asyncio
async def test_pipeline_result_composes_hints_selection_and_review_together():
    data = snapshot()
    selection = await select_context(data, Selector())
    result = ContextPipelineResult(
        hints=SearchHints(data.request, ("업로드",), (), (), "fake", ()),
        selection=selection,
        review=review([]),
        llm_usage=(),
        embedding_usage=(),
    )
    packet = compose_from_pipeline(data, result, metadata())
    assert packet.request_summary.functions == ("업로드",)
    assert packet.design_item_version_ids == ("v1", "v2", "v3")


@pytest.mark.asyncio
async def test_search_status_other_than_complete_is_not_a_packet():
    data = snapshot()
    selection = await select_context(data, Selector())
    not_ready = data.model_copy(update={"search_status": "NOT_READY"})
    with pytest.raises(ContextPacketError) as error:
        compose_development_context(not_ready, selection, metadata())
    assert error.value.code == "context_not_ready"


@pytest.mark.asyncio
async def test_oversized_packet_fails_instead_of_dropping_sources():
    count = 300
    data = snapshot(evidence_count=count)
    selection = await select_context(
        data, Selector(items=(), evidence=tuple(f"e{i}" for i in range(1, count + 1)))
    )
    meta = metadata(evidence_count=count, location="위치 " + "가" * 990)
    with pytest.raises(ContextPacketError) as error:
        compose_development_context(data, selection, meta)
    assert error.value.code == "packet_too_large"

    small = compose_development_context(
        snapshot(), await select_context(snapshot(), Selector()), metadata()
    )
    assert packet_size(small) < MAX_PACKET_BYTES


def test_truncate_summary_keeps_short_text_and_marks_cuts():
    assert truncate_summary("  짧은 요약  ") == "짧은 요약"
    cut = truncate_summary("a" * 600)
    assert len(cut) == SUMMARY_CODE_POINTS and cut.endswith("…")


@pytest.mark.asyncio
async def test_direct_match_truncation_is_preserved_in_stored_unresolved():
    class TruncatingSelector(Selector):
        async def select(self, data):
            return SelectionProposal(
                design_item_version_ids=("v1",),
                evidence_ids=("e1",),
                direct_match_truncation=DirectMatchTruncation(items=1, evidence=1),
            )

    data = snapshot()
    selection = await select_context(data, TruncatingSelector())
    packet = compose_development_context(data, selection, metadata())
    assert packet.direct_match_truncation == DirectMatchTruncation(items=1, evidence=1)
    assert packet.unresolved == (
        "SELECTION_TRUNCATED: 직접 일치 후보 중 설계 1건, 원문 1건이 직접 선택 상한으로 제외됐습니다.",
    )
    assert packet.unresolved_details[0].kind == "SELECTION_TRUNCATED"
    assert packet.to_save_packet_args()["unresolved"] == list(packet.unresolved)
