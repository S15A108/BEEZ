import { z } from 'zod'
import * as schemas from '@/features/documents/schemas'
import { idSchema } from '@/api/contracts/common'
import { callEndpoint, toFormData, type RequestOptions } from './request'

const service = {
  upload: (
    projectId: string,
    input: schemas.UploadInput,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.uploadResponseSchema,
      {
        method: 'POST',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/documents`,
        data: toFormData(schemas.uploadInputSchema.parse(input)),
        idempotent: true,
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
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/documents`,
        params: schemas.listQuerySchema.parse(query ?? {}),
      },
      options,
    ),
  get: (
    projectId: string,
    documentId: string,
    query?: schemas.GetQuery,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.getResponseSchema,
      {
        method: 'GET',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/documents/${encodeURIComponent(String(idSchema.parse(documentId)))}`,
        params: schemas.getQuerySchema.parse(query ?? {}),
      },
      options,
    ),
  download: (
    projectId: string,
    documentId: string,
    versionNo: number,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      z.instanceof(Blob),
      {
        method: 'GET',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/documents/${encodeURIComponent(String(idSchema.parse(documentId)))}/versions/${encodeURIComponent(String(z.number().int().parse(versionNo)))}/file`,
        responseType: 'blob',
      },
      options,
    ),
  versions: (
    projectId: string,
    documentId: string,
    query?: schemas.VersionsQuery,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.versionsResponseSchema,
      {
        method: 'GET',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/documents/${encodeURIComponent(String(idSchema.parse(documentId)))}/versions`,
        params: schemas.versionsQuerySchema.parse(query ?? {}),
      },
      options,
    ),
  diff: (
    projectId: string,
    documentId: string,
    query: schemas.DiffQuery,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.diffResponseSchema,
      {
        method: 'GET',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/documents/${encodeURIComponent(String(idSchema.parse(documentId)))}/diff`,
        params: schemas.diffQuerySchema.parse(query),
      },
      options,
    ),
  update: (
    projectId: string,
    documentId: string,
    input: schemas.UpdateInput,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.updateResponseSchema,
      {
        method: 'PATCH',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/documents/${encodeURIComponent(String(idSchema.parse(documentId)))}`,
        data: schemas.updateInputSchema.parse(input),
      },
      options,
    ),
  baseline: (projectId: string, options?: RequestOptions) =>
    callEndpoint(
      schemas.baselineResponseSchema,
      {
        method: 'GET',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/baselines/current`,
      },
      options,
    ),
  baselines: (
    projectId: string,
    query?: schemas.BaselinesQuery,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.baselinesResponseSchema,
      {
        method: 'GET',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/baselines`,
        params: schemas.baselinesQuerySchema.parse(query ?? {}),
      },
      options,
    ),
  baselineVersion: (
    projectId: string,
    versionNo: number,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.baselineVersionResponseSchema,
      {
        method: 'GET',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/baselines/${encodeURIComponent(String(z.number().int().parse(versionNo)))}`,
      },
      options,
    ),
  search: (
    projectId: string,
    query: schemas.SearchQuery,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.searchResponseSchema,
      {
        method: 'GET',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/search`,
        params: schemas.searchQuerySchema.parse(query),
      },
      options,
    ),
  uploadDraft: (
    projectId: string,
    documentId: string,
    input: schemas.UploadDraftInput,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.uploadDraftResponseSchema,
      {
        method: 'POST',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/documents/${encodeURIComponent(String(idSchema.parse(documentId)))}/draft-versions`,
        data: toFormData(schemas.uploadDraftInputSchema.parse(input)),
        idempotent: true,
      },
      options,
    ),
}

export const documentsApi = { ...service, content: service.get }
