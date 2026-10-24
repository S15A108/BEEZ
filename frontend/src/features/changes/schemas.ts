// BEEZ REST v0.2 및 공통 v0.3: snake_case 응답을 그대로 검증한다.
import { z } from 'zod'
import * as common from '@/api/contracts/common'

export const createInputSchema = z.object({
  base_baseline_id: common.idSchema,
  title: z.string(),
  reason: z.string(),
  task_id: common.idSchema.optional(),
  validation_id: common.idSchema.optional(),
  items: common.changeItemSchema.array(),
  documents: common.documentChangeSchema.array(),
})
export type CreateInput = z.infer<typeof createInputSchema>

export const createResponseSchema = z.object({
  id: common.idSchema,
  project_id: common.idSchema,
  base_baseline_id: common.idSchema,
  title: z.string(),
  reason: z.string(),
  task_id: common.idSchema.optional(),
  validation_id: common.idSchema.optional(),
  items: common.changeItemSchema.array(),
  documents: common.documentChangeSchema.array(),
  status: z.enum([
    'PROPOSED',
    'IN_REVIEW',
    'APPROVED',
    'PUBLISHED',
    'REJECTED',
  ]),
  proposer_account_id: common.idSchema,
  actor_type: z.enum(['HUMAN', 'AGENT']),
  current_baseline_id: common.idSchema,
  requires_refresh: z.boolean(),
  published_baseline_id: common.idSchema.optional(),
  created_at: common.timeSchema,
  updated_at: common.timeSchema,
  rejection_reason: z.string().optional(),
})
export type CreateResponse = z.infer<typeof createResponseSchema>

export const listQuerySchema = z.object({
  status: z
    .enum(['PROPOSED', 'IN_REVIEW', 'APPROVED', 'PUBLISHED', 'REJECTED'])
    .optional(),
  cursor: z.string().optional(),
  limit: z.number().int().min(1).max(100).optional(),
})
export type ListQuery = z.infer<typeof listQuerySchema>

export const listResponseSchema = common.pageSchema(common.changeProposalSchema)
export type ListResponse = z.infer<typeof listResponseSchema>

export const getResponseSchema = z.object({
  proposal: common.changeProposalSchema,
  comparisons: common.comparisonSchema.array(),
})
export type GetResponse = z.infer<typeof getResponseSchema>

export const updateInputSchema = z.object({
  base_baseline_id: common.idSchema,
  title: z.string(),
  reason: z.string(),
  task_id: common.idSchema.optional(),
  validation_id: common.idSchema.optional(),
  items: common.changeItemSchema.array(),
  documents: common.documentChangeSchema.array(),
  expected_updated_at: common.timeSchema,
})
export type UpdateInput = z.infer<typeof updateInputSchema>

export const updateResponseSchema = z.object({
  id: common.idSchema,
  project_id: common.idSchema,
  base_baseline_id: common.idSchema,
  title: z.string(),
  reason: z.string(),
  task_id: common.idSchema.optional(),
  validation_id: common.idSchema.optional(),
  items: common.changeItemSchema.array(),
  documents: common.documentChangeSchema.array(),
  status: z.enum([
    'PROPOSED',
    'IN_REVIEW',
    'APPROVED',
    'PUBLISHED',
    'REJECTED',
  ]),
  proposer_account_id: common.idSchema,
  actor_type: z.enum(['HUMAN', 'AGENT']),
  current_baseline_id: common.idSchema,
  requires_refresh: z.boolean(),
  published_baseline_id: common.idSchema.optional(),
  created_at: common.timeSchema,
  updated_at: common.timeSchema,
  rejection_reason: z.string().optional(),
})
export type UpdateResponse = z.infer<typeof updateResponseSchema>

export const impactResponseSchema = z.object({
  explicit: common.explicitImpactSchema.array(),
  candidates: common.impactCandidateSchema.array(),
  analysis_status: z.enum(['PENDING', 'RUNNING', 'COMPLETED', 'FAILED']),
  generation: common.generationSchema.optional(),
  error_code: z.string().optional(),
})
export type ImpactResponse = z.infer<typeof impactResponseSchema>

export const retryImpactResponseSchema = z.object({
  explicit: common.explicitImpactSchema.array(),
  candidates: common.impactCandidateSchema.array(),
  analysis_status: z.enum(['PENDING', 'RUNNING', 'COMPLETED', 'FAILED']),
  generation: common.generationSchema.optional(),
  error_code: z.string().optional(),
})
export type RetryImpactResponse = z.infer<typeof retryImpactResponseSchema>

export const generateDraftInputSchema = z.object({
  document_id: common.idSchema,
  base_document_version_id: common.idSchema,
  instruction: z.string(),
  task_id: common.idSchema.optional(),
})
export type GenerateDraftInput = z.infer<typeof generateDraftInputSchema>

export const generateDraftResponseSchema = z.object({
  document_id: common.idSchema,
  base_document_version_id: common.idSchema,
  after_content: z.string(),
  generation: common.generationSchema,
})
export type GenerateDraftResponse = z.infer<typeof generateDraftResponseSchema>

export const reviewInputSchema = z.object({
  expected_updated_at: common.timeSchema,
})
export type ReviewInput = z.infer<typeof reviewInputSchema>

export const reviewResponseSchema = z.object({
  id: common.idSchema,
  project_id: common.idSchema,
  base_baseline_id: common.idSchema,
  title: z.string(),
  reason: z.string(),
  task_id: common.idSchema.optional(),
  validation_id: common.idSchema.optional(),
  items: common.changeItemSchema.array(),
  documents: common.documentChangeSchema.array(),
  status: z.enum([
    'PROPOSED',
    'IN_REVIEW',
    'APPROVED',
    'PUBLISHED',
    'REJECTED',
  ]),
  proposer_account_id: common.idSchema,
  actor_type: z.enum(['HUMAN', 'AGENT']),
  current_baseline_id: common.idSchema,
  requires_refresh: z.boolean(),
  published_baseline_id: common.idSchema.optional(),
  created_at: common.timeSchema,
  updated_at: common.timeSchema,
  rejection_reason: z.string().optional(),
})
export type ReviewResponse = z.infer<typeof reviewResponseSchema>

export const approveInputSchema = z.object({
  expected_base_baseline_id: common.idSchema,
  expected_updated_at: common.timeSchema,
})
export type ApproveInput = z.infer<typeof approveInputSchema>

export const approveResponseSchema = z.object({
  proposal: common.changeProposalSchema,
  baseline: common.baselineSchema,
})
export type ApproveResponse = z.infer<typeof approveResponseSchema>

export const rejectInputSchema = z.object({
  reason: z.string(),
  expected_updated_at: common.timeSchema,
})
export type RejectInput = z.infer<typeof rejectInputSchema>

export const rejectResponseSchema = z.object({
  id: common.idSchema,
  project_id: common.idSchema,
  base_baseline_id: common.idSchema,
  title: z.string(),
  reason: z.string(),
  task_id: common.idSchema.optional(),
  validation_id: common.idSchema.optional(),
  items: common.changeItemSchema.array(),
  documents: common.documentChangeSchema.array(),
  status: z.enum([
    'PROPOSED',
    'IN_REVIEW',
    'APPROVED',
    'PUBLISHED',
    'REJECTED',
  ]),
  proposer_account_id: common.idSchema,
  actor_type: z.enum(['HUMAN', 'AGENT']),
  current_baseline_id: common.idSchema,
  requires_refresh: z.boolean(),
  published_baseline_id: common.idSchema.optional(),
  created_at: common.timeSchema,
  updated_at: common.timeSchema,
  rejection_reason: z.string().optional(),
})
export type RejectResponse = z.infer<typeof rejectResponseSchema>

export const approvalResponseSchema = z.object({
  id: common.idSchema,
  proposal_id: common.idSchema,
  approved_by: common.idSchema,
  approved_at: common.timeSchema,
  baseline_id: common.idSchema,
})
export type ApprovalResponse = z.infer<typeof approvalResponseSchema>

export const commentInputSchema = z.object({
  body: z.string(),
  kind: z.enum(['COMMENT', 'REVIEW_REQUEST']).optional(),
  mentioned_member_ids: common.idSchema.array().optional(),
})
export type CommentInput = z.infer<typeof commentInputSchema>

export const commentResponseSchema = z.object({
  id: common.idSchema,
  proposal_id: common.idSchema,
  author_account_id: common.idSchema,
  kind: z.enum(['COMMENT', 'REVIEW_REQUEST']),
  body: z.string(),
  created_at: common.timeSchema,
})
export type CommentResponse = z.infer<typeof commentResponseSchema>

export const commentsQuerySchema = z.object({
  cursor: z.string().optional(),
  limit: z.number().int().min(1).max(100).optional(),
})
export type CommentsQuery = z.infer<typeof commentsQuerySchema>

export const commentsResponseSchema = common.pageSchema(
  common.changeCommentSchema,
)
export type CommentsResponse = z.infer<typeof commentsResponseSchema>
