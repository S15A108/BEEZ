"""Prepare a scoped revision from caller-confirmed meeting decisions.

The caller owns authorization, decision confirmation and freshness checks.
This function neither loads records nor calls a model or stores a draft.
"""

from typing import Any

from pydantic import BaseModel, ConfigDict, Field, model_validator

from app.schemas.documents import SourceInput
from app.services.document_parsing.pipeline import parse_source


class Contract(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)


class Entry(Contract):
    id: str = Field(min_length=1, max_length=128)
    meeting_id: str = Field(min_length=1, max_length=128)


class Decision(Contract):
    id: str = Field(min_length=1, max_length=128)
    text: str = Field(min_length=1, max_length=4000)
    evidence_entry_ids: list[str] = Field(min_length=1, max_length=100)


class MeetingRevisionInput(Contract):
    project_id: str = Field(min_length=1, max_length=128)
    meeting_id: str = Field(min_length=1, max_length=128)
    document_id: str = Field(min_length=1, max_length=128)
    document_version_id: str = Field(min_length=1, max_length=256)
    source: SourceInput
    entries: list[Entry] = Field(min_length=1, max_length=200)
    decisions: list[Decision] = Field(min_length=1, max_length=30)
    selected_block_ids: list[str] = Field(min_length=1, max_length=30)
    instruction: str = Field(min_length=1, max_length=4000)
    related_proposals: list[str] = Field(default_factory=list, max_length=30)
    request_key: str = Field(min_length=1, max_length=128)

    @model_validator(mode="after")
    def check_links(self):
        values = [
            self.project_id,
            self.meeting_id,
            self.document_id,
            self.document_version_id,
            self.request_key,
        ]
        values += [entry.id for entry in self.entries]
        values += [decision.id for decision in self.decisions]
        values += [decision.text for decision in self.decisions]
        if any(not value.strip() or value != value.strip() for value in values):
            raise ValueError("blank or unnormalized identifier or decision")
        if not self.instruction.strip() or any(
            not v.strip() or len(v) > 4000 for v in self.related_proposals
        ):
            raise ValueError("invalid instruction or proposal")
        entries = [entry.id for entry in self.entries]
        ids = [decision.id for decision in self.decisions]
        texts = [decision.text for decision in self.decisions]
        if (
            len(set(entries)) != len(entries)
            or len(set(ids)) != len(ids)
            or len(set(texts)) != len(texts)
        ):
            raise ValueError("duplicate entry or decision")
        if any(entry.meeting_id != self.meeting_id for entry in self.entries):
            raise ValueError("entry belongs to another meeting")
        for decision in self.decisions:
            refs = decision.evidence_entry_ids
            if len(set(refs)) != len(refs) or not set(refs) <= set(entries):
                raise ValueError("invalid evidence references")
        if (
            self.project_id != self.source.project_id
            or self.document_version_id != self.source.document_version_id
        ):
            raise ValueError("source identity mismatch")
        if len(set(self.selected_block_ids)) != len(self.selected_block_ids):
            raise ValueError("duplicate selection")
        return self


def prepare_meeting_revision(data: dict[str, Any]) -> dict[str, Any]:
    """Return the proposed #191 context and #137 request without side effects."""
    body = MeetingRevisionInput.model_validate(data)
    document = parse_source(body.source)
    if document.normalized_text != body.source.content:
        raise ValueError("source must be normalized")
    if not set(body.selected_block_ids) <= {
        block.block_id for block in document.blocks
    }:
        raise ValueError("selection does not belong to source")
    context = body.model_dump(
        include={
            "project_id",
            "meeting_id",
            "document_id",
            "document_version_id",
            "entries",
            "decisions",
        }
    )
    request = {
        "source": body.source.model_dump(),
        "expected_document_version_id": body.document_version_id,
        "selected_block_ids": body.selected_block_ids,
        "instruction": body.instruction,
        "agreements": [decision.text for decision in body.decisions],
        "related_proposals": body.related_proposals,
        "request_key": body.request_key,
    }
    return {"meeting": context, "request": request}
