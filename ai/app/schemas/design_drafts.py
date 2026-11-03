"""Answer-informed design item drafts for one fixed document version (#134).

The BE passes the authorized document, the #133 planning questions with their
stored answers, and the design item versions it already holds. The result is
a set of DRAFT item candidates with per-item sources (document quotes and
answer references) and a change list that compares each draft with the item
it would update. Nothing here is approved: the only status is DRAFT, no ID is
issued, and the supplied existing items are never modified.
"""

from typing import Annotated, Any, Literal, Self

from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    StringConstraints,
    TypeAdapter,
    ValidationError,
    model_validator,
)

from app.schemas.documents import DocumentKind, Sha256, SourceInput
from app.schemas.planning_questions import (
    MAX_CONTENT_CHARS,
    DocumentRef,
    EvidenceItem,
    Id,
    Key,
    Location,
    QuestionAnswer,
    SupplementQuestion,
    Text,
    validate_answer,
)

MAX_ITEMS = 100
DEFAULT_MAX_ITEMS = 50
MAX_EXISTING_ITEMS = 500
MAX_QUESTIONS = 200
# Per model proposal. A draft merges every proposal for one base item, so its
# own bounds are the natural ones: nothing a proposal verified is dropped.
MAX_SOURCES_PER_ITEM = 5
MAX_ANSWER_REFS_PER_ITEM = 10
MAX_SOURCES_PER_DRAFT = MAX_SOURCES_PER_ITEM * MAX_ITEMS
MAX_ANSWER_REFS_PER_DRAFT = MAX_QUESTIONS
MAX_UNRESOLVED = 50
MAX_DESCRIPTION_CHARS = 10_000

# 00-common DesignItemVersion.kind / DesignItemContent v1.
DesignKind = Literal[
    "REQUIREMENT", "API_CONTRACT", "DATA_CONTRACT", "POLICY", "CONSTRAINT"
]
ExistingStatus = Literal["DRAFT", "APPROVED"]
HttpMethod = Literal[
    "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "TRACE"
]
ChangeKind = Literal["CREATE", "UPDATE"]
FieldChangeKind = Literal["ADDED", "CHANGED", "REMOVED"]
# What backs a draft item: verified document quotes, stored answers, both, or
# nothing the reviewer can check.
Support = Literal["DOCUMENT", "ANSWER", "DOCUMENT_AND_ANSWER", "NONE"]
QuestionStatus = Literal["OPEN", "ANSWERED", "DISMISSED"]

Description = Annotated[
    str, StringConstraints(min_length=1, max_length=MAX_DESCRIPTION_CHARS)
]
# The same rules as the BE DesignContentValidator: `/`-rooted, no query,
# fragment, whitespace or control characters (C0, DEL and C1).
ApiPath = Annotated[
    str,
    StringConstraints(
        min_length=1, max_length=2000, pattern=r"^/[^\s?#\x00-\x1f\x7f-\x9f]*$"
    ),
]
# The BE only requires non-blank text here; a stored value must never be
# rejected on this side, so no length cap beyond the request body limit.
Entity = Annotated[str, StringConstraints(min_length=1)]
DisplayKey = Annotated[str, StringConstraints(min_length=1, max_length=64)]
FieldName = Annotated[str, StringConstraints(min_length=1)]


class _Strict(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True, strict=True)


class DesignItemContent(BaseModel):
    """DesignItemContent v1 (00-common): description is required for every kind.

    Every other JSON field is kept exactly as supplied, a null-valued field
    included: stored content is never rewritten here, so `before` is the
    item as the BE holds it. Only the fields a kind requires are
    type-checked, by `check_content_for_kind`, so a POLICY may carry
    `method: "manual"` just as the BE contract allows. Blank field names are
    rejected as the BE does.
    """

    model_config = ConfigDict(extra="allow", frozen=True, strict=True)

    description: Description

    @model_validator(mode="before")
    @classmethod
    def reject_blank_names(cls, data: Any) -> Any:
        if isinstance(data, dict) and any(
            not isinstance(name, str) or not name.strip() for name in data
        ):
            raise ValueError("blank content field name")
        return data

    @model_validator(mode="after")
    def nonblank(self) -> Self:
        if not self.description.strip():
            raise ValueError("blank description")
        return self

    def extra_fields(self) -> dict[str, Any]:
        return dict(self.model_extra or {})


_METHOD = TypeAdapter(HttpMethod)
_PATH = TypeAdapter(ApiPath)
_ENTITY = TypeAdapter(Entity)
_FIELDS = TypeAdapter(list[Any])


def check_content_for_kind(kind: DesignKind, content: DesignItemContent) -> None:
    """Kind-specific required fields: API method+path, DATA entity+fields.

    Other kinds are not checked beyond description, matching the BE contract.
    """
    extra = content.extra_fields()
    try:
        if kind == "API_CONTRACT":
            _METHOD.validate_python(extra.get("method"), strict=True)
            _PATH.validate_python(extra.get("path"), strict=True)
        elif kind == "DATA_CONTRACT":
            if not _ENTITY.validate_python(extra.get("entity"), strict=True).strip():
                raise ValueError("blank entity")
            _FIELDS.validate_python(extra.get("fields"), strict=True)
    except ValidationError as error:
        raise ValueError(f"{kind} content lacks a valid required field") from error


class ItemSource(_Strict):
    """00-common Source as stored on an existing design item version."""

    document_id: Id
    document_version_id: Id
    version_no: int = Field(ge=1)
    location: Location


class ExistingDesignItem(_Strict):
    """A design item version the BE holds; APPROVED ones are read-only context."""

    design_item_version_id: Id
    design_item_id: Id
    key: DisplayKey
    kind: DesignKind
    version_no: int = Field(ge=1)
    status: ExistingStatus
    schema_version: Literal[1] = 1
    content: DesignItemContent
    source: ItemSource | None = None

    @model_validator(mode="after")
    def content_fits_kind(self) -> Self:
        check_content_for_kind(self.kind, self.content)
        return self


class AnsweredQuestion(_Strict):
    """One stored #133 question with its evidence and current answer state."""

    question: SupplementQuestion
    evidence: tuple[EvidenceItem, ...] = Field(default=(), max_length=5)
    status: QuestionStatus
    answer: QuestionAnswer | None = None

    @model_validator(mode="after")
    def answer_matches_status(self) -> Self:
        if (self.status == "ANSWERED") != (self.answer is not None):
            raise ValueError("ANSWERED questions carry an answer; others do not")
        if self.answer is not None:
            if self.answer.question_key != self.question.question_key:
                raise ValueError("answer belongs to another question")
            validate_answer(self.question, self.answer.answer)
        pairs = [(item.block_id, item.quote) for item in self.evidence]
        if len(set(pairs)) != len(pairs):
            raise ValueError("duplicate evidence")
        return self


class DesignDraftRequest(_Strict):
    """BE-authorized input: the fixed document, stored questions and items."""

    project_id: Id
    request_key: Id
    document: DocumentRef
    kind: DocumentKind
    media_type: Literal["text/markdown", "text/plain"]
    content: Annotated[
        str, StringConstraints(min_length=1, max_length=MAX_CONTENT_CHARS)
    ]
    content_hash: Sha256
    text_hash: Sha256
    normalization_version: Annotated[
        str, StringConstraints(min_length=1, max_length=64)
    ]
    parser_profile: Annotated[str, StringConstraints(min_length=1, max_length=64)]
    existing_items: tuple[ExistingDesignItem, ...] = Field(
        default=(), max_length=MAX_EXISTING_ITEMS
    )
    questions: tuple[AnsweredQuestion, ...] = Field(
        default=(), max_length=MAX_QUESTIONS
    )
    max_items: int = Field(default=DEFAULT_MAX_ITEMS, ge=1, le=MAX_ITEMS)

    @model_validator(mode="after")
    def consistent(self) -> Self:
        versions = [item.design_item_version_id for item in self.existing_items]
        keys = [item.key for item in self.existing_items]
        if len(set(versions)) != len(versions) or len(set(keys)) != len(keys):
            raise ValueError("duplicate existing item")
        question_keys = [entry.question.question_key for entry in self.questions]
        if len(set(question_keys)) != len(question_keys):
            raise ValueError("duplicate question")
        for entry in self.questions:
            for item in entry.evidence:
                if (
                    item.document_id != self.document.document_id
                    or item.document_version_id != self.document.document_version_id
                    or item.version_no != self.document.version_no
                ):
                    raise ValueError("question evidence outside the document")
        return self

    def to_source_input(self) -> SourceInput:
        return SourceInput(
            project_id=self.project_id,
            document_version_id=self.document.document_version_id,
            kind=self.kind,
            media_type=self.media_type,
            content=self.content,
            content_hash=self.content_hash,
            text_hash=self.text_hash,
            normalization_version=self.normalization_version,
            parser_profile=self.parser_profile,
        )


class DraftItem(_Strict):
    """One unapproved item candidate; the BE assigns IDs and keys on save."""

    candidate_key: Key
    kind: DesignKind
    status: Literal["DRAFT"] = "DRAFT"
    schema_version: Literal[1] = 1
    content: DesignItemContent
    # Set when the draft would update an existing item version. An APPROVED
    # base is never edited: the BE creates a new DRAFT version from it.
    base_design_item_version_id: Id | None = None
    base_status: ExistingStatus | None = None
    sources: tuple[EvidenceItem, ...] = Field(
        default=(), max_length=MAX_SOURCES_PER_DRAFT
    )
    answer_refs: tuple[Key, ...] = Field(
        default=(), max_length=MAX_ANSWER_REFS_PER_DRAFT
    )
    support: Support
    rationale: Text

    @model_validator(mode="after")
    def consistent(self) -> Self:
        check_content_for_kind(self.kind, self.content)
        if not self.rationale.strip():
            raise ValueError("blank rationale")
        if (self.base_design_item_version_id is None) != (self.base_status is None):
            raise ValueError("base id and base status go together")
        pairs = [(item.block_id, item.quote) for item in self.sources]
        if len(set(pairs)) != len(pairs) or len(set(self.answer_refs)) != len(
            self.answer_refs
        ):
            raise ValueError("duplicate source or answer reference")
        expected = {
            (True, True): "DOCUMENT_AND_ANSWER",
            (True, False): "DOCUMENT",
            (False, True): "ANSWER",
            (False, False): "NONE",
        }[(bool(self.sources), bool(self.answer_refs))]
        if self.support != expected:
            raise ValueError("support must reflect sources and answer references")
        return self


class FieldChange(_Strict):
    """One top-level content field that differs between before and after.

    `change` says how: ADDED (no such field before), REMOVED (none after) or
    CHANGED (a different JSON value, null to a value included). A null in
    `before` or `after` is therefore unambiguous: the field is absent there
    for ADDED/REMOVED and null-valued for CHANGED.
    """

    field: FieldName
    change: FieldChangeKind
    before: Any = None
    after: Any = None


class DraftChange(_Strict):
    """Before/after view of one draft so answers' effect can be reviewed."""

    candidate_key: Key
    change: ChangeKind
    base_design_item_version_id: Id | None = None
    before: DesignItemContent | None = None
    after: DesignItemContent
    changed_fields: tuple[FieldChange, ...] = ()
    # Top-level fields that the model's proposals for this item set to
    # different values; the last proposal's value is in `after`, so the
    # reviewer must settle these before trusting the draft. An update may
    # carry conflicts and no field change at all, when the proposals ended
    # up back at the stored value: it is kept so the disagreement is seen.
    conflicting_fields: tuple[FieldName, ...] = ()
    answer_refs: tuple[Key, ...] = ()

    @model_validator(mode="after")
    def consistent(self) -> Self:
        if self.change == "CREATE":
            if self.base_design_item_version_id is not None or self.before is not None:
                raise ValueError("a created item has no base")
            if self.changed_fields or self.conflicting_fields:
                raise ValueError("a created item lists no field changes")
        else:
            if self.base_design_item_version_id is None or self.before is None:
                raise ValueError("an update needs its base and before content")
            if not self.changed_fields and not self.conflicting_fields:
                raise ValueError("an update must change a field or flag a conflict")
        names = [item.field for item in self.changed_fields]
        if len(set(names)) != len(names) or len(set(self.conflicting_fields)) != len(
            self.conflicting_fields
        ):
            raise ValueError("duplicate field change")
        return self


class DesignDraftResult(_Strict):
    document: DocumentRef
    kind: DocumentKind
    text_hash: Sha256
    parser_profile: Annotated[str, StringConstraints(min_length=1, max_length=64)]
    max_items: int = Field(ge=1, le=MAX_ITEMS)
    items: tuple[DraftItem, ...] = Field(max_length=MAX_ITEMS)
    # Same order as items, one entry per item.
    changes: tuple[DraftChange, ...] = Field(max_length=MAX_ITEMS)
    # Existing items no draft touches; the BE keeps them exactly as they are.
    untouched_existing_ids: tuple[Id, ...] = ()
    unanswered_question_keys: tuple[Key, ...] = ()
    # Open points the model could not settle from the document and answers.
    unresolved: tuple[Text, ...] = Field(default=(), max_length=MAX_UNRESOLVED)
    omitted_items: int = Field(default=0, ge=0)

    @model_validator(mode="after")
    def consistent(self) -> Self:
        keys = [item.candidate_key for item in self.items]
        if len(set(keys)) != len(keys):
            raise ValueError("duplicate candidate key")
        if len(self.changes) != len(self.items):
            raise ValueError("changes must mirror items")
        bases: list[str] = []
        for item, change in zip(self.items, self.changes, strict=True):
            if (
                change.candidate_key != item.candidate_key
                or change.after != item.content
                or change.base_design_item_version_id
                != item.base_design_item_version_id
                or change.answer_refs != item.answer_refs
                or (change.change == "UPDATE")
                != (item.base_design_item_version_id is not None)
            ):
                raise ValueError("change does not match its item")
            if item.base_design_item_version_id is not None:
                bases.append(item.base_design_item_version_id)
            for source in item.sources:
                if (
                    source.document_id != self.document.document_id
                    or source.document_version_id != self.document.document_version_id
                    or source.version_no != self.document.version_no
                ):
                    raise ValueError("source outside the analyzed document")
        if len(set(bases)) != len(bases):
            raise ValueError("one draft per existing item")
        if set(bases) & set(self.untouched_existing_ids):
            raise ValueError("an updated item is not untouched")
        if len(set(self.untouched_existing_ids)) != len(self.untouched_existing_ids):
            raise ValueError("duplicate untouched id")
        if len(set(self.unanswered_question_keys)) != len(
            self.unanswered_question_keys
        ):
            raise ValueError("duplicate unanswered key")
        return self
