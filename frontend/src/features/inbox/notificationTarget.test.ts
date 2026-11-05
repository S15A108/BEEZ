import { expect, it } from 'vitest'
import { targetFocus } from './notificationTarget'

const base = {
  id: '1',
  project_id: '10',
  type: 'DOCUMENT_APPROVED',
  title: '문서 승인',
  body: '',
  target_id: '42',
  created_at: '2026-11-05T00:00:00Z',
}

it('문서 알림은 문서 ID로, 문서 ID가 빠지면 목록으로, 그 밖은 target_id로 연다', () => {
  expect(
    targetFocus({ ...base, target_type: 'DOCUMENT_VERSION', document_id: '7' }),
  ).toBe('7')
  expect(targetFocus({ ...base, target_type: 'DOCUMENT_VERSION' })).toBeNull()
  expect(targetFocus({ ...base, target_type: 'TASK' })).toBe('42')
})
