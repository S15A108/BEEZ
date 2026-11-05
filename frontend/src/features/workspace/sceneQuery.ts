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
      const [project, members, tasks, onboarding, agentWork] =
        await Promise.all([
          projectsApi.get(projectId),
          // 팀원은 마지막 페이지까지 읽는다. 첫 20명만 쓰면 21번째 이후 팀원(나일 수도 있다)이 사무실·HUD에서 빠진다
          // (PR #72 재전수 SECOND-AMP-04).
          readAllPages((cursor) =>
            membersApi.list(projectId, { cursor, limit: 100 }),
          ),
          tasksApi.list(projectId, { limit: 100 }),
          unlessMissing(projectsApi.onboarding(projectId)),
          // 사무실 에이전트의 자리(D37): 작업 현황(MON-A05). 없는 API면 undefined로 두고 연결 여부로 어림한다.
          unlessMissing(
            readAllPages((cursor) =>
              dashboardApi.agentWork(projectId, { cursor, limit: 100 }),
            ),
          ),
        ])
      return {
        project,
        members,
        tasks,
        agentWork: agentWork?.items,
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
