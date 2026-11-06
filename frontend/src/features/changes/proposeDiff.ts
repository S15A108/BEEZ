import { lineDiff } from './lineDiff'
import { normalizeDocument } from './normalizeDocument'

// 문서 수정 제안 ② 변경점 확인(캔버스 PNL-04-v2 propose-changes): 기준 원문과 고친 글을 줄마다 비교해 바뀐 곳을
// 수정·추가·삭제로 나눈다. 붙어 있는 빠진 줄과 새 줄은 차례로 짝지어 '수정'으로 보고, 남는 줄은 '추가'나 '삭제'다.
// 짝지은 두 줄의 항목 키가 다르면(REQ-002를 지우고 그 자리에 REQ-003을 씀) 수정이 아니라 삭제와 추가로 나눈다(PR #135 리뷰).
// 바뀐 곳마다 그 줄의 항목 키(REQ-001 등)와 그 위의 가장 가까운 제목(## …)을 붙인다. 빈 줄만 바뀐 곳은 세지 않는다.
// API 없음: 바뀐 곳에서 설계 항목을 찾아 CHG-A01 items를 채우는 기능(수정안 요청)이 없어 키는 글에서 읽어 보여 주기만 한다.
export type ProposedChange = {
  kind: 'UPDATE' | 'CREATE' | 'RETIRE'
  before?: string
  after?: string
  key?: string
  section?: string
}

// 서버가 만드는 키만 읽는다: 종류별 접두어와 3자리 이상 번호(ProposalPublicationService·DesignItemCommandService의
// REQ·API·ENT·POL·CON-%03d). UTF-8, ISO-8601, SHA-256 같은 말은 키가 아니다.
const KEY = /\b(?:REQ|API|ENT|POL|CON)-\d{3,}\b/
const HEADING = /^#{1,6}\s+(.+)$/

type Line = { text: string; section?: string }

export function proposeChanges(base: string, edited: string) {
  const changes: ProposedChange[] = []
  let section: string | undefined
  let removed: Line[] = []
  let added: Line[] = []
  const flush = () => {
    for (let i = 0; i < Math.max(removed.length, added.length); i++) {
      const before = removed[i]
      const after = added[i]
      // 긴 문서는 lineDiff가 가운데를 통째로 빠진 줄·더한 줄로 돌려줘서, 짝지은 두 줄이 똑같을 수 있다.
      // 똑같은 짝은 바뀐 곳이 아니다(PR #135 리뷰).
      if (before && after && before.text === after.text) continue
      const beforeKey = before?.text.match(KEY)?.[0]
      const afterKey = after?.text.match(KEY)?.[0]
      if (before && after && beforeKey === afterKey) {
        changes.push({
          kind: 'UPDATE',
          before: before.text,
          after: after.text,
          key: afterKey,
          section: after.section,
        })
        continue
      }
      if (before)
        changes.push({
          kind: 'RETIRE',
          before: before.text,
          key: beforeKey,
          section: before.section,
        })
      if (after)
        changes.push({
          kind: 'CREATE',
          after: after.text,
          key: afterKey,
          section: after.section,
        })
    }
    removed = []
    added = []
  }
  for (const line of lineDiff(
    normalizeDocument(base),
    normalizeDocument(edited),
  )) {
    if (line.kind === 'same') flush()
    // 빈 줄은 바뀐 곳으로 세지 않고 짝도 짓지 않는다.
    else if (line.text.trim())
      (line.kind === 'removed' ? removed : added).push({
        text: line.text,
        section,
      })
    // 제목은 고친 글 기준으로 따라간다(빠진 제목 아래의 바뀐 곳은 그 앞 제목에 붙는다).
    if (line.kind !== 'removed')
      section = line.text.match(HEADING)?.[1] ?? section
  }
  flush()
  return {
    changes,
    count: (kind: ProposedChange['kind']) =>
      changes.filter((change) => change.kind === kind).length,
    addedLines: changes.filter((change) => change.after).length,
    removedLines: changes.filter((change) => change.before).length,
  }
}
