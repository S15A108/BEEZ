"""#133 schema invariants: no confirmed state, evidence matches status, typed answers."""

import pytest
from pydantic import ValidationError

from app.schemas.documents import SourceLocator
from app.schemas.planning_questions import (
    ChoiceAnswer,
    ChoiceAnswerFormat,
    DocumentRef,
    EssayAnswer,
    EssayAnswerFormat,
    EvidenceItem,
    PlanningQuestionRequest,
    PlanningQuestionResult,
    QuestionAnswer,
    ReviewCandidate,
    ShortAnswer,
    ShortAnswerFormat,
    SupplementQuestion,
    validate_answer,
)

REF = DocumentRef(document_id="201", document_version_id="211", version_no=1)


def evidence(block="blk_a", quote="보관 기간은 미정이다.", line=3, **overrides):
    fields = {
        "document_id": "201",
        "document_version_id": "211",
        "version_no": 1,
        "block_id": block,
        "locator": SourceLocator(start_line=line, end_line=line),
        "location": f"L{line}-L{line}",
        "quote": quote,
    }
    fields.update(overrides)
    return EvidenceItem(**fields)


def candidate(**overrides):
    fields = {
        "candidate_key": "model-01",
        "kind": "MISSING_CANDIDATE",
        "status": "REVIEW_CANDIDATE",
        "signal": "MODEL_PROPOSAL",
        "summary": "보관 기간이 정해지지 않았습니다.",
        "evidence": (evidence(),),
    }
    fields.update(overrides)
    return ReviewCandidate(**fields)


def question(**overrides):
    fields = {
        "question_key": "q-model-01",
        "candidate_key": "model-01",
        "rank": 1,
        "question_type": "SHORT_ANSWER",
        "text": "보관 기간은 얼마인가요?",
        "answer_format": ShortAnswerFormat(),
        "origin": "MODEL",
        "basis": "REVIEW_CANDIDATE",
    }
    fields.update(overrides)
    return SupplementQuestion(**fields)


def result(**overrides):
    fields = {
        "document": REF,
        "kind": "PLAN",
        "text_hash": "a" * 64,
        "parser_profile": "markdown-v1",
        "max_questions": 20,
        "candidates": (candidate(),),
        "questions": (question(),),
    }
    fields.update(overrides)
    return PlanningQuestionResult(**fields)


def test_candidate_status_has_no_confirmed_value():
    with pytest.raises(ValidationError):
        candidate(status="CONFIRMED")


def test_review_candidate_needs_verified_evidence_and_no_reason():
    with pytest.raises(ValidationError):
        candidate(evidence=())
    with pytest.raises(ValidationError):
        candidate(unverified_reason="NO_EVIDENCE")


def test_unverified_candidate_needs_a_reason_and_may_keep_verified_quotes():
    with pytest.raises(ValidationError):
        candidate(status="UNVERIFIED")
    kept = candidate(status="UNVERIFIED", unverified_reason="QUOTE_NOT_IN_BLOCK")
    assert kept.evidence and kept.unverified_reason == "QUOTE_NOT_IN_BLOCK"
    assert (
        candidate(
            status="UNVERIFIED", unverified_reason="NO_EVIDENCE", evidence=()
        ).evidence
        == ()
    )


def test_conflict_needs_two_distinct_blocks_and_no_duplicate_evidence():
    two = (evidence("blk_a", "30 MB"), evidence("blk_b", "20 MB", line=9))
    assert candidate(kind="CONFLICT", evidence=two).kind == "CONFLICT"
    with pytest.raises(ValidationError):
        candidate(kind="CONFLICT", evidence=(evidence(), evidence(quote="다른 문장")))
    with pytest.raises(ValidationError):
        candidate(evidence=(evidence(), evidence()))


@pytest.mark.parametrize("quote", [" 앞 공백", "뒤 공백 ", "   "])
def test_quote_must_be_a_trimmed_nonblank_substring(quote):
    with pytest.raises(ValidationError):
        evidence(quote=quote)


def test_question_answer_format_must_match_type():
    with pytest.raises(ValidationError):
        question(question_type="ESSAY", answer_format=ShortAnswerFormat())
    assert question(question_type="ESSAY", answer_format=EssayAnswerFormat()).rank == 1


@pytest.mark.parametrize(
    "options",
    [("하나",), ("같다", "같다"), ("", "둘"), tuple(f"o{i}" for i in range(9))],
)
def test_choice_format_needs_two_to_eight_distinct_nonblank_options(options):
    with pytest.raises(ValidationError):
        ChoiceAnswerFormat(options=options)


def choice_question(**overrides):
    fields = {
        "question_type": "CHOICE",
        "answer_format": ChoiceAnswerFormat(options=("둔다", "두지 않는다")),
    }
    fields.update(overrides)
    return question(**fields)


def test_answers_that_fit_their_format_are_accepted():
    validate_answer(choice_question(), ChoiceAnswer(selected=("둔다",)))
    validate_answer(
        choice_question(), ChoiceAnswer(selected=(), other_text="조건부로 둔다")
    )
    multi = choice_question(
        answer_format=ChoiceAnswerFormat(options=("a", "b"), multiple=True)
    )
    validate_answer(multi, ChoiceAnswer(selected=("a", "b")))
    validate_answer(question(), ShortAnswer(text="90일"))
    validate_answer(
        question(question_type="ESSAY", answer_format=EssayAnswerFormat()),
        EssayAnswer(text="90일 보관 후 삭제한다."),
    )


@pytest.mark.parametrize(
    "target, answer",
    [
        (choice_question(), ShortAnswer(text="둔다")),
        (choice_question(), ChoiceAnswer(selected=("없는 선택지",))),
        (choice_question(), ChoiceAnswer(selected=("둔다", "두지 않는다"))),
        (choice_question(), ChoiceAnswer(selected=("둔다", "둔다"))),
        (choice_question(), ChoiceAnswer(selected=())),
        (
            choice_question(
                answer_format=ChoiceAnswerFormat(
                    options=("둔다", "두지 않는다"), allow_other=False
                )
            ),
            ChoiceAnswer(selected=("둔다",), other_text="추가 설명"),
        ),
        (question(), ShortAnswer(text="   ")),
        (
            question(answer_format=ShortAnswerFormat(max_length=3)),
            ShortAnswer(text="네 글자다"),
        ),
    ],
)
def test_answers_that_do_not_fit_are_rejected(target, answer):
    with pytest.raises(ValueError):
        validate_answer(target, answer)


def test_question_answer_is_a_discriminated_union_in_json():
    stored = QuestionAnswer.model_validate_json(
        '{"question_key": "q-model-01", "answer": {"type": "CHOICE", '
        '"selected": ["둔다"]}, "answered_by": "11", '
        '"answered_at": "2026-11-02T03:00:00Z"}'
    )
    assert isinstance(stored.answer, ChoiceAnswer)
    with pytest.raises(ValidationError):
        QuestionAnswer.model_validate_json(
            '{"question_key": "q", "answer": {"type": "CHOICE", "text": "x"}}'
        )


def test_result_questions_are_ranked_in_order_and_mirror_their_candidates():
    with pytest.raises(ValidationError):
        result(questions=(question(rank=2),))
    with pytest.raises(ValidationError):
        result(questions=(question(basis="UNVERIFIED"),))
    with pytest.raises(ValidationError):
        result(questions=(question(candidate_key="missing"),))
    with pytest.raises(ValidationError):
        result(max_questions=1, questions=(question(), question(rank=2)))


def test_result_rejects_foreign_evidence_and_misplaced_truncation():
    foreign = candidate(evidence=(evidence(document_version_id="999"),))
    with pytest.raises(ValidationError):
        result(candidates=(foreign,))
    with pytest.raises(ValidationError):
        result(truncated_candidate_keys=("model-01",))
    extra = candidate(candidate_key="model-02")
    ok = result(candidates=(candidate(), extra), truncated_candidate_keys=("model-02",))
    assert ok.truncated_candidate_keys == ("model-02",)


def test_result_round_trips_through_json():
    original = result()
    assert PlanningQuestionResult.model_validate_json(original.model_dump_json()) == (
        original
    )


def test_request_defaults_and_source_input():
    body = PlanningQuestionRequest.model_validate_json(
        '{"project_id": "101", "request_key": "r", "document": {"document_id": "201",'
        ' "document_version_id": "211", "version_no": 1}, "kind": "PLAN",'
        ' "media_type": "text/markdown", "content": "# 제목\\n", "content_hash": "'
        + "a" * 64
        + '", "text_hash": "'
        + "b" * 64
        + '", "normalization_version": "utf8-lf-v1", "parser_profile": "markdown-v1"}'
    )
    assert body.mode == "MODEL" and body.max_questions == 20
    source = body.to_source_input()
    assert source.document_version_id == "211" and source.kind == "PLAN"
    with pytest.raises(ValidationError):
        PlanningQuestionRequest.model_validate(
            {**body.model_dump(), "max_questions": 51}
        )
