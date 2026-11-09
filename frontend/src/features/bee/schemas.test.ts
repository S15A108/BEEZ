import { expect, it } from 'vitest'
import {
  askInputSchema,
  askResponseSchema,
  statusResponseSchema,
} from './schemas'

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

it('검증된 답변의 인용·확인 수준과 설계·작업 참조를 보존한다', () => {
  const answer = {
    origin: 'MODEL',
    model: 'model',
    text: '정책입니다. [1]',
    sources: [],
    unresolved: [],
    answer_id: '55',
    answer_status: 'ANSWERED',
    grounded: true,
    citations: [
      {
        n: 1,
        document_id: '1',
        document_version_id: '2',
        version_no: 3,
        title: '정책',
        locator: { type: 'text_lines', start_line: 4, end_line: 6 },
        quote: '정책 내용',
      },
    ],
    verification: { level: 'TOKENS', claims: 1 },
    design_refs: [
      {
        design_item_id: '5',
        design_item_version_id: '6',
        key: 'API-01',
        kind: 'API_CONTRACT',
        version_no: 1,
      },
    ],
    task_refs: [{ task_id: '7', key: 'TASK-01', status: 'READY' }],
  }
  expect(askResponseSchema.parse(answer)).toEqual(answer)
  expect(
    askResponseSchema.safeParse({
      ...answer,
      citations: [{ ...answer.citations[0], n: 2 }],
    }).success,
  ).toBe(false)
  expect(
    askResponseSchema.safeParse({ ...answer, grounded: false }).success,
  ).toBe(false)
  expect(
    askResponseSchema.safeParse({
      ...answer,
      citations: [
        {
          ...answer.citations[0],
          locator: { type: 'text_lines', start_line: 6, end_line: 4 },
        },
      ],
    }).success,
  ).toBe(false)
})
