import type {
  ChangeProposal,
  ChangeProposalInput,
  PreSubmitReview,
  ResolvedDocumentChange,
} from '../api/contracts/common'
import { normalizeDocument } from '../features/changes/normalizeDocument'
import { proposeChanges } from '../features/changes/proposeDiff'
import {
  draftDecisionInputSchema,
  draftInputSchema,
  draftReviewInputSchema,
  resubmitDraftInputSchema,
  submitDraftInputSchema,
  updateDraftInputSchema,
} from '../features/changes/schemas'
import type {
  Draft,
  DraftReview,
  DraftReviewStep,
  DraftSuggestion,
} from '../features/changes/schemas'
import { affectedTasks } from './changes'
import {
  fail,
  findById,
  mockHash,
  requireAccount,
  requireProject,
} from './helpers'
import type { MockContext, MockReply } from './helpers'
import { copy, nextId, now } from './state'
import type { MockProject, StoredChangeDraft } from './state'

// 올리기 전 비비 검토(CHG-A16~A20)와 초안으로 올리기(CHG-A01·A04) 브라우저 예시. 실제 AI를 부르지 않고 규칙으로
// 수정안을 만든다. 검토는 접수 시각부터 단계마다 STEP_MS씩 지나 끝나므로 진행 화면을 볼 수 있다.

const STEP_MS = 1500
const TIMEOUT_MS = 10 * 60_000
const STEP_KEYS = [
  'READ_CHANGES',
  'FIND_RELATED',
  'AI_CANDIDATES',
  'GAPS_CONFLICTS',
] as const

// 개발 서버에서 실패 장면을 눈으로 보려는 스위치(목 전용, live 빌드에는 들어가지 않는다).
// localStorage 'beez.mock.changeReview' = running(끝나지 않음, 10분 뒤 시간 초과) | timeout(시간 초과) | failed(모델 실패)
function reviewScenario() {
  try {
    const value = localStorage.getItem('beez.mock.changeReview')
    return value === 'running' || value === 'timeout' || value === 'failed'
      ? value
      : undefined
  } catch {
    return undefined
  }
}

const drafts = (project: MockProject) => (project.change_drafts ??= [])

function ownDraft(context: MockContext, draftId: unknown) {
  const project = requireProject(context)
  const draft = drafts(project).find((item) => item.id === draftId)
  // 다른 계정에는 404로 존재를 숨긴다.
  if (!draft || draft.owner_account_id !== requireAccount(context).id)
    fail(404, 'not_found', '작성 중인 제안을 찾을 수 없어요.')
  return draft
}

function guardOpen(draft: StoredChangeDraft, expectedRevision?: unknown) {
  if (draft.submitted_proposal_id)
    fail(409, 'draft_already_submitted', '이미 올린 제안이에요.')
  if (expectedRevision !== undefined && expectedRevision !== draft.revision)
    fail(
      409,
      'version_conflict',
      '작성 중인 제안이 바뀌었어요. 다시 불러와 주세요.',
    )
}

function guardBaseline(context: MockContext, baselineId: string) {
  const current = requireProject(context).project.current_baseline
  if (!current)
    fail(409, 'baseline_missing', '먼저 첫 기준 설계를 만들어 주세요.')
  if (current.id !== baselineId)
    fail(
      409,
      'baseline_conflict',
      '기준이 바뀌었어요. 최신 기준과 다시 비교해 주세요.',
    )
}

const latestOf = (draft: StoredChangeDraft) => draft.reviews.at(-1)

// 고친 글을 서버처럼 정규화해 해석한다. 서버처럼 문서마다 DRAFT 버전을 두되, 같은 초안을 다시 저장하면 그 버전을 고쳐 쓴다.
function resolveDocuments(
  context: MockContext,
  input: ChangeProposalInput,
  previous: ResolvedDocumentChange[] = [],
): ResolvedDocumentChange[] {
  const project = requireProject(context)
  return input.documents.map((change) => {
    const base = findById(project.versions, change.base_document_version_id)
    const after = normalizeDocument(
      change.after_content ??
        findById(project.versions, change.proposed_document_version_id!)
          .content ??
        '',
    )
    if (after === normalizeDocument(base.content ?? ''))
      fail(
        400,
        'validation_failed',
        '기준과 같은 문서는 변경 제안에 넣지 않아요.',
      )
    const kept = previous.find(
      (item) =>
        item.document_id === change.document_id &&
        item.base_document_version_id === change.base_document_version_id,
    )
    const version = kept
      ? findById(project.versions, kept.proposed_document_version_id)
      : newVersion(context, base.document_id)
    version.content = after
    version.content_hash = mockHash(after)
    return {
      document_id: change.document_id,
      base_document_version_id: change.base_document_version_id,
      proposed_document_version_id: version.id,
      after_content: after,
      content_hash: mockHash(after),
    }
  })
}

function newVersion(context: MockContext, documentId: string) {
  const project = requireProject(context)
  const siblings = project.versions.filter(
    (item) => item.document_id === documentId,
  )
  const base =
    siblings.find((item) => item.status === 'APPROVED') ?? siblings[0]
  const version = {
    ...base,
    id: nextId(context.database),
    version_no: Math.max(...siblings.map((item) => item.version_no)) + 1,
    status: 'DRAFT' as const,
    created_at: now(),
  }
  project.versions.push(version)
  return version
}

const setContent = (
  context: MockContext,
  change: ResolvedDocumentChange,
  content: string,
) => {
  const version = findById(
    requireProject(context).versions,
    change.proposed_document_version_id,
  )
  version.content = content
  version.content_hash = mockHash(content)
  change.after_content = content
  change.content_hash = mockHash(content)
}

const occursOnce = (content: string, text: string) =>
  !!text && content.split(text).length === 2

// 규칙 수정안: 문서마다 처음 더하거나 고친 줄에 '조건이 맞지 않을 때' 안내를 더하는 PATCH 하나, 그 줄에 항목 키가 있으면
// 이어진 작업 계획을 함께 고치라는 COMMENT 하나. 설계 항목 변경에는 수정안 없이 COMMENT만 준다(명세).
function suggestionsOf(
  context: MockContext,
  draft: StoredChangeDraft,
): DraftSuggestion[] {
  const project = requireProject(context)
  return draft.documents.flatMap((change) => {
    const base = findById(project.versions, change.base_document_version_id)
    const line = proposeChanges(
      base.content ?? '',
      change.after_content,
    ).changes.find(
      (item) => item.after && occursOnce(change.after_content, item.after),
    )
    if (!line?.after) return []
    const result: DraftSuggestion[] = [
      {
        id: nextId(context.database),
        type: 'PATCH',
        category: 'OMISSION',
        ...(line.key ? { design_item_key: line.key } : {}),
        explanation:
          '바꾼 줄에 조건이 맞지 않을 때 무엇을 보여 주는지 적혀 있지 않아요.',
        document_id: change.document_id,
        base_document_version_id: change.base_document_version_id,
        reviewed_content_hash: change.content_hash,
        original_text: line.after,
        replacement_text: `${line.after} 조건이 맞지 않으면 그 이유를 함께 안내한다.`,
        decision: 'PENDING',
        sources: [],
      },
    ]
    if (line.key)
      result.push({
        id: nextId(context.database),
        type: 'COMMENT',
        category: 'CONSISTENCY',
        design_item_key: line.key,
        explanation: `${line.key}를 참조하는 작업 계획이 있으면 승인 뒤 함께 고쳐야 해요.`,
        sources: [],
      })
    return result
  })
}

// 접수 뒤 지난 시간으로 단계를 진행하고, 끝나면 결과를 저장한다. 끝난 검토는 그대로 둔다.
function advance(context: MockContext, draft: StoredChangeDraft) {
  const review = latestOf(draft)
  if (!review || review.status !== 'RUNNING') return
  const elapsed = Date.now() - Date.parse(review.created_at)
  const scenario = reviewScenario()
  const reached = Math.min(
    Math.floor(elapsed / STEP_MS),
    scenario === 'running' ? 1 : STEP_KEYS.length,
  )
  const finishedStatus = (key: (typeof STEP_KEYS)[number]) =>
    // AI-A03은 초안·문서 변경을 아직 분석하지 않는다(명세: document_change_unavailable 전까지 NOT_EVALUATED).
    key === 'AI_CANDIDATES' ? 'NOT_EVALUATED' : 'DONE'
  review.steps = STEP_KEYS.map((key, index) => ({
    key,
    status:
      index < reached
        ? finishedStatus(key)
        : index === reached
          ? 'RUNNING'
          : 'PENDING',
  }))
  const failWith = (code: string) => {
    review.status = 'FAILED'
    review.error_code = code
    review.completed_at = now()
    review.steps = review.steps.map((step): DraftReviewStep =>
      step.status === 'RUNNING' || step.status === 'PENDING'
        ? { ...step, status: 'FAILED' }
        : step,
    )
  }
  if (elapsed >= TIMEOUT_MS) return failWith('review_timeout')
  if (reached < STEP_KEYS.length) return
  if (scenario === 'timeout') {
    review.steps[3] = { key: 'GAPS_CONFLICTS', status: 'RUNNING' }
    return failWith('review_timeout')
  }
  if (scenario === 'failed') {
    review.steps[3] = { key: 'GAPS_CONFLICTS', status: 'RUNNING' }
    return failWith('model_unavailable')
  }
  const project = requireProject(context)
  const affected = affectedTasks(project, draft)
  review.tasks_to_wait = affected
    .filter((task) => task.status === 'IN_PROGRESS')
    .map((task) => ({
      task_id: task.id,
      title: task.current_plan_revision.plan.title,
      ...(task.assignee_member_id
        ? { assignee_member_id: task.assignee_member_id }
        : {}),
    }))
  review.validations_to_stale = affected.flatMap((task) =>
    task.validation && task.validation.status !== 'STALE'
      ? [
          {
            validation_id: task.validation.id,
            task_id: task.id,
            task_title: task.current_plan_revision.plan.title,
          },
        ]
      : [],
  )
  delete review.ai_candidates
  review.suggestions = suggestionsOf(context, draft)
  const changed = draft.documents.reduce(
    (sum, change) =>
      sum +
      proposeChanges(
        findById(project.versions, change.base_document_version_id).content ??
          '',
        change.after_content,
      ).changes.length,
    draft.items.length,
  )
  const counts = {
    READ_CHANGES: changed,
    FIND_RELATED:
      review.tasks_to_wait.length + review.validations_to_stale.length,
    GAPS_CONFLICTS: review.suggestions.length,
  } as Record<string, number>
  review.steps = review.steps.map((step) =>
    step.key in counts ? { ...step, count: counts[step.key] } : step,
  )
  review.status = 'COMPLETED'
  review.summary = `바뀐 곳 ${changed}개를 읽고 수정안 ${review.suggestions.length}개를 찾았어요. 예시 모드의 규칙 결과예요.`
  review.completed_at = now()
}

const stateHash = (review: DraftReview) =>
  mockHash({
    id: review.id,
    status: review.status,
    decisions: review.suggestions.map((item) => [item.id, item.decision]),
  })

function view(context: MockContext, draft: StoredChangeDraft): Draft {
  advance(context, draft)
  const { owner_account_id, reviews, ...rest } = copy(draft)
  void owner_account_id
  const latest = reviews.at(-1)
  return latest
    ? { ...rest, latest_review: latest, review_state_hash: stateHash(latest) }
    : rest
}

// 올릴 때 사용자가 확인해야 하는 상태가 있는지(CHG-A01 처리 규칙).
function needsAcknowledgement(draft: StoredChangeDraft) {
  const review = latestOf(draft)
  return (
    !review ||
    review.status !== 'COMPLETED' ||
    review.resolved_revision !== draft.revision ||
    review.suggestions.some(
      (item) => item.type === 'PATCH' && item.decision === 'PENDING',
    )
  )
}

/**
 * CHG-A01·A04를 draft_id로 받을 때. 검사를 마치면 제안에 쓸 입력과 검토 상태를 돌려주고, 제안을 저장한 뒤 finish를 부른다.
 * source를 주면 반려된 그 제안의 재제출(CHG-A04)이다.
 */
export function takeDraft(context: MockContext, source?: ChangeProposal) {
  const body = (
    source ? resubmitDraftInputSchema : submitDraftInputSchema
  ).parse(context.body)
  const draft = ownDraft(context, body.draft_id)
  guardOpen(draft)
  if (
    source ? draft.source_proposal_id !== source.id : draft.source_proposal_id
  )
    fail(
      400,
      'validation_failed',
      source
        ? '이 제안을 보완한 초안만 다시 올릴 수 있어요.'
        : '반려된 제안을 보완한 초안은 그 제안으로 다시 올려요.',
    )
  guardBaseline(context, draft.base_baseline_id)
  advance(context, draft)
  if (body.expected_draft_revision !== draft.revision)
    fail(
      409,
      'version_conflict',
      '작성 중인 제안이 바뀌었어요. 다시 확인해 주세요.',
    )
  const review = latestOf(draft)
  const required = needsAcknowledgement(draft)
  if (required && !body.review_acknowledged)
    fail(
      409,
      'review_acknowledgement_required',
      '확인할 검토 상태가 있어요. 확인한 뒤 올려 주세요.',
    )
  if (
    body.review_acknowledged &&
    review &&
    body.expected_review_state_hash !== stateHash(review)
  )
    fail(
      409,
      'version_conflict',
      '확인한 뒤 검토 상태가 바뀌었어요. 다시 확인해 주세요.',
    )
  const input: ChangeProposalInput = {
    base_baseline_id: draft.base_baseline_id,
    title: draft.title,
    reason: draft.reason,
    ...(draft.task_id ? { task_id: draft.task_id } : {}),
    ...(draft.validation_id ? { validation_id: draft.validation_id } : {}),
    ...(draft.previous_proposal_id
      ? { previous_proposal_id: draft.previous_proposal_id }
      : {}),
    items: draft.items,
    // 초안의 DRAFT 버전을 그대로 제안에 잇는다.
    documents: draft.documents.map((change) => ({
      document_id: change.document_id,
      base_document_version_id: change.base_document_version_id,
      proposed_document_version_id: change.proposed_document_version_id,
    })),
  }
  const submittedSuggestion = (item: DraftSuggestion) => ({
    type: item.type,
    ...(item.document_id ? { document_id: item.document_id } : {}),
    ...(item.design_item_key ? { design_item_key: item.design_item_key } : {}),
    ...(item.category ? { category: item.category } : {}),
    explanation: item.explanation,
    ...(item.type === 'PATCH'
      ? {
          original_text: item.original_text,
          replacement_text: item.replacement_text,
          decision: item.decision as 'PENDING' | 'SKIPPED',
        }
      : {}),
    sources: item.sources,
  })
  const preSubmitReview: PreSubmitReview = {
    status: !review
      ? 'NOT_REQUESTED'
      : review.status === 'RUNNING'
        ? 'CANCELLED'
        : review.status,
    ...(review ? { review_id: review.id } : {}),
    edited_after_review:
      !!review && review.resolved_revision !== draft.revision,
    pending_suggestions: (review?.suggestions ?? [])
      .filter((item) => item.type === 'PATCH' && item.decision === 'PENDING')
      .map(submittedSuggestion),
    skipped_suggestions: (review?.suggestions ?? [])
      .filter((item) => item.type === 'COMMENT' || item.decision === 'SKIPPED')
      .map(submittedSuggestion),
    acknowledged: required,
  }
  return {
    input,
    preSubmitReview,
    finish(proposalId: string) {
      // 진행 중이던 검토는 취소로 끝낸다. 뒤에 온 결과는 초안과 제출 내용을 바꾸지 못한다.
      if (review?.status === 'RUNNING') {
        review.status = 'CANCELLED'
        review.completed_at = now()
      }
      draft.submitted_proposal_id = proposalId
      draft.updated_at = now()
    },
  }
}

export function draftRoutes(id: string, context: MockContext): MockReply {
  const project = requireProject(context)
  const { body, params } = context
  switch (id) {
    case 'CHG-A16': {
      const input = draftInputSchema.parse(body)
      guardBaseline(context, input.base_baseline_id)
      if (input.source_proposal_id) {
        const source = project.proposals.find(
          (item) =>
            item.id === input.source_proposal_id &&
            item.proposer_account_id === requireAccount(context).id,
        )
        if (!source) fail(404, 'not_found', '보완할 제안을 찾을 수 없어요.')
        if (source.status !== 'REJECTED')
          fail(409, 'invalid_transition', '반려된 제안만 보완해 다시 올려요.')
      }
      const created = now()
      const draft: StoredChangeDraft = {
        ...input,
        id: nextId(context.database),
        project_id: project.project.id,
        revision: 1,
        documents: resolveDocuments(context, input),
        owner_account_id: requireAccount(context).id,
        reviews: [],
        created_at: created,
        updated_at: created,
      }
      drafts(project).push(draft)
      return { status: 201, data: view(context, draft) }
    }
    case 'CHG-A17':
      return { data: view(context, ownDraft(context, params.draft_id)) }
    case 'CHG-A18': {
      const draft = ownDraft(context, params.draft_id)
      const input = updateDraftInputSchema.parse(body)
      guardOpen(draft, input.expected_revision)
      advance(context, draft)
      if (latestOf(draft)?.status === 'RUNNING')
        fail(
          409,
          'review_in_progress',
          '비비가 검토하는 중에는 고칠 수 없어요.',
        )
      // 지금 기준과 다시 비교한 저장만 받는다. 기준 ID만 바꾸거나 고친 글을 덮어쓰지 않는다.
      guardBaseline(context, input.base_baseline_id)
      const documents = resolveDocuments(context, input, draft.documents)
      delete draft.task_id
      delete draft.validation_id
      const { expected_revision, source_proposal_id, ...fields } = input
      void expected_revision
      void source_proposal_id
      Object.assign(draft, fields, {
        documents,
        revision: draft.revision + 1,
        updated_at: now(),
      })
      return { data: view(context, draft) }
    }
    case 'CHG-A19': {
      const draft = ownDraft(context, params.draft_id)
      const input = draftReviewInputSchema.parse(body)
      guardOpen(draft, input.expected_revision)
      guardBaseline(context, draft.base_baseline_id)
      advance(context, draft)
      if (latestOf(draft)?.status === 'RUNNING')
        fail(409, 'review_in_progress', '비비가 이미 검토하고 있어요.')
      const review: DraftReview = {
        id: nextId(context.database),
        status: 'RUNNING',
        input_revision: draft.revision,
        resolved_revision: draft.revision,
        steps: STEP_KEYS.map((key, index) => ({
          key,
          status: index ? 'PENDING' : 'RUNNING',
        })),
        tasks_to_wait: [],
        validations_to_stale: [],
        suggestions: [],
        usage: [],
        created_at: now(),
      }
      draft.reviews.push(review)
      return { status: 202, data: copy(review) }
    }
    case 'CHG-A20': {
      const draft = ownDraft(context, params.draft_id)
      const input = draftDecisionInputSchema.parse(body)
      guardOpen(draft, input.expected_revision)
      advance(context, draft)
      const review = latestOf(draft)
      if (!review || review.id !== params.review_id)
        fail(409, 'invalid_transition', '최신 검토의 수정안만 고를 수 있어요.')
      const suggestion = findById(review.suggestions, params.suggestion_id)
      if (suggestion.type === 'COMMENT')
        fail(409, 'invalid_transition', '의견은 고를 것이 없어요.')
      // 되돌리기(PENDING)로 처리한 선택을 다시 열 수 있다. 반영↔넘기기는 되돌린 뒤에 고른다.
      if (input.decision !== 'PENDING' && suggestion.decision !== 'PENDING')
        fail(409, 'invalid_transition', '먼저 되돌린 뒤 다시 골라 주세요.')
      const textChange =
        input.decision === 'APPLIED'
          ? [suggestion.original_text!, suggestion.replacement_text!]
          : input.decision === 'PENDING' && suggestion.decision === 'APPLIED'
            ? [suggestion.replacement_text!, suggestion.original_text!]
            : undefined
      if (textChange) {
        const change = draft.documents.find(
          (item) =>
            item.document_id === suggestion.document_id &&
            item.base_document_version_id ===
              suggestion.base_document_version_id,
        )
        if (!change || !occursOnce(change.after_content, textChange[0]))
          fail(
            409,
            'review_stale',
            '고친 글이 바뀌어 이 수정안을 반영할 수 없어요.',
          )
        setContent(
          context,
          change,
          change.after_content.replace(textChange[0], textChange[1]),
        )
        // 검토 뒤 고치지 않았을 때만 검토의 반영 리비전도 함께 올린다. 고친 뒤면 '검토 뒤 고침'이 그대로 남는다.
        const inSync = review.resolved_revision === draft.revision
        draft.revision += 1
        if (inSync) review.resolved_revision = draft.revision
      }
      suggestion.decision = input.decision
      draft.updated_at = now()
      return { data: view(context, draft) }
    }
  }
  return fail(404, 'not_found', '명세에 없는 작성 중 제안 요청이에요.')
}
