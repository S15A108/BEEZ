// BEEZ REST v0.2 및 공통 v0.3: snake_case 응답을 그대로 검증한다.
import { z } from 'zod'
import * as common from '@/api/contracts/common'

export const createInputSchema = z.object({
  name: z.string(),
  description: z.string().optional(),
})
export type CreateInput = z.infer<typeof createInputSchema>

export const createResponseSchema = z.object({
  id: common.idSchema,
  name: z.string(),
  description: z.string().optional(),
  my_role: z.enum(['LEADER', 'MEMBER']),
  current_baseline: common.baselineSummarySchema.optional(),
  created_at: common.timeSchema,
  updated_at: common.timeSchema,
})
export type CreateResponse = z.infer<typeof createResponseSchema>

export const listQuerySchema = z.object({
  cursor: z.string().optional(),
  limit: z.number().int().min(1).max(100).optional(),
})
export type ListQuery = z.infer<typeof listQuerySchema>

export const listResponseSchema = common.pageSchema(common.projectSchema)
export type ListResponse = z.infer<typeof listResponseSchema>

export const getResponseSchema = z.object({
  id: common.idSchema,
  name: z.string(),
  description: z.string().optional(),
  my_role: z.enum(['LEADER', 'MEMBER']),
  current_baseline: common.baselineSummarySchema.optional(),
  created_at: common.timeSchema,
  updated_at: common.timeSchema,
})
export type GetResponse = z.infer<typeof getResponseSchema>

export const updateInputSchema = z
  .object({
    name: z.string().optional(),
    description: z.string().optional(),
  })
  .refine(
    (value) => Object.values(value).some((field) => field !== undefined),
    { message: '변경할 필드가 필요합니다.' },
  )
export type UpdateInput = z.infer<typeof updateInputSchema>

export const updateResponseSchema = z.object({
  id: common.idSchema,
  name: z.string(),
  description: z.string().optional(),
  my_role: z.enum(['LEADER', 'MEMBER']),
  current_baseline: common.baselineSummarySchema.optional(),
  created_at: common.timeSchema,
  updated_at: common.timeSchema,
})
export type UpdateResponse = z.infer<typeof updateResponseSchema>

export const removeInputSchema = z.object({
  confirm_name: z.string(),
})
export type RemoveInput = z.infer<typeof removeInputSchema>

export const onboardingResponseSchema = z.object({
  required_documents: common.requiredDocumentSchema.array(),
  baseline_published: z.boolean(),
  member_count: z.number().int(),
  my_agent_connected: z.boolean(),
})
export type OnboardingResponse = z.infer<typeof onboardingResponseSchema>
