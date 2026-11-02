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
  expires_at: common.timeSchema.optional(),
  login_required: z.boolean(),
  // MEM-A04 수정안 요청 필드(docs/api 01-backend-rest). 서버가 아직 안 줄 수 있어 모두 선택으로 받는다.
  inviter: z.object({ name: z.string(), email: z.string() }).optional(),
  project_description: z.string().optional(),
  invited_email: z.string().optional(),
  account_matches: z.boolean().optional(),
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

// MEM-A07(PR #50): 생략한 필드는 그대로, ""는 지우기. domain·role_label은 앞뒤 공백을 뺀 뒤 50자 이하,
// color는 #RRGGBB(서버가 대문자로 저장). 역할(LEADER/MEMBER)은 이 요청으로 바뀌지 않는다.
const memberTextSchema = z
  .string()
  .trim()
  .max(50, '50자까지 쓸 수 있어요.')
  .optional()
export const updateInputSchema = z
  .object({
    domain: memberTextSchema,
    role_label: memberTextSchema,
    color: z
      .string()
      .regex(/^(#[0-9a-fA-F]{6})?$/, '색은 #RRGGBB 형식이에요.')
      .optional(),
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

// MEM-A08(PR #50): 권한이 바뀐 두 멤버를 [이전 팀장, 새 팀장] 순서로 준다.
export const transferResponseSchema = z.object({
  members: z.tuple([common.memberSchema, common.memberSchema]),
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
