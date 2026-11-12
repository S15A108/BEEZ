import type { ReactNode } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { validationsApi } from '@/api/services'
import { validationSchema } from '@/api/contracts/common'
import { ACTIVE_POLL_MS } from '@/lib/pollIntervals'
import {
  toValidationView,
  useValidationActions,
  useValidationQuery,
} from './useValidation'

const validation = validationSchema.parse({
  id: '601',
  task_id: '501',
  plan_revision_id: '511',
  baseline_id: '401',
  status: 'RUNNING',
  trigger: 'AGENT',
  current_attempt: 1,
  design_refs: [],
  related_task_refs: [],
  findings: [],
  cache_hit: false,
  created_at: '2026-10-23T03:00:00Z',
})
const input = { plan_revision_id: '511', expected_lock_version: 1 }
afterEach(() => vi.restoreAllMocks())

function wrapper({ children }: { children: ReactNode }) {
  return (
    <QueryClientProvider
      client={
        new QueryClient({
          defaultOptions: { queries: { retry: false, gcTime: 0 } },
        })
      }
    >
      {children}
    </QueryClientProvider>
  )
}

it('polls a running validation until completion and then stops', async () => {
  let finished = false
  const get = vi
    .spyOn(validationsApi, 'get')
    .mockImplementation(async () =>
      finished ? { ...validation, status: 'PASS' } : validation,
    )
  const { result, unmount } = renderHook(
    () => useValidationQuery('101', '601'),
    { wrapper },
  )
  await waitFor(() => expect(result.current.data?.status).toBe('RUNNING'))
  await waitFor(() => expect(get.mock.calls.length).toBeGreaterThan(2))
  finished = true
  await waitFor(() => expect(result.current.data?.status).toBe('PASS'))
  // 끝난 뒤에는 몇 주기를 기다려도 다시 읽지 않는다.
  const settled = get.mock.calls.length
  await new Promise((resolve) => setTimeout(resolve, ACTIVE_POLL_MS * 5))
  expect(get).toHaveBeenCalledTimes(settled)
  unmount()
})

it('cancels an in-flight lookup on unmount', async () => {
  let signal: AbortSignal | undefined
  vi.spyOn(validationsApi, 'get').mockImplementation(
    (_project, _id, options) => {
      signal = options?.signal
      return new Promise(() => undefined)
    },
  )
  const { unmount } = renderHook(() => useValidationQuery('101', '601'), {
    wrapper,
  })
  await waitFor(() => expect(signal).toBeDefined())
  unmount()
  expect(signal?.aborted).toBe(true)
})

it('can recover from a lookup error and resumes polling when still running', async () => {
  let finished = false
  const get = vi
    .spyOn(validationsApi, 'get')
    .mockRejectedValueOnce(new Error('offline'))
    .mockImplementation(async () =>
      finished ? { ...validation, status: 'PASS' } : validation,
    )
  const { result } = renderHook(() => useValidationQuery('101', '601'), {
    wrapper,
  })
  await waitFor(() => expect(result.current.isError).toBe(true))
  await act(async () => {
    await result.current.refetch()
  })
  await waitFor(() => expect(result.current.data?.status).toBe('RUNNING'))
  const resumed = get.mock.calls.length
  await waitFor(() => expect(get.mock.calls.length).toBeGreaterThan(resumed))
  finished = true
  await waitFor(() => expect(result.current.data?.status).toBe('PASS'))
})

it('rejects a response for a different validation and recovers on retry', async () => {
  vi.spyOn(validationsApi, 'get')
    .mockResolvedValueOnce({ ...validation, id: '602', status: 'PASS' })
    .mockResolvedValueOnce(validation)
  const { result } = renderHook(() => useValidationQuery('101', '601'), {
    wrapper,
  })
  await waitFor(() => expect(result.current.isError).toBe(true))
  expect(result.current.data).toBeUndefined()
  await act(async () => {
    await result.current.refetch()
  })
  await waitFor(() => expect(result.current.data?.id).toBe('601'))
  expect(result.current.data?.status).toBe('RUNNING')
})

it('deduplicates submission and rejects a response after the selected task changes', async () => {
  let resolve!: (value: typeof validation) => void
  let signal: AbortSignal | undefined
  const create = vi
    .spyOn(validationsApi, 'create')
    .mockImplementation((_project, _task, _input, options) => {
      signal = options?.signal
      return new Promise((done) => {
        resolve = done
      })
    })
  const { result, rerender } = renderHook(
    ({ task }) => useValidationActions('101', task),
    {
      initialProps: { task: '501' },
      wrapper,
    },
  )
  let first!: Promise<unknown>
  let second!: Promise<unknown>
  act(() => {
    first = result.current.create(input)
    second = result.current.create(input)
  })
  expect(first).toBe(second)
  expect(create).toHaveBeenCalledTimes(1)
  expect(result.current.busy).toBe(true)
  const rejected = expect(first).rejects.toMatchObject({ name: 'AbortError' })
  rerender({ task: '502' })
  expect(signal?.aborted).toBe(false)
  expect(result.current.busy).toBe(false)
  await act(async () => {
    resolve(validation)
    await rejected
  })
})

it('refreshes the original task and list when a request fails after possible server acceptance', async () => {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  const taskKey = ['projects', '101', 'task', '501']
  const listKey = ['projects', '101', 'tasks']
  const validationKey = ['projects', '101', 'validation', '601']
  for (const key of [taskKey, listKey, validationKey])
    client.setQueryData(key, {})
  vi.spyOn(validationsApi, 'retry').mockRejectedValue(new Error('network lost'))
  const { result } = renderHook(() => useValidationActions('101', '501'), {
    wrapper: ({ children }) => (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    ),
  })
  await act(async () => {
    await expect(
      result.current.retry('601', { expected_attempt: 1 }),
    ).rejects.toThrow('network lost')
  })
  for (const key of [taskKey, listKey, validationKey])
    expect(client.getQueryState(key)?.isInvalidated).toBe(true)
})

it('preserves findings and evidence while deriving retryability', () => {
  const finding = {
    id: '1',
    source: 'MODEL' as const,
    kind: 'ERROR' as const,
    severity: 'BLOCKING' as const,
    message: '확인 필요',
    sources: [],
    resolution: 'HUMAN_REVIEW' as const,
  }
  const view = toValidationView({
    ...validation,
    status: 'ERROR',
    findings: [finding],
  })
  expect(view.phase).toBe('failure')
  expect(view.findings).toEqual([finding])
  expect(view.needsHumanReview).toBe(true)
  expect(view.retryable).toBe(false)
})

it('과거 수집 오류와 의미 분석의 근거 부족이 섞이면 사람 검토를 유지한다', () => {
  const findings = [
    {
      id: '1',
      source: 'MODEL' as const,
      kind: 'ERROR' as const,
      severity: 'BLOCKING' as const,
      message: '분석 후보 검색 또는 원문 근거 범위가 불완전합니다',
      sources: [],
      resolution: 'HUMAN_REVIEW' as const,
    },
    {
      id: '2',
      source: 'MODEL' as const,
      kind: 'ERROR' as const,
      severity: 'BLOCKING' as const,
      message: '정책을 판단할 근거가 부족합니다',
      sources: [],
      resolution: 'HUMAN_REVIEW' as const,
    },
  ]
  const view = toValidationView({
    ...validation,
    status: 'ERROR',
    error_code: 'ai_evidence_insufficient',
    findings,
  })
  expect(view.collectionFailure).toBeUndefined()
  expect(view.findings).toEqual(findings)
  expect(view.needsHumanReview).toBe(true)
  expect(view.retryable).toBe(false)
})

it.each(
  [
    ['search_query_too_long'],
    ['search_query_limit'],
    ['search_results_truncated'],
    ['design_candidate_limit'],
    ['design_reference_limit'],
    ['related_task_limit'],
    ['unresolved_limit'],
    ['source_evidence_unavailable'],
    ['evidence_fetch_failed', 'source_evidence_unavailable'],
    ['search_query_too_long', 'evidence_fetch_failed'],
    ['source_evidence_unavailable', 'unknown_collection_failure'],
    ['unknown_collection_failure'],
    [],
  ].map((codes) => ({ codes })),
)(
  '수집 원인 $codes 는 과거 RETRY 값과 관계없이 사람 검토가 필요하다',
  ({ codes }) => {
    const view = toValidationView({
      ...validation,
      status: 'ERROR',
      // 과거 코드보다 상세한 수집 원인을 우선한다.
      error_code: 'evidence_fetch_failed',
      execution_failure: {
        type: 'EVIDENCE_COLLECTION_FAILED',
        reason_codes: codes,
        retryable: true,
        message: '근거 수집 실패',
      },
      findings: [
        {
          id: '1',
          source: 'MODEL',
          kind: 'ERROR',
          severity: 'BLOCKING',
          message: '분석 후보 검색 또는 원문 근거 범위가 불완전합니다',
          sources: [],
          resolution: 'RETRY',
        },
      ],
    })
    expect(view.collectionFailure?.reason_codes).toEqual(codes)
    expect(view.needsHumanReview).toBe(true)
    expect(view.retryable).toBe(false)
    expect(view.findings).toEqual([])
  },
)

it('일시적 수집 원인만 있으면 서버의 재시도 허용 여부를 따른다', () => {
  const executionFailure = {
    type: 'EVIDENCE_COLLECTION_FAILED' as const,
    reason_codes: ['evidence_fetch_failed'],
    retryable: true,
    message: '근거 수집 실패',
  }
  const error = {
    ...validation,
    status: 'ERROR' as const,
    execution_failure: executionFailure,
    findings: [
      {
        id: '1',
        source: 'MODEL' as const,
        kind: 'ERROR' as const,
        severity: 'BLOCKING' as const,
        message: '수집 실패',
        sources: [],
        resolution: 'RETRY' as const,
      },
    ],
  }
  expect(toValidationView(error).retryable).toBe(true)
  expect(toValidationView(error).needsHumanReview).toBe(false)
  expect(
    toValidationView({
      ...error,
      execution_failure: { ...executionFailure, retryable: false },
    }).retryable,
  ).toBe(false)
  expect(toValidationView({ ...error, findings: [] }).retryable).toBe(false)
  expect(
    toValidationView({
      ...error,
      findings: [
        ...error.findings,
        { ...error.findings[0], id: '2', resolution: 'HUMAN_REVIEW' },
      ],
    }).retryable,
  ).toBe(false)
})

it.each([
  '검색 또는 원문 근거 범위가 불완전합니다',
  '분석 후보 검색 또는 원문 근거 범위가 불완전합니다',
])(
  '상세 원인이 없는 과거 수집 실패 문구는 수동 검토로 안내한다: %s',
  (message) => {
    const view = toValidationView({
      ...validation,
      status: 'ERROR',
      error_code: 'ai_evidence_insufficient',
      findings: [
        {
          id: '1',
          source: 'MODEL',
          kind: 'ERROR',
          severity: 'BLOCKING',
          message,
          sources: [],
          resolution: 'RETRY',
        },
      ],
    })
    expect(view.collectionFailure?.reason_codes).toEqual([
      'unknown_collection_failure',
    ])
    expect(view.needsHumanReview).toBe(true)
    expect(view.retryable).toBe(false)
  },
)

it('상세 원인이 없는 과거 evidence_fetch_failed는 수집 재시도를 유지한다', () => {
  const view = toValidationView({
    ...validation,
    status: 'ERROR',
    error_code: 'evidence_fetch_failed',
    findings: [
      {
        id: '1',
        source: 'MODEL',
        kind: 'ERROR',
        severity: 'BLOCKING',
        message: '근거를 불러오지 못했습니다',
        sources: [],
        resolution: 'RETRY',
      },
    ],
  })
  expect(view.collectionFailure?.reason_codes).toEqual([
    'evidence_fetch_failed',
  ])
  expect(view.retryable).toBe(true)
  expect(view.needsHumanReview).toBe(false)
})
