from types import SimpleNamespace

import pytest
from pydantic import BaseModel, ValidationError

from app.services.llm_client import LLMError, LLMUsage
from app.services.meeting_revision_bridge import (
    DecisionRef,
    EntryRef,
    MeetingRevisionContext,
    RevisionModuleUnavailable,
    RevisionOutputError,
    connect_meeting_revision,
)


class Source(BaseModel):
    project_id: str
    document_version_id: str


class Request(BaseModel):
    source: Source
    expected_document_version_id: str
    selected_block_ids: list[str]
    agreements: list[str]


class Shared:
    RevisionRequest = Request

    def __init__(self):
        self.calls = 0
        self.output = {
            "result": {
                "document_version_id": "211",
                "changes": [{"block_id": "block-1", "evidence": []}],
            },
            "usage": [{"feature": "BEE_DRAFT"}],
        }

    async def generate_revision(self, request, client, model):
        self.calls += 1
        return self.output


def context():
    return MeetingRevisionContext(
        project_id="101",
        meeting_id="901",
        document_id="201",
        document_version_id="211",
        entries=[EntryRef(id="911", meeting_id="901")],
        decisions=[
            DecisionRef(
                id="d1", text="삭제 권한을 팀장으로 제한", evidence_entry_ids=["911"]
            )
        ],
    )


def prepared():
    return {
        "source": {"project_id": "101", "document_version_id": "211"},
        "expected_document_version_id": "211",
        "selected_block_ids": ["block-1"],
        "agreements": ["삭제 권한을 팀장으로 제한"],
    }


@pytest.mark.asyncio
async def test_calls_shared_once_and_keeps_references_separate_from_draft():
    shared = Shared()
    output = await connect_meeting_revision(
        context(), prepared(), None, "test", revision_module=shared
    )
    assert shared.calls == 1
    assert output["draft"] == shared.output
    assert output["draft"]["usage"] == [{"feature": "BEE_DRAFT"}]
    assert output["meeting"]["decisions"][0]["evidence_entry_ids"] == ["911"]
    assert "evidence_entry_ids" not in output["draft"]["result"]["changes"][0]


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "variant", ["project", "version", "empty_scope", "duplicate_scope", "agreement"]
)
async def test_mismatch_rejected_before_shared_call(variant):
    req = prepared()
    if variant == "project":
        req["source"]["project_id"] = "other"
    elif variant == "version":
        req["source"]["document_version_id"] = "other"
    elif variant == "empty_scope":
        req["selected_block_ids"] = []
    elif variant == "duplicate_scope":
        req["selected_block_ids"] = ["block-1", "block-1"]
    else:
        req["agreements"] = ["다른 결정"]
    shared = Shared()
    with pytest.raises(ValueError):
        await connect_meeting_revision(
            context(), req, None, "test", revision_module=shared
        )
    assert shared.calls == 0


@pytest.mark.parametrize(
    "variant", ["wrong_meeting", "unknown_entry", "duplicate_decision"]
)
def test_context_rejects_inconsistent_references(variant):
    body = context().model_dump()
    if variant == "wrong_meeting":
        body["entries"][0]["meeting_id"] = "other"
    elif variant == "unknown_entry":
        body["decisions"][0]["evidence_entry_ids"] = ["unknown"]
    else:
        body["decisions"].append(body["decisions"][0].copy())
    with pytest.raises(ValidationError):
        MeetingRevisionContext.model_validate(body)


@pytest.mark.asyncio
async def test_wrong_output_version_or_block_is_not_connected():
    shared = Shared()
    shared.output["result"]["changes"][0]["block_id"] = "outside"
    with pytest.raises(RevisionOutputError) as caught:
        await connect_meeting_revision(
            context(), prepared(), None, "test", revision_module=shared
        )
    assert shared.calls == 1
    assert caught.value.usage == [{"feature": "BEE_DRAFT"}]
    assert "outside" not in str(caught.value)


@pytest.mark.asyncio
async def test_shared_error_propagates_without_fallback():
    class Failure(Shared):
        async def generate_revision(self, request, client, model):
            self.calls += 1
            raise RuntimeError("shared call failed")

    shared = Failure()
    with pytest.raises(RuntimeError, match="shared call failed"):
        await connect_meeting_revision(
            context(), prepared(), SimpleNamespace(), "test", revision_module=shared
        )
    assert shared.calls == 1


@pytest.mark.asyncio
async def test_shared_llm_error_and_usage_are_preserved():
    usage = LLMUsage(
        request_key="call-1",
        feature="BEE_DRAFT",
        project_id="101",
        model="test",
        latency_ms=12,
        failed=True,
        tokens_known=False,
    )
    expected = LLMError("invalid_output", (usage,))

    class Failure(Shared):
        async def generate_revision(self, request, client, model):
            raise expected

    with pytest.raises(LLMError) as caught:
        await connect_meeting_revision(
            context(), prepared(), None, "test", revision_module=Failure()
        )
    assert caught.value is expected
    assert caught.value.usage == (usage,)


@pytest.mark.asyncio
async def test_unavailable_shared_module_never_falls_back(monkeypatch):
    def missing(_name):
        raise ModuleNotFoundError(name="app.services.document_revision")

    monkeypatch.setattr("app.services.meeting_revision_bridge.import_module", missing)
    with pytest.raises(RevisionModuleUnavailable):
        await connect_meeting_revision(context(), prepared(), None, "test")
