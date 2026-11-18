import { expect, it } from 'vitest'
import { onboardingFrom } from './onboardingFrom'

it('초기 설정 현황 API가 없으면 프로젝트·팀원·문서 목록으로 같은 모양을 만든다', () => {
  const onboarding = onboardingFrom(
    { current_baseline: undefined },
    [
      { account_id: '11', agent_connected: true },
      { account_id: '12', agent_connected: false },
    ],
    [
      { kind: 'PLAN', approved_version: undefined },
      {
        kind: 'ERD',
        approved_version: {} as never,
      },
    ],
    '11',
  )
  expect(onboarding).toEqual({
    required_documents: [
      { kind: 'PLAN', uploaded: true, approved: false },
      { kind: 'ERD', uploaded: true, approved: true },
      { kind: 'REQUIREMENTS', uploaded: false, approved: false },
      { kind: 'API_SPEC', uploaded: false, approved: false },
    ],
    baseline_published: false,
    member_count: 2,
    my_agent_connected: true,
  })
})
