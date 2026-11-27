import { describe, expect, it } from 'vitest'
import type { DocumentReviewSuggestion } from '@/api/contracts/common'
import { suggestionIndexes } from './suggestionNumbers'

const base = { sources: [], messages: [], explanation: '설명' }
const comment = (id: string): DocumentReviewSuggestion => ({
  ...base,
  id,
  type: 'COMMENT',
})
const patch = (
  id: string,
  origin: 'REVIEW' | 'DIRECT_REQUEST',
): DocumentReviewSuggestion => ({
  ...base,
  id,
  type: 'PATCH',
  origin,
  original_text: 'a',
  replacement_text: 'b',
  decision: 'PENDING',
})

describe('suggestionIndexes', () => {
  it('의견에서 요청해 받은 제안은 그 의견의 번호를 이어받는다', () => {
    const list = [
      comment('c1'),
      comment('c2'),
      comment('c3'),
      patch('p3', 'DIRECT_REQUEST'),
      patch('p1', 'DIRECT_REQUEST'),
    ]
    const indexes = suggestionIndexes(list, { p1: 'c1', p3: 'c3' })
    expect(indexes.get('p1')).toBe(0)
    expect(indexes.get('p3')).toBe(2)
  })

  it('어느 의견에서 요청했는지 모르면 검토 제안 다음 번호를 받는다', () => {
    const list = [
      comment('c1'),
      patch('r1', 'REVIEW'),
      patch('d1', 'DIRECT_REQUEST'),
    ]
    const indexes = suggestionIndexes(list, {})
    expect(indexes.get('r1')).toBe(0)
    expect(indexes.get('d1')).toBe(1)
  })
})
