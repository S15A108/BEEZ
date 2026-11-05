import { expect, it } from 'vitest'
import { proposeChanges } from './proposeDiff'

const base = [
  '## 1. 프로젝트 목록',
  'REQ-001 로그인한 사용자는 참여 프로젝트를 조회한다.',
  'REQ-002 팀장만 설계 변경을 승인한다.',
  '',
  '## 4. 정책',
].join('\n')

it('바뀐 줄을 수정·추가·삭제로 나누고 항목 키와 그 위 제목을 붙인다', () => {
  const edited = [
    '## 1. 프로젝트 목록',
    'REQ-001 로그인한 사용자는 참여 프로젝트를 조회하고, 없으면 새 프로젝트 만들기를 안내한다.',
    'REQ-002 팀장만 설계 변경을 승인한다.',
    'REQ-003 참여한 프로젝트가 없으면 받은 초대를 먼저 보여 준다.',
    '',
    '## 4. 정책',
    "POL-001 빈 목록 문구는 '아직 참여한 프로젝트가 없어요'로 통일한다.",
  ].join('\n')
  const result = proposeChanges(base, edited)
  expect(
    result.changes.map(({ kind, key, section }) => [kind, key, section]),
  ).toEqual([
    ['UPDATE', 'REQ-001', '1. 프로젝트 목록'],
    ['CREATE', 'REQ-003', '1. 프로젝트 목록'],
    ['CREATE', 'POL-001', '4. 정책'],
  ])
  expect(result.changes[0].before).toBe(
    'REQ-001 로그인한 사용자는 참여 프로젝트를 조회한다.',
  )
  expect([
    result.count('UPDATE'),
    result.count('CREATE'),
    result.count('RETIRE'),
  ]).toEqual([1, 2, 0])
  expect([result.addedLines, result.removedLines]).toEqual([3, 1])
})

it('지운 줄은 삭제로 세고, 빈 줄과 줄바꿈 방식(CRLF)만 다른 곳은 바뀐 곳이 아니다', () => {
  const result = proposeChanges(
    base,
    `${base.replace('REQ-002 팀장만 설계 변경을 승인한다.\n', '')}\n\n`.replaceAll(
      '\n',
      '\r\n',
    ),
  )
  expect(result.changes).toEqual([
    {
      kind: 'RETIRE',
      before: 'REQ-002 팀장만 설계 변경을 승인한다.',
      key: 'REQ-002',
      section: '1. 프로젝트 목록',
    },
  ])
  expect(proposeChanges(base, base).changes).toEqual([])
})

// PR #135 리뷰: 빠진 줄과 새 줄을 차례로 짝지어 모두 '수정'으로 보면, REQ-002를 지우고 그 자리에 REQ-003을 쓴 것이
// 'REQ-003 수정'으로만 보이고 승인된 REQ-002가 없어지는 것이 화면에서 사라진다.
it('항목 키가 다른 줄끼리는 수정으로 짝짓지 않고 삭제와 추가로 나눈다', () => {
  const result = proposeChanges(
    base,
    base.replace(
      'REQ-002 팀장만 설계 변경을 승인한다.',
      'REQ-003 팀원도 설계 변경을 제안한다.',
    ),
  )
  expect(
    result.changes.map(({ kind, key, before, after }) => [
      kind,
      key,
      before,
      after,
    ]),
  ).toEqual([
    ['RETIRE', 'REQ-002', 'REQ-002 팀장만 설계 변경을 승인한다.', undefined],
    ['CREATE', 'REQ-003', undefined, 'REQ-003 팀원도 설계 변경을 제안한다.'],
  ])
  expect([
    result.count('UPDATE'),
    result.count('CREATE'),
    result.count('RETIRE'),
  ]).toEqual([0, 1, 1])
})

it('항목 키는 서버가 만드는 REQ·API·ENT·POL·CON-숫자 3자리 이상만 읽는다', () => {
  const keyOf = (line: string) =>
    proposeChanges('', line).changes.map((change) => change.key)[0]
  expect(keyOf('UTF-8로 저장하고 ISO-8601 시각과 SHA-256 해시를 쓴다.')).toBe(
    undefined,
  )
  expect(keyOf('REQ-01 두 자리 번호는 키가 아니다.')).toBe(undefined)
  expect(keyOf('UTF-8 응답은 API-012 규칙을 따른다.')).toBe('API-012')
  expect(keyOf('ENT-1000 프로젝트 테이블')).toBe('ENT-1000')
})

// PR #135 리뷰(전진): 긴 문서는 lineDiff가 가운데를 통째로 빠진 줄·더한 줄로 돌려주는데, 순서대로 짝지은 두 줄이
// 똑같아도 '수정'으로 셌다. 2,000줄 문서에서 두 줄만 고쳐도 '바뀐 곳 1,491개'가 나왔다. 똑같은 짝은 바뀐 곳이 아니다.
it('긴 문서에서 두 줄만 고치면 바뀐 곳도 두 곳이다', () => {
  const base = Array.from({ length: 2000 }, (_, i) => `줄 ${i + 1}`)
  const edited = [...base]
  edited[9] = '줄 10 고침'
  edited[1499] = '줄 1500 고침'
  const result = proposeChanges(base.join('\n'), edited.join('\n'))
  expect(
    result.changes.map(({ kind, before, after }) => [kind, before, after]),
  ).toEqual([
    ['UPDATE', '줄 10', '줄 10 고침'],
    ['UPDATE', '줄 1500', '줄 1500 고침'],
  ])
  expect([result.addedLines, result.removedLines]).toEqual([2, 2])
})
