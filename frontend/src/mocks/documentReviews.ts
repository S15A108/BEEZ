import { documentVersionSchema } from '../api/contracts/common'
import type {
  DocumentBeeReview,
  DocumentReviewSuggestion,
  DocumentWorkingCopy,
} from '../api/contracts/common'
import {
  fail,
  findById,
  requireAccount,
  requireLeader,
  requireProject,
  requiredString,
} from './helpers'
import type { MockContext, MockReply } from './helpers'
import { analyzeDocument, textOf } from './documents'
import { copy, nextId, now } from './state'
import type { MockProject, StoredSubmission, StoredWorkingCopy } from './state'

// 문서별 검토·승인(DOC-A13~A21) 브라우저 예시. 비비 검토는 실제 AI를 부르지 않고 규칙으로 보완 제안을 만든다.

const strip = ({ content, review, ...submission }: StoredSubmission) => {
  void content
  void review
  return submission
}

// 서버의 reference_hash 대역: 이 문서를 뺀 승인본 집합(문서 id와 승인 버전)이 같으면 같은 값이다.
function referenceHash(project: MockProject, documentId: string) {
  const approved = project.documents
    .filter((item) => item.id !== documentId && item.approved_version)
    .map((item) => `${item.id}:${item.approved_version!.version_no}`)
    .sort()
  return `sha256:mock-reference-${approved.join(',')}`
}

// 서버의 usable() 대역: 본문(revision)과 참고 승인본이 검토 때와 같을 때만 그 검토를 쓴다.
function usable(
  project: MockProject,
  target: StoredWorkingCopy,
  review: DocumentBeeReview,
) {
  return (
    review.resolved_revision === target.revision &&
    review.reference_hash === referenceHash(project, target.document_id)
  )
}

function view(
  project: MockProject,
  copyOf: StoredWorkingCopy,
): DocumentWorkingCopy {
  const review = copyOf.latest_review
  return {
    ...copy(copyOf),
    // 팀장 결정을 기다리거나 끝난 건은 제출 때 통과한 검토라 무효로 표시하지 않는다.
    review_stale:
      !!review &&
      copyOf.status !== 'SUBMITTED' &&
      copyOf.status !== 'APPROVED' &&
      !usable(project, copyOf, review),
    submissions: project.submissions
      .filter((item) => item.document_id === copyOf.document_id)
      .sort((a, b) => (BigInt(a.id) < BigInt(b.id) ? 1 : -1))
      .map(strip),
  }
}

function workingCopy(project: MockProject, documentId: string) {
  findById(project.documents, documentId)
  return (
    project.working_copies.find((item) => item.document_id === documentId) ??
    fail(404, 'not_found', '작성 내용을 찾을 수 없어요.')
  )
}

function guardOwner(
  context: MockContext,
  project: MockProject,
  target: StoredWorkingCopy,
  expected: unknown,
) {
  if (requireAccount(context).id !== target.owner_account_id)
    fail(403, 'forbidden', '등록한 사람만 할 수 있어요.')
  if (project.project.current_baseline)
    fail(
      409,
      'change_proposal_required',
      '기준 설계가 생긴 뒤에는 변경 제안으로 바꿔 주세요.',
    )
  if (target.status === 'SUBMITTED')
    fail(409, 'submission_locked', '팀장 검토를 요청한 뒤에는 고칠 수 없어요.')
  if (Number(expected) !== target.revision)
    fail(
      409,
      'version_conflict',
      '다른 곳에서 먼저 고쳤어요. 새로 불러와 주세요.',
    )
}

const occursOnce = (content: string, text: string) =>
  !!text && content.split(text).length === 2

// 개발 서버에서 비비 대화·직접 요청의 실패 장면을 눈으로 보려는 스위치(목 전용, live 빌드에는 들어가지 않는다).
// localStorage 'beez.mock.review' = unavailable(503) | invalid(502)
function guardAi() {
  let scenario: string | null
  try {
    scenario = localStorage.getItem('beez.mock.review')
  } catch {
    scenario = null
  }
  if (scenario === 'unavailable')
    fail(503, 'review_unavailable', '비비를 부를 수 없어요.')
  if (scenario === 'invalid')
    fail(502, 'invalid_ai_output', '비비 결과를 확인하지 못했어요.')
}

// 대화·직접 요청은 지금 작성 내용의 최신 검토에서만 쓴다(DOC-A22·A23).
function latestReview(
  project: MockProject,
  target: StoredWorkingCopy,
  reviewId: string,
) {
  const review = target.latest_review
  // 직접 고치거나 다시 올리면 이전 검토가 사라지므로, 검토 id가 낡았을 때는 review_stale이다.
  if (!review) fail(409, 'review_stale', '본문이 바뀌어 다시 검토해야 해요.')
  if (review.id !== reviewId)
    fail(404, 'not_found', '비비 검토를 찾을 수 없어요.')
  if (!usable(project, target, review))
    fail(409, 'review_stale', '이 검토는 더 쓸 수 없어요.')
  return review
}

// 규칙 기반 예시 제안: 제목 줄에 목적, 마지막 줄에 예외 상황을 덧붙이자고 한다.
function suggestionsFor(
  context: MockContext,
  content: string,
  sources: DocumentReviewSuggestion['sources'] = [],
): DocumentReviewSuggestion[] {
  const lines = content.split('\n').filter((line) => line.trim())
  const heading = lines.find((line) => line.startsWith('#'))
  const last = lines.at(-1)
  const result: DocumentReviewSuggestion[] = []
  if (heading && occursOnce(content, heading))
    result.push({
      id: nextId(context.database),
      explanation: '이 문서가 무엇을 정하는지 목적이 없어요.',
      type: 'PATCH',
      step: 'BODY',
      original_text: heading,
      replacement_text: `${heading}\n\n목적: 이 문서가 정하는 범위를 한 줄로 적어요.`,
      decision: 'PENDING',
      origin: 'REVIEW',
      sources: [],
      messages: [],
    })
  if (last && last !== heading && occursOnce(content, last))
    result.push({
      id: nextId(context.database),
      explanation: '실패하거나 비어 있을 때의 동작이 없어요.',
      type: 'PATCH',
      step: 'BODY',
      original_text: last,
      replacement_text: `${last}\n- 실패하거나 비어 있을 때 어떻게 보이는지 적는다.`,
      decision: 'PENDING',
      origin: 'REVIEW',
      sources,
      messages: [],
    })
  return result
}

async function editWith(
  project: MockProject,
  target: StoredWorkingCopy,
  content: string,
  fileName: string,
) {
  if (!content.trim())
    fail(400, 'validation_failed', '원문 내용이 비어 있어요.')
  target.content = content
  target.file_name = fileName
  target.revision += 1
  target.status = 'EDITING'
  // 직접 고치면 이전 비비 검토는 무효가 된다.
  delete target.latest_review
  return { data: view(project, target) }
}

export async function reviewRoutes(
  id: string,
  context: MockContext,
): Promise<MockReply | undefined> {
  const project = requireProject(context)
  const { body, params, query, database } = context
  switch (id) {
    case 'DOC-A13':
      return { data: view(project, workingCopy(project, params.document_id)) }
    case 'DOC-A14': {
      const target = workingCopy(project, params.document_id)
      guardOwner(context, project, target, body.expected_revision)
      return editWith(
        project,
        target,
        requiredString(body, 'content'),
        requiredString(body, 'file_name'),
      )
    }
    case 'DOC-A15': {
      const target = workingCopy(project, params.document_id)
      guardOwner(
        context,
        project,
        target,
        context.form?.get('expected_revision'),
      )
      const file = context.form?.get('file')
      if (!(file instanceof Blob))
        fail(400, 'validation_failed', '원문 파일을 선택해 주세요.')
      const fileName = file instanceof File ? file.name : target.file_name
      if (!/\.(md|txt)$/i.test(fileName))
        fail(
          415,
          'unsupported_media_type',
          'Markdown(.md) 파일만 올릴 수 있어요.',
        )
      return editWith(project, target, await textOf(file), fileName)
    }
    case 'DOC-A16': {
      const target = workingCopy(project, params.document_id)
      guardOwner(context, project, target, body.expected_revision)
      const document = findById(project.documents, target.document_id)
      const others = project.documents.filter((item) => item.id !== document.id)
      const approvedFirst = others.find((item) => item.approved_version)
      const suggestions = suggestionsFor(
        context,
        target.content,
        approvedFirst?.approved_version
          ? [
              {
                reference_id: `ref-${approvedFirst.id}`,
                document_id: approvedFirst.id,
                document_version_id: approvedFirst.approved_version.id,
                kind: approvedFirst.kind,
                version_no: approvedFirst.approved_version.version_no,
              },
            ]
          : [],
      )
      // 서버 v2 단계 규칙(DocumentReviewResultValidator): 본문은 늘 검토하고, 같은 종류는 SAME_KIND로,
      // 승인본이 없는 종류는 REFERENCE_UNAVAILABLE로 건너뛴다. 요구사항 명세서의 단계 코드는 REQ_SPEC이다.
      const approved = others.filter((item) => item.approved_version)
      const steps: DocumentBeeReview['steps'] = (
        ['BODY', 'API_SPEC', 'ERD', 'REQ_SPEC'] as const
      ).map((step) => {
        const kind = step === 'REQ_SPEC' ? 'REQUIREMENTS' : step
        const ids = approved
          .filter((item) => item.kind === kind)
          .map((item) => `ref-${item.id}`)
        if (step === 'BODY')
          return {
            step,
            status: 'COMPLETE',
            reason: 'REVIEWED',
            reference_ids: [],
          }
        if (kind === document.kind)
          return {
            step,
            status: 'SKIPPED',
            reason: 'SAME_KIND',
            reference_ids: [],
          }
        if (!ids.length)
          return {
            step,
            status: 'PARTIAL',
            reason: 'REFERENCE_UNAVAILABLE',
            reference_ids: [],
          }
        return {
          step,
          status: 'COMPLETE',
          reason: 'REVIEWED',
          reference_ids: ids,
        }
      })
      const unavailable = steps
        .filter((item) => item.reason === 'REFERENCE_UNAVAILABLE')
        .map((item) => item.step)
      // 반려 뒤 검토면 가장 최근 반려 제출을 서버가 고른다(DOC-A16 선택 규칙).
      const rejected = project.submissions
        .filter(
          (item) =>
            item.document_id === target.document_id &&
            item.status === 'REJECTED',
        )
        .at(-1)
      const review: DocumentBeeReview = {
        id: nextId(database),
        input_revision: target.revision,
        resolved_revision: target.revision,
        reference_hash: referenceHash(project, target.document_id),
        summary:
          '예시 검토 · 실제 AI 호출 없음. 문서의 목적과 예외 상황을 보완하자고 제안했어요.',
        unavailable_references: unavailable,
        references: approved.map((item) => ({
          document_id: item.id,
          document_version_id: item.approved_version!.id,
          kind: item.kind,
          version_no: item.approved_version!.version_no,
          content: item.approved_version!.content ?? '',
          reference_id: `ref-${item.id}`,
        })),
        suggestions,
        usage: [],
        steps,
        contract_version: 2,
        content_hash: `sha256:mock-content-${target.document_id}-${target.revision}`,
        references_used: [
          ...new Set(steps.flatMap((item) => item.reference_ids)),
        ].sort(),
        review_status: unavailable.length ? 'PARTIAL' : 'COMPLETE',
        ...(rejected && target.status === 'REJECTED'
          ? { rejection_submission_id: rejected.id }
          : {}),
      }
      target.latest_review = review
      return { status: 201, data: review }
    }
    case 'DOC-A17': {
      const target = workingCopy(project, params.document_id)
      guardOwner(context, project, target, body.expected_revision)
      const review = target.latest_review
      if (!review || review.id !== params.review_id)
        fail(404, 'not_found', '비비 검토를 찾을 수 없어요.')
      if (!usable(project, target, review))
        fail(409, 'review_stale', '이 검토는 더 쓸 수 없어요.')
      const suggestion = findById(review.suggestions, params.suggestion_id)
      const { original_text: original, replacement_text: replacement } =
        suggestion
      // 의견(COMMENT)은 반영·넘기기 대상이 아니다.
      if (original === undefined || replacement === undefined)
        fail(400, 'validation_failed', '의견은 반영하거나 넘길 수 없어요.')
      if (suggestion.decision !== 'PENDING')
        fail(409, 'version_conflict', '이미 처리한 제안이에요.')
      if (body.decision === 'APPLY') {
        if (!occursOnce(target.content, original))
          fail(409, 'review_stale', '제안한 원문을 본문에서 찾지 못했어요.')
        // 대화(DOC-A22)에서 비비가 낸 다른 대체문으로 반영하면 그 문장을 쓰고 기록한다.
        const other =
          typeof body.replacement_text === 'string'
            ? body.replacement_text
            : undefined
        target.content = target.content.replace(
          original,
          () => other ?? replacement,
        )
        target.revision += 1
        review.resolved_revision = target.revision
        suggestion.decision = 'APPLIED'
        if (other !== undefined) suggestion.applied_replacement_text = other
      } else if (body.decision === 'SKIP') suggestion.decision = 'SKIPPED'
      else fail(400, 'validation_failed', '반영하기나 넘기기를 골라 주세요.')
      return { data: view(project, target) }
    }
    case 'DOC-A18': {
      const target = workingCopy(project, params.document_id)
      guardOwner(context, project, target, body.expected_revision)
      const review = target.latest_review
      if (
        !review ||
        review.id !== body.review_id ||
        !usable(project, target, review)
      )
        fail(409, 'review_stale', '최신 본문으로 비비 검토를 먼저 받아 주세요.')
      if (review.suggestions.some((item) => item.decision === 'PENDING'))
        fail(
          409,
          'review_decisions_pending',
          '처리하지 않은 보완 제안이 있어요.',
        )
      const document = findById(project.documents, target.document_id)
      let version = document.latest_draft
      if (!version || version.content !== target.content) {
        version = documentVersionSchema.parse({
          id: nextId(database),
          document_id: document.id,
          version_no:
            Math.max(
              0,
              ...project.versions
                .filter((item) => item.document_id === document.id)
                .map((item) => item.version_no),
            ) + 1,
          status: 'DRAFT',
          file_name: target.file_name,
          media_type: 'text/markdown',
          content_hash: `sha256:mock-${database.next_id}`,
          content: target.content,
          created_at: now(),
        })
        project.versions.push(version)
        document.latest_draft = version
        analyzeDocument(context, version)
      }
      const submission: StoredSubmission = {
        id: nextId(database),
        document_id: document.id,
        document_version_id: version.id,
        submitted_by: requireAccount(context).id,
        revision: target.revision,
        status: 'SUBMITTED',
        content_hash: version.content_hash,
        submitted_at: now(),
        content: target.content,
        review: copy(review),
      }
      project.submissions.push(submission)
      target.status = 'SUBMITTED'
      return { status: 201, data: strip(submission) }
    }
    case 'DOC-A19':
      return {
        data: project.submissions
          .filter(
            (item) =>
              !query.get('status') || item.status === query.get('status'),
          )
          .sort((a, b) => (BigInt(a.id) < BigInt(b.id) ? 1 : -1))
          .map(strip),
      }
    case 'DOC-A20': {
      const submission = findById(project.submissions, params.submission_id)
      if (submission.document_id !== params.document_id)
        fail(404, 'not_found', '제출을 찾을 수 없어요.')
      return {
        data: {
          submission: strip(submission),
          content: submission.content,
          review: submission.review,
        },
      }
    }
    case 'DOC-A21': {
      requireLeader(context)
      if (project.project.current_baseline)
        fail(
          409,
          'change_proposal_required',
          '기준 설계가 생긴 뒤에는 변경 제안으로 바꿔 주세요.',
        )
      const submission = findById(project.submissions, params.submission_id)
      if (submission.document_id !== params.document_id)
        fail(404, 'not_found', '제출을 찾을 수 없어요.')
      if (submission.status === 'WITHDRAWN')
        fail(409, 'submission_withdrawn', '올린 사람이 요청을 거뒀어요.')
      if (submission.status !== 'SUBMITTED')
        fail(409, 'submission_decided', '이미 결정한 제출이에요.')
      const target = workingCopy(project, submission.document_id)
      const document = findById(project.documents, submission.document_id)
      if (body.decision === 'APPROVE') {
        const version = findById(
          project.versions,
          submission.document_version_id,
        )
        if (
          document.approved_version &&
          document.approved_version.id !== version.id
        ) {
          const previous = findById(
            project.versions,
            document.approved_version.id,
          )
          previous.status = 'SUPERSEDED'
        }
        version.status = 'APPROVED'
        document.approved_version = version
        if (document.latest_draft?.id === version.id)
          delete document.latest_draft
        submission.status = 'APPROVED'
        target.status = 'APPROVED'
      } else if (body.decision === 'REJECT') {
        const reason =
          typeof body.rejection_reason === 'string'
            ? body.rejection_reason.trim()
            : ''
        if (!reason)
          fail(400, 'validation_failed', '반려 사유를 입력해 주세요.')
        submission.status = 'REJECTED'
        submission.rejection_reason = reason
        // 같은 작성 건을 다시 열고 리비전을 올려 이전 검토를 무효로 한다.
        target.status = 'REJECTED'
        target.revision += 1
        delete target.latest_review
      } else fail(400, 'validation_failed', '승인이나 반려를 골라 주세요.')
      submission.decided_by = requireAccount(context).id
      submission.decided_at = now()
      return { data: strip(submission) }
    }
    case 'DOC-A22': {
      const target = workingCopy(project, params.document_id)
      if (requireAccount(context).id !== target.owner_account_id)
        fail(403, 'forbidden', '등록한 사람만 할 수 있어요.')
      if (target.status === 'SUBMITTED')
        fail(
          409,
          'submission_locked',
          '팀장 검토를 요청한 뒤에는 고칠 수 없어요.',
        )
      const review = latestReview(project, target, params.review_id)
      const suggestion = findById(review.suggestions, params.suggestion_id)
      const text = requiredString(body, 'text').trim()
      if (!text)
        fail(400, 'validation_failed', '비비에게 보낼 말을 적어 주세요.')
      const used = review.suggestions.reduce(
        (sum, item) =>
          sum + item.messages.filter((m) => m.author_kind === 'HUMAN').length,
        0,
      )
      if (used >= 80)
        fail(409, 'review_chat_limit', '이 검토에서 나눌 대화를 모두 썼어요.')
      guardAi()
      const message = {
        id: nextId(database),
        author_kind: 'HUMAN' as const,
        text,
        created_at: now(),
      }
      // 규칙 기반 예시 답: PATCH 제안이면 다른 대체문을 하나 낸다. COMMENT 는 대체문을 내지 않는다(형식 위반).
      const reply = {
        id: nextId(database),
        author_kind: 'BEE' as const,
        text:
          suggestion.type === 'COMMENT'
            ? '예시 답 · 실제 AI 호출 없음. 이 의견은 읽기 전용이라 본문은 바뀌지 않아요.'
            : '예시 답 · 실제 AI 호출 없음. 말씀하신 내용을 담아 이렇게 바꿀 수 있어요.',
        ...(suggestion.type === 'PATCH' &&
        suggestion.replacement_text !== undefined
          ? {
              proposed_text: `${suggestion.replacement_text}\n- ${text}`,
            }
          : {}),
        created_at: now(),
      }
      suggestion.messages.push(message, reply)
      return { status: 201, data: { message, reply } }
    }
    case 'DOC-A23': {
      const target = workingCopy(project, params.document_id)
      guardOwner(context, project, target, body.expected_revision)
      const review = latestReview(project, target, params.review_id)
      const text = requiredString(body, 'text').trim()
      if (!text) fail(400, 'validation_failed', '요청 내용을 적어 주세요.')
      if (
        review.suggestions.filter((item) => item.origin === 'DIRECT_REQUEST')
          .length >= 20
      )
        fail(409, 'review_request_limit', '직접 요청을 모두 썼어요.')
      guardAi()
      // 규칙 기반 예시: 본문 마지막 줄 뒤에 요청 내용을 덧붙이는 제안 하나를 만든다.
      const last = target.content
        .split('\n')
        .filter((line) => line.trim())
        .at(-1)
      if (!last || !occursOnce(target.content, last))
        fail(502, 'invalid_ai_output', '비비 결과를 확인하지 못했어요.')
      const suggestion: DocumentReviewSuggestion = {
        id: nextId(database),
        explanation: `요청대로 반영해요 · ${text}`,
        type: 'PATCH',
        step: 'BODY',
        original_text: last,
        replacement_text: `${last}\n- ${text}`,
        decision: 'PENDING',
        origin: 'DIRECT_REQUEST',
        sources: [],
        messages: [],
      }
      review.suggestions.push(suggestion)
      return { status: 201, data: { suggestion } }
    }
    case 'DOC-A24': {
      const submission = findById(project.submissions, params.submission_id)
      if (submission.document_id !== params.document_id)
        fail(404, 'not_found', '제출을 찾을 수 없어요.')
      const target = workingCopy(project, submission.document_id)
      if (requireAccount(context).id !== submission.submitted_by)
        fail(403, 'forbidden', '올린 사람만 취소할 수 있어요.')
      if (project.project.current_baseline)
        fail(
          409,
          'change_proposal_required',
          '기준 설계가 생긴 뒤에는 변경 제안으로 바꿔 주세요.',
        )
      // 같은 제출을 다시 취소하면 같은 결과(멱등)다.
      if (submission.status === 'WITHDRAWN')
        return { data: view(project, target) }
      if (submission.status !== 'SUBMITTED')
        fail(409, 'submission_decided', '팀장이 이미 결정했어요.')
      submission.status = 'WITHDRAWN'
      // 작성 건을 다시 열고 리비전을 올려 이전 검토를 무효로 한다(반려 뒤 재작성과 같다).
      target.status = 'EDITING'
      target.revision += 1
      delete target.latest_review
      return { data: view(project, target) }
    }
    default:
      return undefined
  }
}
