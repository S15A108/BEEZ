// BEEZ REST v0.2 및 공통 v0.3: snake_case 응답을 그대로 검증한다.
import { z } from 'zod'
import * as common from '@/api/contracts/common'

// BEE-A01 찾는 범위. 빠지면 서버가 APPROVED로 본다(명세 수정안 요청, dev 서버에 아직 없음).
export const askScopeSchema = z.enum(['APPROVED', 'INCLUDE_DRAFTS'])
export type AskScope = z.infer<typeof askScopeSchema>

export const askInputSchema = z.object({
  question: z.string(),
  task_id: common.idSchema.optional(),
  scope: askScopeSchema.optional(),
})
export type AskInput = z.infer<typeof askInputSchema>

export const askResponseSchema = z.object({
  origin: z.enum(['MODEL', 'TEMPLATE']),
  model: z.string().optional(),
  text: z.string(),
  sources: common.sourceSchema.array(),
  unresolved: z.string().array(),
  usage: common.usageSchema.array().optional(),
})
export type AskResponse = z.infer<typeof askResponseSchema>

export const suggestAssignmentsInputSchema = z.object({
  task_ids: common.idSchema.array(),
})
export type SuggestAssignmentsInput = z.infer<
  typeof suggestAssignmentsInputSchema
>

export const suggestAssignmentsResponseSchema = z.object({
  suggestions: common.assignmentSuggestionSchema.array(),
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
