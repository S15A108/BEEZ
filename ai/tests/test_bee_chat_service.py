"""Answer verification checks the model output against the inputs; fakes only."""

import json

import pytest

from app.schemas.bee_chat import BeeChatRequest, BeeInstanceRequest, ProjectProfile
from app.services.bee_chat import (
    INSUFFICIENT_TEXT,
    OUT_OF_SCOPE_TEXT,
    REVIEW_NOTICE,
    UNVERIFIED_NOTE,
    AnswerProposal,
    LLMAnswerProposer,
    ModelAnswer,
    build_instance,
    generate_bee_chat,
    renumber_markers,
)
from app.services.bee_chat import proposer as proposer_module
from app.services.llm_client import (
    LLMClient,
    LLMContext,
    LLMError,
    LLMUsage,
    ProviderError,
    ProviderResponse,
)

PROFILE = "gms-small-1024-v1"
LIMIT = "단일 파일 업로드 한도는 30 MB이며, 초과하면 HTTP 413을 반환한다."
PUBLISH = "발행은 리더만 수행한다."
ITEM_SOURCE = {
    "document_id": "301",
    "document_version_id": "311",
    "version_no": 1,
    "location": "발행 권한 / L11-L11",
}


def evidence(evidence_id, text, section=None, line=3) -> dict:
    return {
        "evidence_id": evidence_id,
        "document_id": "202",
        "document_version_id": "212",
        "version_no": 2,
        "chunk_key": f"chk_{evidence_id}",
        "text": text,
        "locator": {"type": "text_lines", "start_line": line, "end_line": line},
        "embedding_profile_id": PROFILE,
        "title": "업로드 요구사항",
        "section_title": section,
    }


def request_dict(**overrides) -> dict:
    fields = {
        "project_id": "101",
        "request_key": "bee-1",
        "question": "파일 한도는 얼마인가요?",
        "instance": {"profile_version": "bee-v1", "project": {"name": "나래"}},
        "scope": {
            "mode": "APPROVED",
            "document_version_ids": ["212", "311"],
            "baseline_id": "401",
        },
        "embedding_profile_id": PROFILE,
        "retrieved_evidence": [
            evidence("ev-1", LIMIT, "파일 크기"),
            evidence("ev-2", PUBLISH, None, 11),
        ],
        "approved_items": [
            {
                "id": "1101",
                "design_item_id": "1001",
                "key": "PLN-001",
                "kind": "POLICY",
                "version_no": 1,
                "status": "APPROVED",
                "content": {"description": PUBLISH},
                "source": ITEM_SOURCE,
            }
        ],
        "related_tasks": [{"id": "501", "key": "TASK-001", "status": "READY"}],
    }
    fields.update(overrides)
    return fields


def request(**overrides) -> BeeChatRequest:
    return BeeChatRequest.model_validate_json(
        json.dumps(request_dict(**overrides), ensure_ascii=False)
    )


def answer(**overrides) -> dict:
    fields = {
        "status": "ANSWERED",
        "text": "한도는 30 MB이고 초과하면 413입니다 [1].",
        "citations": [{"evidence_id": "ev-1", "quote": LIMIT, "claim": "한도"}],
        "design_refs": [],
        "task_refs": [],
        "unresolved": [],
        "next_actions": [],
    }
    fields.update(overrides)
    return fields


class Scripted:
    def __init__(self, raw: dict) -> None:
        self.raw = raw
        self.seen = None

    async def propose(self, request, evidence):
        self.seen = (request, evidence)
        usage = LLMUsage(
            request_key="k",
            feature="BEE_CHAT",
            project_id="101",
            model="fake",
            latency_ms=1,
            failed=False,
            tokens_known=False,
        )
        return AnswerProposal(ModelAnswer.model_validate(self.raw), "fake", (usage,))


async def run(raw: dict, **overrides):
    return await generate_bee_chat(request(**overrides), Scripted(raw))


async def rejected(raw: dict, **overrides) -> LLMError:
    with pytest.raises(LLMError) as info:
        await run(raw, **overrides)
    error = info.value
    assert error.code == "invalid_output" and error.usage[-1].failed
    assert error.to_api_dict()["error"]["code"] == "model_unavailable"
    return error


def test_renumber_markers_rewrites_kept_and_drops_removed():
    assert renumber_markers("a [1] b [2] c [3]", {1: 1, 3: 2}) == "a [1] b c [2]"
    assert renumber_markers("[1] 앞 [2]", {2: 1}) == "앞 [1]"
    assert renumber_markers("no markers", {}) == "no markers"


@pytest.mark.asyncio
async def test_unverifiable_quotes_are_dropped_and_markers_renumbered():
    raw = answer(
        text="한도는 50 MB이고 [1] 초과하면 413이며 [2], 발행은 리더만 합니다 [3].",
        citations=[
            {"evidence_id": "ev-1", "quote": "한도는 50 MB이다."},
            {"evidence_id": "ev-1", "quote": LIMIT},
            {"evidence_id": "ev-2", "quote": PUBLISH},
            {"evidence_id": "ev-2", "quote": PUBLISH},
        ],
    )
    outcome = await run(raw)
    result = outcome.result
    assert outcome.origin == "MODEL" and outcome.model == "fake"
    assert result.answer_status == "ANSWERED" and result.grounded
    assert [c.n for c in result.citations] == [1, 2]
    assert [c.quote for c in result.citations] == [LIMIT, PUBLISH]
    assert (
        result.text
        == "한도는 50 MB이고 초과하면 413이며 [1], 발행은 리더만 합니다 [2]."
    )
    assert result.citations[0].section_title == "파일 크기"
    assert result.citations[0].claim is None
    assert [s.location for s in result.sources] == ["파일 크기 / L3-L3", "L11-L11"]
    assert result.evidence_considered == 2 and result.evidence_omitted == 0
    assert (
        result.instance.system_prompt_hash
        == build_instance(
            BeeInstanceRequest(
                project_id="101", request_key="k", project=ProjectProfile(name="나래")
            )
        ).system_prompt_hash
    )
    assert "50 MB" not in {c.quote for c in result.citations}


@pytest.mark.asyncio
async def test_unknown_ids_reject_the_output_and_keep_usage():
    await rejected(answer(citations=[{"evidence_id": "ev-9", "quote": LIMIT}]))
    await rejected(answer(design_refs=["9999"]))
    await rejected(answer(task_refs=["999"]))
    await rejected(answer(text="   "))
    # The model only saw the capped evidence, so a dropped chunk is unknown.
    await rejected(
        answer(citations=[{"evidence_id": "ev-2", "quote": PUBLISH}]), max_evidence=1
    )


@pytest.mark.asyncio
async def test_an_answer_without_verifiable_grounding_is_withheld():
    raw = answer(
        text="한도는 50 MB입니다 [1].",
        citations=[{"evidence_id": "ev-1", "quote": "한도는 50 MB이다."}],
        task_refs=["501"],
        unresolved=["단위가 MiB인지 불명확"],
        next_actions=["요구사항 담당에게 확인"],
    )
    result = (await run(raw)).result
    assert result.answer_status == "INSUFFICIENT_EVIDENCE" and not result.grounded
    assert result.text == INSUFFICIENT_TEXT
    assert result.citations == () and result.task_refs == () and result.sources == ()
    assert result.unresolved == ("단위가 MiB인지 불명확", UNVERIFIED_NOTE)
    assert result.next_actions == ("요구사항 담당에게 확인",)
    assert "50 MB" not in json.dumps(result.model_dump(mode="json"), ensure_ascii=False)


@pytest.mark.asyncio
async def test_non_answers_use_the_fixed_text_and_drop_citations():
    raw = answer(
        status="INSUFFICIENT_EVIDENCE",
        text="",
        citations=[{"evidence_id": "ev-1", "quote": LIMIT}],
        design_refs=["1101"],
        unresolved=["보관 기간은 문서에 없다."],
    )
    result = (await run(raw)).result
    assert result.answer_status == "INSUFFICIENT_EVIDENCE"
    assert result.text == INSUFFICIENT_TEXT and not result.citations
    assert not result.design_refs and result.unresolved == ("보관 기간은 문서에 없다.",)
    scope = (await run(answer(status="OUT_OF_SCOPE", text="", citations=[]))).result
    assert scope.answer_status == "OUT_OF_SCOPE" and scope.text == OUT_OF_SCOPE_TEXT
    assert not scope.grounded and not scope.sources


@pytest.mark.asyncio
async def test_approved_items_ground_an_answer_and_add_their_sources():
    raw = answer(
        text="발행은 리더만 합니다. TASK-001이 이를 구현합니다.",
        citations=[],
        design_refs=["1101", "1101"],
        task_refs=["501"],
    )
    result = (await run(raw)).result
    assert result.grounded and result.answer_status == "ANSWERED"
    assert [ref.key for ref in result.design_refs] == ["PLN-001"]
    assert result.design_refs[0].design_item_version_id == "1101"
    assert [ref.key for ref in result.task_refs] == ["TASK-001"]
    assert [s.model_dump(mode="json") for s in result.sources] == [ITEM_SOURCE]


@pytest.mark.asyncio
async def test_evidence_cap_and_review_scope_add_notices():
    proposer = Scripted(answer())
    outcome = await generate_bee_chat(request(max_evidence=1), proposer)
    assert [item.evidence_id for item in proposer.seen[1]] == ["ev-1"]
    result = outcome.result
    assert result.evidence_considered == 1 and result.evidence_omitted == 1
    assert any("1건" in notice for notice in result.notices)
    review = (
        await run(
            answer(),
            scope={"mode": "DOCUMENT_REVIEW", "document_version_ids": ["212"]},
            instance=None,
        )
    ).result
    assert REVIEW_NOTICE in review.notices and review.instance is None


@pytest.mark.asyncio
async def test_known_unresolved_come_first_and_duplicates_collapse():
    raw = answer(unresolved=["모델 메모", "이미 아는 것", "모델 메모"])
    result = (await run(raw, unresolved=["이미 아는 것"])).result
    assert result.unresolved == ("이미 아는 것", "모델 메모")


class FakeProvider:
    def __init__(self, content, *, model="gpt-6-luna") -> None:
        self.content = content
        self.model = model
        self.requests = []

    async def complete(self, request, *, timeout_seconds):
        self.requests.append(request)
        if isinstance(self.content, Exception):
            raise self.content
        return ProviderResponse(self.content, self.model, 10, 5)


def proposer(provider, **overrides) -> LLMAnswerProposer:
    fields = {
        "context": LLMContext(project_id="101", feature="BEE_CHAT", request_key="r"),
        "model": "gpt-6-luna",
        "call_key": "llm-bee-chat-1",
        "timeout_seconds": 5,
    }
    fields.update(overrides)
    return LLMAnswerProposer(LLMClient(provider), **fields)


@pytest.mark.asyncio
async def test_llm_proposer_builds_the_prompt_from_the_instance_and_history():
    provider = FakeProvider(json.dumps(answer(), ensure_ascii=False))
    body = request(history=[{"role": "USER", "text": "이전 질문"}])
    outcome = await generate_bee_chat(body, proposer(provider))
    assert outcome.result.answer_status == "ANSWERED"
    assert (
        outcome.usage[0].feature == "BEE_CHAT" and outcome.usage[0].input_tokens == 10
    )
    sent = provider.requests[0]
    assert "「나래」" in sent.messages[0].content
    payload = json.loads(sent.messages[1].content)
    assert payload["history"] == [{"role": "USER", "text": "이전 질문"}]
    assert [item["evidence_id"] for item in payload["evidence"]] == ["ev-1", "ev-2"]
    assert payload["approved_items"][0]["key"] == "PLN-001"
    assert payload["related_tasks"][0]["plan"] is None
    assert sent.response_format == {"type": "json_object"}


@pytest.mark.asyncio
async def test_llm_proposer_rejects_bad_context_output_and_oversized_payload(
    monkeypatch,
):
    mismatched = proposer(
        FakeProvider("{}"),
        context=LLMContext(project_id="other", feature="BEE_CHAT", request_key="r"),
    )
    with pytest.raises(LLMError) as info:
        await generate_bee_chat(request(), mismatched)
    assert info.value.code == "invalid_input" and not info.value.usage

    with pytest.raises(LLMError) as info:
        await generate_bee_chat(
            request(), proposer(FakeProvider('{"status": "ANSWERED"}'))
        )
    assert info.value.code == "invalid_output" and info.value.usage[0].failed

    with pytest.raises(LLMError) as info:
        await generate_bee_chat(
            request(), proposer(FakeProvider(ProviderError("model_timeout")))
        )
    assert info.value.code == "model_timeout" and info.value.usage[0].failed

    monkeypatch.setattr(proposer_module, "MAX_PAYLOAD_BYTES", 10)
    with pytest.raises(LLMError) as info:
        await generate_bee_chat(request(), proposer(FakeProvider("{}")))
    assert info.value.code == "invalid_input"
