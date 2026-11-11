import { ACTIVE_POLL_MS } from '@/lib/pollIntervals'
import { useLayoutEffect, useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { validationsApi } from '@/api/services'
import type { Validation } from '@/api/contracts/common'
import type { CreateInput, RetryInput } from './schemas'
import { evidenceCollectionFailure } from './evidenceCollectionFailure'

/** 상태와 근거를 화면에서 일관되게 소비하기 위한 표현. 원본 ID와 근거는 보존한다. */
export function toValidationView(validation: Validation) {
  const collectionFailure = evidenceCollectionFailure(validation)
  const needsHumanReview =
    !collectionFailure &&
    validation.findings.some(
      (finding) =>
        finding.kind === 'ERROR' && finding.resolution === 'HUMAN_REVIEW',
    )
  const phase =
    validation.status === 'NOT_RUN'
      ? 'idle'
      : validation.status === 'RUNNING'
        ? 'running'
        : validation.status === 'ERROR'
          ? 'failure'
          : 'success'
  return {
    phase,
    status: validation.status,
    // 수집을 완료한 뒤 나온 검증 결과로 보완 항목을 안내한다. 실행 실패 화면에서는 원인과 재시도만 보여 준다.
    findings: collectionFailure ? [] : validation.findings,
    policyAnalysis: validation.policy_analysis,
    designRefs: validation.design_refs,
    relatedTaskRefs: validation.related_task_refs,
    needsHumanReview,
    collectionFailure,
    retryable:
      validation.status === 'ERROR' &&
      (collectionFailure ? collectionFailure.retryable : !needsHumanReview),
  } as const
}

/** 검증 ID별 캐시를 분리하고 실행 중에만 조회한다. 구독 해제 시 진행 중인 조회도 취소한다. */
export function useValidationQuery(projectId: string, validationId?: string) {
  return useQuery({
    queryKey: ['projects', projectId, 'validation', validationId],
    queryFn: async ({ signal }) => {
      const response = await validationsApi.get(projectId, validationId!, {
        signal,
      })
      if (response.id !== validationId)
        throw new Error('요청한 검증과 다른 응답을 받았습니다.')
      return response
    },
    enabled: !!validationId,
    refetchInterval: (query) =>
      !query.state.error && query.state.data?.status === 'RUNNING'
        ? ACTIVE_POLL_MS
        : false,
  })
}

type Pending = {
  key: string
  promise: Promise<Validation>
}

/** POST는 서버가 접수했을 수 있으므로 화면 전환 뒤에도 완료를 기다린다. */
export function useValidationActions(projectId: string, taskId?: string) {
  const scope = JSON.stringify([projectId, taskId])
  const queryClient = useQueryClient()
  const active = useRef({ scope, generation: 0, mounted: false })
  useLayoutEffect(() => {
    const state = active.current
    state.scope = scope
    state.mounted = true
    const generation = ++state.generation
    return () => {
      if (state.generation === generation) state.mounted = false
    }
  }, [scope])
  const pending = useRef(new Map<string, Pending>())
  const [busyScopes, setBusyScopes] = useState<Set<string>>(new Set())

  const run = (
    key: string,
    request: (signal: AbortSignal) => Promise<Validation>,
  ) => {
    const existing = pending.current.get(scope)
    if (existing) {
      if (existing.key === key) return existing.promise
      return Promise.reject(new Error('다른 검증 요청을 처리하고 있습니다.'))
    }
    const controller = new AbortController()
    const generation = active.current.generation
    const isCurrent = () =>
      active.current.mounted &&
      active.current.scope === scope &&
      active.current.generation === generation
    const promise = (async () => {
      let response: Validation | undefined
      let failure: unknown
      let failed = false
      try {
        response = await request(controller.signal)
      } catch (error) {
        failed = true
        failure = error
      } finally {
        // 실패나 화면 이탈에도 서버 접수 가능성이 있으므로 원래 작업을 다시 읽는다.
        await Promise.all([
          queryClient.invalidateQueries({
            queryKey: ['projects', projectId, 'tasks'],
          }),
          queryClient.invalidateQueries({
            queryKey: ['projects', projectId, 'task', taskId],
          }),
          queryClient.invalidateQueries({
            queryKey: ['projects', projectId, 'validation'],
          }),
        ])
        pending.current.delete(scope)
        if (active.current.mounted)
          setBusyScopes((scopes) => {
            const next = new Set(scopes)
            next.delete(scope)
            return next
          })
      }
      if (!isCurrent())
        throw new DOMException('화면이 변경되었습니다.', 'AbortError')
      if (failed) throw failure
      return response!
    })()
    pending.current.set(scope, { key, promise })
    setBusyScopes((scopes) => new Set(scopes).add(scope))
    return promise
  }

  return {
    busy: busyScopes.has(scope),
    create: (input: CreateInput) => {
      if (!taskId) return Promise.reject(new Error('작업을 선택해 주세요.'))
      return run(JSON.stringify(['create', input]), (signal) =>
        validationsApi.create(projectId, taskId, input, { signal }),
      )
    },
    retry: (validationId: string, input: RetryInput) =>
      run(JSON.stringify(['retry', validationId, input]), (signal) =>
        validationsApi.retry(projectId, validationId, input, { signal }),
      ),
  }
}
