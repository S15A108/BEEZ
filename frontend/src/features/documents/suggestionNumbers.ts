import type { DocumentReviewSuggestion } from '@/api/contracts/common'

// 카드에 붙는 번호(0부터). 화면 단계가 바뀌어도 같은 항목은 같은 번호를 갖게 한다.
// - 의견은 의견끼리, 검토에서 나온 제안은 제안끼리 센다.
// - 의견에 '비비에게 요청하기'로 받은 제안은 그 의견의 번호를 이어받는다(의견 1 → 제안 1).
// - 어느 의견에서 요청했는지 모르는 직접 요청은 검토 제안 다음 번호를 받는다.
export function suggestionIndexes(
  suggestions: DocumentReviewSuggestion[],
  sources: Record<string, string>,
): Map<string, number> {
  const indexes = new Map<string, number>()
  let comments = 0
  let patches = 0
  for (const item of suggestions) {
    if (item.type === 'COMMENT') indexes.set(item.id, comments++)
    else if (item.origin !== 'DIRECT_REQUEST') indexes.set(item.id, patches++)
  }
  for (const item of suggestions) {
    if (item.type !== 'PATCH' || item.origin !== 'DIRECT_REQUEST') continue
    const source = sources[item.id]
    const linked = source === undefined ? undefined : indexes.get(source)
    indexes.set(item.id, linked ?? patches++)
  }
  return indexes
}
