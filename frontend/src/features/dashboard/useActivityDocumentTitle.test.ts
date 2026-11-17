import { expect, it } from 'vitest'
import type { Document, DocumentVersion } from '@/api/contracts/common'
import { documentTitleOf } from './useActivityDocumentTitle'

const version = (id: string) => ({ id }) as DocumentVersion
const documents = [
  {
    id: '1',
    kind: 'PLAN',
    title: '기획안',
    approved_version: version('11'),
    latest_draft: version('12'),
  },
  { id: '2', kind: 'ERD', title: 'ERD', latest_draft: version('21') },
] as Document[]

it('문서 버전을 가리키는 활동은 그 버전이 승인본이든 최신 초안이든 문서 이름을 찾는다', () => {
  const of = (target_id: string) =>
    documentTitleOf({ target_type: 'DOCUMENT_VERSION', target_id }, documents)
  expect(of('11')).toBe('기획안')
  expect(of('12')).toBe('기획안')
  expect(of('21')).toBe('ERD')
})

// 활동 응답에는 이름이 없다. 목록에서 못 찾으면 이름을 지어내지 않는다.
it('목록에 없는 지난 버전이나 문서가 아닌 대상이면 이름이 없다', () => {
  expect(
    documentTitleOf(
      { target_type: 'DOCUMENT_VERSION', target_id: '10' },
      documents,
    ),
  ).toBeUndefined()
  expect(
    documentTitleOf({ target_type: 'TASK', target_id: '11' }, documents),
  ).toBeUndefined()
  expect(documentTitleOf({}, documents)).toBeUndefined()
})
