export type Zone =
  | 'meeting'
  | 'lead'
  | 'dev'
  | 'review'
  | 'board'
  | 'lobby'
  | 'humandesk'
  | 'control'
  | 'hr'

export interface SceneProject {
  id: string
  name: string
  lot?: number | null
  design?: string
  status?: string
  floors?: number
  /** 도시 이름표의 안 읽은 알림 배지 */
  unread?: { text: string; label: string }
}

export interface SceneAgent {
  id: string
  name: string
  kind: 'human' | 'lead' | 'agent'
  color?: string
  /** 계정 avatar(human·robot·bee, 옛 bee-*). 있으면 사무실 모양을 이것으로 정한다(D34). */
  avatar?: string
  /** AI 에이전트면 주인 팀원 id. 주인 색 띠를 두른 하늘색 로봇으로 그린다(D35). */
  owner?: string
  /** 이름표 아래 말풍선(대기 이유 등, D37) */
  note?: string
  status?: string
  home_zone?: Zone
  zone?: Zone
}

export interface SceneTask {
  id: string
  /** 사람이 보는 작업 번호(TASK-003). 없으면 id를 쓴다. */
  key?: string
  title: string
  state: string
  assignee_id?: string | null
  description?: string
  owned_paths?: string[]
  waiting_reason?: string
}

/** Renderer data stays separate from the backend DTO and its authentication model. */
export interface Agent {
  id: string
  name: string
  role: string
  kind: 'human' | 'lead' | 'agent'
  avatar?: string
  builtin: boolean
  color: string
  home_zone: Zone
  zone: Zone
  status: string
  bubble: string | null
  bubble_at: string | null
  session_no: number
  session_started_at: string | null
  created_at: string
  context_messages: number
  team: string | null
  purpose: string | null
  model: string | null
  effort: string | null
  max_tokens: number | null
  token_budget_daily: number | null
  hired_by: string | null
  talking: boolean
  talking_with: string | null
  duty_status: 'on' | 'off'
  off_since: string | null
  back_at: string | null
  usage: {
    calls: number
    failed: number
    input_tokens: number
    output_tokens: number
    last24h: number
    simulated?: number
  }
}

export interface DecisionRef {
  key: string
  version: number
}
export interface Interior {
  themes: Record<string, string>
  decor: {
    id: string
    floor_key: string
    kind: string
    x: number
    y: number
    created_at: string
  }[]
  expanded_at: string | null
}
export type TaskStatus =
  | 'todo'
  | 'in_progress'
  | 'review_pending'
  | 'changes_requested'
  | 'unverifiable'
  | 'passed'
export interface Task {
  id: string
  title: string
  description: string
  scope: string
  assignee_id: string | null
  status: TaskStatus
  state_label?: string
  waiting_reason?: string
  decision_keys: string[]
  decision_refs: DecisionRef[]
  needs_recheck: boolean
  started_at: string | null
  updated_at: string
  req_refs: { doc_id: string; version: number; req_id: string }[]
  owned_paths?: string[]
  /** 사람이 보는 작업 번호(TASK-003). 없으면 id를 쓴다. */
  key?: string
  depends_on?: string[]
  acceptance?: string[]
}
export type Selection =
  | { type: 'zone'; id: Zone }
  | { type: 'agent'; id: string }
  | { type: 'task'; id: string }
  | { type: 'decision'; key: string }
  | { type: 'event'; id: number }
  /** 사무실 화면(메모 벽·관계 TV·대시보드)을 누르면 여는 업무 창. focus는 그 창에서 고를 항목(변경 제안 ID) */
  | {
      type: 'pane'
      id: 'changes' | 'relations' | 'dashboard' | 'tasks'
      focus?: string
    }
export interface LeadEvent {
  id: number
  ts: string
  actor_id: string | null
  actor_kind: string
  type: string
  summary: string
  task_id: string | null
  decision_key: string | null
  decision_version: number | null
  refs: { agent_id?: string }
  data: {
    phase?: string
    note?: string
    task_title?: string
    activity?: { action?: string; detail?: string; files?: string[] }
  }
}
export interface Snapshot {
  project: {
    id: string
    name: string
    description?: string
    requirements?: { id: string; text: string }[]
  }
  agents: Agent[]
  tasks: Task[]
  events: LeadEvent[]
}
