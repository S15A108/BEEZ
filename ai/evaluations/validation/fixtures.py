"""Four synthetic examples for the task-plan validation evaluation."""

import json
from copy import deepcopy
from hashlib import sha256


def _request() -> dict:
    source = {
        "document_id": "201",
        "document_version_id": "211",
        "version_no": 1,
        "location": "회원 조회 API / 요청 본문",
    }
    document = (
        "# 회원 조회 API\n"
        "POST /members/lookup\n"
        "요청 JSON: memberId (integer, 필수)\n"
        "응답 JSON: memberId (integer), name (string)\n"
    )
    plan = {
        "title": "회원 정보 조회 API 구현",
        "goal": "승인된 회원 조회 API의 요청과 응답 계약을 구현한다.",
        "included_scope": ["POST /members/lookup 구현"],
        "excluded_scope": ["회원 정보 수정"],
        "approach": "POST /members/lookup 요청 JSON의 memberId를 필수 정수로 받아 회원 정보를 반환한다.",
        "completion_criteria": ["memberId가 포함된 요청에 memberId와 name을 응답한다."],
        "dependency_task_ids": [],
        "design_refs": [
            {
                "design_item_id": "301",
                "design_item_version_id": "311",
                "relation": "IMPLEMENTS",
            }
        ],
        "unlinked": [],
        "no_contract_change": True,
        "context_packet_id": None,
    }
    return {
        "project_id": "101",
        "request_key": "evaluation-184-normal",
        "validation_id": "601",
        "attempt": 1,
        "plan_revision": {
            "id": "511",
            "task_id": "501",
            "revision_no": 1,
            "plan": plan,
            "created_at": "2026-10-29T00:00:00Z",
            "created_by": "11",
        },
        "approved_items": [
            {
                "id": "311",
                "design_item_id": "301",
                "key": "API-001",
                "kind": "API_CONTRACT",
                "version_no": 1,
                "status": "APPROVED",
                "schema_version": 1,
                "content": {
                    "description": "회원 식별자로 회원 정보를 조회한다.",
                    "method": "POST",
                    "path": "/members/lookup",
                    "request_fields": [
                        {
                            "name": "memberId",
                            "location": "body",
                            "type": "integer",
                            "required": True,
                        }
                    ],
                    "responses": [
                        {
                            "status_code": "200",
                            "fields": [
                                {
                                    "name": "memberId",
                                    "location": "body",
                                    "type": "integer",
                                    "required": True,
                                },
                                {
                                    "name": "name",
                                    "location": "body",
                                    "type": "string",
                                    "required": True,
                                },
                            ],
                        }
                    ],
                },
                "source": source,
            }
        ],
        "related_plans": [],
        "rule_findings": [],
        "unresolved": [],
        "checks": [
            {
                "key": "plan-completeness",
                "kind": "PLAN_COMPLETENESS",
                "design_item_version_ids": [],
                "related_plan_revision_ids": [],
            },
            {
                "key": "referenced-design-311",
                "kind": "DESIGN_ALIGNMENT",
                "design_item_version_ids": ["311"],
                "related_plan_revision_ids": [],
            },
        ],
        "evidence": [
            {
                "evidence_id": "e-member-api",
                "design_item_version_id": "311",
                "source": deepcopy(source),
                "locator": {"type": "text_lines", "start_line": 1, "end_line": 4},
                "text": document,
                "text_hash": sha256(document.encode("utf-8")).hexdigest(),
                "normalization_version": "utf8-lf-v1",
            }
        ],
        "execution_budget_ms": 45000,
    }


def fixtures() -> list[dict]:
    normal = _request()
    mismatch = deepcopy(normal)
    mismatch["request_key"] = "evaluation-184-mismatch"
    mismatch["validation_id"] = "602"
    mismatch["plan_revision"]["plan"]["approach"] = (
        "POST /members/lookup 요청 JSON의 userId만 필수 정수로 받는다. "
        "요청 필드는 userId이고 memberId는 받지 않는다."
    )
    mismatch["plan_revision"]["plan"]["completion_criteria"] = [
        "userId 요청에 회원 정보를 응답한다."
    ]
    policy = deepcopy(normal)
    policy["request_key"] = "evaluation-184-policy-unresolved"
    policy["validation_id"] = "603"
    policy["unresolved"] = [
        "회원 정보를 조회할 수 있는 사용자 권한 정책이 아직 결정되지 않았다."
    ]
    policy["plan_revision"]["plan"]["approach"] += (
        " 로그인하지 않은 사용자도 회원 정보를 조회할 수 있도록 구현한다."
    )
    conflict = deepcopy(normal)
    conflict["request_key"] = "evaluation-184-document-conflict"
    conflict["validation_id"] = "604"
    conflict_text = (
        "# 회원 조회 API\n"
        "POST /members/lookup\n"
        "요청 JSON: userId (integer, 필수)\n"
        "응답 JSON: memberId (integer), name (string)\n"
    )
    conflict["evidence"][0]["text"] = conflict_text
    conflict["evidence"][0]["text_hash"] = sha256(
        conflict_text.encode("utf-8")
    ).hexdigest()
    cases = [
        {
            "name": "normal",
            "request": normal,
            "expectation": "COMPLETE, no blocking findings",
        },
        {
            "name": "mismatch",
            "request": mismatch,
            "expectation": "BLOCKING MISMATCH with design version 311 describing userId versus memberId",
        },
        {
            "name": "policy_unresolved",
            "request": policy,
            "expectation": "PARTIAL with EVIDENCE_INSUFFICIENT, not PASS",
        },
        {
            "name": "document_conflict",
            "request": conflict,
            "expectation": "MISMATCH or CONFLICT referencing design version 311 and the supplied contradictory document evidence",
        },
    ]
    for case in cases:
        request = case["request"]
        snapshot = {
            key: request[key]
            for key in (
                "project_id",
                "plan_revision",
                "approved_items",
                "related_plans",
                "rule_findings",
                "unresolved",
                "checks",
                "evidence",
            )
        }
        serialized = json.dumps(
            snapshot, ensure_ascii=False, sort_keys=True, separators=(",", ":")
        )
        request["input_snapshot_hash"] = (
            "sha256:" + sha256(serialized.encode("utf-8")).hexdigest()
        )
    return cases
