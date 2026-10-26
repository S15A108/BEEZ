import * as schemas from '@/features/contexts/schemas'
import { idSchema } from '@/api/contracts/common'
import { callEndpoint, type RequestOptions } from './request'

export const contextsApi = {
  packet: (projectId: string, packetId: string, options?: RequestOptions) =>
    callEndpoint(
      schemas.packetResponseSchema,
      {
        method: 'GET',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/context-packets/${encodeURIComponent(String(idSchema.parse(packetId)))}`,
      },
      options,
    ),
  list: (
    projectId: string,
    query?: schemas.ListQuery,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.listResponseSchema,
      {
        method: 'GET',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/context-records`,
        params: schemas.listQuerySchema.parse(query ?? {}),
      },
      options,
    ),
  get: (projectId: string, recordId: string, options?: RequestOptions) =>
    callEndpoint(
      schemas.getResponseSchema,
      {
        method: 'GET',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/context-records/${encodeURIComponent(String(idSchema.parse(recordId)))}`,
      },
      options,
    ),
  create: (
    projectId: string,
    input: schemas.CreateInput,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.createResponseSchema,
      {
        method: 'POST',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/context-records`,
        data: schemas.createInputSchema.parse(input),
        idempotent: true,
      },
      options,
    ),
  suggestDesignReferences: (
    projectId: string,
    input: schemas.SuggestDesignReferencesInput,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.suggestDesignReferencesResponseSchema,
      {
        method: 'POST',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/design-reference-suggestions`,
        data: schemas.suggestDesignReferencesInputSchema.parse(input),
        idempotent: true,
      },
      options,
    ),
}
