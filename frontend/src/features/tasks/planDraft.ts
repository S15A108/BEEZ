// 작업 계획 문서 초안(TSK-A08, S15P31A108-385): 받은 초안을 폼의 빈 칸에만 채우는 순수 함수.
import type { PlanDraft, PlanDraftField } from './schemas'

/** 폼에서 초안이 채우는 칸의 현재 값(폼 값과 같은 모양) */
export interface PlanFormSnapshot {
  goal: string
  included_scope: string
  excluded_scope: string
  approach: string
  completion_criteria: { text: string }[]
}

export interface DraftFill {
  /** 채울 칸의 새 값 */
  patch: Partial<PlanFormSnapshot>
  /** 채운 칸 이름 */
  fields: PlanDraftField[]
  /** 채우기 전 값. 되돌리기에 쓴다. */
  previous: PlanFormSnapshot
}

export const draftFieldLabel: Record<PlanDraftField, string> = {
  goal: '목표',
  included_scope: '포함 범위',
  excluded_scope: '제외 범위',
  approach: '접근 방법',
  completion_criteria: '완료 기준',
}

export const documentTypeLabel = {
  PLAN: '기획안',
  REQUIREMENTS: '요구사항 명세서',
  API_SPEC: 'API 명세서',
  ERD: 'ERD',
} as const

/** 같은 입력인지 비교하는 키. 같은 입력으로는 다시 받지 못하게 한다. */
export function draftKey(title: string, memo: string) {
  return JSON.stringify([title.trim(), memo.trim()])
}

const blank = (value: string) => !value.trim()

/**
 * 사용자가 이미 적은 칸은 건드리지 않고 비어 있는 칸만 채운다.
 * 초안에 내용이 없는 칸도 채우지 않는다.
 */
export function fillEmptyFields(
  current: PlanFormSnapshot,
  draft: PlanDraft,
): DraftFill {
  const patch: Partial<PlanFormSnapshot> = {}
  const fields: PlanDraftField[] = []
  const { plan } = draft
  if (blank(current.goal) && plan.goal.trim()) {
    patch.goal = plan.goal.trim()
    fields.push('goal')
  }
  if (blank(current.included_scope) && plan.included_scope.length) {
    patch.included_scope = plan.included_scope.join('\n')
    fields.push('included_scope')
  }
  if (blank(current.excluded_scope) && plan.excluded_scope.length) {
    patch.excluded_scope = plan.excluded_scope.join('\n')
    fields.push('excluded_scope')
  }
  if (blank(current.approach) && plan.approach.trim()) {
    patch.approach = plan.approach.trim()
    fields.push('approach')
  }
  if (
    current.completion_criteria.every((row) => blank(row.text)) &&
    plan.completion_criteria.length
  ) {
    patch.completion_criteria = plan.completion_criteria.map((text) => ({
      text,
    }))
    fields.push('completion_criteria')
  }
  return { patch, fields, previous: current }
}

/**
 * 지금 값이 채운 초안 그대로인가. 사용자가 고치면 노란 표시를 거둔다.
 * 완료 기준은 줄마다 비교해 그대로인 줄만 true 를 준다.
 */
export function isDraftText(
  field: Exclude<PlanDraftField, 'completion_criteria'>,
  fill: DraftFill | null,
  current: string,
) {
  const drafted = fill?.patch[field]
  return drafted !== undefined && drafted === current
}

export function isDraftCriterion(fill: DraftFill | null, text: string) {
  return !!fill?.patch.completion_criteria?.some((row) => row.text === text)
}

/**
 * 초안으로 채운 칸을 이전 값으로 되돌릴 값. **지금 값이 채운 초안 그대로인 칸만** 돌린다.
 * 채운 뒤 사용자가 고친 칸은 건드리지 않고 `kept`에 담아 알린다(사용자가 쓴 글을 잃지 않게).
 * 완료 기준은 줄 전체가 초안 그대로일 때만 돌린다.
 */
export function undoPatch(
  fill: DraftFill,
  current: PlanFormSnapshot,
): { patch: Partial<PlanFormSnapshot>; kept: PlanDraftField[] } {
  const patch: Partial<PlanFormSnapshot> = {}
  const kept: PlanDraftField[] = []
  for (const field of fill.fields) {
    if (field === 'completion_criteria') {
      const drafted = fill.patch.completion_criteria ?? []
      const same =
        drafted.length === current.completion_criteria.length &&
        drafted.every(
          (row, index) => row.text === current.completion_criteria[index].text,
        )
      if (same) patch.completion_criteria = fill.previous.completion_criteria
      else kept.push(field)
    } else if (fill.patch[field] === current[field]) {
      patch[field] = fill.previous[field]
    } else {
      kept.push(field)
    }
  }
  return { patch, kept }
}
