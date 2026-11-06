import { expect, it } from 'vitest'
import {
  START_WAIT_MS,
  generationOf,
  isGenerating,
  shouldPollAnalysis,
} from './questionGeneration'

const completed = (question_generation?: {
  status: string
  error_code?: string
  retryable?: boolean
}) => ({ status: 'COMPLETED', question_generation })

it('분석이 끝났는데 질문 생성 행이 아직 없으면 기다리는 상태로 보고 계속 읽는다', () => {
  expect(generationOf(completed(), false)).toEqual({ status: 'WAITING' })
  expect(isGenerating(generationOf(completed(), false))).toBe(true)
  expect(shouldPollAnalysis(completed(), false)).toBe(true)
})

it('기다리는 시간이 끝나면 질문을 만들지 않는 문서로 보고 읽기를 멈춘다', () => {
  expect(START_WAIT_MS).toBeGreaterThan(0)
  expect(generationOf(completed(), true)).toBeUndefined()
  expect(shouldPollAnalysis(completed(), true)).toBe(false)
})

it('분석이 도는 중이면 질문 생성 여부와 상관없이 계속 읽는다', () => {
  expect(shouldPollAnalysis({ status: 'PENDING' }, true)).toBe(true)
  expect(shouldPollAnalysis({ status: 'RUNNING' }, true)).toBe(true)
  expect(generationOf({ status: 'RUNNING' }, false)).toBeUndefined()
})

it('질문 생성 상태가 오면 그 값을 따르고, 대기·진행만 계속 읽는다', () => {
  for (const status of ['PENDING', 'RUNNING'])
    expect(shouldPollAnalysis(completed({ status }), true)).toBe(true)
  expect(shouldPollAnalysis(completed({ status: 'COMPLETED' }), false)).toBe(
    false,
  )
  expect(
    generationOf(
      completed({
        status: 'FAILED',
        error_code: 'ai_unavailable',
        retryable: true,
      }),
      false,
    ),
  ).toEqual({ status: 'FAILED', errorCode: 'ai_unavailable', retryable: true })
  // 실패한 문서는 더 읽지 않는다. 다른 문서의 진행은 문서마다 따로 판단한다.
  expect(shouldPollAnalysis(completed({ status: 'FAILED' }), false)).toBe(false)
})
