import { expect, it } from 'vitest'
import {
  comparisonSchema,
  documentBeeReviewSchema,
  meetingEntrySchema,
  notificationSchema,
  pageSchema,
  usageSummarySchema,
} from './common'

const usage = {
  request_key: 'document-review-201-abc',
  feature: 'BEE_DRAFT',
  model: 'model',
  input_tokens: 10,
  output_tokens: 10,
  latency_ms: 200,
  failed: false,
  tokens_known: true,
}
const review = {
  id: '81',
  input_revision: 1,
  resolved_revision: 1,
  reference_hash: 'sha256:reference',
  summary: '검토함',
  unavailable_references: [],
  references: [],
  suggestions: [],
}

// PR #72 전수 리뷰 SC-1: 서버는 BigDecimal 비용을 JSON 숫자로, 단가가 없으면 null로 준다.
it('검토 비용은 숫자·문자열을 모두 받아 문자열로 맞추고 null은 없는 값으로 본다', () => {
  const parse = (extra: object) =>
    documentBeeReviewSchema.parse({
      ...review,
      usage: [{ ...usage, ...extra }],
    }).usage[0]
  expect(
    parse({ estimated_cost_amount: 0.00123, currency: 'USD' }),
  ).toMatchObject({ estimated_cost_amount: '0.00123', currency: 'USD' })
  expect(parse({ estimated_cost_amount: '0.5' }).estimated_cost_amount).toBe(
    '0.5',
  )
  const empty = parse({ estimated_cost_amount: null, currency: null })
  expect(empty.estimated_cost_amount).toBeUndefined()
  expect(empty.currency).toBeUndefined()
})

it('사용량 합계와 항목별 비용도 숫자로 와도 읽는다', () => {
  const summary = usageSummarySchema.parse({
    from: '2026-10-22T00:00:00Z',
    to: '2026-10-23T00:00:00Z',
    input_tokens: 1,
    output_tokens: 1,
    call_count: 1,
    estimated_cost_amount: 1.25,
    currency: 'USD',
    by_feature: [
      {
        key: 'BEE_DRAFT',
        input_tokens: 1,
        output_tokens: 1,
        call_count: 1,
        estimated_cost_amount: 1.25,
      },
    ],
    by_task: [],
    by_validation: [],
  })
  expect(summary.estimated_cost_amount).toBe('1.25')
  expect(summary.by_feature[0].estimated_cost_amount).toBe('1.25')
})

// S15P31A108-252: 서버 v2 검토는 단계 4개와 읽기 전용 의견(COMMENT)을 준다. 의견은 null 필드가 빠져서 온다.
it('v2 검토의 단계와 의견을 읽고, 원문이 없는 수정 제안은 거절한다', () => {
  const parsed = documentBeeReviewSchema.parse({
    ...review,
    usage: [],
    unavailable_references: ['REQ_SPEC'],
    review_status: 'PARTIAL',
    steps: [
      {
        step: 'BODY',
        status: 'COMPLETE',
        reason: 'REVIEWED',
        reference_ids: [],
      },
      {
        step: 'API_SPEC',
        status: 'COMPLETE',
        reason: 'REVIEWED',
        reference_ids: ['r1'],
      },
      {
        step: 'ERD',
        status: 'SKIPPED',
        reason: 'SAME_KIND',
        reference_ids: [],
      },
      {
        step: 'REQ_SPEC',
        status: 'PARTIAL',
        reason: 'REFERENCE_UNAVAILABLE',
        reference_ids: [],
      },
    ],
    suggestions: [
      { id: '1', explanation: '참고', type: 'COMMENT', step: 'BODY' },
      {
        id: '2',
        explanation: '누락',
        original_text: '가',
        replacement_text: '나',
        decision: 'PENDING',
      },
    ],
  })
  expect(parsed.steps.map((item) => item.step)).toEqual([
    'BODY',
    'API_SPEC',
    'ERD',
    'REQ_SPEC',
  ])
  expect(parsed.suggestions[0].decision).toBeUndefined()
  // type이 없는 과거 제안은 수정 제안(PATCH)으로 본다.
  expect(parsed.suggestions[1].type).toBe('PATCH')
  expect(
    documentBeeReviewSchema.safeParse({
      ...review,
      usage: [],
      suggestions: [{ id: '3', explanation: '누락', type: 'PATCH' }],
    }).success,
  ).toBe(false)
  // 단계를 주지 않는 과거 검토는 빈 배열로 읽는다.
  expect(documentBeeReviewSchema.parse({ ...review, usage: [] }).steps).toEqual(
    [],
  )
})

it('사용량의 미보고 호출 수를 버리지 않는다', () => {
  const summary = usageSummarySchema.parse({
    from: '2026-10-22T00:00:00Z',
    to: '2026-10-23T00:00:00Z',
    input_tokens: 1,
    output_tokens: 1,
    call_count: 2,
    estimated_cost_amount: '0.5',
    currency: 'USD',
    tokens_unknown_call_count: 1,
    cost_unknown_call_count: 1,
    by_feature: [
      {
        key: 'DOCUMENT_PARSE',
        input_tokens: 0,
        output_tokens: 0,
        call_count: 1,
        estimated_cost_amount: '0',
        tokens_unknown_call_count: 1,
        cost_unknown_call_count: 1,
      },
    ],
    by_task: [],
    by_validation: [],
  })
  expect(summary.tokens_unknown_call_count).toBe(1)
  expect(summary.cost_unknown_call_count).toBe(1)
  expect(summary.by_feature[0].cost_unknown_call_count).toBe(1)
})

// PR #72 mock/live 전수 대조 DOC-LIVE-2: CREATE 항목 비교는 target_id 키 없이 온다(서버 전역 non_null).
it('비교 응답은 target_id 키가 없어도 읽는다', () => {
  expect(
    comparisonSchema.parse({
      target_type: 'DESIGN_ITEM',
      current_changed: false,
      format: 'STRUCTURED',
      proposed_content: { description: '새 API' },
    }).target_id,
  ).toBeUndefined()
})

// 충돌 회의는 서버가 열고, 첫 기록은 비비의 CONFLICT_BRIEF다. 이 종류가 파싱 실패하면 기록 목록이 통째로 안 열린다.
it('회의 기록은 충돌 브리핑과 정리 종류도 읽는다', () => {
  const entry = {
    id: '1',
    meeting_id: '9',
    text: '두 작업이 충돌해요',
    created_at: '2026-11-05T01:40:00Z',
  }
  expect(
    meetingEntrySchema.parse({ ...entry, kind: 'CONFLICT_BRIEF' }).kind,
  ).toBe('CONFLICT_BRIEF')
  expect(meetingEntrySchema.parse({ ...entry, kind: 'SUMMARY' }).kind).toBe(
    'SUMMARY',
  )
})

// S15P31A108-296: PR #133의 문서 알림은 target_type=DOCUMENT_VERSION(target_id는 문서 버전 ID)에 document_id를 함께 준다.
// 한 건이라도 못 읽으면 알림 목록 전체가 실패하므로, 다른 알림과 섞인 페이지로 확인한다.
it('문서 알림이 섞인 알림 목록을 읽고 document_id를 남긴다', () => {
  const base = {
    project_id: '1',
    title: '문서 승인',
    body: '등록한 문서가 승인되었습니다',
    created_at: '2026-11-05T01:00:00Z',
  }
  const page = pageSchema(notificationSchema).parse({
    items: [
      {
        ...base,
        id: '11',
        type: 'TASK_ASSIGNED',
        target_type: 'TASK',
        target_id: '5',
      },
      {
        ...base,
        id: '10',
        type: 'DOCUMENT_APPROVED',
        target_type: 'DOCUMENT_VERSION',
        target_id: '42',
        document_id: '7',
      },
    ],
    has_more: false,
  })
  expect(page.items[1]).toMatchObject({
    target_type: 'DOCUMENT_VERSION',
    target_id: '42',
    document_id: '7',
  })
  expect(page.items[0]).not.toHaveProperty('document_id')
})

// PR #133 답변: 기준 알림에만 baseline_version_no가 오고, target_id는 기준 ID 그대로다.
it('기준 알림의 기준 버전 번호를 남긴다', () => {
  expect(
    notificationSchema.parse({
      id: '12',
      project_id: '1',
      type: 'BASELINE_CREATED',
      title: '새 기준',
      body: '',
      target_type: 'BASELINE',
      target_id: '900',
      baseline_version_no: 3,
      created_at: '2026-11-05T01:00:00Z',
    }),
  ).toMatchObject({ target_id: '900', baseline_version_no: 3 })
})
