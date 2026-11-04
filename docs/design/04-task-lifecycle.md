# BEEZ 작업 생명주기 정책 v1.5

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

## 2. 전이

### 2.1 요청 전이 표 (확정)

사람·에이전트가 TSK-A07 로 보내는 이벤트. 값은 명세 `event` 그대로: `START` `RESUME` `WAIT` `COMPLETED` `CANCEL`.

| 현재 \ 이벤트 | START | RESUME | WAIT | COMPLETED | CANCEL |
|---|---|---|---|---|---|
| DRAFT | ✗ | ✗ | ✗ | ✗ | ○ |
| READY | ○ 게이트 | ✗ | ✗ | ✗ | ○ |
| IN_PROGRESS | ◎ 멱등 | ✗ | ○ | ○ ※ | ○ |
| WAITING | ✗ | ○ 게이트 | ✗ | ✗ | ○ |
| COMPLETED | ✗ | ✗ | ✗ | ◎ 멱등 | ✗ |
| CANCELLED | ✗ | ✗ | ✗ | ✗ | ◎ 멱등 |

- ✗ → `400 invalid_transition`
- ◎ 멱등 → 상태를 바꾸지 않고 현재 상태를 200 으로 돌려준다. **멱등 판정은 `expected_lock_version` 비교보다 먼저** 한다(명세 TSK-A07). 재전송된 같은 요청이 409 를 받으면 에이전트가 실패로 오해한다.
  - IN_PROGRESS + START 의 멱등은 **`started_validation_id` 가 요청 값과 같을 때만**이다(명세 "기존 START 결과 반환", 00-common 5절). 다른 검증으로 보낸 START 는 409 `revision_conflict`.
  - RESUME 에는 상태 기반 멱등이 없다. RESUME 재전송은 `Idempotency-Key`(00-common 5절)가 처리한다.
- ※ IN_PROGRESS + COMPLETED 는 결과가 둘이다 (TSK-A07 처리 규칙·Response).
  - `delta` 없음 → **COMPLETED**, `200`
  - `delta` 있음 → **WAITING(REVALIDATION)**, `202`. trigger=DELTA 검증을 접수하고 응답에 `delta_validation_id` 를 넣는다 (검증 접수는 S15P31A108-159)
- `DRAFT → READY` 는 이벤트가 아니다. 계획 리비전이 처음 저장될 때 서비스가 자동으로 바꾼다.

### 2.2 시스템 전이 (확정)

요청 이벤트가 아니라 **서버가** 바꾸는 전이. 6×5 표에는 없으므로 테스트를 따로 둔다.

| # | 계기 | 대상 | 결과 | 점유 | 근거 |
|---|---|---|---|---|---|
| S1 | 설계 발행 — 작업이 참조한 항목의 이전 버전이 바뀜 | **IN_PROGRESS 만** | **WAITING(REVALIDATION)**, 관련 검증 STALE. 바뀐 항목과 WAITING 으로 바꿀 작업은 get_design_changes(-164)의 `DesignChangeQueries` 와 **같은 쿼리**로 뽑는다. STALE 대상은 REST CHG 발행 규칙대로 `validation_design_ref` 에서 직접 찾는다(작업 상태·현재 계획으로 거르지 않음). 폐기 항목은 새 baseline_item 에서 **뺀다**(발행 -194 계약; G2 도 스냅샷 비교만 함) | 유지 | REST CHG 발행 처리 규칙("IN_PROGRESS 작업만"), 00-common 6절, D5 |
| S2 | 진행 중 계획 변경 (TSK-A04) — **검증 입력이 바뀐** 변경만 (§2.5, AC-13) | **IN_PROGRESS 만** | **WAITING(REVALIDATION)**, 이전 검증 STALE(`PLAN_CHANGED`). 제목·접근 방식·패킷만 바뀐 규칙 또는 가짜 검증은 유지해 새 리비전으로 **이어 받는다**. 실제 검색을 사용한 검증은 검색어 또는 패킷이 바뀌면 무효화한다. delta 완료 보고의 DELTA 를 기다리는 동안은 어느 쪽이든 `400 invalid_transition` | 유지 | REST TSK-A04 처리 규칙("진행 중 계획 변경"), §2.5 |
| S3 | delta 있는 COMPLETED 보고 | IN_PROGRESS | **WAITING(REVALIDATION)**, `202`. 순서: DELTA 검증을 **먼저** 접수 → 그 ID 를 넣어 완료 보고 저장 (`task_completion_report` 는 UPDATE 금지라 나중에 채울 수 없음) | 유지 | REST TSK-A07, ERD task_completion_report |
| S4 | DELTA 검증이 PASS | 작업이 **아직** WAITING(REVALIDATION) 이고, 현재 계획에 delta 완료 보고가 있고, 이 검증이 현재 계획의 **가장 최근 DELTA 검증**일 때만. (완료 보고의 `delta_validation_id` 와 같은지로 보지 않는 이유: 설계 발행으로 DELTA 가 STALE 이 되면 서버가 새 DELTA 를 접수하는데(S1·§2.3) 완료 보고는 UPDATE 금지라 그 번호를 새로 적을 수 없다. -163 구현) | **COMPLETED** | 해제 | REST TSK-A07 |
| S5 | 담당자 인계 — 팀장의 담당자 변경(TSK-A06) · 팀원 제외(MEM-A09) · 나가기(MEM-A10)의 열린 작업 인계 (-212) · 미배정 작업을 팀원이 스스로 맡기(TSK-A06, 수정안 요청 · 11-04 사용자 결정) | **IN_PROGRESS** 는 상태까지, **WAITING** 은 검증만 | IN_PROGRESS 는 **WAITING(REVALIDATION)**. 점유 중(IN_PROGRESS·WAITING) 작업의 DELTA 를 뺀 검증은 도는 것(NOT_RUN·RUNNING)까지 STALE(`ASSIGNEE_CHANGED`), 대기 job 취소. DELTA 검증은 담당자와 무관해 그대로(PASS 면 S4). 같은 담당자로 바꾸면 아무것도 하지 않는다 | 유지 | REST TSK-A06·MEM-A09·A10 처리 규칙, §6 |

검증 쪽 사건(늦은 결과·입력 변경·리스 만료·실패·RESUME)까지 합친 무효화·롤백 전체 표와 기록 위치·수동 조치는 [11 검증 롤백](11-validation-rollback.md)에 있다.

IN_PROGRESS 가 아닌 작업은 S1·S2 에서 **상태를 바꾸지 않고 검증만 STALE** 로 둔다.
- READY: START 때 G1·G2 가 걸러낸다.
- WAITING(INPUT·APPROVAL·DEPENDENCY): 원래 사유를 덮어쓰지 않는다. RESUME 때 G1(새 리비전엔 PASS 없음)·G2(참조 버전 바뀜)가 걸러낸다.
- WAITING(REVALIDATION): 이미 재검증 대기라 그대로.

### 2.5 계획 변경과 검증 최신성 (AC-13 결정 — -162 구현, 변준성)

검증은 계획 **리비전**에 묶여 있고(G1·TaskDetail.validation_status·현황 집계·NTF-04 유효성 모두 "현재 리비전의 가장 최근 검증"), 계획을 바꾸면 늘 새 리비전이 생긴다. 그래서 "검증 입력에 영향 없는 변경은 검증을 유지한다"(AC-13)를 다음처럼 정한다.

- **검증 입력** = `task_plan_revision.input_hash` 에 들어가는 것: 목표, 포함·제외 범위, 완료 기준, 선행 작업, 계약 변경 없음·미연결 표시, 설계 참조(항목·버전·관계·**CHANGES 의 제안 내용**). **제목·접근 방식·컨텍스트 패킷은 이 계획 해시에 넣지 않는다.** 다만 AI 검색에는 제목·접근 방식과 패킷이 쓰이므로, 검색을 사용한 완료 검증은 제목·접근 방식·패킷이 바뀌면 이어 받지 않는다. 검색·분석 도중 계획이나 관련 계획의 리비전이 바뀌면 기존 검색 결과는 폐기한다. 검증의 `input_snapshot_hash` 도 리비전 번호가 아니라 이 계획 입력 해시(관련 작업의 계획도 그 입력 해시)로 만든다 — 같은 입력이면 리비전이 달라도 같은 값(AC-16 재사용 -161 의 키).
- 계획 변경 때 새 리비전의 입력 해시를 **직전 리비전**과 비교한다. 직전 리비전을 못 읽으면 "바뀌지 않았다" 로 보지 않고 바뀐 것으로 다룬다(조회 실패 ≠ 변경 없음).
  - **같다**: 아무것도 무효화하지 않고 상태도 두며, 직전 리비전의 (DELTA 를 뺀) 가장 최근 검증이 끝난 것(PASS·NEEDS_INPUT·NEEDS_REVIEW)이면 새 리비전에 **같은 내용으로 한 줄 더** 넣는다(참조·관련 계획·발견 항목까지, `summary.carried_from` = 원본 번호, 실행 기록은 복사하지 않음). 에이전트가 쥔 옛 번호로 START·RESUME 하면 G1 이 현재 리비전의 그 복사본으로 판정한다(§3). 가장 최근 것이 **RUNNING** 이면 두고, 그 결과가 반영될 때 같은 기준(입력 해시)으로 통과하면 그때 이어 받는다. STALE·ERROR 면 이어 받을 것이 없다.
  - **다르다**: 전처럼 S2(IN_PROGRESS → WAITING(REVALIDATION), 점유 중 작업의 끝난 검증 STALE `PLAN_CHANGED`). READY 의 옛 PASS 는 두고 G1 이 리비전으로 거른다.
- delta 완료 보고의 DELTA 검증이 **아직 도는 동안**(현재 계획의 가장 최근 DELTA 가 NOT_RUN·RUNNING)은 입력이 같아도 계획을 바꿀 수 없다(`400 invalid_transition`). 그 결과가 PASS 면 S4 가 현재 계획의 DELTA 로 완료시키는데, 그사이 계획이 바뀌면 완료가 조용히 사라진다. 결과를 기다리거나 RESUME(출구 ①)으로 보고를 거둔다.
- 결과가 **난 뒤**(NEEDS_INPUT·NEEDS_REVIEW·ERROR, 또 가짜 분석기를 끈 서버라 완료되지 않은 PASS)는 계획을 바꿀 수 있다 — §2.3 출구 ② 다. 설계 발행(S1)으로 시작 때 PASS 가 STALE 이 되고 재접수된 DELTA 가 NEEDS_INPUT 으로 끝난 작업은 RESUME 도 G1 에 막히므로, 계획 변경까지 막으면 나갈 길이 없다. 입력이 바뀐 변경이면 그 DELTA 도 끝난 검증이라 S2 대로 STALE(`PLAN_CHANGED`)이 되고, 작업은 WAITING(REVALIDATION) 그대로 새 계획의 PASS 로 RESUME 한다. 옛 완료 보고는 옛 리비전에 이력으로 남고 새 계획에는 기다리는 보고가 없다(다시 COMPLETED 하려면 RESUME 뒤).
- 결과 반영 때 입력 비교(`PLAN_CHANGED`·`RELATED_PLAN_CHANGED`)도 리비전 번호가 아니라 입력 해시로 한다 — 도는 사이 자기 계획이나 관련 작업의 제목만 바뀐 결과는 버리지 않는다.
- 유지와 무효화의 경계는 위 "검증 입력" 목록이다. 접근 방식이 바뀌어도 유지하는 것은 담당자 결정(AI 분석이 approach 를 읽더라도 설계 대조의 대상은 목표·범위·기준·참조다). 바꾸려면 `PlanInputHasher` 와 이 절을 함께 고친다.

RESUME(§2.3 출구 ①)은 delta 완료 보고를 거두고 다시 일하는 것이므로, 그 보고를 기다리던 **DELTA 검증을 끝났든 돌고 있든 STALE(`TASK_RESUMED`)** 로 바꾸고 대기 job 을 취소한다(-163). 두지 않으면 다시 일하던 작업이 — 인계(S5)로 다시 WAITING(REVALIDATION) 이 된 뒤 등 — 뒤늦게 도착한 옛 DELTA 의 PASS 로 완료될 수 있다.

### 2.3 WAITING(REVALIDATION) 에서 나가는 길 (담당자 결정 — 명세 반영 필요)

사유 값을 더 나누지 않는다. 나누면 DDL CHECK·명세 enum·FE 계약이 함께 바뀐다. 대신 **그 작업의 가장 최근 검증의 `trigger`** 로 구분한다.

| 가장 최근 검증 | 그 검증 결과 | 작업 | 나가는 길 |
|---|---|---|---|
| trigger = DELTA | PASS | 서버가 **COMPLETED** (S4) | — |
| trigger = DELTA | NEEDS_INPUT · NEEDS_REVIEW | **WAITING(REVALIDATION) 유지** | ① 코드를 계획대로 되돌릴 것이면 `RESUME`(시작 때 PASS 가 아직 유효하면 게이트 통과) 후 delta 없이 다시 COMPLETED ② 바뀐 내용이 맞으면 계획 리비전을 올려(S2) 새 검증 PASS → `RESUME` → COMPLETED (DELTA 결과가 난 뒤라 계획 변경이 열려 있다, §2.5) ③ 설계 자체를 바꿔야 하면 변경 제안 → 승인 후 ②. 설계 발행으로 시작 때 PASS 가 STALE 이면 ①은 G1 에 막혀 ②·③ 만 남는다 |
| trigger = DELTA | ERROR | **WAITING(REVALIDATION) 유지** | 같은 검증 재시도(VAL-A03, 새 validation_run). 자동 PASS 하지 않는다. ①·② 도 쓸 수 있다 |
| trigger = DELTA | RUNNING | 유지 | 결과를 기다린다. 이 동안 계획 변경은 `400 invalid_transition`(§2.5). 거두려면 ① |
| trigger = DELTA | STALE (DELTA 검증 중에 참조 설계가 발행됨) | 유지 | 새 기준으로 **새 DELTA 검증**을 접수한다(STALE 은 재시도가 아니라 새 검증, VAL-A03). 설계 발행 처리(S15P31A108-163)가 자동 접수한다. ②·③ 출구도 그대로 쓸 수 있고, ①은 G2 가 새 설계 기준으로 다시 판단한다 |
| trigger = AGENT·REVALIDATION (S1·S2·S5 로 들어온 경우) | 무엇이든 | 유지 | 현재 계획에 대한 새 PASS 를 받아 `RESUME` (게이트 G1·G2) |

- 어느 경우든 사람·에이전트가 `COMPLETED` 를 다시 보내서 빠져나갈 수는 없다(WAITING + COMPLETED = ✗). 완료는 S4 또는 RESUME 뒤의 새 COMPLETED 로만.
- ①이 가능한 이유: DELTA 검증은 별도 검증 행이라 시작 때 PASS 를 STALE 로 만들지 않는다. 그 사이 설계가 바뀌었으면 G2 가 막는다.
- 담당자 인계(S5) 뒤에는 ①이 막힌다(의도). 시작 때 PASS 는 이전 담당자 쪽 검증이라 S5 가 STALE 로 만든다. DELTA 가 NEEDS_INPUT·NEEDS_REVIEW 로 끝난 작업을 넘겨받은 새 담당자는 ②·③으로 나간다. DELTA 가 아직 RUNNING 이면 그대로 결과를 기다리고, PASS 면 S4 로 완료된다.

### 2.4 WAIT 사유 (확정)

| 사유 | 누가 설정 | 언제 |
|---|---|---|
| INPUT | 작업자·에이전트 | 계획 보완 입력을 기다림 |
| APPROVAL | 작업자·에이전트 | 설계 변경 승인을 기다림 |
| DEPENDENCY | 작업자·에이전트 | 선행 작업 완료를 기다림 |
| REVALIDATION | **시스템만** | 설계 발행(S1) · 진행 중 계획 변경(S2) · 완료 델타(S3) · 담당자 인계(S5, -212) |

요청에 `REVALIDATION` 을 넣으면 `400 invalid_transition`. 이 사유로 대기 중인 작업은 새 PASS 없이 나갈 수 없으므로, 에이전트가 사유를 바꿔치기해 게이트를 우회하지 못하게 막는다.

## 3. 시작 게이트 (확정 — 설계 D4)

START 와 RESUME 모두 같은 게이트를 지난다. 대기 중에도 설계는 바뀔 수 있기 때문이다(AC-06).

```
1. 멱등 확인 (START 만): 현재 IN_PROGRESS 이고 started_validation_id 가 요청 값과 같으면 현재 결과 반환.
   RESUME 는 이 단계를 거치지 않는다 — 시작 때 PASS 로 다시 RESUME 하는 것(§2.3 ①, WAIT 후 재개)이 막히지 않게.
2. expected_lock_version 비교 → 다르면 409 revision_conflict
3. project 행 SELECT … FOR UPDATE        ← 같은 프로젝트의 START/RESUME 를 한 줄로 세움
4. 네 가지 검사 (전부 수행하고 실패를 모아서 한 번에 응답)
5. 통과 → IN_PROGRESS, started_validation_id 기록, waiting_reason 비움
```

| # | 검사 | 실패 코드 | 근거 |
|---|---|---|---|
| G1 | `validation_id` 가 이 작업의 **현재 리비전**에 대한 검증이고 `status = PASS`. 옛 리비전 번호라도 현재 리비전에 그 검증을 이어 받은 복사본(§2.5)이 있으면 복사본으로 판정. 가짜 분석기 판정은 원본(`carried_from`)의 실행 기록으로 | `409 validation_not_passed` | AC-01·13, MCP-12 |
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
| 작업에는 담당 멤버(`assignee_member_id`) 가 있고, 그 멤버는 같은 프로젝트의 활성 멤버여야 한다 (복합 FK). (수정안 요청 · 11-04 사용자 결정) 담당자가 나가거나 제외될 때 인계 대상을 고르지 않으면 비워진다(미배정, MEM-A09·A10). 상태·검증은 그대로다. 미배정 작업은 팀장이 TSK-A06 으로 정하거나 팀원이 스스로 맡을 때까지 아무도 계획 수정·시작·재개하지 못한다 | 확정 (미배정은 수정안 요청) |
| 계획 등록·수정, START·RESUME·WAIT·COMPLETED 는 **담당자 본인**(사람 세션 또는 그 사람의 에이전트 토큰)만 한다 | 확정 (TSK-A07) |
| CANCEL 은 담당자 본인. 예외로 **사람 세션의 LEADER 는 "보고가 끊긴 작업"만** 취소할 수 있다. 에이전트 토큰은 LEADER 것이라도 남의 작업을 취소하지 못한다. 그 밖의 팀장 개입은 미결(§6) | 확정 (AC-14, TSK-A07 처리 규칙, 00-common 2절 TASK_OWNER "팀장도 자동 대리 권한 없음") |
| "보고가 끊긴 작업" = **IN_PROGRESS** 이고, 마지막 상태 보고 이후 임계값이 지난 작업. WAITING 은 보고가 멈추는 게 정상이라 넣지 않는다(넣으면 팀장 승인을 기다리는 작업까지 취소 가능해진다). 임계값은 미결(§6), 구현은 설정값(기본 30분) | 담당자 결정 (명세는 "보고가 끊긴 작업"까지만 정함) |
| 담당자 변경은 **현재 사람 팀장만** 한다. 진행 중 점유·인계·재검증 정책과 담당자 본인 변경 허용 여부는 미결(§6, Q02). (수정안 요청 · 11-04 사용자 결정) 예외: 미배정 작업은 팀원이 자기에게 맡길 수 있다(TSK-A06). 새 작업 등록(TSK-A01)도 MEMBER 라서다. 동시에 맡으면 lock_version 비교로 먼저 처리된 쪽만 성공하고(409 `revision_conflict`), 맡은 뒤는 S5 | 확정 (REST TSK-A06. 미배정 작업 스스로 맡기는 수정안 요청) |

### 4.2 선행 작업 (확정 + 담당자 결정)

| 규칙 | 라벨 |
|---|---|
| 선행 관계는 두 곳에 저장한다. `task_plan_revision.dependency_task_ids` = 그 리비전 당시 스냅샷(복원용), `task_dependency` = 현재 관계(게이트용) | 확정 (ERD) |
| 계획을 새로 올리면 `task_dependency` 를 그 계획 내용으로 덮어쓴다 | 담당자 결정 |
| `kind = BLOCKS` 만 G3 에 쓴다. `RELATES` 는 표시용 | 확정 (ERD) |
| 자기 자신·다른 프로젝트 작업은 `400 validation_failed`, A→B·B→A 1단계 순환은 `409 dependency_cycle` 로 막는다(명세 TSK-A01·A04 오류표). 더 깊은 순환(A→B→C→A)은 탐지하지 않아 시작 게이트에서 서로 기다릴 수 있다 — 미결(§6) | 확정(코드) + 담당자 결정(깊이) |

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
| 보고 끊김 임계값 (AC-14 "stalled", IN_PROGRESS 만) | 마지막 상태 보고(`agent_session.last_report_at`, 없으면 작업 마지막 변경 시각) 기준 **30분** | 설계 보고서 §5. 검증 lease 와 같은 자릿수 |
| 팀장의 더 넓은 개입 (범위 축소로 필요 없어진 작업 정리, READY 작업 취소, 오래 멈춘 WAITING 작업 정리 등) | 지금은 불가. 담당자에게 요청. 필요하면 위 임계값과 함께 정한다 | CANCEL 은 재개가 안 되므로(아래) 좁게 시작. 00-common 2절 "개입 정책 Q02" |
| 선행 작업 순환 탐지 깊이 | **1단계**(A↔B)만 서비스에서. 깊은 순환은 UI 경고 | 설계 보고서 §5 DEPENDS_ON 추적 깊이와 동일 |
| 진행 중 담당자 변경 | 금지. WAITING 으로 내린 뒤 변경. **-212 구현(2026-10-29 변준성 동의)**: §2.2 S5. 담당자 변경(TSK-A06)·제외·나가기가 같은 `TaskHandoverService.reassign` 으로 IN_PROGRESS 를 WAITING(REVALIDATION) 으로 내리고, 점유 중 작업의 검증(도는 것 포함)을 STALE(`ASSIGNEE_CHANGED`)로 바꾼 뒤 넘긴다. DELTA 검증만 그대로(담당자와 무관, PASS 면 S4). REVALIDATION 은 누가 접수하는지 -159 에서 정할 때까지 함께 무효화. READY 작업의 PASS 는 남긴다 — 시작 전이라 이전 담당자의 에이전트 세션이 없고 계획도 그대로라, 새 담당자가 같은 계획으로 START 해도 G1·G2 가 그대로 판단한다 | 에이전트 세션과 어긋남 방지. Q02 |
| 담당자 본인이 담당자를 바꾸는 것 | 열지 않음 (명세대로 팀장만). (수정안 요청 · 11-04 사용자 결정) 미배정 작업을 자기에게 맡기는 것만 연다 | 열면 TSK-A06 명세도 바꿔야 함 — 11-04 수정안에서 TSK-A06 에 반영 |
| 제외된 팀원의 진행 중 작업 | 점유를 계속 잡으므로 해제 필요. 팀장이 재배정·취소. **-212 구현(2026-10-29 변준성 동의)**: 열린 작업이 남으면 제외·나가기는 409 `active_tasks_exist`, `reassign_to_member_id` 를 주면 위 "진행 중 담당자 변경" 규칙으로 모두 넘긴 뒤 끝낸다. **(수정안 요청 · 11-04 사용자 결정)** 제외·나가기(MEM-A09·A10)는 막지 않는다. 화면이 경고만 보이고, `reassign_to_member_id` 가 있으면 위 규칙으로 넘기고 없으면 담당자만 비운다(미배정). 작업 상태·검증·점유는 그대로 두고, 새 담당자가 정해지면(팀장 배정 또는 팀원이 스스로 맡기, TSK-A06) 위 "진행 중 담당자 변경" 규칙(S5)을 따른다. 팀장이 미배정 작업을 취소하는 길은 열지 않는다(아래 알려진 한계) | -172 4번과 함께 결정. 11-04 수정안은 S15P31A108-218 |
| "센 관계 하나" 규칙 | 위 4.3 대로 | ERD §6 에서 팀 동의 대기 중 |
| CANCEL 후 재개 | 불가. 새 작업으로 | 이력 단순화. 필요하면 "복제" 기능으로 |

**알려진 한계 (11-04 사용자 결정, S15P31A108-218)**: 미배정 작업은 팀원이 스스로 맡거나 팀장이 TSK-A06 으로 배정하면 정리된다(11-04 추가). 남은 한계는 팀장이 미배정 작업을 바로 취소하는 길이 없다는 것이다. 보고가 끊긴 IN_PROGRESS 는 §4.1 의 팀장 취소로 정리할 수 있지만, 미배정 WAITING·READY·DRAFT 작업은 누군가 맡아야 정리된다. 아무도 맡지 않는 동안 점유 중(IN_PROGRESS·WAITING)인 미배정 작업은 같은 항목을 바꾸는 다른 작업의 시작을 계속 막는다(G4).

## 7. 이 문서가 코드에 들어가는 자리

| 내용 | 클래스 (예정) | 테스트 |
|---|---|---|
| §1 상태·사유 | `task.entity.TaskStatus`, `WaitingReason` (enum, DB 는 STRING) | 엔티티-DDL 일치 (`ddl-auto=validate`) |
| §2.1 요청 전이 표 | `task.service.TaskTransitionPolicy` — 허용/멱등/금지 결과 반환, 예외 안 던짐 | 6×5 전수 단위 테스트 + COMPLETED delta 있음/없음(202/200) 통합 |
| §2.2 시스템 전이 | S2: `TaskPlanService`, S3: `TaskStateService`(DELTA 접수는 `ValidationAcceptService.acceptBySystem`), S1: `PublicationImpactAdapter`(-163, 발행 포트 구현), S4: `DeltaCompletion`(검증 결과 반영·완료 보고·발행 뒤 재접수에서 부름), S5: `TaskHandoverService`(-212) | 모두 구현됨 (-163) |
| §3 게이트 | `task.service.StartGate` — project 잠금 후 G1~G4 | Testcontainers: 동시 START 2 스레드, PASS 없음, 선행 미완료, 설계 변경 후 |
| §4.3 참조 정규화 | `task.service.TaskPlanService` | 단위 |
| §4.1·§5 권한 | 각 서비스 첫 줄에서 -170 공통 권한 검사 | 비멤버 **404**(존재 숨김, AC-10), 비담당자 403, 에이전트 CANCEL 403, 팀장 CANCEL 은 보고 끊긴 작업만 |

## 변경 이력

- v1.12 (2026-11-04) 나가기·제외 수정안(S15P31A108-218, 11-04 사용자 결정). §4.1 담당자 행에 미배정, §6 "제외된 팀원의 진행 중 작업" 행에 제외·나가기는 막지 않고 인계 대상이 없으면 담당자만 비움(상태·검증·점유 그대로, 배정되면 S5), §6 아래에 알려진 한계. 같은 날 미배정 작업을 팀원이 스스로 맡는 길(TSK-A06)을 더해 §2.2 S5·§4.1·§6·알려진 한계를 고침.
- v1.11 (2026-11-03) -162 검증 최신성(변준성). §2.5 추가: 검증 입력(계획 입력 해시) 정의와 AC-13 결정 — 입력 같은 계획 변경은 검증 유지·이어 받기, 다른 변경은 S2, delta 검증이 아직 도는 동안만 계획 변경 거부(결과가 난 뒤는 §2.3 출구 ② 로 허용). S2 행·G1 행·§2.3 ② 에 반영. 검증 `input_snapshot_hash` 와 결과 반영의 입력 비교를 리비전 번호 대신 계획 입력 해시로.
- v1.10 (2026-11-02) §2.2 에 검증 롤백 문서(11) 링크 (변준성, -160).
- v1.9 (2026-11-02) -163 리뷰 반영(변준성). DELTA 검증은 완료 delta 를 분석 입력(AI-A02 `completion_delta`, 검사 `completion-delta`)에 넣고 규칙만으로는 PASS 하지 않는다 — READS 만 참조해도 의미 분석으로 간다(§2.2 S3, 완료 보고 자리에서 바로 COMPLETED 되는 경우 없음). RESUME 은 기다리던 DELTA 를 STALE(`TASK_RESUMED`)로(§2.2 아래). 가짜 분석기를 끈 서버에서는 가짜 분석기의 DELTA PASS 로 완료하지 않는다(G1 과 같은 규칙).
- v1.8 (2026-11-02) -163 구현 반영(변준성). S1 `PublicationImpactAdapter` 와 S3 DELTA 접수·S4 완료 전이 구현, S4 조건을 "현재 계획의 가장 최근 DELTA" 로(§2.2). 발행 중 DELTA 가 STALE 이 되면 새 기준으로 새 DELTA 를 서버가 접수하고, 계획이 옛 버전을 참조하므로 보통 규칙(STALE_REF)으로 바로 NEEDS_INPUT 이 나 담당자가 ② 출구로 간다. 방금 IN_PROGRESS 에서 WAITING 으로 내린 작업은 재접수하지 않는다(다시 일하던 작업이라 서버가 완료를 판단할 대상이 아님). READS 만 참조해 DELTA 가 규칙만으로 PASS 면 완료 보고 자리에서 바로 COMPLETED(200)다 — delta 내용 자체는 AI 계약(AI-A02)에 아직 없어 현재 계획 검사와 같다.
- v1.7 (2026-10-29) 리뷰 반영(변준성). §2.2 에 S5 담당자 인계 추가, §2.4 REVALIDATION 계기에 S5, §2.3 에 인계 뒤 ① 출구가 막히는 것(의도), §6 에 READY 작업 PASS 를 남기는 이유, §7 에 S5 자리.
- v1.6 (2026-10-29) §6 "진행 중 담당자 변경"·"제외된 팀원의 진행 중 작업"에 -212 구현 내용 기록(전진, 변준성 확인 요청).
- v1.5 (2026-10-27) S1: 바뀐 항목·WAITING 대상은 -164 `DesignChangeQueries` 재사용, STALE 대상은 validation_design_ref 기준, 폐기는 스냅샷 제외로 명시(§2.2). 리뷰 반영(전진).
- v1.4 (2026-10-26) 리뷰 반영(전진). S3 저장 순서(DELTA 검증 → 완료 보고)와 S4 적용 조건(아직 WAITING(REVALIDATION)·같은 delta 검증일 때만) 명시(§2.2).
- v1.3 (2026-10-26) 선행 순환 응답을 명세대로 409 dependency_cycle 로 정정(§4.2).
- v1.2 (2026-10-26) 리뷰 반영(전진) 2차. START 멱등을 "IN_PROGRESS + 같은 validation_id" 로 좁히고 RESUME 는 멱등 단계 제외(§2.1·§3), S1·S2 대상을 명세대로 IN_PROGRESS 만으로(§2.2), DELTA+STALE 출구 추가(§2.3), "보고가 끊긴 작업"을 IN_PROGRESS 로 한정하고 담당자 결정으로 표시(§4.1).
- v1.1 (2026-10-26) 리뷰 반영(전진). 시스템 전이 표(§2.2)와 WAITING(REVALIDATION) 출구(§2.3) 추가, COMPLETED 를 delta 유무로 200/202 분리, 팀장 CANCEL 을 "보고 끊긴 작업"으로 좁힘, 담당자 변경을 명세대로 "사람 팀장만", 비멤버 응답 404 로 정정.
- v1.0 (2026-10-26) 최초 작성. ERD v1.1·API 명세 v0.3 기준.
