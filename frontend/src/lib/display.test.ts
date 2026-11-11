import { describe, expect, it } from 'vitest'
import { activityText, lineRangeText } from './display'

// 공통 계약 6.2 활동 유형 전부(docs/design/10-notification-activity-contract.md)
const CONTRACT_TYPES = [
  'TASK_CREATED',
  'TASK_PLAN_REVISED',
  'TASK_STARTED',
  'TASK_RESUMED',
  'TASK_WAITING',
  'TASK_COMPLETION_REPORTED',
  'TASK_COMPLETED',
  'TASK_CANCELLED',
  'TASK_ASSIGNEE_CHANGED',
  'VALIDATION_FINISHED',
  'DOCUMENT_REGISTERED',
  'DOCUMENT_APPROVED',
  'DOCUMENT_REJECTED',
  'BASELINE_CREATED',
  'CHANGE_PROPOSAL_SUBMITTED',
  'CHANGE_PROPOSAL_WITHDRAWN',
  'CHANGE_PROPOSAL_REVIEW_STARTED',
  'CHANGE_PROPOSAL_REVIEW_REQUESTED',
  'CHANGE_PROPOSAL_COMMENTED',
  'CHANGE_PROPOSAL_APPROVED',
  'CHANGE_PROPOSAL_REJECTED',
  'CHANGE_PROPOSAL_PUBLISHED',
  'MEETING_CLOSED',
  'PROJECT_CREATED',
  'MEMBER_JOINED',
  'MEMBER_LEFT',
  'MEMBER_REMOVED',
  'LEADER_TRANSFERRED',
  'AGENT_CONNECTED',
  'AGENT_DISCONNECTED',
]

describe('activityText', () => {
  it('계약의 활동 유형을 모두 기본 문구가 아닌 한글 해요체로 보인다', () => {
    const fallback = activityText({ type: 'NOT_A_TYPE' })
    for (const type of CONTRACT_TYPES) {
      const text = activityText({ type })
      expect(text, type).not.toBe(fallback)
      expect(text, type).toMatch(/^[가-힣 ·]+요$/)
    }
  })

  it('모르는 유형이 오면 영어 코드 대신 기본 문구를 보인다', () => {
    expect(activityText({ type: 'SOMETHING_NEW' })).toBe('활동을 기록했어요')
  })

  it('팀장 위임은 target_id로 팀원 목록에서 새 팀장을 찾아 이름을 넣는다', () => {
    const members = [
      { id: '21', name: '이팀장' },
      { id: '22', name: '김동료' },
    ]
    expect(
      activityText({ type: 'LEADER_TRANSFERRED', target_id: '22' }, members),
    ).toBe('김동료님에게 팀장을 넘겼어요')
  })

  it('새 팀장이 팀원 목록에 없거나 target_id가 없으면 이름 없이 보인다', () => {
    const members = [{ id: '21', name: '이팀장' }]
    expect(
      activityText({ type: 'LEADER_TRANSFERRED', target_id: '99' }, members),
    ).toBe('팀장을 넘겼어요')
    expect(activityText({ type: 'LEADER_TRANSFERRED' }, members)).toBe(
      '팀장을 넘겼어요',
    )
  })

  it('팀원 제외는 제외된 사람 이름 없이 보인다', () => {
    expect(
      activityText({ type: 'MEMBER_REMOVED', target_id: '22' }, [
        { id: '22', name: '김동료' },
      ]),
    ).toBe('팀원을 제외했어요')
  })
})

// S15P31A108-395: 서버 출처 위치의 L4-L6 표기를 화면의 4~6행 표기로 바꾼다.
it('출처 위치의 줄 범위를 행 표기로 바꾸고 다른 위치는 그대로 둔다', () => {
  expect(lineRangeText('접근 정책 / L4-L6')).toBe('접근 정책 / 4~6행')
  expect(lineRangeText('L13-L16')).toBe('13~16행')
  expect(lineRangeText('승인 설계 / API-01')).toBe('승인 설계 / API-01')
  expect(lineRangeText('4~5행')).toBe('4~5행')
})
