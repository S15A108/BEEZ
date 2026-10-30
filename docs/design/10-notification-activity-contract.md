# 알림·활동 이벤트 공통 계약

작성: 전진 · Jira: S15P31A108-180 · 대상: 오서현(-217·-237), 변준성(-64), 전진(-181·-182·-183) · 참고: 양지훈(CDC 경계)

각 도메인이 알림과 활동 이벤트를 **어떻게 넘기는가**를 정한다. 사용자가 보는 모양은 REST 명세 [NTF-A01·A02](../api/01-backend-rest.md#ntf-a01)·[MON-A04](../api/01-backend-rest.md#mon-a04)가 기준이고, 이 문서는 도메인 서비스가 부르는 자바 입구·필드·키·유형만 다룬다. [공통 서비스 데이터](../api/00-common.md)의 "활동 이벤트"·"알림 요청" 행을 구체화한 것이며 메시지 브로커를 전제하지 않는다.

## 1. 결정 요약

| # | 항목 | 결정 | 이유 |
| --- | --- | --- | --- |
| 1 | 전달 시점 | 도메인 트랜잭션 **안에서** 저장한다. 키가 이미 있으면 조용히 건너뛴다 | 커밋 뒤 호출은 커밋과 저장 사이에 backend가 죽으면 유실된다(배포 때마다 컨테이너를 다시 만든다). 같은 트랜잭션이면 롤백된 처리의 알림도, 유실도 없다 |
| 2 | 필드 이름 | 계약은 API 이름을 쓴다. DB 컬럼은 바꾸지 않고 구현에서 대응시킨다 | 명세·화면과 같은 말을 쓰고, 마이그레이션은 꼭 필요한 것만 |
| 3 | 중복 키 | 도메인이 만든다. `{대상}:{ID}:{동작}[:구분]`, 알림은 끝에 `:to:{수신자 계정 ID}` | 같은 사실을 몇 번 넘겨도 한 건만 남게 |
| 4 | 수신자·문구 | 호출하는 도메인이 수신자를 고르고 제목·본문을 완성해서 넘긴다 | 누가 받아야 하는지는 그 도메인만 안다. 공통 모듈은 템플릿을 두지 않는다 |
| 5 | 활동 대상 | 주 대상 1개 + 선택 보조 참조(`task_id`·`change_proposal_id`·`baseline_id`) + `result` | MON-A04 응답·필터 모양 그대로 |
| 6 | 유형 | 이 문서 6절 표가 목록이다. 새 유형은 표에 행을 추가하는 PR로. 코드는 형식만 검사한다 | 유형 이름은 한곳에서 보되, 새 유형을 넣을 때마다 공통 모듈 코드를 고치지 않게 |

## 2. 전달 방식

### 2.1 입구

| 입구 | 패키지 | 담당 |
| --- | --- | --- |
| `NotificationRequester.request(NotificationRequest)` / `requestAll(List<NotificationRequest>)` | `com.beez.backend.notification` | 전진 -181 |
| `ActivityRecorder.record(CallerContext, ActivityEvent)` — 사람·에이전트 행위 | `com.beez.backend.activity` | 전진 -182 |
| `ActivityRecorder.recordBy(SystemActor.BEE\|SYSTEM, ActivityEvent)` — 비비·시스템 행위 | `com.beez.backend.activity` | 전진 -182 |

`SystemActor`는 activity 패키지에 새로 둔다. 기존 `common.access.ActorType`(HUMAN·AGENT)은 "요청을 보낸 주체" 뜻이라 BEE·SYSTEM을 넣어 넓히지 않는다.

- 두 입구는 `@Transactional(propagation = MANDATORY)`다. **도메인 트랜잭션 안에서만** 부를 수 있고, 밖에서 부르면 예외다.
- 도메인 트랜잭션이 없는 곳(토큰 필터·MCP 어댑터·스케줄러)에서 남겨야 하면 `TransactionTemplate`으로 짧은 쓰기 트랜잭션을 열고 그 안에서 부른다. 이때 롤백·유실의 범위는 그 짧은 트랜잭션 하나다.
- `readOnly` 트랜잭션 안에서는 부르지 않는다(PostgreSQL이 INSERT를 거부한다). 읽기 경로에서 남겨야 하면 그 서비스 호출이 끝난 뒤 호출측(어댑터)에서 위처럼 짧은 트랜잭션을 연다. `REQUIRES_NEW`로 안에서 열면 바깥 연결을 쥔 채 연결을 하나 더 잡아, 자주 불리는 읽기 경로에서 연결 풀이 빨리 바닥난다.
- 저장은 `INSERT … ON CONFLICT (dedupe_key) DO NOTHING`이다. 같은 키가 다시 오면 **처음 것을 유지**하고 오류를 내지 않는다. 내용이 달라도 마찬가지다.
- 이 INSERT와 수신자 확인 조회는 **`EntityManager` 네이티브 쿼리**로 한다. `JdbcTemplate`은 Hibernate를 거치지 않아, 같은 트랜잭션에서 도메인이 JPA로 바꾸고 아직 flush하지 않은 변경(방금 제외·되살린 멤버십, 바뀐 담당자)을 보지 못한다. 네이티브 쿼리는 실행 전에 자동 flush된다.
- `requestAll`은 수신자들이 활성 멤버인지 **조회 한 번**(`account_id IN (…)`)으로 거른다. 발행처럼 project 행 잠금을 쥔 트랜잭션에서 불리므로 수신자 수만큼 조회하지 않는다.
- 도메인 이벤트(`ApplicationEvent`)로 넘기고 싶으면 듣는 쪽은 `@TransactionalEventListener(phase = BEFORE_COMMIT)` 또는 `@EventListener`(같은 트랜잭션)를 쓴다. **`AFTER_COMMIT`에서는 부르지 않는다** — 이미 커밋이 끝난 뒤라 저장이 반영되지 않을 수 있고(Spring `TransactionSynchronization.afterCommit` 주의 사항), 유실 틈도 그대로 남는다.
- 도메인 이벤트에는 계정 ID가 아니라 **`CallerContext`를 담는다.** 계정 ID만 담으면 같은 계정의 사람 행위와 에이전트 행위를 구분할 수 없고, 에이전트 세션도 연결할 수 없다. 사람·에이전트가 아니라 시스템 판단으로 생긴 변화(검증 결과에 따른 전이 등)는 `CallerContext` 없이 `recordBy(SystemActor.SYSTEM, …)`로 남긴다.

### 2.2 실패를 어떻게 다루나

| 상황 | 처리 |
| --- | --- |
| 같은 키가 이미 있음 | 건너뜀. 정상 |
| 알림 수신자가 그 프로젝트의 활성 멤버가 아님(나감·제외·탈퇴) | 그 수신자만 건너뜀. 오류 아님 |
| 제목·본문이 길이 제한을 넘음 | 공통 모듈이 **코드 포인트 기준**으로 잘라서(끝에 `…`) 저장. 오류 아님 — 사용자가 입력한 제목이 문구에 들어가기 때문. UTF-16 단위로 자르면 이모지가 반으로 갈려 깨진다 |
| 필수 필드 누락, 키·유형이 형식(5·6절)에 맞지 않음 | 예외 → 도메인 처리도 롤백. 사용자 입력과 무관한 호출 코드 버그이므로 테스트에서 잡는다 |
| 그 밖 DB 오류 | 예외 → 도메인 처리도 롤백 |

알림·활동 저장 실패가 도메인 처리를 되돌릴 수 있다는 것이 이 방식의 대가다. 사용자 입력으로 생길 수 있는 경우(키 충돌·비멤버·길이 초과)는 모두 건너뛰거나 잘라서 저장하므로, 남는 것은 호출 코드 버그와 DB 장애뿐이라 받아들인다. 이 결정으로 [D7](01-system-architecture.md)의 "알림·타임라인은 커밋 후"를 "같은 트랜잭션, 키 충돌은 건너뜀"으로 바꾼다(Q03).

### 2.3 하지 않는 것

- 재시도·지연 전송·outbox 없음. 같은 트랜잭션이라 필요 없다.
- 메일·푸시 발송 없음. 화면이 알림함(NTF-A01)을 조회한다. 실시간 전파(NTF-07·08)는 미정.
- CDC(-224~-229)를 거치지 않는다. CDC 소비자가 알림·활동을 남겨야 하면 소비자 트랜잭션 안에서 같은 입구를 부른다. 반대로 `notification`·`activity_event`는 CDC 캡처 대상에서 뺀다(-225) — 개인 알림이 전달 경로로 새지 않게.

## 3. 알림 요청

| 필드 | 타입 | 필수 | 규칙 |
| --- | --- | --- | --- |
| `notification_key` | string | Y | 5절 규칙. 최대 200자. DB `dedupe_key` |
| `project_id` | ID | Y | |
| `recipient_account_id` | ID | Y | 받는 사람 1명. 여러 명이면 요청을 여러 개 만든다(키도 수신자별) |
| `type` | string | Y | 6.1 표의 값 |
| `title` | string | Y | 최대 100자, 넘으면 잘라 저장. 화면에 그대로 보인다 |
| `body` | string | Y | 최대 500자, 넘으면 잘라 저장 |
| `target_type` | `TASK\|VALIDATION\|CHANGE_PROPOSAL\|BASELINE\|MEETING\|DOCUMENT_VERSION` | Y | 누르면 갈 곳. DB `ref_kind` |
| `target_id` | ID | Y | DB `ref_id` |
| `document_id` | ID | 조건 | `target_type=DOCUMENT_VERSION`이면 필수(화면이 문서 상세 주소를 만든다) |

- **수신자는 호출 도메인이 고른다.** 관련자가 없으면 아무도 부르지 않는다 — 전체 팀으로 바꿔 보내지 않는다(NTF-02).
- **행위 자체를 알리는 알림**(승인됨·반려됨·올림·검토 요청·의견 지정·기준 생김 등)은 행위자 본인을 빼고 넘긴다. 자기가 한 일을 자기에게 알리지 않는다.
- **받는 사람이 후속 조치를 해야 하는 알림**(`REVALIDATION_REQUIRED`·`TASK_ASSIGNED`)은 행위자 본인이어도 보낸다. 팀장이 발행한 변경으로 팀장 자신의 작업이 재검증 대기가 되거나, 팀장이 자기에게 작업을 배정해도 알림함에 남아야 한다. 6.1 표의 "본인 포함" 열을 따른다.
- **문구에 원문을 넣지 않는다.** 제목·본문은 "어떤 일이 있었다"까지만 쓰고, 문서 내용·의견 원문·토큰 값은 넣지 않는다. 자세한 내용은 대상 화면에서 권한 검사를 거쳐 본다.
- 읽음(`read_at`)은 공통 모듈이 관리한다(NTF-A02). 해결 상태(`resolved_at`)는 이번 계약에서 쓰지 않는다 — 응답 필드가 없다.
- 보관 기한은 두지 않는다(MVP). 대상이 지워졌거나 권한이 없으면 화면이 404를 받고 읽음 처리한다(NTF-A01 처리 규칙).

## 4. 활동 이벤트

| 필드 | 타입 | 필수 | 규칙 |
| --- | --- | --- | --- |
| `event_key` | string | Y | 5절 규칙. 최대 200자. DB `dedupe_key` |
| `project_id` | ID | Y | |
| `type` | string | Y | 6.2 표의 값 |
| `target_type` | string | Y | 주 대상 종류. 3절 값 + `DOCUMENT`·`PROJECT`·`MEMBER`·`AGENT_SESSION` |
| `target_id` | ID | Y | |
| `task_id`·`change_proposal_id`·`baseline_id` | ID | N | 보조 참조. 주 대상과 같아도 넣는다(필터용 — MON-A04 `change_proposal_id`로 한 제안의 지나온 길을 본다) |
| `result` | `SUCCESS\|FAILURE` | N | 기본 `SUCCESS`. 검증 실패처럼 결과가 갈리는 활동에만 `FAILURE` |

**행위자는 호출측이 채우지 않는다.** 사람·에이전트 행위는 서비스가 이미 받은 `CallerContext`를 그대로 넘기고, 공통 모듈이 아래를 채운다.

| 저장 필드 | 채우는 방법 |
| --- | --- |
| `actor_type` | `CallerContext.kind`(HUMAN·AGENT). 비비·시스템은 `recordBy`의 `SystemActor`. DB `actor_kind` |
| `actor_account_id` | `CallerContext.accountId`(에이전트는 토큰 주인 계정). 비비·시스템이면 비움 |
| `agent_session_id` | `CallerContext.agentTokenId`의 현재 연결 세션. 세션 기록(-178)이 생기기 전에는 비움 |
| `actor_name`(응답) | 계정 이름 또는 토큰 이름 |
| `occurred_at` | `clock_timestamp()`(실제 저장 시각). `now()`는 트랜잭션 시작 시각이라 쓰지 않는다 |

- 타임라인 정렬·cursor는 **id 내림차순**으로 한다(-183에서 MON-A04 처리 규칙에 반영). 시각 기준 정렬은 시각이 트랜잭션 시작 쪽으로 당겨질수록, 늦게 커밋된 활동이 cursor로 이미 지나간 위치에 끼어 이어 보기에서 빠질 수 있다. id는 저장하는 순간에 정해지므로 그 틈이 저장~커밋 사이로 줄어든다(완전히 없어지지는 않는다 — 처음부터 다시 읽으면 보인다).
- 타임라인은 프로젝트 **멤버 전원**이 본다. 멤버에게 보여도 되는 사실만 남기고 원문·민감정보는 넣지 않는다.
- 화면 응답(Activity)에는 주 대상 `target_type`·`target_id`도 내보낸다(공통 명세 Activity 스키마 수정안). 문서·멤버·세션처럼 보조 참조가 없는 활동도 무엇에 대한 일인지 보이게.

## 5. 중복 키 규칙

```
{대상}:{ID}:{동작}[:{구분}...]          활동 이벤트
{대상}:{ID}:{동작}[:{구분}...]:to:{계정 ID}   알림 (수신자별)
```

- **도메인 사실만으로 정해지게** 만든다. 난수·현재 시각을 넣지 않는다. 같은 처리를 다시 해도(멱등 재요청·재시도) 같은 키가 나와야 한 건만 남는다.
- 같은 동작이 여러 번 정당하게 일어날 수 있으면 구분 값을 붙인다. 버전 번호·잠금 버전·리비전 번호처럼 매번 달라지는 도메인 값을 쓴다.
- 첫 조각(`{대상}`)은 그 대상을 가진 도메인만 쓴다. 영문 소문자와 `-`.
- 공통 모듈이 검사하는 형식: `^[a-z][a-z0-9-]*(:[A-Za-z0-9_-]+)+$`, 최대 200자. 맞지 않으면 예외(2.2).
- 알림과 활동은 테이블이 달라 같은 키 앞부분을 써도 된다.

| 예 | 뜻 |
| --- | --- |
| `task:501:lock:7:START` | 작업 501 시작, 시작을 저장한 뒤 올라간 잠금 버전 7(이미 쓰는 `TaskStateChangedEvent` 키 — `saveAndFlush` 뒤의 `lockVersion`) |
| `task:501:lock:9:COMPLETED` | 작업 501이 delta 검증 통과(S4)로 COMPLETED가 됨, 그 저장 뒤 잠금 버전 9 |
| `validation:88:finished:2` | 검증 88의 2번째 시도가 끝남 |
| `change-proposal:71:published` | 제안 71 발행 |
| `change-proposal:71:published:to:12` | 제안 71 발행 알림, 계정 12에게 |
| `change-discussion:88:mentioned:to:12` | 의견 88에서 지정된 멤버 12에게 알림(`ChangeDiscussionService` TODO의 "의견 ID + 대상 계정"에 동작을 붙인 것) |
| `doc-submission:33:review-requested:to:11` | 문서 제출 33 검토 요청, 팀장 11에게 |
| `project:101:created` | 프로젝트 101 생성 |

## 6. 유형표

이름은 `{대상}_{과거형 동작}` 대문자 스네이크(`^[A-Z][A-Z0-9_]{2,63}$`). 공통 모듈은 이 형식만 검사하고 목록은 갖지 않는다 — 유형 상수는 소유 도메인 코드에 두고, 새 유형은 그 도메인 PR에서 이 표에 행을 추가한다. 수신자·대상은 소유 도메인이 구현하며 확정하고, 바뀌면 이 표를 고친다.

### 6.1 알림 유형

| type | 소유 | 언제 | 수신자 | 본인 포함 | target_type |
| --- | --- | --- | --- | --- | --- |
| `DOCUMENT_BEE_REVIEW_DONE` | 문서(오서현) | 비비 문서 검토 끝남 | 등록자 | — (행위자가 비비) | DOCUMENT_VERSION |
| `DOCUMENT_REVIEW_REQUESTED` | 문서(오서현) | 문서 승인 요청 | 팀장 | 아니오 | DOCUMENT_VERSION |
| `DOCUMENT_APPROVED` | 문서(오서현) | 문서 승인 | 등록자 | 아니오 | DOCUMENT_VERSION |
| `DOCUMENT_REJECTED` | 문서(오서현) | 문서 반려(사유는 상세에서) | 등록자 | 아니오 | DOCUMENT_VERSION |
| `DOCUMENT_CHANGED_BY_MEETING` | 회의(오서현) | 회의가 끝나 문서가 바뀜 | 관련 작업 담당자 | 아니오 | DOCUMENT_VERSION |
| `BASELINE_CREATED` | 기준(오서현) | 기준 v1 생김 | 활성 팀원 | 아니오 | BASELINE |
| `MEETING_OPENED` | 회의(오서현) | 비비가 회의를 엶 | 참여자 | — (행위자가 비비) | MEETING |
| `MEETING_RECORD_ADDED` | 회의(오서현) | 회의에 새 기록 | 다른 참여자 | 아니오 | MEETING |
| `CHANGE_PROPOSAL_SUBMITTED` | 변경(오서현) | 제안 올림 | 관련 작업 담당자 | 아니오 | CHANGE_PROPOSAL |
| `CHANGE_PROPOSAL_REVIEW_REQUESTED` | 변경(오서현) | 검토 요청 | 지정 검토자 | 아니오 | CHANGE_PROPOSAL |
| `CHANGE_PROPOSAL_COMMENTED` | 변경(오서현) | 의견에서 멤버 지정 | 지정 멤버 | 아니오 | CHANGE_PROPOSAL |
| `CHANGE_PROPOSAL_APPROVED` | 변경(오서현) | 승인 | 제안자 | 아니오 | CHANGE_PROPOSAL |
| `CHANGE_PROPOSAL_REJECTED` | 변경(오서현) | 반려 | 제안자 | 아니오 | CHANGE_PROPOSAL |
| `CHANGE_PROPOSAL_PUBLISHED` | 변경(오서현) | 발행 | 관련 작업 담당자 | 아니오 | CHANGE_PROPOSAL |
| `TASK_ASSIGNED` | 작업(변준성) · 호출은 `TaskHandoverService.reassign`(전진) | 담당자 변경(TSK-A06)·인계(멤버 제외·나가기) — 모두 `reassign`을 거친다 | 새 담당자 | **예** | TASK |
| `REVALIDATION_REQUIRED` | 작업(변준성) | 발행으로 검증이 STALE이 됐거나 WAITING(REVALIDATION)이 됨(완료·취소 작업 제외) | 작업 담당자, 작업마다 한 건 | **예** | TASK |

### 6.2 활동 유형

| type | 소유 | target_type |
| --- | --- | --- |
| `TASK_CREATED`·`TASK_PLAN_REVISED` | 작업(변준성) | TASK |
| `TASK_STARTED`·`TASK_RESUMED`·`TASK_WAITING`·`TASK_COMPLETION_REPORTED`·`TASK_COMPLETED`·`TASK_CANCELLED` | 작업(변준성) | TASK — 아래 "작업 유형 정하기"를 따른다 |
| `TASK_ASSIGNEE_CHANGED` | 작업(변준성) · 호출은 `TaskHandoverService.reassign`(전진, TSK-A06·제외·나가기 모두) | TASK |
| `VALIDATION_FINISHED` | 작업(변준성) | VALIDATION — 아래 "검증 완료 정하기"를 따른다 |
| `DOCUMENT_REGISTERED`·`DOCUMENT_APPROVED`·`DOCUMENT_REJECTED` | 문서(오서현) | DOCUMENT_VERSION |
| `BASELINE_CREATED` | 기준(오서현) | BASELINE |
| `CHANGE_PROPOSAL_SUBMITTED`·`_WITHDRAWN`·`_REVIEW_STARTED`·`_REVIEW_REQUESTED`·`_COMMENTED`·`_APPROVED`·`_REJECTED`·`_PUBLISHED` | 변경(오서현) | CHANGE_PROPOSAL |
| `PROJECT_CREATED` | 프로젝트(전진) | PROJECT |
| `MEMBER_JOINED`·`MEMBER_LEFT`·`MEMBER_REMOVED`·`LEADER_TRANSFERRED` | 프로젝트(전진) | MEMBER |
| `AGENT_CONNECTED`·`AGENT_DISCONNECTED` | 에이전트(전진 -178) | AGENT_SESSION |

**작업 유형 정하기.** 활동 유형은 보고된 `TaskEvent` 이름이 아니라 **실제 전이 결과**(바뀐 뒤 상태·대기 사유)로 정한다. 변경분(delta)이 있는 `COMPLETED` 보고는 작업을 `WAITING(REVALIDATION)`으로 보내는데, 이벤트 이름만 따르면 재검증을 기다리는 작업이 타임라인에 완료로 보인다.

| 보고·원인 | 실제 전이 | 활동 유형 | 행위자 |
| --- | --- | --- | --- |
| START | → IN_PROGRESS | `TASK_STARTED` | 보고자(`CallerContext`) |
| RESUME | WAITING → IN_PROGRESS | `TASK_RESUMED` | 보고자 |
| WAIT | → WAITING | `TASK_WAITING` | 보고자 |
| COMPLETED, delta 없음 | → COMPLETED | `TASK_COMPLETED` | 보고자 |
| COMPLETED, delta 있음 | → WAITING(REVALIDATION) | `TASK_COMPLETION_REPORTED`(완료 보고, 재검증 대기) | 보고자 |
| delta 검증 통과(S4) | WAITING → COMPLETED | `TASK_COMPLETED` | `SystemActor.SYSTEM` |
| CANCEL | → CANCELLED | `TASK_CANCELLED` | 보고자 |
| 같은 보고 재전송(멱등 — 이벤트 발행 전에 반환) | 없음 | 남기지 않음 | — |

- 시스템이 진행 중 작업을 `WAITING(REVALIDATION)`으로 내리는 전이([04 문서](04-task-lifecycle.md) §2.2)는 작업 활동을 따로 남기지 않는다. 각각 그 원인이 된 행위의 활동이 대신한다.
  - S1 설계 발행 → `CHANGE_PROPOSAL_PUBLISHED` 활동 + 담당자에게 `REVALIDATION_REQUIRED` 알림
  - S2 진행 중 계획 변경(TSK-A04) → `TASK_PLAN_REVISED`
  - S5 담당자 인계(TSK-A06·MEM-A09·A10) → `TASK_ASSIGNEE_CHANGED`
- `TASK_COMPLETED`는 작업이 실제로 COMPLETED가 된 순간에만 남긴다. 그래서 delta가 있는 작업에는 `TASK_COMPLETION_REPORTED`와 `TASK_COMPLETED` 두 건이 남는다.
- 작업 활동의 키는 `task:{id}:lock:{저장·flush 뒤 잠금 버전}:{동작}`이다. 상태 보고는 `TaskEvent` 이름을, 그 밖은 `PLAN_REVISED`처럼 동작 이름을 쓴다(`TASK_CREATED`는 `task:{id}:created`).
  - **예외 — 담당자 변경**: `TaskHandoverService.reassign`은 저장을 호출측에 맡기므로(`handOver`는 루프 뒤 `saveAllAndFlush`) 기록 시점에 잠금 버전이 아직 오르지 않았다. 그래서 `task:{id}:lock:{변경 전 잠금 버전}:ASSIGNEE_CHANGED`(알림은 끝에 `:to:{계정}`)를 쓴다. 동작 이름이 달라 다른 작업 키와 겹치지 않고, 담당자 변경마다 버전이 오르므로 다음 변경과도 겹치지 않는다. S3 완료 보고는 `task:{id}:lock:{n}:COMPLETED`, S4는 작업을 COMPLETED로 저장한 뒤의 버전으로 `task:{id}:lock:{m}:COMPLETED`를 쓴다. 작업 행이 바뀔 때마다 `@Version`이 올라가므로 m > n이라 두 키는 겹치지 않는다.

**검증 완료(`VALIDATION_FINISHED`) 정하기.**

| 항목 | 규칙 |
| --- | --- |
| 언제 | 검증이 끝 상태(PASS·NEEDS_INPUT·NEEDS_REVIEW·ERROR)가 된 순간, 그 결과를 저장하는 같은 트랜잭션에서 |
| 남기지 않는 것 | NOT_RUN·RUNNING(아직 안 끝남), STALE(무효화 — 원인이 된 행위의 활동이 대신함), 시도 번호가 `current_attempt`와 달라 버려진 늦은 결과 |
| 행위자 | 요청 처리 안에서 규칙만으로 바로 끝나면 요청자의 `CallerContext`, 워커가 끝내면 `SystemActor.SYSTEM` |
| `result` | PASS만 `SUCCESS`. NEEDS_INPUT·NEEDS_REVIEW·ERROR는 `FAILURE`(어떤 상태였는지는 검증 상세에서 본다) |
| 키 | `validation:{id}:finished:{attempt}` — ERROR 뒤 재실행하면 같은 검증이 다시 끝나므로 시도 번호로 구분한다 |
| 보조 참조 | `task_id`를 함께 넣는다(작업별 타임라인 필터) |

## 7. 구현에 따라오는 변경

| 대상 | 변경 | 티켓 |
| --- | --- | --- |
| `notification` | `document_id` 컬럼 추가 | -181 |
| `activity_event` | `actor_kind` CHECK에 `BEE` 추가, `agent_session_id`·`task_id`·`change_proposal_id`·`baseline_id`·`result` 저장(필터 대상은 인덱스 가능한 컬럼), `occurred_at` 기본값 `clock_timestamp()` | -182 |
| MON-A04 | 정렬·cursor를 id 내림차순으로, 응답에 `target_type`·`target_id` | -183 |
| `TaskStateChangedEvent`·`TaskStateService` | 이벤트의 `actorAccountId`를 `CallerContext`로 바꾸고, 발행부(`report`)가 받은 `caller`를 그대로 담는다. 바뀐 뒤 상태(`to`)는 이미 있으니 대기 사유(`waitingReason`)를 더해, 둘로 유형을 정한다(6.2 "작업 유형 정하기") | -64(변준성) |
| `TaskStateChangedListener` | `AFTER_COMMIT` 로그 → `BEFORE_COMMIT`에서 `ActivityRecorder` 호출 | -64(변준성) |
| 검증 결과 반영 | 끝난 검증마다 `VALIDATION_FINISHED`(6.2 "검증 완료 정하기"). S4면 같은 트랜잭션에서 작업을 COMPLETED로 바꾸고 `recordBy(SYSTEM, TASK_COMPLETED)` | -159(변준성) |
| 설계 발행 수신·무효화(S1) | 발행 트랜잭션 안에서, 발행으로 검증이 STALE이 됐거나 WAITING(REVALIDATION)이 된 작업의 담당자에게 작업마다 한 건씩 `REVALIDATION_REQUIRED` 알림(본인 포함). S1은 IN_PROGRESS만 WAITING으로 바꾸고 READY 등은 검증만 STALE로 두는데, READY 작업도 새 PASS가 있어야 시작할 수 있으므로 함께 보낸다. STALE 대상은 작업 상태로 거르지 않으므로([04 문서](04-task-lifecycle.md) S1) 완료·취소 작업은 여기서 뺀다(이 제외 기준은 MCP-03 `affected_task_ids`와 같다 — 대상 선정 기준까지 같은 것은 아니다). 담당자가 없는 작업(`assignee_member_id` NULL)은 건너뛴다. 키는 발행한 제안에 묶어 `task:{작업 ID}:revalidation:{제안 ID}:to:{계정}`(첫 조각은 작업 도메인 몫, 5절) — 여러 작업을 한 번에 바꾸는 일괄 UPDATE면 `@Version`이 오르지 않아 잠금 버전 키를 쓸 수 없다 | -163(변준성) |
| `TaskPlanService` | `createTask` → `TASK_CREATED`, `addRevision` → `TASK_PLAN_REVISED`(S2 재검증 대기 전환 포함) | -64(변준성) |
| `TaskHandoverService.reassign`·`handOver` | `CallerContext` 인자를 추가하고, `reassign` 안에서 담당자가 실제로 바뀐 작업마다 `TASK_ASSIGNEE_CHANGED` 활동 + 새 담당자에게 `TASK_ASSIGNED` 알림(본인 포함). 키는 변경 전 잠금 버전(6.2 예외). 같은 담당자면 아무것도 남기지 않는다. 담당자 변경(TSK-A06, `TaskAssigneeService`)과 팀원 제외·나가기(`ProjectMemberService`)가 모두 `reassign`을 거치므로 호출 지점은 이 한 곳이다. 두 호출부도 받은 `caller`를 넘기게 바꾼다 | 전진(-212 코드 작성자, -182와 함께) |
| 코드 TODO 7곳 | 변경 제안 4(생성·거두기·검토 시작/반려 — `ProposalCommandService`, 승인·발행 — `ProposalPublicationService`)·의견 1(`ChangeDiscussionService`)·문서 검토 2(`DocumentReviewService` 제출·결정) → 이 계약으로 호출. 모두 `@Transactional` 또는 `TransactionTemplate` 안이라 그 자리에서 부르면 된다 | -237·-217(오서현) |

## 8. 일정

- **11/3**: 이 계약 합의. 같은 날 -181·-182 첫 커밋으로 입구 인터페이스와 요청 record만 먼저 dev에 넣는다 — 호출측이 컴파일되게.
- **11/5**: 저장까지 동작(-181·-182 최소 구현). 조회 API(NTF-A01·A02, MON-A04)는 그 뒤.
- 그 전에 호출 코드를 쓰는 쪽은 이 문서 필드대로 요청을 만들고 호출부만 TODO로 둔다.

## 9. 합의 기록

| 항목 | 오서현 | 변준성 |
| --- | --- | --- |
| 1. 같은 트랜잭션 저장(D7·Q03 수정) | | |
| 3. 중복 키 규칙 | | |
| 6. 유형표(본인 소유 행) | | |
