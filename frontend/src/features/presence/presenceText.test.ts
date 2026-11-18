import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { presenceReportOf, presenceView } from './presenceText'

beforeEach(() => {
  vi.useFakeTimers({ now: new Date('2026-11-11T04:00:00Z'), toFake: ['Date'] })
})
afterEach(() => {
  vi.useRealTimers()
})

it('사무실 안에서는 개발팀 위치와 열어 둔 메뉴를 보고한다', () => {
  expect(presenceReportOf({ outdoor: false, pane: 'tasks' })).toEqual({
    zone: 'dev',
    panel: 'tasks',
  })
  expect(presenceReportOf({ outdoor: false, pane: null })).toEqual({
    zone: 'dev',
  })
})

it('실외 산책 중에는 사무실 안 위치를 보내지 않는다', () => {
  expect(presenceReportOf({ outdoor: true, pane: null })).toEqual({})
})

it('접속 중이면 위치 이름을 보이고 모르는 위치는 접속 중으로 둔다', () => {
  expect(
    presenceView({
      member_id: '1',
      online: true,
      last_seen_at: '2026-11-11T03:59:50Z',
      zone: 'meeting',
    }),
  ).toEqual({ online: true, place: '회의실' })
  expect(presenceView({ member_id: '1', online: true, zone: 'new' })).toEqual({
    online: true,
    place: '접속 중',
  })
})

it('오프라인이면 마지막 접속을 몇 시간 전으로 보이고 위치는 비운다', () => {
  expect(
    presenceView({
      member_id: '2',
      online: false,
      last_seen_at: '2026-11-11T03:00:00Z',
      zone: 'dev',
    }),
  ).toEqual({ online: false, detail: '오프라인 · 1시간 전' })
})

it('접속한 적 없는 팀원은 접속 점만 오프라인이고 보일 글이 없다', () => {
  expect(presenceView({ member_id: '3', online: false })).toEqual({
    online: false,
    detail: undefined,
  })
})

it('아직 받지 못했으면 점도 글도 없다', () => {
  expect(presenceView(undefined)).toEqual({})
})
