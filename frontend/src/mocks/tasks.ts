import {
  contextRecordInputSchema,
  taskPlanInputSchema,
  taskStateInputSchema,
} from '../api/contracts/common'
import type {
  ContextRecord,
  Finding,
  TaskDetail,
  Validation,
} from '../api/contracts/common'
import {
  activity,
  expectValue,
  fail,
  findById,
  page,
  requireAccount,
  requireProject,
  requiredString,
  templateGeneration,
} from './helpers'
import type { MockContext, MockReply } from './helpers'
import { copy, nextId, now } from './state'

function taskOwner(context: MockContext, task: TaskDetail): void {
  if (task.assignee_member_id !== context.member?.id)
    fail(403, 'forbidden', '현재 작업 담당자만 진행할 수 있어요.')
}

function syncValidation(context: MockContext, validation: Validation): void {
  const task = findById(requireProject(context).tasks, validation.task_id)
  if (task.current_plan_revision.id === validation.plan_revision_id) {
    task.validation_status = validation.status
    task.validation = copy(validation)
  }
}

function evaluate(context: MockContext, validation: Validation): void {
  const project = requireProject(context)
  const task = findById(project.tasks, validation.task_id)
  const revision = findById(project.revisions, validation.plan_revision_id)
  if (revision.id !== task.current_plan_revision.id) {
    validation.status = 'STALE'
    validation.stale_reason = '검증 중 작업 계획의 새 리비전이 저장됐어요.'
    validation.finished_at = now()
    delete context.database.pending_validations[validation.id]
    return
  }
  const plan = revision.plan
  const findings: Finding[] = []
  const add = (
    kind: Finding['kind'],
    message: string,
    resolution: Finding['resolution'] = 'FIX_PLAN',
  ) =>
    findings.push({
      id: nextId(context.database),
      source: 'RULE',
      kind,
      severity: 'BLOCKING',
      message,
      sources: [],
      resolution,
      next_action: '계획과 승인 근거를 확인한 뒤 다시 검증해 주세요.',
    })
  if (
    !plan.title.trim() ||
    !plan.goal.trim() ||
    !plan.approach.trim() ||
    !plan.included_scope.length ||
    !plan.completion_criteria.length
  )
    add(
      'MISSING_INPUT',
      '목표·포함 범위·접근 방법·완료 기준을 모두 작성해 주세요.',
    )
  const changes = plan.design_refs.filter(
    (reference) => reference.relation === 'CHANGES',
  )
  if (!plan.no_contract_change && !changes.length && !plan.unlinked.length)
    add(
      'MISSING_INPUT',
      '계약 변경 항목 또는 아직 연결하지 않은 변경 내용을 작성해 주세요.',
    )
  if (plan.no_contract_change && changes.length)
    add('MISMATCH', '계약 변경 없음과 CHANGES 참조를 함께 사용할 수 없어요.')
  for (const reference of plan.design_refs) {
    const current = project.items.find(
      (item) =>
        item.id === reference.design_item_version_id &&
        item.design_item_id === reference.design_item_id,
    )
    if (!current || current.status !== 'APPROVED')
      add('STALE_REF', '현재 승인되지 않은 설계 버전을 참조하고 있어요.')
    if (
      reference.relation === 'CHANGES' &&
      project.tasks.some(
        (other) =>
          other.id !== task.id &&
          ['IN_PROGRESS', 'WAITING'].includes(other.status) &&
          other.current_plan_revision.plan.design_refs.some(
            (item) =>
              item.relation === 'CHANGES' &&
              item.design_item_id === reference.design_item_id,
          ),
      )
    )
      add(
        'CONFLICT',
        '다른 진행 작업이 같은 설계 항목의 변경을 점유하고 있어요.',
        'HUMAN_REVIEW',
      )
  }
  // The mock evaluates explicit rules only. Unlinked semantics require a real analysis provider.
  if (plan.unlinked.length)
    add(
      'MISSING_POLICY',
      '연결하지 않은 계약 변경은 사람의 검토가 필요해요. 예시 환경은 의미 분석을 실행하지 않아요.',
      'HUMAN_REVIEW',
    )
  validation.findings = findings
  validation.status = findings.some(
    (finding) =>
      finding.kind === 'MISSING_INPUT' || finding.kind === 'STALE_REF',
  )
    ? 'NEEDS_INPUT'
    : findings.length
      ? 'NEEDS_REVIEW'
      : 'PASS'
  validation.finished_at = now()
  delete context.database.pending_validations[validation.id]
  syncValidation(context, validation)
  activity(context, 'TASK_VALIDATED', { task_id: task.id })
}

function startValidation(context: MockContext, task: TaskDetail): MockReply {
  const project = requireProject(context)
  taskOwner(context, task)
  expectValue(
    task.current_plan_revision.id,
    context.body.plan_revision_id,
    'revision_conflict',
  )
  expectValue(
    task.lock_version,
    context.body.expected_lock_version,
    'revision_conflict',
  )
  if (task.validation_status === 'RUNNING')
    fail(409, 'validation_running', '이미 검증 중이에요.')
  const baseline =
    project.baselines.at(-1) ??
    fail(409, 'baseline_not_found', '먼저 기준 설계를 발행해 주세요.')
  const validation: Validation = {
    id: nextId(context.database),
    task_id: task.id,
    plan_revision_id: task.current_plan_revision.id,
    baseline_id: baseline.id,
    status: 'RUNNING',
    trigger: 'AGENT',
    current_attempt: 1,
    design_refs: copy(task.current_plan_revision.plan.design_refs),
    related_task_refs: project.tasks
      .filter(
        (item) =>
          item.id !== task.id &&
          ['IN_PROGRESS', 'WAITING'].includes(item.status),
      )
      .map((item) => ({
        task_id: item.id,
        plan_revision_id: item.current_plan_revision.id,
      })),
    findings: [],
    cache_hit: false,
    created_at: now(),
  }
  project.validations.push(validation)
  context.database.pending_validations[validation.id] = 0
  syncValidation(context, validation)
  return { status: 202, data: validation }
}

export function taskRoutes(
  id: string,
  context: MockContext,
): MockReply | undefined {
  if (!/^(TSK|VAL|CTX|BEE)-/.test(id)) return undefined
  const project = requireProject(context)
  const { body, params, query, database } = context
  const task = params.task_id
    ? findById(project.tasks, params.task_id)
    : undefined
  const validation = params.validation_id
    ? findById(project.validations, params.validation_id)
    : undefined
  switch (id) {
    case 'TSK-A01': {
      const plan = taskPlanInputSchema.parse(body)
      const taskId = nextId(database)
      const revision = {
        id: nextId(database),
        task_id: taskId,
        revision_no: 1,
        plan,
        created_at: now(),
        created_by: requireAccount(context).id,
      }
      const next: TaskDetail = {
        id: taskId,
        key: `TASK-${String(project.tasks.length + 1).padStart(3, '0')}`,
        project_id: project.project.id,
        assignee_member_id: context.member!.id,
        status: 'READY',
        lock_version: 1,
        current_plan_revision: revision,
        validation_status: 'NOT_RUN',
        created_at: now(),
        updated_at: now(),
      }
      if (plan.context_packet_id)
        findById(project.packets, plan.context_packet_id)
      plan.dependency_task_ids.forEach((dependencyId) =>
        findById(project.tasks, dependencyId),
      )
      project.tasks.push(next)
      project.revisions.push(copy(revision))
      activity(context, 'TASK_CREATED', { task_id: taskId })
      return { status: 201, data: next }
    }
    case 'TSK-A02':
      return {
        data: page(
          project.tasks.filter(
            (item) =>
              (!query.get('status') || item.status === query.get('status')) &&
              (!query.get('assignee_member_id') ||
                item.assignee_member_id === query.get('assignee_member_id')),
          ),
          query,
        ),
      }
    case 'TSK-A03':
      return { data: task! }
    case 'TSK-A04': {
      taskOwner(context, task!)
      if (['COMPLETED', 'CANCELLED'].includes(task!.status))
        fail(400, 'invalid_transition', '종료한 작업의 계획은 바꿀 수 없어요.')
      expectValue(
        task!.lock_version,
        body.expected_lock_version,
        'revision_conflict',
      )
      expectValue(
        task!.current_plan_revision.revision_no,
        body.expected_revision_no,
        'revision_conflict',
      )
      const plan = taskPlanInputSchema.parse(body)
      plan.dependency_task_ids.forEach((dependencyId) => {
        if (dependencyId === task!.id)
          fail(
            400,
            'dependency_cycle',
            '자기 자신을 선행 작업으로 지정할 수 없어요.',
          )
        findById(project.tasks, dependencyId)
      })
      const revision = {
        id: nextId(database),
        task_id: task!.id,
        revision_no: task!.current_plan_revision.revision_no + 1,
        plan,
        created_at: now(),
        created_by: requireAccount(context).id,
      }
      project.revisions.push(revision)
      task!.current_plan_revision = copy(revision)
      task!.lock_version++
      task!.validation_status = 'NOT_RUN'
      delete task!.validation
      task!.updated_at = now()
      return { status: 201, data: task! }
    }
    case 'TSK-A05':
      return {
        data: page(
          project.revisions.filter((revision) => revision.task_id === task!.id),
          query,
        ),
      }
    case 'TSK-A06': {
      if (context.member?.role !== 'LEADER')
        fail(403, 'forbidden', '인간 팀장이 담당자를 지정할 수 있어요.')
      expectValue(
        task!.lock_version,
        body.expected_lock_version,
        'revision_conflict',
      )
      const member = findById(
        project.members,
        requiredString(body, 'assignee_member_id'),
      )
      task!.assignee_member_id = member.id
      task!.lock_version++
      task!.updated_at = now()
      return { data: task! }
    }
    case 'VAL-A01':
      return startValidation(context, task!)
    case 'VAL-A02': {
      if (validation!.status === 'RUNNING') {
        const count = database.pending_validations[validation!.id] ?? 0
        database.pending_validations[validation!.id] = count + 1
        if (count >= 1) evaluate(context, validation!)
      }
      return { data: validation! }
    }
    case 'VAL-A03':
      return {
        data: page(
          project.validations.filter((item) => item.task_id === task!.id),
          query,
        ),
      }
    case 'VAL-A04': {
      const currentTask = findById(project.tasks, validation!.task_id)
      taskOwner(context, currentTask)
      expectValue(
        validation!.current_attempt,
        body.expected_attempt,
        'revision_conflict',
      )
      if (validation!.status !== 'ERROR')
        fail(
          409,
          'invalid_transition',
          '실패한 분석 실행만 재시도할 수 있어요. 계획을 바꿨다면 새 검증을 시작해 주세요.',
        )
      validation!.status = 'RUNNING'
      validation!.current_attempt++
      delete validation!.error_code
      delete validation!.finished_at
      database.pending_validations[validation!.id] = 0
      syncValidation(context, validation!)
      return { status: 202, data: validation! }
    }
    case 'TSK-A07': {
      taskOwner(context, task!)
      const input = taskStateInputSchema.parse(body)
      expectValue(
        task!.lock_version,
        input.expected_lock_version,
        'revision_conflict',
      )
      expectValue(
        task!.current_plan_revision.id,
        input.plan_revision_id,
        'revision_conflict',
      )
      if (['COMPLETED', 'CANCELLED'].includes(task!.status))
        fail(400, 'invalid_transition', '이미 종료한 작업이에요.')
      if (['START', 'RESUME'].includes(input.event)) {
        if (
          task!.validation_status !== 'PASS' ||
          task!.validation?.id !== input.validation_id
        )
          fail(
            409,
            'validation_required',
            '현재 계획의 통과한 검증이 필요해요.',
          )
        const dependencies =
          task!.current_plan_revision.plan.dependency_task_ids
        if (
          dependencies.some(
            (dependencyId) =>
              findById(project.tasks, dependencyId).status !== 'COMPLETED',
          )
        )
          fail(
            409,
            'dependency_incomplete',
            '선행 작업이 아직 완료되지 않았어요.',
          )
        const refs = task!.current_plan_revision.plan.design_refs
        if (
          refs.some(
            (reference) =>
              !project.items.some(
                (item) =>
                  item.id === reference.design_item_version_id &&
                  item.status === 'APPROVED',
              ),
          )
        )
          fail(409, 'stale_validation', '현재 승인 근거로 다시 검증해 주세요.')
        if (input.event === 'RESUME' && task!.status !== 'WAITING')
          fail(400, 'invalid_transition', '대기 중인 작업만 재개할 수 있어요.')
        if (input.event === 'START' && task!.status !== 'READY')
          fail(400, 'invalid_transition', '준비된 작업만 시작할 수 있어요.')
        task!.status = 'IN_PROGRESS'
        task!.started_validation_id = input.validation_id
        delete task!.waiting_reason
      } else if (input.event === 'WAIT') {
        if (!input.waiting_reason)
          fail(400, 'validation_failed', '대기 이유를 입력해 주세요.')
        task!.status = 'WAITING'
        task!.waiting_reason = input.waiting_reason
      } else if (input.event === 'COMPLETED') {
        if (task!.status !== 'IN_PROGRESS')
          fail(400, 'invalid_transition', '진행 중인 작업만 완료할 수 있어요.')
        task!.status = 'COMPLETED'
        delete task!.waiting_reason
      } else {
        task!.status = 'CANCELLED'
        delete task!.waiting_reason
      }
      task!.lock_version++
      task!.updated_at = now()
      activity(context, `TASK_${input.event}`, { task_id: task!.id })
      return { data: task! }
    }
    case 'CTX-A01':
      return { data: findById(project.packets, params.packet_id) }
    case 'CTX-A02':
      return {
        data: page(
          project.records.filter(
            (record) =>
              (record.visibility === 'SHARED' ||
                record.author_account_id === requireAccount(context).id) &&
              (!query.get('task_id') ||
                record.task_id === query.get('task_id')) &&
              (!query.get('q') ||
                `${record.summary} ${record.detail}`.includes(query.get('q')!)),
          ),
          query,
        ),
      }
    case 'CTX-A03': {
      const record = findById(project.records, params.record_id)
      if (
        record.visibility === 'PRIVATE' &&
        record.author_account_id !== requireAccount(context).id
      )
        fail(404, 'not_found', '작업 기록을 찾을 수 없어요.')
      return { data: record }
    }
    case 'CTX-A04': {
      const input = contextRecordInputSchema.parse(body)
      if (input.task_id) findById(project.tasks, input.task_id)
      const previous = input.previous_id
        ? findById(project.records, input.previous_id)
        : undefined
      if (previous && previous.author_account_id !== requireAccount(context).id)
        fail(403, 'forbidden', '내 작업 기록만 수정할 수 있어요.')
      if (previous && !previous.is_latest_revision)
        fail(
          409,
          'revision_conflict',
          '최신 작업 기록을 기준으로 수정해 주세요.',
        )
      const recordId = nextId(database)
      const record: ContextRecord = {
        ...input,
        id: recordId,
        root_id: previous?.root_id ?? recordId,
        revision_no: (previous?.revision_no ?? 0) + 1,
        author_account_id: requireAccount(context).id,
        is_latest_revision: true,
        references_current: true,
        created_at: now(),
      }
      if (previous) previous.is_latest_revision = false
      project.records.push(record)
      return { status: 201, data: record }
    }
    case 'CTX-A05':
      return {
        data: {
          candidates: project.items
            .filter((item) => item.status === 'APPROVED')
            .map((item) => ({
              design_item_version: item,
              reason:
                '예시 프로젝트의 현재 승인 항목입니다. 적용 여부를 직접 확인해 주세요.',
              sources: item.source ? [item.source] : [],
            })),
        },
      }
    case 'BEE-A01': {
      requiredString(body, 'question')
      const approved = project.documents.filter(
        (document) => document.approved_version,
      )
      return {
        data: templateGeneration(
          approved.length
            ? `${approved[0].title}의 승인 원문을 확인해 주세요. ${approved[0].approved_version?.content?.slice(0, 250) ?? ''}`
            : '현재 승인된 문서가 없어요. 공용 문서를 검토하고 기준 설계를 발행해 주세요.',
          approved.slice(0, 1).map((document) => ({
            document_id: document.id,
            document_version_id: document.approved_version!.id,
            version_no: document.approved_version!.version_no,
            location: document.title,
          })),
        ),
      }
    }
    case 'BEE-A02':
      return {
        data: {
          suggestions: project.tasks
            .filter(
              (item) =>
                Array.isArray(body.task_ids) && body.task_ids.includes(item.id),
            )
            .map((item) => ({
              task_id: item.id,
              member_id: item.assignee_member_id,
              reason: '예시 추천입니다. 현재 담당자와 역할을 검토해 주세요.',
            })),
          generation: templateGeneration(
            '현재 담당자를 제안으로 표시했어요. 자동 배정하지 않아요.',
          ),
        },
      }
    default:
      return undefined
  }
}
