import { expect, it } from 'vitest'
import type { Notification } from '@/api/contracts/common'
import { foldUnread } from './foldUnread'

const note = (
  id: string,
  target_type: Notification['target_type'],
  target_id: string,
): Notification => ({
  id,
  project_id: '101',
  type: 'ANY',
  title: `알림 ${id}`,
  body: '',
  target_type,
  target_id,
  created_at: '2026-11-18T00:00:00Z',
})

it('할 일 줄과 대상이 같은 알림은 그 줄에 접고 나머지는 새 소식으로 보낸다', () => {
  const unread = [
    note('1', 'CHANGE_PROPOSAL', '71'),
    note('2', 'MEETING', '901'),
    note('3', 'MEETING', '901'),
    note('4', 'CHANGE_PROPOSAL', '72'),
    note('5', 'DOCUMENT_VERSION', '301'),
  ]
  const { byRow, news } = foldUnread(unread, [
    { key: 'review', targets: ['CHANGE_PROPOSAL:71'] },
    { key: 'meeting-901', targets: ['MEETING:901'] },
    { key: 'ready', targets: ['TASK:501'] },
  ])
  expect(byRow.get('review')?.map((item) => item.id)).toEqual(['1'])
  expect(byRow.get('meeting-901')?.map((item) => item.id)).toEqual(['2', '3'])
  expect(byRow.get('ready')).toEqual([])
  expect(news.map((item) => item.id)).toEqual(['4', '5'])
})

it('할 일 줄이 없으면(시작 가이드가 떠 있을 때) 모든 알림이 새 소식이다', () => {
  const unread = [note('1', 'MEETING', '901'), note('2', 'TASK', '501')]
  expect(foldUnread(unread, []).news).toEqual(unread)
})

it('두 줄이 같은 대상을 가리켜도 알림은 앞 줄 하나에만 접는다', () => {
  const { byRow, news } = foldUnread(
    [note('1', 'TASK', '501')],
    [
      { key: 'unassigned', targets: ['TASK:501'] },
      { key: 'ready', targets: ['TASK:501'] },
    ],
  )
  expect(byRow.get('unassigned')).toHaveLength(1)
  expect(byRow.get('ready')).toEqual([])
  expect(news).toEqual([])
})
