"""Synthetic scenarios with separate initial and fresh post-refinement holdouts."""

import hashlib


def case(name, split, expectation, plan, policies, *, other_evidence=None):
    items, evidence = [], []
    for index, (description, source_line) in enumerate(policies):
        item_id, version_id = str(301 + index), str(311 + index)
        source = {
            "document_id": str(201 + index),
            "document_version_id": str(211 + index),
            "version_no": 1,
            "location": "1~1행",
        }
        items.append(
            {
                "id": version_id,
                "design_item_id": item_id,
                "key": f"POL-{index + 1}",
                "kind": "POLICY",
                "version_no": 1,
                "status": "APPROVED",
                "schema_version": 1,
                "content": {"description": description},
                "source": source,
            }
        )
        evidence.append(
            {
                "evidence_id": f"policy-source-{index + 1}",
                "design_item_version_id": version_id,
                "source": source,
                "locator": {"type": "text_lines", "start_line": 1, "end_line": 1},
                "text": source_line + "\n",
                "text_hash": hashlib.sha256((source_line + "\n").encode()).hexdigest(),
                "normalization_version": "utf8-lf-v1",
            }
        )
    if other_evidence:
        evidence.append(
            {
                "evidence_id": "unmapped-document",
                "source": {
                    "document_id": "209",
                    "document_version_id": "219",
                    "version_no": 1,
                    "location": "1~1행",
                },
                "locator": {"type": "text_lines", "start_line": 1, "end_line": 1},
                "text": other_evidence + "\n",
                "text_hash": hashlib.sha256(
                    (other_evidence + "\n").encode()
                ).hexdigest(),
                "normalization_version": "utf8-lf-v1",
            }
        )
    checks = [
        {
            "key": "plan-completeness",
            "kind": "PLAN_COMPLETENESS",
            "design_item_version_ids": [],
            "related_plan_revision_ids": [],
        }
    ]
    checks += [
        {
            "key": f"candidate-design-{item['id']}",
            "kind": "DESIGN_ALIGNMENT",
            "design_item_version_ids": [item["id"]],
            "related_plan_revision_ids": [],
        }
        for item in items
    ]
    checks.append(
        {
            "key": "policy-gap-v1",
            "kind": "POLICY_ALIGNMENT",
            "design_item_version_ids": [item["id"] for item in items],
            "related_plan_revision_ids": [],
        }
    )
    return {
        "name": name,
        "split": split,
        "expectation": expectation,
        "request": {
            "project_id": "101",
            "request_key": "policy-eval",
            "validation_id": "601",
            "attempt": 1,
            "plan_revision": {
                "id": "511",
                "task_id": "501",
                "revision_no": 1,
                "created_by": "11",
                "created_at": "2026-10-26T03:00:00Z",
                "plan": {
                    "title": "합성 작업",
                    "goal": plan,
                    "included_scope": [plan],
                    "excluded_scope": [],
                    "approach": plan,
                    "completion_criteria": [plan],
                    "dependency_task_ids": [],
                    "design_refs": [],
                    "unlinked": [],
                    "no_contract_change": True,
                },
            },
            "approved_items": items,
            "related_plans": [],
            "rule_findings": [],
            "unresolved": [],
            "input_snapshot_hash": "sha256:synthetic-policy-eval",
            "checks": checks,
            "evidence": evidence,
            "execution_budget_ms": 60000,
        },
    }


def fixtures():
    return [
        case(
            "login_supported",
            "development",
            "SUPPORTED",
            "로그인 5회 실패하면 계정을 30분 잠근다.",
            [
                (
                    "로그인 5회 실패하면 계정을 30분 잠근다.",
                    "로그인 잠금 정책: 5회 실패 시 30분 잠금",
                )
            ],
        ),
        case(
            "upload_undecided",
            "development",
            "EXPLICITLY_UNDECIDED",
            "업로드 파일의 보관 기간 정책을 적용한다.",
            [("업로드 파일 보관 기간 정책을 결정한다.", "업로드 보관 정책: 미정")],
        ),
        case(
            "login_mismatch",
            "development",
            "CONTRADICTORY",
            "로그인 10회 실패하면 계정을 30분 잠근다.",
            [
                (
                    "로그인 5회 실패하면 계정을 30분 잠근다.",
                    "로그인 잠금 정책: 5회 실패 시 30분 잠금",
                )
            ],
        ),
        case(
            "two_designs_conflict",
            "development",
            "CONFLICT",
            "로그인 실패 시 계정 잠금 정책을 적용한다.",
            [
                (
                    "로그인 5회 실패하면 계정을 30분 잠근다.",
                    "로그인 잠금 정책: 5회 실패 시 30분 잠금",
                ),
                (
                    "로그인 3회 실패하면 계정을 10분 잠근다.",
                    "로그인 잠금 정책: 3회 실패 시 10분 잠금",
                ),
            ],
        ),
        case(
            "missing_search",
            "development",
            "INSUFFICIENT",
            "관리자가 보고서를 삭제할 수 있는 권한 정책을 적용한다.",
            [],
        ),
        case(
            "unrelated_document",
            "development",
            "INSUFFICIENT",
            "알림 재전송 간격 정책을 적용한다.",
            [("프로필 사진은 PNG 형식이다.", "프로필 사진 정책: PNG 형식")],
            other_evidence="알림 화면 색상은 파란색이다.",
        ),
        case(
            "refund_supported",
            "holdout",
            "SUPPORTED",
            "구매 후 7일 이내이고 미사용인 주문만 환불한다.",
            [
                (
                    "미사용 주문은 구매 후 7일 이내에만 환불한다.",
                    "환불 정책: 구매 후 7일 이내 미사용 주문만 환불",
                )
            ],
        ),
        case(
            "retention_mismatch",
            "holdout",
            "CONTRADICTORY",
            "접속 로그를 7일 보관하고 삭제한다.",
            [("접속 로그는 30일 보관한 뒤 삭제한다.", "접속 로그 보관 정책: 30일")],
        ),
        case(
            "export_undecided",
            "holdout",
            "EXPLICITLY_UNDECIDED",
            "데이터 내보내기 권한 정책을 적용한다.",
            [("데이터 내보내기 권한은 결정이 필요하다.", "내보내기 권한 정책: 미확정")],
        ),
        case(
            "quota_supported",
            "holdout",
            "SUPPORTED",
            "사용자별 하루 요청을 100건으로 제한한다.",
            [
                (
                    "사용자별 하루 요청은 최대 100건이다.",
                    "요청 제한 정책: 사용자별 하루 100건",
                )
            ],
        ),
        case(
            "documentation_only",
            "holdout",
            "NO_REQUIREMENTS",
            "README 맞춤법과 띄어쓰기만 수정한다. 기능, 권한, 데이터 처리는 변경하지 않는다.",
            [],
        ),
        case(
            "mixed_incomplete",
            "holdout",
            "INSUFFICIENT",
            "파일 업로드는 10MB 이하만 허용하고 업로드 파일 보관 기간 정책도 적용한다.",
            [("파일은 최대 10MB까지 업로드할 수 있다.", "업로드 크기 정책: 최대 10MB")],
        ),
        # Added after the first two runs to retain an unseen set after prompt refinement.
        case(
            "fresh_password",
            "fresh_holdout",
            "SUPPORTED",
            "비밀번호는 최소 12자로 설정한다.",
            [("비밀번호는 최소 12자여야 한다.", "비밀번호 길이 정책: 최소 12자")],
        ),
        case(
            "fresh_expiry",
            "fresh_holdout",
            "CONTRADICTORY",
            "임시 초대 링크를 3시간 뒤 만료시킨다.",
            [
                (
                    "임시 초대 링크는 발급 후 1시간 뒤 만료된다.",
                    "초대 링크 만료 정책: 1시간",
                )
            ],
        ),
        case(
            "fresh_payment",
            "fresh_holdout",
            "EXPLICITLY_UNDECIDED",
            "결제 실패 시 재시도 정책을 적용한다.",
            [("결제 실패 시 재시도 여부를 결정해야 한다.", "결제 재시도 정책: 미결정")],
        ),
        case(
            "fresh_returns_conflict",
            "fresh_holdout",
            "CONFLICT",
            "구매 상품의 반품 가능 기간 정책을 적용한다.",
            [
                ("상품 반품은 구매 후 14일 이내 가능하다.", "반품 기간 정책: 14일"),
                ("상품 반품은 구매 후 30일 이내 가능하다.", "반품 기간 정책: 30일"),
            ],
        ),
        case(
            "fresh_notification",
            "fresh_holdout",
            "INSUFFICIENT",
            "알림 수신 거부 기준 정책을 적용한다.",
            [],
        ),
        case(
            "fresh_comment_only",
            "fresh_holdout",
            "NO_REQUIREMENTS",
            "코드 주석의 맞춤법만 고친다. 실행 결과와 권한 및 저장 방식은 변경하지 않는다.",
            [],
        ),
    ]
