// BEEZ REST v0.2 및 공통 v0.3: snake_case 응답을 그대로 검증한다.
import { z } from 'zod'
import * as common from '@/api/contracts/common'

export const createInvitationInputSchema = z.object({
  email: z.string().optional(),
})
export type CreateInvitationInput = z.infer<typeof createInvitationInputSchema>

export const createInvitationResponseSchema = z.object({
  invitation: common.invitationSchema,
  invite_url: z.string(),
})
export type CreateInvitationResponse = z.infer<
  typeof createInvitationResponseSchema
>

export const invitationsQuerySchema = z.object({
  cursor: z.string().optional(),
  limit: z.number().int().min(1).max(100).optional(),
})
export type InvitationsQuery = z.infer<typeof invitationsQuerySchema>

export const invitationsResponseSchema = common.pageSchema(
  common.invitationSchema,
)
export type InvitationsResponse = z.infer<typeof invitationsResponseSchema>

export const previewInvitationInputSchema = z.object({
  token: z.string(),
})
export type PreviewInvitationInput = z.infer<
  typeof previewInvitationInputSchema
>

export const previewInvitationResponseSchema = z.object({
  project_name: z.string(),
  expires_at: common.timeSchema,
  login_required: z.boolean(),
})
export type PreviewInvitationResponse = z.infer<
  typeof previewInvitationResponseSchema
>

export const acceptInvitationInputSchema = z.object({
  token: z.string(),
})
export type AcceptInvitationInput = z.infer<typeof acceptInvitationInputSchema>

export const acceptInvitationResponseSchema = z.object({
  project: common.projectSchema,
  member: common.memberSchema,
})
export type AcceptInvitationResponse = z.infer<
  typeof acceptInvitationResponseSchema
>

export const listQuerySchema = z.object({
  cursor: z.string().optional(),
  limit: z.number().int().min(1).max(100).optional(),
})
export type ListQuery = z.infer<typeof listQuerySchema>

export const listResponseSchema = common.pageSchema(common.memberSchema)
export type ListResponse = z.infer<typeof listResponseSchema>

export const updateInputSchema = z
  .object({
    domain: z.string().optional(),
    role_label: z.string().optional(),
    color: z.string().optional(),
  })
  .refine(
    (value) => Object.values(value).some((field) => field !== undefined),
    { message: '변경할 필드가 필요합니다.' },
  )
export type UpdateInput = z.infer<typeof updateInputSchema>

export const updateResponseSchema = z.object({
  id: common.idSchema,
  account_id: common.idSchema,
  name: z.string(),
  role: z.enum(['LEADER', 'MEMBER']),
  domain: z.string().optional(),
  role_label: z.string().optional(),
  color: z.string().optional(),
  agent_connected: z.boolean(),
})
export type UpdateResponse = z.infer<typeof updateResponseSchema>

export const transferInputSchema = z.object({
  target_member_id: common.idSchema,
})
export type TransferInput = z.infer<typeof transferInputSchema>

export const transferResponseSchema = z.object({
  members: common.memberSchema.array(),
})
export type TransferResponse = z.infer<typeof transferResponseSchema>

export const removeInputSchema = z.object({
  reassign_to_member_id: common.idSchema.optional(),
})
export type RemoveInput = z.infer<typeof removeInputSchema>

export const leaveInputSchema = z.object({
  reassign_to_member_id: common.idSchema.optional(),
})
export type LeaveInput = z.infer<typeof leaveInputSchema>
