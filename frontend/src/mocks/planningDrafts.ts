import type {
  DesignItemVersion,
  PlanningAnswerPayload,
  PlanningQuestion,
} from '../api/contracts/common'
import type { DraftCandidate, DraftJob } from '../features/planning/schemas'
import {
  fail,
  findById,
  mockHash,
  requireAccount,
  requireProject,
  strings,
} from './helpers'
import type { MockContext, MockReply } from './helpers'
import { nextId, now } from './state'

// 개발 서버에서 질문 생성·초안 후보의 여러 상태를 눈으로 보려는 스위치(목 전용, live 빌드에는 들어가지 않는다).
// localStorage 'beez.mock.planQuestions' = failed(질문 생성 실패. 재시도하면 풀린다)
// localStorage 'beez.mock.planDraft' = failed(초안 작업 실패) | stale(채택이 candidate_stale 로 막힘)
function scenario<T extends string>(key: string, values: readonly T[]) {
  try {
    const value = localStorage.getItem(key)
    return values.find((item) => item === value)
  } catch {
    return undefined
  }
}
export const questionScenario = () =>
  scenario('beez.mock.planQuestions', ['failed'] as const)
const draftScenario = () =>
  scenario('beez.mock.planDraft', ['failed', 'stale'] as const)

// PLN-A01·A02 의 question_generation. 실패 스위치가 켜져 있으면 FAILED, 재시도(A02)하면 스위치를 끄고 PENDING 으로 답한다.
export function questionGeneration(retry: boolean) {
  if (questionScenario() === 'failed') {
    if (!retry)
      return { status: 'FAILED', error_code: 'ai_unavailable', retryable: true }
    try {
      localStorage.removeItem('beez.mock.planQuestions')
    } catch {
      // 스위치를 못 지워도 예시 응답에는 영향이 없다.
    }
    return { status: 'PENDING' }
  }
  return { status: 'COMPLETED' }
}

const OTHER_MAX = 500

function answerText(payload: PlanningAnswerPayload): string {
  return payload.type === 'CHOICE'
    ? [
        ...payload.selected,
        ...(payload.other_text ? [payload.other_text] : []),
      ].join(', ')
    : payload.text
}

// 서버 PlanningAnswers: 답은 질문의 answer_format 을 따라야 한다.
function toPayload(
  question: PlanningQuestion,
  body: Record<string, unknown>,
): PlanningAnswerPayload {
  const format = question.answer_format
  const type = question.question_type ?? 'SHORT_ANSWER'
  const raw = body.answer_payload as Record<string, unknown> | undefined
  if (raw) {
    if (raw.type !== type)
      fail(400, 'validation_failed', '답변 유형이 질문과 맞지 않아요.')
    if (type === 'CHOICE') {
      const selected = strings(raw.selected)
      const other =
        typeof raw.other_text === 'string' ? raw.other_text.trim() : ''
      if (selected.some((item) => !format?.options?.includes(item)))
        fail(400, 'validation_failed', '선택지에 없는 답이에요.')
      if (!format?.multiple && selected.length + (other ? 1 : 0) > 1)
        fail(400, 'validation_failed', '하나만 선택할 수 있어요.')
      if (other && !format?.allow_other)
        fail(400, 'validation_failed', '기타 입력을 받지 않는 질문이에요.')
      if (!selected.length && !other)
        fail(400, 'validation_failed', '답변을 선택해 주세요.')
      return {
        type: 'CHOICE',
        selected,
        ...(other ? { other_text: other.slice(0, OTHER_MAX) } : {}),
      }
    }
    const text = typeof raw.text === 'string' ? raw.text.trim() : ''
    if (!text) fail(400, 'validation_failed', '답변을 입력해 주세요.')
    if (format?.max_length && [...text].length > format.max_length)
      fail(400, 'validation_failed', '답변이 너무 길어요.')
    return { type: type as 'SHORT_ANSWER' | 'ESSAY', text }
  }
  const text = typeof body.answer === 'string' ? body.answer.trim() : ''
  if (!text) fail(400, 'validation_failed', 'answer 값을 입력해 주세요.')
  if (type !== 'CHOICE') return { type: type as 'SHORT_ANSWER' | 'ESSAY', text }
  // 선택지와 같은 문장이면 선택, 아니면 기타 입력으로 본다.
  if (format?.options?.includes(text))
    return { type: 'CHOICE', selected: [text] }
  if (!format?.allow_other)
    fail(400, 'validation_failed', '기타 입력을 받지 않는 질문이에요.')
  return { type: 'CHOICE', selected: [], other_text: text }
}

// 서버는 질문을 id 순(만든 순서)으로 주고 cursor 는 앞 쪽 마지막 질문의 id 다.
function questionPage(items: PlanningQuestion[], query: URLSearchParams) {
  const limit = Math.min(100, Math.max(1, Number(query.get('limit')) || 20))
  const sorted = [...items].sort((a, b) =>
    BigInt(a.id) < BigInt(b.id) ? -1 : 1,
  )
  const cursor = query.get('cursor')
  const start = cursor ? sorted.findIndex((item) => item.id === cursor) + 1 : 0
  if (cursor && !start)
    fail(400, 'validation_failed', '유효하지 않은 목록 위치예요.')
  const selected = sorted.slice(start, start + limit)
  const hasMore = start + limit < sorted.length
  return {
    items: selected.map((item, index) => ({
      ...item,
      number: start + index + 1,
    })),
    total_count: sorted.length,
    has_more: hasMore,
    ...(hasMore ? { next_cursor: selected.at(-1)!.id } : {}),
  }
}

function sourceOf(version: {
  document_id: string
  id: string
  version_no: number
}) {
  return {
    document_id: version.document_id,
    document_version_id: version.id,
    version_no: version.version_no,
    location: '1. 예시 분석',
  }
}

// 후보 만들기: 이 문서 버전에서 나온 DRAFT 항목마다 UPDATE 후보를, 원문 첫 줄로 CREATE 후보를 만든다(규칙 기반 예시).
function candidatesFor(
  context: MockContext,
  job: DraftJob,
  index: number,
): DraftCandidate[] {
  const project = requireProject(context)
  const version = findById(project.versions, job.document_version_id)
  const answered = project.questions.filter(
    (question) =>
      question.status === 'ANSWERED' || (question.answer && !question.status),
  )
  const refs = answered.map((question) => `q-${question.id}`)
  const conflicted = answered.some((question) => question.kind === 'CONFLICT')
  const line =
    version.content
      ?.split('\n')
      .find((text) => text.trim() && !text.startsWith('#')) ??
    '원문에서 요구사항을 찾지 못했어요.'
  const sources = [{ ...sourceOf(version), quote: line.slice(0, 120) }]
  const result: DraftCandidate[] = []
  project.items
    .filter(
      (item) =>
        item.status === 'DRAFT' &&
        item.source?.document_version_id === version.id,
    )
    .forEach((item) => {
      const clarification = answered
        .map((question) => `${question.question} → ${question.answer}`)
        .join('\n')
      result.push({
        id: nextId(context.database),
        job_id: job.id,
        candidate_key: item.key,
        change: 'UPDATE',
        kind: item.kind,
        status: 'PENDING',
        base_design_item_version_id: item.id,
        base_status: item.status,
        before: item.content,
        after: {
          ...item.content,
          description: `${String(item.content.description ?? '')} (답변을 반영해 구체화)`,
          ...(clarification ? { clarification } : {}),
        },
        changed_fields: [
          {
            field: 'description',
            change: 'CHANGED',
            before: item.content.description,
            after: `${String(item.content.description ?? '')} (답변을 반영해 구체화)`,
          },
          ...(clarification
            ? [
                {
                  field: 'clarification',
                  change: 'ADDED' as const,
                  after: clarification,
                },
              ]
            : []),
        ],
        conflicting_fields: conflicted ? ['description'] : [],
        sources: item.source
          ? [{ ...item.source, quote: line.slice(0, 120) }]
          : sources,
        answer_refs: refs,
        support: refs.length ? 'DOCUMENT_AND_ANSWER' : 'DOCUMENT',
        rationale: refs.length
          ? '팀장이 답한 내용을 기존 항목에 합쳤어요.'
          : '원문 문장에 맞춰 항목을 다듬었어요.',
      })
    })
  result.push({
    id: nextId(context.database),
    job_id: job.id,
    candidate_key: `new-${version.id}`,
    change: 'CREATE',
    kind: 'REQUIREMENT',
    status: 'PENDING',
    after: { description: line },
    changed_fields: [],
    conflicting_fields: [],
    sources,
    answer_refs: refs,
    support: refs.length ? 'DOCUMENT_AND_ANSWER' : 'DOCUMENT',
    rationale: '원문 첫 문장에서 새 요구사항을 제안해요.',
  })
  // 첫 작업에만 근거 없는 후보를 하나 둔다. 채택하려면 "근거 없음"을 확인해야 한다.
  if (index === 0)
    result.push({
      id: nextId(context.database),
      job_id: job.id,
      candidate_key: `policy-${version.id}`,
      change: 'CREATE',
      kind: 'POLICY',
      status: 'PENDING',
      after: { description: '모든 변경 요청에는 변경 사유를 남긴다.' },
      changed_fields: [],
      conflicting_fields: [],
      sources: [],
      answer_refs: [],
      support: 'NONE',
      rationale:
        '문서와 답변에서 근거를 찾지 못했어요. 필요한지 판단해 주세요.',
    })
  return result
}

// 작업은 조회할 때마다 PENDING → RUNNING → 결과(COMPLETED/FAILED) 순으로 한 걸음씩 나아간다.
function advance(context: MockContext, jobs: DraftJob[]) {
  jobs.forEach((job, index) => {
    if (job.status === 'PENDING') job.status = 'RUNNING'
    else if (job.status === 'RUNNING') {
      if (draftScenario() === 'failed') {
        job.status = 'FAILED'
        job.error_code = 'ai_unavailable'
        job.retryable = true
      } else {
        job.status = 'COMPLETED'
        job.candidates = candidatesFor(context, job, index)
        job.unanswered_question_keys = requireProject(context)
          .questions.filter((question) => question.status === 'OPEN')
          .map((question) => `q-${question.id}`)
        job.unresolved = []
        job.omitted_items = 0
      }
    } else return
    job.updated_at = now()
  })
}

function findCandidate(jobs: DraftJob[], id: string): DraftCandidate {
  return (
    jobs.flatMap((job) => job.candidates).find((item) => item.id === id) ??
    fail(404, 'not_found', '후보를 찾을 수 없어요.')
  )
}

function adopt(
  context: MockContext,
  candidate: DraftCandidate,
): DraftCandidate & {
  design_item_version_id: string
} {
  const project = requireProject(context)
  const body = context.body
  if (candidate.status === 'DISCARDED')
    fail(409, 'candidate_discarded', '이미 폐기한 후보예요.')
  if (candidate.status === 'ADOPTED')
    return {
      ...candidate,
      design_item_version_id: candidate.adopted_design_item_version_id!,
    }
  if (project.baselines.length)
    fail(409, 'baseline_exists', '이미 기준 설계가 발행됐어요.')
  if (draftScenario() === 'stale')
    fail(
      409,
      'candidate_stale',
      '후보를 만든 뒤 문서, 답변 또는 설계 항목이 바뀌었어요. 초안을 다시 만들어 주세요.',
    )
  if (candidate.support === 'NONE' && body.confirm_no_evidence !== true)
    fail(400, 'validation_failed', '근거 없음을 확인해 주세요.')
  const resolved = strings(body.resolved_fields)
  if (candidate.conflicting_fields.some((field) => !resolved.includes(field)))
    fail(400, 'validation_failed', '충돌한 항목을 모두 확정해 주세요.')
  let adopted: DesignItemVersion
  if (candidate.change === 'UPDATE') {
    adopted = findById(
      project.items,
      candidate.base_design_item_version_id ?? '',
    )
    if (adopted.status !== 'DRAFT')
      fail(
        409,
        'candidate_stale',
        '대상 초안이 바뀌었어요. 초안을 다시 만들어 주세요.',
      )
    adopted.content = candidate.after
    adopted.content_hash = mockHash(candidate.after)
  } else {
    const version = findById(
      project.versions,
      project.draft_jobs?.find((job) => job.id === candidate.job_id)
        ?.document_version_id ?? '',
    )
    adopted = {
      id: nextId(context.database),
      design_item_id: nextId(context.database),
      key: `${candidate.kind === 'POLICY' ? 'POL' : 'REQ'}-${String(project.items.length + 1).padStart(3, '0')}`,
      kind: candidate.kind as DesignItemVersion['kind'],
      version_no: 1,
      status: 'DRAFT',
      schema_version: 1,
      content: candidate.after,
      source: sourceOf(version),
      content_hash: mockHash(candidate.after),
    }
    project.items.push(adopted)
  }
  candidate.status = 'ADOPTED'
  candidate.adopted_design_item_version_id = adopted.id
  return { ...candidate, design_item_version_id: adopted.id }
}

export function planningDraftRoutes(
  id: string,
  context: MockContext,
): MockReply | undefined {
  if (!/^PLN-A0[345]/.test(id)) return undefined
  const project = requireProject(context)
  const { body, params, query, database } = context
  project.draft_jobs ??= []
  switch (id) {
    case 'PLN-A03':
      return {
        data: questionPage(
          project.questions.filter(
            (question) =>
              !query.get('document_version_id') ||
              question.source?.document_version_id ===
                query.get('document_version_id'),
          ),
          query,
        ),
      }
    case 'PLN-A04': {
      const question = findById(project.questions, params.question_id)
      if (project.baselines.length)
        fail(409, 'design_already_published', '이미 발행된 초안이에요.')
      const dismiss = body.status === 'DISMISSED'
      if (
        dismiss &&
        (body.answer !== undefined || body.answer_payload !== undefined)
      )
        fail(400, 'validation_failed', '닫을 때는 답변을 함께 보내지 않아요.')
      if (body.status !== undefined && !dismiss)
        fail(400, 'validation_failed', 'status 값이 올바르지 않아요.')
      if (dismiss) {
        question.status = 'DISMISSED'
        delete question.answer
        delete question.answer_payload
        delete question.answered_by
        delete question.answered_at
        return { data: question }
      }
      const payload = toPayload(question, body)
      question.status = 'ANSWERED'
      question.answer = answerText(payload)
      question.answer_payload = payload
      question.answered_by = requireAccount(context).id
      question.answered_at = now()
      return { data: question }
    }
    case 'PLN-A05': {
      const ids = strings(body.document_version_ids)
      if (!ids.length)
        fail(400, 'validation_failed', '초안의 원문 버전을 선택해 주세요.')
      if (project.baselines.length)
        fail(409, 'baseline_exists', '이미 기준 설계가 발행됐어요.')
      const versions = ids.map((versionId) =>
        findById(project.versions, versionId),
      )
      if (
        versions.some((version) =>
          project.draft_jobs!.some(
            (job) =>
              job.document_version_id === version.id &&
              (job.status === 'PENDING' || job.status === 'RUNNING'),
          ),
        )
      )
        fail(409, 'draft_in_progress', '초안을 만드는 중이에요.')
      const jobs: DraftJob[] = versions.map((version) => ({
        id: nextId(database),
        document_version_id: version.id,
        status: 'PENDING',
        created_at: now(),
        updated_at: now(),
        candidates: [],
      }))
      project.draft_jobs.push(...jobs)
      return {
        status: 202,
        data: {
          status: 'PENDING',
          document_version_ids: ids,
          jobs: jobs.map((job) => ({
            id: job.id,
            document_version_id: job.document_version_id,
          })),
        },
      }
    }
    case 'PLN-A05-LIST': {
      const versionId = query.get('document_version_id')
      const shown = project
        .draft_jobs!.filter(
          (job) => !versionId || job.document_version_id === versionId,
        )
        .slice(-20)
      advance(context, shown)
      return { data: { jobs: [...shown].reverse() } }
    }
    case 'PLN-A05-ADOPT':
      return {
        data: adopt(
          context,
          findCandidate(project.draft_jobs!, params.candidate_id),
        ),
      }
    case 'PLN-A05-DISCARD': {
      const candidate = findCandidate(project.draft_jobs!, params.candidate_id)
      if (candidate.status === 'ADOPTED')
        fail(409, 'candidate_adopted', '이미 채택한 후보는 폐기할 수 없어요.')
      candidate.status = 'DISCARDED'
      return { data: candidate }
    }
  }
  return undefined
}
