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
}

export interface SceneAgent {
  id: string
  name: string
  kind: 'human' | 'lead' | 'agent'
  color?: string
  status?: string
  home_zone?: Zone
  zone?: Zone
}

export interface SceneTask {
  id: string
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
  depends_on?: string[]
  acceptance?: string[]
}
export type Selection =
  | { type: 'zone'; id: Zone }
  | { type: 'agent'; id: string }
  | { type: 'task'; id: string }
  | { type: 'decision'; key: string }
  | { type: 'event'; id: number }
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
export interface WorldProject {
  id: string
  name: string
  lot?: number | null
  design: string
  floors: number
  status: string
  has_badge: boolean
}
export interface WorldSummary {
  lots: { index: number; project_id: string | null }[]
  projects: WorldProject[]
}
