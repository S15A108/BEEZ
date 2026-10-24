// BEEZ REST v0.2 및 공통 v0.3: snake_case 응답을 그대로 검증한다.
import { z } from 'zod'
import * as common from '@/api/contracts/common'

export const packetResponseSchema = z.object({
  id: common.idSchema,
  project_id: common.idSchema,
  task_id: common.idSchema.optional(),
  baseline_id: common.idSchema,
  request: z.string(),
  design_items: common.designItemVersionSchema.array(),
  constraints: z.string().array(),
  unresolved: z.string().array(),
  related_tasks: common.relatedTaskSchema.array(),
  sources: common.sourceSchema.array(),
  created_at: common.timeSchema,
  content_hash: z.string(),
})
export type PacketResponse = z.infer<typeof packetResponseSchema>

export const listQuerySchema = z.object({
  q: z.string().optional(),
  task_id: common.idSchema.optional(),
  kind: z.enum(['DECISION', 'PROGRESS', 'ISSUE', 'HANDOVER']).optional(),
  cursor: z.string().optional(),
  limit: z.number().int().min(1).max(100).optional(),
})
export type ListQuery = z.infer<typeof listQuerySchema>

export const listResponseSchema = common.pageSchema(
  common.contextRecordSummarySchema,
)
export type ListResponse = z.infer<typeof listResponseSchema>

export const getResponseSchema = z.object({
  id: common.idSchema,
  root_id: common.idSchema,
  revision_no: z.number().int(),
  task_id: common.idSchema.optional(),
  kind: z.enum(['DECISION', 'PROGRESS', 'ISSUE', 'HANDOVER']),
  summary: z.string(),
  author_account_id: common.idSchema,
  visibility: z.enum(['SHARED', 'PRIVATE']),
  is_latest_revision: z.boolean(),
  references_current: z.boolean(),
  created_at: common.timeSchema,
  previous_id: common.idSchema.optional(),
  plan_revision_id: common.idSchema.optional(),
  source_packet_id: common.idSchema.optional(),
  detail: z.string(),
  design_refs: common.versionRefSchema.array(),
})
export type GetResponse = z.infer<typeof getResponseSchema>

export const createInputSchema = z.object({
  previous_id: common.idSchema.optional(),
  task_id: common.idSchema.optional(),
  plan_revision_id: common.idSchema.optional(),
  source_packet_id: common.idSchema.optional(),
  kind: z.enum(['DECISION', 'PROGRESS', 'ISSUE', 'HANDOVER']),
  visibility: z.enum(['SHARED', 'PRIVATE']),
  summary: z.string(),
  detail: z.string(),
  design_refs: common.versionRefSchema.array(),
})
export type CreateInput = z.infer<typeof createInputSchema>

export const createResponseSchema = z.object({
  id: common.idSchema,
  root_id: common.idSchema,
  revision_no: z.number().int(),
  task_id: common.idSchema.optional(),
  kind: z.enum(['DECISION', 'PROGRESS', 'ISSUE', 'HANDOVER']),
  summary: z.string(),
  author_account_id: common.idSchema,
  visibility: z.enum(['SHARED', 'PRIVATE']),
  is_latest_revision: z.boolean(),
  references_current: z.boolean(),
  created_at: common.timeSchema,
  previous_id: common.idSchema.optional(),
  plan_revision_id: common.idSchema.optional(),
  source_packet_id: common.idSchema.optional(),
  detail: z.string(),
  design_refs: common.versionRefSchema.array(),
})
export type CreateResponse = z.infer<typeof createResponseSchema>

export const suggestDesignReferencesInputSchema = z.object({
  request: z.string(),
  task_id: common.idSchema.optional(),
})
export type SuggestDesignReferencesInput = z.infer<
  typeof suggestDesignReferencesInputSchema
>

export const suggestDesignReferencesResponseSchema = z.object({
  candidates: common.designSuggestionSchema.array(),
})
export type SuggestDesignReferencesResponse = z.infer<
  typeof suggestDesignReferencesResponseSchema
>
