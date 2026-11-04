// BEEZ REST v0.2 및 공통 v0.3: snake_case 응답을 그대로 검증한다.
import { z } from 'zod'
import * as common from '@/api/contracts/common'

export const registerInputSchema = z.object({
  email: z.string(),
  password: z.string(),
  name: z.string(),
  // ACC-A12에서 받은 1회용 토큰. 인증한 이메일과 email이 같아야 한다(PR #114).
  verified_token: z.string(),
})
export type RegisterInput = z.infer<typeof registerInputSchema>

// ACC-A11 가입 인증 코드 보내기 (PR #114). 응답은 201이다.
export const sendEmailVerificationInputSchema = z.object({
  email: z.string(),
})
export type SendEmailVerificationInput = z.infer<
  typeof sendEmailVerificationInputSchema
>

export const sendEmailVerificationResponseSchema = z.object({
  // 순번이 아닌 난수 문자열이라 십진 ID(idSchema)가 아니다(PR #114 2f3e1c9).
  verification_id: z.string().min(1),
  expires_at: common.timeSchema,
  resend_available_at: common.timeSchema,
})
export type SendEmailVerificationResponse = z.infer<
  typeof sendEmailVerificationResponseSchema
>

// ACC-A12 가입 인증 코드 확인 (PR #114)
export const confirmEmailVerificationInputSchema = z.object({
  code: z.string(),
})
export type ConfirmEmailVerificationInput = z.infer<
  typeof confirmEmailVerificationInputSchema
>

export const confirmEmailVerificationResponseSchema = z.object({
  verified_token: z.string(),
  email: z.string(),
  expires_at: common.timeSchema,
})
export type ConfirmEmailVerificationResponse = z.infer<
  typeof confirmEmailVerificationResponseSchema
>

// ACC-A12 400 verification_code_mismatch의 error.details
export const codeMismatchDetailsSchema = z.object({
  remaining_attempts: z.number().int(),
})

export const registerResponseSchema = z.object({
  id: common.idSchema,
  email: z.string(),
  name: z.string(),
  avatar: z.string().optional(),
  created_at: common.timeSchema,
})
export type RegisterResponse = z.infer<typeof registerResponseSchema>

export const loginInputSchema = z.object({
  email: z.string(),
  password: z.string(),
})
export type LoginInput = z.infer<typeof loginInputSchema>

export const loginResponseSchema = z.object({
  id: common.idSchema,
  email: z.string(),
  name: z.string(),
  avatar: z.string().optional(),
  created_at: common.timeSchema,
})
export type LoginResponse = z.infer<typeof loginResponseSchema>

export const meResponseSchema = z.object({
  id: common.idSchema,
  email: z.string(),
  name: z.string(),
  avatar: z.string().optional(),
  created_at: common.timeSchema,
})
export type MeResponse = z.infer<typeof meResponseSchema>

export const updateMeInputSchema = z
  .object({
    name: z.string().optional(),
    avatar: z.string().optional(),
  })
  .refine(
    (value) => Object.values(value).some((field) => field !== undefined),
    { message: '변경할 필드가 필요합니다.' },
  )
export type UpdateMeInput = z.infer<typeof updateMeInputSchema>

export const updateMeResponseSchema = z.object({
  id: common.idSchema,
  email: z.string(),
  name: z.string(),
  avatar: z.string().optional(),
  created_at: common.timeSchema,
})
export type UpdateMeResponse = z.infer<typeof updateMeResponseSchema>

export const changePasswordInputSchema = z.object({
  current_password: z.string(),
  new_password: z.string(),
})
export type ChangePasswordInput = z.infer<typeof changePasswordInputSchema>

export const forgotPasswordInputSchema = z.object({
  email: z.string(),
  // ACC-A07 수정안 요청: 가입 때 쓴 표시 이름(앞뒤 공백 제외)
  name: z.string(),
})
export type ForgotPasswordInput = z.infer<typeof forgotPasswordInputSchema>

export const forgotPasswordResponseSchema = z.object({
  message: z.string(),
})
export type ForgotPasswordResponse = z.infer<
  typeof forgotPasswordResponseSchema
>

export const verifyResetTokenInputSchema = z.object({
  token: z.string(),
})
export type VerifyResetTokenInput = z.infer<typeof verifyResetTokenInputSchema>

export const verifyResetTokenResponseSchema = z.object({
  valid: z.boolean(),
  expires_at: common.timeSchema,
})
export type VerifyResetTokenResponse = z.infer<
  typeof verifyResetTokenResponseSchema
>

export const resetPasswordInputSchema = z.object({
  token: z.string(),
  new_password: z.string(),
})
export type ResetPasswordInput = z.infer<typeof resetPasswordInputSchema>

export const withdrawInputSchema = z.object({
  password: z.string(),
})
export type WithdrawInput = z.infer<typeof withdrawInputSchema>
