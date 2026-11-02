import { describe, expect, it } from 'vitest'
import {
  CODE_TTL_MS,
  clock,
  confirmCode,
  sendCode,
  verificationMessage,
} from './emailCode'

const reason = (run: () => unknown) => {
  try {
    run()
  } catch (cause) {
    return verificationMessage(cause)
  }
  throw new Error('통과하면 안 된다')
}

describe('가입 이메일 인증 (목 모드)', () => {
  it('맞는 코드를 넣으면 인증 토큰과 이메일을 돌려준다', () => {
    const sent = sendCode('a@beez.local', 0)
    expect(sent.expires_at).toBe(new Date(CODE_TTL_MS).toISOString())
    expect(confirmCode(sent.verification_id, sent.mock_code, 1000)).toEqual(
      expect.objectContaining({ email: 'a@beez.local' }),
    )
  })

  it('틀리면 남은 시도를 알려 주고 다섯 번 틀리면 코드를 다시 받게 한다', () => {
    const { verification_id: id } = sendCode('b@beez.local', 0)
    expect(reason(() => confirmCode(id, '000000', 1))).toBe(
      '코드가 맞지 않아요 (남은 시도 4번)',
    )
    for (let i = 0; i < 3; i += 1) reason(() => confirmCode(id, '000000', 1))
    expect(reason(() => confirmCode(id, '000000', 1))).toBe(
      '틀린 횟수를 넘었어요. 코드를 다시 받아 주세요.',
    )
  })

  it('5분이 지나거나 다시 받은 뒤의 이전 코드는 받지 않는다', () => {
    const first = sendCode('c@beez.local', 0)
    expect(
      reason(() =>
        confirmCode(first.verification_id, first.mock_code, CODE_TTL_MS),
      ),
    ).toBe('인증 시간이 지났어요. 코드를 다시 받아 주세요.')
    const second = sendCode('c@beez.local', 0)
    sendCode('c@beez.local', 0)
    expect(() =>
      confirmCode(second.verification_id, second.mock_code, 1),
    ).toThrow()
  })

  it('남은 시간을 분:초로 보여 준다', () => {
    expect(clock(CODE_TTL_MS - 1000)).toBe('04:59')
    expect(clock(-5)).toBe('00:00')
  })
})
