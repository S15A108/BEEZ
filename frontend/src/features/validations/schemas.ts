// BEEZ REST v0.2 및 공통 v0.3: snake_case 응답을 그대로 검증한다.
import { z } from 'zod'
import * as common from '@/api/contracts/common'

export const createInputSchema = z.object({
  plan_revision_id: common.idSchema,
  expected_lock_version: z.number().int(),
})
export type CreateInput = z.infer<typeof createInputSchema>

export const createResponseSchema = common.validationSchema
export type CreateResponse = z.infer<typeof createResponseSchema>

export const getResponseSchema = common.validationSchema
export type GetResponse = z.infer<typeof getResponseSchema>

export const listQuerySchema = z.object({
  cursor: z.string().optional(),
  limit: z.number().int().min(1).max(100).optional(),
})
export type ListQuery = z.infer<typeof listQuerySchema>

export const listResponseSchema = common.pageSchema(common.validationSchema)
export type ListResponse = z.infer<typeof listResponseSchema>

export const retryInputSchema = z.object({
  expected_attempt: z.number().int(),
})
export type RetryInput = z.infer<typeof retryInputSchema>

export const retryResponseSchema = common.validationSchema
export type RetryResponse = z.infer<typeof retryResponseSchema>
