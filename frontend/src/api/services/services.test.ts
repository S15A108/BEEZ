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
import {
  authApi,
  changesApi,
  documentsApi,
  projectsApi,
  validationsApi,
} from './index'

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

  // PR #72 리뷰 3: 서버가 오류 코드로 실패를 확정한 5xx 뒤에는 새 키로 다시 실행하고, 본문 없는 5xx는 같은 키로 재확인한다.
  it('실패가 확정된 5xx 뒤에는 새 키를 쓰고, 결과를 모르는 5xx는 키를 유지한다', async () => {
    const keys: string[] = []
    const failures = [
      { status: 503, data: { error: { code: 'ai_unavailable' } } },
      { status: 502, data: '' },
    ]
    api.defaults.adapter = async (config) => {
      keys.push(String(config.headers.get('Idempotency-Key')))
      const failure = failures.shift()
      if (failure)
        throw new AxiosError('failed', 'ERR_BAD_RESPONSE', config, undefined, {
          config,
          data: failure.data,
          status: failure.status,
          statusText: '',
          headers: new AxiosHeaders(),
        })
      return {
        config,
        data: project,
        status: 201,
        statusText: 'Created',
        headers: new AxiosHeaders(),
      }
    }
    const create = () => projectsApi.create({ name: '확정된 실패 뒤 재시도' })
    await expect(create()).rejects.toBeInstanceOf(AxiosError)
    await expect(create()).rejects.toBeInstanceOf(AxiosError)
    await create()
    expect(keys[1]).not.toBe(keys[0])
    expect(keys[2]).toBe(keys[1])
  })

  // PR #72 5차 조사 FIFTH-DOC-01: 비비 검토가 아직 도는 중(409 review_in_progress)이면 키를 지우지 않고 같은 키로 다시 확인한다.
  // 다른 409처럼 실패가 확정된 경우는 그대로 새 키를 쓴다.
  it('검토가 진행 중이라는 409 뒤에는 같은 키로 다시 확인하고, 확정된 409 뒤에는 새 키를 쓴다', async () => {
    const keys: string[] = []
    const failures = [
      { status: 409, code: 'review_in_progress' },
      { status: 409, code: 'version_conflict' },
    ]
    api.defaults.adapter = async (config) => {
      keys.push(String(config.headers.get('Idempotency-Key')))
      const failure = failures.shift()
      if (failure)
        throw new AxiosError('conflict', 'ERR_BAD_REQUEST', config, undefined, {
          config,
          data: { error: { code: failure.code, message: '' } },
          status: failure.status,
          statusText: 'Conflict',
          headers: new AxiosHeaders(),
        })
      return {
        config,
        data: project,
        status: 201,
        statusText: 'Created',
        headers: new AxiosHeaders(),
      }
    }
    const create = () => projectsApi.create({ name: '검토 진행 중 재확인' })
    await expect(create()).rejects.toBeInstanceOf(AxiosError)
    await expect(create()).rejects.toBeInstanceOf(AxiosError)
    await create()
    expect(keys[1]).toBe(keys[0])
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

  it('121개 REST 계약은 중복 없이 기록하고 확장 미결 기능에는 실행 경로를 만들지 않는다', () => {
    expect(endpointRegistry).toHaveLength(121)
    expect(new Set(endpointRegistry.map((endpoint) => endpoint.id)).size).toBe(
      121,
    )
    expect(
      new Set(
        endpointRegistry.map(
          (endpoint) => `${endpoint.method} ${endpoint.path}`,
        ),
      ).size,
    ).toBe(121)
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

  // 서버(ExplicitImpactCalculator)는 담당자가 없는 작업이면 explicit 항목에서 member_id를 생략한다.
  it('미배정 작업이 영향 대상이어도 분석 시작과 영향 조회 응답을 받는다', async () => {
    const body = {
      explicit: [{ task_id: '501', design_item_id: '211' }],
      candidates: [],
      analysis_status: 'RUNNING',
      revision: 1,
      snapshot_hash: 'sha256:0123',
      is_current: true,
      run_id: '9',
    }
    respond(body, 202)
    const started = await changesApi.retryImpact('101', '71')
    expect(started.explicit[0]).not.toHaveProperty('member_id')
    respond({ ...body, analysis_status: 'COMPLETED' })
    const read = await changesApi.impact('101', '71')
    expect(read.analysis_status).toBe('COMPLETED')
    expect(read.explicit).toEqual([{ task_id: '501', design_item_id: '211' }])
  })

  it('영향 조회에서 담당자 추천과 근거를 보존한다', async () => {
    const ownerRecommendations = {
      proposal_revision: 1,
      snapshot_hash: 'sha256:0123',
      direct_reference_status: 'COMPLETE',
      direct_reference_reasons: [],
      document_reference_status: 'INCOMPLETE',
      document_reference_reasons: ['document_mapping_unavailable'],
      ai_analysis_status: 'NOT_EVALUATED',
      ai_current: true,
      ai_reanalysis_in_progress: false,
      withheld_ai_candidates: 0,
      recommendations: [
        {
          member_id: '21',
          area: '백엔드',
          evidence: [
            {
              type: 'DIRECT_TASK_REFERENCE',
              reason: '현재 작업 계획이 변경 항목을 직접 참조합니다.',
              task_id: '501',
              task_key: 'TASK-001',
              plan_revision_id: '511',
              design_item_id: '301',
              design_item_version_id: '311',
              referenced_design_item_version_id: '311',
              sources: [],
            },
            {
              type: 'DOCUMENT_SOURCE',
              reason: '변경 문서의 출처 설계를 참조합니다.',
              task_id: '502',
              task_key: 'TASK-002',
              plan_revision_id: '512',
              design_item_id: '302',
              design_item_version_id: '312',
              referenced_design_item_version_id: '312',
              document_id: '201',
              document_version_id: '211',
              sources: [],
            },
          ],
        },
      ],
      notification_candidate_member_ids: ['21'],
    }
    respond({
      explicit: [],
      candidates: [],
      analysis_status: 'NOT_EVALUATED',
      owner_recommendations: ownerRecommendations,
    })
    const read = await changesApi.impact('101', '71')
    expect(read.owner_recommendations).toEqual(ownerRecommendations)
  })
})

// PR #72 mock/live 전수 대조 DOC-LIVE-1: 백엔드가 Idempotency-Key를 필수로 받는 문서 분류(DOC-A07)와
// 변경 제안 재제출(CHG-A04)도 키를 보낸다. 빠지면 서버가 400으로 거절한다.
describe('멱등 키가 필수인 수정 요청', () => {
  it('문서 분류와 변경 제안 재제출에 Idempotency-Key를 보낸다', async () => {
    respond({})
    await documentsApi
      .update(
        '101',
        '201',
        { domain: '공통' },
        { idempotencyKey: 'classify-once' },
      )
      .catch(() => undefined)
    expect(lastRequest?.method).toBe('patch')
    expect(lastRequest?.headers.get('Idempotency-Key')).toBe('classify-once')
    await changesApi
      .update(
        '101',
        '701',
        {
          base_baseline_id: '401',
          title: '다시 제출',
          reason: '반려 사유 반영',
          items: [],
          documents: [],
          expected_revision: 2,
          expected_snapshot_hash: `sha256:${'a'.repeat(64)}`,
        },
        { idempotencyKey: 'resubmit-once' },
      )
      .catch(() => undefined)
    expect(lastRequest?.method).toBe('put')
    expect(lastRequest?.headers.get('Idempotency-Key')).toBe('resubmit-once')
    expect(
      endpointRegistry
        .filter((endpoint) => ['DOC-A07', 'CHG-A04'].includes(endpoint.id))
        .every((endpoint) => endpoint.idempotent),
    ).toBe(true)
  })
})
