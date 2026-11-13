import { expect, it } from 'vitest'
import { documentLines } from './sourceLocation'

// 서버(SourceRemapper.lines)와 줄 번호가 하나라도 어긋나면 팀장이 본 줄과 다른 줄이 근거로 저장된다.
it('마지막 줄바꿈 뒤에는 빈 줄을 만들지 않고 중간의 빈 줄은 센다', () => {
  expect(documentLines('# 제목\n\n본문\n')).toEqual(['# 제목', '', '본문'])
  expect(documentLines('끝에 줄바꿈 없음')).toEqual(['끝에 줄바꿈 없음'])
  expect(documentLines('')).toEqual([])
})
