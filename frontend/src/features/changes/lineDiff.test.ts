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
