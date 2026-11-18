import { AxiosError, AxiosHeaders } from 'axios'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { MISSING_API_MESSAGE } from '@/api/error'
import { authApi } from '@/api/services'
import {
  CODE_TTL_MS,
  RESEND_INTERVAL_MS,
  sentCode,
} from '@/mocks/emailVerifications'
import { resetMockApi, setupMockApi } from '@/mocks/setup'
import { clock, verificationMessage } from './emailCode'

let restore: () => void
beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(0)
  resetMockApi()
  restore = setupMockApi()
})
afterEach(() => {
  restore()
  vi.useRealTimers()
})

const reason = (request: Promise<unknown>) =>
  request.then(
    () => {
      throw new Error('통과하면 안 된다')
    },
    (cause: unknown) => verificationMessage(cause),
  )
const send = (email: string) => authApi.sendEmailVerification({ email })
const confirm = (id: string, code: string) =>
  authApi.confirmEmailVerification(id, { code })
const wrong = (code: string) => (code === '000000' ? '111111' : '000000')

describe('가입 이메일 인증 (목 모드)', () => {
  it('맞는 코드를 넣으면 인증 토큰과 이메일을 돌려준다', async () => {
    const sent = await send('a@beez.local')
    expect(sent.expires_at).toBe(new Date(CODE_TTL_MS).toISOString())
    vi.setSystemTime(1000)
    expect(
      await confirm(sent.verification_id, sentCode(sent.verification_id)!),
    ).toEqual(expect.objectContaining({ email: 'a@beez.local' }))
  })

  it('틀리면 남은 시도를 알려 주고 다섯 번 틀리면 코드를 다시 받게 한다', async () => {
    const { verification_id: id } = await send('b@beez.local')
    const code = wrong(sentCode(id)!)
    expect(await reason(confirm(id, code))).toBe(
      '코드가 맞지 않아요 (남은 시도 4번)',
    )
    for (let i = 0; i < 3; i += 1) await reason(confirm(id, code))
    expect(await reason(confirm(id, code))).toBe(
      '틀린 횟수를 넘었어요. 코드를 다시 받아 주세요.',
    )
  })

  it('5분이 지나거나 다시 받은 뒤의 이전 코드는 받지 않는다', async () => {
    const first = await send('c@beez.local')
    vi.setSystemTime(CODE_TTL_MS)
    expect(
      await reason(
        confirm(first.verification_id, sentCode(first.verification_id)!),
      ),
    ).toBe('인증 시간이 지났어요. 코드를 다시 받아 주세요.')
    const second = await send('c@beez.local')
    // 다시 받기는 60초 뒤부터 된다.
    vi.setSystemTime(CODE_TTL_MS + RESEND_INTERVAL_MS)
    await send('c@beez.local')
    await expect(
      confirm(second.verification_id, sentCode(second.verification_id)!),
    ).rejects.toThrow()
  })

  it('남은 시간을 분:초로 보여 준다', () => {
    expect(clock(CODE_TTL_MS - 1000)).toBe('04:59')
    expect(clock(-5)).toBe('00:00')
  })
})

const failure = (status: number, data?: unknown) =>
  new AxiosError('failed', 'ERR_BAD_REQUEST', undefined, undefined, {
    status,
    statusText: '',
    headers: {},
    config: { headers: new AxiosHeaders() },
    data,
  })
const code = (status: number, value: string) =>
  failure(status, { error: { code: value, message: 'server text' } })

it('코드 보내기 오류마다 칸 아래 안내를 주고, 서버 문구는 그대로 보이지 않는다', () => {
  expect(verificationMessage(code(409, 'email_already_exists'))).toBe(
    '이미 가입된 이메일이에요. 로그인해 주세요.',
  )
  expect(verificationMessage(code(429, 'rate_limited'))).toBe(
    '코드를 너무 자주 받았어요. 잠시 뒤에 다시 받아 주세요.',
  )
  expect(verificationMessage(code(503, 'mail_send_failed'))).toBe(
    '인증 메일을 보내지 못했어요. 다시 받아 주세요.',
  )
  expect(verificationMessage(code(404, 'not_found'))).toBe(
    '인증 요청을 찾을 수 없어요. 코드를 다시 받아 주세요.',
  )
  // 아직 인증 API가 없는 서버(PR #114 배포 전)
  expect(
    verificationMessage(
      failure(404, {
        error: { code: 'not_found', message: MISSING_API_MESSAGE },
      }),
    ),
  ).toBe('이 기능은 서버에서 아직 준비 중이에요.')
  expect(verificationMessage(code(400, 'verification_code_mismatch'))).toBe(
    '코드가 맞지 않아요.',
  )
  expect(verificationMessage(new AxiosError('Network Error'))).toBe(
    '서버에 연결할 수 없습니다. 연결 상태를 확인해 주세요.',
  )
})
