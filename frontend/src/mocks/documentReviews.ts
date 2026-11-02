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

function view(
  project: MockProject,
  copyOf: StoredWorkingCopy,
): DocumentWorkingCopy {
  return {
    ...copy(copyOf),
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

// 규칙 기반 예시 제안: 제목 줄에 목적, 마지막 줄에 예외 상황을 덧붙이자고 한다.
function suggestionsFor(
  context: MockContext,
  content: string,
): DocumentReviewSuggestion[] {
  const lines = content.split('\n').filter((line) => line.trim())
  const heading = lines.find((line) => line.startsWith('#'))
  const last = lines.at(-1)
  const result: DocumentReviewSuggestion[] = []
  if (heading && occursOnce(content, heading))
    result.push({
      id: nextId(context.database),
      explanation: '누락 · 이 문서가 무엇을 정하는지 목적이 없어요.',
      original_text: heading,
      replacement_text: `${heading}\n\n목적: 이 문서가 정하는 범위를 한 줄로 적어요.`,
      decision: 'PENDING',
    })
  if (last && last !== heading && occursOnce(content, last))
    result.push({
      id: nextId(context.database),
      explanation: '일관성 · 실패하거나 비어 있을 때의 동작이 없어요.',
      original_text: last,
      replacement_text: `${last}\n- 실패하거나 비어 있을 때 어떻게 보이는지 적는다.`,
      decision: 'PENDING',
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
      const suggestions = suggestionsFor(context, target.content)
      const approvedKind = (kind: string) =>
        others.some((item) => item.kind === kind && item.approved_version)
          ? '승인본과 비교함'
          : '승인본이 없어 건너뜀'
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
        reference_hash: `sha256:mock-reference-${target.document_id}-${target.revision}`,
        summary:
          '예시 검토 · 실제 AI 호출 없음. 문서의 목적과 예외 상황을 보완하자고 제안했어요.',
        unavailable_references: others
          .filter((item) => !item.approved_version)
          .map((item) => item.kind),
        references: others.flatMap((item) =>
          item.approved_version
            ? [
                {
                  document_id: item.id,
                  document_version_id: item.approved_version.id,
                  kind: item.kind,
                  version_no: item.approved_version.version_no,
                  content: item.approved_version.content ?? '',
                },
              ]
            : [],
        ),
        suggestions,
        usage: [],
        steps: [
          {
            label: '문서 읽기',
            result: `문단 ${target.content.split(/\n\s*\n/).filter((part) => part.trim()).length}개`,
          },
          {
            label: '승인된 API 명세서와 비교',
            result: approvedKind('API_SPEC'),
          },
          { label: 'ERD와 맞춰 보기', result: approvedKind('ERD') },
          { label: '요구사항과 비교', result: approvedKind('REQUIREMENTS') },
          { label: '수정 제안 정리', result: `제안 ${suggestions.length}개` },
        ],
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
      if (review.resolved_revision !== target.revision)
        fail(409, 'review_stale', '본문이 바뀌어 다시 검토해야 해요.')
      const suggestion = findById(review.suggestions, params.suggestion_id)
      if (suggestion.decision !== 'PENDING')
        fail(409, 'version_conflict', '이미 처리한 제안이에요.')
      if (body.decision === 'APPLY') {
        if (!occursOnce(target.content, suggestion.original_text))
          fail(409, 'review_stale', '제안한 원문을 본문에서 찾지 못했어요.')
        target.content = target.content.replace(
          suggestion.original_text,
          suggestion.replacement_text,
        )
        target.revision += 1
        review.resolved_revision = target.revision
        suggestion.decision = 'APPLIED'
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
        review.resolved_revision !== target.revision
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
    default:
      return undefined
  }
}
