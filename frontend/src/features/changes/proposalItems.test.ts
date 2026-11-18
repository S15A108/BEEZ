import { expect, it } from 'vitest'
import {
  isStaleDocument,
  proposalFormSchema,
  toDocumentChanges,
} from './proposalItems'

// PR #72 리뷰 1: 문서 2개짜리 반려 제안을 그대로 다시 내면 두 문서 변경이 모두 요청에 남아야 한다.
it('다시 내는 제안은 폼에서 고친 첫 문서와 나머지 문서 변경을 모두 보낸다', () => {
  const source = [
    {
      document_id: '201',
      base_document_version_id: '2011',
      proposed_document_version_id: '2019',
      after_content: '# 기획안\n고친 글',
      content_hash: 'sha256:a',
    },
    {
      document_id: '203',
      base_document_version_id: '2031',
      proposed_document_version_id: '2039',
      after_content: '# 요구사항\n고친 글',
      content_hash: 'sha256:b',
    },
  ]
  // 폼 기본값은 첫 문서, 나머지는 그대로 함께 간다.
  const changes = toDocumentChanges(
    { document_id: '201', after_content: source[0].after_content },
    '2012',
    source.slice(1),
  )
  expect(changes).toEqual([
    {
      document_id: '201',
      base_document_version_id: '2012',
      after_content: '# 기획안\n고친 글',
    },
    {
      document_id: '203',
      base_document_version_id: '2031',
      after_content: '# 요구사항\n고친 글',
    },
  ])
  // 첫 문서를 '고치지 않음'으로 바꿔도 나머지 문서는 남고, 폼에서 같은 문서를 고르면 폼의 글이 대신한다.
  expect(
    toDocumentChanges({ document_id: '' }, undefined, source.slice(1)),
  ).toHaveLength(1)
  expect(
    toDocumentChanges(
      { document_id: '203', after_content: '새 글' },
      '2032',
      source.slice(1),
    ),
  ).toEqual([
    {
      document_id: '203',
      base_document_version_id: '2032',
      after_content: '새 글',
    },
  ])
  // 남는 문서가 있으면 설계 항목·고칠 문서가 없어도 막지 않는다.
  expect(
    proposalFormSchema.safeParse({
      title: '제목',
      reason: '이유',
      task_id: '',
      items: [],
      kept_documents: 1,
    }).success,
  ).toBe(true)
})

it('고칠 문서도 함께 올라갈 변경도 없으면 문서를 고르라고 알린다', () => {
  expect(
    proposalFormSchema.safeParse({ title: '제목', reason: '이유', task_id: '' })
      .error?.issues[0].message,
  ).toBe('고칠 문서를 골라 주세요.')
  expect(
    proposalFormSchema.safeParse({
      title: '제목',
      reason: '이유',
      task_id: '',
      kept_items: 1,
    }).success,
  ).toBe(true)
})

// 추가 점검 2: 고친 문서의 기준 버전을 모르면 그 문서를 조용히 빼지 않는다.
it('고친 문서의 기준 버전을 모르면 문서 변경을 만들지 않고 알린다', () => {
  expect(() =>
    toDocumentChanges({ document_id: '203', after_content: '글' }, undefined),
  ).toThrow('고친 문서의 기준 버전을 알 수 없어요.')
})

// 추가 점검 1: 폼에서 고칠 문서를 다른 문서로 바꿔도 원래 첫 문서의 변경은 남는다.
it('다시 낼 때 남길 문서는 원래 제안의 모든 문서 중 폼에서 지금 고치는 문서만 뺀다', () => {
  const source = [
    {
      document_id: '201',
      base_document_version_id: '2011',
      proposed_document_version_id: '2019',
      after_content: '기획안 고친 글',
      content_hash: 'sha256:a',
    },
    {
      document_id: '203',
      base_document_version_id: '2031',
      proposed_document_version_id: '2039',
      after_content: '요구사항 고친 글',
      content_hash: 'sha256:b',
    },
  ]
  expect(
    toDocumentChanges(
      { document_id: '203', after_content: '요구사항 새 글' },
      '2031',
      source,
    ).map((change) => [change.document_id, change.after_content]),
  ).toEqual([
    ['203', '요구사항 새 글'],
    ['201', '기획안 고친 글'],
  ])
})

// 추가 점검 3: 기준 버전이 지금 승인본과 다른 문서 변경은 그대로 낼 수 없다.
it('문서 변경의 기준 버전이 지금 승인본과 다르면 기준이 바뀐 문서로 본다', () => {
  const current = (id: string) => ({ '201': '2012', '203': '2031' })[id]
  expect(
    isStaleDocument(
      { document_id: '201', base_document_version_id: '2011' },
      current,
    ),
  ).toBe(true)
  expect(
    isStaleDocument(
      { document_id: '203', base_document_version_id: '2031' },
      current,
    ),
  ).toBe(false)
})

// PR #72 재전수 ROUND2-DOC-01: A·B를 바꾼 제안에서 A를 기준 원문으로 되돌리면 A의 옛 수정안이 다시 들어가지 않는다.
it('기준 원문으로 되돌린 문서는 원래 제안의 변경도 빼고 남은 문서만 보낸다', () => {
  const source = [
    {
      document_id: '201',
      base_document_version_id: '2011',
      proposed_document_version_id: '2019',
      after_content: '# 기획안\n고친 글',
      content_hash: 'sha256:a',
    },
    {
      document_id: '203',
      base_document_version_id: '2031',
      proposed_document_version_id: '2039',
      after_content: '# 요구사항\n고친 글',
      content_hash: 'sha256:b',
    },
  ]
  expect(
    toDocumentChanges(
      { document_id: '', removed_document_id: '201' },
      undefined,
      source,
    ),
  ).toEqual([
    {
      document_id: '203',
      base_document_version_id: '2031',
      after_content: '# 요구사항\n고친 글',
    },
  ])
})
