import { z } from 'zod'
import * as schemas from '@/features/members/schemas'
import { idSchema } from '@/api/contracts/common'
import { callEndpoint, type RequestOptions } from './request'

const service = {
  createInvitation: (
    projectId: string,
    input?: schemas.CreateInvitationInput,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.createInvitationResponseSchema,
      {
        method: 'POST',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/invitations`,
        data: schemas.createInvitationInputSchema.parse(input ?? {}),
        idempotent: true,
      },
      options,
    ),
  invitations: (
    projectId: string,
    query?: schemas.InvitationsQuery,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.invitationsResponseSchema,
      {
        method: 'GET',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/invitations`,
        params: schemas.invitationsQuerySchema.parse(query ?? {}),
      },
      options,
    ),
  revokeInvitation: (
    projectId: string,
    invitationId: string,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      z.void(),
      {
        method: 'DELETE',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/invitations/${encodeURIComponent(String(idSchema.parse(invitationId)))}`,
      },
      options,
    ),
  previewInvitation: (
    input: schemas.PreviewInvitationInput,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.previewInvitationResponseSchema,
      {
        method: 'POST',
        url: `/invitations/preview`,
        data: schemas.previewInvitationInputSchema.parse(input),
      },
      options,
    ),
  acceptInvitation: (
    input: schemas.AcceptInvitationInput,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.acceptInvitationResponseSchema,
      {
        method: 'POST',
        url: `/invitations/accept`,
        data: schemas.acceptInvitationInputSchema.parse(input),
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
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/members`,
        params: schemas.listQuerySchema.parse(query ?? {}),
      },
      options,
    ),
  update: (
    projectId: string,
    memberId: string,
    input?: schemas.UpdateInput,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.updateResponseSchema,
      {
        method: 'PATCH',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/members/${encodeURIComponent(String(idSchema.parse(memberId)))}`,
        data: schemas.updateInputSchema.parse(input ?? {}),
      },
      options,
    ),
  transfer: (
    projectId: string,
    input: schemas.TransferInput,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.transferResponseSchema,
      {
        method: 'POST',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/leadership-transfer`,
        data: schemas.transferInputSchema.parse(input),
        idempotent: true,
      },
      options,
    ),
  remove: (
    projectId: string,
    memberId: string,
    input?: schemas.RemoveInput,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      z.void(),
      {
        method: 'POST',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/members/${encodeURIComponent(String(idSchema.parse(memberId)))}/remove`,
        data: schemas.removeInputSchema.parse(input ?? {}),
        idempotent: true,
      },
      options,
    ),
  leave: (
    projectId: string,
    input?: schemas.LeaveInput,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      z.void(),
      {
        method: 'POST',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/leave`,
        data: schemas.leaveInputSchema.parse(input ?? {}),
        idempotent: true,
      },
      options,
    ),
}

export const membersApi = { ...service, invite: service.createInvitation }
