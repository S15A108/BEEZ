import { expect, it } from 'vitest'
import { askInputSchema, statusResponseSchema } from './schemas'

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

// BEE-A01 요청의 scope는 찾는 범위다. 기본 APPROVED라 빠져도 된다.
it('질문 요청은 찾는 범위(APPROVED·INCLUDE_DRAFTS)를 실어 보내고 모르는 범위는 거부한다', () => {
  expect(
    askInputSchema.parse({ question: '초대 만료는?', scope: 'INCLUDE_DRAFTS' }),
  ).toEqual({ question: '초대 만료는?', scope: 'INCLUDE_DRAFTS' })
  expect(askInputSchema.parse({ question: '초대 만료는?' })).toEqual({
    question: '초대 만료는?',
  })
  expect(
    askInputSchema.safeParse({ question: '초대 만료는?', scope: 'ALL' })
      .success,
  ).toBe(false)
})
