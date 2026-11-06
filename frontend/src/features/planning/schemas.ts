// BEEZ REST v0.2 및 공통 v0.3: snake_case 응답을 그대로 검증한다.
import { z } from 'zod'
import * as common from '@/api/contracts/common'

export const analysisQuerySchema = z.object({
  document_version_id: common.idSchema,
})
export type AnalysisQuery = z.infer<typeof analysisQuerySchema>

// S15P31A108-232: 분석이 끝난 뒤 따로 도는 보완 질문 생성 단계. 실패해도 분석 status 는 COMPLETED 다.
export const questionGenerationSchema = z.object({
  status: z.enum(['PENDING', 'RUNNING', 'COMPLETED', 'FAILED']),
  error_code: z.string().optional(),
  retryable: z.boolean().optional(),
})
export type QuestionGeneration = z.infer<typeof questionGenerationSchema>

export const analysisResponseSchema = z.object({
  document_version_id: common.idSchema,
  status: z.enum(['PENDING', 'RUNNING', 'COMPLETED', 'FAILED']),
  items: common.designItemVersionSchema.array(),
  questions: common.planningQuestionSchema.array(),
  error_code: z.string().optional(),
  source_status: z.enum(['PENDING', 'RUNNING', 'READY', 'FAILED']).optional(),
  index_status: z.enum(['PENDING', 'RUNNING', 'READY', 'FAILED']).optional(),
  attempt: z.number().int().positive().optional(),
  message: z.string().optional(),
  question_generation: questionGenerationSchema.optional(),
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
  question_generation: questionGenerationSchema.optional(),
})
export type RetryAnalysisResponse = z.infer<typeof retryAnalysisResponseSchema>

export const questionsQuerySchema = z.object({
  document_version_id: common.idSchema.optional(),
  cursor: z.string().optional(),
  limit: z.number().int().min(1).max(100).optional(),
})
export type QuestionsQuery = z.infer<typeof questionsQuerySchema>

export const questionsResponseSchema = common
  .pageSchema(common.planningQuestionSchema)
  .safeExtend({
    total_count: z.number().int().optional(),
  })
export type QuestionsResponse = z.infer<typeof questionsResponseSchema>

// 답변(`answer`)이나 구조 답변(`answer_payload`) 하나, 또는 답변 없이 닫기(`status`)만 보낸다. 닫을 때는 답변을 함께 보내지 않는다.
export const answerQuestionInputSchema = z
  .object({
    answer: z.string().optional(),
    answer_payload: common.planningAnswerPayloadSchema.optional(),
    status: z.literal('DISMISSED').optional(),
  })
  .superRefine((input, context) => {
    const answered =
      input.answer !== undefined || input.answer_payload !== undefined
    if (input.status === 'DISMISSED' && answered)
      context.addIssue({
        code: 'custom',
        path: ['status'],
        message: '답변 없이 닫을 때는 답변을 함께 보내지 않습니다.',
      })
    if (input.status !== 'DISMISSED' && !answered)
      context.addIssue({
        code: 'custom',
        path: ['answer'],
        message: '답변이나 닫기 중 하나가 필요합니다.',
      })
  })
export type AnswerQuestionInput = z.infer<typeof answerQuestionInputSchema>

export const answerQuestionResponseSchema = common.planningQuestionSchema
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
  jobs: z
    .object({ id: common.idSchema, document_version_id: common.idSchema })
    .array()
    .optional(),
})
export type GenerateDraftsResponse = z.infer<
  typeof generateDraftsResponseSchema
>

// PLN-A05 후보 조회: 작업마다 후보가 들어 있다. 근거는 AI가 만든 값이라 모르는 키도 그대로 둔다.
export const draftCandidateSourceSchema = z.looseObject({
  document_id: common.idSchema.optional(),
  document_version_id: common.idSchema.optional(),
  version_no: z.number().int().optional(),
  location: z.string().optional(),
  quote: z.string().optional(),
})
export type DraftCandidateSource = z.infer<typeof draftCandidateSourceSchema>

export const draftCandidateSchema = z.object({
  id: common.idSchema,
  job_id: common.idSchema.optional(),
  candidate_key: z.string(),
  change: z.enum(['CREATE', 'UPDATE']),
  kind: z.string(),
  status: z.enum(['PENDING', 'ADOPTED', 'DISCARDED']),
  base_design_item_version_id: common.idSchema.optional(),
  base_status: z.string().optional(),
  before: common.structuredContentSchema.optional(),
  after: common.structuredContentSchema,
  changed_fields: z.string().array().default([]),
  conflicting_fields: z.string().array().default([]),
  sources: draftCandidateSourceSchema.array().default([]),
  answer_refs: z.string().array().default([]),
  support: z.enum(['DOCUMENT', 'ANSWER', 'DOCUMENT_AND_ANSWER', 'NONE']),
  rationale: z.string().optional(),
  adopted_design_item_version_id: common.idSchema.optional(),
})
export type DraftCandidate = z.infer<typeof draftCandidateSchema>

export const draftJobSchema = z.object({
  id: common.idSchema,
  document_version_id: common.idSchema,
  status: z.enum(['PENDING', 'RUNNING', 'COMPLETED', 'FAILED']),
  error_code: z.string().optional(),
  retryable: z.boolean().optional(),
  created_at: common.timeSchema.optional(),
  updated_at: common.timeSchema.optional(),
  unresolved: z.string().array().optional(),
  unanswered_question_keys: z.string().array().optional(),
  untouched_existing_ids: z.string().array().optional(),
  omitted_items: z.number().int().optional(),
  candidates: draftCandidateSchema.array().default([]),
})
export type DraftJob = z.infer<typeof draftJobSchema>

export const draftsQuerySchema = z.object({
  document_version_id: common.idSchema.optional(),
})
export type DraftsQuery = z.infer<typeof draftsQuerySchema>

export const draftsResponseSchema = z.object({
  jobs: draftJobSchema.array(),
})
export type DraftsResponse = z.infer<typeof draftsResponseSchema>

export const adoptCandidateInputSchema = z.object({
  confirm_no_evidence: z.boolean().optional(),
  resolved_fields: z.string().array().optional(),
})
export type AdoptCandidateInput = z.infer<typeof adoptCandidateInputSchema>

export const adoptCandidateResponseSchema = draftCandidateSchema.safeExtend({
  design_item_version_id: common.idSchema.optional(),
})
export type AdoptCandidateResponse = z.infer<
  typeof adoptCandidateResponseSchema
>

export const discardCandidateResponseSchema = draftCandidateSchema
export type DiscardCandidateResponse = z.infer<
  typeof discardCandidateResponseSchema
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
