// BEEZ REST v0.2 및 공통 v0.3: snake_case 응답을 그대로 검증한다.
import { z } from 'zod'
import * as common from '@/api/contracts/common'

// 회의 생성(MTG-A01)은 폐지 예정이다. 변경 제안 화면의 '회의로 논의'를 주석 처리해 지금은 부르는 화면이 없다(열린 질문 Q5).
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

export const inviteResponseSchema = common.meetingSchema
export type InviteResponse = z.infer<typeof inviteResponseSchema>

export const addEntryInputSchema = z.object({
  text: z.string(),
})
export type AddEntryInput = z.infer<typeof addEntryInputSchema>

export const addEntryResponseSchema = common.meetingEntrySchema
export type AddEntryResponse = z.infer<typeof addEntryResponseSchema>

// MTG-A11 비비 회의 정리: 정리에 쓸 마지막 기록(TEXT 또는 CONFLICT_BRIEF)까지의 대화로 정리를 만든다.
export const summarizeInputSchema = z.object({
  through_entry_id: common.idSchema,
})
export type SummarizeInput = z.infer<typeof summarizeInputSchema>

// MTG-A12 정리 수정 요청: 팀장이 고칠 점(1~4,000자)을 적어 최신 정리를 다시 만든다.
export const FEEDBACK_MAX = 4000
export const reviseSummaryInputSchema = z.object({
  feedback: z
    .string()
    .trim()
    .min(1, '고칠 점을 적어 주세요.')
    .max(FEEDBACK_MAX, `${FEEDBACK_MAX}자까지 쓸 수 있어요.`),
})
export type ReviseSummaryInput = z.infer<typeof reviseSummaryInputSchema>

export const summaryResponseSchema = common.meetingSummarySchema
export type SummaryResponse = z.infer<typeof summaryResponseSchema>

// MTG-A13 회의 끝내기: 팀장이 확인한 최신 정리로 변경 제안을 만들고 회의를 닫는다.
export const closeInputSchema = z.object({
  summary_id: common.idSchema,
})
export type CloseInput = z.infer<typeof closeInputSchema>

export const closeResponseSchema = z.object({
  meeting: common.meetingSchema,
  // 정리에 바꿀 문서가 없으면 제안을 만들지 않아 생략된다.
  change_proposal_id: common.idSchema.optional(),
  notified_member_ids: common.idSchema.array(),
})
export type CloseResponse = z.infer<typeof closeResponseSchema>

export const entriesQuerySchema = z.object({
  cursor: z.string().optional(),
  limit: z.number().int().min(1).max(100).optional(),
})
export type EntriesQuery = z.infer<typeof entriesQuerySchema>

export const entriesResponseSchema = common.pageSchema(
  common.meetingEntrySchema,
)
export type EntriesResponse = z.infer<typeof entriesResponseSchema>
