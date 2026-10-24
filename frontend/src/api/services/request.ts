import { isAxiosError, type AxiosRequestConfig } from 'axios'
import type { z } from 'zod'
import { api } from '@/api/client'

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
    // 전송 결과가 확실한 입력·권한 오류는 새 입력을 허용하고, 결과 불명 상태는 키를 유지한다.
    const status = isAxiosError(error) ? error.response?.status : undefined
    if (fingerprint && status !== undefined && status >= 400 && status < 500)
      unresolvedRequests.delete(fingerprint)
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
