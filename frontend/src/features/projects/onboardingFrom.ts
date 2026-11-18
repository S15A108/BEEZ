import type { Document, Member } from '@/api/contracts/common'
import type { GetResponse, OnboardingResponse } from './schemas'

const REQUIRED_KINDS = ['PLAN', 'ERD', 'REQUIREMENTS', 'API_SPEC'] as const

// 초기 설정 현황(PRJ-A06)이 서버에 아직 없을 때, 이미 있는 API(프로젝트·팀원·문서 목록)로 같은 모양을 만든다.
export function onboardingFrom(
  project: Pick<GetResponse, 'current_baseline'>,
  members: Pick<Member, 'account_id' | 'agent_connected'>[],
  documents: Pick<Document, 'kind' | 'approved_version'>[],
  accountId?: string,
): OnboardingResponse {
  return {
    required_documents: REQUIRED_KINDS.map((kind) => {
      const document = documents.find((item) => item.kind === kind)
      return {
        kind,
        uploaded: Boolean(document),
        approved: Boolean(document?.approved_version),
      }
    }),
    baseline_published: Boolean(project.current_baseline),
    member_count: members.length,
    my_agent_connected: members.some(
      (member) => member.account_id === accountId && member.agent_connected,
    ),
  }
}
