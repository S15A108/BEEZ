import { z } from 'zod'
import * as schemas from '@/features/meetings/schemas'
import { idSchema } from '@/api/contracts/common'
import { callEndpoint, type RequestOptions } from './request'

export const meetingsApi = {
  create: (
    projectId: string,
    input: schemas.CreateInput,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.createResponseSchema,
      {
        method: 'POST',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/meetings`,
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
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/meetings`,
        params: schemas.listQuerySchema.parse(query ?? {}),
      },
      options,
    ),
  get: (projectId: string, meetingId: string, options?: RequestOptions) =>
    callEndpoint(
      schemas.getResponseSchema,
      {
        method: 'GET',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/meetings/${encodeURIComponent(String(idSchema.parse(meetingId)))}`,
      },
      options,
    ),
  invite: (
    projectId: string,
    meetingId: string,
    input: schemas.InviteInput,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.inviteResponseSchema,
      {
        method: 'POST',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/meetings/${encodeURIComponent(String(idSchema.parse(meetingId)))}/participants`,
        data: schemas.inviteInputSchema.parse(input),
        idempotent: true,
      },
      options,
    ),
  // MTG-A14(PR #54, 수정안 요청): 팀장만 뺀다. 팀장 자신은 409 leader_not_removable.
  removeParticipant: (
    projectId: string,
    meetingId: string,
    memberId: string,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      z.void(),
      {
        method: 'DELETE',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/meetings/${encodeURIComponent(String(idSchema.parse(meetingId)))}/participants/${encodeURIComponent(String(idSchema.parse(memberId)))}`,
        idempotent: true,
      },
      options,
    ),
  respond: (
    projectId: string,
    meetingId: string,
    input: schemas.RespondInput,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.respondResponseSchema,
      {
        method: 'PATCH',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/meetings/${encodeURIComponent(String(idSchema.parse(meetingId)))}/participation`,
        data: schemas.respondInputSchema.parse(input),
      },
      options,
    ),
  addEntry: (
    projectId: string,
    meetingId: string,
    input: schemas.AddEntryInput,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.addEntryResponseSchema,
      {
        method: 'POST',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/meetings/${encodeURIComponent(String(idSchema.parse(meetingId)))}/entries`,
        data: schemas.addEntryInputSchema.parse(input),
        idempotent: true,
      },
      options,
    ),
  analyze: (
    projectId: string,
    meetingId: string,
    input: schemas.AnalyzeInput,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.analyzeResponseSchema,
      {
        method: 'POST',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/meetings/${encodeURIComponent(String(idSchema.parse(meetingId)))}/bee-analysis`,
        data: schemas.analyzeInputSchema.parse(input),
        idempotent: true,
      },
      options,
    ),
  minutes: (
    projectId: string,
    meetingId: string,
    input: schemas.MinutesInput,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.minutesResponseSchema,
      {
        method: 'POST',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/meetings/${encodeURIComponent(String(idSchema.parse(meetingId)))}/minutes`,
        data: schemas.minutesInputSchema.parse(input),
        idempotent: true,
      },
      options,
    ),
  changeDraft: (
    projectId: string,
    meetingId: string,
    input: schemas.ChangeDraftInput,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.changeDraftResponseSchema,
      {
        method: 'POST',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/meetings/${encodeURIComponent(String(idSchema.parse(meetingId)))}/change-drafts`,
        data: schemas.changeDraftInputSchema.parse(input),
        idempotent: true,
      },
      options,
    ),
  entries: (
    projectId: string,
    meetingId: string,
    query?: schemas.EntriesQuery,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.entriesResponseSchema,
      {
        method: 'GET',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/meetings/${encodeURIComponent(String(idSchema.parse(meetingId)))}/entries`,
        params: schemas.entriesQuerySchema.parse(query ?? {}),
      },
      options,
    ),
}
