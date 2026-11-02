/// <reference types="node" />
import { webcrypto } from 'node:crypto'
import { afterEach, beforeEach, expect, it } from 'vitest'
import { agentsApi, authApi } from '@/api/services'
import { getApiErrorCode, getApiErrorStatus } from '@/api/error'
import { DEMO_EMAIL, DEMO_PASSWORD, resetMockApi, setupMockApi } from './setup'

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

it('활성 토큰은 계정·프로젝트마다 5개까지이고 폐기하면 다시 발급한다', async () => {
  const issued = []
  for (let index = 0; index < 5; index++)
    issued.push(await agentsApi.issueToken('101', { label: `PC ${index}` }))
  const blocked = await agentsApi
    .issueToken('101', { label: '여섯째' })
    .catch((error: unknown) => error)
  expect(getApiErrorStatus(blocked)).toBe(409)
  expect(getApiErrorCode(blocked)).toBe('agent_limit_reached')
  // 다른 프로젝트는 따로 센다.
  await expect(agentsApi.issueToken('102', {})).resolves.toBeTruthy()
  await agentsApi.revokeToken(issued[0].token_info.id)
  await expect(agentsApi.issueToken('101', {})).resolves.toBeTruthy()
})

it('label은 앞뒤 공백을 빼고 비면 빼며, 100자를 넘으면 거절한다', async () => {
  const trimmed = await agentsApi.issueToken('101', { label: '  내 PC  ' })
  expect(trimmed.token_info.label).toBe('내 PC')
  const empty = await agentsApi.issueToken('101', { label: '   ' })
  expect(empty.token_info).not.toHaveProperty('label')
  const long = await agentsApi
    .issueToken('101', { label: 'a'.repeat(101) })
    .catch((error: unknown) => error)
  expect(getApiErrorCode(long)).toBe('validation_failed')
})

it('내 토큰 목록은 폐기 토큰도 id 내림차순으로 주고 프로젝트로 거른다', async () => {
  const first = await agentsApi.issueToken('101', {})
  const second = await agentsApi.issueToken('101', {})
  await agentsApi.issueToken('102', {})
  await agentsApi.revokeToken(first.token_info.id)
  const page = await agentsApi.myTokens({ project_id: '101' })
  expect(page.items.map((token) => token.id)).toEqual([
    second.token_info.id,
    first.token_info.id,
  ])
  expect(page.items[1].revoked_at).toBeTruthy()
})
