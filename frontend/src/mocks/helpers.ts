import type { Account, Member, Page, Source } from '../api/contracts/common'
import type { MockDatabase, MockProject } from './state'
import { nextId, now } from './state'

export class MockApiError extends Error {
  status: number
  code: string

  constructor(status: number, code: string, message: string) {
    super(message)
    this.status = status
    this.code = code
  }
}

export interface MockContext {
  database: MockDatabase
  body: Record<string, unknown>
  form?: FormData
  params: Record<string, string>
  query: URLSearchParams
  account?: Account
  member?: Member
  project?: MockProject
}

export interface MockReply {
  data?: unknown
  status?: number
  headers?: Record<string, string>
}

export function fail(status: number, code: string, message: string): never {
  throw new MockApiError(status, code, message)
}

export function omit<T extends object, K extends keyof T>(
  value: T,
  key: K,
): Omit<T, K> {
  const result = { ...value }
  delete result[key]
  return result
}

export function requiredString(
  input: Record<string, unknown>,
  key: string,
): string {
  const value = input[key]
  if (typeof value !== 'string' || !value.trim())
    fail(400, 'validation_failed', `${key} 값을 입력해 주세요.`)
  return value.trim()
}

export function strings(value: unknown): string[] {
  return Array.isArray(value)
    ? value.filter((item): item is string => typeof item === 'string')
    : []
}

export function rows(value: unknown): Record<string, unknown>[] {
  return Array.isArray(value)
    ? value.filter(
        (item): item is Record<string, unknown> =>
          !!item && typeof item === 'object' && !Array.isArray(item),
      )
    : []
}

export function requireProject(context: MockContext): MockProject {
  return context.project ?? fail(404, 'not_found', '프로젝트를 찾을 수 없어요.')
}

export function requireAccount(context: MockContext): Account {
  return (
    context.account ?? fail(401, 'unauthenticated', '먼저 로그인해 주세요.')
  )
}

export function requireLeader(context: MockContext): void {
  if (context.member?.role !== 'LEADER')
    fail(403, 'forbidden', '인간 팀장이 진행할 수 있어요.')
}

export function findById<T extends { id: string }>(items: T[], id: string): T {
  return (
    items.find((item) => item.id === id) ??
    fail(404, 'not_found', '항목을 찾을 수 없어요.')
  )
}

export function expectValue(
  actual: unknown,
  expected: unknown,
  code = 'version_conflict',
): void {
  if (actual !== expected)
    fail(409, code, '조회한 기준이 변경됐어요. 최신 내용을 확인해 주세요.')
}

// 목에서 쓰는 sha256 모양 해시. 실제 SHA-256이 아니라 내용이 바뀌면 값이 바뀌는 것만 보장한다.
export function mockHash(value: unknown): string {
  const text = typeof value === 'string' ? value : JSON.stringify(value)
  let out = ''
  for (let seed = 0; seed < 8; seed++) {
    let hash = 0x811c9dc5 ^ seed
    for (let index = 0; index < text.length; index++) {
      hash ^= text.charCodeAt(index)
      hash = Math.imul(hash, 0x01000193)
    }
    out += (hash >>> 0).toString(16).padStart(8, '0')
  }
  return `sha256:${out}`
}

export function page<T extends { id: string }>(
  items: T[],
  query: URLSearchParams,
): Page<T> {
  const limit = Math.min(100, Math.max(1, Number(query.get('limit')) || 20))
  const sorted = [...items].sort((a, b) =>
    BigInt(a.id) < BigInt(b.id) ? 1 : -1,
  )
  const cursor = query.get('cursor')
  const start = cursor
    ? sorted.findIndex((item) => `mock-after-${item.id}` === cursor) + 1
    : 0
  if (cursor && !start)
    fail(400, 'validation_failed', '유효하지 않은 목록 위치예요.')
  const selected = sorted.slice(start, start + limit)
  const hasMore = start + limit < sorted.length
  return {
    items: selected,
    has_more: hasMore,
    ...(hasMore ? { next_cursor: `mock-after-${selected.at(-1)!.id}` } : {}),
  }
}

export function activity(
  context: MockContext,
  type: string,
  extra: Record<string, string> = {},
): void {
  const project = requireProject(context)
  project.activities.push({
    id: nextId(context.database),
    type,
    actor_account_id: requireAccount(context).id,
    result: 'SUCCESS',
    created_at: now(),
    ...extra,
  })
}

export function notification(
  context: MockContext,
  title: string,
  targetType:
    'TASK' | 'VALIDATION' | 'CHANGE_PROPOSAL' | 'BASELINE' | 'MEETING',
  targetId: string,
): void {
  const project = requireProject(context)
  context.database.notifications.push({
    id: nextId(context.database),
    account_id: requireAccount(context).id,
    project_id: project.project.id,
    type: 'MOCK_UPDATE',
    title,
    body: '브라우저 예시 데이터가 변경됐어요.',
    target_type: targetType,
    target_id: targetId,
    created_at: now(),
  })
}

export function templateGeneration(text: string, sources: Source[] = []) {
  return {
    origin: 'TEMPLATE' as const,
    text: `예시 응답 · ${text}`,
    sources,
    unresolved: [],
    usage: [],
  }
}
