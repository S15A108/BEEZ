// BEEZ REST v0.2 및 공통 v0.3: snake_case 응답을 그대로 검증한다.
import { z } from 'zod'
import * as common from '@/api/contracts/common'

export const documentsResponseSchema = z.object({
  baseline: common.baselineSummarySchema.optional(),
  by_kind: common.documentCountSchema.array(),
})
export type DocumentsResponse = z.infer<typeof documentsResponseSchema>

export const changesResponseSchema = z.object({
  by_status: common.stateCountSchema.array(),
  review_required_count: z.number().int(),
})
export type ChangesResponse = z.infer<typeof changesResponseSchema>

export const tasksResponseSchema = z.object({
  by_status: common.stateCountSchema.array(),
  by_waiting_reason: common.waitingCountSchema.array(),
})
export type TasksResponse = z.infer<typeof tasksResponseSchema>

export const validationsResponseSchema = z.object({
  by_status: common.stateCountSchema.array(),
})
export type ValidationsResponse = z.infer<typeof validationsResponseSchema>

export const usageQuerySchema = z.object({
  from: common.timeSchema,
  to: common.timeSchema,
  task_id: common.idSchema.optional(),
  validation_id: common.idSchema.optional(),
})
export type UsageQuery = z.infer<typeof usageQuerySchema>

export const usageResponseSchema = z.object({
  from: common.timeSchema,
  to: common.timeSchema,
  input_tokens: z.number().int(),
  output_tokens: z.number().int(),
  call_count: z.number().int(),
  estimated_cost_amount: common.costAmountSchema,
  currency: common.currencySchema,
  by_feature: common.usageBucketSchema.array(),
  by_task: common.usageBucketSchema.array(),
  by_validation: common.usageBucketSchema.array(),
})
export type UsageResponse = z.infer<typeof usageResponseSchema>

export const relationsQuerySchema = z.object({
  task_id: common.idSchema.optional(),
  design_item_id: common.idSchema.optional(),
})
export type RelationsQuery = z.infer<typeof relationsQuerySchema>

export const relationsResponseSchema = z.object({
  nodes: common.relationNodeSchema.array(),
  edges: common.relationEdgeSchema.array(),
})
export type RelationsResponse = z.infer<typeof relationsResponseSchema>

export const activitiesQuerySchema = z.object({
  type: z.string().optional(),
  cursor: z.string().optional(),
  limit: z.number().int().min(1).max(100).optional(),
})
export type ActivitiesQuery = z.infer<typeof activitiesQuerySchema>

export const activitiesResponseSchema = common.pageSchema(common.activitySchema)
export type ActivitiesResponse = z.infer<typeof activitiesResponseSchema>

export const agentWorkQuerySchema = z.object({
  member_id: common.idSchema.optional(),
  cursor: z.string().optional(),
  limit: z.number().int().min(1).max(100).optional(),
})
export type AgentWorkQuery = z.infer<typeof agentWorkQuerySchema>

export const agentWorkResponseSchema = common.pageSchema(common.agentWorkSchema)
export type AgentWorkResponse = z.infer<typeof agentWorkResponseSchema>
