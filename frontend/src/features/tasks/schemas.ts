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

const breakdownJob = {
  id: common.idSchema,
  baseline_id: common.idSchema,
}
export const breakdownJobSchema = z.discriminatedUnion('status', [
  z.object({ ...breakdownJob, status: z.enum(['PENDING', 'RUNNING']) }),
  z.object({
    ...breakdownJob,
    status: z.literal('COMPLETED'),
    tasks: z.array(
      z.object({
        id: common.idSchema,
        key: z.string(),
        title: z.string(),
        assignee_member_id: common.idSchema.nullish(),
      }),
    ),
  }),
  z.object({
    ...breakdownJob,
    status: z.literal('FAILED'),
    error_code: z.string().optional(),
    retryable: z.boolean().optional(),
  }),
])
export const breakdownStatusResponseSchema = breakdownJobSchema.nullable()
export type BreakdownJob = z.infer<typeof breakdownJobSchema>
export type CreateResponse = z.infer<typeof createResponseSchema>

// TSK-A02(11-04 사용자 결정): status는 여러 값을 받고(같은 이름 반복), unassigned=true면 담당자 없는 작업만.
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
    .array()
    .optional(),
  unassigned: z.boolean().optional(),
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

// TSK-A08(S15P31A108-385): 승인 문서로 계획 칸 초안을 받는다. 저장하지 않고 응답으로만 돌려준다.
// 제목만 있어도 된다. 메모는 칸이 없어 이미 적은 목표를 보낸다.
export const planDraftInputSchema = z.object({
  title: z.string().trim().min(1).max(200),
  memo: z.string().trim().max(2000).optional(),
})
export type PlanDraftInput = z.infer<typeof planDraftInputSchema>

export const planDraftFieldSchema = z.enum([
  'goal',
  'included_scope',
  'excluded_scope',
  'approach',
  'completion_criteria',
])
export type PlanDraftField = z.infer<typeof planDraftFieldSchema>

const documentTypeSchema = z.enum(['PLAN', 'REQUIREMENTS', 'API_SPEC', 'ERD'])
const designKindSchema = z.enum([
  'REQUIREMENT',
  'API_CONTRACT',
  'DATA_CONTRACT',
  'POLICY',
  'CONSTRAINT',
])
const draftSourceSchema = z.object({
  document_id: common.idSchema,
  document_type: documentTypeSchema,
  document_version_id: common.idSchema,
  version_no: z.number().int().min(1),
  location: z.string(),
  quote: z.string(),
})

export const planDraftResponseSchema = z.object({
  status: z.enum(['DRAFTED', 'INSUFFICIENT']),
  plan: z.object({
    goal: z.string(),
    included_scope: z.string().array(),
    excluded_scope: z.string().array(),
    approach: z.string(),
    completion_criteria: z.string().array(),
  }),
  evidence: draftSourceSchema.extend({ field: planDraftFieldSchema }).array(),
  // 글은 있지만 원문에서 확인된 인용이 없는 칸
  unsupported_fields: planDraftFieldSchema.array(),
  // 현재 기준의 승인 항목만 온다. 버전 ID 는 저장할 때 그대로 design_refs 에 쓴다.
  design_ref_candidates: z
    .object({
      design_item_id: common.idSchema,
      design_item_version_id: common.idSchema,
      key: z.string(),
      kind: designKindSchema,
      title: z.string(),
      reason: z.string(),
    })
    .array(),
  // 문서에는 있으나 승인된 설계 항목에 없는 내용. 변경 제안(CREATE)으로 넘길 수 있다.
  new_design_item_candidates: z
    .object({
      kind: designKindSchema,
      content: common.structuredContentSchema,
      rationale: z.string(),
      evidence: draftSourceSchema.array(),
    })
    .array(),
  unlinked: z.object({ text: z.string() }).array(),
  baseline_id: common.idSchema,
})
export type PlanDraft = z.infer<typeof planDraftResponseSchema>

// S15P31A108-400: 제안은 서버에 보관하며 승인 전에는 작업 계획을 바꾸지 않는다.
export const planRevisionProposalSchema = z.object({
  id: common.idSchema,
  baseline_id: common.idSchema,
  source_validation_id: common.idSchema,
  expected_revision_no: z.number().int(),
  expected_lock_version: z.number().int(),
  before_plan: common.taskPlanInputSchema,
  proposed_plan: common.taskPlanInputSchema,
  changes: z
    .object({
      field: planDraftFieldSchema,
      reason: z.string(),
      finding_ids: common.idSchema.array(),
    })
    .array(),
  evidence: draftSourceSchema.extend({ field: planDraftFieldSchema }).array(),
  expires_at: common.timeSchema,
})
export type PlanRevisionProposal = z.infer<typeof planRevisionProposalSchema>

export const applyPlanRevisionProposalResponseSchema = z.object({
  task: common.taskDetailSchema,
  validation: common.validationSchema,
})
