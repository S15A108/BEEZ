"""AI-A08 meeting analysis input and output contracts.

The backend is responsible for project authorization and loading the approved
snapshot.  These models validate the envelope; they do not authenticate users or
turn retrieved information into an approved decision.
"""

import json
from typing import Annotated, Any, Literal, Self

from pydantic import BaseModel, ConfigDict, Field, StringConstraints, model_validator

TextId = Annotated[
    str, StringConstraints(strip_whitespace=True, min_length=1, max_length=128)
]
ShortText = Annotated[str, StringConstraints(min_length=1, max_length=4_000)]
EntryText = Annotated[str, StringConstraints(min_length=1, max_length=100_000)]


class ContractModel(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)


class Source(ContractModel):
    document_id: TextId
    document_version_id: TextId
    version_no: int = Field(ge=1)
    # Do not normalize the locator: output must be compared to the exact input.
    location: Annotated[str, StringConstraints(min_length=1, max_length=10_000)]

    @model_validator(mode="after")
    def valid_location(self) -> Self:
        if not self.location.strip():
            raise ValueError("source location is blank")
        return self


class DesignSource(ContractModel):
    """An input design can identify a document without a known citation location."""

    document_id: TextId
    document_version_id: TextId
    version_no: int = Field(ge=1)
    location: Annotated[str, StringConstraints(max_length=10_000)] | None = None


class DesignItemVersion(ContractModel):
    id: TextId
    design_item_id: TextId
    key: TextId
    kind: Literal[
        "REQUIREMENT", "API_CONTRACT", "DATA_CONTRACT", "POLICY", "CONSTRAINT"
    ]
    version_no: int = Field(ge=1)
    status: Literal["APPROVED"]
    schema_version: int = Field(ge=1)
    content: dict[str, Any]
    source: DesignSource | None = None
    content_hash: Annotated[str, StringConstraints(max_length=256)] | None = None


class MeetingEntry(ContractModel):
    id: TextId
    meeting_id: TextId
    kind: Literal["TEXT", "BEE_ANALYSIS", "MINUTES", "CONFLICT_BRIEF", "SUMMARY"]
    # Preserve original meeting text; it is untrusted data, not instructions.
    text: EntryText
    # AI-A08's current example omits author_kind; #205 returns it when known.
    author_kind: Literal["HUMAN", "BEE"] | None = None
    author_account_id: TextId | None = None
    author_member_id: TextId | None = None
    author_name: Annotated[str, StringConstraints(max_length=256)] | None = None
    refs: list[dict[str, Any]] | None = Field(default=None, max_length=100)
    through_entry_id: TextId | None = None
    generation: dict[str, Any] | None = None
    created_at: Annotated[str, StringConstraints(min_length=1, max_length=128)]

    @model_validator(mode="after")
    def text_not_blank(self) -> Self:
        if not self.text.strip():
            raise ValueError("meeting entry text is blank")
        return self


class SummaryDocument(ContractModel):
    document_id: TextId
    document_version_id: TextId
    text: str

    @model_validator(mode="after")
    def normalized_text(self) -> Self:
        if self.text.startswith("\ufeff") or "\r" in self.text:
            raise ValueError("document must use utf8-lf-v1")
        if len(self.text.encode("utf-8")) > 300_000:
            raise ValueError("document exceeds size limit")
        return self


class MeetingAnalysisRequest(ContractModel):
    project_id: TextId
    request_key: TextId
    mode: Literal["QUESTIONS", "MINUTES", "SUMMARY"]
    entries: list[MeetingEntry] = Field(min_length=1, max_length=200)
    approved_items: list[DesignItemVersion] = Field(max_length=100)
    change_proposal: dict[str, Any] | None = None
    related_tasks: list[dict[str, Any]] | None = None
    documents: list[SummaryDocument] | None = Field(default=None, max_length=10)
    previous_summary: dict[str, Any] | None = None
    feedback: ShortText | None = None

    @model_validator(mode="after")
    def validate_context(self) -> Self:
        if self.mode == "SUMMARY":
            if self.related_tasks is None or self.documents is None:
                raise ValueError("SUMMARY requires related_tasks and documents")
            if (self.previous_summary is None) != (self.feedback is None):
                raise ValueError(
                    "previous_summary and feedback must be supplied together"
                )
            if self.feedback is not None and not self.feedback.strip():
                raise ValueError("feedback is blank")
            ids = [document.document_id for document in self.documents]
            if len(ids) != len(set(ids)):
                raise ValueError("duplicate document")
        elif any(
            value is not None
            for value in (
                self.related_tasks,
                self.documents,
                self.previous_summary,
                self.feedback,
            )
        ):
            raise ValueError("summary context requires SUMMARY mode")
        entry_ids = [entry.id for entry in self.entries]
        meeting_ids = {entry.meeting_id for entry in self.entries}
        version_ids = [item.id for item in self.approved_items]
        item_ids = [item.design_item_id for item in self.approved_items]
        if len(set(entry_ids)) != len(entry_ids) or len(meeting_ids) != 1:
            raise ValueError("entries must be unique and belong to one meeting")
        if len(set(version_ids)) != len(version_ids) or len(set(item_ids)) != len(
            item_ids
        ):
            raise ValueError("duplicate approved design")
        try:
            encoded = json.dumps(
                self.model_dump(), ensure_ascii=False, allow_nan=False
            ).encode("utf-8")
        except (TypeError, ValueError, RecursionError) as exc:
            raise ValueError("meeting input is not valid JSON") from exc
        if len(encoded) > 1_048_576:
            raise ValueError("meeting input exceeds transport limit")
        return self


class MeetingActionItem(ContractModel):
    text: ShortText
    member_id: TextId | None = None

    @model_validator(mode="after")
    def text_not_blank(self) -> Self:
        if not self.text.strip():
            raise ValueError("action item is blank")
        return self


class MeetingAnalysisResult(ContractModel):
    text: Annotated[str, StringConstraints(min_length=1, max_length=20_000)]
    decisions: list[ShortText] = Field(max_length=100)
    unresolved: list[ShortText] = Field(max_length=100)
    action_items: list[MeetingActionItem] = Field(max_length=100)
    # AI-A08 response example includes sources although 00-common omits the row.
    sources: list[Source] = Field(max_length=100)

    @model_validator(mode="after")
    def no_blank_text(self) -> Self:
        if not self.text.strip() or any(
            not item.strip() for item in [*self.decisions, *self.unresolved]
        ):
            raise ValueError("meeting result contains blank text")
        return self


class SummaryLocator(ContractModel):
    type: Literal["text_lines"]
    start_line: int = Field(ge=1)
    end_line: int = Field(ge=1)


class SummaryDocumentChange(ContractModel):
    document_id: TextId
    base_version_id: TextId
    locator: SummaryLocator
    before: Annotated[str, StringConstraints(max_length=300_000)]
    after: Annotated[str, StringConstraints(max_length=100_000)]
    evidence_entry_ids: list[TextId] = Field(min_length=1, max_length=200)


class MeetingSummaryResult(ContractModel):
    text: Annotated[str, StringConstraints(min_length=1, max_length=4_000)]
    conflicts: list[ShortText] = Field(max_length=100)
    decisions: list[ShortText] = Field(max_length=100)
    unresolved: list[ShortText] = Field(max_length=100)
    document_changes: list[SummaryDocumentChange] = Field(max_length=100)
    action_items: list[MeetingActionItem] = Field(max_length=100)
    sources: list[Source] = Field(max_length=100)

    @model_validator(mode="after")
    def no_blank_text(self) -> Self:
        if not self.text.strip() or any(
            not item.strip()
            for item in [*self.conflicts, *self.decisions, *self.unresolved]
        ):
            raise ValueError("meeting summary contains blank text")
        return self
