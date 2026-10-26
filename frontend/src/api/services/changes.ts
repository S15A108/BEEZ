import * as schemas from '@/features/changes/schemas'
import { idSchema } from '@/api/contracts/common'
import { callEndpoint, type RequestOptions } from './request'

export const changesApi = {
  create: (
    projectId: string,
    input: schemas.CreateInput,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.createResponseSchema,
      {
        method: 'POST',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/change-proposals`,
        data: schemas.createInputSchema.parse(input),
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
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/change-proposals`,
        params: schemas.listQuerySchema.parse(query ?? {}),
      },
      options,
    ),
  get: (projectId: string, proposalId: string, options?: RequestOptions) =>
    callEndpoint(
      schemas.getResponseSchema,
      {
        method: 'GET',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/change-proposals/${encodeURIComponent(String(idSchema.parse(proposalId)))}`,
      },
      options,
    ),
  update: (
    projectId: string,
    proposalId: string,
    input: schemas.UpdateInput,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.updateResponseSchema,
      {
        method: 'PUT',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/change-proposals/${encodeURIComponent(String(idSchema.parse(proposalId)))}`,
        data: schemas.updateInputSchema.parse(input),
      },
      options,
    ),
  impact: (projectId: string, proposalId: string, options?: RequestOptions) =>
    callEndpoint(
      schemas.impactResponseSchema,
      {
        method: 'GET',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/change-proposals/${encodeURIComponent(String(idSchema.parse(proposalId)))}/impact`,
      },
      options,
    ),
  retryImpact: (
    projectId: string,
    proposalId: string,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.retryImpactResponseSchema,
      {
        method: 'POST',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/change-proposals/${encodeURIComponent(String(idSchema.parse(proposalId)))}/impact/retry`,
        idempotent: true,
      },
      options,
    ),
  generateDraft: (
    projectId: string,
    input: schemas.GenerateDraftInput,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.generateDraftResponseSchema,
      {
        method: 'POST',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/change-drafts`,
        data: schemas.generateDraftInputSchema.parse(input),
        idempotent: true,
      },
      options,
    ),
  review: (
    projectId: string,
    proposalId: string,
    input: schemas.ReviewInput,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.reviewResponseSchema,
      {
        method: 'POST',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/change-proposals/${encodeURIComponent(String(idSchema.parse(proposalId)))}/review`,
        data: schemas.reviewInputSchema.parse(input),
        idempotent: true,
      },
      options,
    ),
  approve: (
    projectId: string,
    proposalId: string,
    input: schemas.ApproveInput,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.approveResponseSchema,
      {
        method: 'POST',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/change-proposals/${encodeURIComponent(String(idSchema.parse(proposalId)))}/approve`,
        data: schemas.approveInputSchema.parse(input),
        idempotent: true,
      },
      options,
    ),
  reject: (
    projectId: string,
    proposalId: string,
    input: schemas.RejectInput,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.rejectResponseSchema,
      {
        method: 'POST',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/change-proposals/${encodeURIComponent(String(idSchema.parse(proposalId)))}/reject`,
        data: schemas.rejectInputSchema.parse(input),
        idempotent: true,
      },
      options,
    ),
  approval: (projectId: string, proposalId: string, options?: RequestOptions) =>
    callEndpoint(
      schemas.approvalResponseSchema,
      {
        method: 'GET',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/change-proposals/${encodeURIComponent(String(idSchema.parse(proposalId)))}/approval`,
      },
      options,
    ),
  comment: (
    projectId: string,
    proposalId: string,
    input: schemas.CommentInput,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.commentResponseSchema,
      {
        method: 'POST',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/change-proposals/${encodeURIComponent(String(idSchema.parse(proposalId)))}/comments`,
        data: schemas.commentInputSchema.parse(input),
        idempotent: true,
      },
      options,
    ),
  comments: (
    projectId: string,
    proposalId: string,
    query?: schemas.CommentsQuery,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.commentsResponseSchema,
      {
        method: 'GET',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/change-proposals/${encodeURIComponent(String(idSchema.parse(proposalId)))}/comments`,
        params: schemas.commentsQuerySchema.parse(query ?? {}),
      },
      options,
    ),
}
