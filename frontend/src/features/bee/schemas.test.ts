import { expect, it } from 'vitest'
import { statusResponseSchema } from './schemas'

// 응답 예시는 dev docs/api/01-backend-rest.md BEE-A04와 서버 BeeStatusResponse(null 필드 생략)와 같다.
it('비비가 한가하면 activity만 온 응답을 받는다', () => {
  expect(statusResponseSchema.parse({ activity: 'IDLE' })).toEqual({
    activity: 'IDLE',
  })
})

it('문서 검토 중이면 문서 ID와 표시 문구를 받고, 숫자 ID나 모르는 활동은 거부한다', () => {
  const reviewing = {
    activity: 'DOCUMENT_REVIEW',
    target_type: 'DOCUMENT',
    target_id: '212',
    label: '알림 API 명세서 검토 중',
  }
  expect(statusResponseSchema.parse(reviewing)).toEqual(reviewing)
  expect(
    statusResponseSchema.safeParse({ ...reviewing, target_id: 212 }).success,
  ).toBe(false)
  expect(statusResponseSchema.safeParse({ activity: 'CODING' }).success).toBe(
    false,
  )
})
