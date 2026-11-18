import { expect, it } from 'vitest'
import type { Meeting } from '@/api/contracts/common'
import { openMeetingCount } from './useMeetingList'

const meeting = (
  id: string,
  status: Meeting['status'],
  participants: Meeting['participants'],
): Meeting => ({
  id,
  project_id: '101',
  title: `회의 ${id}`,
  status,
  participants,
  created_at: '2026-10-28T01:40:00Z',
})

it('내가 참여 중인 진행 중 회의만 센다', () => {
  const meetings = [
    meeting('1', 'OPEN', [{ member_id: '11', status: 'JOINED' }]),
    // status가 없으면 진행 중으로 본다
    meeting('2', undefined, [{ member_id: '11', status: 'INVITED' }]),
    meeting('3', 'CLOSED', [{ member_id: '11', status: 'JOINED' }]),
    meeting('4', 'OPEN', [{ member_id: '12', status: 'JOINED' }]),
    meeting('5', 'OPEN', [{ member_id: '11', status: 'DECLINED' }]),
  ]
  expect(openMeetingCount(meetings, '11')).toBe(2)
  expect(openMeetingCount(meetings, undefined)).toBe(0)
})
