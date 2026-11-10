import { BACKGROUND_POLL_MS } from '@/lib/pollIntervals'
import { queryOptions } from '@tanstack/react-query'
import { unlessMissing } from '@/api/error'
import { readAllPages } from '@/api/readAllPages'
import {
  dashboardApi,
  documentsApi,
  membersApi,
  projectsApi,
  tasksApi,
} from '@/api/services'
import { onboardingFrom } from '@/features/projects/onboardingFrom'

// 워크스페이스 첫 화면 데이터. 초기 설정 현황(PRJ-A06)이 서버에 없으면 대체 계산이 내 계정의 에이전트 연결을
// 보므로, 계정 id를 키에 넣고 세션이 준비된 뒤에만 부른다(늦게 온 세션으로 my_agent_connected가 false로 굳지 않게).
export function sceneQuery(projectId: string, accountId?: string) {
  return queryOptions({
    queryKey: ['projects', projectId, 'scene', accountId],
    enabled: !!accountId,
    queryFn: async () => {
      const [project, members, tasks, onboarding] = await Promise.all([
        projectsApi.get(projectId),
        // 팀원은 마지막 페이지까지 읽는다. 첫 20명만 쓰면 21번째 이후 팀원(나일 수도 있다)이 사무실·HUD에서 빠진다
        // (PR #72 재전수 SECOND-AMP-04).
        readAllPages((cursor) =>
          membersApi.list(projectId, { cursor, limit: 100 }),
        ),
        // 작업도 마지막 페이지까지 읽는다. 최신 100개만 쓰면 그 밖에 있는 내 시작 대기 작업이 HUD에서 빠진다
        // (PR #72 5차 UI-01).
        readAllPages((cursor) =>
          tasksApi.list(projectId, { cursor, limit: 100 }),
        ),
        unlessMissing(projectsApi.onboarding(projectId)),
      ])
      return {
        project,
        members,
        tasks,
        onboarding:
          onboarding ??
          onboardingFrom(
            project,
            members.items,
            (await documentsApi.list(projectId, { limit: 100 })).items,
            accountId,
          ),
      }
    },
  })
}

// 사무실 에이전트의 자리(D37): 작업 현황(MON-A05). 첫 화면 조회에 묶지 않아 실패해도 첫 화면은 열리고(PR #128 리뷰),
// 열어 둔 사무실에도 다른 사람·에이전트의 상태 변화가 보이게 30초마다 다시 부른다. 다시 부르다 실패하면 TanStack이
// 마지막으로 받은 값을 그대로 두고, 한 번도 받지 못했으면 data가 없어 화면은 연결 여부로 어림한다. 서버에 없는
// API(404·405·501)면 null로 두고 더 부르지 않는다.
// ponytail: 30초 폴링. 서버에 상태 변경 알림이 생기면 그 신호로 이 쿼리를 무효화한다.
export function agentWorkQuery(projectId: string, accountId?: string) {
  return queryOptions({
    queryKey: ['projects', projectId, 'agent-work', accountId],
    enabled: !!accountId,
    queryFn: async () =>
      (
        await unlessMissing(
          readAllPages((cursor) =>
            dashboardApi.agentWork(projectId, { cursor, limit: 100 }),
          ),
        )
      )?.items ?? null,
    refetchInterval: (query) =>
      query.state.data === null ? false : BACKGROUND_POLL_MS,
  })
}
