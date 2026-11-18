import { describe, expect, it } from 'vitest'
import { unreadByProject, unreadCount } from './useUnreadByProject'

describe('unreadCount', () => {
  it('0건이면 배지를 숨긴다', () => {
    expect(unreadCount(0, false)).toBeUndefined()
    expect(unreadCount(0, true)).toBeUndefined()
  })

  it('99건까지는 수를 그대로, 넘으면 99+로 보인다', () => {
    expect(unreadCount(99, false)?.text).toBe('99')
    expect(unreadCount(100, false)).toEqual({
      text: '99+',
      label: '안 읽은 알림 99+건',
    })
  })

  it('다음 페이지가 더 있으면 수 뒤에 +를 붙인다', () => {
    expect(unreadCount(3, true)?.text).toBe('3+')
    expect(unreadCount(100, true)?.text).toBe('99+')
  })
})

describe('unreadByProject', () => {
  it('항목의 project_id로 프로젝트별 수를 센다', () => {
    const of = unreadByProject({
      items: [{ project_id: '1' }, { project_id: '2' }, { project_id: '1' }],
      has_more: false,
    })
    expect(of('1')?.text).toBe('2')
    expect(of('2')?.text).toBe('1')
    expect(of('3')).toBeUndefined()
  })

  it('아직 응답이 없으면 배지가 없다', () => {
    expect(unreadByProject()('1')).toBeUndefined()
  })
})
