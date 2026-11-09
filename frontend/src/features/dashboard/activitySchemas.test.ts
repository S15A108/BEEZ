/// <reference types="node" />
import { webcrypto } from 'node:crypto'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { activitySchema } from '@/api/contracts/common'
import { authApi, dashboardApi } from '@/api/services'
import {
  DEMO_EMAIL,
  DEMO_PASSWORD,
  resetMockApi,
  setupMockApi,
} from '@/mocks/setup'
import { activitiesQuerySchema } from './schemas'

describe('활동 조회 요청 스키마', () => {
  it('변경 제안 id·행위자 필터를 걸러 내지 않고 기존 값도 그대로 둔다', () => {
    expect(
      activitiesQuerySchema.parse({
        change_proposal_id: '71',
        actor_type: 'BEE',
        type: 'CHANGE_PROPOSAL_COMMENTED',
        cursor: 'mock-after-1010',
        limit: 10,
      }),
    ).toEqual({
      change_proposal_id: '71',
      actor_type: 'BEE',
      type: 'CHANGE_PROPOSAL_COMMENTED',
      cursor: 'mock-after-1010',
      limit: 10,
    })
  })

  it('제안 key(CP-012)나 모르는 행위자 종류는 거부한다', () => {
    expect(
      activitiesQuerySchema.safeParse({ change_proposal_id: 'CP-012' }).success,
    ).toBe(false)
    expect(
      activitiesQuerySchema.safeParse({ actor_type: 'ROBOT' }).success,
    ).toBe(false)
  })
})

describe('활동 응답 스키마', () => {
  const base = {
    id: '1',
    type: 'CHANGE_PROPOSAL_REVIEW_REQUESTED',
    result: 'SUCCESS',
    created_at: '2026-10-23T03:00:00Z',
  }

  it('행위자·대상 필드를 보존한다', () => {
    const parsed = activitySchema.parse({
      ...base,
      actor_type: 'AGENT',
      actor_account_id: '13',
      actor_name: '민수 에이전트',
      agent_session_id: '55',
      change_proposal_id: '71',
      target_type: 'CHANGE_PROPOSAL',
      target_id: '71',
    })
    expect(parsed).toMatchObject({
      actor_type: 'AGENT',
      actor_name: '민수 에이전트',
      agent_session_id: '55',
      target_type: 'CHANGE_PROPOSAL',
      target_id: '71',
    })
  })

  it('이름 없는 비비·시스템 응답도 통과하고, 행위자 종류가 없으면 거부한다', () => {
    expect(
      activitySchema.safeParse({ ...base, actor_type: 'BEE' }).success,
    ).toBe(true)
    expect(
      activitySchema.safeParse({ ...base, actor_type: 'SYSTEM' }).success,
    ).toBe(true)
    expect(activitySchema.safeParse(base).success).toBe(false)
  })

  it('모르는 result 값은 그대로 통과시킨다', () => {
    expect(
      activitySchema.parse({ ...base, actor_type: 'HUMAN', result: 'PARTIAL' })
        .result,
    ).toBe('PARTIAL')
  })
})

describe('mock MON-A04', () => {
  let restore: () => void
  beforeEach(async () => {
    Object.defineProperty(globalThis.crypto, 'subtle', {
      configurable: true,
      value: webcrypto.subtle,
    })
    localStorage.clear()
    resetMockApi()
    restore = setupMockApi()
    await authApi.login({ email: DEMO_EMAIL, password: DEMO_PASSWORD })
  })
  afterEach(() => restore())

  it('변경 제안·행위자 필터를 적용하고 id 내림차순 두 페이지로 나눈다', async () => {
    const first = await dashboardApi.activities('101', {
      change_proposal_id: '71',
      limit: 10,
    })
    expect(first.items).toHaveLength(10)
    expect(first.has_more).toBe(true)
    expect(first.items.every((item) => item.change_proposal_id === '71')).toBe(
      true,
    )
    const second = await dashboardApi.activities('101', {
      change_proposal_id: '71',
      limit: 10,
      cursor: first.next_cursor,
    })
    expect(second.items).toHaveLength(2)
    expect(second.has_more).toBe(false)

    const bee = await dashboardApi.activities('101', {
      change_proposal_id: '71',
      actor_type: 'BEE',
    })
    expect(bee.items.map((item) => item.actor_type)).toEqual(['BEE'])
    const other = await dashboardApi.activities('101', {
      change_proposal_id: '999',
    })
    expect(other.items).toEqual([])
  })
})
