import {
  changeCommentSchema,
  changeProposalInputSchema,
} from '../api/contracts/common'
import type {
  ChangeProposal,
  ChangeProposalInput,
} from '../api/contracts/common'
import {
  activity,
  expectValue,
  fail,
  findById,
  mockHash,
  notification,
  page,
  requireAccount,
  requireLeader,
  requireProject,
  requiredString,
  templateGeneration,
} from './helpers'
import type { MockContext, MockReply } from './helpers'
import { normalizeDocument } from '../features/changes/normalizeDocument'
import {
  scopedBlocksInputSchema,
  scopedDraftInputSchema,
} from '../features/changes/schemas'
import { describeContent } from '../lib/display'
import { copy, nextId, now } from './state'
import type { MockProject } from './state'
import type { ApprovalPreviewItem } from '../features/changes/schemas'
import { draftRoutes, takeDraft } from './changeDrafts'

function proposalView(
  context: MockContext,
  proposal: ChangeProposal,
): ChangeProposal {
  const current =
    requireProject(context).project.current_baseline?.id ??
    proposal.base_baseline_id
  return {
    ...proposal,
    current_baseline_id: current,
    requires_refresh:
      proposal.base_baseline_id !== current && proposal.status !== 'PUBLISHED',
  }
}

// 제출 스냅샷: 같은 리비전의 본문을 가리키는 해시(PR #46)
function submit(
  context: MockContext,
  input: ChangeProposalInput,
  revision: number,
): Pick<ChangeProposal, 'revision' | 'snapshot_hash' | 'documents'> {
  const project = requireProject(context)
  const documents = input.documents.map((change) => {
    const base = findById(project.versions, change.base_document_version_id)
    const uploaded = change.proposed_document_version_id
      ? findById(project.versions, change.proposed_document_version_id)
      : undefined
    // 서버처럼 본문을 정규화하고, 기준과 같으면 거절한다(PR #46 0df13f0).
    const after = normalizeDocument(
      change.after_content ?? uploaded?.content ?? '',
    )
    if (after === normalizeDocument(base.content ?? ''))
      fail(
        400,
        'validation_failed',
        '기준과 같은 문서는 변경 제안에 넣지 않아요.',
      )
    // after_content로 내도 기준 버전을 부모로 하는 DRAFT 버전을 만들어 돌려준다.
    let version = uploaded
    if (!version) {
      const versionNo =
        Math.max(
          ...project.versions
            .filter((item) => item.document_id === change.document_id)
            .map((item) => item.version_no),
        ) + 1
      version = {
        ...base,
        id: nextId(context.database),
        version_no: versionNo,
        status: 'DRAFT' as const,
        // 기준 버전을 복사해도 현재 기준 표시와 올린 사람은 넘기지 않는다.
        created_by: requireAccount(context).id,
        is_current_baseline: false,
        content: after,
        content_hash: mockHash(after),
        created_at: now(),
      }
      project.versions.push(version)
    }
    return {
      document_id: change.document_id,
      base_document_version_id: change.base_document_version_id,
      proposed_document_version_id: version.id,
      after_content: after,
      content_hash: mockHash(after),
    }
  })
  return {
    revision,
    snapshot_hash: mockHash({
      title: input.title,
      reason: input.reason,
      base_baseline_id: input.base_baseline_id,
      items: input.items,
      documents,
      revision,
    }),
    documents,
  }
}

// 검토한 제출(리비전·해시)이 지금과 같은지 본다. 기준이 없으면 baseline_missing.
function expectSubmission(context: MockContext, proposal: ChangeProposal) {
  if (!requireProject(context).project.current_baseline)
    fail(409, 'baseline_missing', '먼저 첫 기준 설계를 만들어 주세요.')
  if (
    context.body.expected_revision !== proposal.revision ||
    context.body.expected_snapshot_hash !== proposal.snapshot_hash
  )
    fail(
      409,
      'version_conflict',
      '제안이 바뀌었어요. 최신 내용을 확인해 주세요.',
    )
}

// 개발 서버에서 승인 확인 화면의 여러 상태를 눈으로 보려는 스위치(목 전용, live 빌드에는 들어가지 않는다).
// localStorage 'beez.mock.approvalPreview' = override(분석 실패) | unevaluated(안 돌림) | running(재분석 중) | conflict(기준 충돌)
type PreviewScenario =
  'override' | 'unevaluated' | 'running' | 'conflict' | 'indexing'
function previewScenario(): PreviewScenario | undefined {
  try {
    const value = localStorage.getItem('beez.mock.approvalPreview')
    return value === 'override' ||
      value === 'unevaluated' ||
      value === 'running' ||
      value === 'conflict' ||
      value === 'indexing'
      ? value
      : undefined
  } catch {
    return undefined
  }
}

function draftScenario() {
  try {
    const value = localStorage.getItem('beez.mock.draft')
    return value === 'unavailable' ||
      value === 'timeout' ||
      value === 'conflict' ||
      value === 'partial'
      ? value
      : undefined
  } catch {
    return undefined
  }
}

// 이 제안을 발행하면 영향을 받는 작업. 발행(publish)과 승인 미리 보기가 같은 기준을 쓴다.
export function affectedTasks(
  project: MockProject,
  proposal: Pick<ChangeProposal, 'items' | 'documents'>,
) {
  const itemIds = new Set(proposal.items.map((item) => item.design_item_id))
  const documentIds = new Set(
    proposal.documents.map((document) => document.document_id),
  )
  return project.tasks.filter((task) => {
    if (['COMPLETED', 'CANCELLED'].includes(task.status)) return false
    return task.current_plan_revision.plan.design_refs.some(
      (reference) =>
        itemIds.has(reference.design_item_id) ||
        project.items.some(
          (item) =>
            item.design_item_id === reference.design_item_id &&
            !!item.source &&
            documentIds.has(item.source.document_id),
        ),
    )
  })
}

function previewAnalysis(scenario: PreviewScenario | undefined) {
  if (scenario === 'override')
    return {
      status: 'FAILED',
      is_current: false,
      error_code: 'analysis_failed',
      reanalysis_in_progress: false,
    } as const
  if (scenario === 'unevaluated')
    return {
      status: 'NOT_EVALUATED',
      is_current: false,
      error_code: 'analysis_not_started',
      reanalysis_in_progress: false,
    } as const
  if (scenario === 'running')
    return {
      status: 'COMPLETED',
      is_current: true,
      reanalysis_in_progress: true,
    } as const
  return {
    status: 'COMPLETED',
    is_current: true,
    reanalysis_in_progress: false,
  } as const
}

// CHG-A14: 저장 없이 승인하면 같이 바뀌는 것을 계산한다(서버 PublishPlanner 와 같은 모양).
function approvalPreview(
  context: MockContext,
  proposal: ChangeProposal,
): MockReply {
  requireLeader(context)
  const project = requireProject(context)
  const scenario = previewScenario()
  const current =
    project.baselines.at(-1) ??
    fail(409, 'baseline_conflict', '먼저 초기 기준 설계를 발행해 주세요.')
  // 서버(PublishPlanner)의 검사 순서: 상태(invalid_transition) → 기준(baseline_conflict) → 색인(analysis_not_completed).
  if (!['PROPOSED', 'IN_REVIEW'].includes(proposal.status))
    fail(409, 'invalid_transition', '검토 중인 제안만 미리 볼 수 있어요.')
  if (scenario === 'conflict' || current.id !== proposal.base_baseline_id)
    fail(409, 'baseline_conflict', '제안을 올린 뒤 기준이 바뀌었어요.')
  // 제안한 문서 버전의 색인이 아직 없으면 미리 보기도 승인도 409. 실제 BE 점검에서 확인한 응답이다.
  if (scenario === 'indexing')
    fail(
      409,
      'analysis_not_completed',
      '제안 문서 버전의 색인이 준비되지 않았습니다.',
    )
  const changed = new Map(
    proposal.items.map((item, index) => [item.design_item_id, { item, index }]),
  )
  const titleOf = (item: { key: string; content: unknown }) =>
    `${item.key} ${describeContent(item.content)}`
  const baselineItems: ApprovalPreviewItem[] = [
    ...current.items.flatMap((reference): ApprovalPreviewItem[] => {
      const version = findById(project.items, reference.design_item_version_id)
      const change = changed.get(reference.design_item_id)
      if (change?.item.operation === 'RETIRE') return []
      if (change)
        return [
          {
            design_item_id: version.design_item_id,
            title: change.item.proposed_content
              ? titleOf({
                  key: version.key,
                  content: change.item.proposed_content,
                })
              : titleOf(version),
            version_no:
              Math.max(
                ...project.items
                  .filter(
                    (item) => item.design_item_id === version.design_item_id,
                  )
                  .map((item) => item.version_no),
              ) + 1,
            operation: 'UPDATE' as const,
            proposal_item_index: change.index,
          },
        ]
      return [
        {
          design_item_id: version.design_item_id,
          title: titleOf(version),
          version_no: version.version_no,
          operation: 'UNCHANGED' as const,
        },
      ]
    }),
    ...proposal.items.flatMap((item, index) =>
      item.operation === 'CREATE'
        ? [
            {
              title: describeContent(item.proposed_content),
              version_no: 1,
              operation: 'CREATE' as const,
              proposal_item_index: index,
            },
          ]
        : [],
    ),
  ]
  const affected = affectedTasks(project, proposal)
  const changedDocuments = new Set(
    proposal.documents.map((document) => document.document_id),
  )
  // 서버는 완료 · 최신 · 재분석 아님일 때만 후보 수를 준다. 재분석 중이면 이전 결과가 보여도 null 이다(ImpactApprovalGate).
  const trusted = !scenario
  return {
    data: {
      next_baseline_version_no: current.version_no + 1,
      baseline_items: baselineItems,
      validations_to_stale: affected.flatMap((task) =>
        task.validation && task.validation.status !== 'STALE'
          ? [
              {
                validation_id: task.validation.id,
                task_id: task.id,
                task_title: task.current_plan_revision.plan.title,
              },
            ]
          : [],
      ),
      tasks_to_wait: affected
        .filter((task) => task.status === 'IN_PROGRESS')
        .map((task) => ({
          task_id: task.id,
          title: task.current_plan_revision.plan.title,
          assignee_member_id: task.assignee_member_id,
        })),
      unchanged: {
        items: baselineItems.filter((item) => item.operation === 'UNCHANGED')
          .length,
        documents: current.documents.filter(
          (reference) => !changedDocuments.has(reference.document_id),
        ).length,
      },
      unreviewed: {
        // 영향 분석이 완료·최신일 때만 숫자. 아니면 null 로 보내 미평가를 0건으로 보이지 않게 한다.
        ai_candidates: trusted ? 0 : null,
        // 서버는 현재 리비전의 검토 요청 중 지정 대상이 아직 답하지 않은 것만 센다. mock 댓글에는 대상·리비전이 없어,
        // 요청한 사람이 아닌 누군가의 뒤 댓글이 하나라도 있으면 답한 것으로 근사한다.
        open_review_requests: project.comments.filter(
          (request) =>
            request.proposal_id === proposal.id &&
            request.kind === 'REVIEW_REQUEST' &&
            !project.comments.some(
              (reply) =>
                reply.proposal_id === proposal.id &&
                Number(reply.id) > Number(request.id) &&
                reply.author_account_id !== request.author_account_id,
            ),
        ).length,
        ai_analysis: previewAnalysis(scenario),
        requires_override_reason:
          scenario === 'override' || scenario === 'unevaluated',
      },
    },
  }
}

function publish(context: MockContext, proposal: ChangeProposal): MockReply {
  requireLeader(context)
  // 서버처럼 사유는 1~1000자(공백만 불가). 목 서버는 영향 분석을 늘 완료·최신으로 보므로 게이트로 막지 않고 보낸 사유만 이력에 남긴다.
  const overrideReason = context.body.impact_override_reason
  if (
    overrideReason !== undefined &&
    (typeof overrideReason !== 'string' ||
      !overrideReason.trim() ||
      overrideReason.length > 1000)
  )
    fail(400, 'validation_failed', '사유는 1~1000자여야 해요.')
  const project = requireProject(context)
  const current =
    project.baselines.at(-1) ??
    fail(409, 'baseline_conflict', '먼저 초기 기준 설계를 발행해 주세요.')
  expectValue(
    current.id,
    context.body.expected_base_baseline_id,
    'baseline_conflict',
  )
  expectValue(current.id, proposal.base_baseline_id, 'baseline_conflict')
  expectSubmission(context, proposal)
  if (!['PROPOSED', 'IN_REVIEW'].includes(proposal.status))
    fail(400, 'invalid_transition', '검토 중인 변경만 승인할 수 있어요.')
  // 승인 확인 화면의 상태를 눈으로 보려는 스위치(previewScenario)와 같은 규칙으로 막는다(S15P31A108-241).
  const scenario = previewScenario()
  if (scenario === 'indexing')
    fail(
      409,
      'analysis_not_completed',
      '제안 문서 버전의 색인이 준비되지 않았습니다.',
    )
  if (scenario === 'running')
    fail(409, 'analysis_running', '영향 분석이 진행 중이에요.')
  if (
    (scenario === 'override' || scenario === 'unevaluated') &&
    typeof overrideReason !== 'string'
  )
    fail(409, 'impact_not_confirmed', '영향 분석을 확인하지 못했어요.')
  // Check every referenced object before changing the browser's stored state.
  for (const change of proposal.documents) {
    const document = findById(project.documents, change.document_id)
    expectValue(document.approved_version?.id, change.base_document_version_id)
    if (change.proposed_document_version_id)
      findById(project.versions, change.proposed_document_version_id)
  }
  for (const change of proposal.items)
    if (change.operation !== 'CREATE') {
      const item = findById(project.items, change.base_design_item_version_id!)
      if (
        item.design_item_id !== change.design_item_id ||
        item.status !== 'APPROVED'
      )
        fail(409, 'version_conflict', '설계 항목의 승인 버전을 확인해 주세요.')
    }
  const baseline = {
    ...copy(current),
    id: nextId(context.database),
    version_no: current.version_no + 1,
    approved_by: requireAccount(context).id,
    created_at: now(),
  }
  for (const change of proposal.documents) {
    const document = findById(project.documents, change.document_id)
    const oldVersion = document.approved_version!
    const version = change.proposed_document_version_id
      ? findById(project.versions, change.proposed_document_version_id)
      : {
          ...copy(oldVersion),
          id: nextId(context.database),
          version_no:
            Math.max(
              ...project.versions
                .filter((item) => item.document_id === document.id)
                .map((item) => item.version_no),
            ) + 1,
          content: change.after_content,
          content_hash: `sha256:mock-${context.database.next_id}`,
          status: 'APPROVED' as const,
          created_at: now(),
        }
    if (!change.proposed_document_version_id) project.versions.push(version)
    oldVersion.status = 'SUPERSEDED'
    oldVersion.is_current_baseline = false
    version.status = 'APPROVED'
    version.is_current_baseline = true
    document.approved_version = version
    if (document.latest_draft?.id === version.id) delete document.latest_draft
    baseline.documents = baseline.documents.map((reference) =>
      reference.document_id === document.id
        ? { document_id: document.id, document_version_id: version.id }
        : reference,
    )
  }
  for (const change of proposal.items) {
    const previous = change.base_design_item_version_id
      ? findById(project.items, change.base_design_item_version_id)
      : undefined
    if (previous) previous.status = 'SUPERSEDED'
    if (change.operation === 'RETIRE') {
      baseline.items = baseline.items.filter(
        (reference) => reference.design_item_id !== change.design_item_id,
      )
      continue
    }
    const item = {
      ...(previous ? copy(previous) : {}),
      id: nextId(context.database),
      design_item_id: previous?.design_item_id ?? nextId(context.database),
      key: previous?.key ?? `REQ-${project.items.length + 1}`,
      kind: previous?.kind ?? change.kind!,
      version_no: (previous?.version_no ?? 0) + 1,
      status: 'APPROVED' as const,
      schema_version: change.schema_version ?? 1,
      content: change.proposed_content ?? {},
      content_hash: `sha256:mock-${context.database.next_id}`,
    }
    project.items.push(item)
    baseline.items = [
      ...baseline.items.filter(
        (reference) => reference.design_item_id !== item.design_item_id,
      ),
      { design_item_id: item.design_item_id, design_item_version_id: item.id },
    ]
  }
  project.baselines.push(baseline)
  project.project.current_baseline = {
    id: baseline.id,
    version_no: baseline.version_no,
    created_at: baseline.created_at,
  }
  proposal.status = 'PUBLISHED'
  proposal.published_baseline_id = baseline.id
  proposal.current_baseline_id = baseline.id
  proposal.updated_at = now()
  proposal.requires_refresh = false
  project.approvals.push({
    id: nextId(context.database),
    proposal_id: proposal.id,
    approved_by: requireAccount(context).id,
    approved_at: now(),
    baseline_id: baseline.id,
    ...(typeof overrideReason === 'string'
      ? { impact_override_reason: overrideReason.trim() }
      : {}),
  })
  for (const task of affectedTasks(project, proposal)) {
    task.validation_status = 'STALE'
    for (const validation of project.validations.filter(
      (item) =>
        item.task_id === task.id &&
        item.plan_revision_id === task.current_plan_revision.id,
    )) {
      validation.status = 'STALE'
      validation.stale_reason = 'DESIGN_PUBLISHED'
      delete context.database.pending_validations[validation.id]
    }
    if (task.validation) {
      task.validation.status = 'STALE'
      task.validation.stale_reason = 'DESIGN_PUBLISHED'
    }
    if (task.status === 'IN_PROGRESS') {
      task.status = 'WAITING'
      task.waiting_reason = 'REVALIDATION'
      task.lock_version++
    }
  }
  activity(context, 'CHANGE_PROPOSAL_APPROVED', {
    change_proposal_id: proposal.id,
    target_type: 'CHANGE_PROPOSAL',
    target_id: proposal.id,
  })
  activity(context, 'CHANGE_PROPOSAL_PUBLISHED', {
    change_proposal_id: proposal.id,
    baseline_id: baseline.id,
    target_type: 'CHANGE_PROPOSAL',
    target_id: proposal.id,
  })
  notification(
    context,
    '변경 제안이 새 기준 설계로 발행됐어요.',
    'BASELINE',
    baseline.id,
    { baseline_version_no: baseline.version_no },
  )
  return { data: { proposal, baseline } }
}

export function changeRoutes(
  id: string,
  context: MockContext,
): MockReply | undefined {
  if (!id.startsWith('CHG-')) return undefined
  const project = requireProject(context)
  const { body, params, query, database } = context
  const proposal = params.proposal_id
    ? findById(project.proposals, params.proposal_id)
    : undefined
  switch (id) {
    case 'CHG-A07-SCOPED-BLOCKS':
    case 'CHG-A07-SCOPED': {
      const input =
        id === 'CHG-A07-SCOPED'
          ? scopedDraftInputSchema.parse(body)
          : scopedBlocksInputSchema.parse(body)
      const doc = findById(project.documents, input.document_id)
      expectValue(doc.approved_version?.id, input.base_document_version_id)
      const version = findById(project.versions, input.base_document_version_id)
      const content = normalizeDocument(version.content ?? '')
      const lines = content.match(/[^\n]*\n|[^\n]+$/g) ?? []
      const blocks = lines.flatMap((text, index) =>
        text.trim()
          ? [
              {
                block_id: `mock-line-${index}`,
                block_type: 'paragraph',
                locator: {
                  type: 'text_lines',
                  start_line: index + 1,
                  end_line: index + 1,
                },
                text,
              },
            ]
          : [],
      )
      if (id === 'CHG-A07-SCOPED-BLOCKS')
        return {
          data: {
            ...input,
            text_hash: mockHash(content),
            parser_profile: 'mock',
            parser_version: 'mock',
            blocks,
          },
        }
      const draftInput = scopedDraftInputSchema.parse(body)
      if (
        new Set(draftInput.selected_block_ids).size !==
          draftInput.selected_block_ids.length ||
        draftInput.selected_block_ids.some(
          (key) => !blocks.some((block) => block.block_id === key),
        )
      )
        fail(422, 'invalid_ai_input', '선택한 부분을 다시 확인해 주세요.')
      const changes = blocks
        .filter((block) =>
          draftInput.selected_block_ids.includes(block.block_id),
        )
        .map((block) => {
          const newline = block.text.endsWith('\n') ? '\n' : ''
          const after = `[예시 수정] ${draftInput.instruction}${newline}`
          lines[block.locator.start_line - 1] = after
          return {
            block_id: block.block_id,
            before: block.text,
            after,
            reason: '예시 모드 결과',
            evidence: [],
          }
        })
      return {
        data: {
          document_id: input.document_id,
          base_document_version_id: input.base_document_version_id,
          after_content: lines.join(''),
          changes,
          unresolved: [
            '예시 모드의 가상 결과예요. 실제 모델이 생성한 내용이 아닙니다.',
          ],
          generation: { origin: 'TEMPLATE', model: 'mock', usage: [] },
        },
      }
    }
    case 'CHG-A16':
    case 'CHG-A17':
    case 'CHG-A18':
    case 'CHG-A19':
    case 'CHG-A20':
      return draftRoutes(id, context)
    case 'CHG-A01': {
      // 작성 중 제안으로 올리면 내용은 초안에서 오고, 제출 당시 검토 상태를 리비전에 남긴다(S15P31A108-218 수정안).
      const fromDraft =
        body.draft_id === undefined ? undefined : takeDraft(context)
      const input = fromDraft?.input ?? changeProposalInputSchema.parse(body)
      if (!project.project.current_baseline)
        fail(409, 'baseline_missing', '먼저 첫 기준 설계를 만들어 주세요.')
      expectValue(
        project.project.current_baseline.id,
        input.base_baseline_id,
        'baseline_conflict',
      )
      // 거둔 제안만 새 제안에 잇는다. 반려된 제안은 CHG-A04로 같은 번호에서 다시 올린다.
      if (input.previous_proposal_id) {
        const previous = findById(project.proposals, input.previous_proposal_id)
        if (
          previous.status !== 'WITHDRAWN' ||
          previous.proposer_account_id !== requireAccount(context).id
        )
          fail(
            400,
            'validation_failed',
            '내가 거둔 제안만 이어서 올릴 수 있어요.',
          )
      }
      const created = now()
      const proposalId = nextId(database)
      const next: ChangeProposal = {
        ...input,
        ...submit(context, input, 1),
        id: proposalId,
        key: `CP-${proposalId}`,
        project_id: project.project.id,
        status: 'PROPOSED',
        proposer_account_id: requireAccount(context).id,
        actor_type: 'HUMAN',
        current_baseline_id: input.base_baseline_id,
        requires_refresh: false,
        created_at: created,
        updated_at: created,
        ...(fromDraft ? { pre_submit_review: fromDraft.preSubmitReview } : {}),
      }
      project.proposals.push(next)
      fromDraft?.finish(next.id)
      activity(context, 'CHANGE_PROPOSAL_SUBMITTED', {
        change_proposal_id: next.id,
        target_type: 'CHANGE_PROPOSAL',
        target_id: next.id,
      })
      return { status: 201, data: next }
    }
    case 'CHG-A02': {
      // 목록은 본문 없는 요약(ChangeProposalSummary). scope·q·sort는 195 미구현이라 받지 않는다.
      const groups: Record<string, string[]> = {
        IN_PROGRESS: ['PROPOSED', 'IN_REVIEW', 'APPROVED'],
        PUBLISHED: ['PUBLISHED'],
        REJECTED: ['REJECTED'],
      }
      const group = query.get('group')
      return {
        data: page(
          project.proposals
            .filter(
              (item) =>
                (!query.get('status') || item.status === query.get('status')) &&
                (!group || groups[group]?.includes(item.status)),
            )
            .map((item) => ({
              id: item.id,
              key: item.key,
              title: item.title,
              status: item.status,
              revision: item.revision,
              base_baseline_id: item.base_baseline_id,
              proposer_account_id: item.proposer_account_id,
              updated_at: item.updated_at,
            })),
          query,
        ),
      }
    }
    case 'CHG-A03':
      return {
        data: {
          proposal: proposalView(context, proposal!),
          // 목은 발행 후 분석 워커를 실행하지 않으므로 완료나 대기 상태를 만들어 내지 않는다.
          ...(proposal!.status === 'PUBLISHED'
            ? {
                published_analysis: {
                  status: 'UNKNOWN',
                  guidance: 'CHECK_RESULT',
                },
              }
            : {}),
          comparisons: [
            ...proposal!.items.map((change) => {
              const base = change.base_design_item_version_id
                ? findById(project.items, change.base_design_item_version_id)
                : undefined
              const current = base
                ? project.items.find(
                    (item) =>
                      item.design_item_id === base.design_item_id &&
                      item.status === 'APPROVED',
                  )
                : undefined
              return {
                target_type: 'DESIGN_ITEM',
                // 새 항목은 발행 전이라 ID가 없다. 실제 서버처럼 null 키는 빼고 보낸다(전역 non_null).
                ...(change.design_item_id
                  ? { target_id: change.design_item_id }
                  : {}),
                current_changed: !!base && !!current && base.id !== current.id,
                format: 'STRUCTURED',
                ...(base ? { base_content: base.content } : {}),
                ...(current ? { current_content: current.content } : {}),
                ...(change.proposed_content
                  ? { proposed_content: change.proposed_content }
                  : {}),
              }
            }),
            ...proposal!.documents.map((change) => {
              const document = findById(project.documents, change.document_id)
              const base = findById(
                project.versions,
                change.base_document_version_id,
              )
              const after =
                change.after_content ??
                findById(project.versions, change.proposed_document_version_id!)
                  .content ??
                ''
              return {
                target_type: 'DOCUMENT',
                target_id: document.id,
                current_changed: document.approved_version?.id !== base.id,
                format: 'TEXT',
                base_content: base.content ?? '',
                current_content: document.approved_version?.content ?? '',
                proposed_content: after,
                diff: `- ${base.content ?? ''}\n+ ${after}`,
              }
            }),
          ],
          explicit_candidates: [],
          candidates_truncated: false,
          // 195 미구현(#218 요청) 항목별 −/+ 문장. 목은 채워 '지금 할 일' 미리 보기를 보여 준다.
          item_texts: proposal!.items.flatMap((change) => {
            if (!change.design_item_id) return []
            const base = change.base_design_item_version_id
              ? findById(project.items, change.base_design_item_version_id)
              : undefined
            return [
              {
                design_item_id: change.design_item_id,
                ...(base ? { before_text: describeContent(base.content) } : {}),
                ...(change.proposed_content
                  ? { after_text: describeContent(change.proposed_content) }
                  : {}),
              },
            ]
          }),
        },
      }
    case 'CHG-A04': {
      if (proposal!.proposer_account_id !== requireAccount(context).id)
        fail(403, 'forbidden', '제안한 사람만 다시 올릴 수 있어요.')
      expectSubmission(context, proposal!)
      // 올린 제안은 잠기고 반려된 제안만 같은 번호로 다시 올린다(PR #46 CHG-A04, 사용자 결정 10-30).
      if (proposal!.status !== 'REJECTED')
        fail(409, 'proposal_locked', '반려된 제안만 고쳐 다시 올릴 수 있어요.')
      // 반려된 제안을 보완한 초안으로 다시 올리면 내용은 초안에서 온다(S15P31A108-218 수정안).
      const fromDraft =
        body.draft_id === undefined ? undefined : takeDraft(context, proposal)
      const input = fromDraft?.input ?? changeProposalInputSchema.parse(body)
      // 서버 PUT처럼 요청에 없는 선택 필드는 비운다. 옛 task_id·validation_id를 남기면 화면이 빠뜨린 것을 가린다
      // (PR #72 4차 조사 FOURTH-DOC-02).
      delete proposal!.task_id
      delete proposal!.validation_id
      // 검토 상태는 리비전마다 새로 남는다. 초안 없이 다시 올린 리비전에는 없다.
      delete proposal!.pre_submit_review
      Object.assign(
        proposal!,
        input,
        submit(context, input, proposal!.revision + 1),
        { status: 'PROPOSED', updated_at: now() },
        fromDraft ? { pre_submit_review: fromDraft.preSubmitReview } : {},
      )
      delete proposal!.rejection_reason
      fromDraft?.finish(proposal!.id)
      return { data: proposalView(context, proposal!) }
    }
    case 'CHG-A05':
    case 'CHG-A06': {
      const itemIds = new Set(
        proposal!.items.map((item) => item.design_item_id),
      )
      return {
        data: {
          explicit: project.tasks.flatMap((task) =>
            task.current_plan_revision.plan.design_refs
              .filter((reference) => itemIds.has(reference.design_item_id))
              .map((reference) => ({
                task_id: task.id,
                member_id: task.assignee_member_id,
                design_item_id: reference.design_item_id,
                ...(task.validation
                  ? { validation_id: task.validation.id }
                  : {}),
              })),
          ),
          candidates: [],
          analysis_status: 'COMPLETED',
          generation: templateGeneration(
            '예시 데이터의 명시적 설계 참조만 비교했어요.',
          ),
        },
      }
    }
    case 'CHG-A07': {
      const document = findById(
        project.documents,
        requiredString(body, 'document_id'),
      )
      const version = findById(
        project.versions,
        requiredString(body, 'base_document_version_id'),
      )
      if (version.document_id !== document.id)
        fail(404, 'not_found', '문서 버전을 찾을 수 없어요.')
      const instruction = requiredString(body, 'instruction')
      if (!instruction.trim() || instruction.length > 2000)
        fail(400, 'validation_failed', '수정 요청은 2,000자 이하여야 해요.')
      // 서버처럼 지금 기준의 승인본에 대한 초안만 만든다(CHG-A07 version_conflict).
      if (document.approved_version?.id !== version.id)
        fail(409, 'version_conflict', '이 문서의 승인본이 바뀌었어요.')
      // 개발 서버에서 초안 화면의 여러 상태를 눈으로 보려는 스위치(목 전용).
      // localStorage 'beez.mock.draft' = unavailable(502) | timeout(504) | conflict(409) | partial(일부만 반영)
      const scenario = draftScenario()
      if (scenario === 'unavailable')
        fail(502, 'ai_unavailable', '초안을 만들지 못했어요.')
      if (scenario === 'timeout')
        fail(504, 'ai_timeout', '초안을 만드는 데 시간이 걸렸어요.')
      if (scenario === 'conflict')
        fail(409, 'version_conflict', '초안을 만드는 동안 승인본이 바뀌었어요.')
      const generation = templateGeneration(
        '입력한 수정 지시를 검토할 초안에 덧붙였어요.',
        scenario === 'partial'
          ? [
              {
                document_id: document.id,
                document_version_id: version.id,
                version_no: version.version_no,
                location: 'L3-L8',
              },
            ]
          : [],
      )
      return {
        data: {
          document_id: document.id,
          base_document_version_id: version.id,
          after_content: `${version.content ?? ''}\n\n## 검토할 수정 초안\n${instruction}\n`,
          generation:
            scenario === 'partial'
              ? {
                  ...generation,
                  unresolved: ['관련 설계 항목 63개 중 50개만 전달했어요.'],
                }
              : generation,
        },
      }
    }
    case 'CHG-A08':
      requireLeader(context)
      expectSubmission(context, proposal!)
      if (proposal!.status !== 'PROPOSED')
        fail(
          400,
          'invalid_transition',
          '제안된 변경만 검토를 시작할 수 있어요.',
        )
      proposal!.status = 'IN_REVIEW'
      proposal!.updated_at = now()
      activity(context, 'CHANGE_PROPOSAL_REVIEW_STARTED', {
        change_proposal_id: proposal!.id,
        target_type: 'CHANGE_PROPOSAL',
        target_id: proposal!.id,
      })
      return { data: proposalView(context, proposal!) }
    case 'CHG-A09':
      return publish(context, proposal!)
    case 'CHG-A10':
      requireLeader(context)
      expectSubmission(context, proposal!)
      if (!['PROPOSED', 'IN_REVIEW'].includes(proposal!.status))
        fail(400, 'invalid_transition', '검토 중인 제안만 반려할 수 있어요.')
      proposal!.status = 'REJECTED'
      proposal!.rejection_reason = requiredString(body, 'reason')
      proposal!.updated_at = now()
      activity(context, 'CHANGE_PROPOSAL_REJECTED', {
        change_proposal_id: proposal!.id,
        target_type: 'CHANGE_PROPOSAL',
        target_id: proposal!.id,
      })
      return { data: proposalView(context, proposal!) }
    case 'CHG-A14':
      return approvalPreview(context, proposal!)
    case 'CHG-A15': {
      // 거두기(PR #46): 제안자만, 올렸거나 반려된 제안만. 응답은 사용자 결정대로 ChangeProposal 그대로.
      if (proposal!.proposer_account_id !== requireAccount(context).id)
        fail(403, 'forbidden', '제안한 사람만 거둘 수 있어요.')
      expectSubmission(context, proposal!)
      if (!['PROPOSED', 'IN_REVIEW', 'REJECTED'].includes(proposal!.status))
        fail(409, 'invalid_transition', '이미 승인·발행했거나 거둔 제안이에요.')
      const at = now()
      Object.assign(proposal!, {
        status: 'WITHDRAWN',
        withdrawn_by: requireAccount(context).id,
        withdrawn_at: at,
        updated_at: at,
      })
      activity(context, 'CHANGE_PROPOSAL_WITHDRAWN', {
        change_proposal_id: proposal!.id,
        target_type: 'CHANGE_PROPOSAL',
        target_id: proposal!.id,
      })
      return { data: proposalView(context, proposal!) }
    }
    case 'CHG-A11':
      return {
        data:
          project.approvals.find((item) => item.proposal_id === proposal!.id) ??
          fail(404, 'not_found', '아직 승인 이력이 없어요.'),
      }
    case 'CHG-A12': {
      // 실제 서버(ChangeDiscussionService)처럼 끝난 제안의 의견은 409, 대상 없는 검토 요청은 400
      // (PR #72 mock/live 전수 대조 DOC-LIVE-5·6).
      if (['PUBLISHED', 'WITHDRAWN'].includes(proposal!.status))
        fail(
          409,
          'invalid_transition',
          '발행되었거나 거둔 변경 제안에는 의견을 남길 수 없습니다',
        )
      if (
        body.kind === 'REVIEW_REQUEST' &&
        !(
          Array.isArray(body.mentioned_member_ids) &&
          body.mentioned_member_ids.length
        )
      )
        fail(
          400,
          'validation_failed',
          '검토 요청은 대상 멤버를 한 명 이상 지정해야 합니다',
        )
      const comment = changeCommentSchema.parse({
        id: nextId(database),
        proposal_id: proposal!.id,
        author_account_id: requireAccount(context).id,
        kind: body.kind ?? 'COMMENT',
        body: requiredString(body, 'body'),
        created_at: now(),
      })
      project.comments.push(comment)
      activity(
        context,
        comment.kind === 'REVIEW_REQUEST'
          ? 'CHANGE_PROPOSAL_REVIEW_REQUESTED'
          : 'CHANGE_PROPOSAL_COMMENTED',
        {
          change_proposal_id: proposal!.id,
          target_type: 'CHANGE_PROPOSAL',
          target_id: proposal!.id,
        },
      )
      return { status: 201, data: comment }
    }
    case 'CHG-A13':
      return {
        data: page(
          project.comments.filter(
            (comment) => comment.proposal_id === proposal!.id,
          ),
          query,
        ),
      }
    default:
      return undefined
  }
}
