"""Compose MCP-01 packet content from one authorized #131 selection (#132).

The BE still owns authorization, baseline freshness, packet storage and the
content hash. This module only joins the selection with BE-supplied metadata:
it never invents IDs, sources, constraints or approvals, never copies raw
source text, and fails instead of silently dropping anything but text length.
"""

import hmac
import json
from typing import Any

from pydantic import ValidationError

from app.schemas.context_packet import (
    CONSTRAINT_KINDS,
    MAX_PACKET_BYTES,
    SUMMARY_CODE_POINTS,
    TRUNCATION_MARKER,
    DevelopmentContextPacket,
    PacketDesignItem,
    PacketMetadata,
    PacketRelatedTask,
    PacketSource,
    RequestSummary,
    UnresolvedDetail,
    UnresolvedReference,
)
from app.schemas.retrieval import EmbeddingUsage
from app.services.llm_client import LLMResult
from app.services.retrieval.context_pipeline import ContextPipelineResult
from app.services.retrieval.context_request_analysis import SearchHints
from app.services.retrieval.context_selection import (
    SelectionInput,
    SelectionResult,
    selection_binding,
)

# Fixed Korean summaries for selection-level states; no model text is involved.
UNRESOLVED_TEXT = {
    "NO_MATCH": "검색을 마쳤지만 요청과 직접 일치하는 승인 설계·원문 근거가 없습니다.",
    "DEPENDENCY_UNVERIFIED": (
        "의존 관계 일부가 재확인 필요하거나 대상·버전을 현재 입력과 대조하지 못해 확장하지 않았습니다."
    ),
}


class ContextPacketError(Exception):
    """Sanitized failure; codes follow the common MCP-01 error table."""

    def __init__(
        self, code: str, message: str, usage: tuple[EmbeddingUsage, ...] = ()
    ) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        # Actual provider-call records made before composition failed.
        self.usage = usage


def truncate_summary(text: str, limit: int = SUMMARY_CODE_POINTS) -> str:
    """Cut to `limit` code points with a marker; IDs are never truncated."""
    text = text.strip()
    if len(text) <= limit:
        return text
    return text[: limit - len(TRUNCATION_MARKER)] + TRUNCATION_MARKER


def check_packet_metadata(snapshot: SelectionInput, metadata: PacketMetadata) -> None:
    """Reject metadata that does not describe exactly this snapshot, before any paid call."""
    items = {item.design_item_version_id: item for item in metadata.design_items}
    versions = {item.design_item_version_id: item for item in snapshot.items}
    if set(items) != set(versions) or any(
        items[version].design_item_id != versions[version].design_item_id
        for version in items
    ):
        raise ContextPacketError(
            "validation_failed", "design item metadata does not match the snapshot"
        )
    # All candidates of these kinds can later become constraints, so incomplete
    # metadata must fail before the selector incurs any embedding/model calls.
    if any(
        item.kind in CONSTRAINT_KINDS
        and (item.description is None or not item.description.strip())
        for item in metadata.design_items
    ):
        raise ContextPacketError(
            "validation_failed", "policy or constraint description is required"
        )
    evidence = {item.evidence_id: item for item in metadata.evidence}
    candidates = {item.evidence_id: item for item in snapshot.evidence}
    if set(evidence) != set(candidates) or any(
        evidence[key].document_version_id != candidates[key].document_version_id
        for key in evidence
    ):
        raise ContextPacketError(
            "validation_failed", "evidence metadata does not match the snapshot"
        )
    # The request may refer to an unplanned or DRAFT task. In that case the
    # BE omits related-task metadata; retain the request task_id but do not
    # fabricate a revision or status. Only the BE can verify this omission.
    if metadata.task is not None and metadata.task.task_id != snapshot.task_id:
        raise ContextPacketError(
            "validation_failed", "task metadata does not match the snapshot"
        )


def _require_binding(snapshot: SelectionInput, selection: SelectionResult) -> None:
    expected = selection_binding(
        snapshot,
        selection.design_items,
        selection.evidence_ids,
        selection.unresolved,
        selection.direct_match_truncation,
    )
    binding = selection.snapshot_binding
    if not isinstance(binding, str) or not hmac.compare_digest(
        binding.encode("ascii", "replace"), expected.encode("ascii")
    ):
        raise ContextPacketError(
            "validation_failed", "selection does not belong to this snapshot"
        )


def _review_details(
    review: LLMResult, selected_items: set[str], selected_evidence: set[str]
) -> list[UnresolvedDetail]:
    """Accept only candidates that passed review_unresolved over this selection."""
    result = review.result
    if (
        not isinstance(result, dict)
        or result.get("scope") != "selected_context_only"
        or not isinstance(result.get("candidates"), list)
    ):
        raise ContextPacketError("validation_failed", "unexpected review result")
    details = []
    for candidate in result["candidates"]:
        references = (
            candidate.get("references") if isinstance(candidate, dict) else None
        )
        if not isinstance(references, list) or not all(
            isinstance(ref, dict) for ref in references
        ):
            raise ContextPacketError("validation_failed", "unexpected review candidate")
        description = candidate.get("description")
        try:
            # Build the structured form only; quotes never enter the packet.
            detail = UnresolvedDetail(
                kind=candidate.get("kind"),
                description=description.strip()
                if isinstance(description, str)
                else description,
                references=tuple(
                    UnresolvedReference(kind=ref.get("kind"), id=ref.get("id"))
                    for ref in references
                ),
            )
        except ValidationError:
            raise ContextPacketError(
                "validation_failed", "unexpected review candidate"
            ) from None
        if detail.kind not in {"POSSIBLE_CONFLICT", "INSUFFICIENT_EVIDENCE"}:
            raise ContextPacketError("validation_failed", "unexpected review kind")
        for ref in detail.references:
            known = selected_items if ref.kind == "design_item" else selected_evidence
            if ref.id not in known:
                raise ContextPacketError(
                    "validation_failed", "review references an unselected record"
                )
        details.append(detail)
    return details


def compose_development_context(
    snapshot: SelectionInput,
    selection: SelectionResult,
    metadata: PacketMetadata,
    *,
    hints: SearchHints | None = None,
    review: LLMResult | None = None,
) -> DevelopmentContextPacket:
    """Join one selection with BE metadata into MCP-01 packet content."""
    if snapshot.search_status != "COMPLETE" or selection.search_status != "COMPLETE":
        raise ContextPacketError(
            "context_not_ready"
            if snapshot.search_status == "NOT_READY"
            else "search_failed",
            "the search did not complete",
        )
    check_packet_metadata(snapshot, metadata)
    _require_binding(snapshot, selection)
    if hints is not None and hints.query != snapshot.request:
        raise ContextPacketError(
            "validation_failed", "hints belong to a different request"
        )

    items = {item.design_item_version_id: item for item in metadata.design_items}
    evidence = {item.evidence_id: item for item in metadata.evidence}
    design_items = tuple(
        PacketDesignItem(
            design_item_version_id=selected.design_item_version_id,
            design_item_id=items[selected.design_item_version_id].design_item_id,
            key=items[selected.design_item_version_id].key,
            kind=items[selected.design_item_version_id].kind,
            version_no=items[selected.design_item_version_id].version_no,
            reasons=selected.reasons,
            source=items[selected.design_item_version_id].source,
        )
        for selected in selection.design_items
    )
    constraints = tuple(
        truncate_summary(meta.description)
        for selected in selection.design_items
        for meta in (items[selected.design_item_version_id],)
        if meta.kind in CONSTRAINT_KINDS
        and meta.description
        and meta.description.strip()
    )

    details: list[UnresolvedDetail] = [
        UnresolvedDetail(kind=code, description=UNRESOLVED_TEXT[code])
        for code in selection.unresolved
    ]
    if review is not None:
        details += _review_details(
            review,
            {item.design_item_version_id for item in selection.design_items},
            set(selection.evidence_ids),
        )
    truncation = selection.direct_match_truncation
    if truncation is not None and (truncation.items or truncation.evidence):
        # Keep the cut visible in the stored packet until the BE contract
        # gains a structured field for it.
        details.append(
            UnresolvedDetail(
                kind="SELECTION_TRUNCATED",
                description=(
                    f"직접 일치 후보 중 설계 {truncation.items}건, 원문 "
                    f"{truncation.evidence}건이 직접 선택 상한으로 제외됐습니다."
                ),
            )
        )

    unresolved = tuple(
        detail.kind
        if detail.kind in UNRESOLVED_TEXT
        else truncate_summary(f"{detail.kind}: {detail.description}")
        for detail in details
    )

    sources: list[PacketSource] = []
    seen: set[tuple[str, str, int, str]] = set()
    for evidence_id in selection.evidence_ids:
        meta = evidence[evidence_id]
        source = PacketSource(
            document_id=meta.document_id,
            document_version_id=meta.document_version_id,
            version_no=meta.version_no,
            location=meta.location,
        )
        _add_source(sources, seen, source)
    # Stored design-item sources may point outside the current baseline or
    # to an unverified location; the BE rejects such packets, so only the
    # evidence sources (baseline document versions) become packet sources.

    related_tasks = (
        (
            PacketRelatedTask(
                task_id=metadata.task.task_id,
                plan_revision_id=metadata.task.plan_revision_id,
                status=metadata.task.status,
            ),
        )
        if metadata.task is not None
        else ()
    )
    summary = (
        RequestSummary(
            functions=tuple(truncate_summary(x) for x in hints.functions),
            domains=tuple(truncate_summary(x) for x in hints.domains),
            keywords=tuple(truncate_summary(x) for x in hints.keywords),
        )
        if hints is not None
        else None
    )
    try:
        packet = DevelopmentContextPacket(
            project_id=snapshot.project_id,
            task_id=snapshot.task_id,
            baseline_id=snapshot.baseline_id,
            request=snapshot.request,
            request_summary=summary,
            design_items=design_items,
            design_item_version_ids=tuple(
                item.design_item_version_id for item in design_items
            ),
            constraints=constraints,
            unresolved=unresolved,
            unresolved_details=tuple(details),
            related_tasks=related_tasks,
            sources=tuple(sources),
            direct_match_truncation=selection.direct_match_truncation,
        )
    except ValidationError:
        raise ContextPacketError(
            "validation_failed", "packet content failed validation"
        ) from None
    if packet_size(packet) > MAX_PACKET_BYTES:
        # IDs and sources are never dropped to fit; the caller must narrow limits.
        raise ContextPacketError(
            "packet_too_large", "packet exceeds the transport bound", selection.usage
        )
    return packet


def compose_from_pipeline(
    snapshot: SelectionInput, result: ContextPipelineResult, metadata: PacketMetadata
) -> DevelopmentContextPacket:
    """Compose from build_development_context output over the same snapshot."""
    return compose_development_context(
        snapshot,
        result.selection,
        metadata,
        hints=result.hints,
        review=result.review,
    )


def packet_size(packet: DevelopmentContextPacket) -> int:
    return len(packet_json(packet).encode("utf-8"))


def packet_json(packet: DevelopmentContextPacket) -> str:
    return json.dumps(
        packet.model_dump(mode="json"), ensure_ascii=False, separators=(",", ":")
    )


def _add_source(
    sources: list[PacketSource],
    seen: set[tuple[str, str, int, str]],
    source: PacketSource,
) -> None:
    key = (
        source.document_id,
        source.document_version_id,
        source.version_no,
        source.location,
    )
    if key not in seen:
        seen.add(key)
        sources.append(source)


def packet_error_body(error: ContextPacketError) -> dict[str, Any]:
    """Common error envelope shared with the other AI routes and MCP tools."""
    return {"error": {"code": error.code, "message": error.message}}
