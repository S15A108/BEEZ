"""#135 schema invariants: scope, evidence, approved items, result grounding."""

import json

import pytest
from pydantic import ValidationError

from app.schemas.bee_chat import (
    ApprovedDesignItem,
    BeeChatRequest,
    BeeChatResult,
    BeeCitation,
    BeeInstance,
    DesignItemRef,
    ProjectProfile,
    RelatedTask,
    RetrievalScope,
    TaskRef,
    prompt_hash,
)
from app.schemas.documents import SourceLocator

SCOPE = {
    "mode": "APPROVED",
    "document_version_ids": ["311", "312"],
    "baseline_id": "401",
}
PROFILE = "gms-small-1024-v1"


def from_json(model, body):
    """The HTTP boundary: strict models read JSON arrays, not Python lists."""
    return model.model_validate_json(json.dumps(body, ensure_ascii=False))


def evidence(**overrides) -> dict:
    fields = {
        "evidence_id": "ev-1",
        "document_id": "301",
        "document_version_id": "311",
        "version_no": 1,
        "chunk_key": "chk_a",
        "text": "단일 파일 한도는 30 MB이다.",
        "locator": {"type": "text_lines", "start_line": 3, "end_line": 3},
        "embedding_profile_id": PROFILE,
    }
    fields.update(overrides)
    return fields


def item(**overrides) -> dict:
    fields = {
        "id": "1101",
        "design_item_id": "1001",
        "key": "PLN-001",
        "kind": "POLICY",
        "version_no": 1,
        "status": "APPROVED",
        "content": {"description": "발행은 리더만 수행한다."},
    }
    fields.update(overrides)
    return fields


def request(**overrides) -> dict:
    fields = {
        "project_id": "101",
        "request_key": "bee-1",
        "question": "한도는 얼마인가요?",
        "scope": SCOPE,
        "embedding_profile_id": PROFILE,
        "retrieved_evidence": [evidence()],
        "approved_items": [item()],
    }
    fields.update(overrides)
    return fields


def citation(n=1, **overrides) -> BeeCitation:
    fields = {
        "n": n,
        "evidence_id": "ev-1",
        "document_id": "301",
        "document_version_id": "311",
        "version_no": 1,
        "chunk_key": "chk_a",
        "locator": SourceLocator(start_line=3, end_line=3),
        "quote": "30 MB",
    }
    fields.update(overrides)
    return BeeCitation(**fields)


def result(**overrides) -> BeeChatResult:
    fields = {
        "answer_status": "ANSWERED",
        "grounded": True,
        "text": "한도는 30 MB입니다 [1].",
        "citations": (citation(),),
        "scope": from_json(RetrievalScope, SCOPE),
        "embedding_profile_id": PROFILE,
        "evidence_considered": 1,
        "evidence_omitted": 0,
    }
    fields.update(overrides)
    return BeeChatResult(**fields)


def test_scope_requires_a_baseline_only_for_approved():
    with pytest.raises(ValidationError):
        RetrievalScope(mode="APPROVED", document_version_ids=("311",))
    with pytest.raises(ValidationError):
        RetrievalScope(
            mode="DOCUMENT_REVIEW", document_version_ids=("311",), baseline_id="401"
        )
    with pytest.raises(ValidationError):
        RetrievalScope(
            mode="APPROVED", document_version_ids=("311", "311"), baseline_id="401"
        )
    review = RetrievalScope(mode="DOCUMENT_REVIEW", document_version_ids=("311",))
    assert review.baseline_id is None


def test_request_reads_json_and_accepts_a_review_scope_without_a_baseline():
    body = from_json(BeeChatRequest, request())
    assert body.retrieved_evidence[0].evidence_id == "ev-1"
    assert body.max_evidence == 12 and body.history == ()
    review = request(
        scope={"mode": "DOCUMENT_REVIEW", "document_version_ids": ["311"]},
        approved_items=[
            item(
                source={
                    "document_id": "301",
                    "document_version_id": "311",
                    "version_no": 1,
                    "location": "L1-L1",
                }
            )
        ],
    )
    assert from_json(BeeChatRequest, review).scope.mode == "DOCUMENT_REVIEW"


@pytest.mark.parametrize(
    "overrides",
    [
        {"retrieved_evidence": [evidence(document_version_id="999")]},
        {"retrieved_evidence": [evidence(embedding_profile_id="other")]},
        {"retrieved_evidence": [evidence(), evidence(chunk_key="chk_b")]},
        {"retrieved_evidence": [evidence(), evidence(evidence_id="ev-2")]},
        {"retrieved_evidence": [evidence(text="   ")]},
        {"question": "   "},
        {"approved_items": [item(), item(id="1102")]},
        {"approved_items": [item(), item(design_item_id="1002", id="1102")]},
        {"related_tasks": [{"id": "501", "key": "T-1", "status": "READY"}] * 2},
        {"unresolved": ["같은 항목", "같은 항목"]},
        {"instance": {"profile_version": "bee-v2", "project": {"name": "x"}}},
        {"extra": 1},
        # PR #96 P2: an approved item sourced outside the retrieval scope.
        {
            "approved_items": [
                item(
                    source={
                        "document_id": "301",
                        "document_version_id": "999",
                        "version_no": 1,
                        "location": "L1-L1",
                    }
                )
            ]
        },
        # A review scope admits only items sourced inside it.
        {
            "scope": {"mode": "DOCUMENT_REVIEW", "document_version_ids": ["311"]},
            "approved_items": [item()],
        },
    ],
)
def test_request_rejects_inconsistent_inputs(overrides):
    with pytest.raises(ValidationError):
        from_json(BeeChatRequest, request(**overrides))


def test_approved_items_must_be_approved_and_well_formed():
    with pytest.raises(ValidationError):
        from_json(ApprovedDesignItem, item(status="DRAFT"))
    with pytest.raises(ValidationError):
        from_json(
            ApprovedDesignItem,
            item(kind="API_CONTRACT", content={"description": "x", "method": "GET"}),
        )
    approved = from_json(
        ApprovedDesignItem,
        item(
            kind="API_CONTRACT",
            content={
                "description": "x",
                "method": "GET",
                "path": "/api/x",
                "note": None,
            },
        ),
    )
    assert approved.content.extra_fields()["note"] is None


def test_related_task_reads_a_full_task_detail_and_ignores_extras():
    task = from_json(
        RelatedTask,
        {
            "id": "501",
            "key": "TASK-001",
            "project_id": "101",
            "assignee_member_id": "21",
            "status": "READY",
            "lock_version": 1,
            "validation_status": "PASS",
            "current_plan_revision": {
                "id": "511",
                "task_id": "501",
                "revision_no": 1,
                "plan": {
                    "title": "로그인 화면",
                    "goal": "승인 계약대로 연결한다.",
                    "included_scope": ["폼"],
                    "design_refs": [
                        {
                            "design_item_id": "1001",
                            "design_item_version_id": "1101",
                            "relation": "IMPLEMENTS",
                            "proposed_content": None,
                        }
                    ],
                    "context_packet_id": "701",
                },
                "created_at": "2026-10-23T03:00:00Z",
            },
            "created_at": "2026-10-23T03:00:00Z",
        },
    )
    assert task.current_plan_revision.plan.title == "로그인 화면"
    assert task.current_plan_revision.plan.design_refs[0].relation == "IMPLEMENTS"
    with pytest.raises(ValidationError):
        from_json(RelatedTask, {"id": "501", "key": "TASK-001", "status": "OPEN"})


def test_result_grounding_invariants():
    assert result().grounded
    with pytest.raises(ValidationError):
        result(grounded=False)
    with pytest.raises(ValidationError):
        result(text="한도는 30 MB입니다 [2].")
    with pytest.raises(ValidationError):
        result(citations=(citation(n=2),))
    with pytest.raises(ValidationError):
        result(citations=(citation(), citation(n=2)))
    with pytest.raises(ValidationError):
        result(answer_status="INSUFFICIENT_EVIDENCE")
    with pytest.raises(ValidationError):
        result(citations=(citation(document_version_id="999"),))
    ungrounded = result(
        answer_status="INSUFFICIENT_EVIDENCE",
        grounded=False,
        text="찾지 못했어요.",
        citations=(),
    )
    assert not ungrounded.grounded
    with pytest.raises(ValidationError):
        result(
            answer_status="INSUFFICIENT_EVIDENCE",
            grounded=False,
            text="찾지 못했어요.",
            citations=(),
            task_refs=(TaskRef(task_id="501", key="TASK-001", status="READY"),),
        )
    by_task = result(
        citations=(),
        task_refs=(TaskRef(task_id="501", key="TASK-001", status="READY"),),
        text="TASK-001은 READY입니다.",
    )
    assert by_task.grounded and not by_task.citations
    by_item = result(
        citations=(),
        design_refs=(
            DesignItemRef(
                design_item_id="1001",
                design_item_version_id="1101",
                key="PLN-001",
                kind="POLICY",
                version_no=1,
            ),
        ),
        text="발행은 리더만 합니다.",
    )
    assert by_item.grounded and not by_item.citations


def test_history_takes_a_full_answer_but_a_user_turn_only_a_question():
    # PR #96 P2: a 2,243-char answer this route returned must be accepted
    # back as BEE history; a user turn stays within the question bound.
    long_answer = "가" * 2243
    body = from_json(
        BeeChatRequest,
        request(
            history=[
                {"role": "USER", "text": "이전 질문"},
                {"role": "BEE", "text": long_answer},
            ]
        ),
    )
    assert body.history[1].text == long_answer
    with pytest.raises(ValidationError):
        from_json(
            BeeChatRequest, request(history=[{"role": "USER", "text": long_answer}])
        )
    with pytest.raises(ValidationError):
        from_json(
            BeeChatRequest, request(history=[{"role": "BEE", "text": "가" * 10_001}])
        )
    with pytest.raises(ValidationError):
        from_json(BeeChatRequest, request(history=[{"role": "BEE", "text": "  "}]))


def test_instance_hash_must_match_the_prompt():
    fields = {
        "project_id": "101",
        "project": ProjectProfile(name="나래"),
        "persona": "비비",
        "greeting": "안녕하세요.",
        "scope_statement": "문서에서 찾아 답해요.",
        "system_prompt": "x",
    }
    with pytest.raises(ValidationError):
        BeeInstance(**fields, system_prompt_hash="0" * 64)
    instance = BeeInstance(**fields, system_prompt_hash=prompt_hash("x"))
    assert instance.profile_version == "bee-v1"
