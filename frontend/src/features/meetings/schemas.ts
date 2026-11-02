// BEEZ REST v0.2 및 공통 v0.3: snake_case 응답을 그대로 검증한다.
import { z } from 'zod'
import * as common from '@/api/contracts/common'

export const createInputSchema = z.object({
  title: z.string(),
  change_proposal_id: common.idSchema.optional(),
  participant_member_ids: common.idSchema.array(),
})
export type CreateInput = z.infer<typeof createInputSchema>

export const createResponseSchema = z.object({
  id: common.idSchema,
  project_id: common.idSchema,
  title: z.string(),
  change_proposal_id: common.idSchema.optional(),
  participants: common.participantSchema.array(),
  created_at: common.timeSchema,
})
export type CreateResponse = z.infer<typeof createResponseSchema>

export const listQuerySchema = z.object({
  cursor: z.string().optional(),
  limit: z.number().int().min(1).max(100).optional(),
})
export type ListQuery = z.infer<typeof listQuerySchema>

export const listResponseSchema = common.pageSchema(common.meetingSchema)
export type ListResponse = z.infer<typeof listResponseSchema>

export const getResponseSchema = common.meetingSchema
export type GetResponse = z.infer<typeof getResponseSchema>

export const inviteInputSchema = z.object({
  member_ids: common.idSchema.array(),
})
export type InviteInput = z.infer<typeof inviteInputSchema>

export const inviteResponseSchema = z.object({
  id: common.idSchema,
  project_id: common.idSchema,
  title: z.string(),
  change_proposal_id: common.idSchema.optional(),
  participants: common.participantSchema.array(),
  created_at: common.timeSchema,
})
export type InviteResponse = z.infer<typeof inviteResponseSchema>

export const respondInputSchema = z.object({
  status: z.enum(['JOINED', 'DECLINED']),
})
export type RespondInput = z.infer<typeof respondInputSchema>

export const respondResponseSchema = z.object({
  id: common.idSchema,
  project_id: common.idSchema,
  title: z.string(),
  change_proposal_id: common.idSchema.optional(),
  participants: common.participantSchema.array(),
  created_at: common.timeSchema,
})
export type RespondResponse = z.infer<typeof respondResponseSchema>

export const addEntryInputSchema = z.object({
  text: z.string(),
})
export type AddEntryInput = z.infer<typeof addEntryInputSchema>

export const addEntryResponseSchema = z.object({
  id: common.idSchema,
  meeting_id: common.idSchema,
  kind: z.enum(['TEXT', 'BEE_ANALYSIS', 'MINUTES']),
  text: z.string(),
  author_account_id: common.idSchema.optional(),
  through_entry_id: common.idSchema.optional(),
  generation: common.generationSchema.optional(),
  created_at: common.timeSchema,
})
export type AddEntryResponse = z.infer<typeof addEntryResponseSchema>

export const analyzeInputSchema = z.object({
  through_entry_id: common.idSchema,
})
export type AnalyzeInput = z.infer<typeof analyzeInputSchema>

export const analyzeResponseSchema = z.object({
  origin: z.enum(['MODEL', 'TEMPLATE']),
  model: z.string().optional(),
  text: z.string(),
  sources: common.sourceSchema.array(),
  unresolved: z.string().array(),
  usage: common.usageSchema.array().optional(),
})
export type AnalyzeResponse = z.infer<typeof analyzeResponseSchema>

export const minutesInputSchema = z.object({
  through_entry_id: common.idSchema,
})
export type MinutesInput = z.infer<typeof minutesInputSchema>

export const minutesResponseSchema = z.object({
  entry: common.meetingEntrySchema,
  summary: z.string(),
  decisions: z.string().array(),
  action_items: common.actionItemSchema.array(),
  unresolved: z.string().array(),
})
export type MinutesResponse = z.infer<typeof minutesResponseSchema>

export const changeDraftInputSchema = z.object({
  minutes_entry_id: common.idSchema,
  document_id: common.idSchema,
  base_document_version_id: common.idSchema,
})
export type ChangeDraftInput = z.infer<typeof changeDraftInputSchema>

export const changeDraftResponseSchema = z.object({
  after_content: z.string(),
  generation: common.generationSchema,
})
export type ChangeDraftResponse = z.infer<typeof changeDraftResponseSchema>

export const entriesQuerySchema = z.object({
  cursor: z.string().optional(),
  limit: z.number().int().min(1).max(100).optional(),
})
export type EntriesQuery = z.infer<typeof entriesQuerySchema>

export const entriesResponseSchema = common.pageSchema(
  common.meetingEntrySchema,
)
export type EntriesResponse = z.infer<typeof entriesResponseSchema>
