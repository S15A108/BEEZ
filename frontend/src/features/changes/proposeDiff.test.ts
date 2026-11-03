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
