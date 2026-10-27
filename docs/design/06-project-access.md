# 프로젝트 권한 검사 사용법 (BE용)

작성: 전진 · Jira: S15P31A108-170 · 기준: [구현 순서·접근 거부 기준](05-build-order-and-access-policy.md) 참고 2

모든 프로젝트 API는 **서비스 메서드 첫 줄**에서 `ProjectAccessChecker`를 호출한다. 컨트롤러·필터에서 따로 검사하지 않는다.
호출자는 `CallerContext`(계정·토큰·사람/에이전트)로 서비스에 **인자로 넘긴다**. REST와 MCP 어댑터가 같은 서비스를 부르기 위해서다.
코드는 `backend/src/main/java/com/beez/backend/common/access/`(권한 검사)와 `common/auth/`(호출자)에 있다.

## 1. 호출

컨트롤러는 메서드 인자에 `CallerContext`를 두기만 하면 된다. 로그인한 세션(또는 MCP 토큰)에서 자동으로 채워진다(`SecurityCallerResolver`). MCP 어댑터처럼 컨트롤러가 아닌 곳은 `SecurityCallerResolver.current()`로 얻는다.

```java
@GetMapping("/{task_id}")
public TaskDetail get(CallerContext caller, @PathVariable("id") long projectId, @PathVariable("task_id") long taskId) {
    return taskService.get(caller, projectId, taskId);
}
```

```java
@Service
public class TaskService {

    private final ProjectAccessChecker access;
    private final TaskRepository tasks;

    public TaskDetail get(CallerContext caller, long projectId, long taskId) {
        ProjectActor actor = access.requireMember(caller, projectId);  // ① 멤버인가 (팀장 포함)
        Task task = tasks.findByIdAndProjectId(taskId, projectId)      // ② ID와 프로젝트 ID를 함께 조건으로
                .orElseThrow(() -> AccessErrors.notFound("cross_project_or_missing task=" + taskId));
        ...
    }

    public void cancel(CallerContext caller, long projectId, long taskId) {
        ProjectActor actor = access.requireMember(caller, projectId);
        actor.requireHuman();                                          // 에이전트면 403
        ...
    }

    public void report(CallerContext caller, long projectId, long taskId, ...) {
        ProjectActor actor = access.requireMember(caller, projectId);
        Task task = ...;
        actor.requireMemberId(task.getAssigneeMemberId());             // 담당자가 아니면 403 (팀장도)
        ...
    }

    public void approve(CallerContext caller, long projectId, ...) {
        ProjectActor actor = access.requireHumanLeader(caller, projectId);  // 팀장 아니면 403, 팀장 토큰이어도 에이전트면 403
        ...
    }
}
```

| 메서드 | 쓰는 곳 | 실패 |
| --- | --- | --- |
| `requireMember(caller, projectId)` | MEMBER API | 비로그인·탈퇴 계정의 세션 401, 폐기·남의 토큰 401 `token_revoked`, 다른 프로젝트 토큰·프로젝트 없음·삭제·비멤버 404 |
| `requireMember(caller, projectId, LEADER)` | LEADER API (에이전트도 허용하는 경우) | 위 + 팀장 아님 403 |
| `requireHumanLeader(caller, projectId)` | 승인·발행·팀 관리·담당자 변경 (00-common 2절 "사람 세션 필수") | 위 + 팀장 아님 403, 에이전트 403 |
| `requireUser(caller)` | 프로젝트와 무관한 본인 API(`/api/me` 등) | 비로그인·에이전트 401 |
| `isActiveMember(memberId, projectId)` | 담당자 지정·변경 대상 확인 | — (true/false) |
| `actor.requireHuman()` | 승인·발행·팀 관리, 에이전트 CANCEL | 에이전트 403 |
| `actor.requireMemberId(memberId[, 메시지])` | TASK_OWNER (담당자 칸은 **멤버 ID**) | 다른 멤버·담당자 없음 403 |
| `actor.accountId()`와 비교 | RECORD_AUTHOR (작성자 칸은 **계정 ID**) | 아래 "작성자 검사" |

`requireUser`가 에이전트를 403이 아니라 **401**로 막는 이유: 에이전트는 MCP 전용이라 REST의 USER API를 부를 인증 수단 자체가 없다(05 참고 2의 1번 "에이전트 토큰으로 `/api/**` 호출 → 401"). 이때 문구는 에이전트용(`AGENT_UNAUTHENTICATED_MESSAGE`)이다.

`ProjectActor`에 담긴 값:

| 필드 | 쓰임 |
| --- | --- |
| `accountId` | 계정. 작성자 기록·판정(`author_account_id`, `created_by_account_id` 등 작성자 칸은 모두 계정 ID) |
| `memberId` | 작업 담당자 판정(`task.assignee_member_id`) |
| `projectId` | 검사를 통과한 프로젝트 |
| `role` | `LEADER` / `MEMBER` |
| `actorType` | `HUMAN`(웹 세션) / `AGENT`(MCP 토큰) |
| `agentTokenId` | 에이전트면 토큰 ID, 사람이면 `null` |

## 2. 직접 던지는 거부

자기 객체의 소속을 확인할 때는 `AccessErrors`를 쓴다. 응답 code·message가 이 파일 한 곳에서 정해지므로 문자열을 직접 쓰지 않는다.

| 상황 | 던질 것 | 응답 |
| --- | --- | --- |
| 다른 프로젝트 객체, 없는 ID | `AccessErrors.notFound("cross_project ...")` | 404 `not_found` |
| 남의 PRIVATE 기록 | `AccessErrors.notFound("private_record ...")` | 404 `not_found` |
| 같은 프로젝트 안 역할·소유 조건 | `AccessErrors.forbidden("로그용 사유", "사용자 메시지")` | 403 `forbidden` |

`reason` 인자는 **서버 로그에만** 남는다(`com.beez.access` 로거, 요청 ID와 함께). 응답 메시지는 [05 문서](05-build-order-and-access-policy.md) 합의 3번대로 나뉜다.
- **404는 하나로 통일**한다. 사유와 관계없이 "리소스가 없거나 프로젝트 소속·접근 권한이 없음"이다(존재를 숨김).
- **403은 사유 메시지**를 담는다(예: "팀장만 할 수 있습니다", "담당자만 계획을 바꿀 수 있습니다"). 같은 프로젝트 멤버에게만 가는 응답이라 숨기지 않는다. FE는 메시지가 아니라 code `forbidden`으로 분기한다.

**작성자 검사 (RECORD_AUTHOR).** 작성자 칸(`context_record`·`change_comment`·`meeting_entry`의 `author_account_id`, `task_plan_revision.created_by_account_id`)은 모두 **계정 ID**라서 `actor.accountId()`와 비교한다. `memberId`와 섞지 않는다.
- 남의 PRIVATE 기록 **조회**는 403이 아니라 **404**다. 작성자 비교를 조회 쿼리 조건으로 건다: `WHERE ... AND (visibility = 'SHARED' OR author_account_id = :accountId)`. 검색 결과·개수에서도 빠진다.
- 403 작성자 검사는 SHARED처럼 **이미 보이는 기록을 수정**할 때만 쓴다.
  ```java
  if (record.getAuthorAccountId() != actor.accountId()) {
      throw AccessErrors.forbidden("not_author record=" + record.getId(), "작성자만 수정할 수 있습니다");
  }
  ```
  아직 사용처가 없어 `ProjectActor`에 전용 메서드를 두지 않았다. 첫 사용처(-95·-110)가 생길 때 필요하면 추가한다.
-156 초안의 `NotFound.because(...)`와 `Membership`은 없어졌다. 각각 `AccessErrors.notFound(...)`와 `ProjectActor`를 쓴다.

목록·검색에서 다른 프로젝트 ID를 필터로 받으면 오류 없이 0건이다. 권한 조건은 페이지·집계 **전**에 쿼리에 넣는다.

## 3. 테스트

**서비스 단위 테스트** — `ProjectAccessChecker`를 mock하고 `TestActors`로 주체를 만든다.

```java
when(access.requireMember(any(), eq(PROJECT_ID))).thenReturn(TestActors.member(PROJECT_ID, 7L));
when(access.requireMember(any(), eq(PROJECT_ID))).thenThrow(AccessErrors.notFound("not_member"));
```

서비스를 직접 부를 때 호출자는 `CallerContext.human(accountId)` / `CallerContext.agent(accountId, tokenId)`로 만든다.
에이전트는 REST로 부를 수 없으므로(401) 에이전트 규칙은 통합 테스트에서도 서비스를 직접 불러 확인한다(예: `task/AccessRulesIntegrationTests`). 옛 임시 헤더(`X-Beez-Account-Id`)는 없어졌다.

**통합 테스트** — `IntegrationTestSupport`를 상속한다. `fixtures()`가 계정·프로젝트·멤버·에이전트 토큰을 SQL로 넣고, `client()`가 로그인 세션을 가진 HTTP 클라이언트를 만든다. 프로젝트·초대 API(-173·-174)가 나오기 전에도 쓸 수 있다.

```java
class TaskIntegrationTest extends IntegrationTestSupport {
    @Test
    void otherProjectTask_is404() {
        var mine = fixtures().projectWithLeader();
        var theirs = fixtures().projectWithLeader();
        ApiClient me = client().login(mine.leaderEmail(), AccessFixtures.PASSWORD);

        assertThat(me.get("/api/projects/" + mine.projectId() + "/tasks/" + theirTaskId).statusCode()).isEqualTo(404);
    }
}
```

실행: `./gradlew test`(빠른 층, Docker 불필요) · `./gradlew integrationTest`(Docker 필요).

## 4. 인증 체인 (참고)

| 경로 | 인증 | 비고 |
| --- | --- | --- |
| `/api/**` | 세션 쿠키 `BEEZ_SESSION` | PUBLIC: `/api/ping`, `/api/auth/register·login·logout`, `/api/auth/password/**`, `/api/invitations/preview` |
| `/mcp/**` | `Authorization: Bearer <프로젝트 토큰>` | 검증 본체는 -175/-176. 그 전에는 모든 토큰 401 |
| 그 밖 | 없음 | `/actuator/health`, `/actuator/info`, Swagger·OpenAPI만 열림 |

에이전트 토큰으로 `/api/**`를 부르면 401이다(에이전트는 MCP 전용). 멤버십·역할은 캐시 없이 매 요청 조회하므로, 제외·위임 직후 다음 요청부터 막힌다. 에이전트 호출은 권한 검사가 토큰 행도 다시 읽어, 필터를 지난 뒤 폐기된 토큰도 막는다.

세션(Q04 제안안): 마지막 요청 후 2시간 만료, `HttpOnly`, `SameSite=Lax`, 운영 `Secure`, CSRF 토큰 없음. 서버 메모리 세션이라 재시작하면 다시 로그인해야 한다.
