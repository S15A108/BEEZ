// BEEZ REST v0.2 및 공통 v0.3: snake_case 응답을 그대로 검증한다.
import { z } from 'zod'
import * as common from '@/api/contracts/common'

// BEE-A01 찾는 범위. 기본 APPROVED. INCLUDE_DRAFTS는 아직 서버가 400으로 거절한다.
export const askScopeSchema = z.enum(['APPROVED', 'INCLUDE_DRAFTS'])
export type AskScope = z.infer<typeof askScopeSchema>

export const askInputSchema = z.object({
  question: z.string(),
  task_id: common.idSchema.optional(),
  scope: askScopeSchema.optional(),
})
export type AskInput = z.infer<typeof askInputSchema>

export const beeCitationSchema = z.object({
  n: z.number().int().positive(),
  document_id: common.idSchema,
  document_version_id: common.idSchema,
  version_no: z.number().int().positive(),
  title: z.string().min(1),
  locator: z
    .object({
      type: z.literal('text_lines'),
      start_line: z.number().int().positive(),
      end_line: z.number().int().positive(),
    })
    .refine((locator) => locator.end_line >= locator.start_line),
  quote: z
    .string()
    .min(1)
    .max(2000)
    .refine((value) => [...value].length <= 1000)
    .refine((value) => value.trim().length > 0 && value.trim() === value),
  section_title: z.string().min(1).optional(),
  claim: z.string().min(1).optional(),
})
export type BeeCitation = z.infer<typeof beeCitationSchema>

export const askResponseSchema = z
  .object({
    origin: z.enum(['MODEL', 'TEMPLATE']),
    model: z.string().optional(),
    text: z.string(),
    sources: common.sourceSchema.array(),
    unresolved: z.string().array(),
    usage: common.usageSchema.array().optional(),
    answer_id: common.idSchema,
    citations: beeCitationSchema.array().max(20),
    grounded: z.boolean(),
    mode: z.literal('APPROVED'),
    baseline_id: common.idSchema,
    document_version_ids: common.idSchema.array(),
    verification: z
      .object({
        level: z.enum(['NONE', 'TOKENS', 'ENTAILMENT']),
        claims: z.number().int().nonnegative(),
        model: z.string().min(1).nullish(),
      })
      .optional(),
    notices: z.string().array().optional(),
  })
  .superRefine((answer, context) => {
    if (answer.citations.some((citation, index) => citation.n !== index + 1)) {
      context.addIssue({
        code: 'custom',
        path: ['citations'],
        message: '인용 번호가 올바르지 않습니다.',
      })
    }
    if (answer.grounded === false && answer.citations.length) {
      context.addIssue({
        code: 'custom',
        path: ['grounded'],
        message: '근거 상태가 일치하지 않습니다.',
      })
    }
  })
export type AskResponse = z.infer<typeof askResponseSchema>

export const suggestAssignmentsInputSchema = z.object({
  task_ids: common.idSchema.array(),
})
export type SuggestAssignmentsInput = z.infer<
  typeof suggestAssignmentsInputSchema
>

export const suggestAssignmentsResponseSchema = z.object({
  suggestions: common.assignmentSuggestionSchema
    .extend({
      alternatives: z
        .object({ member_id: common.idSchema, reason: z.string().min(1) })
        .array(),
    })
    .array(),
  generation: common.generationSchema,
})
export type SuggestAssignmentsResponse = z.infer<
  typeof suggestAssignmentsResponseSchema
>

// BEE-A04 비비 현재 상태. 한가하면(IDLE) activity만 오고 나머지는 빠진다.
// 문서 검토의 target_id는 문서 ID, label은 서버가 만든 문구('문서 이름 검토 중', "'회의 제목' 진행 중")다.
export const statusResponseSchema = z.object({
  activity: z.enum(['DOCUMENT_REVIEW', 'MEETING', 'IDLE']),
  target_type: z.enum(['DOCUMENT', 'MEETING']).optional(),
  target_id: common.idSchema.optional(),
  label: z.string().optional(),
})
export type StatusResponse = z.infer<typeof statusResponseSchema>
