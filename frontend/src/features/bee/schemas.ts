// BEEZ REST v0.2 및 공통 v0.3: snake_case 응답을 그대로 검증한다.
import { z } from 'zod'
import * as common from '@/api/contracts/common'

export const askInputSchema = z.object({
  question: z.string(),
  task_id: common.idSchema.optional(),
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
