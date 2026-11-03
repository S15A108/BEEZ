"""Service composes rule hits with source-checked model proposals; fakes only."""

import json
from hashlib import sha256

import pytest

from app.schemas.planning_questions import (
    ChoiceAnswerFormat,
    DocumentRef,
    EssayAnswerFormat,
    PlanningQuestionRequest,
    ShortAnswerFormat,
)
from app.services.document_parsing.pipeline import parse_source
from app.services.llm_client import (
    LLMClient,
    LLMContext,
    LLMError,
    LLMUsage,
    ProviderError,
    ProviderResponse,
)
from app.services.planning_questions import (
    CandidateProposal,
    LLMCandidateProposer,
    ModelCandidate,
    QuestionDraft,
    generate_planning_questions,
    model_candidate_key,
)
from app.services.planning_questions import proposer as proposer_module

DOC = (
    "# 업로드\n\n단일 파일 한도는 30 MB이다. 합계 한도는 이 문서에서 정의하지 않는다.\n\n"
    "## 재시도\n\n재시도는 멤버가 수행한다.\n\n| 항목 | 값 |\n| --- | --- |\n"
    "| 보관 기간 | 미정 |\n"
)
REF = DocumentRef(document_id="201", document_version_id="211", version_no=1)


def request(content=DOC, **overrides):
    digest = sha256(content.encode("utf-8")).hexdigest()
    fields = {
        "project_id": "101",
        "request_key": "planning-1",
        "document": REF,
        "kind": "REQUIREMENTS",
        "media_type": "text/markdown",
        "content": content,
        "content_hash": digest,
        "text_hash": digest,
        "normalization_version": "utf8-lf-v1",
        "parser_profile": "markdown-v1",
    }
    fields.update(overrides)
    return PlanningQuestionRequest(**fields)


def usage():
    return LLMUsage(
        request_key="call-1",
        feature="DOCUMENT_PARSE",
        project_id="101",
        model="fake",
        latency_ms=1,
        failed=False,
        tokens_known=True,
        input_tokens=10,
        output_tokens=5,
    )


def proposal(kind, summary, evidence, question=None):
    return {
        "kind": kind,
        "summary": summary,
        "evidence": evidence,
        "question": question
        or {"type": "SHORT_ANSWER", "text": "값은 무엇인가요?", "options": []},
    }


class FakeProposer:
    """Line anchors become real block ids; literal block ids are used as is."""

    def __init__(self, *proposals):
        self.proposals = proposals
        self.documents = []

    async def propose(self, document):
        self.documents.append(document)

        def block_for(item):
            if "block_id" in item:
                return item["block_id"]
            for block in document.blocks:
                if block.locator.start_line <= item["line"] <= block.locator.end_line:
                    return block.block_id
            raise KeyError(item)

        candidates = tuple(
            ModelCandidate.model_validate(
                {
                    **item,
                    "evidence": [
                        {"block_id": block_for(e), "quote": e["quote"]}
                        for e in item["evidence"]
                    ],
                }
            )
            for item in self.proposals
        )
        return CandidateProposal(candidates, "fake", (usage(),))


def by_summary(result):
    return {candidate.summary: candidate for candidate in result.candidates}


def question_for(result, candidate):
    return next(
        q for q in result.questions if q.candidate_key == candidate.candidate_key
    )


@pytest.mark.asyncio
async def test_rules_only_mode_returns_template_questions_without_a_model():
    outcome = await generate_planning_questions(request(mode="RULES_ONLY"), None)
    assert outcome.origin == "TEMPLATE" and outcome.model is None
    assert outcome.usage == ()
    keys = [candidate.candidate_key for candidate in outcome.result.candidates]
    assert keys == ["rule-undecided-L3-1", "rule-placeholder-L11-c2"]
    assert {question.origin for question in outcome.result.questions} == {"TEMPLATE"}
    assert [q.rank for q in outcome.result.questions] == [1, 2]
    assert "usage" not in outcome.to_api_dict()


@pytest.mark.asyncio
async def test_model_mode_needs_a_proposer():
    with pytest.raises(ValueError):
        await generate_planning_questions(request(), None)


@pytest.mark.asyncio
async def test_verified_model_candidate_replaces_the_overlapping_rule_hit():
    proposer = FakeProposer(
        proposal(
            "MISSING_CANDIDATE",
            "합계 한도가 없습니다.",
            [{"line": 3, "quote": "합계 한도는 이 문서에서 정의하지 않는다."}],
            {"type": "ESSAY", "text": "합계 한도를 둘까요?", "options": []},
        )
    )
    outcome = await generate_planning_questions(request(), proposer)
    keys = {candidate.candidate_key for candidate in outcome.result.candidates}
    assert "rule-undecided-L3-1" not in keys
    model = by_summary(outcome.result)["합계 한도가 없습니다."]
    assert model.candidate_key.startswith("model-")
    assert model.status == "REVIEW_CANDIDATE" and model.signal == "MODEL_PROPOSAL"
    assert model.evidence[0].location == "업로드 / L3-L3"
    assert model.evidence[0].locator.start_line == 3
    question = question_for(outcome.result, model)
    assert question.question_key == f"q-{model.candidate_key}"
    assert question.origin == "MODEL" and question.question_type == "ESSAY"
    assert isinstance(question.answer_format, EssayAnswerFormat)
    assert outcome.origin == "MODEL" and outcome.model == "fake"
    assert outcome.usage[0].request_key == "call-1"
    assert proposer.documents[0].document_version_id == "211"


@pytest.mark.asyncio
async def test_fabricated_quote_is_unverified_and_never_output():
    proposer = FakeProposer(
        proposal(
            "MISSING_CANDIDATE",
            "합계 한도가 다릅니다.",
            [{"line": 3, "quote": "합계 한도는 50 MB"}],
        )
    )
    outcome = await generate_planning_questions(request(), proposer)
    model = by_summary(outcome.result)["합계 한도가 다릅니다."]
    assert model.status == "UNVERIFIED"
    assert model.unverified_reason == "QUOTE_NOT_IN_BLOCK" and model.evidence == ()
    assert "50 MB" not in json.dumps(outcome.to_api_dict(), ensure_ascii=False)
    # The rule hit on the same block stays because the model candidate is unverified.
    keys = {candidate.candidate_key for candidate in outcome.result.candidates}
    assert "rule-undecided-L3-1" in keys
    assert question_for(outcome.result, model).basis == "UNVERIFIED"


@pytest.mark.asyncio
async def test_unknown_block_empty_evidence_and_partial_evidence_reasons():
    proposer = FakeProposer(
        proposal(
            "CLARIFICATION", "없는 블록", [{"block_id": "blk_x", "quote": "재시도"}]
        ),
        proposal("MISSING_CANDIDATE", "주제 없음", []),
        proposal(
            "CLARIFICATION",
            "일부만 맞음",
            [
                {"line": 7, "quote": "재시도는 멤버가 수행한다."},
                {"line": 7, "quote": "관리자만 수행한다."},
            ],
        ),
    )
    outcome = await generate_planning_questions(request(), proposer)
    candidates = by_summary(outcome.result)
    assert candidates["없는 블록"].unverified_reason == "BLOCK_NOT_FOUND"
    assert candidates["주제 없음"].unverified_reason == "NO_EVIDENCE"
    partial = candidates["일부만 맞음"]
    assert partial.status == "UNVERIFIED"
    assert partial.unverified_reason == "QUOTE_NOT_IN_BLOCK"
    assert [item.quote for item in partial.evidence] == ["재시도는 멤버가 수행한다."]


@pytest.mark.asyncio
async def test_conflict_needs_two_distinct_verified_blocks_and_ranks_first():
    same_block = proposal(
        "CONFLICT",
        "한 블록 안의 두 문장",
        [
            {"line": 3, "quote": "단일 파일 한도는 30 MB이다."},
            {"line": 3, "quote": "합계 한도는 이 문서에서 정의하지 않는다."},
        ],
        {"type": "CHOICE", "text": "어느 쪽인가요?", "options": ["a", "b"]},
    )
    two_blocks = proposal(
        "CONFLICT",
        "두 블록",
        [
            {"line": 3, "quote": "30 MB"},
            {"line": 7, "quote": "재시도는 멤버가 수행한다."},
        ],
        {"type": "CHOICE", "text": "어느 쪽인가요?", "options": ["a", "b"]},
    )
    outcome = await generate_planning_questions(
        request(), FakeProposer(same_block, two_blocks)
    )
    candidates = by_summary(outcome.result)
    assert (
        candidates["한 블록 안의 두 문장"].unverified_reason == "INSUFFICIENT_EVIDENCE"
    )
    assert candidates["두 블록"].status == "REVIEW_CANDIDATE"
    assert outcome.result.candidates[0].summary == "두 블록"
    assert outcome.result.candidates[-1].summary == "한 블록 안의 두 문장"


@pytest.mark.asyncio
async def test_priority_order_cap_and_truncation():
    proposer = FakeProposer(
        proposal(
            "CLARIFICATION",
            "설명 필요",
            [{"line": 7, "quote": "재시도는 멤버가 수행한다."}],
            {"type": "CHOICE", "text": "누구인가요?", "options": ["멤버", "리더"]},
        ),
        proposal("MISSING_CANDIDATE", "근거 없음", []),
    )
    outcome = await generate_planning_questions(request(max_questions=2), proposer)
    candidates = by_summary(outcome.result)
    clarification = candidates["설명 필요"].candidate_key
    unverified = candidates["근거 없음"].candidate_key
    keys = [candidate.candidate_key for candidate in outcome.result.candidates]
    # Verified MISSING hits by position, then verified CLARIFICATION, then unverified.
    assert keys == [
        "rule-undecided-L3-1",
        "rule-placeholder-L11-c2",
        clarification,
        unverified,
    ]
    assert [(q.rank, q.candidate_key) for q in outcome.result.questions] == [
        (1, "rule-undecided-L3-1"),
        (2, "rule-placeholder-L11-c2"),
    ]
    assert outcome.result.truncated_candidate_keys == (clarification, unverified)
    assert outcome.result.omitted_candidates == 0
    assert outcome.result.max_questions == 2


@pytest.mark.asyncio
async def test_model_keys_depend_on_content_not_on_output_order():
    anchored = proposal(
        "CLARIFICATION", "설명 A", [{"line": 7, "quote": "재시도는 멤버가 수행한다."}]
    )
    unanchored = proposal("MISSING_CANDIDATE", "근거 없음 B", [])
    one = await generate_planning_questions(
        request(), FakeProposer(anchored, unanchored)
    )
    two = await generate_planning_questions(
        request(), FakeProposer(unanchored, anchored)
    )
    first, second = by_summary(one.result), by_summary(two.result)
    assert first["설명 A"].candidate_key == second["설명 A"].candidate_key
    assert first["근거 없음 B"].candidate_key == second["근거 없음 B"].candidate_key
    assert first["설명 A"].candidate_key != first["근거 없음 B"].candidate_key
    assert first["근거 없음 B"].candidate_key == model_candidate_key(
        "MISSING_CANDIDATE",
        (),
        QuestionDraft(
            "SHORT_ANSWER", "  값은   무엇인가요? ", ShortAnswerFormat(), "MODEL"
        ),
    )
    # A different kind over the same quote is a different gap.
    other_kind = proposal(
        "MISSING_CANDIDATE",
        "설명 A",
        [{"line": 7, "quote": "재시도는 멤버가 수행한다."}],
    )
    three = await generate_planning_questions(request(), FakeProposer(other_kind))
    assert (
        by_summary(three.result)["설명 A"].candidate_key
        != first["설명 A"].candidate_key
    )


@pytest.mark.asyncio
async def test_different_questions_on_the_same_sentence_are_both_kept():
    quote = [{"line": 3, "quote": "단일 파일 한도는 30 MB이다."}]
    size = proposal(
        "CLARIFICATION",
        "한도 단위",
        quote,
        {
            "type": "SHORT_ANSWER",
            "text": "최대 업로드 크기는 몇 MB인가요?",
            "options": [],
        },
    )
    retention = proposal(
        "CLARIFICATION",
        "보관 기간",
        quote,
        {"type": "SHORT_ANSWER", "text": "파일은 며칠 동안 보관하나요?", "options": []},
    )
    one = await generate_planning_questions(request(), FakeProposer(size, retention))
    two = await generate_planning_questions(request(), FakeProposer(retention, size))
    first, second = by_summary(one.result), by_summary(two.result)
    assert {"한도 단위", "보관 기간"} <= set(first) and {
        "한도 단위",
        "보관 기간",
    } <= set(second)
    assert first["한도 단위"].candidate_key != first["보관 기간"].candidate_key
    assert first["한도 단위"].candidate_key == second["한도 단위"].candidate_key
    assert first["보관 기간"].candidate_key == second["보관 기간"].candidate_key
    # A different answer format over the same question text is also distinct.
    as_choice = proposal(
        "CLARIFICATION",
        "한도 단위(선택)",
        quote,
        {
            "type": "CHOICE",
            "text": "최대 업로드 크기는 몇 MB인가요?",
            "options": ["30", "50"],
        },
    )
    three = await generate_planning_questions(request(), FakeProposer(size, as_choice))
    assert len(by_summary(three.result)) == len(one.result.candidates)


@pytest.mark.asyncio
async def test_only_a_repeated_identical_question_collapses_to_the_first():
    anchored = proposal(
        "CLARIFICATION", "설명 A", [{"line": 7, "quote": "재시도는 멤버가 수행한다."}]
    )
    repeated = proposal(
        "CLARIFICATION",
        "설명 A의 다른 요약",
        [{"line": 7, "quote": "재시도는 멤버가 수행한다."}],
    )
    outcome = await generate_planning_questions(
        request(), FakeProposer(anchored, repeated)
    )
    model_summaries = [
        c.summary for c in outcome.result.candidates if c.signal == "MODEL_PROPOSAL"
    ]
    assert model_summaries == ["설명 A"]


@pytest.mark.asyncio
async def test_model_question_formats_are_typed():
    proposer = FakeProposer(
        proposal(
            "CLARIFICATION",
            "선택",
            [{"line": 7, "quote": "멤버"}],
            {
                "type": "CHOICE",
                "text": "누구?",
                "options": [" 멤버 ", "리더"],
                "multiple": True,
            },
        ),
        proposal(
            "CLARIFICATION",
            "단답",
            [{"line": 7, "quote": "수행한다"}],
            {"type": "SHORT_ANSWER", "text": "몇 회?", "options": []},
        ),
    )
    outcome = await generate_planning_questions(request(), proposer)
    candidates = by_summary(outcome.result)
    choice = question_for(outcome.result, candidates["선택"]).answer_format
    assert isinstance(choice, ChoiceAnswerFormat)
    assert choice.options == ("멤버", "리더") and choice.multiple and choice.allow_other
    short = question_for(outcome.result, candidates["단답"]).answer_format
    assert isinstance(short, ShortAnswerFormat)


class FakeProvider:
    def __init__(self, output):
        self.output = output
        self.calls = []

    async def complete(self, request, *, timeout_seconds):
        self.calls.append(request)
        if isinstance(self.output, Exception):
            raise self.output
        return ProviderResponse(
            json.dumps(self.output, ensure_ascii=False), request.model, 12, 7
        )


def llm_proposer(provider, *, project="101", **kwargs):
    return LLMCandidateProposer(
        LLMClient(provider),
        context=LLMContext(
            project_id=project, feature="DOCUMENT_PARSE", request_key="planning-1"
        ),
        model="fake",
        call_key="call-1",
        timeout_seconds=3,
        **kwargs,
    )


def document():
    return parse_source(request().to_source_input())


@pytest.mark.asyncio
async def test_llm_proposer_sends_blocks_and_returns_structured_candidates():
    provider = FakeProvider(
        {
            "candidates": [
                {
                    "kind": "CLARIFICATION",
                    "summary": "멤버의 범위",
                    "evidence": [{"block_id": "blk_any", "quote": "멤버"}],
                    "question": {"type": "ESSAY", "text": "누구인가요?"},
                }
            ]
        }
    )
    result = await llm_proposer(provider).propose(document())
    assert len(result.candidates) == 1 and result.model == "fake"
    assert result.usage[0].input_tokens == 12 and not result.usage[0].failed
    llm_request = provider.calls[0]
    assert "untrusted" in llm_request.messages[0].content
    payload = json.loads(llm_request.messages[1].content)
    assert payload["document"]["document_version_id"] == "211"
    assert payload["blocks"][1]["text"].startswith("단일 파일 한도는")
    assert payload["blocks"][1]["location"] == "업로드 / L3-L3"
    assert {"block_id", "type", "location", "text"} == set(payload["blocks"][0])
    assert llm_request.response_format == {"type": "json_object"}
    assert llm_request.model_options == {"max_completion_tokens": 2048}
    assert llm_request.context.feature == "DOCUMENT_PARSE"


@pytest.mark.parametrize(
    "bad_output",
    [
        {"candidates": [{"kind": "MISSING_CANDIDATE", "summary": "x", "evidence": []}]},
        {
            "candidates": [
                {
                    "kind": "DEFECT",
                    "summary": "x",
                    "evidence": [],
                    "question": {"type": "ESSAY", "text": "q"},
                }
            ]
        },
        {
            "candidates": [
                {
                    "kind": "CLARIFICATION",
                    "summary": "x",
                    "evidence": [],
                    "question": {"type": "CHOICE", "text": "q", "options": ["하나"]},
                }
            ]
        },
        {
            "candidates": [
                {
                    "kind": "CLARIFICATION",
                    "summary": "x",
                    "evidence": [],
                    "question": {"type": "ESSAY", "text": "q", "options": ["a", "b"]},
                }
            ]
        },
        {"candidates": [], "approved": True},
        {"candidates": [{"kind": "CLARIFICATION"}] * 21},
    ],
)
@pytest.mark.asyncio
async def test_structurally_invalid_model_output_keeps_failed_usage(bad_output):
    provider = FakeProvider(bad_output)
    with pytest.raises(LLMError) as error:
        await llm_proposer(provider).propose(document())
    assert error.value.code == "invalid_output"
    assert error.value.usage[0].failed and error.value.usage[0].input_tokens == 12


@pytest.mark.asyncio
async def test_scope_mismatch_and_oversized_document_never_reach_the_provider(
    monkeypatch,
):
    provider = FakeProvider({"candidates": []})
    with pytest.raises(LLMError) as error:
        await llm_proposer(provider, project="other").propose(document())
    assert error.value.code == "invalid_input" and not provider.calls
    monkeypatch.setattr(proposer_module, "MAX_PAYLOAD_BYTES", 10)
    with pytest.raises(LLMError) as error:
        await llm_proposer(provider).propose(document())
    assert error.value.code == "invalid_input" and not provider.calls


@pytest.mark.asyncio
async def test_provider_failure_keeps_real_failed_usage():
    provider = FakeProvider(
        ProviderError(
            "model_unavailable", model="fake", input_tokens=4, output_tokens=0
        )
    )
    with pytest.raises(LLMError) as error:
        await llm_proposer(provider).propose(document())
    assert error.value.code == "model_unavailable"
    assert error.value.usage[0].failed and error.value.usage[0].input_tokens == 4
