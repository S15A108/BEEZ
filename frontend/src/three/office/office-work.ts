import type {
  Agent,
  LeadEvent,
  Selection,
  Snapshot,
  Task,
  TaskStatus,
  Zone,
} from '../types'
import type { Floor, Furniture, Seat } from './floors'
import { isOfficeWorkingStatus } from './office-life'
import { officeWorkZone } from './office-layout'

export type WorkColumn = 'todo' | 'working' | 'review' | 'done'
export const WORK_COLUMNS: readonly WorkColumn[] = [
  'todo',
  'working',
  'review',
  'done',
]
export const WORK_COLUMN_LABELS: Record<WorkColumn, string> = {
  todo: '할 일',
  working: '작업 중',
  review: '대기·검토',
  done: '완료·종료',
}
export const WORK_COLORS: Record<WorkColumn | 'blocked' | 'idle', string> = {
  todo: '#fff1c4',
  working: '#ffd24a',
  review: '#d6c7e5',
  done: '#eef7ef',
  blocked: '#efc1af',
  idle: '#f5f3ec',
}
export interface WorkNote {
  id: string
  title: string
  owner: string
  label: string
  column: WorkColumn
  blocked: boolean
  target: Selection
  files: string[]
}
export interface WorkMonitor {
  owner: string
  status: string
  tone: WorkColumn | 'blocked' | 'idle'
  title: string
  taskLabel: string
  action: string
  files: string[]
  detail: string
  at: string | null
  target: Selection
  agentId?: string
}
export interface OfficeWorkState {
  project: string
  monitors: Record<string, WorkMonitor>
  notes: WorkNote[]
  counts: Record<WorkColumn, number>
  summary: WorkMonitor
}
export interface OfficeWorkHit {
  title: string
  detail: string
  target: Selection
}

export const officeFurnitureKey = (f: Pick<Furniture, 'kind' | 'x' | 'y'>) =>
  `${f.kind}/${f.x}/${f.y}`
const TASK_LABELS: Record<TaskStatus, string> = {
  todo: '할 일',
  in_progress: '진행 중',
  changes_requested: '수정 요청',
  review_pending: '검토 대기',
  unverifiable: '확인 필요',
  passed: '검토 통과',
}
const columnOf = (status: TaskStatus): WorkColumn =>
  status === 'passed'
    ? 'done'
    : status === 'review_pending' || status === 'unverifiable'
      ? 'review'
      : status === 'todo'
        ? 'todo'
        : 'working'
const clean = (value: unknown, max = 180) =>
  typeof value === 'string'
    ? value
        .replace(/\p{Cc}/gu, ' ')
        .trim()
        .slice(0, max)
    : ''

/** Keep a person's home seat reserved through meetings, breaks and off-duty snapshots. */
export function officeHomeSeats(
  floor: Floor,
  agents: readonly Agent[],
): Map<string, Seat> {
  const result = new Map<string, Seat>(),
    occupied: Partial<Record<Zone, number>> = {}
  for (const agent of agents) {
    const zone = floor.footprint ? officeWorkZone(agent) : agent.home_zone
    const seats = floor.seats[zone]
    if (!seats?.length) continue
    const index = occupied[zone] ?? 0
    occupied[zone] = index + 1
    const seat = seats[index]
    if (seat) result.set(agent.id, seat)
  }
  return result
}

function latestWork(
  events: readonly LeadEvent[],
  agentId: string,
  taskId?: string,
): LeadEvent | undefined {
  return events
    .filter(
      (event) =>
        (event.actor_id ?? event.refs?.agent_id) === agentId &&
        (!taskId || !event.task_id || event.task_id === taskId) &&
        (event.type === 'agent.work' ||
          event.type.startsWith('agent.task_') ||
          event.type === 'agent.reported' ||
          /^agent\.coding_(done|failed|started)$/.test(event.type)),
    )
    .sort((a, b) => b.ts.localeCompare(a.ts) || b.id - a.id)[0]
}

function eventAction(event?: LeadEvent): string {
  const action = clean(event?.data?.activity?.action, 80)
  if (!action)
    return (
      clean(event?.data?.note, 100) ||
      (/^agent\.coding_/.test(event?.type ?? '') ? clean(event?.summary) : '')
    )
  return `${action}${event?.data?.phase === 'failed' ? ' 실패' : event?.data?.phase === 'completed' ? ' 완료' : ''}`
}
function eventFiles(event?: LeadEvent): string[] {
  return Array.isArray(event?.data?.activity?.files)
    ? event.data.activity.files
        .filter((file: unknown): file is string => typeof file === 'string')
        .map((file: string) => clean(file))
        .slice(0, 3)
    : []
}

export function buildOfficeWork(
  floor: Floor,
  snap: Pick<Snapshot, 'project' | 'agents' | 'tasks' | 'events'>,
): OfficeWorkState {
  const notes: WorkNote[] = [...snap.tasks]
    .sort(
      (a, b) =>
        b.updated_at.localeCompare(a.updated_at) || a.id.localeCompare(b.id),
    )
    .map((task) => ({
      id: task.id,
      title: task.title,
      owner:
        snap.agents.find((agent) => agent.id === task.assignee_id)?.name ??
        '담당자 미배정',
      label: `${task.id} · ${task.state_label ?? TASK_LABELS[task.status]}`,
      column: columnOf(task.status),
      blocked:
        task.status === 'changes_requested' || task.status === 'unverifiable',
      target: { type: 'task', id: task.id },
      files: task.owned_paths?.slice(0, 3) ?? [],
    }))
  const seats = officeHomeSeats(floor, snap.agents),
    monitors: Record<string, WorkMonitor> = {}
  const rank: Record<TaskStatus, number> = {
    in_progress: 0,
    changes_requested: 1,
    review_pending: 2,
    unverifiable: 3,
    todo: 4,
    passed: 5,
  }
  for (const agent of snap.agents) {
    const assigned = snap.tasks
      .filter((task) => task.assignee_id === agent.id)
      .sort(
        (a, b) =>
          rank[a.status] - rank[b.status] ||
          b.updated_at.localeCompare(a.updated_at),
      )
    let task: Task | undefined = assigned[0]
    const newest = latestWork(snap.events, agent.id)
    if (
      task?.status === 'passed' &&
      newest &&
      !newest.task_id &&
      newest.ts > task.updated_at
    )
      task = undefined
    const work = task ? latestWork(snap.events, agent.id, task.id) : newest,
      action = eventAction(work),
      files = eventFiles(work)
    const off = agent.duty_status === 'off',
      working = isOfficeWorkingStatus(agent.status)
    const monitor: WorkMonitor = {
      agentId: agent.id,
      owner: agent.name,
      status: off
        ? '일시정지'
        : agent.talking || agent.status === 'talking'
          ? '대화 중'
          : working
            ? '작업 중'
            : agent.status === 'blocked'
              ? '확인 필요'
              : agent.status === 'disconnected'
                ? '연결 끊김'
                : '자리 비움',
      tone: off
        ? 'idle'
        : agent.status === 'blocked'
          ? 'blocked'
          : working
            ? 'working'
            : task
              ? columnOf(task.status)
              : 'idle',
      title: task?.title ?? (action || '배정된 작업 없음'),
      taskLabel: task
        ? `${task.id} · ${task.state_label ?? TASK_LABELS[task.status]}`
        : work
          ? '도구 실행 기록'
          : '배정된 작업 없음',
      action: action || '아직 구체적인 실행 기록이 없어요',
      files,
      detail:
        clean(work?.data?.activity?.detail) ||
        clean(work?.data?.note) ||
        task?.waiting_reason ||
        (task?.needs_recheck ? '설계 재확인이 필요해요' : ''),
      at: work?.ts ?? null,
      target: task
        ? { type: 'task', id: task.id }
        : { type: 'agent', id: agent.id },
    }
    // External agents can report real tool work before a formal task has been assigned.
    if (
      agent.kind !== 'human' &&
      !assigned.some((item) => item.status !== 'passed') &&
      work &&
      !work.task_id &&
      action
    ) {
      notes.push({
        id: `execution-${agent.id}`,
        title: action,
        owner: agent.name,
        label: '최근 도구 실행',
        column:
          work.data?.phase === 'failed'
            ? 'review'
            : working
              ? 'working'
              : 'done',
        blocked: work.data?.phase === 'failed',
        target: { type: 'event', id: work.id },
        files,
      })
    }
    const seat = seats.get(agent.id)
    if (!seat) continue
    const desk = floor.furniture
      .filter(
        (item) =>
          ['desk', 'execdesk', 'console', 'counter', 'requestdesk'].includes(
            item.kind,
          ) &&
          seat.x >= item.x - 0.5 &&
          seat.x <= item.x + item.w &&
          seat.y >= item.y - 1 &&
          seat.y <= item.y + item.h + 1.5,
      )
      .sort(
        (a, b) =>
          Math.hypot(
            seat.x + 0.5 - a.x - a.w / 2,
            seat.y + 0.5 - a.y - a.h / 2,
          ) -
          Math.hypot(
            seat.x + 0.5 - b.x - b.w / 2,
            seat.y + 0.5 - b.y - b.h / 2,
          ),
      )[0]
    if (!desk) continue
    const key = officeFurnitureKey(desk),
      current = monitors[key]
    // Shared consoles show the active colleague first and name every occupant.
    if (current) {
      const primary = working && current.tone !== 'working' ? monitor : current
      monitors[key] = { ...primary, owner: `${current.owner} · ${agent.name}` }
    } else monitors[key] = monitor
  }
  const counts = Object.fromEntries(
    WORK_COLUMNS.map((column) => [
      column,
      notes.filter((note) => note.column === column).length,
    ]),
  ) as Record<WorkColumn, number>
  const summary: WorkMonitor = {
    owner: snap.project.name,
    status: '프로젝트 작업 현황',
    tone: 'idle',
    title: snap.tasks.length
      ? `작업 ${snap.tasks.length}개`
      : '아직 등록된 작업이 없어요',
    taskLabel: '공용 모니터',
    action: WORK_COLUMNS.map(
      (column) => `${WORK_COLUMN_LABELS[column]} ${counts[column]}`,
    ).join(' · '),
    detail: '',
    files: [],
    at: null,
    target: { type: 'zone', id: 'dev' },
  }
  return { project: snap.project.name, monitors, notes, counts, summary }
}

/** Same ordering and cap for the texture and hit testing; remaining items stay counted. */
export function visibleBoardNotes(
  work: OfficeWorkState,
  column: WorkColumn,
): WorkNote[] {
  return work.notes.filter((note) => note.column === column).slice(0, 2)
}
