# BEEZ 작업 생명주기 정책 v1.0

> 티켓 S15P31A108-157. 작업(task)의 상태·전이·담당·선행 작업·동시 시작 방지 규칙을 한 곳에 모은다.
> 코드(-156 계획 서비스, -158 상태 전이)는 이 문서를 그대로 구현한다. 바뀌면 문서를 먼저 고친다.
>
> 라벨: **확정** = 요구사항·API 명세·ERD에 이미 있는 것 · **담당자 결정** = 명세가 비워 둔 것을 주관자(변준성)가 정함 · **미결** = 팀 결정 필요(추천안 표기)
>
> 근거 문서: `docs/api/00-common.md` 6절·TaskStateInput, `docs/api/01-backend-rest.md` TSK-A07, `docs/design/03-erd.md` task 절, `backend/.../V2__init_schema.sql`

## 0. 한 줄 요약

작업은 `DRAFT → READY → IN_PROGRESS ⇄ WAITING → COMPLETED` 로 흐르고 어디서든 `CANCELLED` 로 갈 수 있다. **IN_PROGRESS 로 들어가는 문(START·RESUME)만 서버가 잠그고 네 가지를 검사한다.** 작업 상태와 검증 상태는 다른 것이다 — READY 는 "계획이 있다"는 뜻이지 "검증을 통과했다"는 뜻이 아니다.

## 1. 상태 (확정)

| 상태 | 뜻 | DB 가 강제하는 것 |
|---|---|---|
| DRAFT | 작업만 만들고 계획은 없음 | — |
| READY | 계획(리비전)이 1개 이상 있음. 시작 전 | `current_plan_revision_id NOT NULL` |
| IN_PROGRESS | 시작 게이트를 통과해 진행 중 | `started_validation_id NOT NULL` |
| WAITING | 진행 중 멈춤. 사유 필수 | `waiting_reason NOT NULL` (WAITING 일 때만) |
| COMPLETED | 완료 보고 저장됨 | `completed_at`, 불변 `task_completion_report` 1건 이상 |
| CANCELLED | 취소. 이력만 남음 | `cancelled_at` |

철자는 DB CHECK 와 명세 모두 `CANCELLED`(L 두 개)다.

## 2. 전이 표 (확정)

요청 이벤트는 명세 TSK-A07 의 `event` 값 그대로: `START` `RESUME` `WAIT` `COMPLETED` `CANCEL`.

| 현재 \ 이벤트 | START | RESUME | WAIT | COMPLETED | CANCEL |
|---|---|---|---|---|---|
| DRAFT | ✗ | ✗ | ✗ | ✗ | ○ |
| READY | ○ 게이트 | ✗ | ✗ | ✗ | ○ |
| IN_PROGRESS | ◎ 멱등 | ✗ | ○ | ○ | ○ |
| WAITING | ✗ | ○ 게이트 | ✗ | ✗ | ○ |
| COMPLETED | ✗ | ✗ | ✗ | ◎ 멱등 | ✗ |
| CANCELLED | ✗ | ✗ | ✗ | ✗ | ◎ 멱등 |

- ✗ → `400 invalid_transition`
- ◎ 멱등 → 상태를 바꾸지 않고 현재 상태를 200 으로 돌려준다. **멱등 판정은 `expected_lock_version` 비교보다 먼저** 한다(명세 TSK-A07). 재전송된 같은 요청이 409 를 받으면 에이전트가 실패로 오해한다.
- `DRAFT → READY` 는 이벤트가 아니다. 계획 리비전이 처음 저장될 때 서비스가 자동으로 바꾼다.
- `WAITING(REVALIDATION) → COMPLETED` 는 사람·에이전트가 못 한다. 완료 델타 검증이 PASS 하면 서버가 바꾼다(명세 TSK-A07 처리 규칙).

### 2.1 WAIT 사유 (확정)

| 사유 | 누가 설정 | 언제 |
|---|---|---|
| INPUT | 작업자·에이전트 | 계획 보완 입력을 기다림 |
| APPROVAL | 작업자·에이전트 | 설계 변경 승인을 기다림 |
| DEPENDENCY | 작업자·에이전트 | 선행 작업 완료를 기다림 |
| REVALIDATION | **시스템만** | 설계 발행·완료 델타로 재검증이 필요함 |

요청에 `REVALIDATION` 을 넣으면 `400 invalid_transition`. 이 사유로 대기 중인 작업은 RESUME 전에 새 검증이 PASS 여야 하므로, 에이전트가 사유를 바꿔치기해 게이트를 우회하지 못하게 막는다.

## 3. 시작 게이트 (확정 — 설계 D4)

START 와 RESUME 모두 같은 게이트를 지난다. 대기 중에도 설계는 바뀔 수 있기 때문이다(AC-06).

```
1. 멱등 확인: 같은 (task_id, validation_id) 로 이미 시작했으면 그 결과 반환
2. expected_lock_version 비교 → 다르면 409 revision_conflict
3. project 행 SELECT … FOR UPDATE        ← 같은 프로젝트의 START/RESUME 를 한 줄로 세움
4. 네 가지 검사 (전부 수행하고 실패를 모아서 한 번에 응답)
5. 통과 → IN_PROGRESS, started_validation_id 기록, waiting_reason 비움
```

| # | 검사 | 실패 코드 | 근거 |
|---|---|---|---|
| G1 | `validation_id` 가 이 작업의 **현재 리비전**에 대한 검증이고 `status = PASS` | `409 validation_not_passed` | AC-01, MCP-12 |
| G2 | 그 검증의 `validation_design_ref` 가 가진 (항목, 버전)이 **현재 baseline_item** 의 버전과 모두 같음 | `409 version_conflict` | AC-06, D4·D5 |
| G3 | `task_dependency(kind=BLOCKS)` 의 선행 작업이 모두 COMPLETED | `409 dependency_not_completed` | TSK-04 |
| G4 | 이 계획이 CHANGES 하는 항목을, 다른 IN_PROGRESS/WAITING 작업의 현재 리비전도 CHANGES 하지 않음 | `409 task_conflict` (+충돌 작업 키·담당자) | AC-04·05 |

- **전역 baseline 번호는 비교하지 않는다.** 내가 참조하지 않은 항목이 바뀌었다고 내 시작을 막지 않는다. (ERD 검증 시나리오: "API-07 v2 발행 → 무관한 T-3 은 v1 기준 PASS 로 시작 가능")
- READS 끼리는 충돌이 아니다. CHANGES 끼리만 막는다.
- 네 가지를 **모두** 검사하는 이유: 에이전트는 한 번 왕복이 비싸다. 첫 실패에서 멈추면 네 번 고쳐 보내야 할 수 있다.
- 왜 project 행을 잠그나: 두 작업은 서로 다른 task 행이라 task 행 잠금으로는 서로를 막지 못한다. 공통 부모를 잠가야 G4 가 경쟁 상태에서도 맞다. (ERD 검증: "동시 START — A: OK, B: REJECT")

## 4. 담당·선행 작업·설계 연결 규칙

### 4.1 담당자 (확정 + 담당자 결정)

| 규칙 | 라벨 |
|---|---|
| 작업에는 담당 멤버(`assignee_member_id`) 가 있고, 그 멤버는 같은 프로젝트의 활성 멤버여야 한다 (복합 FK) | 확정 |
| 계획 등록·수정, START·RESUME·WAIT·COMPLETED 는 **담당자 본인**(사람 세션 또는 그 사람의 에이전트 토큰)만 한다 | 확정 (TSK-A07) |
| CANCEL 은 담당자 본인, 또는 **사람 세션의 LEADER**. 에이전트 토큰은 LEADER 것이라도 남의 작업을 취소하지 못한다 | 확정 (AC-14, 00-common 2절 "승인은 사람만") |
| 담당자 변경은 LEADER 또는 현재 담당자. 진행 중(IN_PROGRESS·WAITING)에는 변경하면 에이전트 세션과 어긋나므로 WAITING 으로 먼저 내린다 | 담당자 결정 |

### 4.2 선행 작업 (확정 + 담당자 결정)

| 규칙 | 라벨 |
|---|---|
| 선행 관계는 두 곳에 저장한다. `task_plan_revision.dependency_task_ids` = 그 리비전 당시 스냅샷(복원용), `task_dependency` = 현재 관계(게이트용) | 확정 (ERD) |
| 계획을 새로 올리면 `task_dependency` 를 그 계획 내용으로 덮어쓴다 | 담당자 결정 |
| `kind = BLOCKS` 만 G3 에 쓴다. `RELATES` 는 표시용 | 확정 (ERD) |
| 자기 자신은 DB CHECK 가 막고, A→B·B→A 1단계 순환은 서비스가 `400 validation_failed` 로 막는다. 더 깊은 순환 탐지는 미결(§6) | 담당자 결정 |

### 4.3 설계 항목 연결 (확정)

| 규칙 | 근거 |
|---|---|
| 계획은 설계 항목을 `READS` / `IMPLEMENTS` / `CHANGES` 중 하나로 참조하고, **항목당 한 줄**이다 | ERD PK (plan_revision_id, design_item_id) |
| 같은 항목이 여러 관계로 오면 센 것 하나만 남긴다: CHANGES > IMPLEMENTS > READS | ERD §6 제안 → 이 문서로 확정 요청 |
| CHANGES 면 `proposed_content` 필수, 아니면 금지. 서비스가 400 으로 먼저 거르고 DB CHECK 가 최후 방어 | ERD CHECK |
| 참조하는 버전은 계획 작성 당시의 **현재 baseline 버전**이다. 에이전트가 임의 버전을 지정하지 못한다 | TRC-02 |
| **명시적 연결이 우선이고 AI 추론 관계는 후보다.** 후보는 `design_item_dependency.origin = AI_CANDIDATE` 로 따로 저장하며 게이트·무효화 계산에 쓰지 않는다 | D5, 티켓 -157 2번 |
| 항목에 없는 새 내용은 `unlinked` 에 적는다. `unlinked` 도 비고 `no_contract_change` 도 아니면 검증은 NEEDS_INPUT | D2, 00-common 6절 |

## 5. POL-04 책임 분담 (담당자 결정)

| 상황 | 누가 | 어떻게 |
|---|---|---|
| 검증 NEEDS_INPUT (계획이 모자람) | **작업자** | 계획 리비전을 올려 다시 검증 |
| 검증 NEEDS_REVIEW / G4 task_conflict (다른 작업과 겹침) | **관련 담당자들끼리** | 응답에 들어 있는 상대 작업 키·담당자로 조율. 한쪽이 계획을 바꾸거나 WAIT(DEPENDENCY) |
| 계획이 승인 설계와 다름 (CHANGES) | **팀장 승인** | 변경 제안(CHG) → 승인·발행. 발행 전엔 WAIT(APPROVAL) |
| 보고가 끊긴 작업 | **LEADER(사람 세션)** | CANCEL (AC-14). 임계값은 미결 §6 |

AI 는 어느 칸에서도 결정하지 않는다. finding 후보와 담당자 추천까지만 한다.

## 6. 미결 (팀 결정 필요) 과 추천안

| 항목 | 추천안 | 근거 |
|---|---|---|
| 보고 끊김 임계값 (AC-14 "stalled") | `agent_session.last_report_at` 기준 **30분** | 설계 보고서 §5. 검증 lease 와 같은 자릿수 |
| 선행 작업 순환 탐지 깊이 | **1단계**(A↔B)만 서비스에서. 깊은 순환은 UI 경고 | 설계 보고서 §5 DEPENDS_ON 추적 깊이와 동일 |
| 진행 중 담당자 변경 | 금지. WAITING 으로 내린 뒤 변경 | 에이전트 세션과 어긋남 방지 |
| "센 관계 하나" 규칙 | 위 4.3 대로 | ERD §6 에서 팀 동의 대기 중 |
| CANCEL 후 재개 | 불가. 새 작업으로 | 이력 단순화. 필요하면 "복제" 기능으로 |

## 7. 이 문서가 코드에 들어가는 자리

| 내용 | 클래스 (예정) | 테스트 |
|---|---|---|
| §1 상태·사유 | `task.entity.TaskStatus`, `WaitingReason` (enum, DB 는 STRING) | 엔티티-DDL 일치 (`ddl-auto=validate`) |
| §2 전이 표 | `task.service.TaskTransitionPolicy` — 허용/멱등/금지 결과 반환, 예외 안 던짐 | 6×5 전수 단위 테스트 |
| §3 게이트 | `task.service.StartGate` — project 잠금 후 G1~G4 | Testcontainers: 동시 START 2 스레드, PASS 없음, 선행 미완료, 설계 변경 후 |
| §4.3 참조 정규화 | `task.service.TaskPlanService` | 단위 |
| §5 권한 | 각 서비스 첫 줄 `ProjectAccessChecker` | 비멤버 403, 비담당자 403, 에이전트 CANCEL 403 |

## 변경 이력

- v1.0 (2026-10-26) 최초 작성. ERD v1.1·API 명세 v0.3 기준.
