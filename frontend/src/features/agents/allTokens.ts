import { agentsApi } from '@/api/services'
import type { AgentToken } from '@/api/contracts/common'

// 내 토큰 목록(AGT-A02)은 폐기한 토큰도 id 내림차순으로 준다. 폐기 토큰이 쌓여도 활성 토큰이 빠지지 않게 끝까지 이어 받는다.
// projectId를 빼면 내 모든 프로젝트의 토큰을 받는다(회원 설정).
export async function allTokens(projectId?: string) {
  const tokens: AgentToken[] = []
  let cursor: string | undefined
  do {
    const page = await agentsApi.myTokens({
      ...(projectId ? { project_id: projectId } : {}),
      limit: 100,
      cursor,
    })
    tokens.push(...page.items)
    cursor = page.has_more ? page.next_cursor : undefined
  } while (cursor)
  return tokens
}
