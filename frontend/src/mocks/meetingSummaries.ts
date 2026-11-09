import { meetingSummarySchema } from '../api/contracts/common'
import type {
  Meeting,
  MeetingDocumentChange,
  MeetingEntry,
  MeetingSummary,
} from '../api/contracts/common'
import { normalizeDocument } from '../features/changes/normalizeDocument'
import {
  activity,
  fail,
  findById,
  requireAccount,
  requireProject,
  requiredString,
  templateGeneration,
} from './helpers'
import type { MockContext, MockReply } from './helpers'
import { changeRoutes } from './changes'
import { nextId, now } from './state'

// MTG-A11~A13 비비 회의 정리·수정 요청·회의 끝내기 예시(S15P31A108-206). 서버 규칙을 따라 하되 실제 AI 정리는 하지 않는다.

const ids = (value: string) => BigInt(value)

/** 정리가 쓸 수 있는 마지막 기록은 사람 기록(TEXT)이나 비비가 연 첫 기록(CONFLICT_BRIEF)이다. */
const summarizable = (entry: MeetingEntry) =>
  entry.kind === 'TEXT' ||
  entry.kind === 'CONFLICT_BRIEF' ||
  // 예전 예시 데이터는 비비의 첫 말을 BEE_ANALYSIS 로 두었다.
  (entry.kind === 'BEE_ANALYSIS' && entry.author_account_id === undefined)

function leaderParticipant(context: MockContext, meeting: Meeting) {
  if (context.member?.role !== 'LEADER')
    fail(403, 'forbidden', '팀장만 요청할 수 있어요.')
  if (
    !meeting.participants.some((item) => item.member_id === context.member!.id)
  )
    fail(403, 'forbidden', '이 회의의 참여자만 할 수 있어요.')
}

function requireOpen(context: MockContext, meeting: Meeting) {
  const project = requireProject(context)
  if (meeting.status === 'CLOSED') fail(409, 'meeting_closed', '끝난 회의예요.')
  if (!project.project.current_baseline)
    fail(409, 'baseline_missing', '먼저 첫 기준 설계를 만들어 주세요.')
}

/** 정리 범위의 줄 변경을 원문에 적용한다. 서버 DocumentLineEditor 와 같은 규칙(줄 범위·before 가 어긋나면 null). */
export function applyLineChanges(
  baseText: string,
  changes: MeetingDocumentChange[],
): string | null {
  const text = normalizeDocument(baseText)
  const endsWithLf = text.endsWith('\n')
  const lines = (endsWithLf ? text.slice(0, -1) : text).split('\n')
  const units = lines.map((line, index) =>
    index < lines.length - 1 || endsWithLf ? `${line}\n` : line,
  )
  const ordered = [...changes].sort(
    (a, b) => a.locator.start_line - b.locator.start_line,
  )
  let previousEnd = 0
  for (const change of ordered) {
    const { start_line: start, end_line: end } = change.locator
    if (start < 1 || end < start || end > units.length || start <= previousEnd)
      return null
    if (units.slice(start - 1, end).join('') !== change.before) return null
    previousEnd = end
  }
  let out = ''
  let next = 1
  for (const change of ordered) {
    const { start_line: start, end_line: end } = change.locator
    out += units.slice(next - 1, start - 1).join('')
    const after = normalizeDocument(change.after)
    const hadLf = units[end - 1].endsWith('\n')
    out += after && hadLf && !after.endsWith('\n') ? `${after}\n` : after
    next = end + 1
  }
  out += units.slice(next - 1).join('')
  return out
}

function summaryFor(
  context: MockContext,
  meeting: Meeting,
  previous?: MeetingSummary,
  feedback?: string,
  throughId?: string,
): MeetingSummary {
  const project = requireProject(context)
  const entries = project.entries
    .filter(
      (item) =>
        item.meeting_id === meeting.id &&
        summarizable(item) &&
        // 서버처럼 요청한 마지막 기록까지만 정리한다(그 뒤에 올라온 대화는 정리에 들어가지 않는다).
        (!throughId || ids(item.id) <= ids(throughId)),
    )
    .sort((a, b) => Number(ids(a.id) - ids(b.id)))
  const through = entries.at(-1)
  if (!through) fail(400, 'validation_failed', '정리할 기록이 없어요.')
  const talk = entries.filter((item) => item.kind === 'TEXT')
  const memberOf = (accountId?: string) =>
    project.members.find((member) => member.account_id === accountId)
  const leaderTalk = talk.filter(
    (item) => memberOf(item.author_account_id)?.role === 'LEADER',
  )
  const decided = leaderTalk.at(-1)
  const decisions = [
    ...(decided
      ? [
          {
            text: decided.text,
            ...(memberOf(decided.author_account_id)
              ? {
                  decided_by_member_id: memberOf(decided.author_account_id)!.id,
                }
              : {}),
          },
        ]
      : []),
    ...(feedback ? [{ text: `고칠 점 반영: ${feedback}` }] : []),
  ]
  // 바뀔 문서: 요구사항 명세서의 REQ-005 줄에 팀이 정한 안내를 덧붙인다(예시).
  const requirements = project.documents.find(
    (document) => document.kind === 'REQUIREMENTS' && document.approved_version,
  )
  const base = requirements?.approved_version
  const lines = normalizeDocument(base?.content ?? '').split('\n')
  const lineIndex = lines.findIndex((line) => line.startsWith('REQ-005'))
  // 예시 회의의 대화에서 정한 안내를 요구사항 문장으로 바꿔 쓴다. 다른 회의는 팀장의 마지막 말을 그대로 덧붙인다.
  const decision = talk.some((item) => item.text.includes('새 초대 링크'))
    ? "쓸 수 없는 초대는 원인을 숨기고 '팀장에게 새 초대 링크를 요청하세요'로만 안내한다."
    : (decided?.text ?? '')
  const documentChanges: MeetingDocumentChange[] =
    requirements && base && lineIndex >= 0 && decided
      ? [
          {
            document_id: requirements.id,
            base_version_id: base.id,
            locator: {
              type: 'text_lines',
              start_line: lineIndex + 1,
              end_line: lineIndex + 1,
            },
            before: `${lines[lineIndex]}\n`,
            after: `${lines[lineIndex]} ${decision}\n`,
            evidence_entry_ids: talk.slice(-2).map((item) => item.id),
          },
        ]
      : []
  const actionItems = (meeting.task_ids ?? []).flatMap((taskId) => {
    const task = project.tasks.find((item) => item.id === taskId)
    return task
      ? [
          {
            text: `${task.key} ${task.current_plan_revision.plan.title}에 정한 내용 반영`,
            member_id: task.assignee_member_id,
          },
        ]
      : []
  })
  const conflicts = meeting.conflict_summary ? [meeting.conflict_summary] : []
  const text = [
    '회의 정리',
    ...(conflicts.length
      ? ['충돌', ...conflicts.map((item) => `- ${item}`)]
      : []),
    ...(decisions.length
      ? ['결정', ...decisions.map((item) => `- ${item.text}`)]
      : []),
    ...(documentChanges.length
      ? ['바뀔 문서', `- 요구사항 명세서 ${lineIndex + 1}행`]
      : []),
    ...(actionItems.length
      ? ['할 일', ...actionItems.map((item) => `- ${item.text}`)]
      : []),
  ].join('\n')
  const generation = templateGeneration(
    '실제 AI 정리는 실행하지 않았어요. 대화에서 팀장의 마지막 결정을 골랐어요.',
  )
  const summaryId = nextId(context.database)
  const entry: MeetingEntry = {
    id: nextId(context.database),
    meeting_id: meeting.id,
    kind: 'SUMMARY',
    summary_id: summaryId,
    text,
    author_kind: 'BEE',
    through_entry_id: through.id,
    generation,
    created_at: now(),
  }
  project.entries.push(entry)
  const summary = meetingSummarySchema.parse({
    id: summaryId,
    meeting_id: meeting.id,
    entry,
    through_entry_id: through.id,
    conflicts,
    decisions,
    document_changes: documentChanges,
    action_items: actionItems,
    ...(previous ? { previous_summary_id: previous.id } : {}),
    ...(feedback ? { feedback } : {}),
    generation,
    created_at: now(),
  })
  ;(project.summaries ??= []).push(summary)
  return summary
}

export function summaryRoutes(
  id: string,
  context: MockContext,
  meeting: Meeting,
): MockReply | undefined {
  const project = requireProject(context)
  const summaries = (project.summaries ??= []).filter(
    (item) => item.meeting_id === meeting.id,
  )
  const latest = summaries.at(-1)
  switch (id) {
    // MTG-A15: 최신 정리를 구조화한 값으로 읽는다. 정리가 아직 없으면 404.
    case 'MTG-A15':
      return {
        data: latest ?? fail(404, 'not_found', '아직 비비 정리가 없어요.'),
      }
    case 'MTG-A11': {
      leaderParticipant(context, meeting)
      requireOpen(context, meeting)
      const through = findById(
        project.entries.filter((item) => item.meeting_id === meeting.id),
        requiredString(context.body, 'through_entry_id'),
      )
      if (!summarizable(through))
        fail(
          400,
          'validation_failed',
          '비비의 분석이나 정리 기록으로는 정리할 수 없어요.',
        )
      return {
        status: 201,
        data: summaryFor(context, meeting, undefined, undefined, through.id),
      }
    }
    case 'MTG-A12': {
      leaderParticipant(context, meeting)
      requireOpen(context, meeting)
      const target = findById(summaries, context.params.summary_id)
      if (target.id !== latest?.id)
        fail(409, 'summary_outdated', '최신 정리가 아니에요.')
      const feedback = requiredString(context.body, 'feedback')
      if (feedback.length > 4000)
        fail(400, 'validation_failed', '고칠 점은 4,000자까지 쓸 수 있어요.')
      return {
        status: 201,
        data: summaryFor(context, meeting, target, feedback),
      }
    }
    case 'MTG-A13': {
      leaderParticipant(context, meeting)
      requireOpen(context, meeting)
      const summary = findById(
        summaries,
        requiredString(context.body, 'summary_id'),
      )
      const outdated = () =>
        fail(
          409,
          'summary_outdated',
          '정리 뒤 새 기록이 있거나 최신 정리가 아니에요.',
        )
      if (summary.id !== latest?.id) outdated()
      // 정리 뒤에 사람이 쓴 기록(TEXT)이 있으면 정리가 오래됐다.
      if (
        project.entries.some(
          (item) =>
            item.meeting_id === meeting.id &&
            item.kind === 'TEXT' &&
            ids(item.id) > ids(summary.through_entry_id),
        )
      )
        outdated()
      // 정리한 뒤 기준이 바뀌어 인용한 문서의 승인본이 달라졌으면 끝낼 수 없다.
      for (const change of summary.document_changes) {
        const document = findById(project.documents, change.document_id)
        if (document.approved_version?.id !== change.base_version_id) outdated()
      }
      const grouped = new Map<string, MeetingDocumentChange[]>()
      for (const change of summary.document_changes)
        grouped.set(change.document_id, [
          ...(grouped.get(change.document_id) ?? []),
          change,
        ])
      const documents = [...grouped].flatMap(([documentId, changes]) => {
        const base = findById(project.versions, changes[0].base_version_id)
        const after = applyLineChanges(base.content ?? '', changes)
        if (after === null) outdated()
        return after === normalizeDocument(base.content ?? '')
          ? []
          : [
              {
                document_id: documentId,
                base_document_version_id: base.id,
                after_content: after!,
              },
            ]
      })
      let proposalId: string | undefined
      if (documents.length) {
        const created = changeRoutes('CHG-A01', {
          ...context,
          params: {},
          body: {
            base_baseline_id: project.project.current_baseline!.id,
            title: `회의 결과: ${meeting.title}`.slice(0, 200),
            reason: [
              ...summary.conflicts.map((item) => `충돌: ${item}`),
              ...summary.decisions.map((item) => `결정: ${item.text}`),
            ].join('\n'),
            items: [],
            documents,
          },
        })
        proposalId = (created?.data as { id: string }).id
      }
      const me = requireAccount(context)
      meeting.status = 'CLOSED'
      meeting.closed_at = now()
      meeting.closed_by = me.id
      if (proposalId) meeting.change_proposal_id = proposalId
      const notified = proposalId
        ? [
            ...new Set(
              (meeting.task_ids ?? []).flatMap((taskId) => {
                const task = project.tasks.find((item) => item.id === taskId)
                const assignee = task?.assignee_member_id
                return assignee && assignee !== context.member!.id
                  ? [assignee]
                  : []
              }),
            ),
          ]
        : []
      activity(context, 'MEETING_CLOSED', {
        ...(proposalId ? { change_proposal_id: proposalId } : {}),
      })
      return {
        data: {
          meeting,
          ...(proposalId ? { change_proposal_id: proposalId } : {}),
          notified_member_ids: notified,
        },
      }
    }
  }
  return undefined
}
