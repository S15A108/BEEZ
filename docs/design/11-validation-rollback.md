# 11. 검증 롤백 (업무 롤백) 설계

담당: 변준성 (S15P31A108-160) · 범위: MVP · 상태: 담당자 결정 (팀 확인 요청)

관련: [04 작업 생애주기](04-task-lifecycle.md) §2.2 시스템 전이 · [00-common 6절 검증 실행 오류 규약](../api/00-common.md) · [REST TSK-A07·VAL-A01~A03](../api/01-backend-rest.md) · [배포 복구 (infra/cd.md)](../infra/cd.md)

## 0. 한 줄 요약

BEEZ 에서 "검증 롤백" 은 **검증 결과·작업 상태를 지우고 과거로 되돌리는 일이 아니다.** 검증이 실패·취소·무효화됐을 때 (1) 그 결과가 작업 상태에 반영되지 않게 막고(같은 트랜잭션에서 전체 롤백), (2) 이미 반영된 것이 있으면 **새 상태(STALE·ERROR·DISCARDED·CANCELLED)로 덮어 "더는 쓸 수 없다" 고 표시**하며, (3) 사람이 다음 길(새 검증·RESUME·재시도·계획 수정)로 나갈 수 있게 하는 것이다. 검증 행·실행·발견 항목은 **지우지 않는다** — 그 자체가 롤백 기록이다. 배포 롤백(예전 이미지로 되돌리기, infra/cd.md)과는 별개다 (§6).

## 1. 롤백 대상 — 무엇을 되돌리고 무엇을 안 되돌리나

| 대상 | 되돌리나 | 어떻게 |
|---|---|---|
| 검증 행(`validation`) | 지우지 않음 | 상태를 `STALE`(사유 필수) 또는 `ERROR` 로. 끝난 상태는 다시 RUNNING 으로 가지 않는다 |
| 실행(`validation_run`) | 지우지 않음 | 열린 실행은 `DISCARDED`(늦은 결과·입력 변경·무효화) 또는 `ERROR`/`TIMEOUT`(실패) 로 닫는다. 모델을 부른 사용량은 어느 경우든 남긴다(-168) |
| 큐(`validation_job`) | 지우지 않음 | `CANCELLED`(무효화·취소) 또는 `FAILED`(실패) 로. 리스 정보는 해제 |
| 발견 항목(`validation_finding`) | 지우지 않음 | 늦은 결과·버린 결과의 후보는 **애초에 넣지 않는다**(진단용 `summary.partial_findings` 에만). 실패는 ERROR 발견 항목 1개를 더한다 |
| 작업 상태(`task.status`) | 사건에 따라 | 검증 때문에 바뀐 상태는 **검증이 아니라 작업 전이 규칙**이 되돌린다 — IN_PROGRESS 는 WAITING(REVALIDATION) 으로 내리고, 완료(S4)는 DELTA PASS 로만 간다. 자동으로 COMPLETED 를 다시 IN_PROGRESS 로 돌리는 경로는 없다 |
| 계획 리비전(`task_plan_revision`) | 절대 안 됨 | 불변. 계획을 "되돌리는" 것은 새 리비전을 올리는 것(TSK-A04) |
| 완료 보고(`task_completion_report`) | 절대 안 됨 | 불변(UPDATE 금지). 완료 보고를 거두는 것은 RESUME(§2.3 출구 ①)이고, 그때 기다리던 DELTA 검증이 STALE(`TASK_RESUMED`) 이 된다 |
| 기준 설계·발행 | 검증 쪽에서 안 건드림 | 발행 트랜잭션이 검증 영향 반영까지 한 트랜잭션이라, 검증 쪽 실패는 **발행 전체를 롤백**한다(§3) |

**POL-06 결정 반영**: 작업·패킷(개발 컨텍스트)·검증의 데이터는 모두 "불변 행 + 현재 포인터" 구조다(계획 리비전·완료 보고·검증 행·실행은 추가만, `task.current_plan_revision_id`·`validation.current_attempt` 가 현재를 가리킴). 그래서 롤백은 "행을 고치는 것" 이 아니라 "포인터를 어디로 두고 옛 행에 무슨 표시를 남기느냐" 의 문제다. 이 문서의 모든 규칙은 그 전제 위에 있다.

## 2. 롤백이 일어나는 계기와 결과 (확정 — 구현됨)

"무효화(STALE)" 는 작업 생애주기 §2.2 S1·S2·S5 와 검증 쪽 사건을 합친 표다. 모두 **프로젝트 행을 잠근 한 트랜잭션** 안에서 한다.

| # | 계기 | 되돌리는 범위 | 남는 기록 | 사람이 다음에 할 일 |
|---|---|---|---|---|
| R1 | 늦은 결과 — 이미 STALE·취소·다른 시도·다른 리스인 검증에 워커 응답 도착 | 아무것도 반영 안 함 | 그 실행 `DISCARDED`("늦은 결과"), 사용량은 그 실행에 | 없음 |
| R2 | 입력 변경 — 결과를 기다리는 사이 이 작업의 계획(`PLAN_CHANGED`)·같은 항목을 보는 관련 작업·그 계획(`RELATED_PLAN_CHANGED`)의 **입력**이 바뀜. 제목·접근 방식만 바뀐 것은 입력 변경이 아니다(계획 입력 해시로 비교, 생명주기 §2.5) | 결과 버림, 검증 `STALE` | 실행 `DISCARDED`(사유 포함), job `CANCELLED`, `stale_reason` | 새 검증 요청(VAL-A01) |
| R3 | 리스 만료 — 워커가 AI 호출 중 죽음·재배포 | 검증 `ERROR` | 실행 `ERROR`, ERROR 발견 항목 `validation_lease_expired`/RETRY, job `FAILED` | 재시도(VAL-A04, -161) 또는 새 검증 |
| R4 | AI·BE 실행 실패 — 시간 초과·연결·계약 위반·PARTIAL·BE 오류 | 검증 `ERROR` | 실행 `TIMEOUT`/`ERROR`, ERROR 발견 항목(코드별 RETRY/HUMAN_REVIEW), 진단값(`summary.partial_findings` 등) | RETRY 면 재시도, HUMAN_REVIEW 면 근거 보완 후 새 검증 |
| R5 | 결과 저장 중 DB 오류 | 결과 반영 트랜잭션 전체 롤백 → R4 로 실패 처리 | 실행 `ERROR` `validation_execution_failed`, 응답의 사용량은 남김 | 재시도 |
| R6 | 작업 취소(TSK-A07 CANCEL) | 이 작업의 모든 검증(끝난 것·도는 것) `STALE`(`TASK_CANCELLED`), 큐 취소, 열린 실행 `DISCARDED` | 위 상태들 | 없음(작업 종료) |
| R7 | 진행 중 계획 변경(S2) — 검증 입력이 바뀐 변경만. 입력이 같으면(제목만 등) 무효화하지 않고 끝난 검증을 새 리비전으로 이어 받는다(생명주기 §2.5) | 끝난 검증만 `STALE`(`PLAN_CHANGED`); 도는 검증은 R2 가 처리 | `stale_reason` | 새 계획으로 검증 요청 → RESUME |
| R8 | 담당자 인계(S5) | DELTA 를 뺀 검증 `STALE`(`ASSIGNEE_CHANGED`), 큐 취소 | 위 상태들 | 새 담당자가 새 검증 요청 → RESUME |
| R9 | 설계 발행(S1, -163) | 바뀐 항목의 옛 버전을 참조한 검증 `STALE`(`DESIGN_PUBLISHED`), 큐 취소, 열린 실행 `DISCARDED`; IN_PROGRESS 작업은 WAITING(REVALIDATION) | 위 상태들 + 기다리던 DELTA 는 새 기준으로 **자동 재접수** | 계획을 새 기준으로 올리고 검증 요청 |
| R10 | RESUME(§2.3 출구 ①) | 기다리던 DELTA(ERROR 포함) `STALE`(`TASK_RESUMED`), 큐 취소 | `stale_reason` | 다시 완료 보고 |
| R11 | 발행 트랜잭션 안에서 R9 처리 실패 | **발행 전체 롤백**(새 기준·버전·승인 이력·현재 포인터까지) | 아무것도 남지 않음(로그만) | 발행을 다시 시도 |

**부분 롤백은 없다.** 한 계기의 처리는 한 트랜잭션이라 "검증은 STALE 인데 큐는 살아 있음" 같은 중간 상태가 생기지 않는다. 트랜잭션 사이의 틈(워커가 AI 를 기다리는 동안 R6~R10 이 일어남)은 R1·R2 가 결과 도착 시점에 닫는다.

## 3. 조건·시점·복구 방식 (확정)

- **시점은 셋뿐이다.** ① 사건이 일어나는 요청의 트랜잭션 안(R6~R11), ② 워커가 결과를 반영하는 트랜잭션 안(R1·R2·R4·R5), ③ 워커의 주기 회수(R3). 별도의 "롤백 배치" 는 없다.
- **잠금 순서는 한 가지** — 프로젝트 행 → 작업 → 검증(job → validation). 발행·상태 보고·계획 변경·검증 접수·결과 반영·회수가 모두 같다. 그래서 두 계기가 겹쳐도 한쪽이 끝난 뒤 다른 쪽이 "지금" 상태를 보고 판단한다.
- **늦은 결과를 막는 울타리(fencing)** — 결과 반영은 `RUNNING`·같은 `current_attempt`·같은 리스 토큰·리스 유효기간 안·분석 입력 불변을 모두 확인한 뒤에만 쓴다. 하나라도 어긋나면 R1·R2. 상태 갱신 SQL 자체도 `where status = 'RUNNING'` 이라 조건이 어긋나면 0 행이다.
- **복구는 "새 행" 으로만** — 재시도(VAL-A04, -161)는 같은 검증의 새 시도(`attempt+1`, 새 실행. 큐 작업은 검증마다 하나라 그 행을 다시 QUEUED 로 돌린다), 무효화 뒤는 새 검증 행, 계획 수정은 새 리비전. 옛 행을 고쳐 살리는 경로는 없다.
- **같은 트랜잭션 원칙(D7)** — 작업 상태 전이와 그에 따른 검증 무효화는 반드시 한 트랜잭션이다. 알림·활동 기록도 붙으면(-181·-182) 같은 트랜잭션에 넣는다(계약 10).

## 4. 롤백 결과 기록 (확정 — 구현됨)

따로 "롤백 표" 를 두지 않는다. 결과는 기존 행의 상태 칸에 남고, 아래로 읽는다.

| 알고 싶은 것 | 어디 |
|---|---|
| 이 검증이 왜 못 쓰게 됐나 | `validation.status=STALE` + `stale_reason` (`PLAN_CHANGED`·`RELATED_PLAN_CHANGED`·`TASK_CANCELLED`·`ASSIGNEE_CHANGED`·`DESIGN_PUBLISHED`·`TASK_RESUMED`) |
| 실패했다면 어떤 실패인가 | `validation.status=ERROR` + 현재 시도의 ERROR 발견 항목(`resolution` RETRY/HUMAN_REVIEW) + 응답 `error_code` |
| 그 시도에서 AI 를 불렀나, 결과는 어디 갔나 | `validation_run.status`(OK/TIMEOUT/ERROR/DISCARDED) + `error`(사유 문장) + `analyzer_version` + `usage` |
| 버린 결과의 내용(진단용) | `validation.summary.partial_findings`·`checked_keys`·`unassessed` (PARTIAL·계약 위반), `usage_rejected`(모양이 틀린 사용량 원문) |
| 큐에서 왜 빠졌나 | `validation_job.status` CANCELLED(무효화·취소) / FAILED(실패) / DONE(정상) |
| 작업이 왜 WAITING 인가 | `task.waiting_reason=REVALIDATION` + 그 작업 현재 계획의 가장 최근 검증(`trigger`·`status`) — 생애주기 §2.3 표 |
| 롤백 성공/실패/부분 | 성공 = 위 상태가 같이 바뀜(한 트랜잭션). 실패 = 트랜잭션 롤백 → 아무 상태도 안 바뀌고 서버 로그에 `validation {} 결과 반영 실패` / `검증 작업 처리 중 오류`. 부분 = 없음(§2) |

운영 점검 쿼리(읽기 전용):

```sql
-- 1) 돌고 있어야 하는데 멈춘 검증: RUNNING 인데 job 이 LEASED 가 아니고 QUEUED 도 아님
select v.id, v.task_id, v.current_attempt, j.status, j.lease_until
  from validation v join validation_job j on j.validation_id = v.id
 where v.status = 'RUNNING' and j.status not in ('QUEUED', 'LEASED');

-- 2) 리스가 지났는데 회수가 안 된 job (회수기가 멈췄는지)
select j.id, j.validation_id, j.lease_until from validation_job j
 where j.status = 'LEASED' and j.lease_until < now() - interval '5 minutes';

-- 3) 열린 실행이 남은 끝난 검증 (있으면 안 됨)
select r.id, r.validation_id, v.status from validation_run r join validation v on v.id = r.validation_id
 where r.status = 'RUNNING' and v.status <> 'RUNNING';

-- 4) 재검증 대기인데 기다리는 DELTA 도 없고 현재 계획 검증도 없는 작업 (사람이 RESUME·새 검증으로 풀어야 함)
select t.id, t.key from task t
 where t.status = 'WAITING' and t.waiting_reason = 'REVALIDATION'
   and not exists (select 1 from validation v where v.task_id = t.id and v.plan_revision_id = t.current_plan_revision_id
                    and v.status in ('RUNNING', 'PASS', 'NEEDS_INPUT', 'NEEDS_REVIEW', 'ERROR'));
```

## 5. 롤백이 실패하면 — 상태 표시와 수동 조치 (담당자 결정)

| 상황 | 시스템이 보이는 모습 | 자동 복구 | 수동 조치 |
|---|---|---|---|
| 결과 반영 트랜잭션이 실패하고 실패 처리까지 실패(DB 장애) | 검증 RUNNING, job LEASED 그대로. 서버 로그에 오류 두 줄 | 리스 만료 뒤 회수기가 R3 로 닫는다(`validation_lease_expired`) — 원인은 DB 장애였어도 코드는 리스 만료로 남는다 | 로그로 원인 확인. 재시도(-161) 또는 새 검증. 회수기도 멈췄으면 쿼리 2 로 찾아 운영자가 `job FAILED`·`validation ERROR` 로 닫는다(아래 SQL) |
| 워커가 죽어 회수기도 안 돎 | 쿼리 2 에 행이 쌓임 | 워커 재기동 시 주기 회수가 처리 | 재기동. 장시간이면 아래 SQL |
| 발행 중 검증 영향 처리 실패(R11) | 발행이 409/500 으로 실패, 아무것도 안 바뀜 | 없음 | 로그 확인 후 발행 재시도. 반복되면 검증 쪽 버그 — -163 담당 |
| 사람이 봐도 작업이 어디서 막혔는지 모름 | WAITING(REVALIDATION) 인데 검증이 STALE/ERROR | 없음 | 생애주기 §2.3 표대로: 새 검증 요청 → RESUME, 또는 계획 수정(새 리비전) → 검증 → RESUME. 완료 보고를 거두려면 RESUME |

운영자가 손으로 닫을 때(최후 수단, 운영 DB 에서, 원인 로그와 함께 기록):

```sql
-- 멈춘 시도 하나를 실패로 닫는다 (워커 회수와 같은 결과). :v 검증 번호
update validation_job set status = 'FAILED', lease_token = null, lease_until = null, updated_at = now()
 where validation_id = :v and status in ('QUEUED', 'LEASED');
update validation_run set status = 'ERROR', error = 'validation_worker_interrupted: 운영자 수동 종결', finished_at = now(), updated_at = now()
 where validation_id = :v and status = 'RUNNING';
insert into validation_finding (validation_id, validation_run_id, source, kind, severity, message, resolution, next_action)
select :v, r.id, 'MODEL', 'ERROR', 'BLOCKING', '워커 중단으로 운영자가 종결했습니다', 'RETRY', '같은 입력으로 다시 실행합니다 (VAL-A04)'
  from validation_run r where r.validation_id = :v order by r.attempt desc limit 1;
update validation set status = 'ERROR', finished_at = now(), lock_version = lock_version + 1, updated_at = now(),
       summary = coalesce(summary, '{}'::jsonb) || '{"error_code": "validation_worker_interrupted"}'::jsonb
 where id = :v and status = 'RUNNING';
```

작업 상태는 손으로 바꾸지 않는다 — 검증을 닫으면 담당자가 정해진 전이(새 검증·RESUME·CANCEL)로 나간다. 상태를 SQL 로 고치면 잠금 번호·활동 기록·알림과 어긋난다.

## 6. 배포 복구와 업무 롤백의 구분 (확정)

| | 배포 복구 (infra/cd.md, 전진 -74) | 업무 롤백 (이 문서) |
|---|---|---|
| 되돌리는 것 | 서버에 떠 있는 코드(이미지 SHA) | 검증 결과가 작업에 미치는 효력 |
| 누가 | 운영자(Jenkins 또는 손으로) | 서버가 자동(§2), 사람은 다음 전이로 |
| DB | 스키마는 되돌리지 않음(Flyway 전진만) | 행을 지우거나 고치지 않음(상태 표시만) |
| 데이터 영향 | 예전 코드가 새 칼럼을 모를 뿐 | 없음 — 모든 변화가 새 행·새 상태 |
| 둘이 만나는 곳 | 재배포로 워커가 내려가면 진행 중 시도는 리스 만료(R3)로 닫힌다. 배포 직후 `validation_lease_expired` ERROR 가 몇 건 생기는 것은 정상이며, 담당자가 재시도하면 된다. 배포 전에 큐를 비울 필요는 없다 | |

## 7. 이 문서가 코드에 들어가는 자리

| 절 | 코드 |
|---|---|
| R1·R2·R4·R5 | `AnalysisResultApplier.current` / `fail` / `applyFailure` |
| R3 | `ValidationWorker.recoverExpired` → `AnalysisResultApplier.applyLeaseExpired` |
| R6·R7·R8 | `ValidationStatusLookup.invalidateAllForTask` / `markStaleForTask` / `invalidateForReassign` |
| R9·R11 | `PublicationImpactAdapter` (+ `ValidationStatusLookup.invalidateByIds`) |
| R10 | `ValidationStatusLookup.invalidateDeltaOnResume` |
| 사용량 보존 | `ValidationRepository.finishRun` / `recordLateUsage` (-168) |
| 재시도(새 시도) | `ValidationRetryService.retry` (VAL-A04·MCP-14, -161) |

## 변경 이력

- v1.0 (2026-11-02) 최초 작성 (변준성, -160). 구현된 R1~R11 과 기록 위치를 정리하고, 롤백 실패 시 수동 조치·배포 복구와의 구분을 담당자 결정으로 제안.
- v1.1 (2026-11-04) 재시도(VAL-A04·MCP-14, -161) 반영 (변준성). 큐 작업은 검증마다 하나라 새 job 이 아니라 같은 행을 다시 QUEUED 로 돌린다. 수동 종결 SQL 이 오류 코드(`validation_worker_interrupted`)·완료 시각을 남기고, 다음 행동을 재시도로 안내한다. 수동 종결·리스 만료로 닫힌 실행은 재시도 횟수 제한에서 세지 않는다(횟수 제한은 실제 검증 AI를 부른 실행만 센다).
