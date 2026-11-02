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


class MeetingAnalysisRequest(ContractModel):
    project_id: TextId
    request_key: TextId
    # SUMMARY is a proposed contract, not the currently agreed MVP format.
    mode: Literal["QUESTIONS", "MINUTES", "SUMMARY"]
    entries: list[MeetingEntry] = Field(min_length=1, max_length=200)
    approved_items: list[DesignItemVersion] = Field(max_length=100)
    change_proposal: dict[str, Any] | None = None

    @model_validator(mode="after")
    def validate_context(self) -> Self:
        if self.mode == "SUMMARY":
            raise ValueError("SUMMARY contract is not agreed")
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
