"""Review candidates and supplement questions for one fixed document version (#133).

The BE authorizes the document, passes its fixed version and raw text, and owns
every stored question, answer and decision. Nothing here is a confirmed defect:
a candidate is either a review candidate whose every cited quote was found in
the referenced source block, or unverified. No third state exists on purpose.
"""

from typing import Annotated, Literal, Self

from pydantic import BaseModel, ConfigDict, Field, StringConstraints, model_validator

from app.schemas.documents import DocumentKind, Sha256, SourceInput, SourceLocator

MAX_CANDIDATES = 50
MAX_QUESTIONS = 50
DEFAULT_MAX_QUESTIONS = 20
MAX_EVIDENCE_PER_CANDIDATE = 5
MAX_CHOICE_OPTIONS = 8
MAX_CONTENT_CHARS = 1_048_576
# known_questions bounds (S15P31A108-414); the BE trims to the same values.
MAX_KNOWN_QUESTIONS = 100
MAX_KNOWN_CHARS = 30_000
KNOWN_ANSWER_CHARS = 200

Id = Annotated[str, StringConstraints(min_length=1, max_length=256, pattern=r"^\S+$")]
Key = Annotated[
    str,
    StringConstraints(min_length=1, max_length=64, pattern=r"^[A-Za-z0-9][\w.:-]*$"),
]
Text = Annotated[str, StringConstraints(min_length=1, max_length=2000)]
Quote = Annotated[str, StringConstraints(min_length=1, max_length=1000)]
Location = Annotated[str, StringConstraints(min_length=1, max_length=1000)]
Option = Annotated[str, StringConstraints(min_length=1, max_length=200)]
Time = Annotated[
    str, StringConstraints(pattern=r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z$")
]

# Matches the BE `planning_question.kind` check constraint (V2 schema).
CandidateKind = Literal["MISSING_CANDIDATE", "CLARIFICATION", "CONFLICT"]
CandidateStatus = Literal["REVIEW_CANDIDATE", "UNVERIFIED"]
UnverifiedReason = Literal[
    "NO_EVIDENCE", "BLOCK_NOT_FOUND", "QUOTE_NOT_IN_BLOCK", "INSUFFICIENT_EVIDENCE"
]
DetectionSignal = Literal[
    "UNDECIDED_STATEMENT", "PLACEHOLDER_CELL", "DUPLICATE_KEY", "MODEL_PROPOSAL"
]
# 선택·단답·서술 (Jira #133 item 3).
QuestionType = Literal["CHOICE", "SHORT_ANSWER", "ESSAY"]
Origin = Literal["MODEL", "TEMPLATE"]
# Statuses the BE question list shows; SUPERSEDED questions are never sent.
KnownQuestionStatus = Literal["OPEN", "ANSWERED", "DISMISSED"]


class _Strict(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True, strict=True)


def _nonblank(value: str) -> str:
    if not value.strip():
        raise ValueError("blank text")
    return value


class DocumentRef(_Strict):
    """00-common Source identity without the location."""

    document_id: Id
    document_version_id: Id
    version_no: int = Field(ge=1)


class EvidenceItem(_Strict):
    """One source anchor: the block, its lines and an exact substring of them."""

    document_id: Id
    document_version_id: Id
    version_no: int = Field(ge=1)
    block_id: Id
    locator: SourceLocator
    # Display location (heading path and line range); not an authorization.
    location: Location
    quote: Quote

    @model_validator(mode="after")
    def quote_is_trimmed(self) -> Self:
        if self.quote != self.quote.strip() or not self.quote.strip():
            raise ValueError("quote must be a trimmed, nonblank substring")
        return self


class ReviewCandidate(_Strict):
    candidate_key: Key
    kind: CandidateKind
    status: CandidateStatus
    signal: DetectionSignal
    summary: Text
    evidence: tuple[EvidenceItem, ...] = Field(max_length=MAX_EVIDENCE_PER_CANDIDATE)
    unverified_reason: UnverifiedReason | None = None

    @model_validator(mode="after")
    def evidence_matches_status(self) -> Self:
        _nonblank(self.summary)
        pairs = [(item.block_id, item.quote) for item in self.evidence]
        if len(set(pairs)) != len(pairs):
            raise ValueError("duplicate evidence")
        if self.status == "REVIEW_CANDIDATE":
            if not self.evidence or self.unverified_reason is not None:
                raise ValueError("a review candidate needs verified evidence only")
            if self.kind == "CONFLICT" and len({b for b, _ in pairs}) < 2:
                raise ValueError("a conflict needs two distinct blocks")
        elif self.unverified_reason is None:
            raise ValueError("an unverified candidate needs a reason")
        return self


class ChoiceAnswerFormat(_Strict):
    type: Literal["CHOICE"] = "CHOICE"
    options: tuple[Option, ...] = Field(min_length=2, max_length=MAX_CHOICE_OPTIONS)
    multiple: bool = False
    # When true the answer may carry free text beside or instead of an option.
    allow_other: bool = True

    @model_validator(mode="after")
    def unique_options(self) -> Self:
        stripped = [option.strip() for option in self.options]
        if any(not option for option in stripped) or len(set(stripped)) != len(
            stripped
        ):
            raise ValueError("options must be nonblank and distinct")
        return self


class ShortAnswerFormat(_Strict):
    type: Literal["SHORT_ANSWER"] = "SHORT_ANSWER"
    max_length: int = Field(default=200, ge=1, le=500)


class EssayAnswerFormat(_Strict):
    type: Literal["ESSAY"] = "ESSAY"
    max_length: int = Field(default=2000, ge=1, le=4000)


AnswerFormat = Annotated[
    ChoiceAnswerFormat | ShortAnswerFormat | EssayAnswerFormat,
    Field(discriminator="type"),
]


class SupplementQuestion(_Strict):
    question_key: Key
    candidate_key: Key
    # 1-based position after priority ordering; the BE may store it as is.
    rank: int = Field(ge=1)
    question_type: QuestionType
    text: Text
    answer_format: AnswerFormat
    origin: Origin
    # Copied from the candidate so a stored question still says whether its
    # evidence was verified; the FE labels UNVERIFIED questions as such.
    basis: CandidateStatus

    @model_validator(mode="after")
    def format_matches_type(self) -> Self:
        _nonblank(self.text)
        if self.answer_format.type != self.question_type:
            raise ValueError("answer_format must match question_type")
        return self


class ChoiceAnswer(_Strict):
    type: Literal["CHOICE"] = "CHOICE"
    selected: tuple[Option, ...] = Field(min_length=0, max_length=MAX_CHOICE_OPTIONS)
    other_text: (
        Annotated[str, StringConstraints(min_length=1, max_length=500)] | None
    ) = None


class ShortAnswer(_Strict):
    type: Literal["SHORT_ANSWER"] = "SHORT_ANSWER"
    text: Annotated[str, StringConstraints(min_length=1, max_length=500)]


class EssayAnswer(_Strict):
    type: Literal["ESSAY"] = "ESSAY"
    text: Annotated[str, StringConstraints(min_length=1, max_length=4000)]


Answer = Annotated[
    ChoiceAnswer | ShortAnswer | EssayAnswer, Field(discriminator="type")
]


class QuestionAnswer(_Strict):
    """Draft storage shape for one answer; the BE assigns IDs and timestamps."""

    question_key: Key
    answer: Answer
    answered_by: Id | None = None
    answered_at: Time | None = None


def validate_answer(question: SupplementQuestion, answer: Answer) -> None:
    """Reject an answer that does not fit the question's answer format."""
    fmt = question.answer_format
    if answer.type != fmt.type:
        raise ValueError("answer type does not match the question")
    if isinstance(answer, ChoiceAnswer) and isinstance(fmt, ChoiceAnswerFormat):
        if len(set(answer.selected)) != len(answer.selected):
            raise ValueError("duplicate option")
        if not set(answer.selected).issubset(fmt.options):
            raise ValueError("unknown option")
        if not fmt.multiple and len(answer.selected) > 1:
            raise ValueError("only one option may be selected")
        if answer.other_text is not None and not fmt.allow_other:
            raise ValueError("free text is not allowed for this question")
        if not answer.selected and answer.other_text is None:
            raise ValueError("an option or free text is required")
    elif isinstance(answer, (ShortAnswer, EssayAnswer)) and not isinstance(
        fmt, ChoiceAnswerFormat
    ):
        if not answer.text.strip():
            raise ValueError("blank answer")
        if len(answer.text) > fmt.max_length:
            raise ValueError("answer exceeds max_length")


class KnownQuestion(_Strict):
    """A question the project already shows from another document or an older
    version (S15P31A108-414). Untrusted data for the model, never instructions.
    `answer` is the first KNOWN_ANSWER_CHARS characters of an ANSWERED answer.
    """

    question: Text
    status: KnownQuestionStatus
    answer: (
        Annotated[str, StringConstraints(min_length=1, max_length=KNOWN_ANSWER_CHARS)]
        | None
    ) = None

    @model_validator(mode="after")
    def answer_only_when_answered(self) -> Self:
        _nonblank(self.question)
        if self.answer is not None and self.status != "ANSWERED":
            raise ValueError("only an answered question carries an answer")
        return self


class PlanningQuestionRequest(_Strict):
    """BE-authorized input: one fixed document version and its raw text."""

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
    # RULES_ONLY never calls a model; it returns deterministic candidates only.
    mode: Literal["MODEL", "RULES_ONLY"] = "MODEL"
    max_questions: int = Field(default=DEFAULT_MAX_QUESTIONS, ge=1, le=MAX_QUESTIONS)
    # Questions already asked, answered or dismissed elsewhere in the project,
    # newest first, so the model does not ask the same decision again. Only
    # the model sees them; rule candidates ignore them.
    known_questions: tuple[KnownQuestion, ...] = Field(
        default=(), max_length=MAX_KNOWN_QUESTIONS
    )

    @model_validator(mode="after")
    def known_questions_fit(self) -> Self:
        chars = sum(
            len(known.question) + len(known.answer or "")
            for known in self.known_questions
        )
        if chars > MAX_KNOWN_CHARS:
            raise ValueError("known_questions exceed MAX_KNOWN_CHARS")
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


class PlanningQuestionResult(_Strict):
    document: DocumentRef
    kind: DocumentKind
    text_hash: Sha256
    parser_profile: Annotated[str, StringConstraints(min_length=1, max_length=64)]
    max_questions: int = Field(ge=1, le=MAX_QUESTIONS)
    candidates: tuple[ReviewCandidate, ...] = Field(max_length=MAX_CANDIDATES)
    # Ordered by priority; at most max_questions, one per candidate.
    questions: tuple[SupplementQuestion, ...] = Field(max_length=MAX_QUESTIONS)
    # Candidates kept in `candidates` but left without a question by the cap.
    truncated_candidate_keys: tuple[Key, ...] = ()
    # Candidates dropped entirely because `candidates` itself was full.
    omitted_candidates: int = Field(default=0, ge=0)

    @model_validator(mode="after")
    def consistent(self) -> Self:
        keys = [candidate.candidate_key for candidate in self.candidates]
        if len(set(keys)) != len(keys):
            raise ValueError("duplicate candidate key")
        by_key = {candidate.candidate_key: candidate for candidate in self.candidates}
        for candidate in self.candidates:
            for item in candidate.evidence:
                if (
                    item.document_id != self.document.document_id
                    or item.document_version_id != self.document.document_version_id
                    or item.version_no != self.document.version_no
                ):
                    raise ValueError("evidence outside the analyzed document")
        question_keys = [question.question_key for question in self.questions]
        questioned = [question.candidate_key for question in self.questions]
        if len(set(question_keys)) != len(question_keys) or len(set(questioned)) != len(
            questioned
        ):
            raise ValueError("duplicate question")
        if len(self.questions) > self.max_questions:
            raise ValueError("questions exceed max_questions")
        for index, question in enumerate(self.questions, start=1):
            candidate = by_key.get(question.candidate_key)
            if candidate is None or question.basis != candidate.status:
                raise ValueError("question does not match its candidate")
            if question.rank != index:
                raise ValueError("questions must be ranked 1..n in order")
        truncated = set(self.truncated_candidate_keys)
        if len(truncated) != len(
            self.truncated_candidate_keys
        ) or not truncated.issubset(set(by_key) - set(questioned)):
            raise ValueError("truncated keys must be unquestioned candidates")
        return self
