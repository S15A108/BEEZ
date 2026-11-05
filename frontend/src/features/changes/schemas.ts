// BEEZ REST v0.2 및 공통 v0.3: snake_case 응답을 그대로 검증한다.
import { z } from 'zod'
import * as common from '@/api/contracts/common'

export const createInputSchema = common.changeProposalInputSchema
export type CreateInput = z.infer<typeof createInputSchema>

export const createResponseSchema = common.changeProposalSchema
export type CreateResponse = z.infer<typeof createResponseSchema>

// 검토한 제출을 가리키는 값(PR #46). 맞지 않으면 409 version_conflict
const expectedSubmission = {
  expected_revision: z.number().int().positive(),
  expected_snapshot_hash: common.snapshotHashSchema,
}

export const listQuerySchema = z.object({
  status: common.changeProposalStatusSchema.optional(),
  // IN_PROGRESS는 PROPOSED·IN_REVIEW·APPROVED
  group: z.enum(['IN_PROGRESS', 'PUBLISHED', 'REJECTED']).optional(),
  cursor: z.string().optional(),
  limit: z.number().int().min(1).max(100).optional(),
})
export type ListQuery = z.infer<typeof listQuerySchema>

export const listResponseSchema = common.pageSchema(
  common.changeProposalSummarySchema,
)
export type ListResponse = z.infer<typeof listResponseSchema>

// CHG-A03 발행 후 접수 상태. 승인 전 영향 분석(CHG-A05)과 별개다.
export const publishedAnalysisSchema = z.object({
  status: z.enum([
    'PENDING',
    'RUNNING',
    'COMPLETED',
    'FAILED',
    'NOT_EVALUATED',
    'UNKNOWN',
  ]),
  evaluation_status: z
    .enum(['COMPLETED', 'INSUFFICIENT_EVIDENCE', 'NOT_EVALUATED'])
    .optional(),
  error_code: z.string().optional(),
  attempt: z.number().int().nonnegative().optional(),
  requested_at: common.timeSchema.optional(),
  started_at: common.timeSchema.optional(),
  finished_at: common.timeSchema.optional(),
  next_attempt_at: common.timeSchema.optional(),
  lease_expired: z.boolean().optional(),
  current_baseline: z.boolean().optional(),
  guidance: z.enum([
    'WAIT',
    'RETRY_SCHEDULED',
    'RECOVERY_PENDING',
    'CONTACT_OPERATOR',
    'REVIEW_REASON',
    'REVIEW_EVIDENCE',
    'CHECK_RESULT',
    'NONE',
  ]),
})
export type PublishedAnalysis = z.infer<typeof publishedAnalysisSchema>

export const getResponseSchema = z.object({
  proposal: common.changeProposalSchema,
  comparisons: common.comparisonSchema.array(),
  // 부가 상태의 새 enum이나 잘못된 값은 상세 전체 오류 대신 확인 필요로 표시한다.
  published_analysis: publishedAnalysisSchema
    .catch({ status: 'UNKNOWN', guidance: 'CHECK_RESULT' })
    .optional(),
  // 직접 참조 후보는 화면에서 아직 쓰지 않아 모양만 받아 둔다(영향 탭은 CHG-A05).
  explicit_candidates: z.record(z.string(), z.unknown()).array().optional(),
  candidates_truncated: z.boolean().optional(),
  // 195 미구현(#218 요청): 항목별 −/+ 문장
  item_texts: z
    .object({
      design_item_id: common.idSchema,
      before_text: z.string().optional(),
      after_text: z.string().optional(),
    })
    .array()
    .optional(),
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
  ...expectedSubmission,
})
export type UpdateInput = z.infer<typeof updateInputSchema>

export const updateResponseSchema = common.changeProposalSchema
export type UpdateResponse = z.infer<typeof updateResponseSchema>

export const impactResponseSchema = common.changeImpactSchema
export type ImpactResponse = z.infer<typeof impactResponseSchema>

// 첫 분석도 이 요청(A12)으로 시작한다. 접수 응답은 새 RUNNING 실행이다.
export const retryImpactResponseSchema = common.changeImpactSchema
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

export const reviewInputSchema = z.object(expectedSubmission)
export type ReviewInput = z.infer<typeof reviewInputSchema>

export const reviewResponseSchema = common.changeProposalSchema
export type ReviewResponse = z.infer<typeof reviewResponseSchema>

export const approveInputSchema = z.object({
  expected_base_baseline_id: common.idSchema,
  ...expectedSubmission,
  // 영향 분석이 완료·최신이 아닐 때(CHG-A09 409 impact_not_confirmed) 팀장이 남기는 사유. 1~1000자.
  impact_override_reason: z.string().trim().min(1).max(1000).optional(),
})
export type ApproveInput = z.infer<typeof approveInputSchema>

export const approveResponseSchema = z.object({
  proposal: common.changeProposalSchema,
  baseline: common.baselineSchema,
})
export type ApproveResponse = z.infer<typeof approveResponseSchema>

export const rejectInputSchema = z.object({
  reason: z.string(),
  ...expectedSubmission,
})
export type RejectInput = z.infer<typeof rejectInputSchema>

export const rejectResponseSchema = common.changeProposalSchema
export type RejectResponse = z.infer<typeof rejectResponseSchema>

// CHG-A15 거두기(PR #46). 응답은 A01·A04·A08·A10과 같은 래퍼 없는 ChangeProposal이다.
export const withdrawInputSchema = z.object(expectedSubmission)
export type WithdrawInput = z.infer<typeof withdrawInputSchema>

export const withdrawResponseSchema = common.changeProposalSchema
export type WithdrawResponse = z.infer<typeof withdrawResponseSchema>

export const approvalResponseSchema = z.object({
  id: common.idSchema,
  proposal_id: common.idSchema,
  approved_by: common.idSchema,
  approved_at: common.timeSchema,
  baseline_id: common.idSchema,
  impact_override_reason: z.string().optional(),
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

// CHG-A14 승인 미리 보기. 저장 없이 승인하면 같이 바뀌는 것을 계산해 준다.
// CREATE 항목은 ID가 없고(전역 non_null이라 키가 빠진다), 유지 항목은 proposal_item_index가 없다.
export const approvalPreviewItemSchema = z.object({
  design_item_id: common.idSchema.optional(),
  title: z.string(),
  version_no: z.number().int().positive(),
  operation: z.enum(['CREATE', 'UPDATE', 'UNCHANGED']),
  proposal_item_index: z.number().int().nonnegative().optional(),
})
export type ApprovalPreviewItem = z.infer<typeof approvalPreviewItemSchema>

export const approvalPreviewAnalysisSchema = z.object({
  status: common.impactStatusSchema,
  is_current: z.boolean(),
  error_code: z.string().optional(),
  // 새 재분석이 도는 중이면 이전 결과가 그대로 보이고, 이때 승인은 409 analysis_running 이다.
  reanalysis_in_progress: z.boolean(),
})

export const approvalPreviewResponseSchema = z.object({
  next_baseline_version_no: z.number().int().positive(),
  baseline_items: approvalPreviewItemSchema.array(),
  validations_to_stale: z
    .object({
      validation_id: common.idSchema,
      task_id: common.idSchema,
      task_title: z.string(),
    })
    .array(),
  tasks_to_wait: z
    .object({
      task_id: common.idSchema,
      title: z.string(),
      assignee_member_id: common.idSchema.optional(),
    })
    .array(),
  unchanged: z.object({
    items: z.number().int().nonnegative(),
    documents: z.number().int().nonnegative(),
  }),
  unreviewed: z.object({
    // 영향 분석이 완료·최신일 때만 숫자다. 아니면 null(미평가를 0건으로 보이지 않기 위함)이며 키는 늘 온다.
    ai_candidates: z.number().int().nonnegative().nullable(),
    open_review_requests: z.number().int().nonnegative(),
    ai_analysis: approvalPreviewAnalysisSchema.nullable(),
    requires_override_reason: z.boolean(),
  }),
})
export type ApprovalPreviewResponse = z.infer<
  typeof approvalPreviewResponseSchema
>
