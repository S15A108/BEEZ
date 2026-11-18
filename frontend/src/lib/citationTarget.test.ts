import { describe, expect, it } from 'vitest'
import {
  citationFocus,
  findCitation,
  parseCitationFocus,
} from './citationTarget'

const target = {
  document_id: '20',
  document_version_id: '21',
  start_line: 2,
  end_line: 4,
  quote: '정확한 원문',
}

describe('문서 인용 위치', () => {
  it('문서와 버전, 행 범위 및 원문을 손실 없이 전달한다', () => {
    expect(parseCitationFocus(citationFocus(target))).toEqual(target)
    expect(parseCitationFocus('citation:{잘못된 값')).toBeUndefined()
    expect(parseCitationFocus('version:20:21')).toBeUndefined()
    expect(
      parseCitationFocus(citationFocus({ ...target, quote: '🐝'.repeat(1000) }))
        ?.quote,
    ).toHaveLength(2000)
    expect(() =>
      citationFocus({ ...target, quote: '🐝'.repeat(1001) }),
    ).toThrow()
    expect(() => citationFocus({ ...target, quote: '   ' })).toThrow()
    expect(() => citationFocus({ ...target, quote: ' 앞뒤 공백 ' })).toThrow()
  })

  it('정규화된 본문의 지정 범위에서 유일한 원문만 찾아 실제 행을 구한다', () => {
    expect(
      findCitation('\uFEFF첫 줄\r\n둘째\r\n정확한 원문\r\n넷째', target),
    ).toMatchObject({
      before: '첫 줄\n둘째\n',
      quote: '정확한 원문',
      after: '\n넷째',
      firstLine: 3,
      lastLine: 3,
    })
    expect(findCitation('첫 줄\n둘째\n다른 문장\n넷째', target)).toBeUndefined()
    expect(
      findCitation('첫 줄\n정확한 원문\n정확한 원문\n넷째', target),
    ).toBeUndefined()
    expect(findCitation('첫 줄\n둘째', target)).toBeUndefined()
  })

  it('행 범위 밖의 동일한 문장은 표시하지 않으며 여러 줄 인용을 계산한다', () => {
    expect(
      findCitation('정확한 원문\n둘째\n다른 문장\n넷째', target),
    ).toBeUndefined()
    expect(
      findCitation('첫 줄\n앞\n여러 줄\n인용', {
        ...target,
        quote: '여러 줄\n인용',
      }),
    ).toMatchObject({ firstLine: 3, lastLine: 4 })
  })
})
