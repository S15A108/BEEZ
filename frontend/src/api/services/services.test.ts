import {
  AxiosError,
  AxiosHeaders,
  type InternalAxiosRequestConfig,
} from 'axios'
import { afterEach, describe, expect, it } from 'vitest'
import { ZodError } from 'zod'
import { api } from '@/api/client'
import {
  changeItemSchema,
  documentChangeSchema,
  idSchema,
  taskStateInputSchema,
} from '@/api/contracts/common'
import {
  endpointRegistry,
  unspecifiedCapabilities,
} from '@/api/contracts/endpoints'
import {
  getApiErrorCode,
  getApiErrorMessage,
  getApiErrorStatus,
} from '@/api/error'
import { authApi, documentsApi, projectsApi, validationsApi } from './index'

const initialAdapter = api.defaults.adapter
const timestamp = '2026-10-23T03:00:00.123456Z'
const project = {
  id: '9007199254740993123',
  name: '승인 설계',
  my_role: 'LEADER',
  created_at: timestamp,
  updated_at: timestamp,
}
let lastRequest: InternalAxiosRequestConfig | undefined

function respond(data: unknown, status = 200) {
  api.defaults.adapter = async (config) => {
    lastRequest = config
    return {
      config,
      data,
      status,
      statusText: 'OK',
      headers: new AxiosHeaders(),
    }
  }
}

afterEach(() => {
  api.defaults.adapter = initialAdapter
  lastRequest = undefined
})

describe('명세 REST 교환', () => {
  it('응답을 잃은 같은 동작의 재시도는 키를 유지하고 확인된 새 동작은 새 키를 쓴다', async () => {
    const keys: string[] = []
    api.defaults.adapter = async (config) => {
      keys.push(String(config.headers.get('Idempotency-Key')))
      if (keys.length === 1)
        throw new AxiosError('response lost', 'ERR_NETWORK', config)
      return {
        config,
        data: project,
        status: 201,
        statusText: 'Created',
        headers: new AxiosHeaders(),
      }
    }
    await expect(
      projectsApi.create({ name: '동일 요청 재확인' }),
    ).rejects.toBeInstanceOf(AxiosError)
    await projectsApi.create({ name: '동일 요청 재확인' })
    expect(keys[1]).toBe(keys[0])
    await projectsApi.create({ name: '동일 요청 재확인' })
    expect(keys[2]).not.toBe(keys[1])
  })

  it('문자열 BIGINT와 선택 필드 생략을 유지하며 페이지의 중첩 DTO까지 검증한다', async () => {
    respond({ items: [project], has_more: false })
    const result = await projectsApi.list({ limit: 20 })
    expect(result.items[0].id).toBe('9007199254740993123')
    expect(result.items[0].description).toBeUndefined()
    expect(lastRequest?.url).toBe('/projects')
    expect(lastRequest?.params).toEqual({ limit: 20 })
    expect(lastRequest?.withCredentials).toBe(true)

    respond({ items: [{ ...project, id: 101 }], has_more: false })
    await expect(projectsApi.list()).rejects.toBeInstanceOf(ZodError)
    respond({ items: [{ ...project, description: null }], has_more: false })
    await expect(projectsApi.list()).rejects.toBeInstanceOf(ZodError)
    respond({ items: [project], has_more: true })
    await expect(projectsApi.list()).rejects.toBeInstanceOf(ZodError)
  })

  it('본문 없는 204를 파싱하지 않고 PATCH·DELETE의 메서드와 본문을 유지한다', async () => {
    respond('', 204)
    await expect(authApi.logout()).resolves.toBeUndefined()
    expect(lastRequest?.method).toBe('post')
    await expect(
      projectsApi.delete('101', { confirm_name: '승인 설계' }),
    ).resolves.toBeUndefined()
    expect(lastRequest?.method).toBe('delete')
    expect(JSON.parse(String(lastRequest?.data))).toEqual({
      confirm_name: '승인 설계',
    })

    respond(project)
    await projectsApi.update('101', { description: '이름은 유지' })
    expect(lastRequest?.method).toBe('patch')
    expect(JSON.parse(String(lastRequest?.data))).toEqual({
      description: '이름은 유지',
    })
    expect(() => projectsApi.update('101', {})).toThrow(ZodError)
  })

  it('multipart 업로드와 원본 다운로드를 JSON 요청·응답과 구분한다', async () => {
    const version = {
      id: '301',
      document_id: '201',
      version_no: 1,
      status: 'DRAFT',
      file_name: 'plan.md',
      media_type: 'text/markdown',
      content_hash: 'hash',
      created_at: timestamp,
    }
    respond(
      {
        document: {
          id: '201',
          project_id: '101',
          kind: 'PLAN',
          title: '기획안',
          latest_draft: version,
        },
        analysis_status: 'PENDING',
      },
      201,
    )
    await documentsApi.upload(
      '101',
      {
        file: new File(['기획'], 'plan.md', { type: 'text/markdown' }),
        kind: 'PLAN',
        title: '기획안',
      },
      { idempotencyKey: 'upload-once' },
    )
    expect(lastRequest?.data).toBeInstanceOf(FormData)
    expect((lastRequest?.data as FormData).get('kind')).toBe('PLAN')
    expect(lastRequest?.headers.get('Idempotency-Key')).toBe('upload-once')

    const file = new Blob(['원본'], { type: 'text/markdown' })
    respond(file)
    await expect(documentsApi.download('101', '201', 1)).resolves.toBe(file)
    expect(lastRequest?.responseType).toBe('blob')
    expect(lastRequest?.url).toBe('/projects/101/documents/201/versions/1/file')
  })

  it('검증 202를 완료로 만들지 않고 같은 ID를 조회한다', async () => {
    const validation = {
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
      created_at: timestamp,
    }
    respond(validation, 202)
    const accepted = await validationsApi.create(
      '101',
      '501',
      { plan_revision_id: '511', expected_lock_version: 2 },
      { idempotencyKey: 'validate-once' },
    )
    expect(accepted.status).toBe('RUNNING')
    expect(lastRequest?.url).toBe('/projects/101/tasks/501/validations')
    expect(lastRequest?.headers.get('Idempotency-Key')).toBe('validate-once')

    respond({ ...validation, status: 'PASS', finished_at: timestamp })
    expect((await validationsApi.get('101', accepted.id)).status).toBe('PASS')
    expect(lastRequest?.url).toBe('/projects/101/validations/601')
    expect(lastRequest?.method).toBe('get')
  })

  it('오류 코드와 HTTP 상태는 보존하며 미확인 서버 메시지를 화면 안내로 노출하지 않는다', async () => {
    api.defaults.adapter = async (config) => {
      throw new AxiosError('conflict', 'ERR_BAD_REQUEST', config, undefined, {
        config,
        status: 409,
        statusText: 'Conflict',
        headers: new AxiosHeaders(),
        data: {
          error: {
            code: 'revision_conflict',
            message: 'PRIVATE-BACKEND-SECRET',
          },
        },
      })
    }
    let failure: unknown
    try {
      await projectsApi.get('101')
    } catch (error) {
      failure = error
    }
    expect(getApiErrorCode(failure)).toBe('revision_conflict')
    expect(getApiErrorStatus(failure)).toBe(409)
    expect(getApiErrorMessage(failure)).toContain('작업 계획이 변경')
    expect(getApiErrorMessage(failure)).not.toContain('PRIVATE-BACKEND-SECRET')
  })
})

describe('계약 경계', () => {
  it('문서·설계 버전과 변경 종류의 조건부 필드를 확인한다', () => {
    expect(idSchema.safeParse('p-101').success).toBe(false)
    expect(
      documentChangeSchema.safeParse({
        document_id: '201',
        base_document_version_id: '301',
      }).success,
    ).toBe(false)
    expect(
      documentChangeSchema.safeParse({
        document_id: '201',
        base_document_version_id: '301',
        after_content: '수정',
        proposed_document_version_id: '302',
      }).success,
    ).toBe(false)
    expect(
      changeItemSchema.safeParse({
        operation: 'CREATE',
        kind: 'POLICY',
        schema_version: 1,
        proposed_content: { description: '검토 대상 구조' },
      }).success,
    ).toBe(true)
    expect(
      changeItemSchema.safeParse({ operation: 'UPDATE', design_item_id: '211' })
        .success,
    ).toBe(false)
    expect(
      taskStateInputSchema.safeParse({
        event: 'START',
        plan_revision_id: '511',
        expected_lock_version: 1,
      }).success,
    ).toBe(false)
  })

  it('108개 REST 계약은 중복 없이 기록하고 확장 미결 기능에는 실행 경로를 만들지 않는다', () => {
    expect(endpointRegistry).toHaveLength(108)
    expect(new Set(endpointRegistry.map((endpoint) => endpoint.id)).size).toBe(
      108,
    )
    expect(
      new Set(
        endpointRegistry.map(
          (endpoint) => `${endpoint.method} ${endpoint.path}`,
        ),
      ).size,
    ).toBe(108)
    expect(
      endpointRegistry.find((endpoint) => endpoint.id === 'PLN-A10')?.path,
    ).toBe('/human/baselines')
    expect(
      endpointRegistry.find((endpoint) => endpoint.id === 'NTF-A01')?.path,
    ).toBe('/me/notifications')
    expect(
      endpointRegistry.some((endpoint) =>
        endpoint.path.includes('/human/spec'),
      ),
    ).toBe(false)
    expect(
      unspecifiedCapabilities.every(
        (capability) => capability.availability === 'UNSPECIFIED',
      ),
    ).toBe(true)
  })
})
