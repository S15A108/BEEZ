import {
  changeCommentSchema,
  changeProposalInputSchema,
} from '../api/contracts/common'
import type { ChangeProposal } from '../api/contracts/common'
import {
  activity,
  expectValue,
  fail,
  findById,
  notification,
  page,
  requireAccount,
  requireLeader,
  requireProject,
  requiredString,
  templateGeneration,
} from './helpers'
import type { MockContext, MockReply } from './helpers'
import { copy, nextId, now } from './state'

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

function publish(context: MockContext, proposal: ChangeProposal): MockReply {
  requireLeader(context)
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
  expectValue(proposal.updated_at, context.body.expected_updated_at)
  if (!['PROPOSED', 'IN_REVIEW'].includes(proposal.status))
    fail(400, 'invalid_transition', '검토 중인 변경만 승인할 수 있어요.')
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
  const affectedItemIds = new Set(
    proposal.items.map((item) => item.design_item_id),
  )
  const affectedDocumentIds = new Set(
    proposal.documents.map((document) => document.document_id),
  )
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
    version.status = 'APPROVED'
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
  })
  for (const task of project.tasks) {
    if (['COMPLETED', 'CANCELLED'].includes(task.status)) continue
    const references = task.current_plan_revision.plan.design_refs
    const affected = references.some(
      (reference) =>
        affectedItemIds.has(reference.design_item_id) ||
        project.items.some(
          (item) =>
            item.design_item_id === reference.design_item_id &&
            !!item.source &&
            affectedDocumentIds.has(item.source.document_id),
        ),
    )
    if (!affected) continue
    task.validation_status = 'STALE'
    for (const validation of project.validations.filter(
      (item) =>
        item.task_id === task.id &&
        item.plan_revision_id === task.current_plan_revision.id,
    )) {
      validation.status = 'STALE'
      validation.stale_reason = '참조한 승인 설계가 변경됐어요.'
      delete context.database.pending_validations[validation.id]
    }
    if (task.validation) {
      task.validation.status = 'STALE'
      task.validation.stale_reason = '참조한 승인 설계가 변경됐어요.'
    }
    if (task.status === 'IN_PROGRESS') {
      task.status = 'WAITING'
      task.waiting_reason = 'REVALIDATION'
      task.lock_version++
    }
  }
  activity(context, 'CHANGE_PUBLISHED', {
    change_proposal_id: proposal.id,
    baseline_id: baseline.id,
  })
  notification(
    context,
    '변경 제안이 새 기준 설계로 발행됐어요.',
    'BASELINE',
    baseline.id,
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
    case 'CHG-A01': {
      const input = changeProposalInputSchema.parse(body)
      expectValue(
        project.project.current_baseline?.id,
        input.base_baseline_id,
        'baseline_conflict',
      )
      const created = now()
      const next: ChangeProposal = {
        ...input,
        id: nextId(database),
        project_id: project.project.id,
        status: 'PROPOSED',
        proposer_account_id: requireAccount(context).id,
        actor_type: 'HUMAN',
        current_baseline_id: input.base_baseline_id,
        requires_refresh: false,
        created_at: created,
        updated_at: created,
      }
      project.proposals.push(next)
      activity(context, 'CHANGE_PROPOSED', { change_proposal_id: next.id })
      return { status: 201, data: next }
    }
    case 'CHG-A02':
      return {
        data: page(
          project.proposals
            .filter(
              (item) =>
                !query.get('status') || item.status === query.get('status'),
            )
            .map((item) => proposalView(context, item)),
          query,
        ),
      }
    case 'CHG-A03':
      return {
        data: {
          proposal: proposalView(context, proposal!),
          comparisons: proposal!.documents.map((change) => {
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
              format: 'TEXT',
              base_content: base.content ?? '',
              current_content: document.approved_version?.content ?? '',
              proposed_content: after,
              diff: `- ${base.content ?? ''}\n+ ${after}`,
            }
          }),
        },
      }
    case 'CHG-A04': {
      expectValue(proposal!.updated_at, body.expected_updated_at)
      if (proposal!.proposer_account_id !== requireAccount(context).id)
        requireLeader(context)
      if (proposal!.status === 'PUBLISHED')
        fail(409, 'invalid_transition', '발행된 제안은 수정할 수 없어요.')
      Object.assign(proposal!, changeProposalInputSchema.parse(body), {
        status: 'PROPOSED',
        updated_at: now(),
      })
      return { data: proposalView(context, proposal!) }
    }
    case 'CHG-A05':
    case 'CHG-A06': {
      const itemIds = new Set(
        proposal!.items.map((item) => item.design_item_id),
      )
      return {
        data: {
          explicit: project.tasks
            .filter((task) =>
              task.current_plan_revision.plan.design_refs.some((reference) =>
                itemIds.has(reference.design_item_id),
              ),
            )
            .map((task) => ({
              task_id: task.id,
              member_id: task.assignee_member_id,
              ...(task.validation ? { validation_id: task.validation.id } : {}),
            })),
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
      return {
        data: {
          document_id: document.id,
          base_document_version_id: version.id,
          after_content: `${version.content ?? ''}\n\n## 검토할 수정 초안\n${instruction}\n`,
          generation: templateGeneration(
            '입력한 수정 지시를 검토할 초안에 덧붙였어요.',
          ),
        },
      }
    }
    case 'CHG-A08':
      expectValue(proposal!.updated_at, body.expected_updated_at)
      if (proposal!.status !== 'PROPOSED')
        fail(
          400,
          'invalid_transition',
          '제안된 변경만 검토를 시작할 수 있어요.',
        )
      proposal!.status = 'IN_REVIEW'
      proposal!.updated_at = now()
      return { data: proposalView(context, proposal!) }
    case 'CHG-A09':
      return publish(context, proposal!)
    case 'CHG-A10':
      requireLeader(context)
      expectValue(proposal!.updated_at, body.expected_updated_at)
      if (!['PROPOSED', 'IN_REVIEW'].includes(proposal!.status))
        fail(400, 'invalid_transition', '검토 중인 제안만 반려할 수 있어요.')
      proposal!.status = 'REJECTED'
      proposal!.rejection_reason = requiredString(body, 'reason')
      proposal!.updated_at = now()
      return { data: proposal! }
    case 'CHG-A11':
      return {
        data:
          project.approvals.find((item) => item.proposal_id === proposal!.id) ??
          fail(404, 'not_found', '아직 승인 이력이 없어요.'),
      }
    case 'CHG-A12': {
      const comment = changeCommentSchema.parse({
        id: nextId(database),
        proposal_id: proposal!.id,
        author_account_id: requireAccount(context).id,
        kind: body.kind ?? 'COMMENT',
        body: requiredString(body, 'body'),
        created_at: now(),
      })
      project.comments.push(comment)
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
