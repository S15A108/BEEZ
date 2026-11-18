import { describe, expect, it } from 'vitest'
import { passwordRule } from './passwordRule'
import { objectParticle } from '@/lib/display'

describe('비밀번호 규칙', () => {
  it('영문과 숫자가 있으면 특수문자가 없어도 통과한다', () => {
    expect(passwordRule('beezdemo2026')).toBeUndefined()
    expect(passwordRule('beez-demo-2026')).toBeUndefined()
  })
  it('영문이나 숫자가 빠지면 빠진 것을 알려 준다', () => {
    expect(passwordRule('beez-demo')).toMatch('숫자가 없어요.')
    expect(passwordRule('2026-10-28')).toMatch('영문이 없어요.')
    expect(passwordRule('!!!!!!!!')).toMatch('영문, 숫자가 없어요.')
  })
  // PR #72 mock/live 전수 대조 LIVE-AMP-02: 서버(PasswordPolicy)처럼 8자 이상, UTF-8 72바이트 이하로 보고 상황별로 알린다.
  it('짧으면 8자 이상, UTF-8 72바이트를 넘으면 너무 길다고 알린다', () => {
    expect(passwordRule('a1!b')).toBe('8자 이상으로 입력하세요.')
    expect(passwordRule(`a1!${'b'.repeat(69)}`)).toBeUndefined()
    expect(passwordRule(`a1!${'b'.repeat(70)}`)).toBe(
      '비밀번호가 너무 길어요. 더 짧게 입력해 주세요.',
    )
    // 한글은 한 글자에 3바이트라 영문 3자와 한글 24자면 75바이트로 넘는다.
    expect(passwordRule(`a1!${'가'.repeat(24)}`)).toBe(
      '비밀번호가 너무 길어요. 더 짧게 입력해 주세요.',
    )
    expect(passwordRule('onlyletters')).toBe(
      '영문과 숫자를 모두 넣어 8자 이상으로 입력하세요. 숫자가 없어요.',
    )
  })
})

describe('목적격 조사', () => {
  it('받침이 있으면 을, 없으면 를을 붙인다', () => {
    expect(objectParticle('이메일')).toBe('을')
    expect(objectParticle('비밀번호')).toBe('를')
    expect(objectParticle('프로젝트 이름')).toBe('을')
    expect(objectParticle('ERD')).toBe('를')
  })
})
