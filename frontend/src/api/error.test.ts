import { AxiosError, AxiosHeaders } from 'axios'
import { expect, it } from 'vitest'
import {
  getApiErrorCode,
  getApiErrorDetails,
  getApiErrorMessage,
  getValidationMessage,
  isMissingApi,
  MISSING_API_MESSAGE,
  shouldRetryQuery,
  unlessMissing,
} from './error'

const failure = (code: string) =>
  new AxiosError('conflict', 'ERR_BAD_REQUEST', undefined, undefined, {
    status: 409,
    statusText: 'Conflict',
    headers: {},
    config: { headers: new AxiosHeaders() },
    data: { error: { code, message: 'server text' } },
  })

it('최초 기준 만들기 거절 코드마다 할 일을 알려 준다', () => {
  expect(getApiErrorMessage(failure('analysis_not_completed'))).toContain(
    '색인이 아직 끝나지 않았어요',
  )
  expect(getApiErrorMessage(failure('document_not_approved'))).toContain(
    '아직 승인하지 않은 문서가 있어요',
  )
  expect(getApiErrorMessage(failure('baseline_exists'))).toContain(
    '이미 기준 v1이 있어요',
  )
})

it('기준 v1 뒤 문서 수정은 변경 제안으로 안내한다', () => {
  expect(getApiErrorMessage(failure('change_proposal_required'))).toBe(
    '기준 설계 생성 이후 문서 수정은 변경 제안을 통해 요청해 주세요.',
  )
})

// S15P31A108-204: 승인 게이트(241)와 상태 전이 오류는 서버 문구를 노출하지 않고 할 일을 알려 준다.
it.each([
  ['invalid_transition', '이미 처리했거나 지금은 할 수 없는 상태예요'],
  ['analysis_running', '영향 분석이 진행 중이에요'],
  ['impact_not_confirmed', '사유를 남기고 승인해 주세요'],
  ['baseline_missing', '먼저 첫 기준 설계를 만들어 주세요'],
  ['proposal_locked', '제출한 제안은 잠겨 있어요'],
  ['idempotency_conflict', '새로고침해서 확인해 주세요'],
])('승인·제안 오류 %s 는 한국어 안내로 바꾼다', (code, text) => {
  expect(getApiErrorMessage(failure(code))).toContain(text)
  expect(getApiErrorMessage(failure(code))).not.toContain('server text')
})

const response = (status: number, data?: unknown) =>
  new AxiosError('failed', 'ERR_BAD_REQUEST', undefined, undefined, {
    status,
    statusText: '',
    headers: {},
    config: { headers: new AxiosHeaders() },
    data,
  })

it('서버에 없는 경로·메서드만 준비 중으로 보고, 있는 API의 항목 없음은 오류로 둔다', () => {
  const missingPath = response(404, {
    error: { code: 'not_found', message: MISSING_API_MESSAGE },
  })
  expect(isMissingApi(missingPath)).toBe(true)
  expect(isMissingApi(response(404))).toBe(true)
  expect(isMissingApi(response(405))).toBe(true)
  expect(isMissingApi(response(501))).toBe(true)
  expect(
    isMissingApi(
      response(404, { error: { code: 'not_found', message: '없는 회의' } }),
    ),
  ).toBe(false)
  expect(isMissingApi(response(409))).toBe(false)
  expect(getApiErrorMessage(missingPath)).toBe(
    '이 기능은 서버에서 아직 준비 중이에요.',
  )
})

// 추가 점검 6: 다시 불러도 같은 4xx는 재시도하지 않고, 5xx와 네트워크 오류만 3번까지 다시 부른다.
it('조회는 5xx와 네트워크 오류만 3번까지 다시 부르고 4xx는 바로 보인다', () => {
  for (const status of [400, 401, 403, 404, 405, 409])
    expect(shouldRetryQuery(0, response(status))).toBe(false)
  expect(shouldRetryQuery(0, response(500))).toBe(true)
  expect(shouldRetryQuery(2, response(503))).toBe(true)
  expect(shouldRetryQuery(3, response(503))).toBe(false)
  const offline = new AxiosError('Network Error', 'ERR_NETWORK')
  expect(shouldRetryQuery(0, offline)).toBe(true)
  expect(shouldRetryQuery(0, new Error('schema'))).toBe(false)
})

// 추가 점검 4: unlessMissing도 없는 API만 undefined로 바꾸고, 있는 API의 '항목 없음' 404는 그대로 던진다.
it('unlessMissing은 없는 API만 준비 중(undefined)으로 바꾼다', async () => {
  const missing = response(404, {
    error: { code: 'not_found', message: MISSING_API_MESSAGE },
  })
  await expect(unlessMissing(Promise.reject(missing))).resolves.toBeUndefined()
  await expect(
    unlessMissing(Promise.reject(response(501))),
  ).resolves.toBeUndefined()
  const notFound = response(404, {
    error: { code: 'not_found', message: '없는 프로젝트' },
  })
  await expect(unlessMissing(Promise.reject(notFound))).rejects.toBe(notFound)
})

// PR #114: ACC-A12 400 verification_code_mismatch는 error.details.remaining_attempts를 준다.
it('오류 본문의 details를 버리지 않고, 없으면 undefined다', () => {
  const mismatch = response(400, {
    error: {
      code: 'verification_code_mismatch',
      message: '인증 코드가 맞지 않습니다',
      details: { remaining_attempts: 3 },
    },
  })
  expect(getApiErrorCode(mismatch)).toBe('verification_code_mismatch')
  expect(getApiErrorDetails(mismatch)).toEqual({ remaining_attempts: 3 })
  expect(getApiErrorDetails(response(409, { error: { code: 'x' } }))).toBe(
    undefined,
  )
})

// S15P31A108-340: 같은 입력의 분석 횟수를 다 쓰면 재시도와 새 검증이 함께 막힌다(VAL-A01·A04 409).
it('검증 횟수 제한은 같은 계획으로 다시 돌리지 말고 계획을 고치라고 안내한다', () => {
  const message = getApiErrorMessage(failure('validation_attempt_limit'))
  expect(message).toContain('횟수를 모두 썼어요')
  expect(message).toContain('고쳐 새로 검증하거나 팀에 알려 주세요')
})

const badRequest = (code: string, message?: string) =>
  new AxiosError('bad', 'ERR_BAD_REQUEST', undefined, undefined, {
    status: 400,
    statusText: 'Bad Request',
    headers: {},
    config: { headers: new AxiosHeaders() },
    data: { error: { code, ...(message ? { message } : {}) } },
  })

it('입력 폼은 400 validation_failed의 서버 문구를 그대로 보인다', () => {
  expect(
    getValidationMessage(
      badRequest('validation_failed', 'description 문자열이 필요합니다.'),
    ),
  ).toBe('description 문자열이 필요합니다.')
})

it('서버 문구가 없거나 다른 오류면 공통 문구를 보인다', () => {
  expect(getValidationMessage(badRequest('validation_failed'))).toBe(
    '입력 내용을 확인해 주세요.',
  )
  expect(getValidationMessage(failure('version_conflict'))).toBe(
    getApiErrorMessage(failure('version_conflict')),
  )
  expect(getValidationMessage(new Error('x'))).toBe(
    '요청을 완료하지 못했습니다. 다시 시도해 주세요.',
  )
})
