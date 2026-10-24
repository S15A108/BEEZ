// BEEZ REST v0.2 및 공통 v0.3: snake_case 응답을 그대로 검증한다.
import { z } from 'zod'
import * as common from '@/api/contracts/common'

export const analysisQuerySchema = z.object({
  document_version_id: common.idSchema,
})
export type AnalysisQuery = z.infer<typeof analysisQuerySchema>

export const analysisResponseSchema = z.object({
  document_version_id: common.idSchema,
  status: z.enum(['PENDING', 'RUNNING', 'COMPLETED', 'FAILED']),
  items: common.designItemVersionSchema.array(),
  questions: common.planningQuestionSchema.array(),
  error_code: z.string().optional(),
})
export type AnalysisResponse = z.infer<typeof analysisResponseSchema>

export const retryAnalysisInputSchema = z.object({
  document_version_id: common.idSchema,
})
export type RetryAnalysisInput = z.infer<typeof retryAnalysisInputSchema>

export const retryAnalysisResponseSchema = z.object({
  document_version_id: common.idSchema,
  status: z.enum(['PENDING', 'RUNNING', 'COMPLETED', 'FAILED']),
  items: common.designItemVersionSchema.array(),
  questions: common.planningQuestionSchema.array(),
  error_code: z.string().optional(),
})
export type RetryAnalysisResponse = z.infer<typeof retryAnalysisResponseSchema>

export const questionsQuerySchema = z.object({
  document_version_id: common.idSchema.optional(),
  cursor: z.string().optional(),
  limit: z.number().int().min(1).max(100).optional(),
})
export type QuestionsQuery = z.infer<typeof questionsQuerySchema>

export const questionsResponseSchema = common.pageSchema(
  common.planningQuestionSchema,
)
export type QuestionsResponse = z.infer<typeof questionsResponseSchema>

export const answerQuestionInputSchema = z.object({
  answer: z.string(),
})
export type AnswerQuestionInput = z.infer<typeof answerQuestionInputSchema>

export const answerQuestionResponseSchema = z.object({
  id: common.idSchema,
  question: z.string(),
  source: common.sourceSchema.optional(),
  answer: z.string().optional(),
  answered_by: common.idSchema.optional(),
  answered_at: common.timeSchema.optional(),
})
export type AnswerQuestionResponse = z.infer<
  typeof answerQuestionResponseSchema
>

export const generateDraftsInputSchema = z.object({
  document_version_ids: common.idSchema.array(),
})
export type GenerateDraftsInput = z.infer<typeof generateDraftsInputSchema>

export const generateDraftsResponseSchema = z.object({
  status: z.literal('PENDING'),
  document_version_ids: common.idSchema.array(),
})
export type GenerateDraftsResponse = z.infer<
  typeof generateDraftsResponseSchema
>

export const itemsQuerySchema = z.object({
  status: z.string().optional(),
  kind: z.string().optional(),
  document_version_id: common.idSchema.optional(),
  cursor: z.string().optional(),
  limit: z.number().int().min(1).max(100).optional(),
})
export type ItemsQuery = z.infer<typeof itemsQuerySchema>

export const itemsResponseSchema = common
  .pageSchema(common.designItemVersionSchema)
  .safeExtend({
    draft_snapshot_hash: z.string().optional(),
  })
export type ItemsResponse = z.infer<typeof itemsResponseSchema>

export const createItemInputSchema = z.object({
  kind: z.enum([
    'REQUIREMENT',
    'API_CONTRACT',
    'DATA_CONTRACT',
    'POLICY',
    'CONSTRAINT',
  ]),
  schema_version: z.number().int(),
  content: common.structuredContentSchema,
  source_document_version_id: common.idSchema.optional(),
  source_location: z.string().optional(),
})
export type CreateItemInput = z.infer<typeof createItemInputSchema>

export const createItemResponseSchema = z.object({
  id: common.idSchema,
  design_item_id: common.idSchema,
  key: z.string(),
  kind: z.enum([
    'REQUIREMENT',
    'API_CONTRACT',
    'DATA_CONTRACT',
    'POLICY',
    'CONSTRAINT',
  ]),
  version_no: z.number().int(),
  status: z.enum(['DRAFT', 'APPROVED', 'SUPERSEDED', 'REJECTED']),
  schema_version: z.number().int(),
  content: common.structuredContentSchema,
  source: common.sourceSchema.optional(),
  content_hash: z.string().optional(),
})
export type CreateItemResponse = z.infer<typeof createItemResponseSchema>

export const updateItemInputSchema = z.object({
  project_id: common.idSchema,
  expected_version_id: common.idSchema,
  content: common.structuredContentSchema,
  expected_content_hash: z.string(),
})
export type UpdateItemInput = z.infer<typeof updateItemInputSchema>

export const updateItemResponseSchema = z.object({
  id: common.idSchema,
  design_item_id: common.idSchema,
  key: z.string(),
  kind: z.enum([
    'REQUIREMENT',
    'API_CONTRACT',
    'DATA_CONTRACT',
    'POLICY',
    'CONSTRAINT',
  ]),
  version_no: z.number().int(),
  status: z.enum(['DRAFT', 'APPROVED', 'SUPERSEDED', 'REJECTED']),
  schema_version: z.number().int(),
  content: common.structuredContentSchema,
  source: common.sourceSchema.optional(),
  content_hash: z.string().optional(),
})
export type UpdateItemResponse = z.infer<typeof updateItemResponseSchema>

export const removeItemQuerySchema = z.object({
  project_id: common.idSchema,
  expected_version_id: common.idSchema,
  expected_content_hash: z.string(),
})
export type RemoveItemQuery = z.infer<typeof removeItemQuerySchema>

export const publishBaselineInputSchema = z.object({
  project_id: common.idSchema,
  document_version_ids: common.idSchema.array(),
  design_item_version_ids: common.idSchema.array(),
  draft_snapshot_hash: z.string(),
})
export type PublishBaselineInput = z.infer<typeof publishBaselineInputSchema>

export const publishBaselineResponseSchema = z.object({
  id: common.idSchema,
  project_id: common.idSchema,
  version_no: z.number().int(),
  documents: common.documentRefSchema.array(),
  items: common.versionRefSchema.array(),
  approved_by: common.idSchema,
  created_at: common.timeSchema,
})
export type PublishBaselineResponse = z.infer<
  typeof publishBaselineResponseSchema
>
