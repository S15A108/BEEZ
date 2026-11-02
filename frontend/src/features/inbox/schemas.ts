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

// ids 또는 all 중 하나. all=true면 본인 알림 전체(project_id가 있으면 그 프로젝트 범위)를 읽음 처리한다(명세 NTF-A02 수정안).
export const markReadInputSchema = z.union([
  z.object({ ids: common.idSchema.array() }),
  z.object({ all: z.literal(true), project_id: common.idSchema.optional() }),
])
export type MarkReadInput = z.infer<typeof markReadInputSchema>

export const markReadResponseSchema = z.object({
  updated_count: z.number().int(),
})
export type MarkReadResponse = z.infer<typeof markReadResponseSchema>
