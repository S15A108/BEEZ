import { isAxiosError, type AxiosRequestConfig } from 'axios'
import type { z } from 'zod'
import { api } from '@/api/client'
import { apiErrorSchema } from '@/api/error'

export interface RequestOptions {
  signal?: AbortSignal
  idempotencyKey?: string
}
interface ContractRequest extends AxiosRequestConfig<unknown> {
  idempotent?: boolean
}

// 응답을 잃은 동작은 같은 키로 재확인한다. 비밀값·본문은 저장소나 로그에 남기지 않는다.
const unresolvedRequests = new Map<string, string>()

async function stableValue(value: unknown): Promise<unknown> {
  if (value instanceof FormData) {
    return Promise.all(
      [...value.entries()]
        .sort(([left], [right]) => left.localeCompare(right))
        .map(async ([key, entry]) => [key, await stableValue(entry)]),
    )
  }
  if (value instanceof Blob) {
    const buffer =
      typeof value.arrayBuffer === 'function'
        ? await value.arrayBuffer()
        : await new Promise<ArrayBuffer>((resolve, reject) => {
            const reader = new FileReader()
            reader.onload = () =>
              reader.result instanceof ArrayBuffer
                ? resolve(reader.result)
                : reject(new Error('파일을 확인하지 못했습니다.'))
            reader.onerror = () =>
              reject(new Error('파일을 확인하지 못했습니다.'))
            reader.readAsArrayBuffer(value)
          })
    return [
      value.type,
      Reflect.get(value, 'name'),
      Array.from(new Uint8Array(buffer)),
    ]
  }
  if (Array.isArray(value)) return Promise.all(value.map(stableValue))
  if (value && typeof value === 'object') {
    return Object.fromEntries(
      await Promise.all(
        Object.entries(value)
          .filter(([, entry]) => entry !== undefined)
          .sort(([left], [right]) => left.localeCompare(right))
          .map(async ([key, entry]) => [key, await stableValue(entry)]),
      ),
    )
  }
  return value
}

async function requestFingerprint(
  request: AxiosRequestConfig<unknown>,
): Promise<string> {
  const serialized = JSON.stringify(
    await stableValue([
      request.method,
      request.url,
      request.params,
      request.data,
    ]),
  )
  if (!crypto.subtle) return serialized
  const digest = await crypto.subtle.digest(
    'SHA-256',
    new TextEncoder().encode(serialized),
  )
  return Array.from(new Uint8Array(digest), (byte) =>
    byte.toString(16).padStart(2, '0'),
  ).join('')
}
export async function callEndpoint<S extends z.ZodType>(
  schema: S,
  config: ContractRequest,
  options?: RequestOptions,
): Promise<z.output<S>> {
  const { idempotent, ...request } = config
  const fingerprint = idempotent ? await requestFingerprint(request) : undefined
  const key = fingerprint
    ? (options?.idempotencyKey ??
      unresolvedRequests.get(fingerprint) ??
      crypto.randomUUID())
    : undefined
  if (fingerprint && key) unresolvedRequests.set(fingerprint, key)
  try {
    const response = await api.request<unknown>({
      ...request,
      signal: options?.signal,
      headers: {
        ...request.headers,
        ...(key ? { 'Idempotency-Key': key } : {}),
      },
    })
    const result = schema.parse(
      response.status === 204 ? undefined : response.data,
    )
    if (fingerprint) unresolvedRequests.delete(fingerprint)
    if (
      request.url === '/auth/login' ||
      request.url === '/auth/logout' ||
      request.url === '/me/withdraw'
    )
      unresolvedRequests.clear()
    return result
  } catch (error) {
    // 결과가 확정된 실패는 키를 버려 다음 시도가 새 키로 다시 실행되게 한다. 입력·권한 오류(4xx)와, 서버가 오류 코드로
    // 실패를 알려 준 5xx(ai_unavailable 등)가 그렇다. 서버는 실패한 키를 다시 실행하지 않는다(PR #72 리뷰).
    // 응답을 못 받았거나 본문 없는 5xx(게이트웨이 등)는 처리됐는지 알 수 없어 같은 키로 재확인한다.
    // 다만 '아직 처리 중'(409 review_in_progress)은 실패가 아니라 키를 남겨 같은 키로 결과를 다시 확인한다. 새 키로 보내면
    // 서버가 같은 리비전을 다시 검토해 AI 검토가 두 번 돈다(PR #72 5차 조사 FIFTH-DOC-01).
    const response = isAxiosError(error) ? error.response : undefined
    const body = apiErrorSchema.safeParse(response?.data)
    const decided =
      !!response &&
      response.status >= 400 &&
      (response.status < 500 || body.success) &&
      body.data?.error.code !== 'review_in_progress'
    if (fingerprint && decided) unresolvedRequests.delete(fingerprint)
    throw error
  }
}
export function toFormData(input: Record<string, unknown>): FormData {
  const data = new FormData()
  for (const [key, value] of Object.entries(input)) {
    if (value instanceof Blob) data.append(key, value)
    else if (value !== undefined) data.append(key, String(value))
  }
  return data
}
