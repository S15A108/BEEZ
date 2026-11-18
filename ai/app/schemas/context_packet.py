"""MCP-01 development context packet composed from a #131 selection (#132).

Provisional BE-to-AI contract. The BE authorizes the snapshot, supplies the
metadata below, re-validates every ID against the current baseline and stores
the immutable packet with its own content hash. The AI never creates IDs,
sources, constraints or approvals and never copies raw source text here.
"""

from typing import Annotated, Literal, Self

from pydantic import BaseModel, ConfigDict, Field, StringConstraints, model_validator

from app.services.retrieval.context_selection import DirectMatchTruncation, Id

# POL-02 (#95): summary-level strings are capped at 500 Unicode code points.
SUMMARY_CODE_POINTS = 500
# Proposed MCP-01 request bound; the shared spec still has to confirm it.
REQUEST_CODE_POINTS = 2000
# Bound for the serialized packet, same as the other internal AI routes.
MAX_PACKET_BYTES = 262_144
TRUNCATION_MARKER = "…"

DesignKind = Literal[
    "REQUIREMENT", "API_CONTRACT", "DATA_CONTRACT", "POLICY", "CONSTRAINT"
]
SelectionReason = Literal["MATCHED", "TASK_PLAN", "DEPENDENCY"]
UnresolvedKind = Literal[
    "NO_MATCH",
    "DEPENDENCY_UNVERIFIED",
    "POSSIBLE_CONFLICT",
    "INSUFFICIENT_EVIDENCE",
    "SELECTION_TRUNCATED",
]
# Kinds whose approved description is surfaced verbatim as a packet constraint.
CONSTRAINT_KINDS = frozenset({"POLICY", "CONSTRAINT"})

Location = Annotated[str, StringConstraints(min_length=1, max_length=1000)]
Summary = Annotated[
    str, StringConstraints(min_length=1, max_length=SUMMARY_CODE_POINTS)
]
RelatedTaskStatus = Literal["READY", "IN_PROGRESS", "WAITING", "COMPLETED", "CANCELLED"]


class PacketModel(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True, strict=True)


class PacketSource(PacketModel):
    """00-common Source: the exact document version and its display location."""

    document_id: Id
    document_version_id: Id
    version_no: int = Field(ge=1)
    location: Location


class DesignItemMeta(PacketModel):
    """BE-supplied identity of one approved item version in the snapshot."""

    design_item_version_id: Id
    design_item_id: Id
    key: Id
    kind: DesignKind
    version_no: int = Field(ge=1)
    source: PacketSource | None = None
    # The approved content.description; only POLICY/CONSTRAINT become constraints.
    description: Annotated[str, StringConstraints(max_length=10_000)] | None = None


class EvidenceMeta(PacketModel):
    """BE-supplied source of one evidence candidate (a document chunk)."""

    evidence_id: Id
    document_id: Id
    document_version_id: Id
    version_no: int = Field(ge=1)
    location: Location


class TaskMeta(PacketModel):
    """Current non-DRAFT task plan and status supplied by the BE."""

    task_id: Id
    plan_revision_id: Id
    status: RelatedTaskStatus


class PacketMetadata(PacketModel):
    """Everything the packet needs beyond the #131 snapshot, supplied by the BE."""

    design_items: tuple[DesignItemMeta, ...]
    evidence: tuple[EvidenceMeta, ...]
    task: TaskMeta | None = None

    @model_validator(mode="after")
    def unique_identities(self) -> Self:
        versions = [item.design_item_version_id for item in self.design_items]
        items = [item.design_item_id for item in self.design_items]
        evidence = [item.evidence_id for item in self.evidence]
        if any(len(ids) != len(set(ids)) for ids in (versions, items, evidence)):
            raise ValueError("duplicate metadata")
        return self


class RequestSummary(PacketModel):
    """Untrusted model hints over the original request; never a filter."""

    functions: tuple[Summary, ...] = ()
    domains: tuple[Summary, ...] = ()
    keywords: tuple[Summary, ...] = ()


class PacketDesignItem(PacketModel):
    design_item_version_id: Id
    design_item_id: Id
    key: Id
    kind: DesignKind
    version_no: int = Field(ge=1)
    reasons: tuple[SelectionReason, ...] = Field(min_length=1)
    # The source stored on the design item, as supplied. Its location and
    # document version are not verified against the current baseline, so it
    # is informational only and never copied into `sources`.
    source: PacketSource | None = None


class PacketRelatedTask(PacketModel):
    """00-common RelatedTask; the BE rechecks revision and status when saving."""

    task_id: Id
    plan_revision_id: Id
    status: RelatedTaskStatus


class UnresolvedReference(PacketModel):
    kind: Literal["design_item", "evidence"]
    id: Id


class UnresolvedDetail(PacketModel):
    """Structured form of one unresolved entry; quotes are never copied here."""

    kind: UnresolvedKind
    description: Annotated[str, StringConstraints(min_length=1, max_length=2000)]
    references: tuple[UnresolvedReference, ...] = ()


class DevelopmentContextPacket(PacketModel):
    """Packet content for MCP-01; `to_save_packet_args` is the BE storage input."""

    project_id: Id
    task_id: Id | None
    baseline_id: Id
    request: Annotated[
        str, StringConstraints(min_length=1, max_length=REQUEST_CODE_POINTS)
    ]
    search_status: Literal["COMPLETE"] = "COMPLETE"
    request_summary: RequestSummary | None = None
    design_items: tuple[PacketDesignItem, ...]
    # Same order as design_items; the BE validates each against the baseline.
    design_item_version_ids: tuple[Id, ...]
    constraints: tuple[Summary, ...]
    # Summary strings for the BE packet; details keep the structured form.
    unresolved: tuple[Summary, ...]
    unresolved_details: tuple[UnresolvedDetail, ...]
    related_tasks: tuple[PacketRelatedTask, ...]
    sources: tuple[PacketSource, ...]
    direct_match_truncation: DirectMatchTruncation | None = None

    @model_validator(mode="after")
    def consistent(self) -> Self:
        ordered = tuple(item.design_item_version_id for item in self.design_items)
        if self.design_item_version_ids != ordered:
            raise ValueError("design_item_version_ids must mirror design_items")
        if len(set(ordered)) != len(ordered):
            raise ValueError("duplicate design item")
        sources = [
            (s.document_id, s.document_version_id, s.version_no, s.location)
            for s in self.sources
        ]
        if len(set(sources)) != len(sources):
            raise ValueError("duplicate source")
        if len(self.unresolved) != len(self.unresolved_details):
            raise ValueError("unresolved summaries and details must align")
        if self.task_id is None and self.related_tasks:
            raise ValueError("related tasks require a task")
        return self

    def to_save_packet_args(self) -> dict:
        """Exactly the BE `SavePacketArgs` fields, snake_case and JSON-ready.

        Direct-match truncation has no structured field in the BE contract yet;
        when candidates were cut it is preserved as a SELECTION_TRUNCATED entry
        of `unresolved` so the stored packet still records the incompleteness.
        """
        return {
            "request": self.request,
            "project_id": self.project_id,
            "task_id": self.task_id,
            "expected_baseline_id": self.baseline_id,
            "design_item_version_ids": list(self.design_item_version_ids),
            "constraints": list(self.constraints),
            "unresolved": list(self.unresolved),
            "related_tasks": [
                task.model_dump(mode="json") for task in self.related_tasks
            ],
            "sources": [source.model_dump(mode="json") for source in self.sources],
        }
