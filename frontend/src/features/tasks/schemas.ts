// BEEZ REST v0.2 및 공통 v0.3: snake_case 응답을 그대로 검증한다.
import { z } from 'zod'
import * as common from '@/api/contracts/common'

export const createInputSchema = z.object({
  title: z.string(),
  goal: z.string(),
  included_scope: z.string().array(),
  excluded_scope: z.string().array(),
  approach: z.string(),
  completion_criteria: z.string().array(),
  dependency_task_ids: common.idSchema.array(),
  design_refs: common.designRefSchema.array(),
  unlinked: common.structuredContentSchema.array(),
  no_contract_change: z.boolean(),
  context_packet_id: common.idSchema.optional(),
})
export type CreateInput = z.infer<typeof createInputSchema>

export const createResponseSchema = common.taskDetailSchema
export type CreateResponse = z.infer<typeof createResponseSchema>

export const listQuerySchema = z.object({
  status: z
    .enum([
      'DRAFT',
      'READY',
      'IN_PROGRESS',
      'WAITING',
      'COMPLETED',
      'CANCELLED',
    ])
    .optional(),
  validation_status: z
    .enum([
      'NOT_RUN',
      'RUNNING',
      'PASS',
      'NEEDS_INPUT',
      'NEEDS_REVIEW',
      'STALE',
      'ERROR',
    ])
    .optional(),
  assignee_member_id: common.idSchema.optional(),
  cursor: z.string().optional(),
  limit: z.number().int().min(1).max(100).optional(),
})
export type ListQuery = z.infer<typeof listQuerySchema>

export const listResponseSchema = common.pageSchema(common.taskDetailSchema)
export type ListResponse = z.infer<typeof listResponseSchema>

export const getResponseSchema = common.taskDetailSchema
export type GetResponse = z.infer<typeof getResponseSchema>

export const savePlanInputSchema = z.object({
  title: z.string(),
  goal: z.string(),
  included_scope: z.string().array(),
  excluded_scope: z.string().array(),
  approach: z.string(),
  completion_criteria: z.string().array(),
  dependency_task_ids: common.idSchema.array(),
  design_refs: common.designRefSchema.array(),
  unlinked: common.structuredContentSchema.array(),
  no_contract_change: z.boolean(),
  context_packet_id: common.idSchema.optional(),
  expected_revision_no: z.number().int(),
  expected_lock_version: z.number().int(),
})
export type SavePlanInput = z.infer<typeof savePlanInputSchema>

export const savePlanResponseSchema = common.taskDetailSchema
export type SavePlanResponse = z.infer<typeof savePlanResponseSchema>

export const planRevisionsQuerySchema = z.object({
  cursor: z.string().optional(),
  limit: z.number().int().min(1).max(100).optional(),
})
export type PlanRevisionsQuery = z.infer<typeof planRevisionsQuerySchema>

export const planRevisionsResponseSchema = common.pageSchema(
  common.taskPlanRevisionSchema,
)
export type PlanRevisionsResponse = z.infer<typeof planRevisionsResponseSchema>

export const assignInputSchema = z.object({
  assignee_member_id: common.idSchema,
  expected_lock_version: z.number().int(),
})
export type AssignInput = z.infer<typeof assignInputSchema>

export const assignResponseSchema = common.taskDetailSchema
export type AssignResponse = z.infer<typeof assignResponseSchema>

export const stateInputSchema = common.taskStateInputSchema
export type StateInput = z.infer<typeof stateInputSchema>

export const stateResponseSchema = common.taskDetailSchema
export type StateResponse = z.infer<typeof stateResponseSchema>
