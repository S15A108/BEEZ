// BEEZ API 공통 v0.3 (2026-10-23). 미결 Q01/Q02 본문은 unknown 값의 객체 경계만 검증한다.
import { z } from 'zod'

export const idSchema = z
  .string()
  .regex(/^(0|[1-9]\d*)$/, '십진 문자열 ID가 필요합니다.')
export const timeSchema = z.iso.datetime()
// 추정 비용: 명세는 십진 문자열이지만 서버(BigDecimal)는 JSON 숫자로 주고, 단가가 없으면 null을 준다.
// 숫자·문자열 모두 받아 문자열로 맞추고 null은 없는 값으로 본다(PR #72 전수 리뷰 SC-1).
export const costAmountSchema = z
  .union([z.number(), z.string()])
  .nullish()
  .transform((value) => (value == null ? undefined : String(value)))
  .optional()
export const currencySchema = z
  .string()
  .nullish()
  .transform((value) => value ?? undefined)
  .optional()
export const structuredContentSchema = z.record(z.string(), z.unknown())
export const pageQuerySchema = z.object({
  cursor: z.string().optional(),
  limit: z.number().int().min(1).max(100).optional(),
})
export const pageSchema = <T extends z.ZodType>(item: T) =>
  z
    .object({
      items: z.array(item),
      has_more: z.boolean(),
      next_cursor: z.string().optional(),
    })
    .superRefine((page, context) => {
      if (page.has_more && !page.next_cursor)
        context.addIssue({
          code: 'custom',
          path: ['next_cursor'],
          message: '다음 페이지 cursor가 필요합니다.',
        })
    })
export type Page<T> = { items: T[]; has_more: boolean; next_cursor?: string }
export type PageQuery = z.infer<typeof pageQuerySchema>

export const accountSchema = z.object({
  id: idSchema,
  email: z.string(),
  name: z.string(),
  avatar: z.string().optional(),
  created_at: timeSchema,
})
export type Account = z.infer<typeof accountSchema>

export const baselineSummarySchema = z.object({
  id: idSchema,
  version_no: z.number().int(),
  created_at: timeSchema,
})
export type BaselineSummary = z.infer<typeof baselineSummarySchema>

export const projectSchema = z.object({
  id: idSchema,
  name: z.string(),
  description: z.string().optional(),
  my_role: z.enum(['LEADER', 'MEMBER']),
  current_baseline: baselineSummarySchema.optional(),
  created_at: timeSchema,
  updated_at: timeSchema,
})
export type Project = z.infer<typeof projectSchema>

export const memberSchema = z.object({
  id: idSchema,
  account_id: idSchema,
  name: z.string(),
  role: z.enum(['LEADER', 'MEMBER']),
  domain: z.string().optional(),
  role_label: z.string().optional(),
  color: z.string().optional(),
  // 명세 요청 중(MEM-A06 팀원 목록에 avatar). 오면 사무실에서 그 팀원을 회원 설정 캐릭터로 그린다(D34).
  avatar: z.string().optional(),
  agent_connected: z.boolean(),
})
export type Member = z.infer<typeof memberSchema>

export const invitationSchema = z.object({
  id: idSchema,
  project_id: idSchema,
  invited_email: z.string().optional(),
  // Q05 6: 초대 만료는 없다. 서버(PR #134)도 expires_at을 보내지 않는다.
  accepted_at: timeSchema.optional(),
  revoked_at: timeSchema.optional(),
})
export type Invitation = z.infer<typeof invitationSchema>

export const agentTokenSchema = z.object({
  id: idSchema,
  project_id: idSchema,
  label: z.string().optional(),
  created_at: timeSchema,
  revoked_at: timeSchema.optional(),
  last_used_at: timeSchema.optional(),
})
export type AgentToken = z.infer<typeof agentTokenSchema>

export const sourceSchema = z.object({
  document_id: idSchema,
  document_version_id: idSchema,
  version_no: z.number().int(),
  location: z.string(),
})
export type Source = z.infer<typeof sourceSchema>

export const documentVersionSchema = z.object({
  id: idSchema,
  document_id: idSchema,
  version_no: z.number().int(),
  status: z.enum(['DRAFT', 'APPROVED', 'SUPERSEDED', 'REJECTED']),
  file_name: z.string(),
  media_type: z.string(),
  content_hash: z.string(),
  content: z.string().optional(),
  created_at: timeSchema,
})
export type DocumentVersion = z.infer<typeof documentVersionSchema>

export const documentSchema = z.object({
  id: idSchema,
  project_id: idSchema,
  kind: z.enum(['PLAN', 'ERD', 'REQUIREMENTS', 'API_SPEC']),
  title: z.string(),
  domain: z.string().optional(),
  approved_version: documentVersionSchema.optional(),
  latest_draft: documentVersionSchema.optional(),
})
export type Document = z.infer<typeof documentSchema>

export const documentReviewReferenceSchema = z.object({
  document_id: idSchema,
  document_version_id: idSchema,
  kind: z.string(),
  version_no: z.number().int(),
  content: z.string(),
  // v2 검토가 단계별 reference_ids로 가리키는 값. 과거 검토는 없다.
  reference_id: z.string().optional(),
})
export type DocumentReviewReference = z.infer<
  typeof documentReviewReferenceSchema
>

// 비비 검토 v2 단계(00-common DocumentBeeReview.steps). 요구사항 명세서는 단계 코드가 REQ_SPEC이다.
export const documentReviewStepCodeSchema = z.enum([
  'BODY',
  'API_SPEC',
  'ERD',
  'REQ_SPEC',
])

// 제안이 기대는 참고 승인본 근거(00-common DocumentReviewSource). 일반 RAG Source와 별개다.
export const documentReviewSourceSchema = z.object({
  reference_id: z.string(),
  document_id: idSchema,
  document_version_id: idSchema,
  kind: z.string(),
  version_no: z.number().int(),
  quote: z.string().optional(),
})
export type DocumentReviewSource = z.infer<typeof documentReviewSourceSchema>

// 제안 카드 아래 비비와의 대화(DOC-A22). 비비가 다른 대체문을 내면 proposed_text에 담긴다.
export const reviewMessageSchema = z.object({
  id: idSchema,
  author_kind: z.enum(['HUMAN', 'BEE']),
  text: z.string(),
  proposed_text: z.string().optional(),
  created_at: timeSchema,
})
export type ReviewMessage = z.infer<typeof reviewMessageSchema>

// COMMENT는 읽기 전용 의견이라 원문·바꿀 글·선택이 없다(서버는 null을 빼고 보낸다).
// type이 없는 과거 제안은 PATCH로 본다.
export const documentReviewSuggestionSchema = z
  .object({
    id: idSchema,
    explanation: z.string(),
    type: z.enum(['PATCH', 'COMMENT']).default('PATCH'),
    step: documentReviewStepCodeSchema.optional(),
    original_text: z.string().optional(),
    replacement_text: z.string().optional(),
    decision: z.enum(['PENDING', 'APPLIED', 'SKIPPED']).optional(),
    category: z
      .enum(['OMISSION', 'CONSISTENCY', 'READABILITY', 'REJECTION_FIX'])
      .optional(),
    severity: z.enum(['MAJOR', 'MINOR']).optional(),
    // 검토 결과인지, '비비에게 직접 요청하기'(DOC-A23)로 받은 제안인지
    origin: z.enum(['REVIEW', 'DIRECT_REQUEST']).optional(),
    // 비비가 대화에서 낸 다른 대체문으로 반영했을 때 실제로 쓴 문장
    applied_replacement_text: z.string().optional(),
    sources: documentReviewSourceSchema.array().default([]),
    messages: reviewMessageSchema.array().default([]),
  })
  .refine(
    (item) =>
      item.type === 'COMMENT' ||
      (item.original_text !== undefined &&
        item.replacement_text !== undefined &&
        item.decision !== undefined),
    'PATCH 제안에는 원문·바꿀 글·선택이 있어야 해요.',
  )
export type DocumentReviewSuggestion = z.infer<
  typeof documentReviewSuggestionSchema
>

export const documentReviewSubmissionSchema = z.object({
  id: idSchema,
  document_id: idSchema,
  document_version_id: idSchema,
  submitted_by: idSchema,
  revision: z.number().int(),
  // WITHDRAWN: 등록자가 팀장 결정 전에 거둔 제출(DOC-A24)
  status: z.enum(['SUBMITTED', 'APPROVED', 'REJECTED', 'WITHDRAWN']),
  content_hash: z.string(),
  submitted_at: timeSchema,
  decided_by: idSchema.optional(),
  decided_at: timeSchema.optional(),
  rejection_reason: z.string().optional(),
})
export type DocumentReviewSubmission = z.infer<
  typeof documentReviewSubmissionSchema
>

export const designItemVersionSchema = z.object({
  id: idSchema,
  design_item_id: idSchema,
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
  content: structuredContentSchema,
  source: sourceSchema.optional(),
  content_hash: z.string().optional(),
})
export type DesignItemVersion = z.infer<typeof designItemVersionSchema>

export const documentRefSchema = z.object({
  document_id: idSchema,
  document_version_id: idSchema,
})
export type DocumentRef = z.infer<typeof documentRefSchema>

export const versionRefSchema = z.object({
  design_item_id: idSchema,
  design_item_version_id: idSchema,
})
export type VersionRef = z.infer<typeof versionRefSchema>

export const designRefSchema = z
  .object({
    design_item_id: idSchema,
    design_item_version_id: idSchema,
    relation: z.enum(['READS', 'IMPLEMENTS', 'CHANGES']),
    proposed_content: structuredContentSchema.optional(),
  })
  .superRefine((reference, context) => {
    if (
      reference.relation === 'CHANGES' &&
      reference.proposed_content === undefined
    )
      context.addIssue({
        code: 'custom',
        path: ['proposed_content'],
        message: 'CHANGES에는 수정안이 필요합니다.',
      })
    if (
      reference.relation !== 'CHANGES' &&
      reference.proposed_content !== undefined
    )
      context.addIssue({
        code: 'custom',
        path: ['proposed_content'],
        message: '수정안은 CHANGES에만 전달합니다.',
      })
  })
export type DesignRef = z.infer<typeof designRefSchema>

export const validationDesignRefSchema = z.object({
  design_item_id: idSchema,
  design_item_version_id: idSchema,
  relation: z.enum(['READS', 'IMPLEMENTS', 'CHANGES', 'DEPENDENCY']),
})
export type ValidationDesignRef = z.infer<typeof validationDesignRefSchema>

export const baselineSchema = z.object({
  id: idSchema,
  project_id: idSchema,
  version_no: z.number().int(),
  documents: documentRefSchema.array(),
  items: versionRefSchema.array(),
  approved_by: idSchema,
  created_at: timeSchema,
})
export type Baseline = z.infer<typeof baselineSchema>

// S15P31A108-232: 질문 유형·답변 형식·근거 상태는 AI가 만든 질문에만 있어 모두 선택으로 받는다.
export const planningAnswerFormatSchema = z.object({
  type: z.enum(['CHOICE', 'SHORT_ANSWER', 'ESSAY']).optional(),
  options: z.string().array().optional(),
  multiple: z.boolean().optional(),
  allow_other: z.boolean().optional(),
  max_length: z.number().int().positive().optional(),
})
export type PlanningAnswerFormat = z.infer<typeof planningAnswerFormatSchema>

export const planningAnswerPayloadSchema = z.union([
  z.object({
    type: z.literal('CHOICE'),
    selected: z.string().array(),
    other_text: z.string().optional(),
  }),
  z.object({
    type: z.enum(['SHORT_ANSWER', 'ESSAY']),
    text: z.string(),
  }),
])
export type PlanningAnswerPayload = z.infer<typeof planningAnswerPayloadSchema>

export const planningQuestionSchema = z.object({
  id: idSchema,
  number: z.number().int().optional(),
  question: z.string(),
  kind: z.enum(['MISSING_CANDIDATE', 'CLARIFICATION', 'CONFLICT']).optional(),
  question_type: z.enum(['CHOICE', 'SHORT_ANSWER', 'ESSAY']).optional(),
  answer_format: planningAnswerFormatSchema.optional(),
  status: z.enum(['OPEN', 'ANSWERED', 'DISMISSED']).optional(),
  evidence_status: z.enum(['REVIEW_CANDIDATE', 'UNVERIFIED']).optional(),
  summary: z.string().optional(),
  source: sourceSchema.optional(),
  answer: z.string().optional(),
  answer_payload: planningAnswerPayloadSchema.optional(),
  answered_by: idSchema.optional(),
  answered_at: timeSchema.optional(),
})
export type PlanningQuestion = z.infer<typeof planningQuestionSchema>

export const usageSchema = z.object({
  request_key: z.string(),
  feature: z.enum([
    'DOCUMENT_PARSE',
    'TASK_VALIDATION',
    'CHANGE_IMPACT',
    'EMBEDDING',
    'BEE_CHAT',
    'BEE_DRAFT',
    'TASK_ASSIGNMENT',
    'MEETING_ANALYSIS',
  ]),
  model: z.string(),
  input_tokens: z.number().int().optional(),
  output_tokens: z.number().int().optional(),
  latency_ms: z.number().int(),
  failed: z.boolean(),
  estimated_cost_amount: costAmountSchema,
  currency: currencySchema,
  tokens_known: z.boolean(),
})
export type Usage = z.infer<typeof usageSchema>

export const documentBeeReviewSchema = z.object({
  id: idSchema,
  input_revision: z.number().int(),
  resolved_revision: z.number().int(),
  reference_hash: z.string(),
  summary: z.string(),
  unavailable_references: z.string().array(),
  references: documentReviewReferenceSchema.array(),
  suggestions: documentReviewSuggestionSchema.array(),
  usage: usageSchema.array(),
  // v2 검토는 단계 4개(본문·API 명세서·ERD·요구사항 명세서)를 주고, 과거 검토는 빈 배열이다.
  steps: z
    .object({
      step: documentReviewStepCodeSchema,
      status: z.enum(['COMPLETE', 'PARTIAL', 'SKIPPED']),
      reason: z.string(),
      reference_ids: z.string().array(),
    })
    .array()
    .default([]),
  // 승인본이 없어 건너뛴 단계가 있으면 PARTIAL. 과거 검토는 없다.
  review_status: z.enum(['COMPLETE', 'PARTIAL']).optional(),
  // 반려 뒤 검토면 서버가 고른 반려 제출
  rejection_submission_id: idSchema.optional(),
  contract_version: z.number().int().optional(),
  content_hash: z.string().optional(),
  // 실제 비교에 쓴 참고 승인본 reference_id 합집합. 과거 검토는 없다.
  references_used: z.string().array().default([]),
})
export type DocumentBeeReview = z.infer<typeof documentBeeReviewSchema>

export const documentWorkingCopySchema = z.object({
  document_id: idSchema,
  owner_account_id: idSchema,
  revision: z.number().int(),
  status: z.enum(['EDITING', 'SUBMITTED', 'APPROVED', 'REJECTED']),
  content: z.string(),
  file_name: z.string(),
  latest_review: documentBeeReviewSchema.optional(),
  submissions: documentReviewSubmissionSchema.array(),
})
export type DocumentWorkingCopy = z.infer<typeof documentWorkingCopySchema>

export const documentReviewSubmissionDetailSchema = z.object({
  submission: documentReviewSubmissionSchema,
  content: z.string(),
  review: documentBeeReviewSchema,
})
export type DocumentReviewSubmissionDetail = z.infer<
  typeof documentReviewSubmissionDetailSchema
>

export const generationSchema = z.object({
  origin: z.enum(['MODEL', 'TEMPLATE']),
  model: z.string().optional(),
  text: z.string(),
  sources: sourceSchema.array(),
  unresolved: z.string().array(),
  usage: usageSchema.array().optional(),
})
export type Generation = z.infer<typeof generationSchema>

export const designDependencySchema = z.object({
  from_design_item_id: idSchema,
  to_design_item_id: idSchema,
  origin: z.enum(['EXPLICIT', 'AI_CANDIDATE']),
})
export type DesignDependency = z.infer<typeof designDependencySchema>

export const changeItemSchema = z
  .object({
    operation: z.enum(['CREATE', 'UPDATE', 'RETIRE']),
    design_item_id: idSchema.optional(),
    base_design_item_version_id: idSchema.optional(),
    kind: z
      .enum([
        'REQUIREMENT',
        'API_CONTRACT',
        'DATA_CONTRACT',
        'POLICY',
        'CONSTRAINT',
      ])
      .optional(),
    schema_version: z.number().int().optional(),
    proposed_content: structuredContentSchema.optional(),
  })
  .superRefine((change, context) => {
    const required =
      change.operation === 'CREATE'
        ? ['kind', 'schema_version', 'proposed_content']
        : change.operation === 'UPDATE'
          ? [
              'design_item_id',
              'base_design_item_version_id',
              'schema_version',
              'proposed_content',
            ]
          : ['design_item_id', 'base_design_item_version_id']
    for (const field of required)
      if (Reflect.get(change, field) === undefined)
        context.addIssue({
          code: 'custom',
          path: [field],
          message: '이 변경 종류에 필요한 필드입니다.',
        })
  })
export type ChangeItem = z.infer<typeof changeItemSchema>

export const documentChangeSchema = z
  .object({
    document_id: idSchema,
    base_document_version_id: idSchema,
    after_content: z.string().optional(),
    proposed_document_version_id: idSchema.optional(),
  })
  .superRefine((change, context) => {
    if (
      (change.after_content === undefined) ===
      (change.proposed_document_version_id === undefined)
    )
      context.addIssue({
        code: 'custom',
        path: ['after_content'],
        message: '수정 원문과 업로드한 초안 버전 중 하나만 필요합니다.',
      })
  })
export type DocumentChange = z.infer<typeof documentChangeSchema>

export const changeProposalInputSchema = z.object({
  base_baseline_id: idSchema,
  title: z.string(),
  reason: z.string(),
  task_id: idSchema.optional(),
  validation_id: idSchema.optional(),
  /** 거둔(WITHDRAWN) 제안을 새 제안으로 다시 올릴 때만. 반려된 제안은 CHG-A04로 같은 번호에서 다시 올린다. */
  previous_proposal_id: idSchema.optional(),
  items: changeItemSchema.array(),
  documents: documentChangeSchema.array(),
})
export type ChangeProposalInput = z.infer<typeof changeProposalInputSchema>

// PR #46(195): 충돌 검사는 updated_at이 아니라 제출 리비전과 스냅샷 해시로 한다.
export const changeProposalStatusSchema = z.enum([
  'PROPOSED',
  'IN_REVIEW',
  'APPROVED',
  'PUBLISHED',
  'REJECTED',
  'WITHDRAWN',
])
export const snapshotHashSchema = z
  .string()
  .regex(/^sha256:[0-9a-f]{64}$/, 'sha256: 뒤에 소문자 64자리여야 합니다.')

// DocumentChange 입력을 해석한 응답(PR #46 0df13f0). after_content로 내도 서버가 DRAFT 버전을 만들어
// proposed_document_version_id를 항상 준다. after_content·content_hash는 정규화(BOM 제거, CRLF→LF)한 저장 본문이다.
export const resolvedDocumentChangeSchema = z.object({
  document_id: idSchema,
  base_document_version_id: idSchema,
  proposed_document_version_id: idSchema,
  after_content: z.string(),
  content_hash: z.string(),
})
export type ResolvedDocumentChange = z.infer<
  typeof resolvedDocumentChangeSchema
>

export const changeProposalSchema = z.object({
  id: idSchema,
  key: z.string(),
  revision: z.number().int().positive(),
  snapshot_hash: snapshotHashSchema,
  project_id: idSchema,
  base_baseline_id: idSchema,
  title: z.string(),
  reason: z.string(),
  task_id: idSchema.optional(),
  validation_id: idSchema.optional(),
  // 195 미구현(#218 요청): 실제 서버에는 없을 수 있다.
  meeting_id: idSchema.optional(),
  items: changeItemSchema.array(),
  documents: resolvedDocumentChangeSchema.array(),
  status: changeProposalStatusSchema,
  proposer_account_id: idSchema,
  actor_type: z.enum(['HUMAN', 'AGENT']),
  // 195 미구현(#218 요청)
  agent: z
    .object({
      agent_session_id: idSchema,
      name: z.string(),
      member_id: idSchema,
    })
    .optional(),
  current_baseline_id: idSchema,
  requires_refresh: z.boolean(),
  published_baseline_id: idSchema.optional(),
  created_at: timeSchema,
  updated_at: timeSchema,
  rejection_reason: z.string().optional(),
  previous_proposal_id: idSchema.optional(),
  withdrawn_by: idSchema.optional(),
  withdrawn_at: timeSchema.optional(),
})
export type ChangeProposal = z.infer<typeof changeProposalSchema>

export const changeProposalSummarySchema = z.object({
  id: idSchema,
  key: z.string(),
  title: z.string(),
  status: changeProposalStatusSchema,
  revision: z.number().int().positive(),
  base_baseline_id: idSchema,
  proposer_account_id: idSchema,
  updated_at: timeSchema,
})
export type ChangeProposalSummary = z.infer<typeof changeProposalSummarySchema>

export const approvalSchema = z.object({
  id: idSchema,
  proposal_id: idSchema,
  approved_by: idSchema,
  approved_at: timeSchema,
  baseline_id: idSchema,
  impact_override_reason: z.string().optional(),
})
export type Approval = z.infer<typeof approvalSchema>

export const changeCommentSchema = z.object({
  id: idSchema,
  proposal_id: idSchema,
  author_account_id: idSchema,
  kind: z.enum(['COMMENT', 'REVIEW_REQUEST']),
  body: z.string(),
  created_at: timeSchema,
})
export type ChangeComment = z.infer<typeof changeCommentSchema>

export const explicitImpactSchema = z.object({
  task_id: idSchema,
  // 담당자가 없는 작업이면 서버가 생략한다(00-common ExplicitImpact).
  member_id: idSchema.optional(),
  design_item_id: idSchema,
  validation_id: idSchema.optional(),
})
export type ExplicitImpact = z.infer<typeof explicitImpactSchema>

export const impactCandidateSchema = z.object({
  task_id: idSchema.optional(),
  member_id: idSchema.optional(),
  design_item_id: idSchema.optional(),
  reason: z.string(),
  sources: sourceSchema.array(),
})
export type ImpactCandidate = z.infer<typeof impactCandidateSchema>

// 서버(A11·A12)는 6종 상태를 돌려준다. 분석 이력이 없는 제안은 200 NOT_EVALUATED(analysis_not_started)다.
// PENDING 은 예시 서버 호환용으로 남긴다.
export const impactStatusSchema = z.enum([
  'PENDING',
  'RUNNING',
  'COMPLETED',
  'INSUFFICIENT_EVIDENCE',
  'NOT_EVALUATED',
  'FAILED',
  'STALE',
])
export type ImpactStatus = z.infer<typeof impactStatusSchema>

export const ownerRecommendationEvidenceSchema = z.object({
  type: z.enum(['DIRECT_TASK_REFERENCE', 'DOCUMENT_SOURCE', 'AI_CANDIDATE']),
  reason: z.string(),
  task_id: idSchema,
  task_key: z.string(),
  plan_revision_id: idSchema,
  design_item_id: idSchema.optional(),
  design_item_version_id: idSchema.optional(),
  referenced_design_item_version_id: idSchema.optional(),
  document_id: idSchema.optional(),
  document_version_id: idSchema.optional(),
  sources: sourceSchema.array(),
})

export const ownerRecommendationsSchema = z.object({
  proposal_revision: z.number().int(),
  snapshot_hash: z.string(),
  direct_reference_status: z.enum([
    'COMPLETE',
    'INCOMPLETE',
    'STALE',
    'NOT_APPLICABLE',
  ]),
  direct_reference_reasons: z.string().array(),
  document_reference_status: z
    .enum(['COMPLETE', 'INCOMPLETE', 'STALE', 'NOT_APPLICABLE'])
    .optional(),
  document_reference_reasons: z.string().array().optional(),
  ai_analysis_status: impactStatusSchema,
  ai_current: z.boolean(),
  ai_reanalysis_in_progress: z.boolean(),
  withheld_ai_candidates: z.number().int().nonnegative(),
  recommendations: z
    .object({
      member_id: idSchema,
      area: z.string().optional(),
      evidence: ownerRecommendationEvidenceSchema.array(),
    })
    .array(),
  notification_candidate_member_ids: idSchema.array(),
})
export type OwnerRecommendations = z.infer<typeof ownerRecommendationsSchema>

export const changeImpactSchema = z.object({
  explicit: explicitImpactSchema.array(),
  candidates: impactCandidateSchema.array(),
  analysis_status: impactStatusSchema,
  generation: generationSchema.optional(),
  error_code: z.string().optional(),
  revision: z.number().int().optional(),
  snapshot_hash: z.string().optional(),
  is_current: z.boolean().optional(),
  stale_reason: z.string().optional(),
  run_id: z.string().optional(),
  reanalysis: z
    .object({ run_id: z.string(), requested_at: timeSchema.optional() })
    .optional(),
  // CHG-A05 확장. 기존 서버와 CHG-A06 재분석 접수 응답에는 없을 수 있다.
  owner_recommendations: ownerRecommendationsSchema.optional(),
})
export type ChangeImpact = z.infer<typeof changeImpactSchema>

export const taskPlanInputSchema = z.object({
  title: z.string(),
  goal: z.string(),
  included_scope: z.string().array(),
  excluded_scope: z.string().array(),
  approach: z.string(),
  completion_criteria: z.string().array(),
  dependency_task_ids: idSchema.array(),
  design_refs: designRefSchema.array(),
  unlinked: structuredContentSchema.array(),
  no_contract_change: z.boolean(),
  context_packet_id: idSchema.optional(),
})
export type TaskPlanInput = z.infer<typeof taskPlanInputSchema>

export const taskPlanRevisionSchema = z.object({
  id: idSchema,
  task_id: idSchema,
  revision_no: z.number().int(),
  plan: taskPlanInputSchema,
  created_at: timeSchema,
  created_by: idSchema,
  agent_token_id: idSchema.optional(),
})
export type TaskPlanRevision = z.infer<typeof taskPlanRevisionSchema>

export const relatedTaskRefSchema = z.object({
  task_id: idSchema,
  plan_revision_id: idSchema,
})
export type RelatedTaskRef = z.infer<typeof relatedTaskRefSchema>

export const findingSchema = z.object({
  id: idSchema,
  source: z.enum(['RULE', 'MODEL']),
  kind: z.enum([
    'MISMATCH',
    'MISSING_INPUT',
    'MISSING_POLICY',
    'CONFLICT',
    'STALE_REF',
    'ERROR',
  ]),
  severity: z.enum(['INFO', 'WARNING', 'BLOCKING']),
  message: z.string(),
  design_item_id: idSchema.optional(),
  design_item_version_id: idSchema.optional(),
  related_task_id: idSchema.optional(),
  sources: sourceSchema.array(),
  resolution: z.enum(['FIX_PLAN', 'PROPOSE_CHANGE', 'HUMAN_REVIEW', 'RETRY']),
  next_action: z.string().optional(),
  ai_suggested_action: z.string().optional(),
  validation_run_id: idSchema.optional(),
  policy_need_id: z.string().optional(),
  conflicting_design_refs: z
    .array(
      z.object({
        design_item_id: idSchema,
        design_item_version_id: idSchema,
        evidence_ids: z.array(z.string()),
        content_paths: z.array(z.array(z.string())),
      }),
    )
    .optional(),
})
export type Finding = z.infer<typeof findingSchema>

const policyPlanEvidenceSchema = z.object({
  field: z.string(),
  index: z.number().int().optional(),
  quote: z.string(),
})

export const validationSchema = z
  .object({
    id: idSchema,
    task_id: idSchema,
    plan_revision_id: idSchema,
    baseline_id: idSchema,
    status: z.enum([
      'NOT_RUN',
      'RUNNING',
      'PASS',
      'NEEDS_INPUT',
      'NEEDS_REVIEW',
      'STALE',
      'ERROR',
    ]),
    trigger: z.enum(['AGENT', 'REVALIDATION', 'DELTA']),
    stale_reason: z.string().optional(),
    current_attempt: z.number().int(),
    design_refs: validationDesignRefSchema.array(),
    related_task_refs: relatedTaskRefSchema.array(),
    findings: findingSchema.array(),
    cache_hit: z.boolean(),
    created_at: timeSchema,
    finished_at: timeSchema.optional(),
    error_code: z.string().optional(),
    policy_analysis: z
      .object({
        policy_assessments: z.array(
          z.object({
            need_id: z.string(),
            topic: z.string(),
            plan_evidence: policyPlanEvidenceSchema,
            state: z.enum([
              'SUPPORTED',
              'EXPLICITLY_UNDECIDED',
              'CONTRADICTORY',
              'INSUFFICIENT',
            ]),
            design_item_version_ids: z.array(idSchema),
            approved_content_refs: z.array(
              z.object({
                design_item_version_id: idSchema,
                path: z.array(z.string()),
                quote: z.string(),
              }),
            ),
            evidence_ids: z.array(z.string()),
            explanation: z.string(),
          }),
        ),
        policy_assessments_truncated: z.boolean(),
        no_policy_requirements_reason: z.string().optional(),
        no_policy_requirements_evidence: policyPlanEvidenceSchema.optional(),
      })
      .optional(),
  })
  .superRefine((validation, context) => {
    if (validation.status === 'STALE' && !validation.stale_reason)
      context.addIssue({
        code: 'custom',
        path: ['stale_reason'],
        message: '무효화 이유가 필요합니다.',
      })
  })
export type Validation = z.infer<typeof validationSchema>

export const taskDetailSchema = z
  .object({
    id: idSchema,
    key: z.string(),
    project_id: idSchema,
    // 담당자가 나가거나 제외돼 비면(미배정) 서버가 생략한다(11-04 사용자 결정, MEM-A09·A10).
    assignee_member_id: idSchema.optional(),
    status: z.enum([
      'DRAFT',
      'READY',
      'IN_PROGRESS',
      'WAITING',
      'COMPLETED',
      'CANCELLED',
    ]),
    waiting_reason: z
      .enum(['INPUT', 'APPROVAL', 'DEPENDENCY', 'REVALIDATION'])
      .optional(),
    lock_version: z.number().int(),
    current_plan_revision: taskPlanRevisionSchema,
    validation_status: z.enum([
      'NOT_RUN',
      'RUNNING',
      'PASS',
      'NEEDS_INPUT',
      'NEEDS_REVIEW',
      'STALE',
      'ERROR',
    ]),
    validation: validationSchema.optional(),
    started_validation_id: idSchema.optional(),
    delta_validation_id: idSchema.optional(),
    created_at: timeSchema,
    updated_at: timeSchema,
  })
  .superRefine((task, context) => {
    if (task.status === 'WAITING' && !task.waiting_reason)
      context.addIssue({
        code: 'custom',
        path: ['waiting_reason'],
        message: '대기 이유가 필요합니다.',
      })
    if (task.status !== 'WAITING' && task.waiting_reason !== undefined)
      context.addIssue({
        code: 'custom',
        path: ['waiting_reason'],
        message: '대기 이유는 WAITING에서만 포함합니다.',
      })
  })
export type TaskDetail = z.infer<typeof taskDetailSchema>

export const completionCheckSchema = z.object({
  criterion: z.string(),
  met: z.boolean(),
})
export type CompletionCheck = z.infer<typeof completionCheckSchema>

export const taskStateInputSchema = z
  .object({
    event: z.enum(['START', 'RESUME', 'WAIT', 'COMPLETED', 'CANCEL']),
    plan_revision_id: idSchema,
    expected_lock_version: z.number().int(),
    validation_id: idSchema.optional(),
    waiting_reason: z.enum(['INPUT', 'APPROVAL', 'DEPENDENCY']).optional(),
    summary: z.string().optional(),
    delta: structuredContentSchema.array().optional(),
    completion_checks: completionCheckSchema.array().optional(),
  })
  .superRefine((input, context) => {
    if (['START', 'RESUME'].includes(input.event) && !input.validation_id)
      context.addIssue({
        code: 'custom',
        path: ['validation_id'],
        message: '시작과 재개에는 PASS 검증 ID가 필요합니다.',
      })
    if (input.event === 'WAIT' && !input.waiting_reason)
      context.addIssue({
        code: 'custom',
        path: ['waiting_reason'],
        message: '대기 이유가 필요합니다.',
      })
  })
export type TaskStateInput = z.infer<typeof taskStateInputSchema>

export const relatedTaskSchema = z.object({
  task_id: idSchema,
  plan_revision_id: idSchema,
  status: z.enum(['READY', 'IN_PROGRESS', 'WAITING', 'COMPLETED', 'CANCELLED']),
})
export type RelatedTask = z.infer<typeof relatedTaskSchema>

export const contextPacketSchema = z.object({
  id: idSchema,
  project_id: idSchema,
  task_id: idSchema.optional(),
  baseline_id: idSchema,
  request: z.string(),
  design_items: designItemVersionSchema.array(),
  constraints: z.string().array(),
  unresolved: z.string().array(),
  related_tasks: relatedTaskSchema.array(),
  sources: sourceSchema.array(),
  created_at: timeSchema,
  content_hash: z.string(),
})
export type ContextPacket = z.infer<typeof contextPacketSchema>

export const contextRecordInputSchema = z.object({
  previous_id: idSchema.optional(),
  task_id: idSchema.optional(),
  plan_revision_id: idSchema.optional(),
  source_packet_id: idSchema.optional(),
  kind: z.enum(['DECISION', 'PROGRESS', 'ISSUE', 'HANDOVER']),
  visibility: z.enum(['SHARED', 'PRIVATE']),
  summary: z.string(),
  detail: z.string(),
  design_refs: versionRefSchema.array(),
})
export type ContextRecordInput = z.infer<typeof contextRecordInputSchema>

export const contextRecordSummarySchema = z.object({
  id: idSchema,
  root_id: idSchema,
  revision_no: z.number().int(),
  task_id: idSchema.optional(),
  kind: z.enum(['DECISION', 'PROGRESS', 'ISSUE', 'HANDOVER']),
  summary: z.string(),
  author_account_id: idSchema,
  visibility: z.enum(['SHARED', 'PRIVATE']),
  is_latest_revision: z.boolean(),
  references_current: z.boolean(),
  created_at: timeSchema,
})
export type ContextRecordSummary = z.infer<typeof contextRecordSummarySchema>

export const contextRecordSchema = z.object({
  id: idSchema,
  root_id: idSchema,
  revision_no: z.number().int(),
  task_id: idSchema.optional(),
  kind: z.enum(['DECISION', 'PROGRESS', 'ISSUE', 'HANDOVER']),
  summary: z.string(),
  author_account_id: idSchema,
  visibility: z.enum(['SHARED', 'PRIVATE']),
  is_latest_revision: z.boolean(),
  references_current: z.boolean(),
  created_at: timeSchema,
  previous_id: idSchema.optional(),
  plan_revision_id: idSchema.optional(),
  source_packet_id: idSchema.optional(),
  detail: z.string(),
  design_refs: versionRefSchema.array(),
})
export type ContextRecord = z.infer<typeof contextRecordSchema>

export const agentConnectionSchema = z.object({
  id: idSchema,
  member_id: idSchema,
  connected: z.boolean(),
  last_report_at: timeSchema.optional(),
  status_check_required: z.boolean(),
})
export type AgentConnection = z.infer<typeof agentConnectionSchema>

export const agentWorkSchema = z.object({
  member_id: idSchema,
  agent_session_id: idSchema.optional(),
  agent_name: z.string().optional(),
  current_task_id: idSchema.optional(),
  task_title: z.string().optional(),
  // 명세에는 없지만 DB는 DRAFT를 허용하고 MON-A05가 작업 상태를 그대로 넘긴다. 이 값 하나로 응답 전체가 깨지지 않게 받는다.
  task_status: z
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
  waiting_reason: z.string().optional(),
  // 명세의 마지막 작업 보고 시각. dev 백엔드는 아직 주지 않는다(11-06 확인). 오면 보여 준다.
  reported_at: timeSchema.optional(),
})
export type AgentWork = z.infer<typeof agentWorkSchema>

export const requiredDocumentSchema = z.object({
  kind: z.enum(['PLAN', 'ERD', 'REQUIREMENTS', 'API_SPEC']),
  uploaded: z.boolean(),
  approved: z.boolean(),
})
export type RequiredDocument = z.infer<typeof requiredDocumentSchema>

export const documentAnalysisSchema = z.object({
  document_version_id: idSchema,
  status: z.enum(['PENDING', 'RUNNING', 'COMPLETED', 'FAILED']),
  items: designItemVersionSchema.array(),
  questions: planningQuestionSchema.array(),
  error_code: z.string().optional(),
})
export type DocumentAnalysis = z.infer<typeof documentAnalysisSchema>

const retrievalLocatorSchema = z.object({
  type: z.literal('text_lines'),
  start_line: z.number().int().min(1),
  end_line: z.number().int().min(1),
})

export const searchHitSchema = z.object({
  type: z.enum(['DOCUMENT', 'DOCUMENT_CHUNK', 'DESIGN_ITEM']),
  id: idSchema,
  title: z.string(),
  snippet: z.string(),
  source: sourceSchema.optional(),
  design_item_id: idSchema.optional(),
  score: z.number().finite().optional(),
  // Exact offsets are Unicode code points in utf8-lf-v1 text; end is exclusive.
  chunk_key: z.string().optional(),
  source_spans: z
    .array(
      z.object({
        block_id: z.string(),
        start_offset: z.number().int().nonnegative(),
        end_offset: z.number().int().positive(),
        locator: retrievalLocatorSchema,
      }),
    )
    .optional(),
  context_refs: z
    .array(
      z.object({
        block_id: z.string(),
        role: z.enum(['heading', 'table_header']),
        locator: retrievalLocatorSchema,
        text_hash: z.string(),
        applied: z.boolean(),
      }),
    )
    .optional(),
})
export type SearchHit = z.infer<typeof searchHitSchema>

export const participantSchema = z.object({
  member_id: idSchema,
  status: z.enum(['INVITED', 'JOINED', 'DECLINED']),
})
export type Participant = z.infer<typeof participantSchema>

export const meetingEntrySchema = z.object({
  id: idSchema,
  meeting_id: idSchema,
  // CONFLICT_BRIEF: 충돌 회의를 열 때 비비가 남기는 첫 기록, SUMMARY: MTG-A11 정리 (명세 MeetingEntry)
  kind: z.enum([
    'TEXT',
    'BEE_ANALYSIS',
    'MINUTES',
    'CONFLICT_BRIEF',
    'SUMMARY',
  ]),
  text: z.string(),
  author_account_id: idSchema.optional(),
  through_entry_id: idSchema.optional(),
  generation: generationSchema.optional(),
  created_at: timeSchema,
})
export type MeetingEntry = z.infer<typeof meetingEntrySchema>

export const meetingSchema = z.object({
  id: idSchema,
  project_id: idSchema,
  title: z.string(),
  change_proposal_id: idSchema.optional(),
  // dev 명세의 '수정안 요청' 필드(PR #42). 서버가 아직 주지 않을 수 있다.
  origin: z.enum(['BEE_CONFLICT', 'HUMAN']).optional(),
  task_ids: idSchema.array().optional(),
  conflict_summary: z.string().optional(),
  status: z.enum(['OPEN', 'CLOSED']).optional(),
  closed_at: timeSchema.optional(),
  closed_by: idSchema.optional(),
  participants: participantSchema.array(),
  created_at: timeSchema,
})
export type Meeting = z.infer<typeof meetingSchema>

export const actionItemSchema = z.object({
  text: z.string(),
  member_id: idSchema.optional(),
})
export type ActionItem = z.infer<typeof actionItemSchema>

export const meetingMinutesSchema = z.object({
  entry: meetingEntrySchema,
  summary: z.string(),
  decisions: z.string().array(),
  action_items: actionItemSchema.array(),
  unresolved: z.string().array(),
})
export type MeetingMinutes = z.infer<typeof meetingMinutesSchema>

export const assignmentSuggestionSchema = z.object({
  task_id: idSchema,
  member_id: idSchema,
  reason: z.string(),
})
export type AssignmentSuggestion = z.infer<typeof assignmentSuggestionSchema>

export const relationNodeSchema = z.object({
  id: idSchema,
  type: z.enum(['TASK', 'DESIGN_ITEM', 'VALIDATION', 'CHANGE_PROPOSAL']),
  label: z.string(),
})
export type RelationNode = z.infer<typeof relationNodeSchema>

export const relationEdgeSchema = z.object({
  from_id: idSchema,
  from_type: z.enum(['TASK', 'DESIGN_ITEM', 'VALIDATION', 'CHANGE_PROPOSAL']),
  to_id: idSchema,
  to_type: z.enum(['TASK', 'DESIGN_ITEM', 'VALIDATION', 'CHANGE_PROPOSAL']),
  relation: z.string(),
  confirmed: z.boolean(),
})
export type RelationEdge = z.infer<typeof relationEdgeSchema>

export const actorTypeSchema = z.enum(['HUMAN', 'AGENT', 'BEE', 'SYSTEM'])
export const activitySchema = z.object({
  id: idSchema,
  type: z.string(),
  actor_type: actorTypeSchema,
  actor_account_id: idSchema.optional(),
  agent_session_id: idSchema.optional(),
  actor_name: z.string().optional(),
  task_id: idSchema.optional(),
  change_proposal_id: idSchema.optional(),
  baseline_id: idSchema.optional(),
  result: z.string(),
  created_at: timeSchema,
})
export type Activity = z.infer<typeof activitySchema>

export const notificationSchema = z.object({
  id: idSchema,
  project_id: idSchema,
  type: z.string(),
  title: z.string(),
  body: z.string(),
  target_type: z.enum([
    'TASK',
    'VALIDATION',
    'CHANGE_PROPOSAL',
    'BASELINE',
    'MEETING',
    'DOCUMENT_VERSION',
  ]),
  // DOCUMENT_VERSION이면 target_id는 문서 버전 ID이고, 문서 상세를 열 문서 ID는 document_id로 온다(계약 10 3절).
  target_id: idSchema,
  document_id: idSchema.optional(),
  // BASELINE이면 target_id는 기준 ID이고, 기준 화면을 열 기준 버전 번호는 baseline_version_no로 온다(PR #133).
  baseline_version_no: z.number().int().optional(),
  created_at: timeSchema,
  read_at: timeSchema.optional(),
})
export type Notification = z.infer<typeof notificationSchema>

export const usageBucketSchema = z.object({
  key: z.string(),
  label: z.string().optional(),
  input_tokens: z.number().int(),
  output_tokens: z.number().int(),
  call_count: z.number().int(),
  estimated_cost_amount: costAmountSchema,
  // 토큰·비용이 합계에 들어가지 못한 호출 수(BE -169). 0보다 크면 합계는 일부만 센 값이다.
  tokens_unknown_call_count: z.number().int().optional(),
  cost_unknown_call_count: z.number().int().optional(),
})
export type UsageBucket = z.infer<typeof usageBucketSchema>

export const usageSummarySchema = z.object({
  from: timeSchema,
  to: timeSchema,
  input_tokens: z.number().int(),
  output_tokens: z.number().int(),
  call_count: z.number().int(),
  estimated_cost_amount: costAmountSchema,
  currency: currencySchema,
  tokens_unknown_call_count: z.number().int().optional(),
  cost_unknown_call_count: z.number().int().optional(),
  by_feature: usageBucketSchema.array(),
  by_task: usageBucketSchema.array(),
  by_validation: usageBucketSchema.array(),
  by_agent: usageBucketSchema.array(),
  by_day: usageBucketSchema.array(),
})
export type UsageSummary = z.infer<typeof usageSummarySchema>

export const changedItemSchema = z.object({
  design_item_id: idSchema,
  before_version_id: idSchema.optional(),
  after_version_id: idSchema.optional(),
})
export type ChangedItem = z.infer<typeof changedItemSchema>

export const publishedChangeSchema = z.object({
  baseline: baselineSummarySchema,
  proposal_id: idSchema,
  changed_items: changedItemSchema.array(),
  affected_task_ids: idSchema.array(),
})
export type PublishedChange = z.infer<typeof publishedChangeSchema>

export const validationValiditySchema = z.object({
  validation_id: idSchema,
  status: z.enum([
    'PASS',
    'STALE',
    'ERROR',
    'RUNNING',
    'NEEDS_INPUT',
    'NEEDS_REVIEW',
  ]),
  reason: z.string().optional(),
})
export type ValidationValidity = z.infer<typeof validationValiditySchema>

export const designChangesSchema = z.object({
  current_baseline: baselineSummarySchema,
  items: publishedChangeSchema.array(),
  validation_validity: validationValiditySchema.array().optional(),
  has_more: z.boolean(),
  next_cursor: z.string().optional(),
})
export type DesignChanges = z.infer<typeof designChangesSchema>

export const findingCandidateSchema = z.object({
  kind: z.enum([
    'MISMATCH',
    'MISSING_INPUT',
    'MISSING_POLICY',
    'CONFLICT',
    'STALE_REF',
  ]),
  severity: z.enum(['INFO', 'WARNING', 'BLOCKING']),
  message: z.string(),
  design_item_id: idSchema.optional(),
  design_item_version_id: idSchema.optional(),
  related_task_id: idSchema.optional(),
  sources: sourceSchema.array(),
  suggested_action: z.string(),
})
export type FindingCandidate = z.infer<typeof findingCandidateSchema>

export const parsedItemSchema = z.object({
  candidate_key: z.string(),
  kind: z.enum([
    'REQUIREMENT',
    'API_CONTRACT',
    'DATA_CONTRACT',
    'POLICY',
    'CONSTRAINT',
  ]),
  schema_version: z.number().int(),
  content: structuredContentSchema,
  location: z.string(),
})
export type ParsedItem = z.infer<typeof parsedItemSchema>

export const promptQuestionSchema = z.object({
  question: z.string(),
  location: z.string(),
})
export type PromptQuestion = z.infer<typeof promptQuestionSchema>

export const embeddingTextSchema = z.object({
  key: z.string(),
  text: z.string(),
})
export type EmbeddingText = z.infer<typeof embeddingTextSchema>

export const embeddingVectorSchema = z.object({
  key: z.string(),
  embedding: z.number().finite().array(),
})
export type EmbeddingVector = z.infer<typeof embeddingVectorSchema>

export const documentCountSchema = z.object({
  kind: z.enum(['PLAN', 'ERD', 'REQUIREMENTS', 'API_SPEC']),
  draft_count: z.number().int(),
  approved_count: z.number().int(),
})
export type DocumentCount = z.infer<typeof documentCountSchema>

export const stateCountSchema = z.object({
  status: z.string(),
  count: z.number().int(),
})
export type StateCount = z.infer<typeof stateCountSchema>

export const waitingCountSchema = z.object({
  waiting_reason: z.string(),
  count: z.number().int(),
})
export type WaitingCount = z.infer<typeof waitingCountSchema>

export const designSuggestionSchema = z.object({
  design_item_version: designItemVersionSchema,
  reason: z.string(),
  sources: sourceSchema.array(),
})
export type DesignSuggestion = z.infer<typeof designSuggestionSchema>

export const dependencyCandidateSchema = z.object({
  from_design_item_id: idSchema,
  to_design_item_id: idSchema,
  reason: z.string(),
  confirmed: z.boolean(),
})
export type DependencyCandidate = z.infer<typeof dependencyCandidateSchema>

export const comparisonSchema = z.object({
  target_type: z.enum(['DOCUMENT', 'DESIGN_ITEM']),
  // CREATE 항목은 발행 전이라 ID가 없다. 서버는 null 값을 응답에서 빼므로(전역 non_null) 키가 없어도 받는다
  // (PR #72 mock/live 전수 대조 DOC-LIVE-2).
  target_id: idSchema.nullish(),
  // 기준과 지금 내용이 다름. 기준→제안 diff와 별개
  current_changed: z.boolean(),
  format: z.enum(['TEXT', 'FILE', 'STRUCTURED']),
  base_content: z.union([structuredContentSchema, z.string()]).optional(),
  current_content: z.union([structuredContentSchema, z.string()]).optional(),
  proposed_content: z.union([structuredContentSchema, z.string()]).optional(),
  diff: z.string().optional(),
  base_document_version_id: idSchema.optional(),
  proposed_document_version_id: idSchema.optional(),
})
export type Comparison = z.infer<typeof comparisonSchema>

export const parseResultSchema = z.object({
  items: parsedItemSchema.array(),
  questions: promptQuestionSchema.array(),
  missing_candidates: z.string().array(),
})
export type ParseResult = z.infer<typeof parseResultSchema>

export const validateResultSchema = z.object({
  findings: findingCandidateSchema.array(),
})
export type ValidateResult = z.infer<typeof validateResultSchema>

export const impactResultSchema = z.object({
  candidates: impactCandidateSchema.array(),
})
export type ImpactResult = z.infer<typeof impactResultSchema>

export const embeddingResultSchema = z.object({
  model: z.string(),
  dimension: z.number().int(),
  vectors: embeddingVectorSchema.array(),
})
export type EmbeddingResult = z.infer<typeof embeddingResultSchema>

export const chatResultSchema = z.object({
  text: z.string(),
  sources: sourceSchema.array(),
  unresolved: z.string().array(),
  next_actions: z.string().array(),
})
export type ChatResult = z.infer<typeof chatResultSchema>

export const draftResultSchema = z.object({
  after_content: z.string(),
  reason: z.string(),
  sources: sourceSchema.array(),
  unresolved: z.string().array(),
})
export type DraftResult = z.infer<typeof draftResultSchema>

export const assignmentsResultSchema = z.object({
  suggestions: assignmentSuggestionSchema.array(),
})
export type AssignmentsResult = z.infer<typeof assignmentsResultSchema>

export const meetingAnalysisResultSchema = z.object({
  text: z.string(),
  decisions: z.string().array(),
  unresolved: z.string().array(),
  action_items: actionItemSchema.array(),
  sources: sourceSchema.array(),
})
export type MeetingAnalysisResult = z.infer<typeof meetingAnalysisResultSchema>
