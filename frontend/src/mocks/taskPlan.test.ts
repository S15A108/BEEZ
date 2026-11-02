/// <reference types="node" />
import { webcrypto } from 'node:crypto'
import { afterEach, beforeEach, expect, it } from 'vitest'
import { authApi, tasksApi, validationsApi } from '@/api/services'
import { DEMO_EMAIL, DEMO_PASSWORD, resetMockApi, setupMockApi } from './setup'

// 사용자 결정 10-30: 폼은 제목·목표·관련 설계·완료 기준만 받고 나머지는 빈 값으로 보낸다.
let restore: () => void
beforeEach(async () => {
  Object.defineProperty(globalThis.crypto, 'subtle', {
    configurable: true,
    value: webcrypto.subtle,
  })
  resetMockApi()
  restore = setupMockApi()
  await authApi.login({ email: DEMO_EMAIL, password: DEMO_PASSWORD })
})
afterEach(() => restore())

it('목은 빈 배열·빈 문자열 계획을 받고, 검증은 빈 칸이 있으면 NEEDS_INPUT을 준다', async () => {
  const task = await tasksApi.create('101', {
    title: '짧은 계획',
    goal: '빈 목록 안내를 만든다.',
    included_scope: [],
    excluded_scope: [],
    approach: '',
    completion_criteria: ['안내가 보인다'],
    dependency_task_ids: [],
    design_refs: [],
    unlinked: [],
    no_contract_change: false,
  })
  const started = await validationsApi.create('101', task.id, {
    plan_revision_id: task.current_plan_revision.id,
    expected_lock_version: task.lock_version,
  })
  // 목은 두 번째 조회에서 검증을 끝낸다.
  await validationsApi.get('101', started.id)
  const validation = await validationsApi.get('101', started.id)
  expect(validation.status).toBe('NEEDS_INPUT')
  expect(validation.findings.map((finding) => finding.message)).toContain(
    '목표·포함 범위·접근 방법·완료 기준을 모두 작성해 주세요.',
  )
})
