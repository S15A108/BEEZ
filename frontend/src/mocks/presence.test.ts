import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { presenceResponseSchema } from '@/features/presence/schemas'
import { presenceRoutes, resetMockPresence } from './presence'
import { createMockDatabase } from './state'
import type { MockContext } from './helpers'

beforeEach(() => {
  vi.useFakeTimers({ now: new Date('2026-11-11T03:00:00Z'), toFake: ['Date'] })
  resetMockPresence()
})
afterEach(() => {
  vi.useRealTimers()
})

function contextOf(body: Record<string, unknown> = {}): MockContext {
  const database = createMockDatabase()
  const project = database.projects[0]
  return {
    database,
    body,
    params: { id: project.project.id },
    query: new URLSearchParams(),
    project,
    member: project.members[0],
  }
}

it('보고하면 204이고 목록에 나는 접속 중, 보고하지 않은 팀원은 접속한 적 없음으로 나온다', () => {
  const context = contextOf({ zone: 'meeting', panel: 'chat' })
  expect(presenceRoutes('MON-A06', context)?.status).toBe(204)

  const list = presenceResponseSchema.parse(
    presenceRoutes('MON-A07', context)?.data,
  )
  expect(list.items).toHaveLength(context.project!.members.length)
  const mine = list.items.find((item) => item.member_id === context.member!.id)
  expect(mine).toMatchObject({ online: true, zone: 'meeting', panel: 'chat' })
  const others = list.items.filter(
    (item) => item.member_id !== context.member!.id,
  )
  expect(others.every((item) => !item.online && !item.last_seen_at)).toBe(true)
})

it('45초가 지나면 오프라인이 되고 마지막 위치와 시각이 남는다', () => {
  const context = contextOf({ zone: 'dev' })
  presenceRoutes('MON-A06', context)
  vi.setSystemTime(new Date('2026-11-11T03:00:45Z'))

  const mine = presenceResponseSchema
    .parse(presenceRoutes('MON-A07', context)?.data)
    .items.find((item) => item.member_id === context.member!.id)
  expect(mine).toMatchObject({
    online: false,
    zone: 'dev',
    last_seen_at: '2026-11-11T03:00:00.000Z',
  })
})

it('서버처럼 허용되지 않은 위치는 거절한다', () => {
  expect(() => presenceRoutes('MON-A06', contextOf({ zone: 'desk' }))).toThrow()
})
