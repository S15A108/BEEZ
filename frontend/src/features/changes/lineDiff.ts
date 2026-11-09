// 문서 원문을 줄 단위로 비교한다(PNL-04 수정안 회의 제안 · 바뀌는 내용).
export type DiffLine = { kind: 'same' | 'removed' | 'added'; text: string }
export type FoldedLine = DiffLine | { kind: 'folded'; count: number }

// LCS 표는 바뀐 가운데 줄 수의 곱만큼 커진다. 앞뒤의 같은 줄은 표 없이 덜어 내고, 가운데가 이 칸 수를 넘으면 표를
// 만들지 않고 가운데를 통째로 빠진 줄·더한 줄로 보인다. 허용 크기(1MB) 안의 긴 문서에서 메모리가 폭증했다(PR #135 리뷰).
// ponytail: 큰 가운데는 줄을 맞추지 않는다. 큰 문서를 줄마다 맞춰야 하면 Myers 알고리즘으로 바꾼다.
const MAX_TABLE_CELLS = 2_000_000

export function lineDiff(before: string, after: string): DiffLine[] {
  const a = before.split('\n')
  const b = after.split('\n')
  let head = 0
  while (head < a.length && head < b.length && a[head] === b[head]) head++
  let tail = 0
  while (
    tail < a.length - head &&
    tail < b.length - head &&
    a[a.length - 1 - tail] === b[b.length - 1 - tail]
  )
    tail++
  const same = (text: string): DiffLine => ({ kind: 'same', text })
  const oldMiddle = a.slice(head, a.length - tail)
  const newMiddle = b.slice(head, b.length - tail)
  return [
    ...a.slice(0, head).map(same),
    ...middleDiff(oldMiddle, newMiddle),
    ...a.slice(a.length - tail).map(same),
  ]
}

function middleDiff(a: string[], b: string[]): DiffLine[] {
  if ((a.length + 1) * (b.length + 1) > MAX_TABLE_CELLS)
    return [
      ...a.map((text): DiffLine => ({ kind: 'removed', text })),
      ...b.map((text): DiffLine => ({ kind: 'added', text })),
    ]
  const lcs = Array.from({ length: a.length + 1 }, () =>
    new Array<number>(b.length + 1).fill(0),
  )
  for (let i = a.length - 1; i >= 0; i--)
    for (let j = b.length - 1; j >= 0; j--)
      lcs[i][j] =
        a[i] === b[j]
          ? lcs[i + 1][j + 1] + 1
          : Math.max(lcs[i + 1][j], lcs[i][j + 1])
  const lines: DiffLine[] = []
  let i = 0
  let j = 0
  while (i < a.length && j < b.length) {
    if (a[i] === b[j]) {
      lines.push({ kind: 'same', text: a[i] })
      i++
      j++
    } else if (lcs[i + 1][j] >= lcs[i][j + 1])
      lines.push({ kind: 'removed', text: a[i++] })
    else lines.push({ kind: 'added', text: b[j++] })
  }
  while (i < a.length) lines.push({ kind: 'removed', text: a[i++] })
  while (j < b.length) lines.push({ kind: 'added', text: b[j++] })
  return lines
}

// 바뀐 줄 앞뒤 context줄만 남기고, 나머지 같은 줄은 접어 개수로 보여 준다.
export function foldDiff(lines: DiffLine[], context = 1): FoldedLine[] {
  const folded: FoldedLine[] = []
  lines.forEach((line, index) => {
    const near = lines
      .slice(Math.max(0, index - context), index + context + 1)
      .some((item) => item.kind !== 'same')
    const last = folded.at(-1)
    if (near) folded.push(line)
    else if (last?.kind === 'folded') last.count++
    else folded.push({ kind: 'folded', count: 1 })
  })
  return folded
}

// 설계 항목 내용(구조화 객체)에서 설명(description) 밖의 바뀐 필드. 설명 문장만 비교하면 API의 method·path나
// DATA_CONTRACT.fields가 바뀐 것이 보이지 않는다(PR #72 전수 리뷰 CHG-2). 값은 문자열이면 그대로, 아니면 JSON으로 보인다.
export function changedFields(before: unknown, after: unknown) {
  const record = (value: unknown) =>
    value && typeof value === 'object' && !Array.isArray(value)
      ? (value as Record<string, unknown>)
      : {}
  const base = record(before)
  const next = record(after)
  const show = (value: unknown) =>
    value === undefined
      ? undefined
      : typeof value === 'string'
        ? value
        : JSON.stringify(value, null, 2)
  return [...new Set([...Object.keys(base), ...Object.keys(next)])]
    .filter(
      (key) =>
        key !== 'description' &&
        JSON.stringify(base[key]) !== JSON.stringify(next[key]),
    )
    .map((key) => ({ key, before: show(base[key]), after: show(next[key]) }))
}
