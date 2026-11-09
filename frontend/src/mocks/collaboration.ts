import { meetingSchema } from '../api/contracts/common'
import type { MeetingEntry, Participant } from '../api/contracts/common'
import {
  activity,
  fail,
  findById,
  notification,
  omit,
  page,
  requireAccount,
  requireProject,
  requiredString,
  strings,
  templateGeneration,
} from './helpers'
import type { MockContext, MockReply } from './helpers'
import { nextId, now } from './state'

function counts(items: { status: string }[]) {
  const count = new Map<string, number>()
  items.forEach((item) =>
    count.set(item.status, (count.get(item.status) ?? 0) + 1),
  )
  return [...count].map(([status, total]) => ({ status, count: total }))
}

export function collaborationRoutes(
  id: string,
  context: MockContext,
): MockReply | undefined {
  const { database, body, params, query } = context
  if (id === 'SYS-A01')
    return {
      data: {
        ok: true,
        service: 'beez-browser-mock',
        started_at: '2026-10-23T03:00:00Z',
        server_time: now(),
      },
    }
  if (id === 'SYS-A02')
    return {
      data: {
        ok: true,
        service: 'beez-browser-mock',
        ai_reachable: false,
        time: now(),
      },
    }
  if (id === 'NTF-A01')
    return {
      data: page(
        database.notifications
          .filter(
            (item) =>
              item.account_id === requireAccount(context).id &&
              (!query.get('project_id') ||
                item.project_id === query.get('project_id')) &&
              (query.get('unread') !== 'true' || !item.read_at),
          )
          .map((item) => omit(item, 'account_id')),
        query,
      ),
    }
  if (id === 'NTF-A02') {
    const ids = new Set(strings(body.ids))
    // all=true면 본인 알림 전체(project_id가 있으면 그 프로젝트)를 읽음 처리한다(명세 NTF-A02 수정안).
    const all = body.all === true
    let updated = 0
    database.notifications
      .filter(
        (item) =>
          item.account_id === requireAccount(context).id &&
          (all
            ? !body.project_id || item.project_id === body.project_id
            : ids.has(item.id)) &&
          !item.read_at,
      )
      .forEach((item) => {
        item.read_at = now()
        updated++
      })
    return { data: { updated_count: updated } }
  }
  if (!/^(MTG|MON)-/.test(id)) return undefined
  const project = requireProject(context)
  const meeting = params.meeting_id
    ? findById(project.meetings, params.meeting_id)
    : undefined
  switch (id) {
    case 'MTG-A01': {
      const participants: Participant[] = [
        { member_id: context.member!.id, status: 'JOINED' },
      ]
      for (const memberId of strings(body.participant_member_ids)) {
        findById(project.members, memberId)
        if (!participants.some((item) => item.member_id === memberId))
          participants.push({ member_id: memberId, status: 'INVITED' })
      }
      if (typeof body.change_proposal_id === 'string')
        findById(project.proposals, body.change_proposal_id)
      const created = meetingSchema.parse({
        id: nextId(database),
        project_id: project.project.id,
        title: requiredString(body, 'title'),
        ...(body.change_proposal_id
          ? { change_proposal_id: body.change_proposal_id }
          : {}),
        participants,
        created_at: now(),
      })
      project.meetings.push(created)
      activity(context, 'MEETING_CREATED')
      notification(context, '새 회의가 열렸어요.', 'MEETING', created.id)
      return { status: 201, data: created }
    }
    case 'MTG-A02':
      return { data: page(project.meetings, query) }
    case 'MTG-A03':
      return { data: meeting! }
    case 'MTG-A04': {
      for (const memberId of strings(body.member_ids)) {
        findById(project.members, memberId)
        if (!meeting!.participants.some((item) => item.member_id === memberId))
          meeting!.participants.push({ member_id: memberId, status: 'INVITED' })
      }
      return { data: meeting! }
    }
    case 'MTG-A14': {
      // PR #54: 팀장만, 팀장 자신은 409, 끝난 회의 409, 참여자가 아니어도 204(멱등)
      if (context.member?.role !== 'LEADER')
        fail(403, 'forbidden', '팀장만 참여자를 뺄 수 있어요.')
      if (meeting!.status === 'CLOSED')
        fail(409, 'meeting_closed', '끝난 회의예요.')
      const target = findById(project.members, params.member_id)
      if (target.role === 'LEADER')
        fail(409, 'leader_not_removable', '팀장은 회의에서 뺄 수 없어요.')
      meeting!.participants = meeting!.participants.filter(
        (item) => item.member_id !== target.id,
      )
      return { status: 204 }
    }
    case 'MTG-A05': {
      const participant =
        meeting!.participants.find(
          (item) => item.member_id === context.member?.id,
        ) ?? fail(403, 'forbidden', '초대된 회의 참여자만 응답할 수 있어요.')
      if (body.status !== 'JOINED' && body.status !== 'DECLINED')
        fail(400, 'validation_failed', '참여 상태를 확인해 주세요.')
      participant.status = body.status
      return { data: meeting! }
    }
    case 'MTG-A06': {
      if (
        !meeting!.participants.some(
          (item) =>
            item.member_id === context.member?.id && item.status === 'JOINED',
        )
      )
        fail(403, 'forbidden', '회의 참여를 수락한 뒤 기록해 주세요.')
      const entry: MeetingEntry = {
        id: nextId(database),
        meeting_id: meeting!.id,
        kind: 'TEXT',
        text: requiredString(body, 'text'),
        author_account_id: requireAccount(context).id,
        created_at: now(),
      }
      project.entries.push(entry)
      return { status: 201, data: entry }
    }
    case 'MTG-A07':
    case 'MTG-A08': {
      const through = findById(
        project.entries.filter((item) => item.meeting_id === meeting!.id),
        requiredString(body, 'through_entry_id'),
      )
      const included = project.entries.filter(
        (item) =>
          item.meeting_id === meeting!.id &&
          BigInt(item.id) <= BigInt(through.id) &&
          item.kind === 'TEXT',
      )
      const summary = included.map((entry) => entry.text).join('\n')
      const author = (accountId?: string) =>
        project.members.find((member) => member.account_id === accountId)
          ?.name ?? '팀원'
      // 비비 정리 예시: 누가 무슨 말을 했는지만 모은다. 충돌·결정·할 일을 고르는 실제 AI 정리는 아니다.
      const generation = templateGeneration(
        id === 'MTG-A07'
          ? [
              `회의 내용을 정리했어요 · 메시지 ${included.length}개 기준`,
              ...included.map(
                (entry) =>
                  `- ${author(entry.author_account_id)}: ${entry.text}`,
              ),
              '실제 AI 정리는 실행하지 않았어요.',
            ].join('\n')
          : `회의 텍스트를 모았어요. 실제 AI 요약은 실행하지 않았어요.\n${summary}`,
      )
      const entry: MeetingEntry = {
        id: nextId(database),
        meeting_id: meeting!.id,
        kind: id === 'MTG-A07' ? 'BEE_ANALYSIS' : 'MINUTES',
        text: generation.text,
        through_entry_id: through.id,
        generation,
        created_at: now(),
      }
      project.entries.push(entry)
      return {
        status: 201,
        data:
          id === 'MTG-A07'
            ? generation
            : {
                entry,
                summary,
                decisions: [],
                action_items: [],
                unresolved: [
                  '예시 환경의 회의록입니다. 실제 합의와 할 일을 직접 확인해 주세요.',
                ],
              },
      }
    }
    case 'MTG-A09': {
      const minutes = findById(
        project.entries.filter(
          (entry) =>
            entry.meeting_id === meeting!.id && entry.kind === 'MINUTES',
        ),
        requiredString(body, 'minutes_entry_id'),
      )
      const version = findById(
        project.versions,
        requiredString(body, 'base_document_version_id'),
      )
      if (version.document_id !== body.document_id)
        fail(404, 'not_found', '문서 버전을 찾을 수 없어요.')
      return {
        data: {
          after_content: `${version.content ?? ''}\n\n## 회의 기반 검토 초안\n${minutes.text}`,
          generation: templateGeneration(
            '회의 기록을 수정 초안에 첨부했어요. 문서에 반영하려면 변경 제안과 인간 팀장 승인이 필요해요.',
          ),
        },
      }
    }
    case 'MTG-A10':
      return {
        data: page(
          project.entries.filter((entry) => entry.meeting_id === meeting!.id),
          query,
        ),
      }
    case 'MON-D01':
      return {
        data: {
          ...(project.project.current_baseline
            ? { baseline: project.project.current_baseline }
            : {}),
          by_kind: (['PLAN', 'ERD', 'REQUIREMENTS', 'API_SPEC'] as const).map(
            (kind) => ({
              kind,
              // 서버 StatusRepository처럼 미승인 초안 버전이 있거나 작성 건이 EDITING·SUBMITTED·REJECTED인 문서를
              // 한 번씩 센다. 승인 문서를 다시 고치기 시작하면 초안 버전 없이도 1이다(LA-TC-09).
              draft_count: project.documents.filter(
                (document) =>
                  document.kind === kind &&
                  (document.latest_draft ||
                    project.working_copies.some(
                      (copy) =>
                        copy.document_id === document.id &&
                        ['EDITING', 'SUBMITTED', 'REJECTED'].includes(
                          copy.status,
                        ),
                    )),
              ).length,
              approved_count: project.documents.filter(
                (document) =>
                  document.kind === kind && document.approved_version,
              ).length,
            }),
          ),
        },
      }
    case 'MON-C01':
      return {
        data: {
          by_status: counts(project.proposals),
          review_required_count: project.proposals.filter((proposal) =>
            ['PROPOSED', 'IN_REVIEW'].includes(proposal.status),
          ).length,
        },
      }
    case 'MON-T01': {
      const reasons = new Map<string, number>()
      project.tasks
        .filter((task) => task.status === 'WAITING' && task.waiting_reason)
        .forEach((task) =>
          reasons.set(
            task.waiting_reason!,
            (reasons.get(task.waiting_reason!) ?? 0) + 1,
          ),
        )
      return {
        data: {
          by_status: counts(project.tasks),
          by_waiting_reason: [...reasons].map(([waiting_reason, count]) => ({
            waiting_reason,
            count,
          })),
        },
      }
    }
    case 'MON-V01':
      return { data: { by_status: counts(project.validations) } }
    case 'MON-A02':
      return {
        data: {
          from:
            query.get('from') ??
            new Date(Date.now() - 7 * 86400000).toISOString(),
          to: query.get('to') ?? now(),
          // 실제 서버처럼: 호출이 없으면 비용·통화를 생략하고(0원으로 보이지 않게) 기능별은 8줄을 0으로 채운다.
          input_tokens: 0,
          output_tokens: 0,
          call_count: 0,
          tokens_unknown_call_count: 0,
          cost_unknown_call_count: 0,
          by_feature: [
            'DOCUMENT_PARSE',
            'TASK_VALIDATION',
            'CHANGE_IMPACT',
            'EMBEDDING',
            'BEE_CHAT',
            'BEE_DRAFT',
            'TASK_ASSIGNMENT',
            'MEETING_ANALYSIS',
          ].map((key) => ({
            key,
            input_tokens: 0,
            output_tokens: 0,
            call_count: 0,
            tokens_unknown_call_count: 0,
            cost_unknown_call_count: 0,
          })),
          by_task: [],
          by_validation: [],
          by_agent: [],
          by_day: [],
        },
      }
    case 'MON-A03': {
      // task_id를 주면 그 작업 기준으로 설계 항목·검증·관련 변경 제안만 돌려준다. 변경 제안 연결은 예시로 후보(점선)로 둔다.
      const taskId = query.get('task_id')
      const tasks = project.tasks.filter(
        (task) => !taskId || task.id === taskId,
      )
      const itemIds = new Set(
        tasks.flatMap((task) =>
          task.current_plan_revision.plan.design_refs.map(
            (reference) => reference.design_item_id,
          ),
        ),
      )
      const proposals = project.proposals.filter((proposal) =>
        proposal.items.some(
          (item) => item.design_item_id && itemIds.has(item.design_item_id),
        ),
      )
      // 실제 서버처럼 작업 기준이면 그 작업에서 나온 제안을 PROPOSED_FROM(제안 → 작업)으로 잇는다(RelationGraphService).
      const fromTask = taskId
        ? project.proposals.filter((proposal) => proposal.task_id === taskId)
        : []
      return {
        data: {
          nodes: [
            ...tasks.map((task) => ({
              id: task.id,
              type: 'TASK',
              label: `${task.key} ${task.current_plan_revision.plan.title}`,
            })),
            ...project.items
              .filter(
                (item) =>
                  item.status === 'APPROVED' &&
                  (!taskId || itemIds.has(item.design_item_id)),
              )
              .map((item) => ({
                id: item.design_item_id,
                type: 'DESIGN_ITEM',
                label: item.key,
              })),
            ...tasks
              .filter((task) => task.validation)
              .map((task) => ({
                id: task.validation!.id,
                type: 'VALIDATION',
                label: `계획 검증 ${task.validation!.status}`,
              })),
            ...[
              ...proposals,
              ...fromTask.filter((proposal) => !proposals.includes(proposal)),
            ].map((proposal) => ({
              id: proposal.id,
              type: 'CHANGE_PROPOSAL',
              label: proposal.title,
            })),
          ],
          edges: [
            ...tasks.flatMap((task) =>
              task.current_plan_revision.plan.design_refs.map((reference) => ({
                from_id: task.id,
                from_type: 'TASK',
                to_id: reference.design_item_id,
                to_type: 'DESIGN_ITEM',
                relation: reference.relation,
                confirmed: true,
              })),
            ),
            ...tasks
              .filter((task) => task.validation)
              .map((task) => ({
                from_id: task.id,
                from_type: 'TASK',
                to_id: task.validation!.id,
                to_type: 'VALIDATION',
                relation: 'VALIDATED_BY',
                confirmed: true,
              })),
            ...proposals.flatMap((proposal) =>
              proposal.items
                .filter(
                  (item) =>
                    item.design_item_id && itemIds.has(item.design_item_id),
                )
                .map((item) => ({
                  from_id: proposal.id,
                  from_type: 'CHANGE_PROPOSAL',
                  to_id: item.design_item_id!,
                  to_type: 'DESIGN_ITEM',
                  relation: 'CHANGES',
                  confirmed: false,
                })),
            ),
            ...fromTask.map((proposal) => ({
              from_id: proposal.id,
              from_type: 'CHANGE_PROPOSAL',
              to_id: taskId!,
              to_type: 'TASK',
              relation: 'PROPOSED_FROM',
              confirmed: true,
            })),
          ],
        },
      }
    }
    case 'MON-A04':
      return {
        data: page(
          project.activities.filter(
            (item) =>
              (!query.get('type') || item.type === query.get('type')) &&
              (!query.get('actor_type') ||
                item.actor_type === query.get('actor_type')) &&
              (!query.get('change_proposal_id') ||
                item.change_proposal_id === query.get('change_proposal_id')),
          ),
          query,
        ),
      }
    case 'MON-A05': {
      const members = page(
        project.members.filter(
          (member) =>
            !query.get('member_id') || member.id === query.get('member_id'),
        ),
        query,
      )
      return {
        data: {
          ...members,
          items: members.items.map((member) => {
            // 지금 맡은 작업: 진행 중 → 대기 → 검증 중인 시작 전 작업 순(사무실 에이전트 자리, D37)
            const mine = project.tasks.filter(
              (item) => item.assignee_member_id === member.id,
            )
            const task =
              mine.find((item) => item.status === 'IN_PROGRESS') ??
              mine.find((item) => item.status === 'WAITING') ??
              mine.find(
                (item) =>
                  item.status === 'READY' &&
                  item.validation_status === 'RUNNING',
              )
            const agent = project.agents.find(
              (item) => item.member_id === member.id,
            )
            return {
              member_id: member.id,
              // 서버는 폐기되지 않은 토큰에 세션이 있을 때만 이 행에 세션을 싣고, 같은 조건으로 agent_connected를 정한다.
              // 예시 DB에서는 연결된 에이전트만 세션이 있는 것으로 둔다(PR #128 리뷰).
              ...(agent?.connected ? { agent_session_id: agent.id } : {}),
              ...(task
                ? {
                    current_task_id: task.id,
                    task_status: task.status,
                    validation_status: task.validation_status,
                    ...(task.waiting_reason
                      ? { waiting_reason: task.waiting_reason }
                      : {}),
                  }
                : {}),
            }
          }),
        },
      }
    }
    default:
      return undefined
  }
}
