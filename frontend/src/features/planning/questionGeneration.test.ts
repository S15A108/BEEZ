import { expect, it } from 'vitest'
import {
  ACTIVE_POLL_MS,
  WAITING_POLL_MS,
  generationOf,
  isGenerating,
  isQuestionTarget,
  pollIntervalOf,
} from './questionGeneration'

const completed = (question_generation?: {
  status: string
  error_code?: string
  retryable?: boolean
}) => ({ status: 'COMPLETED', question_generation })

it('분석이 끝났는데 질문 생성 행이 아직 없으면 대상 문서는 기다리는 상태로 계속 읽는다', () => {
  expect(generationOf(completed(), true)).toEqual({ status: 'WAITING' })
  expect(isGenerating(generationOf(completed(), true))).toBe(true)
  expect(pollIntervalOf(completed(), true)).toBe(WAITING_POLL_MS)
})

it('기다리는 상태는 시간으로 끊지 않는다 — 같은 응답이면 몇 번이든 계속 읽는다', () => {
  for (let poll = 0; poll < 1000; poll++)
    expect(pollIntervalOf(completed(), true)).toBe(WAITING_POLL_MS)
})

it('서버가 질문을 만들지 않는 문서(기준 발행 후, 다른 상태의 버전)는 기다리지 않는다', () => {
  expect(generationOf(completed(), false)).toBeUndefined()
  expect(pollIntervalOf(completed(), false)).toBe(false)
  expect(isQuestionTarget({ status: 'DRAFT' }, false)).toBe(true)
  expect(isQuestionTarget({ status: 'APPROVED' }, false)).toBe(true)
  expect(isQuestionTarget({ status: 'DRAFT' }, true)).toBe(false)
  expect(isQuestionTarget({ status: 'SUPERSEDED' }, false)).toBe(false)
  expect(isQuestionTarget({ status: 'REJECTED' }, false)).toBe(false)
})

it('분석이 도는 중이면 질문 생성 여부와 상관없이 계속 읽는다', () => {
  expect(pollIntervalOf({ status: 'PENDING' }, false)).toBe(ACTIVE_POLL_MS)
  expect(pollIntervalOf({ status: 'RUNNING' }, false)).toBe(ACTIVE_POLL_MS)
  expect(generationOf({ status: 'RUNNING' }, true)).toBeUndefined()
})

it('질문 생성 상태가 오면 그 값을 따르고, 대기·진행만 계속 읽는다', () => {
  for (const status of ['PENDING', 'RUNNING'])
    expect(pollIntervalOf(completed({ status }), false)).toBe(ACTIVE_POLL_MS)
  expect(pollIntervalOf(completed({ status: 'COMPLETED' }), true)).toBe(false)
  expect(
    generationOf(
      completed({
        status: 'FAILED',
        error_code: 'ai_unavailable',
        retryable: true,
      }),
      true,
    ),
  ).toEqual({ status: 'FAILED', errorCode: 'ai_unavailable', retryable: true })
  // 실패한 문서는 더 읽지 않는다. 다른 문서의 진행은 문서마다 따로 판단한다.
  expect(pollIntervalOf(completed({ status: 'FAILED' }), true)).toBe(false)
})
