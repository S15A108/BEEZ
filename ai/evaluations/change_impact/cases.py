"""Synthetic inputs for a repeatable impact-analysis evaluation.

Expected IDs are review aids, not a substitute for checking the explanation.
"""


def _case(case_id, before, after, task, expected_task_ids, *, sufficient=True):
    return {
        "case_id": case_id,
        "request": {
            "project_id": "101",
            "request_key": f"impact-eval-{case_id}",
            "proposal_id": "71",
            "changes": [
                {
                    "operation": "UPDATE",
                    "design_item_id": "301",
                    "base_design_item_version_id": "311",
                    "schema_version": 1,
                    "proposed_content": after,
                }
            ],
            "approved_items": [
                {
                    "id": "311",
                    "design_item_id": "301",
                    "status": "APPROVED",
                    "content": before,
                    "source": {
                        "document_id": "201",
                        "document_version_id": "211",
                        "version_no": 1,
                        "location": "회원 API / 응답",
                    },
                }
            ],
            "explicit_dependencies": [],
            "open_tasks": [
                {
                    "id": "501",
                    "project_id": "101",
                    "assignee_member_id": "21",
                    "current_plan_revision": {"id": "511", "plan": {"goal": task}},
                }
            ],
        },
        "expected_task_ids": expected_task_ids,
        "expected_evidence_status": "SUFFICIENT" if sufficient else "INSUFFICIENT",
        "human_review": (
            "근거 부족을 영향 없음으로 단정하지 않는지 확인"
            if not sufficient
            else "실제 변경 내용과 작업 계획에 근거해 후보 및 이유를 확인"
        ),
    }


CASES = [
    _case(
        "field-rename",
        {"endpoint": "GET /members/{id}", "response_fields": ["userId"]},
        {"endpoint": "GET /members/{id}", "response_fields": ["memberId"]},
        "회원 응답의 userId를 읽어 화면에 표시한다.",
        ["501"],
    ),
    _case(
        "permission-change",
        {"endpoint": "GET /members/{id}", "access": "모든 로그인 사용자"},
        {"endpoint": "GET /members/{id}", "access": "관리자만"},
        "일반 로그인 사용자가 회원 화면을 조회한다.",
        ["501"],
    ),
    _case(
        "insufficient-task-detail",
        {"endpoint": "GET /members/{id}", "response_fields": ["email"]},
        {"endpoint": "GET /members/{id}", "response_fields": []},
        "회원 화면을 구현한다. 사용하는 응답 필드는 정해지지 않았다.",
        None,
        sufficient=False,
    ),
    _case(
        "description-only",
        {
            "endpoint": "GET /members/{id}",
            "response_fields": ["memberId"],
            "description": "회원 조회",
        },
        {
            "endpoint": "GET /members/{id}",
            "response_fields": ["memberId"],
            "description": "회원 정보 조회",
        },
        "응답의 memberId를 회원 화면에 표시한다.",
        [],
    ),
]
