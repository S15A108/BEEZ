import { expect, it } from 'vitest'
import { presenceReportSchema, presenceResponseSchema } from './schemas'

it('보고는 허용된 위치와 메뉴만 받고 모두 생략해도 된다', () => {
  expect(presenceReportSchema.safeParse({}).success).toBe(true)
  expect(
    presenceReportSchema.safeParse({ zone: 'dev', seat: 'A-3', panel: 'tasks' })
      .success,
  ).toBe(true)
  expect(presenceReportSchema.safeParse({ zone: 'desk' }).success).toBe(false)
  expect(presenceReportSchema.safeParse({ panel: 'nope' }).success).toBe(false)
  expect(presenceReportSchema.safeParse({ seat: 'a b' }).success).toBe(false)
  expect(presenceReportSchema.safeParse({ seat: 'A'.repeat(17) }).success).toBe(
    false,
  )
})

it('서버가 생략하는 필드가 없어도 접속 현황을 받는다', () => {
  const parsed = presenceResponseSchema.parse({
    items: [
      {
        member_id: '22',
        online: true,
        last_seen_at: '2026-11-11T03:00:00Z',
        zone: 'dev',
        seat: 'A-3',
        panel: 'tasks',
      },
      { member_id: '23', online: false, last_seen_at: '2026-11-11T01:00:00Z' },
      { member_id: '24', online: false },
    ],
  })
  expect(parsed.items).toHaveLength(3)
  expect(parsed.items[2]).not.toHaveProperty('last_seen_at')
})

it('서버가 모르는 위치를 보내도 목록 전체가 깨지지 않는다', () => {
  expect(
    presenceResponseSchema.safeParse({
      items: [{ member_id: '1', online: true, zone: 'rooftop' }],
    }).success,
  ).toBe(true)
})
