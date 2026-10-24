import { z } from 'zod'
import * as schemas from '@/features/auth/schemas'
import { callEndpoint, type RequestOptions } from './request'

export const authApi = {
  register: (input: schemas.RegisterInput, options?: RequestOptions) =>
    callEndpoint(
      schemas.registerResponseSchema,
      {
        method: 'POST',
        url: `/auth/register`,
        data: schemas.registerInputSchema.parse(input),
        idempotent: true,
      },
      options,
    ),
  login: (input: schemas.LoginInput, options?: RequestOptions) =>
    callEndpoint(
      schemas.loginResponseSchema,
      {
        method: 'POST',
        url: `/auth/login`,
        data: schemas.loginInputSchema.parse(input),
      },
      options,
    ),
  logout: (options?: RequestOptions) =>
    callEndpoint(z.void(), { method: 'POST', url: `/auth/logout` }, options),
  me: (options?: RequestOptions) =>
    callEndpoint(
      schemas.meResponseSchema,
      { method: 'GET', url: `/me` },
      options,
    ),
  updateMe: (input?: schemas.UpdateMeInput, options?: RequestOptions) =>
    callEndpoint(
      schemas.updateMeResponseSchema,
      {
        method: 'PATCH',
        url: `/me`,
        data: schemas.updateMeInputSchema.parse(input ?? {}),
      },
      options,
    ),
  changePassword: (
    input: schemas.ChangePasswordInput,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      z.void(),
      {
        method: 'PUT',
        url: `/me/password`,
        data: schemas.changePasswordInputSchema.parse(input),
      },
      options,
    ),
  forgotPassword: (
    input: schemas.ForgotPasswordInput,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.forgotPasswordResponseSchema,
      {
        method: 'POST',
        url: `/auth/password/forgot`,
        data: schemas.forgotPasswordInputSchema.parse(input),
      },
      options,
    ),
  verifyResetToken: (
    input: schemas.VerifyResetTokenInput,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.verifyResetTokenResponseSchema,
      {
        method: 'POST',
        url: `/auth/password/reset/verify`,
        data: schemas.verifyResetTokenInputSchema.parse(input),
      },
      options,
    ),
  resetPassword: (
    input: schemas.ResetPasswordInput,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      z.void(),
      {
        method: 'POST',
        url: `/auth/password/reset`,
        data: schemas.resetPasswordInputSchema.parse(input),
        idempotent: true,
      },
      options,
    ),
  withdraw: (input: schemas.WithdrawInput, options?: RequestOptions) =>
    callEndpoint(
      z.void(),
      {
        method: 'POST',
        url: `/me/withdraw`,
        data: schemas.withdrawInputSchema.parse(input),
        idempotent: true,
      },
      options,
    ),
}
