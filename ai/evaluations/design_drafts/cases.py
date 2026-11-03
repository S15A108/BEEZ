"""Scripted answers, existing items and draft proposals over the plan document (#134).

The questions come from the #133 pipeline run on `evaluations/data/plan.md`
with its scripted fake provider, so the answers below attach to real question
keys and evidence. The draft proposal mimics a plausible model output and
deliberately includes an unchanged restatement, a fabricated quote and an
unsupported item so the review shows how each is handled. No model is called.
"""

from collections.abc import Callable

from app.schemas.planning_questions import PlanningQuestionRequest
from app.services.planning_questions import generate_planning_questions
from evaluations.planning_questions.cases import PROJECT_ID, document_entry
from evaluations.planning_questions.cases import request_body as planning_request_body
from evaluations.planning_questions.run import (
    ScriptedProposer as ScriptedQuestionProposer,
)

DOCUMENT = "plan.md"

# Answers keyed by question key (rule questions) or question text (model
# questions); both are stable across runs of the #133 scripted pipeline.
ANSWERS = {
    "'활성 프로젝트 리더'는 어떤 기준으로 판단하나요?": {
        "type": "CHOICE",
        "selected": ["계정 활성과 프로젝트 멤버십 활성이 모두 필요하다"],
    },
    "정식 운영 담당자는 누가, 언제 정하나요?": {
        "type": "SHORT_ANSWER",
        "text": "시범 운영 평가 회의에서 프로젝트 리더가 정한다.",
    },
    "q-rule-undecided-L7-1": {
        "type": "ESSAY",
        "text": "모바일 전용 애플리케이션은 첫 출시 범위에서 제외하고 2027년 상반기 이후 검토한다.",
    },
    "q-rule-undecided-L29-1": {"type": "ESSAY", "text": "미정"},
}
DISMISSED = {"개인정보 보관 기간과 삭제 절차를 어떻게 정할 계획인가요?"}

EXISTING_ITEMS = [
    {
        "design_item_version_id": "1101",
        "design_item_id": "1001",
        "key": "PLN-001",
        "kind": "POLICY",
        "version_no": 1,
        "status": "APPROVED",
        "schema_version": 1,
        "content": {"description": "문서의 최종 발행은 프로젝트 리더만 수행한다."},
        "source": {
            "document_id": "301",
            "document_version_id": "311",
            "version_no": 1,
            "location": "나래 협업 도구 기획서 / 발행 권한 / L11-L11",
        },
    },
    {
        "design_item_version_id": "1102",
        "design_item_id": "1002",
        "key": "PLN-002",
        "kind": "REQUIREMENT",
        "version_no": 1,
        "status": "DRAFT",
        "schema_version": 1,
        "content": {
            "description": "미승인 초안은 접근 권한이 있는 멤버의 문서 검토 모드에서만 조회한다."
        },
    },
    {
        "design_item_version_id": "1103",
        "design_item_id": "1003",
        "key": "PLN-003",
        "kind": "CONSTRAINT",
        "version_no": 1,
        "status": "DRAFT",
        "schema_version": 1,
        "content": {"description": "첫 출시는 웹 브라우저를 대상으로 한다."},
    },
]

# Sources use line anchors; answer_refs use question keys or question text.
DRAFT_PROPOSAL = {
    "items": [
        {
            "kind": "POLICY",
            "content": {
                "description": "문서의 최종 발행은 계정이 활성 상태이고 프로젝트 멤버십도 활성인 프로젝트 리더만 수행한다."
            },
            "base_design_item_version_id": "1101",
            "sources": [
                {
                    "line": 11,
                    "quote": "문서의 최종 발행은 활성 프로젝트 리더만 수행한다.",
                },
                {
                    "line": 13,
                    "quote": "리더도 비활성 계정이거나 프로젝트에서 탈퇴했다면 발행할 수 없다.",
                },
            ],
            "answer_refs": ["'활성 프로젝트 리더'는 어떤 기준으로 판단하나요?"],
            "rationale": "원문의 '활성' 기준이 모호했고 답변이 계정 활성과 멤버십 활성을 모두 요구한다고 확정했습니다.",
        },
        {
            "kind": "CONSTRAINT",
            "content": {
                "description": "첫 출시는 웹 브라우저를 대상으로 하며 모바일 전용 애플리케이션은 첫 출시 범위에서 제외한다(2027년 상반기 이후 검토)."
            },
            "base_design_item_version_id": "1103",
            "sources": [
                {
                    "line": 7,
                    "quote": "첫 출시는 웹 브라우저를 대상으로 하며 모바일 전용 애플리케이션 개발 일정은 정하지 않았다.",
                }
            ],
            "answer_refs": ["q-rule-undecided-L7-1"],
            "rationale": "답변이 모바일 앱을 첫 출시 범위에서 제외한다고 밝혀 기존 초안에 범위를 더했습니다.",
        },
        {
            "kind": "REQUIREMENT",
            "content": {
                "description": "정식 운영 담당자는 시범 운영 평가 회의에서 프로젝트 리더가 정한다."
            },
            "base_design_item_version_id": None,
            "sources": [
                {"line": 27, "quote": "| 정식 운영 | 미정 | 시범 운영 평가 후 협의 |"}
            ],
            "answer_refs": ["정식 운영 담당자는 누가, 언제 정하나요?"],
            "rationale": "표의 '미정' 담당을 답변이 채웠습니다.",
        },
        {
            "kind": "POLICY",
            "content": {
                "description": "검토 모드의 결과에는 대상 버전을 표시하며 이를 현재 정책으로 소개하지 않는다."
            },
            "base_design_item_version_id": None,
            "sources": [
                {
                    "line": 19,
                    "quote": "검토 모드의 결과에는 대상 버전을 표시하며, 이를 현재 정책이라고 소개하지 않는다.",
                }
            ],
            "answer_refs": [],
            "rationale": "원문에 명시된 정책을 항목으로 옮겼습니다.",
        },
        {
            # Unchanged restatement of an existing draft: dropped, not a change.
            "kind": "REQUIREMENT",
            "content": {
                "description": "미승인 초안은 접근 권한이 있는 멤버의 문서 검토 모드에서만 조회한다."
            },
            "base_design_item_version_id": "1102",
            "sources": [
                {
                    "line": 17,
                    "quote": "미승인 초안은 접근 권한이 있는 멤버의 문서 검토 모드에서만 조회한다.",
                }
            ],
            "answer_refs": [],
            "rationale": "기존 항목과 같습니다.",
        },
        {
            # The quote is fabricated: the source is dropped, the answer remains.
            "kind": "REQUIREMENT",
            "content": {"description": "정식 운영 날짜는 아직 정하지 않았다."},
            "base_design_item_version_id": None,
            "sources": [{"line": 29, "quote": "정식 운영 날짜는 2027년 1월이다."}],
            "answer_refs": ["q-rule-undecided-L29-1"],
            "rationale": "답변도 미정이라 날짜를 확정하지 않았습니다.",
        },
        {
            # Nothing in the document or the answers backs this item.
            "kind": "CONSTRAINT",
            "content": {"description": "서비스 응답 시간은 1초 이내여야 한다."},
            "base_design_item_version_id": None,
            "sources": [],
            "answer_refs": [],
            "rationale": "일반적인 성능 기준을 제안합니다.",
        },
    ],
    "unresolved": ["유료 요금제 가격은 답변에서도 미정으로 남았다."],
}


def resolve_ref(reference: str, questions: list[dict]) -> str:
    """A reference is a question key (q-…) or the exact question text."""
    if reference.startswith("q-"):
        return reference
    for question in questions:
        if question["text"] == reference:
            return question["question_key"]
    raise KeyError(reference)


async def planning_questions() -> list[dict]:
    """Run the #133 pipeline with its scripted provider; return stored questions."""
    body = planning_request_body(DOCUMENT)
    request = PlanningQuestionRequest.model_validate(body)
    outcome = await generate_planning_questions(
        request, ScriptedQuestionProposer(DOCUMENT)
    )
    result = outcome.result
    evidence = {c.candidate_key: c.evidence for c in result.candidates}
    stored = []
    for question in result.questions:
        key = question.question_key
        answer = ANSWERS.get(key) or ANSWERS.get(question.text)
        if answer is not None:
            status = "ANSWERED"
        elif question.text in DISMISSED:
            status = "DISMISSED"
        else:
            status = "OPEN"
        stored.append(
            {
                "question": question.model_dump(mode="json"),
                "evidence": [
                    item.model_dump(mode="json")
                    for item in evidence[question.candidate_key]
                ],
                "status": status,
                "answer": None
                if answer is None
                else {
                    "question_key": key,
                    "answer": answer,
                    "answered_by": "11",
                    "answered_at": "2026-11-03T03:00:00Z",
                },
            }
        )
    return stored


async def build_request(*, max_items: int = 50) -> dict:
    """The #134 request for the plan document, as the BE would send it."""
    entry = document_entry(DOCUMENT)
    body = planning_request_body(DOCUMENT)
    return {
        "project_id": PROJECT_ID,
        "request_key": f"design-drafts-{entry['document_version_id']}",
        "document": body["document"],
        "kind": body["kind"],
        "media_type": body["media_type"],
        "content": body["content"],
        "content_hash": body["content_hash"],
        "text_hash": body["text_hash"],
        "normalization_version": body["normalization_version"],
        "parser_profile": body["parser_profile"],
        "existing_items": EXISTING_ITEMS,
        "questions": await planning_questions(),
        "max_items": max_items,
    }


def scripted_draft(
    block_for_line: Callable[[int], str], question_key_for: Callable[[str], str]
) -> dict:
    """The raw JSON a model would return, with anchors turned into real ids."""
    items = []
    for item in DRAFT_PROPOSAL["items"]:
        items.append(
            {
                **item,
                "sources": [
                    {"block_id": block_for_line(s["line"]), "quote": s["quote"]}
                    for s in item["sources"]
                ],
                "answer_refs": [question_key_for(ref) for ref in item["answer_refs"]],
            }
        )
    return {"items": items, "unresolved": list(DRAFT_PROPOSAL["unresolved"])}
