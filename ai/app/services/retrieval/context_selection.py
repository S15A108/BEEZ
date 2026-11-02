"""Select development context from a BE-authorized, current-baseline snapshot.

This is an internal proposal, not an HTTP contract or an authorization layer.
The caller must perform project membership checks, retrieve a consistent current
baseline and recheck them before using the result. Raw source evidence is never
promoted to an approved design item, nor is a model allowed to create IDs.
"""

import hashlib
import json
from typing import Annotated, Literal, Protocol

from pydantic import BaseModel, ConfigDict, Field, model_validator

from app.schemas.retrieval import EmbeddingUsage

Id = Annotated[str, Field(min_length=1, max_length=256, pattern=r"^\S+$")]
ShortText = Annotated[str, Field(min_length=1, max_length=2000)]
CandidateText = Annotated[str, Field(min_length=1, max_length=100_000)]

# One upper bound per candidate kind, shared by the HTTP schema, the server
# limits and the unresolved review so no stage rejects what another allowed.
MAX_CANDIDATES_PER_KIND = 200

# Only stable, non-sensitive codes may cross the selector boundary.
SAFE_SELECTION_ERROR_CODES = frozenset(
    {
        "invalid_embedding_input",
        "embedding_profile_mismatch",
        "embedding_limit_exceeded",
        "tokenizer_failure",
        "embedding_timeout",
        "invalid_embedding_usage",
        "embedding_provider_failed",
        "invalid_embedding_output",
        "selection_incomplete",
        "selection_failed",
        "context_not_ready",
        "search_failed",
        "invalid_selection",
    }
)


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
        # A BE current dependency set has one row per directed item pair.
        # Reject mixed current/history rows instead of expanding an old
        # CONFIRMED edge when the same pair is now released or rechecked.
        dependency_pairs = [
            (edge.from_design_item_id, edge.to_design_item_id)
            for edge in self.relations
            if edge.relation == "DEPENDS_ON"
        ]
        if len(dependency_pairs) != len(set(dependency_pairs)):
            raise ValueError("duplicate dependency pair")
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


class DirectMatchTruncation(_Frozen):
    """Threshold-passing supplied candidates omitted by the direct top-k limit."""

    items: int = Field(ge=0)
    evidence: int = Field(ge=0)


class SelectionProposal(_Frozen):
    # The model returns only IDs from the supplied snapshot, never new objects.
    design_item_version_ids: tuple[Id, ...]
    evidence_ids: tuple[Id, ...]
    usage: tuple[EmbeddingUsage, ...] = ()
    # None means the selector did not report truncation, not that it was zero.
    direct_match_truncation: DirectMatchTruncation | None = None


class SelectionProvider(Protocol):
    async def select(self, request: SelectionInput) -> SelectionProposal: ...


class SelectedItem(_Frozen):
    design_item_version_id: Id
    reasons: tuple[Literal["MATCHED", "DEPENDENCY", "TASK_PLAN"], ...]


class SelectionResult(_Frozen):
    design_items: tuple[SelectedItem, ...]
    evidence_ids: tuple[Id, ...]
    unresolved: tuple[Literal["NO_MATCH", "DEPENDENCY_UNVERIFIED"], ...]
    search_status: Literal["COMPLETE"] = "COMPLETE"
    usage: tuple[EmbeddingUsage, ...] = ()
    direct_match_truncation: DirectMatchTruncation | None = None
    # In-process consistency marker only; never part of the external payload.
    snapshot_binding: str | None = Field(default=None, exclude=True, repr=False)


def selection_binding(
    snapshot, items, evidence_ids, unresolved, truncation=None
) -> str:
    """Detect accidental mixing of inputs and selected output, not authorization."""
    payload = {
        "snapshot": snapshot.model_dump(mode="json"),
        "items": [item.model_dump(mode="json") for item in items],
        "evidence_ids": list(evidence_ids),
        "unresolved": list(unresolved),
        "direct_match_truncation": (
            truncation.model_dump(mode="json") if truncation is not None else None
        ),
    }
    encoder = json.JSONEncoder(sort_keys=True, ensure_ascii=True, separators=(",", ":"))
    digest = hashlib.sha256()
    for part in encoder.iterencode(payload):
        digest.update(part.encode("ascii"))
    return digest.hexdigest()


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
        except ContextSelectionError as exc:
            code = (
                exc.code
                if isinstance(exc.code, str) and exc.code in SAFE_SELECTION_ERROR_CODES
                else "selection_failed"
            )
            raise ContextSelectionError(code, exc.usage) from None
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
        or (
            proposal.direct_match_truncation is not None
            and (
                proposal.direct_match_truncation.items
                > len(request.items) - len(matched)
                or proposal.direct_match_truncation.evidence
                > len(request.evidence) - len(evidence_ids)
            )
        )
    ):
        raise ContextSelectionError("invalid_selection", proposal.usage)

    # A selector that found threshold-passing candidates but returned no direct
    # matches due to its own limit cannot truthfully report NO_MATCH.
    truncation = proposal.direct_match_truncation
    if (
        not matched
        and not evidence_ids
        and truncation is not None
        and (truncation.items or truncation.evidence)
    ):
        raise ContextSelectionError("selection_incomplete", proposal.usage)

    reasons: dict[str, set[str]] = {}
    for version in matched:
        reasons.setdefault(version, set()).add("MATCHED")
    for ref in request.task_design_refs:
        reasons.setdefault(ref.design_item_version_id, set()).add("TASK_PLAN")

    # One hop only from directly matched/plan items; never follow newly added nodes.
    # Eligible edges with unavailable or mismatched endpoint versions cannot be
    # expanded. Surface only the incomplete state, not any missing endpoint ID.
    seeds = {versions[version].design_item_id for version in reasons}
    dependency_unverified = False
    for relation in request.relations:
        if (
            relation.relation == "DEPENDS_ON"
            and relation.origin == "EXPLICIT"
            and relation.status == "CONFIRMED"
            and relation.from_design_item_id in seeds
        ):
            source = by_item[relation.from_design_item_id]
            target = by_item.get(relation.to_design_item_id)
            if (
                source.design_item_version_id != relation.from_design_item_version_id
                or target is None
                or target.design_item_version_id != relation.to_design_item_version_id
            ):
                dependency_unverified = True
                continue
            reasons.setdefault(target.design_item_version_id, set()).add("DEPENDENCY")

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
    unresolved = ("NO_MATCH",) if not matched and not evidence_ids else ()
    if dependency_unverified:
        unresolved += ("DEPENDENCY_UNVERIFIED",)
    return SelectionResult(
        design_items=selected,
        evidence_ids=evidence_ids,
        # A task reference is useful context, but does not prove the search
        # found a relevant item or source for this particular request.
        unresolved=unresolved,
        usage=proposal.usage,
        direct_match_truncation=proposal.direct_match_truncation,
        snapshot_binding=selection_binding(
            request,
            selected,
            evidence_ids,
            unresolved,
            proposal.direct_match_truncation,
        ),
    )
