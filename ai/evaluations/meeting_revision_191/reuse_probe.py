"""Explore the unmerged #137 module with synthetic data; never persist a draft.

Run only from an isolated AI checkout containing both #190 and #137. The caller
in this probe *pretends* the meeting decision was already confirmed; neither
module verifies the link between that decision and an actual meeting entry.
"""

import asyncio
import copy
import json

from pydantic import ValidationError

from app.schemas.documents import SourceInput
from app.schemas.meeting_analysis import MeetingAnalysisRequest
from app.services.document_parsing.normalization import text_digest
from app.services.document_parsing.pipeline import parse_source
from app.services.llm_client import LLMClient, LLMError, ProviderResponse
from app.services.meeting_analysis import analyze
from app.services.meeting_revision_bridge import generate_meeting_revision


class Provider:
    def __init__(self, response):
        self.response = response
        self.calls = 0

    async def complete(self, request, *, timeout_seconds):
        self.calls += 1
        return ProviderResponse(
            json.dumps(self.response, ensure_ascii=False), request.model, 10, 5
        )


async def probe():
    try:
        from app.services.document_revision import RevisionRequest, generate_revision
    except ModuleNotFoundError as exc:
        if exc.name == "app.services.document_revision":
            raise RuntimeError("137 document revision module is not installed") from exc
        raise

    decision = "삭제 권한은 팀장만 가진다."
    meeting = MeetingAnalysisRequest.model_validate(
        {
            "project_id": "101",
            "request_key": "probe-meeting",
            "mode": "MINUTES",
            "entries": [
                {
                    "id": "911",
                    "meeting_id": "901",
                    "kind": "TEXT",
                    "text": f"결정: {decision}",
                    "created_at": "2026-11-05T00:00:00Z",
                }
            ],
            "approved_items": [],
        }
    )
    meeting_provider = Provider(
        {
            "text": "삭제 권한을 팀장으로 제한하기로 했다.",
            "decisions": [decision],
            "unresolved": [],
            "action_items": [],
            "sources": [],
        }
    )
    minutes = await analyze(meeting, LLMClient(meeting_provider), "test-model")
    assert minutes["result"]["decisions"] == [decision]

    # This synthetic fixture explicitly assumes the decision was confirmed.
    # Production must obtain that confirmation and its entry linkage elsewhere.
    text = "첫 문단은 그대로 둡니다.\n\n삭제 권한은 모든 사용자에게 있습니다.\n\n마지막 문단입니다.\n"
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
    blocks = parse_source(source).blocks
    selected = next(block.block_id for block in blocks if block.locator.start_line == 3)
    other = next(block.block_id for block in blocks if block.block_id != selected)
    revision = RevisionRequest(
        source=source,
        expected_document_version_id="211",
        selected_block_ids=[selected],
        instruction="삭제 권한을 팀장으로 제한한다.",
        agreements=minutes["result"]["decisions"],
        request_key="probe-revision",
    )
    with_invalid_version = revision.model_dump()
    with_invalid_version["expected_document_version_id"] = "212"
    try:
        RevisionRequest.model_validate(with_invalid_version)
    except ValidationError:
        pass
    else:
        raise AssertionError("mismatched version accepted")

    change = {
        "block_id": selected,
        "replacement": "삭제 권한은 팀장만 가진다.\n",
        "reason": "회의에서 확인한 삭제 권한 반영",
        "evidence": [decision],
    }
    provider = Provider({"changes": [change], "unresolved": []})
    data = {
        "project_id": "101",
        "meeting_id": "901",
        "document_id": "201",
        "document_version_id": "211",
        "source": source.model_dump(),
        "entries": [{"id": "911", "meeting_id": "901"}],
        "decisions": [
            {"id": "decision-1", "text": decision, "evidence_entry_ids": ["911"]}
        ],
        "selected_block_ids": [selected],
        "instruction": revision.instruction,
        "request_key": "probe-revision",
    }
    linked = await generate_meeting_revision(data, LLMClient(provider), "test-model")
    draft = linked["draft"]
    assert linked["meeting"]["decisions"][0]["evidence_entry_ids"] == ["911"]
    assert draft["result"]["after_content"] == text.replace(
        "삭제 권한은 모든 사용자에게 있습니다.", "삭제 권한은 팀장만 가진다."
    )
    assert draft["result"]["changes"][0]["evidence"] == [decision]
    assert source.content == text
    assert "meeting_id" not in draft["result"]

    for field, value in [("block_id", other), ("evidence", ["없는 합의"])]:
        invalid = copy.deepcopy(change)
        invalid[field] = value
        bad_provider = Provider({"changes": [invalid], "unresolved": []})
        try:
            await generate_revision(revision, LLMClient(bad_provider), "test-model")
        except LLMError as exc:
            assert exc.code == "invalid_output"
        else:
            raise AssertionError(f"invalid {field} accepted")

    assert meeting_provider.calls == 1 and provider.calls == 1
    print("PASS: synthetic meeting -> scoped draft; version and scope guards")
    print("NOT VERIFIED: meeting decision confirmation, entry links, storage or GMS")


if __name__ == "__main__":
    asyncio.run(probe())
