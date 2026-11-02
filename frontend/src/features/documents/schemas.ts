// BEEZ REST v0.2 및 공통 v0.3: snake_case 응답을 그대로 검증한다.
import { z } from 'zod'
import * as common from '@/api/contracts/common'

export const uploadInputSchema = z.object({
  file: z.instanceof(Blob),
  kind: z.enum(['PLAN', 'ERD', 'REQUIREMENTS', 'API_SPEC']),
  title: z.string(),
  domain: z.string().optional(),
})
export type UploadInput = z.infer<typeof uploadInputSchema>

export const uploadResponseSchema = z.object({
  document: common.documentSchema,
  analysis_status: z.literal('PENDING'),
})
export type UploadResponse = z.infer<typeof uploadResponseSchema>

export const listQuerySchema = z.object({
  kind: z.enum(['PLAN', 'ERD', 'REQUIREMENTS', 'API_SPEC']).optional(),
  domain: z.string().optional(),
  cursor: z.string().optional(),
  limit: z.number().int().min(1).max(100).optional(),
})
export type ListQuery = z.infer<typeof listQuerySchema>

export const listResponseSchema = common.pageSchema(common.documentSchema)
export type ListResponse = z.infer<typeof listResponseSchema>

export const getQuerySchema = z.object({
  version_no: z.number().int().optional(),
})
export type GetQuery = z.infer<typeof getQuerySchema>

export const getResponseSchema = z.object({
  document: common.documentSchema,
  version: common.documentVersionSchema,
})
export type GetResponse = z.infer<typeof getResponseSchema>

export const versionsQuerySchema = z.object({
  cursor: z.string().optional(),
  limit: z.number().int().min(1).max(100).optional(),
})
export type VersionsQuery = z.infer<typeof versionsQuerySchema>

export const versionsResponseSchema = common.pageSchema(
  common.documentVersionSchema,
)
export type VersionsResponse = z.infer<typeof versionsResponseSchema>

export const diffQuerySchema = z.object({
  from_version: z.number().int(),
  to_version: z.number().int(),
})
export type DiffQuery = z.infer<typeof diffQuerySchema>

export const diffResponseSchema = z.object({
  from_version: z.number().int(),
  to_version: z.number().int(),
  format: z.enum(['TEXT', 'FILE']),
  diff: z.string().optional(),
  before_hash: z.string(),
  after_hash: z.string(),
})
export type DiffResponse = z.infer<typeof diffResponseSchema>

export const updateInputSchema = z.object({
  domain: z.string(),
})
export type UpdateInput = z.infer<typeof updateInputSchema>

export const updateResponseSchema = z.object({
  id: common.idSchema,
  project_id: common.idSchema,
  kind: z.enum(['PLAN', 'ERD', 'REQUIREMENTS', 'API_SPEC']),
  title: z.string(),
  domain: z.string().optional(),
  approved_version: common.documentVersionSchema.optional(),
  latest_draft: common.documentVersionSchema.optional(),
})
export type UpdateResponse = z.infer<typeof updateResponseSchema>

export const baselineResponseSchema = z.object({
  id: common.idSchema,
  project_id: common.idSchema,
  version_no: z.number().int(),
  documents: common.documentRefSchema.array(),
  items: common.versionRefSchema.array(),
  approved_by: common.idSchema,
  created_at: common.timeSchema,
})
export type BaselineResponse = z.infer<typeof baselineResponseSchema>

export const baselinesQuerySchema = z.object({
  cursor: z.string().optional(),
  limit: z.number().int().min(1).max(100).optional(),
})
export type BaselinesQuery = z.infer<typeof baselinesQuerySchema>

export const baselinesResponseSchema = common.pageSchema(
  common.baselineSummarySchema,
)
export type BaselinesResponse = z.infer<typeof baselinesResponseSchema>

export const baselineVersionResponseSchema = z.object({
  id: common.idSchema,
  project_id: common.idSchema,
  version_no: z.number().int(),
  documents: common.documentRefSchema.array(),
  items: common.versionRefSchema.array(),
  approved_by: common.idSchema,
  created_at: common.timeSchema,
})
export type BaselineVersionResponse = z.infer<
  typeof baselineVersionResponseSchema
>

export const searchQuerySchema = z.object({
  q: z.string(),
  mode: z.enum(['KEYWORD', 'SEMANTIC']),
  domain: z.string().optional(),
  kind: z.string().optional(),
  cursor: z.string().optional(),
  limit: z.number().int().min(1).max(100).optional(),
})
export type SearchQuery = z.infer<typeof searchQuerySchema>

export const searchResponseSchema = common
  .pageSchema(common.searchHitSchema)
  .safeExtend({
    elapsed_ms: z.number().int(),
  })
export type SearchResponse = z.infer<typeof searchResponseSchema>

export const uploadDraftInputSchema = z.object({
  file: z.instanceof(Blob),
  base_document_version_id: common.idSchema,
})
export type UploadDraftInput = z.infer<typeof uploadDraftInputSchema>

export const uploadDraftResponseSchema = z.object({
  id: common.idSchema,
  document_id: common.idSchema,
  version_no: z.number().int(),
  status: z.enum(['DRAFT', 'APPROVED', 'SUPERSEDED', 'REJECTED']),
  file_name: z.string(),
  media_type: z.string(),
  content_hash: z.string(),
  content: z.string().optional(),
  created_at: common.timeSchema,
})
export type UploadDraftResponse = z.infer<typeof uploadDraftResponseSchema>

// 문서별 검토·승인 (DOC-A13~A21, dev 89b2c60)
export const workingCopyResponseSchema = common.documentWorkingCopySchema
export type WorkingCopyResponse = z.infer<typeof workingCopyResponseSchema>

export const updateWorkingCopyInputSchema = z.object({
  expected_revision: z.number().int().positive(),
  content: z.string().min(1),
  file_name: z.string(),
})
export type UpdateWorkingCopyInput = z.infer<
  typeof updateWorkingCopyInputSchema
>

export const reuploadWorkingCopyInputSchema = z.object({
  expected_revision: z.number().int().positive(),
  file: z.instanceof(Blob),
})
export type ReuploadWorkingCopyInput = z.infer<
  typeof reuploadWorkingCopyInputSchema
>

export const requestBeeReviewInputSchema = z.object({
  expected_revision: z.number().int().positive(),
})
export type RequestBeeReviewInput = z.infer<typeof requestBeeReviewInputSchema>

export const decideSuggestionInputSchema = z.object({
  expected_revision: z.number().int().positive(),
  decision: z.enum(['APPLY', 'SKIP']),
})
export type DecideSuggestionInput = z.infer<typeof decideSuggestionInputSchema>

export const submitReviewInputSchema = z.object({
  expected_revision: z.number().int().positive(),
  review_id: common.idSchema,
})
export type SubmitReviewInput = z.infer<typeof submitReviewInputSchema>

export const reviewSubmissionsQuerySchema = z.object({
  status: z.enum(['SUBMITTED', 'APPROVED', 'REJECTED']).optional(),
})
export type ReviewSubmissionsQuery = z.infer<
  typeof reviewSubmissionsQuerySchema
>

export const reviewSubmissionsResponseSchema =
  common.documentReviewSubmissionSchema.array()
export type ReviewSubmissionsResponse = z.infer<
  typeof reviewSubmissionsResponseSchema
>

export const decideSubmissionInputSchema = z
  .object({
    decision: z.enum(['APPROVE', 'REJECT']),
    rejection_reason: z.string().max(10000).optional(),
  })
  .refine(
    (input) => input.decision === 'APPROVE' || !!input.rejection_reason?.trim(),
    { path: ['rejection_reason'], message: '반려 사유를 입력해 주세요.' },
  )
export type DecideSubmissionInput = z.infer<typeof decideSubmissionInputSchema>
