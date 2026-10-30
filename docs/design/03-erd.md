# BEEZ ERD v1.1 (최종본)

> ![핵심 관계](erd-core.png)
>
> 이 설계는 PostgreSQL + pgvector에서 DDL과 불변식 시나리오 21개로 검증했다. DDL은 `backend/src/main/resources/db/migration/V2__init_schema.sql`에 있다. 머지된 마이그레이션 파일은 수정하지 않고, 구조 변경은 새 번호(V3__...) 파일로 추가한다.

> 2026-10-28 / 194 추가: [문서별 검토·승인 구조](../api/01-backend-rest.md#document-review-workflow)는 V6에 추가한다. 문서별 승인본과 개발 기준 baseline을 구분하며, 아래 V2 도식은 신규 검토 테이블을 포함하지 않는다. 현재 개발 기준은 계속 project.current_baseline_id로 정한다. 문서 관리 화면은 별도 승인된 문서 버전을 조회할 수 있고, 해당 승인이 baseline 생성을 뜻하지 않는다.

### V6 추가 관계

| 테이블 | 참조·역할 |
| --- | --- |
| document_working_copy | document당 1개, owner account/초기 version/기준 version, 내용·revision·편집 상태 |
| document_bee_review_request | document/account/request_key 실행 멱등성, 완료·실패·usage |
| document_bee_review | working copy의 입력 revision/참고 스냅샷·검토 결과·usage |
| document_bee_suggestion | review별 원문/대체문/설명과 작성자의 처리 선택 |
| document_review_submission | document와 정확한 version/review를 연결하는 불변 제출. review/document 복합 FK, document/revision UNIQUE, 대기 제출 1개 |
| document_original_cleanup | 참조가 바뀐 작성 파일의 영속 정리 큐. 제출 원본 참조는 삭제하지 않음 |

문서별 승인에서는 document_version과 submission의 결정을 한 트랜잭션으로 저장한다. 최초 baseline 생성에서는 이 승인 기록을 고치지 않고 baseline 참조·수동 항목 승인·현재 기준 포인터를 한 트랜잭션으로 저장한다. 미제출 working copy와 제출 본문은 별도이며, 제출 후 DB 트리거로 본문 변경을 막는다.

<a id="design-relation-history"></a>

### V9 설계 의존관계 상태·이력 (S15P31A108-167)

| 테이블 | 참조·역할 |
| --- | --- |
| design_item_dependency (칸 추가) | `status`(EXPLICIT: CONFIRMED·NEEDS_RECHECK·RELEASED / AI_CANDIDATE: CANDIDATE·REJECTED), `reason`. 물리 삭제 대신 해제·거절 상태. CONFIRMED 는 확인자·시각 필수 |
| design_item_dependency_history | 쌍 단위 변경 한 줄씩 (불변). 유효 기준·양 끝 항목 버전·근거·확인자·변경 제안·관계 집합 리비전. 확정·유지는 기준과 버전 필수 |
| design_relation_set | 프로젝트별 관계 집합 리비전. 확정 관계 집합이 바뀌는 호출마다 1 증가, 행 없음 = 0 |

관계 확정·이력·조회는 변준성 담당이다 (#40 합의: 진입점 오서현, 저장 서비스 변준성). 진입점은 발행·변경 승인 트랜잭션 안에서 [관계 저장 서비스](09-relation-service-contract.md)를 한 번 부르고, 실패하면 함께 롤백된다. 판정·무효화는 D5 그대로 직접 참조만 쓰고 이 관계를 따라가지 않는다.

<a id="document-review-storage"></a>

### 문서 검토 저장·원본 보존

V6은 working copy, 비비 실행/결과/제안, 제출, 작성 원본 정리 큐를 추가한다. 모델 사용량은 기존 llm_usage를 재사용한다. 문서 검토 알림은 전진 담당 공통 서비스 연동 TODO이며 문서 도메인은 notification에 직접 저장하지 않는다. 알림 대상 타입·중복 키·전달 보장은 담당자 계약 확정 후 반영한다. 195의 change_proposal 리비전 컬럼을 미리 넣지 않는다. 미병합 194의 V6에 통합하며 기존 V2~V5 migration은 변경하지 않는다.

파일 원본은 private object storage에 보관한다. 직접 편집/제안 반영의 제출 파일은 정규화 UTF-8 본문으로 생성한다. 파일 재업로드 후 내용 편집이 없으면 재업로드한 원본 bytes를 보존한다. content_hash는 원본 bytes, text_hash는 정규화 본문을 나타낸다. 파일 PUT은 트랜잭션 밖, DB 참조·제출·멱등 응답은 한 트랜잭션이다. 실패 시 새 원본은 기존 보상 규칙으로 정리한다.

더 이상 참조하지 않는 작성 파일은 영속 cleanup 큐에 넣는다. 정리 worker는 문서 버전/working copy 참조를 확인한 후 트랜잭션 밖에서 삭제한다. 제출·반려·승인 이력의 원본은 유지한다. 정리 실패는 재시도하며 `worker-enabled` 설정을 따른다.

첫 제출에서 내부 v1의 내용을 고정할 때 색인 processing을 먼저 잠그고 attempt/lease를 교체한다. 이전 작업의 늦은 청크는 활성 결과가 되지 않는다. 새 본문의 색인이 완료되기 전에는 baseline을 생성할 수 없다.

## 0. v0.2 → v1.0 에서 바뀐 것

| 리뷰 지적 | 결정 |
|---|---|
| `baseline_item`에 같은 항목 두 버전이 들어갈 수 있음 | PK `(baseline_id, design_item_id)` + 버전이 그 항목 소속인지 복합 FK + 프로젝트 일치 FK |
| 기준 설계에 문서 버전이 없음 (DOC-08) | `baseline_document` |
| `task_design_link`가 task에만 묶여 계획 수정 전후가 섞임 | **`task_plan_design_ref`로 교체**: 계획 리비전 × 항목 × 버전 × 관계. 현재 점유는 `task.current_plan_revision_id`로 따라간다 |
| 같은 항목에 여러 관계 | **항목당 한 행, 가장 강한 관계 하나** (CHANGES > IMPLEMENTS > READS). 무효화는 세 관계 모두 대상이라 정보 손실이 없다 |
| 검증이 대조한 다른 작업 리비전이 없음 | `validation_task_ref` (관련 작업 + 그 계획 리비전) |
| 검증이 본 설계 버전 | `validation_design_ref` (D4·D5의 기준) |
| 완료 델타가 계획 리비전을 수정 | `task_completion_report` (불변) |
| `design_relation` 다형 FK | **`design_item_dependency`** (항목 ↔ 항목, 실제 FK). 작업 연결은 계획 참조, 문서 연결은 버전의 출처 컬럼 |
| 표시 키 전역 UK | `(project_id, key)` UK |
| 보완 질문·답변 저장 위치 없음 | `planning_question` |
| 회의 원문·참여 상태 없음 | `meeting_participant`, `meeting_entry`. MTG-01~03은 `change_comment` 재사용 |
| 컨텍스트 패킷 보존 (TRC-03) | `get_development_context` 호출 시 `dev_context_packet`(불변) + `dev_context_packet_ref` 저장. **계획 리비전이 패킷을 참조** (`task_plan_revision.context_packet_id`, NULL = 근거 패킷 없음). 검증은 판정 기준을 `validation_design_ref`에 따로 남기고, 근거 패킷은 리비전을 통해 추적 |
| `context_record` 구조 (find/read/write_context) | 수정 = 새 리비전 행(불변). `UNIQUE(previous_id)`로 같은 리비전을 두 사람이 동시에 고치면 두 번째가 거부(409). 참조 버전은 `context_record_ref`로 저장해 조회 시 현재 기준과 비교("과거 버전" 표시) |
| 최초 발행의 문서 스냅샷 | v1 발행에도 `baseline_document` 생성 (02 F1) |
| 워커 실행 소유권 | `validation_job.lease_token`, `validation_run.lease_token`, `validation.current_attempt` (fencing) |
| current 포인터 이중 관리 | **`design_item.current_approved_version_id`, `document.current_approved_version_id` 삭제.** 현재 버전은 `project.current_baseline_id` → `baseline_item` / `baseline_document` 조인으로만 구한다. `task.baseline_id`, `task.latest_validation_id`도 삭제 |
| 단순 FK는 소속을 보장하지 않음 | `(id, parent_id)` 복합 FK로 소유 관계 보장 (아래 4절) |
| 불변성이 문장뿐 | 트리거로 UPDATE 차단 (승인 항목 버전 본문, 기준 설계, 계획 리비전·참조, 검증 참조, 승인, 완료 보고, 패킷) |

## 0-1. v1.1 — API 명세와 이름 맞춤

API 명세(`docs/api`)와 다르게 나온 컬럼 이름·값을 명세 쪽에 맞췄다. 테이블 수(43)와 관계는 그대로이고, 불변식 시나리오 21개는 다시 돌려 모두 통과했다.

| 테이블 | 바뀐 것 |
|---|---|
| `task` | `waiting_reason` = `INPUT`, `APPROVAL`, `DEPENDENCY`, `REVALIDATION`(시스템만 설정) |
| `task_plan_revision` | `title` 필수, `included_scope`·`excluded_scope`·`dependency_task_ids` 스냅샷 추가, `acceptance` → `completion_criteria`, `created_by_account_id` 필수(에이전트 작성이면 토큰 소유 계정) + `created_by_token_id` 선택 |
| `validation_finding` | `source`(`RULE`·`MODEL`), `kind`(`MISMATCH`·`MISSING_INPUT`·`MISSING_POLICY`·`CONFLICT`·`STALE_REF`·`ERROR`), `severity`(`INFO`·`WARNING`·`BLOCKING`), `design_item_version_id`, `sources`, `resolution`(`FIX_PLAN`·`PROPOSE_CHANGE`·`HUMAN_REVIEW`·`RETRY`), `next_action` |
| `validation_finding` (V14, -159) | `check_key`(의미 분석 후보가 속한 검사 키, 규칙 finding 은 NULL), `evidence_ids`(요청 evidence 참조 키 배열, 기본 `[]`). 큐 조회 인덱스 `ix_job_ready`(QUEUED 의 next_run_at)와 이력 인덱스 `ix_validation_task` 는 V2 에 있는 것을 쓴다 |
| `task_completion_report` | `summary`, `completion_checks` 추가 |
| `llm_usage` | `request_key` 유니크, `tokens_known`(모르면 토큰 수 NULL), `estimated_cost_amount`+`currency`, `failed`, `feature` 값 = `DOCUMENT_PARSE`·`TASK_VALIDATION`·`CHANGE_IMPACT`·`EMBEDDING`·`BEE_CHAT`·`BEE_DRAFT`·`TASK_ASSIGNMENT`·`MEETING_ANALYSIS` |

작업 상태·전이·시작 게이트 규칙은 [04-task-lifecycle.md](04-task-lifecycle.md) 에 따로 정리했다.

## 1. 테이블과 소유자 (44개)

| 묶음 | 테이블 | 소유 |
|---|---|---|
| A. 계정·프로젝트·에이전트 | account, project, project_member, invitation, agent_token, agent_session | 전진 |
| B. 문서·설계·기준 설계 | document, document_version, design_item, design_item_version, baseline, baseline_item, baseline_document, planning_question | 오서현 |
| B. 설계 의존관계·이력 | design_item_dependency, design_item_dependency_history (V9), design_relation_set (V9) | 변준성 (진입점은 오서현, #40 합의) |
| B. 수동 항목·최초 발행 명령 | design_http_command (V6, 프로젝트·사용자·동작·요청 키 복합 PK, fingerprint·응답·24시간 만료) | 오서현 |
| B. 변경·승인·협의 | change_proposal, change_proposal_item, change_comment, change_comment_target, approval, meeting, meeting_participant, meeting_entry | 오서현 |
| C. 작업·계획·검증 | task, task_plan_revision, task_plan_design_ref, task_dependency, task_completion_report, validation, validation_design_ref, validation_task_ref, validation_run, validation_job, validation_finding | 변준성 |
| C'. 개발 컨텍스트 | dev_context_packet, dev_context_packet_ref, context_record, context_record_ref, document_chunk | 박상은 (MCP-05~07 도구 계약은 전진 주관) |
| C'. 조회 이력·비용 | context_access_log, llm_usage | 변준성 (llm_usage 기록은 각 AI 기능) |
| D. 운영 공통 | notification, activity_event, mcp_audit_log | 전진 |

2026-10-27 담당 보완: 위 C' 중 원문 RAG의 document_chunk DB 저장/벡터 SQL 검색과 run/manifest·실행 상태 BE 구현은 사용자 지정으로 **오서현**이 맡는다. 박상은은 청킹·임베딩·검색 전략/컨텍스트·답변 AI를 맡고, 나머지 개발 컨텍스트/MCP 담당은 기존 구분을 따른다. 이 보완은 스키마 변경이나 신규 migration 적용을 뜻하지 않는다.

공통: `id BIGINT GENERATED ALWAYS AS IDENTITY`, `created_at`·`updated_at TIMESTAMPTZ`, enum은 TEXT + CHECK, 구조화 데이터는 JSONB, 물리 삭제 없음. 연결 테이블(`baseline_item`, `baseline_document`, `design_item_dependency`, `task_plan_design_ref`, `task_dependency`, `validation_design_ref`, `validation_task_ref`, `dev_context_packet_ref`, `context_record_ref`, `meeting_participant`)은 복합 PK 예외.

## 2. 핵심 관계 — 설계 · 계획 · 검증

```mermaid
erDiagram
  project ||--o{ design_item : ""
  project |o--|| baseline : "current_baseline_id (유일한 현재 기준)"
  design_item ||--o{ design_item_version : "불변 버전"
  baseline ||--o{ baseline_item : "항목 스냅샷"
  baseline ||--o{ baseline_document : "문서 스냅샷"
  design_item_version ||--o{ baseline_item : ""
  document_version ||--o{ baseline_document : ""
  design_item ||--o{ design_item_dependency : "from / to"
  design_item ||--o{ design_item_dependency_history : "from / to (V9, 불변)"
  project ||--o| design_relation_set : "관계 집합 리비전 (V9)"

  task ||--o{ task_plan_revision : "불변 리비전"
  task |o--|| task_plan_revision : "current_plan_revision_id"
  task_plan_revision ||--o{ task_plan_design_ref : "항목당 1행"
  design_item_version ||--o{ task_plan_design_ref : "계획이 기준 삼은 버전"
  task ||--o{ task_completion_report : "자기 보고"

  task_plan_revision ||--o{ validation : "입력"
  validation ||--o{ validation_design_ref : "본 항목 버전"
  validation ||--o{ validation_task_ref : "대조한 다른 작업 리비전"
  design_item_version ||--o{ validation_design_ref : ""
  task_plan_revision ||--o{ validation_task_ref : ""
  dev_context_packet |o--o{ task_plan_revision : "계획의 근거 패킷"
  validation ||--o| validation_job : "큐"
  validation ||--o{ validation_run : "시도"
  validation ||--o{ validation_finding : ""
  validation_run ||--o{ llm_usage : "시도별 비용"

  baseline {
    bigint id PK
    bigint project_id FK
    int version_no "UK(project_id, version_no)"
    bigint change_proposal_id FK "v1은 NULL"
  }
  baseline_item {
    bigint baseline_id PK
    bigint design_item_id PK "기준 설계당 항목 1개"
    bigint design_item_version_id FK "그 항목의 버전 (복합 FK)"
  }
  baseline_document {
    bigint baseline_id PK
    bigint document_id PK
    bigint document_version_id FK "그 문서의 버전 (복합 FK)"
  }
  design_item {
    bigint id PK
    bigint project_id FK
    text key "UK(project_id, key)"
    text kind "REQUIREMENT, API_CONTRACT, DATA_CONTRACT, POLICY, CONSTRAINT"
  }
  design_item_version {
    bigint id PK
    bigint design_item_id FK
    int version_no "UK(design_item_id, version_no)"
    text status "DRAFT, APPROVED, SUPERSEDED, REJECTED"
    int schema_version
    jsonb content "구조화 본문, 승인 후 불변"
    bigint source_document_version_id FK
  }
  task {
    bigint id PK
    bigint project_id FK
    text key "UK(project_id, key)"
    text status "DRAFT, READY, IN_PROGRESS, WAITING, COMPLETED, CANCELLED"
    text waiting_reason "WAITING일 때만"
    bigint current_plan_revision_id FK "자기 리비전만 (복합 FK)"
    bigint started_validation_id FK "자기 검증만 (복합 FK)"
    int lock_version
  }
  task_plan_revision {
    bigint id PK
    bigint task_id FK
    int revision_no "UK(task_id, revision_no)"
    jsonb unlinked "신규, 미연결 내용"
    boolean no_contract_change "명시적 선언"
    bigint context_packet_id FK "NULL = 근거 패킷 없음"
    text input_hash
  }
  task_plan_design_ref {
    bigint plan_revision_id PK
    bigint design_item_id PK
    bigint design_item_version_id FK
    text relation "READS, IMPLEMENTS, CHANGES"
    jsonb proposed_content "CHANGES일 때만"
  }
  validation {
    bigint id PK
    bigint task_id FK
    bigint plan_revision_id FK "그 작업의 리비전 (복합 FK)"
    bigint baseline_id FK "당시 기준, 이력용"
    text status "NOT_RUN, RUNNING, PASS, NEEDS_INPUT, NEEDS_REVIEW, STALE, ERROR"
    text input_snapshot_hash "설계 대조 재사용 키"
    int current_attempt "늦은 결과 차단"
  }
  validation_design_ref {
    bigint validation_id PK
    bigint design_item_id PK
    bigint design_item_version_id FK
    text relation "READS, IMPLEMENTS, CHANGES, DEPENDENCY"
  }
  validation_task_ref {
    bigint validation_id PK
    bigint related_task_id PK
    bigint related_plan_revision_id FK
  }
  validation_job {
    bigint id PK
    bigint validation_id FK "UK"
    text status "QUEUED, LEASED, DONE, FAILED, CANCELLED"
    uuid lease_token
    timestamptz lease_until
  }
  validation_run {
    bigint id PK
    bigint validation_id FK
    int attempt "UK(validation_id, attempt)"
    uuid lease_token
    text kind "RULE, MODEL"
    text status "RUNNING, OK, TIMEOUT, ERROR, DISCARDED"
  }
  task_completion_report {
    bigint id PK
    bigint task_id FK
    bigint plan_revision_id FK
    jsonb delta
    bigint delta_validation_id FK
  }
```

## 3. 나머지 관계

```mermaid
erDiagram
  account ||--o{ project_member : ""
  project ||--o{ project_member : "활성 LEADER 1명"
  project_member ||--o{ agent_token : "멤버만 발급"
  agent_token ||--o{ agent_session : ""
  project ||--o{ invitation : ""

  project ||--o{ document : ""
  document ||--o{ document_version : ""
  document_version ||--o{ document_chunk : "임베딩"
  project ||--o{ planning_question : "보완 질문"

  project ||--o{ change_proposal : ""
  baseline ||--o{ change_proposal : "base_baseline_id"
  change_proposal ||--o{ change_proposal_item : "CREATE, UPDATE, RETIRE"
  change_proposal ||--o{ change_comment : "의견, 검토 요청, 결정 (proposal_revision_no = 보고 쓴 제출 리비전)"
  change_comment ||--o{ change_comment_target : "검토 요청 대상·관련 멤버(계정)"
  change_proposal ||--o{ change_proposal_revision : "불변 제출 리비전"
  change_proposal_revision ||--o{ change_comment : "의견이 귀속된 리비전"
  change_proposal_revision ||--o| approval : "리비전당 결정 1건"
  change_proposal_revision ||--o{ change_proposal_document : "기준/제안 문서 버전"
  change_proposal |o--o{ meeting : ""
  meeting ||--o{ meeting_participant : ""
  meeting ||--o{ meeting_entry : "분석 대상 텍스트"

  project ||--o{ dev_context_packet : ""
  dev_context_packet ||--o{ dev_context_packet_ref : "포함한 항목 버전"
  project ||--o{ context_record : "공유 작업 기록"
  context_record ||--o{ context_record_ref : "참조 항목 버전"
  dev_context_packet |o--o{ context_record : "작성 당시 패킷"
  agent_token ||--o{ context_access_log : ""
  account ||--o{ context_access_log : "호출 계정 (V5)"
  project ||--o{ notification : ""
  project ||--o{ activity_event : ""
  agent_token ||--o{ mcp_audit_log : ""

  dev_context_packet {
    bigint id PK
    bigint project_id FK
    bigint task_id FK "선택"
    bigint agent_token_id FK
    text request "자연어 개발 요청"
    bigint baseline_id FK "조회 당시 기준"
    jsonb content "항목, 제약, 미확정, 출처"
    text content_hash
  }
  dev_context_packet_ref {
    bigint packet_id PK
    bigint design_item_id PK
    bigint design_item_version_id FK
  }
  context_record {
    bigint id PK
    bigint project_id FK
    bigint root_id FK "첫 리비전 id"
    bigint previous_id FK "UK, 동시 수정 거부"
    int revision_no
    bigint task_id FK
    bigint plan_revision_id FK "작성 당시 계획"
    bigint source_packet_id FK "작성 당시 패킷"
    bigint author_account_id FK
    bigint author_token_id FK "NULL = 웹에서 작성"
    text kind "DECISION, PROGRESS, ISSUE, HANDOVER"
    text visibility "SHARED, PRIVATE"
    text summary "find 대상"
    text detail
    vector embedding
  }
  context_record_ref {
    bigint record_id PK
    bigint design_item_id PK
    bigint design_item_version_id FK
  }
```

![나머지 관계](erd-others.png)

## 4. 소유 관계를 DB가 보장하는 방식

단순 FK는 "그 id가 존재한다"까지만 확인한다. 아래는 `(id, parent_id)` 유니크 + 복합 FK로 "그 부모의 것"까지 보장한다.

| 참조 | 보장 |
|---|---|
| `project.current_baseline_id` | 같은 프로젝트의 기준 설계 |
| `baseline_item.design_item_version_id` | 그 행의 `design_item_id` 소속 버전, 기준 설계와 같은 프로젝트 |
| `baseline_document.document_version_id` | 그 문서의 버전 |
| `task.current_plan_revision_id`, `task.started_validation_id` | 그 작업의 리비전·검증 |
| `task.assignee_member_id`, `task_dependency` | 같은 프로젝트 |
| `agent_token (project_id, account_id)` | 그 프로젝트의 멤버 |
| `validation.plan_revision_id`, `task_completion_report` | 그 작업의 리비전 |
| `*_ref.design_item_version_id` | 그 행의 항목 소속 버전 |
| `change_proposal.base_baseline_id`, `published_baseline_id` | 같은 프로젝트 |

순환 참조(`project ↔ baseline`, `task ↔ task_plan_revision`, `task ↔ validation`, `baseline ↔ change_proposal`)는 `DEFERRABLE INITIALLY DEFERRED`라 같은 트랜잭션 안에서 순서 없이 넣을 수 있다.

## 5. 불변식과 검증 결과

PostgreSQL 16.15 + pgvector에서 실행한 검증 결과 (2026-10-22).

| 불변식 | 구현 | 결과 |
|---|---|---|
| 기준 설계당 항목 1버전 | PK `(baseline_id, design_item_id)` | 거부됨 ✓ |
| 다른 항목·다른 프로젝트의 버전을 스냅샷에 넣을 수 없음 | 복합 FK | 거부됨 ✓ |
| 승인 버전 본문 불변, 상태는 APPROVED→SUPERSEDED만 | 트리거 | 거부됨 ✓ |
| 표시 키는 프로젝트 내 유니크 | `(project_id, key)` UK | 거부됨 ✓ |
| 활성 팀장 1명 | 부분 유니크 인덱스 | 거부됨 ✓ |
| 비멤버는 토큰 없음 | 복합 FK | 거부됨 ✓ |
| 계획 리비전 불변 | 트리거 | 거부됨 ✓ |
| 다른 작업의 리비전을 current로 지정 불가 | 복합 FK (deferred) | 거부됨 ✓ |
| 자기 자신 선행 불가 | CHECK | 거부됨 ✓ |
| CHANGES면 변경 내용 필수 | CHECK | 거부됨 ✓ |
| 시작 검증 없이 IN_PROGRESS 불가 | CHECK | 거부됨 ✓ |
| 이전 attempt·STALE 이후 늦은 결과 | 조건부 UPDATE | 0행 반영 ✓ |
| 같은 항목 CHANGES 두 작업 동시 START (D4) | `project` 행 FOR UPDATE + 겹침 검사 | A 성공, B는 A 커밋까지 대기 후 거부 ✓ |
| 같은 START 재요청 | 멱등 처리 | 같은 결과 ✓ |
| 무관한 설계 변경 후 시작 (D4·D5 충돌 해결) | `validation_design_ref` 버전 비교 | 이전 기준 PASS로 시작 성공 ✓ |
| 변경 항목을 CHANGES한 진행 중 작업 | 발행 트랜잭션에서 STALE + WAITING | WAITING(REVALIDATION) ✓ |
| 변경 항목을 READS한 작업 | 같은 경로 | 시작 전이면 시작 거부, 진행 중이면 WAITING ✓ |
| 최초 발행에 문서 스냅샷 포함 | 발행 트랜잭션에서 `baseline_document` 생성 | v1 문서 스냅샷 생성 ✓ |
| 패킷 불변, 계획 리비전이 패킷 참조 | 트리거 · FK | 수정 거부, 연결 확인 ✓ |
| `context_record` 같은 리비전 동시 수정 | `UNIQUE(previous_id)` | 두 번째 거부 ✓ |
| `context_record` 리비전 불변, 최신·과거 버전 판별 | 트리거 · 후속 없는 행 · 현재 기준 비교 | 수정 거부, "과거 버전" 표시 ✓ |

`try_start`, `publish_change` 테스트 함수는 서비스 로직을 SQL로 옮긴 **프로토타입**이다. 실제 구현은 Java 서비스에 두고, 이 시나리오를 Testcontainers 통합 테스트로 옮긴다.

## 6. 남은 결정

### V10 변경 제안 제출·반려 후 재제출

195는 기존 V2~V6을 수정하지 않고 `V10__change_proposal_revisions.sql`로 추가한다. V7~V9는 선행 PR을 위한 번호이며 병합·운영 적용 순서를 확인해야 한다. 이 번호 선택은 다른 PR의 최종 번호를 변경하거나 운영 적용을 허가하는 의미가 아니다.

| 저장 대상 | 계약 |
| --- | --- |
| change_proposal | title·revision_no 추가. 최초 PROPOSED, 제출 후 잠금. REJECTED에서 같은 ID/key로 다음 리비전을 제출. WITHDRAWN·withdrawn_by/at·previous_proposal_id 추가, 거둔 제안은 변경 불가. 이전 제안은 같은 프로젝트·작성 계정의 WITHDRAWN만 연결 |
| change_proposal_revision | PK(proposal, revision). 같은 프로젝트 제안·기준 baseline FK. 입력·해석된 원문·제출자/토큰·해시·시각 저장. UPDATE/DELETE 금지 |
| change_proposal_item | 최신 제출의 항목 투영. schema_version 추가. 과거 항목은 불변 revision.snapshot으로 조회. 188은 이 명시적 스키마 버전을 읽어야 하며 현재 값 추측 금지 |
| change_proposal_document | 리비전별 문서 기준/제안 version 연결. 프로젝트·문서 소속 복합 FK. 모든 문서 변경은 proposed_document_version_id를 가진다(NOT NULL). after_content 입력은 서버가 정규화해 새 DRAFT 문서 버전과 색인 작업(document_processing)으로 만들고 snapshot에도 보존한다 |
| document_version.change_proposal_id | 업로드 DRAFT의 제안 귀속. 연결 후 본문·해시·파일·부모·등록자·귀속 변경 금지. 과거 리비전에서 사용한 연결도 유지 |
| approval | proposal_revision_no 추가, UNIQUE(proposal, revision), 제출 리비전 FK. 기존 제안당 UNIQUE 제거. 기존 행 이관용 DEFAULT 1은 채운 뒤 제거해 새 결정은 검토한 리비전을 반드시 지정한다. 기존 결정 UPDATE 금지는 유지 |
| design_http_command | CHG-A01/A04/A15의 24시간 응답 보존 재사용. 프로젝트/계정/동작/사람 또는 토큰으로 범위 구분 |

기존 제안은 당시 확인 가능한 데이터로 legacy 리비전 1을 보존한다. 과거에 저장하지 않았던 proposed schema_version·문서 본문을 추측해서 채우지 않는다. legacy 해시는 PostgreSQL JSONB 직렬화 바이트로 고정하고, 새 제출 해시는 키 정렬 canonical JSON의 SHA-256으로 생성한다. 클라이언트는 반환된 해시를 그대로 사용한다.

실제 명령은 project → 현재 멤버/계정 → 에이전트 토큰 순서로 잠근다. 생성·재제출·리비전·현재 항목 투영·문서 연결·멱등 응답은 한 트랜잭션이다. 외부 파일/AI 호출은 없다. 현재 baseline과 기준 버전을 확인하며 제출만으로 설계·작업 상태를 바꾸지 않는다. 최종 심사 API와 발행은 198에서 구현한다.

원문이 변경돼도 기준은 제출 당시 버전이다. [CHG-A03](../api/01-backend-rest.md#chg-a03)의 diff는 기준→제안이며 현재 승인본 차이는 별도 표시한다. 반려 이력은 리비전별 결정에서 조회한다. 거두기는 승인/반려 approval로 저장하지 않으며 제안 행에 작성 계정·시각을 남기고 원문·revision/hash를 유지한다. 이전 제안 연결은 복합 프로젝트 FK와 INSERT 트리거로 검사한다. #197의 규칙/AI 후보·관계 집합 revision·발행별 분석 상태는 담당 계약 확정 후 별도 migration으로 추가하며 V10에 선반영하지 않는다.

194 보완: 수동 항목 5종 content v1·DRAFT 편집·REJECTED 제외·전체 스냅샷·문서 4종 색인 후 최초 발행은 [확정 계약](../api/01-backend-rest.md#pln-a10)을 따른다. V6 design_http_command는 등록/최초 발행의 사용자·프로젝트·동작별 멱등 응답을 저장한다. 기존 V2~V5는 수정하지 않는다.

- 수동 항목 content v1은 194에서 확정. 자동 생성·확장 스키마는 오서현·박상은 검토
- 임베딩 차원 N — 지금은 `vector(1024)` 임시값
- `design_item_dependency`를 무효화에서 몇 단계까지 따라갈지 → #40 합의: 판정·무효화는 D5 직접 참조만, 변경 영향 후보는 확정 관계 1단계 + 작업 계획 참조
- 계획 참조의 "가장 강한 관계 하나" 규칙에 팀 동의
- 문서 파일 저장소: 2026-10-27 SeaweedFS S3로 통일. DB storage_key 유지, 로컬도 Docker SeaweedFS 사용. 실제 배포 환경/영속 볼륨은 인프라 적용 필요.
