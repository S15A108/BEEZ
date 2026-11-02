import { expect, it } from 'vitest'
import {
  comparisonSchema,
  documentBeeReviewSchema,
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
