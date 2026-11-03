import { lineDiff } from './lineDiff'
import { normalizeDocument } from './normalizeDocument'

// 문서 수정 제안 ② 변경점 확인(캔버스 PNL-04-v2 propose-changes): 기준 원문과 고친 글을 줄마다 비교해 바뀐 곳을
// 수정·추가·삭제로 나눈다. 붙어 있는 빠진 줄과 새 줄은 차례로 짝지어 '수정'으로 보고, 남는 줄은 '추가'나 '삭제'다.
// 바뀐 곳마다 줄 안의 항목 키(REQ-001 등)와 그 위의 가장 가까운 제목(## …)을 붙인다. 빈 줄만 바뀐 곳은 세지 않는다.
// API 없음: 바뀐 곳에서 설계 항목을 찾아 CHG-A01 items를 채우는 기능(수정안 요청)이 없어 키는 글에서 읽어 보여 주기만 한다.
export type ProposedChange = {
  kind: 'UPDATE' | 'CREATE' | 'RETIRE'
  before?: string
  after?: string
  key?: string
  section?: string
}

const KEY = /\b[A-Z][A-Z0-9]*-\d+\b/
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
      const line = (after ?? before)!
      changes.push({
        kind: before && after ? 'UPDATE' : after ? 'CREATE' : 'RETIRE',
        ...(before ? { before: before.text } : {}),
        ...(after ? { after: after.text } : {}),
        key: line.text.match(KEY)?.[0],
        section: line.section,
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
