import { AxiosError, AxiosHeaders, CanceledError } from 'axios'
import type {
  AxiosAdapter,
  AxiosResponse,
  InternalAxiosRequestConfig,
} from 'axios'
import { ZodError } from 'zod'
import { api } from '../api/client'
import { endpointRegistry } from '../api/contracts/endpoints'
import { accountRoutes } from './accounts'
import { changeRoutes } from './changes'
import { collaborationRoutes } from './collaboration'
import { documentRoutes } from './documents'
import { fail, MockApiError } from './helpers'
import type { MockContext, MockReply } from './helpers'
import {
  copy,
  loadMockDatabase,
  resetMockDatabase,
  saveMockDatabase,
} from './state'
import { taskRoutes } from './tasks'

export {
  DEMO_EMAIL,
  DEMO_PASSWORD,
  MOCK_NOTICE,
  MOCK_STORAGE_KEY,
} from './state'

const routes = endpointRegistry
  .map((endpoint) => {
    const names: string[] = []
    const pattern = endpoint.path.replace(/\{([^}]+)\}/g, (_, name: string) => {
      names.push(name)
      return '([^/]+)'
    })
    return { endpoint, names, pattern: new RegExp(`^${pattern}/?$`) }
  })
  .sort((a, b) => a.names.length - b.names.length)

const idempotency = new Map<string, { fingerprint: string; reply: MockReply }>()
let queue: Promise<void> = Promise.resolve()

function parseInput(config: InternalAxiosRequestConfig): {
  body: Record<string, unknown>
  form?: FormData
} {
  if (config.data instanceof FormData) {
    const body: Record<string, unknown> = {}
    config.data.forEach((value, key) => {
      body[key] = value
    })
    return { body, form: config.data }
  }
  if (typeof config.data === 'string' && config.data.trim()) {
    try {
      const body: unknown = JSON.parse(config.data)
      if (!body || typeof body !== 'object' || Array.isArray(body))
        fail(400, 'invalid_json', 'JSON 객체가 필요해요.')
      return { body: body as Record<string, unknown> }
    } catch (error) {
      if (error instanceof MockApiError) throw error
      fail(400, 'invalid_json', 'JSON 형식을 확인해 주세요.')
    }
  }
  return {
    body:
      config.data && typeof config.data === 'object'
        ? (config.data as Record<string, unknown>)
        : {},
  }
}

async function dispatch(
  config: InternalAxiosRequestConfig,
): Promise<AxiosResponse> {
  const response = (reply: MockReply): AxiosResponse => ({
    data: reply.status === 204 ? undefined : copyResponse(reply.data),
    status: reply.status ?? 200,
    statusText: String(reply.status ?? 200),
    headers: new AxiosHeaders({
      'x-beez-mode': 'mock',
      'x-request-id': 'browser-mock',
      ...reply.headers,
    }),
    config,
  })
  const database = loadMockDatabase()
  const before = copy(database)
  try {
    if (config.signal?.aborted) throw new CanceledError()
    if (/^[a-z][a-z\d+.-]*:/i.test(config.url ?? ''))
      fail(404, 'not_found', '예시 환경은 외부 주소를 호출하지 않아요.')
    const url = new URL(config.url ?? '', 'http://beez.mock')
    const path = url.pathname.replace(/^\/api(?=\/|$)/, '') || '/'
    const method = (config.method ?? 'get').toUpperCase()
    const route = routes.find(
      (item) => item.endpoint.method === method && item.pattern.test(path),
    )
    if (!route)
      fail(
        404,
        'not_found',
        '명세에 등록되지 않은 API 또는 아직 계약이 없는 확장 기능이에요.',
      )
    const match = path.match(route.pattern)!
    const params = Object.fromEntries(
      route.names.map((name, index) => [
        name,
        decodeURIComponent(match[index + 1]),
      ]),
    )
    const query = new URLSearchParams(url.search)
    if (config.params && typeof config.params === 'object')
      Object.entries(config.params as Record<string, unknown>).forEach(
        ([key, value]) => {
          if (value !== undefined && value !== null)
            query.set(key, String(value))
        },
      )
    const input = parseInput(config)
    const account = database.accounts.find(
      (item) => item.profile.id === database.session_account_id,
    )?.profile
    if (route.endpoint.auth !== 'PUBLIC' && !account)
      fail(401, 'unauthenticated', '먼저 로그인해 주세요.')
    const projectId = route.endpoint.path.startsWith('/projects/{id}')
      ? params.id
      : typeof input.body.project_id === 'string'
        ? input.body.project_id
        : query.get('project_id')
    const project = projectId
      ? database.projects.find((item) => item.project.id === projectId)
      : undefined
    const member = project?.members.find(
      (item) => item.account_id === account?.id,
    )
    if (projectId && (!project || !member))
      fail(404, 'not_found', '프로젝트를 찾을 수 없어요.')
    if (route.endpoint.auth === 'LEADER' && member?.role !== 'LEADER')
      fail(403, 'forbidden', '인간 팀장이 진행할 수 있어요.')
    const context: MockContext = {
      database,
      ...input,
      params,
      query,
      account,
      project,
      member,
    }
    const key = config.headers.get('Idempotency-Key')
    const scope =
      key && !route.endpoint.multipart && route.endpoint.id !== 'AGT-A01'
        ? `${account?.id ?? 'public'}:${projectId ?? ''}:${route.endpoint.id}:${String(key)}`
        : ''
    const fingerprint = JSON.stringify(input.body)
    const prior = scope ? idempotency.get(scope) : undefined
    if (prior) {
      if (prior.fingerprint !== fingerprint)
        fail(
          409,
          'idempotency_conflict',
          '같은 요청 키에 다른 입력이 전달됐어요.',
        )
      return response(prior.reply)
    }
    const reply =
      (await accountRoutes(route.endpoint.id, context)) ??
      (await documentRoutes(route.endpoint.id, context)) ??
      changeRoutes(route.endpoint.id, context) ??
      taskRoutes(route.endpoint.id, context) ??
      collaborationRoutes(route.endpoint.id, context)
    if (!reply)
      fail(
        404,
        'not_found',
        '이 기능은 브라우저 예시에서 지원하지 않아요. 실제 API 연동 후 사용할 수 있어요.',
      )
    const finalized = {
      ...reply,
      status: reply.status ?? (route.endpoint.noContent ? 204 : 200),
    }
    saveMockDatabase(database)
    if (scope) idempotency.set(scope, { fingerprint, reply: copy(finalized) })
    return response(finalized)
  } catch (error) {
    // A failed mutation must not leave partial document, baseline, or task updates.
    saveMockDatabase(before)
    if (error instanceof CanceledError) throw error
    const failure =
      error instanceof MockApiError
        ? error
        : error instanceof ZodError
          ? new MockApiError(
              400,
              'validation_failed',
              '요청 값이 API 명세와 맞지 않아요.',
            )
          : new MockApiError(
              500,
              'internal',
              '브라우저 예시 데이터를 처리하지 못했어요.',
            )
    const failedResponse = response({
      status: failure.status,
      data: { error: { code: failure.code, message: failure.message } },
    })
    throw new AxiosError(
      failure.message,
      failure.status >= 500
        ? AxiosError.ERR_BAD_RESPONSE
        : AxiosError.ERR_BAD_REQUEST,
      config,
      undefined,
      failedResponse,
    )
  }
}

function copyResponse(value: unknown): unknown {
  return value instanceof Blob || value === undefined ? value : copy(value)
}

/** Every request is resolved locally; no fetch/XHR fallback exists. */
export const mockAdapter: AxiosAdapter = (config) => {
  const pending = queue.then(() => dispatch(config))
  queue = pending.then(
    () => undefined,
    () => undefined,
  )
  return pending
}

/** Call only in VITE_API_MODE=mock (the application's default mode). */
export function setupMockApi(): () => void {
  const previous = api.defaults.adapter
  api.defaults.adapter = mockAdapter
  return () => {
    api.defaults.adapter = previous
  }
}

export function resetMockApi(): void {
  idempotency.clear()
  resetMockDatabase()
}
