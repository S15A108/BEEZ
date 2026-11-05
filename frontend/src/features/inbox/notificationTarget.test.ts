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

// PR #133 답변: 기준 알림의 target_id는 기준 ID이고, 문서함이 여는 기준 버전 번호는 baseline_version_no로 온다.
it('기준 알림은 기준 버전 번호로 그 기준을, 번호가 빠지면 문서 목록을 연다', () => {
  expect(
    targetFocus({ ...base, target_type: 'BASELINE', baseline_version_no: 3 }),
  ).toBe('baseline:3')
  expect(targetFocus({ ...base, target_type: 'BASELINE' })).toBeNull()
})
