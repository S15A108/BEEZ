import json
from types import SimpleNamespace

import pytest
from pydantic import BaseModel, ValidationError

from app.schemas.documents import SourceInput
from app.services.document_parsing.normalization import text_digest
from app.services.document_parsing.pipeline import parse_source
from app.services.llm_client import LLMClient, LLMError, LLMUsage, ProviderResponse
from app.services.meeting_revision_bridge import (
    DecisionRef,
    EntryRef,
    MeetingRevisionContext,
    RevisionModuleUnavailable,
    RevisionOutputError,
    connect_meeting_revision,
    generate_meeting_revision,
)
from app.services.meeting_revision_interface import prepare_meeting_revision


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


def real_input():
    text = "첫 문단\n\n삭제 권한은 모든 사용자에게 있습니다.\n\n마지막 문단\n"
    digest = text_digest(text)
    source = SourceInput(
        project_id="101",
        document_version_id="211",
        kind="PLAN",
        media_type="text/plain",
        content=text,
        content_hash=digest,
        text_hash=digest,
        normalization_version="utf8-lf-v1",
        parser_profile="plain-v1",
    )
    selected = next(
        b.block_id for b in parse_source(source).blocks if b.locator.start_line == 3
    )
    return {
        "project_id": "101",
        "meeting_id": "901",
        "document_id": "201",
        "document_version_id": "211",
        "source": source.model_dump(),
        "entries": [{"id": "911", "meeting_id": "901"}],
        "decisions": [
            {
                "id": "summary:1:decision:0",
                "text": "삭제 권한은 팀장만 가진다",
                "evidence_entry_ids": ["911"],
            }
        ],
        "selected_block_ids": [selected],
        "instruction": "삭제 권한을 팀장으로 제한",
        "request_key": "meeting-revision-test",
    }


class RealModuleProvider:
    def __init__(self, selected):
        self.selected = selected
        self.calls = 0

    async def complete(self, request, *, timeout_seconds):
        self.calls += 1
        output = {
            "changes": [
                {
                    "block_id": self.selected,
                    "replacement": "삭제 권한은 팀장만 가진다.\n",
                    "reason": "결정 반영",
                    "evidence": ["삭제 권한은 팀장만 가진다"],
                }
            ],
            "unresolved": [],
        }
        return ProviderResponse(
            json.dumps(output, ensure_ascii=False), request.model, 10, 5
        )


@pytest.mark.asyncio
async def test_actual_138_to_191_to_137_preserves_scope_and_meeting_links():
    data = real_input()
    provider = RealModuleProvider(data["selected_block_ids"][0])
    result = await generate_meeting_revision(data, LLMClient(provider), "test-model")
    assert provider.calls == 1
    assert result["meeting"]["decisions"] == data["decisions"]
    assert result["draft"]["result"]["after_content"] == data["source"][
        "content"
    ].replace("삭제 권한은 모든 사용자에게 있습니다.", "삭제 권한은 팀장만 가진다.")
    assert result["draft"]["usage"][0]["feature"] == "BEE_DRAFT"
    assert "evidence_entry_ids" not in result["draft"]["result"]["changes"][0]


@pytest.mark.asyncio
async def test_bridge_accepts_138_meeting_dict_directly():
    data = real_input()
    prepared = prepare_meeting_revision(data)
    provider = RealModuleProvider(data["selected_block_ids"][0])
    result = await connect_meeting_revision(
        prepared["meeting"], prepared["request"], LLMClient(provider), "test-model"
    )
    assert result["meeting"]["decisions"] == data["decisions"]
    assert provider.calls == 1


@pytest.mark.asyncio
@pytest.mark.parametrize("variant", ["missing_evidence", "wrong_scope", "version"])
async def test_138_invalid_input_never_calls_137_model(variant):
    data = real_input()
    provider = RealModuleProvider(data["selected_block_ids"][0])
    if variant == "missing_evidence":
        data["decisions"][0]["evidence_entry_ids"] = []
    elif variant == "wrong_scope":
        data["selected_block_ids"] = ["unknown"]
    else:
        data["document_version_id"] = "other"
    with pytest.raises(ValueError):
        await generate_meeting_revision(data, LLMClient(provider), "test-model")
    assert provider.calls == 0
