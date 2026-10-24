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
    let updated = 0
    database.notifications
      .filter(
        (item) =>
          item.account_id === requireAccount(context).id &&
          ids.has(item.id) &&
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
        { member_id: context.member!.id, status: 'ACCEPTED' },
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
    case 'MTG-A05': {
      const participant =
        meeting!.participants.find(
          (item) => item.member_id === context.member?.id,
        ) ?? fail(403, 'forbidden', '초대된 회의 참여자만 응답할 수 있어요.')
      if (body.status !== 'ACCEPTED' && body.status !== 'DECLINED')
        fail(400, 'validation_failed', '참여 상태를 확인해 주세요.')
      participant.status = body.status
      return { data: meeting! }
    }
    case 'MTG-A06': {
      if (
        !meeting!.participants.some(
          (item) =>
            item.member_id === context.member?.id && item.status === 'ACCEPTED',
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
      const generation = templateGeneration(
        `회의 텍스트를 모았어요. 실제 AI 요약은 실행하지 않았어요.\n${summary}`,
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
              draft_count: project.documents.filter(
                (document) => document.kind === kind && document.latest_draft,
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
          input_tokens: 0,
          output_tokens: 0,
          call_count: 0,
          estimated_cost_amount: '0',
          currency: 'USD',
          by_feature: [],
          by_task: [],
          by_validation: [],
        },
      }
    case 'MON-A03':
      return {
        data: {
          nodes: [
            ...project.tasks.map((task) => ({
              id: task.id,
              type: 'TASK',
              label: task.current_plan_revision.plan.title,
            })),
            ...project.items
              .filter((item) => item.status === 'APPROVED')
              .map((item) => ({
                id: item.design_item_id,
                type: 'DESIGN_ITEM',
                label: item.key,
              })),
          ],
          edges: project.tasks.flatMap((task) =>
            task.current_plan_revision.plan.design_refs.map((reference) => ({
              from_id: task.id,
              from_type: 'TASK',
              to_id: reference.design_item_id,
              to_type: 'DESIGN_ITEM',
              relation: reference.relation,
              confirmed: true,
            })),
          ),
        },
      }
    case 'MON-A04':
      return {
        data: page(
          project.activities.filter(
            (item) => !query.get('type') || item.type === query.get('type'),
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
            const task = project.tasks.find(
              (item) =>
                item.assignee_member_id === member.id &&
                item.status === 'IN_PROGRESS',
            )
            const agent = project.agents.find(
              (item) => item.member_id === member.id,
            )
            return {
              member_id: member.id,
              ...(agent ? { agent_session_id: agent.id } : {}),
              ...(task
                ? {
                    current_task_id: task.id,
                    task_status: task.status,
                    validation_status: task.validation_status,
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
