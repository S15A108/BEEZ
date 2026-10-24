import type { Agent } from './types'

export const BIBI_NAME = '비비'
export const LEGACY_BIBI_ID = '__beez_reception__'
export const agentDisplayName = (agent: Pick<Agent, 'kind' | 'name'>) =>
  agent.kind === 'lead' ? BIBI_NAME : agent.name
export const bibiOf = (agents: Agent[]) =>
  agents.find((agent) => agent.kind === 'lead')

/** Old guide links join the existing team lead's conversation and history. */
export const conversationAgentId = (id: string, agents: Agent[]) =>
  id === LEGACY_BIBI_ID ? (bibiOf(agents)?.id ?? id) : id
