import { z } from 'zod'
import * as schemas from '@/features/projects/schemas'
import { idSchema } from '@/api/contracts/common'
import { callEndpoint, type RequestOptions } from './request'

const service = {
  create: (input: schemas.CreateInput, options?: RequestOptions) =>
    callEndpoint(
      schemas.createResponseSchema,
      {
        method: 'POST',
        url: `/projects`,
        data: schemas.createInputSchema.parse(input),
        idempotent: true,
      },
      options,
    ),
  list: (query?: schemas.ListQuery, options?: RequestOptions) =>
    callEndpoint(
      schemas.listResponseSchema,
      {
        method: 'GET',
        url: `/projects`,
        params: schemas.listQuerySchema.parse(query ?? {}),
      },
      options,
    ),
  get: (projectId: string, options?: RequestOptions) =>
    callEndpoint(
      schemas.getResponseSchema,
      {
        method: 'GET',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}`,
      },
      options,
    ),
  update: (
    projectId: string,
    input?: schemas.UpdateInput,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.updateResponseSchema,
      {
        method: 'PATCH',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}`,
        data: schemas.updateInputSchema.parse(input ?? {}),
      },
      options,
    ),
  remove: (
    projectId: string,
    input: schemas.RemoveInput,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      z.void(),
      {
        method: 'DELETE',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}`,
        data: schemas.removeInputSchema.parse(input),
      },
      options,
    ),
  onboarding: (projectId: string, options?: RequestOptions) =>
    callEndpoint(
      schemas.onboardingResponseSchema,
      {
        method: 'GET',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/onboarding`,
      },
      options,
    ),
}

export const projectsApi = { ...service, delete: service.remove }
