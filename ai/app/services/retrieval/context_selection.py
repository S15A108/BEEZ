"""Select development context from a BE-authorized, current-baseline snapshot.

This is an internal proposal, not an HTTP contract or an authorization layer.
The caller must perform project membership checks, retrieve a consistent current
baseline and recheck them before using the result. Raw source evidence is never
promoted to an approved design item, nor is a model allowed to create IDs.
"""

from typing import Annotated, Literal, Protocol

from pydantic import BaseModel, ConfigDict, Field, model_validator

from app.schemas.retrieval import EmbeddingUsage

Id = Annotated[str, Field(min_length=1, max_length=256, pattern=r"^\S+$")]
ShortText = Annotated[str, Field(min_length=1, max_length=2000)]
CandidateText = Annotated[str, Field(min_length=1, max_length=100_000)]


class _Frozen(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True, strict=True)


class ApprovedItem(_Frozen):
    design_item_id: Id
    design_item_version_id: Id
    project_id: Id
    baseline_id: Id
    status: Literal["APPROVED"]
    text: CandidateText


class ApprovedEvidence(_Frozen):
    evidence_id: Id
    document_version_id: Id
    project_id: Id
    baseline_id: Id
    text: CandidateText


class DesignRelation(_Frozen):
    from_design_item_id: Id
    to_design_item_id: Id
    from_design_item_version_id: Id
    to_design_item_version_id: Id
    relation: str
    origin: str
    status: str


class TaskDesignRef(_Frozen):
    design_item_id: Id
    design_item_version_id: Id


class SelectionInput(_Frozen):
    project_id: Id
    baseline_id: Id
    request: ShortText
    task_id: Id | None = None
    # Current baseline's document versions, supplied by BE, not by the model.
    document_version_ids: tuple[Id, ...]
    items: tuple[ApprovedItem, ...]
    evidence: tuple[ApprovedEvidence, ...]
    relations: tuple[DesignRelation, ...]
    task_design_refs: tuple[TaskDesignRef, ...]
    search_status: Literal["COMPLETE", "NOT_READY", "FAILED"]

    @model_validator(mode="after")
    def validate_snapshot(self):
        if not self.request.strip():
            raise ValueError("empty request")
        if len(set(self.document_version_ids)) != len(self.document_version_ids):
            raise ValueError("duplicate document version")
        item_ids = [item.design_item_id for item in self.items]
        version_ids = [item.design_item_version_id for item in self.items]
        evidence_ids = [item.evidence_id for item in self.evidence]
        if any(
            len(ids) != len(set(ids)) for ids in (item_ids, version_ids, evidence_ids)
        ):
            raise ValueError("duplicate candidate")
        for item in self.items:
            if (
                item.project_id != self.project_id
                or item.baseline_id != self.baseline_id
            ):
                raise ValueError("item outside snapshot")
            if not item.text.strip():
                raise ValueError("empty candidate")
        for item in self.evidence:
            if (
                item.project_id != self.project_id
                or item.baseline_id != self.baseline_id
                or item.document_version_id not in self.document_version_ids
            ):
                raise ValueError("evidence outside snapshot")
            if not item.text.strip():
                raise ValueError("empty candidate")
        if self.task_id is None and self.task_design_refs:
            raise ValueError("task references require a task")
        task_ids = [ref.design_item_id for ref in self.task_design_refs]
        if len(task_ids) != len(set(task_ids)):
            raise ValueError("duplicate task reference")
        by_id = {item.design_item_id: item for item in self.items}
        for ref in self.task_design_refs:
            item = by_id.get(ref.design_item_id)
            if (
                item is None
                or ref.design_item_version_id != item.design_item_version_id
            ):
                raise ValueError("task reference outside current snapshot")
        return self


class SelectionProposal(_Frozen):
    # The model returns only IDs from the supplied snapshot, never new objects.
    design_item_version_ids: tuple[Id, ...]
    evidence_ids: tuple[Id, ...]
    usage: tuple[EmbeddingUsage, ...] = ()


class SelectionProvider(Protocol):
    async def select(self, request: SelectionInput) -> SelectionProposal: ...


class SelectedItem(_Frozen):
    design_item_version_id: Id
    reasons: tuple[Literal["MATCHED", "DEPENDENCY", "TASK_PLAN"], ...]


class SelectionResult(_Frozen):
    design_items: tuple[SelectedItem, ...]
    evidence_ids: tuple[Id, ...]
    unresolved: tuple[Literal["NO_MATCH"], ...]
    search_status: Literal["COMPLETE"] = "COMPLETE"
    usage: tuple[EmbeddingUsage, ...] = ()


class ContextSelectionError(Exception):
    """Sanitized failure: do not return failed search as an empty success."""

    def __init__(self, code: str, usage: tuple[EmbeddingUsage, ...] = ()):
        super().__init__(code)
        self.code = code
        # A selector may preserve structured, actual provider-call records.
        self.usage = usage


async def select_context(
    request: SelectionInput, selector: SelectionProvider
) -> SelectionResult:
    if request.search_status != "COMPLETE":
        raise ContextSelectionError(
            "context_not_ready"
            if request.search_status == "NOT_READY"
            else "search_failed"
        )

    # If a complete search found no selectable candidates, no model call is needed.
    if request.items or request.evidence:
        try:
            proposal = await selector.select(request)
            if not isinstance(proposal, SelectionProposal):
                raise TypeError("invalid result")
        except ContextSelectionError:
            raise
        except Exception:  # noqa: BLE001
            # Provider exceptions can contain prompts, URLs, or secrets.
            raise ContextSelectionError("selection_failed") from None
    else:
        proposal = SelectionProposal(design_item_version_ids=(), evidence_ids=())

    matched = proposal.design_item_version_ids
    evidence_ids = proposal.evidence_ids
    versions = {item.design_item_version_id: item for item in request.items}
    by_item = {item.design_item_id: item for item in request.items}
    if (
        len(set(matched)) != len(matched)
        or len(set(evidence_ids)) != len(evidence_ids)
        or not set(matched).issubset(versions)
        or not set(evidence_ids).issubset(item.evidence_id for item in request.evidence)
    ):
        raise ContextSelectionError("invalid_selection", proposal.usage)

    reasons: dict[str, set[str]] = {}
    for version in matched:
        reasons.setdefault(version, set()).add("MATCHED")
    for ref in request.task_design_refs:
        reasons.setdefault(ref.design_item_version_id, set()).add("TASK_PLAN")

    # One hop only from directly matched/plan items; never follow newly added nodes.
    # Endpoints missing from the BE-supplied current snapshot are excluded, not
    # surfaced to the selector or returned as current approved design items.
    seeds = {versions[version].design_item_id for version in reasons}
    for relation in request.relations:
        if (
            relation.relation == "DEPENDS_ON"
            and relation.origin == "EXPLICIT"
            and relation.status == "CONFIRMED"
            and relation.from_design_item_id in seeds
            and relation.to_design_item_id in by_item
            and by_item[relation.from_design_item_id].design_item_version_id
            == relation.from_design_item_version_id
            and by_item[relation.to_design_item_id].design_item_version_id
            == relation.to_design_item_version_id
        ):
            reasons.setdefault(
                by_item[relation.to_design_item_id].design_item_version_id, set()
            ).add("DEPENDENCY")

    order = ("MATCHED", "TASK_PLAN", "DEPENDENCY")
    selected = tuple(
        SelectedItem(
            design_item_version_id=item.design_item_version_id,
            reasons=tuple(
                reason
                for reason in order
                if reason in reasons[item.design_item_version_id]
            ),
        )
        for item in request.items
        if item.design_item_version_id in reasons
    )
    return SelectionResult(
        design_items=selected,
        evidence_ids=evidence_ids,
        # A task reference is useful context, but does not prove the search
        # found a relevant item or source for this particular request.
        unresolved=("NO_MATCH",) if not matched and not evidence_ids else (),
        usage=proposal.usage,
    )
