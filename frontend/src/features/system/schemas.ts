// BEEZ REST v0.2 및 공통 v0.3: snake_case 응답을 그대로 검증한다.
import { z } from 'zod'
import * as common from '@/api/contracts/common'

export const pingResponseSchema = z.object({
  ok: z.boolean(),
  service: z.string(),
  started_at: common.timeSchema,
  server_time: common.timeSchema,
})
export type PingResponse = z.infer<typeof pingResponseSchema>

export const statusResponseSchema = z.object({
  ok: z.boolean(),
  service: z.string(),
  ai_reachable: z.boolean(),
  time: common.timeSchema,
})
export type StatusResponse = z.infer<typeof statusResponseSchema>
