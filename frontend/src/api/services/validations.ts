import * as schemas from '@/features/validations/schemas'
import { idSchema } from '@/api/contracts/common'
import { callEndpoint, type RequestOptions } from './request'

export const validationsApi = {
  create: (
    projectId: string,
    taskId: string,
    input: schemas.CreateInput,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.createResponseSchema,
      {
        method: 'POST',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/tasks/${encodeURIComponent(String(idSchema.parse(taskId)))}/validations`,
        data: schemas.createInputSchema.parse(input),
        idempotent: true,
      },
      options,
    ),
  get: (projectId: string, validationId: string, options?: RequestOptions) =>
    callEndpoint(
      schemas.getResponseSchema,
      {
        method: 'GET',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/validations/${encodeURIComponent(String(idSchema.parse(validationId)))}`,
      },
      options,
    ),
  list: (
    projectId: string,
    taskId: string,
    query?: schemas.ListQuery,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.listResponseSchema,
      {
        method: 'GET',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/tasks/${encodeURIComponent(String(idSchema.parse(taskId)))}/validations`,
        params: schemas.listQuerySchema.parse(query ?? {}),
      },
      options,
    ),
  retry: (
    projectId: string,
    validationId: string,
    input: schemas.RetryInput,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.retryResponseSchema,
      {
        method: 'POST',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/validations/${encodeURIComponent(String(idSchema.parse(validationId)))}/retry`,
        data: schemas.retryInputSchema.parse(input),
        idempotent: true,
      },
      options,
    ),
}
