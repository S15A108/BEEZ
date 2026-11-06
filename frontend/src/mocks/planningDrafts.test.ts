/// <reference types="node" />
import { webcrypto } from 'node:crypto'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { authApi, documentsApi, planningApi } from '../api/services'
import {
  answerQuestionInputSchema,
  draftsResponseSchema,
  questionsResponseSchema,
} from '../features/planning/schemas'
import { DEMO_EMAIL, DEMO_PASSWORD, resetMockApi, setupMockApi } from './setup'

let restoreAdapter: () => void

beforeEach(() => {
  Object.defineProperty(globalThis.crypto, 'subtle', {
    configurable: true,
    value: webcrypto.subtle,
  })
  localStorage.clear()
  resetMockApi()
  restoreAdapter = setupMockApi()
})
afterEach(() => restoreAdapter())

const login = () =>
  authApi.login({ email: DEMO_EMAIL, password: DEMO_PASSWORD })

// 프로젝트 102(기준 발행 전)의 문서 버전 4개로 초안 작업을 만들고 결과까지 기다린다.
async function generate() {
  const documents = await documentsApi.list('102')
  await planningApi.generateDrafts('102', {
    document_version_ids: documents.items.map((item) => item.latest_draft!.id),
  })
  await planningApi.drafts('102')
  return (await planningApi.drafts('102')).jobs
}

describe('답변 입력 계약', () => {
  it('답변이나 닫기 중 하나만 받고, 닫을 때는 답변을 함께 보내지 않는다', () => {
    expect(answerQuestionInputSchema.safeParse({ answer: '네' }).success).toBe(
      true,
    )
    expect(
      answerQuestionInputSchema.safeParse({
        answer_payload: { type: 'CHOICE', selected: ['팀장만'] },
      }).success,
    ).toBe(true)
    expect(
      answerQuestionInputSchema.safeParse({ status: 'DISMISSED' }).success,
    ).toBe(true)
    expect(answerQuestionInputSchema.safeParse({}).success).toBe(false)
    expect(
      answerQuestionInputSchema.safeParse({
        status: 'DISMISSED',
        answer: '네',
      }).success,
    ).toBe(false)
  })
})

describe('보완 질문 예시 응답', () => {
  it('질문 목록은 유형·순번·전체 수를 주고 id 순으로 나온다', async () => {
    await login()
    const list = await planningApi.questions('102')
    expect(list.total_count).toBe(5)
    expect(list.items.map((item) => item.number)).toEqual([1, 2, 3, 4, 5])
    expect(list.items.map((item) => item.question_type)).toEqual([
      'SHORT_ANSWER',
      'CHOICE',
      'CHOICE',
      'ESSAY',
      'SHORT_ANSWER',
    ])
    expect(list.items[3].evidence_status).toBe('UNVERIFIED')
    expect(list.items[4].status).toBe('DISMISSED')
  })

  it('선택형은 선택지·기타·복수 선택 규칙을 서버처럼 검사한다', async () => {
    await login()
    const answer = (id: string, input: object) =>
      planningApi.answerQuestion('102', id, input)
    const saved = await answer('63', {
      answer_payload: { type: 'CHOICE', selected: ['팀장만'] },
    })
    expect(saved).toMatchObject({ status: 'ANSWERED', answer: '팀장만' })
    // 선택지와 다른 문장은 기타 입력으로 본다.
    const other = await answer('63', { answer: '팀장이 지정한 사람' })
    expect(other.answer_payload).toEqual({
      type: 'CHOICE',
      selected: [],
      other_text: '팀장이 지정한 사람',
    })
    await expect(
      answer('63', {
        answer_payload: {
          type: 'CHOICE',
          selected: ['팀장만', '팀장과 팀원 모두'],
        },
      }),
    ).rejects.toMatchObject({ response: { status: 400 } })
    // 64번은 기타를 받지 않고 여러 개를 고를 수 있다.
    await expect(answer('64', { answer: '문자' })).rejects.toMatchObject({
      response: { status: 400 },
    })
    const multi = await answer('64', {
      answer_payload: { type: 'CHOICE', selected: ['앱 안 알림', '이메일'] },
    })
    expect(multi.answer).toBe('앱 안 알림, 이메일')
  })

  it('서술형은 길이 상한을 넘기면 거절하고, 답 없이 닫으면 이전 답변을 지운다', async () => {
    await login()
    await expect(
      planningApi.answerQuestion('102', '65', {
        answer_payload: { type: 'ESSAY', text: '가'.repeat(501) },
      }),
    ).rejects.toMatchObject({ response: { status: 400 } })
    await planningApi.answerQuestion('102', '65', {
      answer: '팀장만 승인해요.',
    })
    const closed = await planningApi.answerQuestion('102', '65', {
      status: 'DISMISSED',
    })
    expect(closed.status).toBe('DISMISSED')
    expect(closed.answer).toBeUndefined()
  })

  it('팀원은 질문에 답할 수 없다', async () => {
    await authApi.login({ email: 'member@beez.local', password: DEMO_PASSWORD })
    await expect(
      planningApi.answerQuestion('102', '63', { answer: '팀장만' }),
    ).rejects.toMatchObject({ response: { status: 403 } })
  })

  it('질문 생성 실패 스위치는 상태에 드러나고 재시도하면 풀린다', async () => {
    await login()
    const document = (await documentsApi.list('102')).items[0]
    const query = { document_version_id: document.latest_draft!.id }
    expect(
      (await planningApi.analysis('102', document.id, query))
        .question_generation,
    ).toEqual({ status: 'COMPLETED' })
    localStorage.setItem('beez.mock.planQuestions', 'failed')
    expect(
      (await planningApi.analysis('102', document.id, query))
        .question_generation,
    ).toMatchObject({
      status: 'FAILED',
      error_code: 'ai_unavailable',
      retryable: true,
    })
    const retried = await planningApi.retryAnalysis('102', document.id, query)
    expect(retried.question_generation?.status).toBe('PENDING')
    expect(
      (await planningApi.analysis('102', document.id, query))
        .question_generation?.status,
    ).toBe('COMPLETED')
  })
})

describe('설계 초안 후보 예시 응답', () => {
  it('접수하면 작업이 대기→진행→완료 순으로 나아가고 후보를 만든다', async () => {
    await login()
    const documents = await documentsApi.list('102')
    const accepted = await planningApi.generateDrafts('102', {
      document_version_ids: documents.items.map(
        (item) => item.latest_draft!.id,
      ),
    })
    expect(accepted.jobs).toHaveLength(4)
    expect((await planningApi.drafts('102')).jobs[0].status).toBe('RUNNING')
    const { jobs } = await planningApi.drafts('102')
    expect(jobs.every((job) => job.status === 'COMPLETED')).toBe(true)
    const candidates = jobs.flatMap((job) => job.candidates)
    expect(candidates.map((item) => item.change)).toContain('UPDATE')
    expect(candidates.map((item) => item.support)).toContain('NONE')
    // 후보를 채택하기 전에는 설계 항목이 바뀌지 않는다.
    expect(
      (await planningApi.items('102', { status: 'DRAFT' })).items,
    ).toHaveLength(1)
  })

  it('같은 문서 버전의 작업이 진행 중이면 draft_in_progress 로 막는다', async () => {
    await login()
    const documents = await documentsApi.list('102')
    const input = {
      document_version_ids: [documents.items[0].latest_draft!.id],
    }
    await planningApi.generateDrafts('102', input)
    await expect(
      planningApi.generateDrafts('102', input),
    ).rejects.toMatchObject({
      response: { status: 409, data: { error: { code: 'draft_in_progress' } } },
    })
  })

  it('근거가 없는 후보는 확인해야 채택하고, 충돌 필드는 모두 확정해야 한다', async () => {
    await login()
    await planningApi.answerQuestion('102', '65', {
      answer: '팀장만 승인해요.',
    })
    const jobs = await generate()
    const candidates = jobs.flatMap((job) => job.candidates)
    const none = candidates.find((item) => item.support === 'NONE')!
    await expect(
      planningApi.adoptCandidate('102', none.id),
    ).rejects.toMatchObject({ response: { status: 400 } })
    const adopted = await planningApi.adoptCandidate('102', none.id, {
      confirm_no_evidence: true,
    })
    expect(adopted.status).toBe('ADOPTED')
    // 같은 후보를 다시 채택하면 처음 결과 그대로다.
    expect(await planningApi.adoptCandidate('102', none.id)).toEqual(adopted)
    const conflicted = candidates.find(
      (item) => item.conflicting_fields.length > 0,
    )!
    expect(conflicted.change).toBe('UPDATE')
    await expect(
      planningApi.adoptCandidate('102', conflicted.id),
    ).rejects.toMatchObject({ response: { status: 400 } })
    await planningApi.adoptCandidate('102', conflicted.id, {
      resolved_fields: ['description'],
    })
    await expect(
      planningApi.discardCandidate('102', conflicted.id),
    ).rejects.toMatchObject({
      response: { status: 409, data: { error: { code: 'candidate_adopted' } } },
    })
  })

  it('폐기한 후보는 채택할 수 없고, stale 스위치는 그 후보만 막는다', async () => {
    await login()
    const candidates = (await generate()).flatMap((job) => job.candidates)
    const create = candidates.filter((item) => item.change === 'CREATE')
    const discarded = await planningApi.discardCandidate('102', create[0].id)
    expect(discarded.status).toBe('DISCARDED')
    await expect(
      planningApi.adoptCandidate('102', create[0].id),
    ).rejects.toMatchObject({
      response: {
        status: 409,
        data: { error: { code: 'candidate_discarded' } },
      },
    })
    localStorage.setItem('beez.mock.planDraft', 'stale')
    await expect(
      planningApi.adoptCandidate('102', create[1].id),
    ).rejects.toMatchObject({
      response: { status: 409, data: { error: { code: 'candidate_stale' } } },
    })
  })

  it('실패 스위치를 켜면 작업이 재시도 가능한 오류로 끝난다', async () => {
    await login()
    localStorage.setItem('beez.mock.planDraft', 'failed')
    const jobs = await generate()
    expect(jobs[0]).toMatchObject({
      status: 'FAILED',
      error_code: 'ai_unavailable',
      retryable: true,
    })
    expect(jobs[0].candidates).toEqual([])
  })
})

describe('명세 응답 모양', () => {
  it('예전 질문과 232 이후 질문 목록을 모두 받는다', () => {
    const parsed = questionsResponseSchema.parse({
      items: [
        { id: '60', question: '예전 질문' },
        {
          id: '61',
          number: 1,
          question: '프로젝트 삭제 권한은 팀장만 가지나요?',
          kind: 'MISSING_CANDIDATE',
          question_type: 'CHOICE',
          answer_format: {
            type: 'CHOICE',
            options: ['팀장만', '팀원도'],
            multiple: false,
            allow_other: true,
          },
          status: 'ANSWERED',
          evidence_status: 'REVIEW_CANDIDATE',
          answer: '팀장만',
          answer_payload: { type: 'CHOICE', selected: ['팀장만'] },
        },
      ],
      total_count: 2,
      has_more: false,
    })
    expect(parsed.total_count).toBe(2)
    expect(parsed.items[0].status).toBeUndefined()
  })

  it('작업과 후보 조회 응답은 모르는 근거 키를 남기고 빠진 배열을 비워 둔다', () => {
    const parsed = draftsResponseSchema.parse({
      jobs: [
        {
          id: '71',
          document_version_id: '211',
          status: 'COMPLETED',
          created_at: '2026-11-03T03:00:00Z',
          updated_at: '2026-11-03T03:00:05Z',
          unresolved: [],
          omitted_items: 0,
          origin: 'MODEL',
          candidates: [
            {
              id: '81',
              job_id: '71',
              candidate_key: 'REQ-001',
              change: 'UPDATE',
              kind: 'REQUIREMENT',
              status: 'PENDING',
              base_design_item_version_id: '311',
              base_status: 'DRAFT',
              before: { description: '이전' },
              after: { description: '이후' },
              changed_fields: ['description'],
              conflicting_fields: [],
              sources: [
                {
                  document_id: '201',
                  document_version_id: '211',
                  version_no: 1,
                  block_id: 'b1',
                  location: '3. 계정 관리',
                  quote: '삭제',
                },
              ],
              answer_refs: ['q-61'],
              support: 'DOCUMENT_AND_ANSWER',
            },
            {
              id: '82',
              candidate_key: 'new-1',
              change: 'CREATE',
              kind: 'POLICY',
              status: 'PENDING',
              after: { description: '새 정책' },
              support: 'NONE',
            },
          ],
        },
        {
          id: '72',
          document_version_id: '212',
          status: 'FAILED',
          error_code: 'invalid_ai_output',
          retryable: true,
          candidates: [],
        },
      ],
    })
    expect(parsed.jobs[0].candidates[0].sources[0]).toMatchObject({
      block_id: 'b1',
    })
    expect(parsed.jobs[0].candidates[1].conflicting_fields).toEqual([])
    expect(parsed.jobs[1].retryable).toBe(true)
  })
})
