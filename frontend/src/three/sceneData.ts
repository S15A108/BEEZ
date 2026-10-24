import type {
  Agent,
  SceneAgent,
  SceneProject,
  SceneTask,
  Snapshot,
  TaskStatus,
  WorldSummary,
} from './types'
import { RECEPTIONIST } from './office/social'

export function citySceneData(projects: readonly SceneProject[]): WorldSummary {
  const visible = projects.filter(
    (project) => project.lot != null && project.lot >= 0 && project.lot < 8,
  )
  return {
    lots: Array.from({ length: 8 }, (_, index) => ({
      index,
      project_id: visible.find((project) => project.lot === index)?.id ?? null,
    })),
    projects: visible.map((project) => ({
      ...project,
      design: project.design ?? 'glass',
      status:
        project.status === 'constructing' || project.status === 'DRAFT'
          ? 'constructing'
          : 'ready',
      floors: project.floors ?? 5,
      has_badge: true,
    })),
  }
}

export function officeSceneAgents(agents: readonly SceneAgent[]): Agent[] {
  return agents.map((agent) => {
    const home =
      agent.home_zone ??
      (agent.kind === 'lead'
        ? 'lead'
        : agent.kind === 'human'
          ? 'humandesk'
          : 'dev')
    return {
      ...RECEPTIONIST,
      ...agent,
      color: agent.color ?? '#8ba6a2',
      home_zone: home,
      zone: agent.zone ?? home,
      status: agent.status?.toLowerCase() ?? 'idle',
    }
  })
}

const TASK_STATE: Record<string, TaskStatus> = {
  DRAFT: 'todo',
  READY: 'todo',
  IN_PROGRESS: 'in_progress',
  WAITING: 'unverifiable',
  COMPLETED: 'passed',
  CANCELLED: 'passed',
}
const TASK_LABEL: Record<string, string> = {
  DRAFT: '초안',
  READY: '시작 준비',
  IN_PROGRESS: '진행 중',
  WAITING: '대기',
  COMPLETED: '완료 보고',
  CANCELLED: '취소',
}
const WAITING_LABEL: Record<string, string> = {
  INPUT: '입력 대기',
  APPROVAL: '승인 대기',
  DEPENDENCY: '선행 작업 대기',
  REVALIDATION: '재검증 대기',
}

export function officeSceneSnapshot(
  project: SceneProject,
  agents: Agent[],
  tasks: readonly SceneTask[],
): Snapshot {
  return {
    project: { id: project.id, name: project.name },
    agents,
    events: [],
    tasks: tasks.map((task) => ({
      id: task.id,
      title: task.title,
      description: task.description ?? '',
      scope: '',
      assignee_id: task.assignee_id ?? null,
      status: TASK_STATE[task.state] ?? 'todo',
      state_label: TASK_LABEL[task.state] ?? '상태 미확인',
      waiting_reason: task.waiting_reason
        ? (WAITING_LABEL[task.waiting_reason] ?? task.waiting_reason)
        : undefined,
      decision_keys: [],
      decision_refs: [],
      needs_recheck: false,
      started_at: null,
      updated_at: '1970-01-01T00:00:00Z',
      req_refs: [],
      owned_paths: task.owned_paths ?? [],
    })),
  }
}
