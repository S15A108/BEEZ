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

// CHG-A07 수정 요청 입력. 공백뿐이면 안 되고 2,000자 이하(명세). 폼 검증 규칙은 여기에만 둔다.
export const MAX_INSTRUCTION = 2000
export const draftRequestFormSchema = z.object({
  instruction: z
    .string()
    .trim()
    .min(1, '어떻게 고칠지 입력해 주세요.')
    .max(
      MAX_INSTRUCTION,
      `수정 요청은 ${MAX_INSTRUCTION.toLocaleString()}자까지 쓸 수 있어요.`,
    ),
})
export type DraftRequestFormValues = z.infer<typeof draftRequestFormSchema>

export const generateDraftResponseSchema = z.object({
  document_id: common.idSchema,
  base_document_version_id: common.idSchema,
  after_content: z.string(),
  generation: common.generationSchema,
})
export type GenerateDraftResponse = z.infer<typeof generateDraftResponseSchema>

export const scopedBlocksInputSchema = z.object({
  document_id: common.idSchema,
  base_document_version_id: common.idSchema,
})
export const scopedBlocksResponseSchema = scopedBlocksInputSchema.extend({
  text_hash: z.string(),
  parser_profile: z.string(),
  parser_version: z.string(),
  blocks: z.array(
    z.object({
      block_id: z.string(),
      block_type: z.string(),
      locator: z.object({
        type: z.literal('text_lines'),
        start_line: z.number().int().positive(),
        end_line: z.number().int().positive(),
      }),
      text: z.string(),
    }),
  ),
})
export type ScopedBlocksResponse = z.infer<typeof scopedBlocksResponseSchema>
export const scopedDraftInputSchema = scopedBlocksInputSchema.extend({
  selected_block_ids: z.array(z.string()).min(1).max(30),
  instruction: z.string().trim().min(1).max(4000),
  task_id: common.idSchema.optional(),
  agreements: z.array(z.string().trim().min(1).max(4000)).max(30).optional(),
  related_proposals: z
    .array(z.string().trim().min(1).max(4000))
    .max(30)
    .optional(),
})
export type ScopedDraftInput = z.infer<typeof scopedDraftInputSchema>
export const scopedDraftResponseSchema = scopedBlocksInputSchema.extend({
  after_content: z.string(),
  changes: z.array(
    z.object({
      block_id: z.string(),
      before: z.string(),
      after: z.string(),
      reason: z.string(),
      evidence: z.array(z.string()),
    }),
  ),
  unresolved: z.array(z.string()),
  generation: z.object({
    origin: z.enum(['MODEL', 'TEMPLATE']),
    model: z.string().optional(),
    usage: z.array(common.usageSchema).optional(),
  }),
})
export type ScopedDraftResponse = z.infer<typeof scopedDraftResponseSchema>

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

// 올리기 전 비비 검토(CHG-A16~A20, S15P31A108-218 수정안·PR #176). 팀장에게 가지 않는 작성 중 제안과 그 검토다.
// 필드 이름은 명세 수정안대로이고 백엔드는 아직 없다(S15P31A108-325 기준). 실제 응답이 나오면 대조한다.
export const draftInputSchema = common.changeProposalInputSchema.extend({
  // 반려된 내 제안을 보완하는 초안이면 그 제안. 이 초안은 CHG-A04로 그 제안에만 올린다.
  source_proposal_id: common.idSchema.optional(),
})
export type DraftInput = z.infer<typeof draftInputSchema>

export const updateDraftInputSchema = draftInputSchema.extend({
  expected_revision: z.number().int().positive(),
})
export type UpdateDraftInput = z.infer<typeof updateDraftInputSchema>

export const draftReviewStepSchema = z.object({
  key: z.enum([
    'READ_CHANGES',
    'FIND_RELATED',
    'AI_CANDIDATES',
    'GAPS_CONFLICTS',
  ]),
  // NOT_EVALUATED는 입력 계약이 없어 평가하지 않은 단계로, 모델 실패(FAILED)와 다르다.
  status: z.enum(['PENDING', 'RUNNING', 'DONE', 'FAILED', 'NOT_EVALUATED']),
  count: z.number().int().nonnegative().optional(),
})
export type DraftReviewStep = z.infer<typeof draftReviewStepSchema>

// PATCH는 초안의 한 문서(document_id)를 고치는 수정안, COMMENT는 읽기 전용 의견이다.
export const draftSuggestionSchema = z
  .object({
    id: common.idSchema,
    type: z.enum(['PATCH', 'COMMENT']),
    explanation: z.string(),
    category: z.enum(['OMISSION', 'CONSISTENCY']).optional(),
    design_item_key: z.string().optional(),
    document_id: common.idSchema.optional(),
    base_document_version_id: common.idSchema.optional(),
    reviewed_content_hash: z.string().optional(),
    original_text: z.string().optional(),
    replacement_text: z.string().optional(),
    decision: z.enum(['PENDING', 'APPLIED', 'SKIPPED']).optional(),
    sources: common.documentReviewSourceSchema.array().default([]),
  })
  .refine(
    (item) =>
      item.type === 'COMMENT' ||
      (item.document_id !== undefined &&
        item.original_text !== undefined &&
        item.replacement_text !== undefined &&
        item.decision !== undefined),
    'PATCH 수정안에는 문서·원문·바꿀 글·선택이 있어야 해요.',
  )
export type DraftSuggestion = z.infer<typeof draftSuggestionSchema>

export const draftReviewSchema = z.object({
  id: common.idSchema,
  // CANCELLED는 진행 중에 확인하고 올려 끊긴 검토다.
  status: z.enum(['RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED']),
  // FAILED일 때만. 접수 10분이 지나면 review_timeout
  error_code: z.string().optional(),
  // 영향 결과(tasks_to_wait·validations_to_stale·ai_candidates)는 이 리비전 기준의 미리 보기다.
  input_revision: z.number().int().positive(),
  // 이 검토의 수정안 반영까지 포함한 리비전. 초안 리비전과 다르면 검토 뒤 고친 것이다.
  resolved_revision: z.number().int().positive(),
  steps: draftReviewStepSchema.array(),
  // CHG-A14와 같은 모양. FIND_RELATED가 DONE일 때만 뜻이 있고, 아니면 빈 배열이라 0건으로 보이지 않는다.
  tasks_to_wait: approvalPreviewResponseSchema.shape.tasks_to_wait,
  validations_to_stale:
    approvalPreviewResponseSchema.shape.validations_to_stale,
  // 평가하지 못하면 null(서버는 null을 빼고 보낸다). 0건과 다르다.
  ai_candidates: z
    .object({
      task_id: common.idSchema,
      title: z.string(),
      reason: z.string(),
      evidence: z.unknown().optional(),
    })
    .array()
    .nullable()
    .optional(),
  suggestions: draftSuggestionSchema.array(),
  summary: z.string().optional(),
  // 화면에서 쓰지 않아 모양만 받는다(새 feature 값이 와도 막히지 않게).
  usage: z.unknown().array(),
  created_at: common.timeSchema,
  completed_at: common.timeSchema.optional(),
})
export type DraftReview = z.infer<typeof draftReviewSchema>

export const draftSchema = z.object({
  id: common.idSchema,
  project_id: common.idSchema,
  revision: z.number().int().positive(),
  base_baseline_id: common.idSchema,
  title: z.string(),
  reason: z.string(),
  task_id: common.idSchema.optional(),
  validation_id: common.idSchema.optional(),
  previous_proposal_id: common.idSchema.optional(),
  items: common.changeItemSchema.array(),
  documents: common.resolvedDocumentChangeSchema.array(),
  latest_review: draftReviewSchema.optional(),
  // 검토 ID·상태·수정안 선택을 합친 해시. 올릴 때 expected_review_state_hash로 돌려보낸다.
  review_state_hash: z.string().optional(),
  source_proposal_id: common.idSchema.optional(),
  submitted_proposal_id: common.idSchema.optional(),
  created_at: common.timeSchema,
  updated_at: common.timeSchema,
})
export type Draft = z.infer<typeof draftSchema>

export const draftReviewInputSchema = z.object({
  expected_revision: z.number().int().positive(),
})

export const draftDecisionInputSchema = z.object({
  expected_revision: z.number().int().positive(),
  // 반영하기·넘기기·되돌리기
  decision: z.enum(['APPLIED', 'SKIPPED', 'PENDING']),
})
export type DraftDecisionInput = z.infer<typeof draftDecisionInputSchema>

// CHG-A01·A04를 초안으로 올릴 때. 본문 필드는 초안에서 오므로 함께 보내지 않는다(보내면 400).
const draftSubmission = {
  draft_id: common.idSchema,
  expected_draft_revision: z.number().int().positive(),
  review_acknowledged: z.boolean(),
  // review_acknowledged=true이고 초안에 검토가 있을 때 사용자가 본 review_state_hash
  expected_review_state_hash: z.string().optional(),
}
export const submitDraftInputSchema = z.object(draftSubmission).strict()
export type SubmitDraftInput = z.infer<typeof submitDraftInputSchema>

export const resubmitDraftInputSchema = z
  .object({ ...draftSubmission, ...expectedSubmission })
  .strict()
export type ResubmitDraftInput = z.infer<typeof resubmitDraftInputSchema>
