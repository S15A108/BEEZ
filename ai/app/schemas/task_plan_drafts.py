"""Task plan draft from approved document excerpts (S15P31A108-385).

The BE picks the excerpts from the approved documents, passes the approved
design items for linking and calls the model once per human click. The result
is a draft of the plan fields with verified document quotes, design items the
plan may link to, and design items the documents mention but the approved
design lacks. Nothing here is stored or approved: the BE returns the draft to
the page and the person decides.
"""

from typing import Annotated, Any, Literal, Self

from pydantic import BaseModel, ConfigDict, Field, StringConstraints, model_validator

from app.schemas.design_drafts import (
    DesignItemContent,
    DesignKind,
    check_content_for_kind,
)
from app.schemas.documents import DocumentKind
from app.schemas.planning_questions import Id, Key, Location, Quote

MAX_EXCERPTS = 12
MAX_EXCERPT_CHARS = 4000
MAX_DESIGN_ITEMS = 100
# Output bounds keep one call near the 1k output-token target.
MAX_GOAL_CHARS = 400
MAX_APPROACH_CHARS = 600
MAX_LIST_ITEMS = 6
MAX_LIST_ENTRY_CHARS = 200
MAX_EVIDENCE = 8
MAX_LINK_CANDIDATES = 5
MAX_NEW_ITEMS = 3
MAX_UNLINKED = 5
MAX_REASON_CHARS = 200

PlanField = Literal[
    "goal", "included_scope", "excluded_scope", "approach", "completion_criteria"
]
PlanStatus = Literal["DRAFTED", "INSUFFICIENT"]

ShortText = Annotated[str, StringConstraints(min_length=1, max_length=200)]
Title = Annotated[str, StringConstraints(min_length=1, max_length=200)]
Memo = Annotated[str, StringConstraints(min_length=1, max_length=2000)]


class _Strict(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True, strict=True)


class TaskInput(_Strict):
    title: Title
    memo: Memo | None = None

    @model_validator(mode="after")
    def nonblank(self) -> Self:
        if not self.title.strip() or (self.memo is not None and not self.memo.strip()):
            raise ValueError("blank text")
        return self


class Excerpt(_Strict):
    """An approved document passage the BE selected; `text` is the exact source."""

    excerpt_id: Key
    document_id: Id
    document_type: DocumentKind
    document_version_id: Id
    version_no: int = Field(ge=1)
    location: Location
    text: Annotated[str, StringConstraints(min_length=1, max_length=MAX_EXCERPT_CHARS)]


class DesignItemRef(_Strict):
    """An approved design item the plan may link to."""

    design_item_id: Id
    kind: DesignKind
    key: Annotated[str, StringConstraints(min_length=1, max_length=64)]
    summary: Annotated[str, StringConstraints(min_length=1, max_length=300)]


class TaskPlanDraftRequest(_Strict):
    project_id: Id
    request_key: Id
    task: TaskInput
    excerpts: tuple[Excerpt, ...] = Field(min_length=1, max_length=MAX_EXCERPTS)
    design_items: tuple[DesignItemRef, ...] = Field(
        default=(), max_length=MAX_DESIGN_ITEMS
    )

    @model_validator(mode="after")
    def unique(self) -> Self:
        excerpts = [item.excerpt_id for item in self.excerpts]
        items = [item.design_item_id for item in self.design_items]
        if len(set(excerpts)) != len(excerpts) or len(set(items)) != len(items):
            raise ValueError("duplicate id")
        return self


# ---- result ----


class PlanFields(_Strict):
    goal: Annotated[str, StringConstraints(max_length=MAX_GOAL_CHARS)] = ""
    included_scope: tuple[ShortText, ...] = Field(default=(), max_length=MAX_LIST_ITEMS)
    excluded_scope: tuple[ShortText, ...] = Field(default=(), max_length=MAX_LIST_ITEMS)
    approach: Annotated[str, StringConstraints(max_length=MAX_APPROACH_CHARS)] = ""
    completion_criteria: tuple[ShortText, ...] = Field(
        default=(), max_length=MAX_LIST_ITEMS
    )


class Evidence(_Strict):
    """A quote checked to occur verbatim in the excerpt it names."""

    field: PlanField
    document_id: Id
    document_type: DocumentKind
    document_version_id: Id
    version_no: int = Field(ge=1)
    location: Location
    quote: Quote


class LinkCandidate(_Strict):
    design_item_id: Id
    reason: Annotated[str, StringConstraints(min_length=1, max_length=MAX_REASON_CHARS)]


class NewItemEvidence(_Strict):
    document_id: Id
    document_type: DocumentKind
    document_version_id: Id
    version_no: int = Field(ge=1)
    location: Location
    quote: Quote


class NewItemCandidate(_Strict):
    kind: DesignKind
    content: dict[str, Any]
    rationale: Annotated[str, StringConstraints(min_length=1, max_length=500)]
    evidence: tuple[NewItemEvidence, ...] = Field(min_length=1, max_length=3)

    @model_validator(mode="after")
    def valid_content(self) -> Self:
        parsed = DesignItemContent.model_validate(self.content)
        check_content_for_kind(self.kind, parsed)
        return self


class Unlinked(_Strict):
    text: Annotated[str, StringConstraints(min_length=1, max_length=500)]


class TaskPlanDraftResult(_Strict):
    status: PlanStatus
    plan: PlanFields
    evidence: tuple[Evidence, ...] = Field(default=(), max_length=MAX_EVIDENCE)
    # Fields with text but no verified quote: shown without a source.
    unsupported_fields: tuple[PlanField, ...] = ()
    design_ref_candidates: tuple[LinkCandidate, ...] = Field(
        default=(), max_length=MAX_LINK_CANDIDATES
    )
    new_design_item_candidates: tuple[NewItemCandidate, ...] = Field(
        default=(), max_length=MAX_NEW_ITEMS
    )
    unlinked: tuple[Unlinked, ...] = Field(default=(), max_length=MAX_UNLINKED)
