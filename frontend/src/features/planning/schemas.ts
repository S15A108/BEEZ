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
  // DOCUMENT_ONLY: 질문·답변 없이 문서만으로 후보를 만든다(실패한 부분만 다시 만들기 포함). 없으면 답변을 반영한다.
  mode: z.enum(['WITH_ANSWERS', 'DOCUMENT_ONLY']).optional(),
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

// 후보의 changed_fields: 본문의 최상위 필드 하나가 어떻게 달라졌는지(AI 계약 134 FieldChange). ADDED 는 이전에 없던 필드, REMOVED 는 이후에 없는 필드다.
export const draftFieldChangeSchema = z.object({
  field: z.string(),
  change: z.enum(['ADDED', 'CHANGED', 'REMOVED']),
  before: z.unknown().optional(),
  after: z.unknown().optional(),
})
export type DraftFieldChange = z.infer<typeof draftFieldChangeSchema>

// 이미 있는 항목·이미 채택한 후보·이미 폐기한 후보·다른 문서에서 아직 결정하지 않은 후보와 같은 후보는 겹침으로 구분해 보여 준다.
export const draftCandidateOverlapSchema = z.object({
  kind: z.enum([
    'EXISTING_ITEM',
    'ADOPTED_CANDIDATE',
    'DISCARDED_CANDIDATE',
    'PENDING_CANDIDATE',
  ]),
  ref_id: common.idSchema,
})
export type DraftCandidateOverlap = z.infer<typeof draftCandidateOverlapSchema>

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
  changed_fields: draftFieldChangeSchema.array().default([]),
  conflicting_fields: z.string().array().default([]),
  sources: draftCandidateSourceSchema.array().default([]),
  answer_refs: z.string().array().default([]),
  support: z.enum(['DOCUMENT', 'ANSWER', 'DOCUMENT_AND_ANSWER', 'NONE']),
  rationale: z.string().optional(),
  overlap: draftCandidateOverlapSchema.optional(),
  adopted_design_item_version_id: common.idSchema.optional(),
})
export type DraftCandidate = z.infer<typeof draftCandidateSchema>

export const draftJobSchema = z.object({
  id: common.idSchema,
  document_version_id: common.idSchema,
  status: z.enum(['PENDING', 'RUNNING', 'COMPLETED', 'FAILED']),
  error_code: z.string().optional(),
  retryable: z.boolean().optional(),
  // 문서에서 뽑은 후보(DOCUMENT_ONLY)와 질문·답변으로 보완한 후보(WITH_ANSWERS)를 구분한다. 예전 서버는 mode 를 주지 않는다.
  mode: z.enum(['WITH_ANSWERS', 'DOCUMENT_ONLY']).default('WITH_ANSWERS'),
  trigger: z.enum(['AUTO', 'MANUAL']).optional(),
  attempt_no: z.number().int().optional(),
  max_attempts: z.number().int().optional(),
  // 긴 문서는 부분으로 나눠 만든다. 실패한 부분만 다시 만든다.
  parts: z
    .object({
      total: z.number().int(),
      completed: z.number().int(),
      failed: z.number().int(),
      failed_error_codes: z.string().array().default([]),
    })
    .optional(),
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
  // 이미 같은 설계 항목이 있어도(지문이 같아도 내용이 다를 수 있다) 알고 채택한다는 확인
  confirm_duplicate: z.boolean().optional(),
  resolved_fields: z.string().array().optional(),
})
export type AdoptCandidateInput = z.infer<typeof adoptCandidateInputSchema>

export const adoptCandidateResponseSchema = draftCandidateSchema.safeExtend({
  design_item_version_id: common.idSchema.optional(),
})
export type AdoptCandidateResponse = z.infer<
  typeof adoptCandidateResponseSchema
>

// 후보 여러 개를 한 번에 채택·폐기. 건별 결과가 오며 일부가 실패해도 나머지는 처리된다.
export const batchCandidatesInputSchema = z.object({
  items: z
    .object({
      candidate_id: common.idSchema,
      confirm_no_evidence: z.boolean().optional(),
      confirm_duplicate: z.boolean().optional(),
      resolved_fields: z.string().array().optional(),
    })
    .array()
    .min(1)
    .max(50),
})
export type BatchCandidatesInput = z.infer<typeof batchCandidatesInputSchema>

export const batchCandidateResultSchema = z.object({
  candidate_id: common.idSchema,
  result: z.enum(['ADOPTED', 'DISCARDED', 'FAILED']),
  design_item_version_id: common.idSchema.optional(),
  error_code: z.string().optional(),
  message: z.string().optional(),
})
export type BatchCandidateResult = z.infer<typeof batchCandidateResultSchema>

export const batchCandidatesResponseSchema = z.object({
  results: batchCandidateResultSchema.array(),
  succeeded: z.number().int(),
  failed: z.number().int(),
})
export type BatchCandidatesResponse = z.infer<
  typeof batchCandidatesResponseSchema
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

// PLN-A11: 시작된 프로젝트에서 팀장이 기준 설계 항목의 출처 위치를 확정한다(S15P31A108-422).
// 줄 범위(start_line·end_line)와 인용 없음(no_quote) 중 하나만 보낸다.
export const confirmItemSourceInputSchema = z.union([
  z.object({
    project_id: common.idSchema,
    expected_version_id: common.idSchema,
    start_line: z.number().int().min(1),
    end_line: z.number().int().min(1),
  }),
  z.object({
    project_id: common.idSchema,
    expected_version_id: common.idSchema,
    no_quote: z.literal(true),
  }),
])
export type ConfirmItemSourceInput = z.infer<
  typeof confirmItemSourceInputSchema
>
export const confirmItemSourceResponseSchema = updateItemResponseSchema

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
