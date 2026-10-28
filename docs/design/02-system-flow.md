# BEEZ 시스템 플로우 (초안 v0.2)

핵심 검증·변경 흐름은 넷이다(비비 대화·역할 추천·벡터 검색·텍스트 회의 분석도 MVP지만 이 넷 위에서 동작한다). F2가 핵심 가치이고 데모 대상이다.

## F1. 온보딩 → 기준 설계 v1 발행 (PRJ · PLN · DOC · MEM · AGT)

```mermaid
sequenceDiagram
  autonumber
  actor L as 팀장(사람)
  participant W as 웹
  participant B as backend
  participant A as ai
  actor M as 팀원
  participant C as Claude Code

  L->>W: 프로젝트 생성 (PRJ-01)
  W->>B: POST /api/projects
  B-->>B: project · member(LEADER) · 비비 인스턴스 생성 (BEE-01)
  L->>W: 팀원 초대 · 역할·담당 영역 설정 (MEM)
  M->>W: 초대 수락 후 필수 문서 업로드 (기획안·ERD·요구사항·API) (DOC-01)
  Note over L,M: 활성 사람 팀원 누구나 등록 가능, 이후 본문 편집은 등록자만 가능
  W->>B: POST /api/projects/{id}/documents
  B-->>B: document · document_version(DRAFT) · 영속 분석 접수 저장
  B->>A: POST /v1/document-chunks → /v1/embeddings
  A-->>B: 원문 청크·임베딩·근거 메타
  B-->>B: 청크/manifest 전량 게시 → SOURCE/INDEX READY
  Note over W,A: 자동 질문·설계 후보/답변 기반 생성은 별도 후속. 원문 색인 완료와 구분
  L->>W: 수동 항목 추가·수정·발행 제외 (PLN-07)
  W->>B: POST design-items / PATCH·DELETE human/design-items
  B-->>W: 전체 draft_snapshot_hash와 항목 버전·본문 해시
  loop 문서별 검토·보완 및 승인
    M->>W: 작성 내용 편집·비비 검토 요청 (DOC-A14~A16)
    W->>B: 등록자·revision 확인 후 검토 요청
    Note over B,A: 비비 HTTP 계약은 AI 담당자 협의 중. 현재 BE 연동은 테스트 대역으로 검증
    B->>A: 대상 본문·참고 문서 스냅샷 전달 (연동 초안)
    A-->>B: 보완 이유·수정 제안 (연동 초안)
    M->>W: 제안 반영/넘기기 후 팀장 검토 요청 (DOC-A17·A18)
    W->>B: 제출 본문·검토 고정, 제출 후 편집 잠금
    L->>W: 문서별 승인 또는 사유를 포함한 반려 (DOC-A21)
    W->>B: 제출 결정 요청
    B-->>B: project 행 FOR UPDATE → 문서 버전·제출 결정 저장, baseline 생성 없음
    Note over B: 반려 후 같은 작성 건에서 보완·재검토·새 제출, 이전 이력 보존
    Note over B: 제출·결정 알림은 전진 담당 공통 서비스 연동 TODO
  end
  L->>W: 문서 4종 승인·색인 완료 후 별도 최초 baseline 생성 (PLN-A10)
  W->>B: POST /api/human/baselines (전체 검토 집합·해시)
  B-->>B: project 행 FOR UPDATE → 승인·색인·검토 집합 재확인 → 수동 항목 APPROVED · baseline v1 + baseline_item + baseline_document · current_baseline_id (PLN-09, NFR-04, DOC-08)
  Note over B: 문서별 승인은 이미 완료. baseline 생성은 승인 기록을 다시 변경하지 않음
  Note over B: 개발 기준 조회·원문 검색은 current_baseline_id의 baseline_document 기준
  Note over B: activity_event(BASELINE_PUBLISHED) 연동은 후속 104. 저장 성공과 전달 완료 구분
  M->>W: 에이전트 토큰 발급 (ACC-05)
  W-->>M: MCP 설정값 · CLAUDE.md 템플릿 · 절차 안내 (AGT-04)
  M->>C: claude mcp add --transport http beez https://…/mcp
  C->>B: 연결 테스트 호출 (AGT-05)
  B-->>C: 200 (토큰 → account·project 식별, 감사 기록)
```

## F2. 작업 계획 → 사전 검증 → 시작 → 완료 (TSK · VAL · MCP-10~12) — 핵심

```mermaid
sequenceDiagram
  autonumber
  participant C as Claude Code (팀원 B)
  participant M as backend /mcp
  participant S as 작업·검증 서비스
  participant R as 규칙 검사기
  participant Q as validation_job 큐
  participant Wk as 검증 워커
  participant A as ai /v1/validate
  participant DB as Postgres

  Note over C: 작업 시작 (CLAUDE.md 규칙)
  C->>M: get_development_context(projectId, request, taskId?)
  M->>S: 승인 설계 조회 (taskId 연결 항목 + 의존 항목 + 미확정)
  S->>DB: dev_context_packet(불변) + dev_context_packet_ref(포함한 항목 버전) 저장 · context_access_log
  S-->>C: JSON {packetId, 설계 항목(키·버전), 제약, 미확정, 출처, baseline v}
  Note over C: 계획 수립
  C->>M: upsert_task_plan(taskId?, packetId?, 목표, 범위, 접근, reads[], changes[], unlinked[], noContractChange?, 완료 기준, expectedRevision?)
  M->>S: task · task_plan_revision(n, context_packet_id) · task_plan_design_ref 저장 (충돌 시 409, packetId 없으면 "근거 패킷 없음" 표시)
  S-->>C: {taskId, planRevision n}

  C->>M: validate_task_plan(taskId, planRevision n)
  M->>S: validation 생성 · validation_design_ref(판정에 쓴 항목 버전) · validation_task_ref 저장. 근거 패킷은 리비전 → context_packet_id로 추적 (입력 스냅샷 해시로 설계 대조 결과 재사용)
  S->>R: 동기 규칙 검사
  R->>DB: 필수 칸·항목 ID 확인 · 구조화 계약 대조 · 같은 항목 CHANGES인 열린 작업 조회
  alt 규칙만으로 판정 끝남 (PASS / NEEDS_INPUT / NEEDS_REVIEW)
    R-->>S: 판정 (최초 응답 전 완료)
    S-->>C: {validationId, status}
  else 신규·미연결 내용 → 의미 분석 필요
    S->>Q: enqueue(validationId)
    S-->>C: {validationId, status: RUNNING}
    Wk->>Q: 짧은 트랜잭션: SKIP LOCKED로 집어 LEASED + lease_until 커밋 (잠금 없이 AI 호출)
    Wk->>A: POST /v1/validate {계획, 관련 승인 항목, 열린 작업 계획, 미확정}
    A-->>Wk: {findings[], origin, usage}  (후보만. 규칙 finding을 해제할 수 없음)
    Wk->>DB: validation_finding · validation_run · llm_usage 저장
    Wk->>DB: UPDATE … WHERE status='RUNNING' AND current_attempt=:attempt (늦은 결과·STALE 덮어쓰기 차단)
    C->>M: get_validation(validationId) (폴링)
    M-->>C: {status, findings, 후속 경로}
  end

  alt NEEDS_REVIEW (예: 같은 API를 다른 작업이 다르게 바꾸려 함 — AC-04)
    C->>M: propose_design_change(…)  ⇒ F3으로
  else PASS
    C->>M: report_task_state(taskId, START, validationId)
    M->>S: 한 트랜잭션: project 행 FOR UPDATE → 현재 리비전 PASS → validation_design_ref 버전 현재성 → 선행 완료 → 같은 항목 CHANGES 없음 (VAL-04, AC-05)
    S-->>C: {status: IN_PROGRESS}
    Note over C: 개발 … (필요 시 write_context 로 공유 요약 제출)
    C->>M: report_task_state(taskId, COMPLETED, 계획 대비 변경[])
    M->>S: task_completion_report(불변) 저장 → 델타 있으면 WAITING(REVALIDATION) 후 통과 시 COMPLETED, 없으면 COMPLETED
    S-->>C: {status}
  end
  Note over M,DB: 모든 호출: mcp_audit_log · context_access_log(참조 baseline v) 기록
```

상태 요약

| 개체 | 상태 |
|---|---|
| task | DRAFT → READY(계획 저장) → IN_PROGRESS(PASS 후 START) ↔ WAITING → COMPLETED / CANCELLED. `waiting_reason`: 보완 · 승인 대기 · 선행 작업 · 재검증 (제안, TSK-03) |
| validation | NOT_RUN → RUNNING → PASS / NEEDS_INPUT / NEEDS_REVIEW / ERROR, 어느 상태에서든 → STALE |
| change_proposal | PROPOSED → IN_REVIEW → APPROVED → PUBLISHED, 또는 REJECTED |

PASS 판정표 (D2, 합의 필요)

| 상황 | 결과 | 판정 주체 |
|---|---|---|
| 필수 칸 누락, 모르는 항목 ID, changes·noContractChange 모두 비어 있음 | NEEDS_INPUT | 규칙 |
| 구조화된 계약의 명확한 불일치 | NEEDS_REVIEW | 규칙 (AI가 해제 불가) |
| 같은 항목을 CHANGES하는 열린 작업 | NEEDS_REVIEW | 규칙 |
| 신규·미연결 내용의 의미 해석 필요 | RUNNING → AI 분석 | AI는 finding 후보만 추가 |
| 위에 해당 없음, AI 분석 불필요 또는 완료 | PASS | 규칙 |
| 분석 실패·시간 초과 | ERROR | 규칙 |

복귀 경로

| 상황 | 처리 |
|---|---|
| NEEDS_INPUT | 계획 보완 → 새 리비전 → 재검증 |
| NEEDS_REVIEW | 계획 수정 또는 변경 제안(F3) → 발행 후 재검증 |
| ERROR | 자동 통과 없음. 명시적 재시도 → 새 validation_run |
| START 거부 | 사유 반환(오래된 참조·선행 미완료·겹침) |
| 진행 중 계획 수정 | 새 리비전 → 기존 PASS STALE → WAITING(REVALIDATION), 점유 유지 |
| 보고 끊김 | "상태 확인 필요" 표시만, 팀장이 수동 중단 또는 인계 (AC-11·14) |
| 취소 | 대기 job 취소, 점유 해제 |
| 선행 작업 순환 | 등록 시 거부 |

## F3. 설계 변경 제안 → 승인 → 발행 → 영향 전파 (CHG · APR · NTF)

```mermaid
sequenceDiagram
  autonumber
  participant CB as Claude Code (B)
  participant B as backend
  participant A as ai
  actor R as 관련 담당자 (A)
  actor L as 팀장
  participant CA as Claude Code (A)

  CB->>B: propose_design_change(baseline v3, 항목 API-07 before/after, 사유, taskId, validationId) (MCP-04)
  B-->>B: change_proposal(PROPOSED, base=v3) · change_proposal_item
  B->>A: POST /v1/impact (변경 항목, 명시적 관계, 열린 작업)  (BEE-06·09)
  A-->>B: 영향 후보 · 담당자 후보 · 근거
  B-->>B: 관련자 선정 (task_plan_design_ref 기준, NTF-02) → notification · activity_event  ※ ai 영향 분석보다 먼저, ai 실패와 무관
  R->>B: 의견 등록 / 검토 (MTG-01·02)
  L->>B: 승인 (사람 세션 + 현재 LEADER만) (APR-01)
  B-->>B: project 행 FOR UPDATE → APR-05: 제안 base(v3) == 현재 baseline? 아니면 409 → 제안자가 최신 기준으로 갱신해 재제출
  B-->>B: 같은 트랜잭션: 새 항목·문서 버전 · baseline v4 + baseline_item · baseline_document · proposal PUBLISHED · approval 이력 (APR-03·04, NFR-04·05)
  B-->>B: 같은 트랜잭션에서 검증 영향 서비스 호출: 변경 항목 이전 버전을 validation_design_ref에 가진 검증 STALE · 진행 중 task WAITING(재검증) (VAL-03, D7)
  B-->>B: notification(발행 · 재검증 필요) · activity_event(BASELINE_PUBLISHED)
  Note over CA: 팀원 A의 에이전트, 다음 호출 시
  CA->>B: report_task_state(…) 또는 get_design_changes(sinceVersion=3) (MCP-03, NTF-04)
  B-->>CA: {변경 항목 API-07 v2, 영향: 내 검증 STALE, 조치: 계획 수정 후 재검증}
  CA->>B: upsert_task_plan(…) → validate_task_plan(…)  ⇒ F2 재진입
```

## F4. 에이전트 연결 · 인증 · 감사 (ACC-05 · AGT · MCP-08·09 · NFR-01~03)

```mermaid
sequenceDiagram
  autonumber
  actor U as 팀원
  participant W as 웹
  participant B as backend
  participant C as Claude Code

  U->>W: 프로젝트별 에이전트 토큰 발급 (ACC-05)
  W->>B: POST /api/projects/{id}/agent-tokens
  B-->>B: 무작위 토큰 생성 → 해시만 저장(agent_token.token_hash) · 평문은 1회 표시
  B-->>W: {token(1회), mcp_url, claude_md_snippet}
  U->>C: claude mcp add … (헤더에 토큰)
  loop 모든 MCP 호출
    C->>B: POST /mcp (Bearer token)
    B-->>B: 해시 조회 → revoked_at 없음? → 현재 멤버십 active? → account · project · role 결정, actor=AGENT (AGT-02·03)
    B-->>B: 도구별 권한 + 대상 객체의 project 소속 검사 (MCP-08, AC-10)
    B-->>B: 처리 후 mcp_audit_log (호출자·도구·대상 ID·결과·지연, 인증정보·원문 제외) (MCP-09)
    B-->>B: agent_session.last_report_at 갱신 (MON-07)
  end
  U->>W: 토큰 폐기 (AGT-06)
  W->>B: DELETE /api/agent-tokens/{id} → revoked_at = now()
  C->>B: 이후 호출
  B-->>C: 401 token_revoked
```

## 비기능 흐름 메모

- 검증 재사용: 입력 스냅샷(계획 리비전 + 참조 항목 버전 + 규칙·분석기 버전) 해시가 같으면 설계 대조 결과 재사용(AC-16). 다른 작업과의 겹침은 재요청·START 때 항상 재확인.
- 응답 유실: `report_task_state(START)` 는 같은 `(task_id, validation_id)` 재요청에 같은 결과를 돌려준다(AC-09).
- 큐: `SKIP LOCKED` 는 경쟁 감소일 뿐 정확히 한 번 실행을 보장하지 않는다. lease 만료 회수 + attempt 조건부 반영으로 보완.
- 보고 끊김: `agent_session.last_report_at` 임계값 초과 시 "상태 확인 필요"만. 자동 완료·해제 없음(AC-14).
- 우회 작업: BEEZ를 거치지 않은 작업은 "검증 없음"으로 표시. 차단했다고 표시하지 않음(AC-12).
