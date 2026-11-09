import { expect, it } from 'vitest'
import { changedFields, foldDiff, lineDiff } from './lineDiff'

it('바뀐 줄은 빠진 줄과 더한 줄로, 같은 줄은 그대로 나눈다', () => {
  expect(lineDiff('a\nb\nc', 'a\nB\nc\nd')).toEqual([
    { kind: 'same', text: 'a' },
    { kind: 'removed', text: 'b' },
    { kind: 'added', text: 'B' },
    { kind: 'same', text: 'c' },
    { kind: 'added', text: 'd' },
  ])
})

// PR #135 리뷰(오서현): LCS 표가 두 문서 줄 수의 곱만큼 커져, 허용 크기(1MB) 안의 긴 문서도 메모리가 폭증했다.
// 앞뒤의 같은 줄은 표 없이 덜어 내고, 그래도 가운데가 너무 크면 표를 만들지 않고 가운데를 통째로 빠진 줄·더한 줄로 보인다.
it('바뀐 가운데가 너무 크면 표를 만들지 않고 통째로 빠진 줄과 더한 줄로 보인다', () => {
  // 가운데 한가운데에 같은 줄 X가 있어도 표를 만들지 않으므로 같은 줄로 맞추지 않는다.
  const middle = (prefix: string) => [
    ...Array.from({ length: 750 }, (_, i) => `${prefix}${i}`),
    'X',
    ...Array.from({ length: 750 }, (_, i) => `${prefix}${i + 750}`),
  ]
  const before = ['머리', ...middle('a'), '공통', '꼬리']
  const after = ['머리', ...middle('b'), '공통', '꼬리']
  const lines = lineDiff(before.join('\n'), after.join('\n'))
  expect(lines[0]).toEqual({ kind: 'same', text: '머리' })
  expect(lines.at(-2)).toEqual({ kind: 'same', text: '공통' })
  expect(lines.at(-1)).toEqual({ kind: 'same', text: '꼬리' })
  const changed = lines.slice(1, -2)
  expect(changed.some((line) => line.kind === 'same')).toBe(false)
  expect(changed.filter((line) => line.kind === 'removed')).toHaveLength(1501)
  expect(changed.filter((line) => line.kind === 'added')).toHaveLength(1501)
})

it('바뀐 줄 앞뒤 한 줄만 남기고 나머지 같은 줄은 개수로 접는다', () => {
  const lines = lineDiff('1\n2\n3\n4\n5\n6', '1\n2\n3\n4\n5\nX')
  expect(foldDiff(lines)).toEqual([
    { kind: 'folded', count: 4 },
    { kind: 'same', text: '5' },
    { kind: 'removed', text: '6' },
    { kind: 'added', text: 'X' },
  ])
})

// PR #72 전수 리뷰 CHG-2: 설명이 같아도 method·path·fields가 바뀌면 그 필드를 보인다.
it('설계 항목 내용에서 설명 밖의 바뀐 필드만 고른다', () => {
  expect(
    changedFields(
      {
        description: '주문을 만든다.',
        method: 'POST',
        path: '/v1/orders',
        fields: [{ name: 'id' }],
      },
      {
        description: '주문을 만든다.',
        method: 'POST',
        path: '/v2/orders',
        fields: [{ name: 'id' }, { name: 'total' }],
      },
    ),
  ).toEqual([
    { key: 'path', before: '/v1/orders', after: '/v2/orders' },
    {
      key: 'fields',
      before: JSON.stringify([{ name: 'id' }], null, 2),
      after: JSON.stringify([{ name: 'id' }, { name: 'total' }], null, 2),
    },
  ])
  expect(changedFields('문서 원문', '바뀐 원문')).toEqual([])
})
