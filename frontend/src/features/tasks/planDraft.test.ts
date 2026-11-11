import { describe, expect, it } from 'vitest'
import {
  draftKey,
  fillEmptyFields,
  isDraftCriterion,
  isDraftText,
  undoPatch,
  type PlanFormSnapshot,
} from './planDraft'
import { planDraftResponseSchema, type PlanDraft } from './schemas'

const draft: PlanDraft = planDraftResponseSchema.parse({
  status: 'DRAFTED',
  plan: {
    goal: '주문 API를 만든다',
    included_scope: ['주문 생성', '번호 발급'],
    excluded_scope: ['환불'],
    approach: '저장 후 번호를 발급한다',
    completion_criteria: ['번호가 응답된다', '없는 메뉴는 거절된다'],
  },
  evidence: [],
  unsupported_fields: [],
  design_ref_candidates: [],
  new_design_item_candidates: [],
  unlinked: [],
  baseline_id: '401',
})
const empty: PlanFormSnapshot = {
  goal: '',
  included_scope: '',
  excluded_scope: '',
  approach: '',
  completion_criteria: [{ text: '' }],
}

describe('문서 초안 채우기', () => {
  it('빈 칸은 모두 채우고 줄 목록은 한 줄씩 합친다', () => {
    const fill = fillEmptyFields(empty, draft)
    expect(fill.fields).toEqual([
      'goal',
      'included_scope',
      'excluded_scope',
      'approach',
      'completion_criteria',
    ])
    expect(fill.patch.included_scope).toBe('주문 생성\n번호 발급')
    expect(fill.patch.completion_criteria).toEqual([
      { text: '번호가 응답된다' },
      { text: '없는 메뉴는 거절된다' },
    ])
  })

  it('이미 적은 칸은 덮어쓰지 않는다', () => {
    const fill = fillEmptyFields(
      {
        ...empty,
        goal: '내가 쓴 목표',
        completion_criteria: [{ text: '내가 쓴 기준' }],
      },
      draft,
    )
    expect(fill.fields).toEqual([
      'included_scope',
      'excluded_scope',
      'approach',
    ])
    expect(fill.patch).not.toHaveProperty('goal')
    expect(fill.patch).not.toHaveProperty('completion_criteria')
  })

  it('공백만 있는 칸은 빈 칸으로 본다', () => {
    const fill = fillEmptyFields({ ...empty, goal: '   ' }, draft)
    expect(fill.fields).toContain('goal')
  })

  it('초안에 내용이 없는 칸은 채우지 않는다', () => {
    const sparse = planDraftResponseSchema.parse({
      ...draft,
      plan: { ...draft.plan, approach: '', excluded_scope: [] },
    })
    const fill = fillEmptyFields(empty, sparse)
    expect(fill.fields).not.toContain('approach')
    expect(fill.fields).not.toContain('excluded_scope')
  })

  it('되돌리기는 채운 칸만 이전 값으로 돌린다', () => {
    const before = { ...empty, goal: '내가 쓴 목표' }
    const fill = fillEmptyFields(before, draft)
    const after: PlanFormSnapshot = {
      goal: '내가 쓴 목표',
      included_scope: '주문 생성\n번호 발급',
      excluded_scope: '환불',
      approach: '저장 후 번호를 발급한다',
      completion_criteria: [
        { text: '번호가 응답된다' },
        { text: '없는 메뉴는 거절된다' },
      ],
    }
    const { patch, kept } = undoPatch(fill, after)
    expect(patch).not.toHaveProperty('goal')
    expect(patch.approach).toBe('')
    expect(patch.completion_criteria).toEqual([{ text: '' }])
    expect(kept).toEqual([])
  })

  it('되돌리기는 채운 뒤 사용자가 고친 칸을 건드리지 않고 알린다', () => {
    const fill = fillEmptyFields(empty, draft)
    const edited: PlanFormSnapshot = {
      goal: '주문 API를 만든다',
      included_scope: '주문 생성\n번호 발급\n내가 더한 줄',
      excluded_scope: '환불',
      approach: '내가 고친 접근 방법',
      completion_criteria: [
        { text: '번호가 응답된다' },
        { text: '내가 고친 기준' },
      ],
    }
    const { patch, kept } = undoPatch(fill, edited)
    // 그대로인 칸만 돌아간다.
    expect(patch.goal).toBe('')
    expect(patch.excluded_scope).toBe('')
    // 고친 칸(포함 범위, 접근 방법, 완료 기준 줄)은 그대로 둔다.
    expect(patch).not.toHaveProperty('included_scope')
    expect(patch).not.toHaveProperty('approach')
    expect(patch).not.toHaveProperty('completion_criteria')
    expect(kept).toEqual(['included_scope', 'approach', 'completion_criteria'])
  })

  it('사용자가 고치면 초안 표시가 사라진다', () => {
    const fill = fillEmptyFields(empty, draft)
    expect(isDraftText('goal', fill, '주문 API를 만든다')).toBe(true)
    expect(isDraftText('goal', fill, '주문 API를 만든다 (수정)')).toBe(false)
    expect(isDraftText('goal', null, '주문 API를 만든다')).toBe(false)
    expect(isDraftCriterion(fill, '번호가 응답된다')).toBe(true)
    expect(isDraftCriterion(fill, '직접 쓴 기준')).toBe(false)
  })

  it('같은 입력은 같은 키가 된다', () => {
    expect(draftKey(' 주문 ', '메모')).toBe(draftKey('주문', ' 메모 '))
    expect(draftKey('주문', '')).not.toBe(draftKey('주문', '메모'))
  })
})
