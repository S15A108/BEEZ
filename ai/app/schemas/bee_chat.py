"""Bee: grounded design Q&A over BE-authorized evidence and approved items (#135).

The BE authorizes the project, runs the semantic search over its READY index
(AI-A04 query embedding, then the allowed-set SQL top-k) and passes the
retrieved chunks, the current approved design items and the related tasks.
This side selects what the model sees, asks for an answer with citations,
checks every citation, item and task reference against those inputs and
returns the answer with its grounding. Nothing is stored: conversation
history lives with the caller and is only read back from the request.
"""

import hashlib
import re
from typing import Annotated, Literal, Self

from pydantic import BaseModel, ConfigDict, Field, StringConstraints, model_validator

from app.schemas.design_drafts import (
    DesignItemContent,
    DesignKind,
    DisplayKey,
    ItemSource,
    check_content_for_kind,
)
from app.schemas.documents import Sha256, SourceLocator
from app.schemas.planning_questions import Id, Location, Quote, Text

PROFILE_VERSION = "bee-v1"
MAX_QUESTION_CHARS = 2000
MAX_EVIDENCE = 50
DEFAULT_MAX_EVIDENCE = 12
# A READY chunk is at most 600 tokens; this only bounds a hostile input.
MAX_EVIDENCE_TEXT_CHARS = 20_000
MAX_APPROVED_ITEMS = 500
MAX_RELATED_TASKS = 100
MAX_HISTORY_TURNS = 10
MAX_CITATIONS = 20
MAX_REFS = 20
MAX_UNRESOLVED = 50
MAX_NEXT_ACTIONS = 10
MAX_NOTICES = 10
MAX_ANSWER_CHARS = 10_000
MAX_SYSTEM_PROMPT_CHARS = 20_000
# PRJ-A01: name 1~40, description 0~300.
MAX_PROJECT_NAME_CHARS = 40
MAX_PROJECT_DESCRIPTION_CHARS = 300

ScopeMode = Literal["APPROVED", "DOCUMENT_REVIEW"]
# ANSWERED is grounded in verified citations or approved items; the other two
# carry a fixed text so the screen never shows a guess as an answer.
AnswerStatus = Literal["ANSWERED", "INSUFFICIENT_EVIDENCE", "OUT_OF_SCOPE"]
TaskStatus = Literal[
    "DRAFT", "READY", "IN_PROGRESS", "WAITING", "COMPLETED", "CANCELLED"
]
PlanRelation = Literal["READS", "IMPLEMENTS", "CHANGES"]
HistoryRole = Literal["USER", "BEE"]

Question = Annotated[
    str, StringConstraints(min_length=1, max_length=MAX_QUESTION_CHARS)
]
EvidenceText = Annotated[
    str, StringConstraints(min_length=1, max_length=MAX_EVIDENCE_TEXT_CHARS)
]
AnswerText = Annotated[
    str, StringConstraints(min_length=1, max_length=MAX_ANSWER_CHARS)
]
SystemPrompt = Annotated[
    str, StringConstraints(min_length=1, max_length=MAX_SYSTEM_PROMPT_CHARS)
]
ProjectName = Annotated[
    str, StringConstraints(min_length=1, max_length=MAX_PROJECT_NAME_CHARS)
]
ProjectDescription = Annotated[
    str, StringConstraints(max_length=MAX_PROJECT_DESCRIPTION_CHARS)
]
Title = Annotated[str, StringConstraints(min_length=1, max_length=500)]
ContentHash = Annotated[str, StringConstraints(min_length=1, max_length=256)]

_MARKER = re.compile(r"\[(\d{1,2})\]")


class _Strict(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True, strict=True)


class _Lenient(BaseModel):
    """BE objects (TaskDetail and its plan) are read for a few fields only."""

    model_config = ConfigDict(extra="ignore", frozen=True, strict=True)


def _nonblank(value: str, what: str) -> None:
    if not value.strip():
        raise ValueError(f"blank {what}")


def _unique(values: list, what: str) -> None:
    if len(set(values)) != len(values):
        raise ValueError(f"duplicate {what}")


def prompt_hash(prompt: str) -> str:
    return hashlib.sha256(prompt.encode("utf-8")).hexdigest()


def citation_markers(text: str) -> list[int]:
    """Every `[n]` the text refers to, in order of appearance."""
    return [int(match) for match in _MARKER.findall(text)]


class ProjectProfile(_Strict):
    """The project context the Bee instance is initialized with (PRJ-A01)."""

    name: ProjectName
    description: ProjectDescription | None = None

    @model_validator(mode="after")
    def named(self) -> Self:
        _nonblank(self.name, "project name")
        return self


class BeeInstanceRequest(_Strict):
    """Initialize the project's Bee instance; deterministic, no model call."""

    project_id: Id
    request_key: Id
    project: ProjectProfile


class BeeInstance(_Strict):
    """What the BE may store for the project: a versioned, hashed prompt."""

    project_id: Id
    profile_version: Literal["bee-v1"] = PROFILE_VERSION
    persona: Title
    greeting: Text
    scope_statement: Text
    system_prompt: SystemPrompt
    system_prompt_hash: Sha256

    @model_validator(mode="after")
    def hashed(self) -> Self:
        if self.system_prompt_hash != prompt_hash(self.system_prompt):
            raise ValueError("system_prompt_hash does not match system_prompt")
        return self


class BeeInstanceRef(_Strict):
    """The stored instance, echoed on each question so the prompt is rebuilt."""

    profile_version: Literal["bee-v1"] = PROFILE_VERSION
    project: ProjectProfile


class RetrievalScope(_Strict):
    """00-common RetrievalScope: the BE-decided exact document version set."""

    mode: ScopeMode
    document_version_ids: tuple[Id, ...] = Field(max_length=1000)
    baseline_id: Id | None = None

    @model_validator(mode="after")
    def consistent(self) -> Self:
        _unique(list(self.document_version_ids), "document version")
        if (self.mode == "APPROVED") != (self.baseline_id is not None):
            raise ValueError("APPROVED needs baseline_id; DOCUMENT_REVIEW has none")
        return self


class RetrievedEvidence(_Strict):
    """00-common RetrievedEvidence: one READY chunk the BE search returned."""

    evidence_id: Id
    document_id: Id
    document_version_id: Id
    version_no: int = Field(ge=1)
    chunk_key: Id
    text: EvidenceText
    locator: SourceLocator
    embedding_profile_id: Id
    # Display extras the BE may pass through to BeeCitation; never required.
    title: Title | None = None
    section_title: Location | None = None

    @model_validator(mode="after")
    def readable(self) -> Self:
        _nonblank(self.text, "evidence text")
        return self


class ApprovedDesignItem(_Strict):
    """00-common DesignItemVersion restricted to the APPROVED status."""

    id: Id
    design_item_id: Id
    key: DisplayKey
    kind: DesignKind
    version_no: int = Field(ge=1)
    status: Literal["APPROVED"]
    schema_version: Literal[1] = 1
    content: DesignItemContent
    source: ItemSource | None = None
    content_hash: ContentHash | None = None

    @model_validator(mode="after")
    def content_fits_kind(self) -> Self:
        check_content_for_kind(self.kind, self.content)
        return self


class TaskDesignRef(_Lenient):
    design_item_id: Id
    design_item_version_id: Id
    relation: PlanRelation


class TaskPlanSummary(_Lenient):
    """The part of 00-common TaskPlan the model is shown."""

    title: Text
    goal: Text | None = None
    design_refs: tuple[TaskDesignRef, ...] = Field(default=(), max_length=200)


class TaskPlanRevision(_Lenient):
    id: Id
    plan: TaskPlanSummary


class RelatedTask(_Lenient):
    """00-common TaskDetail, read for its id, key, status and current plan.

    The BE may send TaskDetail as it is; fields not listed here are ignored.
    """

    id: Id
    key: DisplayKey
    status: TaskStatus
    assignee_member_id: Id | None = None
    current_plan_revision: TaskPlanRevision | None = None


class ChatTurn(_Strict):
    """A prior turn of the open conversation, supplied by the caller as data."""

    role: HistoryRole
    text: Text

    @model_validator(mode="after")
    def readable(self) -> Self:
        _nonblank(self.text, "turn text")
        return self


class BeeChatRequest(_Strict):
    """BE-authorized input for one question (AI-A05 `/v1/bee/chat`)."""

    project_id: Id
    request_key: Id
    question: Question
    instance: BeeInstanceRef | None = None
    scope: RetrievalScope
    embedding_profile_id: Id
    retrieved_evidence: tuple[RetrievedEvidence, ...] = Field(
        default=(), max_length=MAX_EVIDENCE
    )
    approved_items: tuple[ApprovedDesignItem, ...] = Field(
        default=(), max_length=MAX_APPROVED_ITEMS
    )
    related_tasks: tuple[RelatedTask, ...] = Field(
        default=(), max_length=MAX_RELATED_TASKS
    )
    # Open points the BE already knows; shown to the model, echoed back first.
    unresolved: tuple[Text, ...] = Field(default=(), max_length=MAX_UNRESOLVED)
    history: tuple[ChatTurn, ...] = Field(default=(), max_length=MAX_HISTORY_TURNS)
    max_evidence: int = Field(default=DEFAULT_MAX_EVIDENCE, ge=1, le=MAX_EVIDENCE)

    @model_validator(mode="after")
    def consistent(self) -> Self:
        _nonblank(self.question, "question")
        allowed = set(self.scope.document_version_ids)
        _unique([item.evidence_id for item in self.retrieved_evidence], "evidence id")
        _unique(
            [(i.document_version_id, i.chunk_key) for i in self.retrieved_evidence],
            "evidence chunk",
        )
        for item in self.retrieved_evidence:
            if item.document_version_id not in allowed:
                raise ValueError("evidence outside the retrieval scope")
            if item.embedding_profile_id != self.embedding_profile_id:
                raise ValueError("evidence from another embedding profile")
        _unique([item.id for item in self.approved_items], "approved item version")
        _unique([item.design_item_id for item in self.approved_items], "approved item")
        _unique([item.key for item in self.approved_items], "approved item key")
        _unique([task.id for task in self.related_tasks], "related task")
        _unique(list(self.unresolved), "unresolved entry")
        return self


class BeeCitation(_Strict):
    """A verified quote: an exact substring of the supplied evidence text.

    `n` is the number the answer text refers to as `[n]`; the BE turns this
    into the screen's BeeCitation (title, line range, highlighted quote).
    """

    n: int = Field(ge=1, le=MAX_CITATIONS)
    evidence_id: Id
    document_id: Id
    document_version_id: Id
    version_no: int = Field(ge=1)
    chunk_key: Id
    locator: SourceLocator
    quote: Quote
    title: Title | None = None
    section_title: Location | None = None
    claim: Text | None = None

    @model_validator(mode="after")
    def quote_is_trimmed(self) -> Self:
        if self.quote != self.quote.strip() or not self.quote.strip():
            raise ValueError("quote must be a trimmed, nonblank substring")
        return self


class DesignItemRef(_Strict):
    """An approved item the answer relied on, with its stored source."""

    design_item_id: Id
    design_item_version_id: Id
    key: DisplayKey
    kind: DesignKind
    version_no: int = Field(ge=1)
    source: ItemSource | None = None


class TaskRef(_Strict):
    task_id: Id
    key: DisplayKey
    status: TaskStatus


class InstanceEcho(_Strict):
    """Which stored instance produced this answer; the BE can compare hashes."""

    profile_version: Literal["bee-v1"] = PROFILE_VERSION
    system_prompt_hash: Sha256


class BeeChatResult(_Strict):
    """00-common ChatResult plus the grounding the screen and BE need."""

    answer_status: AnswerStatus
    # True when at least one citation or approved item backs the answer.
    grounded: bool
    text: AnswerText
    citations: tuple[BeeCitation, ...] = Field(default=(), max_length=MAX_CITATIONS)
    # 00-common Source rows mapped from citations and referenced items.
    sources: tuple[ItemSource, ...] = Field(
        default=(), max_length=MAX_CITATIONS + MAX_REFS
    )
    design_refs: tuple[DesignItemRef, ...] = Field(default=(), max_length=MAX_REFS)
    task_refs: tuple[TaskRef, ...] = Field(default=(), max_length=MAX_REFS)
    unresolved: tuple[Text, ...] = Field(default=(), max_length=MAX_UNRESOLVED)
    next_actions: tuple[Text, ...] = Field(default=(), max_length=MAX_NEXT_ACTIONS)
    notices: tuple[Text, ...] = Field(default=(), max_length=MAX_NOTICES)
    scope: RetrievalScope
    embedding_profile_id: Id
    evidence_considered: int = Field(ge=0, le=MAX_EVIDENCE)
    evidence_omitted: int = Field(ge=0)
    instance: InstanceEcho | None = None

    @model_validator(mode="after")
    def consistent(self) -> Self:
        _nonblank(self.text, "answer text")
        if [item.n for item in self.citations] != list(
            range(1, len(self.citations) + 1)
        ):
            raise ValueError("citations are numbered 1..n in order")
        _unique([(c.evidence_id, c.quote) for c in self.citations], "citation")
        _unique([ref.design_item_version_id for ref in self.design_refs], "design ref")
        _unique([ref.task_id for ref in self.task_refs], "task ref")
        _unique(list(self.unresolved), "unresolved entry")
        _unique(list(self.next_actions), "next action")
        if self.grounded != bool(self.citations or self.design_refs):
            raise ValueError("grounded must reflect citations and design refs")
        if (self.answer_status == "ANSWERED") != self.grounded:
            raise ValueError("only an ANSWERED answer is grounded")
        if not self.grounded and self.task_refs:
            raise ValueError("task references belong to a grounded answer")
        if any(n > len(self.citations) for n in citation_markers(self.text)):
            raise ValueError("answer text refers to a citation it does not have")
        for citation in self.citations:
            if citation.document_version_id not in self.scope.document_version_ids:
                raise ValueError("citation outside the retrieval scope")
        return self
