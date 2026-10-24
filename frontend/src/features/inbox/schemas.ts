// BEEZ REST v0.2 및 공통 v0.3: snake_case 응답을 그대로 검증한다.
import { z } from 'zod'
import * as common from '@/api/contracts/common'

export const listQuerySchema = z.object({
  project_id: common.idSchema.optional(),
  unread: z.boolean().optional(),
  cursor: z.string().optional(),
  limit: z.number().int().min(1).max(100).optional(),
})
export type ListQuery = z.infer<typeof listQuerySchema>

export const listResponseSchema = common.pageSchema(common.notificationSchema)
export type ListResponse = z.infer<typeof listResponseSchema>

export const markReadInputSchema = z.object({
  ids: common.idSchema.array(),
})
export type MarkReadInput = z.infer<typeof markReadInputSchema>

export const markReadResponseSchema = z.object({
  updated_count: z.number().int(),
})
export type MarkReadResponse = z.infer<typeof markReadResponseSchema>
