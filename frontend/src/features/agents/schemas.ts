// BEEZ REST v0.2 및 공통 v0.3: snake_case 응답을 그대로 검증한다.
import { z } from 'zod'
import * as common from '@/api/contracts/common'

export const issueTokenInputSchema = z.object({
  label: z.string().optional(),
})
export type IssueTokenInput = z.infer<typeof issueTokenInputSchema>

export const issueTokenResponseSchema = z.object({
  token_info: common.agentTokenSchema,
  token: z.string(),
  mcp_url: z.string(),
  claude_md_snippet: z.string(),
})
export type IssueTokenResponse = z.infer<typeof issueTokenResponseSchema>

export const myTokensQuerySchema = z.object({
  project_id: common.idSchema.optional(),
  cursor: z.string().optional(),
  limit: z.number().int().min(1).max(100).optional(),
})
export type MyTokensQuery = z.infer<typeof myTokensQuerySchema>

export const myTokensResponseSchema = common.pageSchema(common.agentTokenSchema)
export type MyTokensResponse = z.infer<typeof myTokensResponseSchema>

export const connectionGuideResponseSchema = z.object({
  mcp_url: z.string(),
  claude_md_snippet: z.string(),
  steps: z.string().array(),
})
export type ConnectionGuideResponse = z.infer<
  typeof connectionGuideResponseSchema
>

export const listQuerySchema = z.object({
  cursor: z.string().optional(),
  limit: z.number().int().min(1).max(100).optional(),
})
export type ListQuery = z.infer<typeof listQuerySchema>

export const listResponseSchema = common.pageSchema(
  common.agentConnectionSchema,
)
export type ListResponse = z.infer<typeof listResponseSchema>

export const getResponseSchema = z.object({
  id: common.idSchema,
  member_id: common.idSchema,
  connected: z.boolean(),
  last_report_at: common.timeSchema.optional(),
  status_check_required: z.boolean(),
})
export type GetResponse = z.infer<typeof getResponseSchema>
