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

// 담당자 없는(미배정) 작업은 누가 맡을 때까지 아무도 계획 수정·검증·시작·재개하지 못한다(11-04 사용자 결정).
const isAssignee = (context: MockContext, task: TaskDetail) =>
  !!task.assignee_member_id && task.assignee_member_id === context.member?.id

function taskOwner(context: MockContext, task: TaskDetail): void {
  if (!isAssignee(context, task))
    fail(403, 'forbidden', '현재 작업 담당자만 진행할 수 있어요.')
}

// 서버 TaskPlanService.resolveDesignRefs·validateDependencies와 같은 검사(PR #72 mock/live 전수 대조 LA-TC-04).
// 기준에 없는 항목은 400, 보낸 버전이 지금 승인 버전과 다르면 409 version_conflict.
// 자기 자신·없는 작업은 400 validation_failed, 선행 작업이 이미 나를 선행으로 두면(1단계 순환) 409 dependency_cycle.
function checkPlanRefs(
  context: MockContext,
  plan: {
    design_refs: { design_item_id: string; design_item_version_id: string }[]
    dependency_task_ids: string[]
  },
  taskId?: string,
): void {
  const project = requireProject(context)
  for (const reference of plan.design_refs) {
    const current = project.items.find(
      (item) =>
        item.design_item_id === reference.design_item_id &&
        item.status === 'APPROVED',
    )
    if (!current)
      fail(
        400,
        'validation_failed',
        `현재 기준 설계에 없거나 폐기된 항목입니다 (design_item_id=${reference.design_item_id})`,
      )
    if (current.id !== reference.design_item_version_id)
      fail(
        409,
        'version_conflict',
        `설계 참조가 오래됨 (design_item_id=${reference.design_item_id}, 현재 버전=${current.id})`,
      )
  }
  for (const dependencyId of plan.dependency_task_ids) {
    if (dependencyId === taskId)
      fail(
        400,
        'validation_failed',
        '작업이 자기 자신을 선행으로 가질 수 없습니다',
      )
    const dependency = project.tasks.find((item) => item.id === dependencyId)
    if (!dependency)
      fail(
        400,
        'validation_failed',
        '선행 작업 중 같은 프로젝트에 없는 것이 있습니다',
      )
    if (
      taskId &&
      dependency.current_plan_revision.plan.dependency_task_ids.includes(taskId)
    )
      fail(
        409,
        'dependency_cycle',
        `선행 관계가 순환합니다 (task_id=${dependencyId})`,
      )
  }
}

// 서버 TaskTransitionPolicy의 요청 전이 표. 멱등은 바꾸지 않고 지금 상태를 200으로 돌려준다.
const ALLOWED: Record<string, string[]> = {
  DRAFT: ['CANCEL'],
  READY: ['START', 'CANCEL'],
  IN_PROGRESS: ['WAIT', 'COMPLETED', 'CANCEL'],
  WAITING: ['RESUME', 'CANCEL'],
  COMPLETED: [],
  CANCELLED: [],
}
const TASK_ACTIVITY: Record<string, string> = {
  START: 'TASK_STARTED',
  RESUME: 'TASK_RESUMED',
  WAIT: 'TASK_WAITING',
  COMPLETED: 'TASK_COMPLETED',
  CANCEL: 'TASK_CANCELLED',
}
const IDEMPOTENT: Record<string, string[]> = {
  IN_PROGRESS: ['START'],
  COMPLETED: ['COMPLETED'],
  CANCELLED: ['CANCEL'],
}
// 서버 beez.task.stalled-after 기본값. 마지막 보고(여기서는 작업의 마지막 변경 시각) 뒤 이만큼 지나면 보고가 끊긴 작업이다.
const STALLED_AFTER_MS = 30 * 60 * 1000

function syncValidation(context: MockContext, validation: Validation): void {
  const task = findById(requireProject(context).tasks, validation.task_id)
  if (task.current_plan_revision.id === validation.plan_revision_id) {
    task.validation_status = validation.status
    task.validation = copy(validation)
  }
}

// 담당자 인계(PR #50 d4db914, 서버 TaskHandoverService.reassign). TSK-A06과 제외·나가기가 같이 쓴다.
// 같은 담당자면 그대로. 점유 중(IN_PROGRESS·WAITING)이면 DELTA를 뺀 이 작업의 검증(진행 중 포함)을
// STALE(ASSIGNEE_CHANGED)로 바꾸고, IN_PROGRESS는 WAITING(REVALIDATION)으로 내린 뒤 넘긴다.
export function reassignTask(
  context: MockContext,
  task: TaskDetail,
  memberId: string,
): void {
  if (task.assignee_member_id === memberId) return
  if (['IN_PROGRESS', 'WAITING'].includes(task.status))
    for (const validation of requireProject(context).validations) {
      if (
        validation.task_id !== task.id ||
        validation.trigger === 'DELTA' ||
        validation.status === 'STALE'
      )
        continue
      validation.status = 'STALE'
      validation.stale_reason = 'ASSIGNEE_CHANGED'
      validation.finished_at ??= now()
      delete context.database.pending_validations[validation.id]
      syncValidation(context, validation)
    }
  if (task.status === 'IN_PROGRESS') {
    task.status = 'WAITING'
    task.waiting_reason = 'REVALIDATION'
  }
  task.assignee_member_id = memberId
  task.lock_version++
  task.updated_at = now()
}

// 개발 서버에서 검증 실행 실패 화면을 눈으로 보려는 스위치(목 전용, live 빌드에는 들어가지 않는다).
// localStorage 'beez.mock.validation' = lease_expired(롤백 실패 뒤 서버가 실행을 회수) | worker_interrupted | timeout | partial.
// 서버처럼 규칙 항목은 두고 현재 시도의 실행 오류 항목 하나를 더해 ERROR로 끝낸다(00-common 검증 실행 오류·상태 매핑).
// 문구는 서버 AnalysisResultApplier와 docs/design/11-validation-rollback.md 수동 종결 SQL에서 가져왔다.
const VALIDATION_FAILURES = {
  lease_expired: {
    error_code: 'validation_lease_expired',
    message:
      '분석 실행이 시간 안에 결과를 반영하지 못했습니다 (워커 중단·재배포)',
    resolution: 'RETRY',
    next_action: '같은 입력으로 다시 실행합니다 (VAL-A04)',
  },
  worker_interrupted: {
    error_code: 'validation_worker_interrupted',
    message: '워커 중단으로 운영자가 종결했습니다',
    resolution: 'RETRY',
    next_action: '같은 입력으로 다시 실행합니다 (VAL-A04)',
  },
  timeout: {
    error_code: 'ai_timeout',
    message: '분석 시간이 초과되었습니다',
    resolution: 'RETRY',
    next_action: '같은 입력으로 다시 실행합니다 (VAL-A04)',
  },
  partial: {
    error_code: 'ai_evidence_insufficient',
    message: '의미 분석을 일부 수행하지 못했습니다',
    resolution: 'HUMAN_REVIEW',
    next_action: '근거와 검사 범위를 보완한 뒤 새 검증을 요청하세요',
  },
} as const
function failureScenario() {
  try {
    const value = localStorage.getItem('beez.mock.validation') ?? ''
    return Object.hasOwn(VALIDATION_FAILURES, value)
      ? VALIDATION_FAILURES[value as keyof typeof VALIDATION_FAILURES]
      : undefined
  } catch {
    return undefined
  }
}

function evaluate(context: MockContext, validation: Validation): void {
  const project = requireProject(context)
  const task = findById(project.tasks, validation.task_id)
  const revision = findById(project.revisions, validation.plan_revision_id)
  if (revision.id !== task.current_plan_revision.id) {
    validation.status = 'STALE'
    validation.stale_reason = 'PLAN_CHANGED'
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
    relatedTaskId?: string,
  ) =>
    findings.push({
      id: nextId(context.database),
      source: 'RULE',
      kind,
      severity: 'BLOCKING',
      message,
      ...(relatedTaskId ? { related_task_id: relatedTaskId } : {}),
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
    const occupant =
      reference.relation === 'CHANGES' &&
      project.tasks.find(
        (other) =>
          other.id !== task.id &&
          ['IN_PROGRESS', 'WAITING'].includes(other.status) &&
          other.current_plan_revision.plan.design_refs.some(
            (item) =>
              item.relation === 'CHANGES' &&
              item.design_item_id === reference.design_item_id,
          ),
      )
    // 서버처럼 부딪힌 작업을 related_task_id로 알려 화면이 협의할 작업과 담당자를 보여 준다.
    if (occupant)
      add(
        'CONFLICT',
        '다른 진행 작업이 같은 설계 항목의 변경을 점유하고 있어요.',
        'HUMAN_REVIEW',
        occupant.id,
      )
  }
  // The mock evaluates explicit rules only. Unlinked semantics require a real analysis provider.
  if (plan.unlinked.length)
    add(
      'MISSING_POLICY',
      '연결하지 않은 계약 변경은 사람의 검토가 필요해요. 예시 환경은 의미 분석을 실행하지 않아요.',
      'HUMAN_REVIEW',
    )
  const failure = failureScenario()
  if (failure) {
    findings.push({
      id: nextId(context.database),
      source: 'MODEL',
      kind: 'ERROR',
      severity: 'BLOCKING',
      message: failure.message,
      sources: [],
      resolution: failure.resolution,
      next_action: failure.next_action,
      validation_run_id: nextId(context.database),
    })
    validation.error_code = failure.error_code
  }
  validation.findings = findings
  validation.status = failure
    ? 'ERROR'
    : findings.some(
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
  // 공통 계약 6.2: PASS만 SUCCESS, 나머지 끝 상태는 FAILURE
  activity(context, 'VALIDATION_FINISHED', {
    task_id: task.id,
    result: validation.status === 'PASS' ? 'SUCCESS' : 'FAILURE',
  })
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
      checkPlanRefs(context, plan)
      project.tasks.push(next)
      project.revisions.push(copy(revision))
      activity(context, 'TASK_CREATED', { task_id: taskId })
      return { status: 201, data: next }
    }
    case 'TSK-A02': {
      // 11-04 사용자 결정: status는 여러 번 주면 그중 하나, unassigned=true면 담당자 없는 작업만. 둘 다 AND다.
      const statuses = query.getAll('status')
      const unassigned = query.get('unassigned') === 'true'
      if (unassigned && query.get('assignee_member_id'))
        fail(
          400,
          'validation_failed',
          'unassigned와 assignee_member_id는 함께 쓸 수 없어요.',
        )
      return {
        data: page(
          project.tasks.filter(
            (item) =>
              (!statuses.length || statuses.includes(item.status)) &&
              (!unassigned || !item.assignee_member_id) &&
              (!query.get('assignee_member_id') ||
                item.assignee_member_id === query.get('assignee_member_id')) &&
              // 서버처럼 validation_status도 페이지를 자르기 전에 건다(LA-TC-05).
              (!query.get('validation_status') ||
                item.validation_status === query.get('validation_status')),
          ),
          query,
        ),
      }
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
      checkPlanRefs(context, plan, task!.id)
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
      // 서버 TaskPlanService: 점유 중(IN_PROGRESS·WAITING)이던 작업의 이전 검증은 PLAN_CHANGED로 무효,
      // IN_PROGRESS만 WAITING(REVALIDATION)으로 내린다. 다른 대기 사유는 덮어쓰지 않는다(LA-TC-02).
      if (['IN_PROGRESS', 'WAITING'].includes(task!.status))
        for (const item of project.validations)
          if (item.task_id === task!.id && item.status !== 'STALE') {
            item.status = 'STALE'
            item.stale_reason = 'PLAN_CHANGED'
            item.finished_at ??= now()
            delete database.pending_validations[item.id]
          }
      if (task!.status === 'IN_PROGRESS') {
        task!.status = 'WAITING'
        task!.waiting_reason = 'REVALIDATION'
      }
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
      // 잠금 번호를 먼저 본다. 같은 미배정 작업을 두 사람이 맡으면 늦은 쪽은 403이 아니라 409 revision_conflict다(11-04).
      expectValue(
        task!.lock_version,
        body.expected_lock_version,
        'revision_conflict',
      )
      // 예외: 팀원은 담당자 없는 작업을 자기에게만 맡길 수 있다(11-04 사용자 결정).
      if (
        context.member?.role !== 'LEADER' &&
        (task!.assignee_member_id ||
          body.assignee_member_id !== context.member?.id)
      )
        fail(
          403,
          'forbidden',
          '팀원은 담당자 없는 작업을 자기에게만 맡길 수 있어요.',
        )
      if (['COMPLETED', 'CANCELLED'].includes(task!.status))
        fail(
          400,
          'invalid_transition',
          '완료·취소된 작업의 담당자는 바꿀 수 없습니다',
        )
      const member = findById(
        project.members,
        requiredString(body, 'assignee_member_id'),
      )
      reassignTask(context, task!, member.id)
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
      // 서버 ValidationRetryService: 보낸 시도 번호가 현재보다 작으면 그 요청이 이미 연 시도가 있는 것이다(응답을 잃고 다시 보냄).
      // 새로 열지 않고 지금 검증을 그대로 돌려준다
      if (
        typeof body.expected_attempt === 'number' &&
        body.expected_attempt < validation!.current_attempt
      )
        return { status: 202, data: validation! }
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
      const input = taskStateInputSchema.parse(body)
      // 서버 TaskStateService: 담당자만 보고한다. 예외로 사람 팀장은 보고가 끊긴(IN_PROGRESS이고 30분 넘게 보고 없음)
      // 남의 작업을 취소할 수 있다. 이미 취소된 작업은 그대로 돌려준다(LA-TC-08).
      if (!isAssignee(context, task!)) {
        if (input.event !== 'CANCEL' || context.member?.role !== 'LEADER')
          taskOwner(context, task!)
        else if (task!.status === 'CANCELLED') return { data: task! }
        else if (
          task!.status !== 'IN_PROGRESS' ||
          Date.now() - Date.parse(task!.updated_at) < STALLED_AFTER_MS
        )
          fail(
            403,
            'forbidden',
            '보고가 끊긴 진행 중 작업만 팀장이 취소할 수 있어요.',
          )
      }
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
      // 서버 TaskTransitionPolicy 전이 표(LA-TC-07). 멱등이면 바꾸지 않고 지금 상태를 돌려준다.
      if (IDEMPOTENT[task!.status]?.includes(input.event))
        return { data: task! }
      if (!ALLOWED[task!.status].includes(input.event))
        fail(
          400,
          'invalid_transition',
          `${task!.status} 상태에서는 ${input.event} 할 수 없습니다`,
        )
      let accepted = false
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
        task!.status = 'IN_PROGRESS'
        task!.started_validation_id = input.validation_id
        delete task!.waiting_reason
      } else if (input.event === 'WAIT') {
        if (!input.waiting_reason)
          fail(400, 'validation_failed', '대기 이유를 입력해 주세요.')
        task!.status = 'WAITING'
        task!.waiting_reason = input.waiting_reason
      } else if (input.event === 'COMPLETED') {
        // 계획과 다른 점(delta)이 있으면 완료가 아니라 재검증 대기로, 응답은 202(LA-TC-03)
        if (input.delta?.length) {
          task!.status = 'WAITING'
          task!.waiting_reason = 'REVALIDATION'
          accepted = true
        } else {
          task!.status = 'COMPLETED'
          delete task!.waiting_reason
        }
      } else {
        task!.status = 'CANCELLED'
        delete task!.waiting_reason
      }
      task!.lock_version++
      task!.updated_at = now()
      // 활동 유형은 실제 전이로 정한다(공통 계약 6.2 '작업 유형 정하기'). delta가 있는 완료는 재검증 대기다.
      activity(
        context,
        accepted ? 'TASK_COMPLETION_REPORTED' : TASK_ACTIVITY[input.event],
        { task_id: task!.id },
      )
      return accepted ? { status: 202, data: task! } : { data: task! }
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
      // 초안 포함(INCLUDE_DRAFTS)이면 초안이 있는 문서는 승인본 대신 최신 초안에서 찾는다.
      const drafts = body.scope === 'INCLUDE_DRAFTS'
      const found = project.documents.flatMap((document) => {
        const version =
          (drafts && document.latest_draft) || document.approved_version
        return version ? [{ title: document.title, version }] : []
      })
      const first = found[0]
      return {
        data: templateGeneration(
          first
            ? `${first.title}의 ${first.version.status === 'DRAFT' ? `초안 v${first.version.version_no}를` : '승인 원문을'} 확인해 주세요. ${first.version.content?.slice(0, 250) ?? ''}`
            : drafts
              ? '찾을 문서가 없어요. 공용 문서함에 문서를 올려 주세요.'
              : '현재 승인된 문서가 없어요. 공용 문서를 검토하고 기준 설계를 발행해 주세요.',
          found.slice(0, 1).map(({ title, version }) => ({
            document_id: version.document_id,
            document_version_id: version.id,
            version_no: version.version_no,
            location: title,
          })),
        ),
      }
    }
    case 'BEE-A02':
      return {
        data: {
          // 예시 추천은 지금 담당자를 그대로 보여 줄 뿐이라, 담당자 없는 작업은 추천하지 않는다.
          suggestions: project.tasks
            .filter(
              (item) =>
                !!item.assignee_member_id &&
                Array.isArray(body.task_ids) &&
                body.task_ids.includes(item.id),
            )
            .flatMap((item) => {
              const primary =
                project.members.find(
                  (member) => member.id === item.assignee_member_id,
                ) ?? project.members[0]
              if (!primary) return []
              return [
                {
                  task_id: item.id,
                  member_id: primary.id,
                  reason:
                    '예시 추천입니다. 현재 담당자와 역할을 검토해 주세요.',
                  alternatives: project.members
                    .filter((member) => member.id !== primary.id)
                    .slice(0, 1)
                    .map((member) => ({
                      member_id: member.id,
                      reason: '예시 대안입니다. 담당 영역을 확인해 주세요.',
                    })),
                },
              ]
            }),
          generation: templateGeneration(
            '현재 담당자를 제안으로 표시했어요. 자동 배정하지 않아요.',
          ),
        },
      }
    case 'BEE-A04': {
      // 서버 BeeRepository.latestActivity와 같은 규칙: 시작한 지 10분 안의 검토와 OPEN 회의 중 가장 최근 하나.
      const since = Date.now() - 10 * 60_000
      const latest = [
        ...(project.running_bee_reviews ?? [])
          .filter((review) => Date.parse(review.started_at) > since)
          .flatMap((review) => {
            const document = project.documents.find(
              (item) => item.id === review.document_id,
            )
            return document
              ? [
                  {
                    at: review.started_at,
                    id: document.id,
                    status: {
                      activity: 'DOCUMENT_REVIEW',
                      target_type: 'DOCUMENT',
                      target_id: document.id,
                      label: `${document.title} 검토 중`,
                    },
                  },
                ]
              : []
          }),
        ...project.meetings
          .filter((meeting) => meeting.status === 'OPEN')
          .map((meeting) => ({
            at: meeting.created_at,
            id: meeting.id,
            status: {
              activity: 'MEETING',
              target_type: 'MEETING',
              target_id: meeting.id,
              label: `'${meeting.title}' 진행 중`,
            },
          })),
      ].sort(
        (a, b) =>
          Date.parse(b.at) - Date.parse(a.at) ||
          Number(BigInt(b.id) - BigInt(a.id)),
      )[0]
      return { data: latest?.status ?? { activity: 'IDLE' } }
    }
    default:
      return undefined
  }
}
