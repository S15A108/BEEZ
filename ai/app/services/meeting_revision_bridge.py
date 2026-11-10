"""Internal meeting-to-revision seam; no storage, approval or authorization.

The caller must supply decisions it has confirmed, their meeting-entry links,
and an already scoped #138 request. This module does not establish whether a
statement was agreed or whether a caller may access a project/document.
"""

import asyncio
from importlib import import_module
from typing import Any, Protocol

from pydantic import BaseModel, ConfigDict, Field, model_validator

from app.services.meeting_revision_interface import prepare_meeting_revision


class Contract(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)


class EntryRef(Contract):
    id: str = Field(min_length=1, max_length=128)
    meeting_id: str = Field(min_length=1, max_length=128)


class DecisionRef(Contract):
    id: str = Field(min_length=1, max_length=128)
    text: str = Field(min_length=1, max_length=4000)
    evidence_entry_ids: list[str] = Field(min_length=1, max_length=100)


class MeetingRevisionContext(Contract):
    project_id: str = Field(min_length=1, max_length=128)
    meeting_id: str = Field(min_length=1, max_length=128)
    document_id: str = Field(min_length=1, max_length=128)
    document_version_id: str = Field(min_length=1, max_length=256)
    entries: list[EntryRef] = Field(min_length=1, max_length=200)
    decisions: list[DecisionRef] = Field(min_length=1, max_length=30)

    @model_validator(mode="after")
    def verify_links(self):
        ids = [entry.id for entry in self.entries]
        decision_ids = [item.id for item in self.decisions]
        texts = [item.text for item in self.decisions]
        all_strings = [
            self.project_id,
            self.meeting_id,
            self.document_id,
            self.document_version_id,
        ]
        all_strings += ids + decision_ids + texts
        if any(not s.strip() or s != s.strip() for s in all_strings):
            raise ValueError("blank or unnormalized identifier/decision")
        if len(set(ids)) != len(ids) or len(set(decision_ids)) != len(decision_ids):
            raise ValueError("duplicate entry or decision")
        if len(set(texts)) != len(texts):
            raise ValueError("ambiguous decision text")
        if any(entry.meeting_id != self.meeting_id for entry in self.entries):
            raise ValueError("entry belongs to a different meeting")
        for decision in self.decisions:
            refs = decision.evidence_entry_ids
            if len(set(refs)) != len(refs) or not set(refs) <= set(ids):
                raise ValueError("unknown or duplicate entry reference")
        return self


class RevisionModule(Protocol):
    RevisionRequest: Any

    async def generate_revision(
        self, request: Any, client: Any, model: str
    ) -> dict: ...


class RevisionModuleUnavailable(RuntimeError):
    """The shared revision implementation is not installed."""


class RevisionOutputError(ValueError):
    """Reject a shared response without losing recorded model usage."""

    def __init__(self, usage: Any):
        super().__init__("invalid shared revision response")
        self.usage = usage if isinstance(usage, list) else []


def _module() -> RevisionModule:
    try:
        return import_module("app.services.document_revision")
    except ModuleNotFoundError as exc:
        if exc.name == "app.services.document_revision":
            raise RevisionModuleUnavailable(
                "shared revision module is not installed"
            ) from exc
        raise


async def connect_meeting_revision(
    context: MeetingRevisionContext | dict[str, Any],
    prepared_request: dict[str, Any],
    client: Any,
    model: str,
    *,
    revision_module: RevisionModule | None = None,
) -> dict[str, Any]:
    """Call #137 once using #138's supplied request and retain meeting links.

    This is an internal seam, not a settled #138 wire contract. It cannot verify
    real membership, approval, document ownership or live baseline freshness.
    The caller must recheck those before storing an unapproved draft.
    """
    context = MeetingRevisionContext.model_validate(
        context.model_dump() if isinstance(context, MeetingRevisionContext) else context
    )
    shared = revision_module if revision_module is not None else _module()
    request = shared.RevisionRequest.model_validate(prepared_request)
    if (
        request.source.project_id != context.project_id
        or request.source.document_version_id != context.document_version_id
        or request.expected_document_version_id != context.document_version_id
        or not request.selected_block_ids
        or len(set(request.selected_block_ids)) != len(request.selected_block_ids)
        or request.agreements != [decision.text for decision in context.decisions]
    ):
        raise ValueError("meeting and revision snapshots do not match")

    response = await shared.generate_revision(request, client, model)
    if not isinstance(response, dict) or not isinstance(response.get("result"), dict):
        raise RevisionOutputError(
            response.get("usage") if isinstance(response, dict) else None
        )
    result = response["result"]
    changes = result.get("changes")
    if (
        result.get("document_version_id") != context.document_version_id
        or not isinstance(changes, list)
        or any(
            not isinstance(change, dict)
            or change.get("block_id") not in request.selected_block_ids
            for change in changes
        )
    ):
        raise RevisionOutputError(response.get("usage"))
    # Preserve caller-supplied references separately: a generated change or
    # evidence quote does not itself prove which entry caused it.
    return {
        "meeting": context.model_dump(),
        "draft": response,
    }


async def generate_meeting_revision(
    data: dict[str, Any], client: Any, model: str
) -> dict[str, Any]:
    """Connect #138's prepared data to #137 without storing or approving it.

    The caller must confirm decisions and entry links, authorize the request,
    and recheck the meeting snapshot and current document before any use.
    """
    prepared = await asyncio.to_thread(prepare_meeting_revision, data)
    context = MeetingRevisionContext.model_validate(prepared["meeting"])
    return await connect_meeting_revision(context, prepared["request"], client, model)
