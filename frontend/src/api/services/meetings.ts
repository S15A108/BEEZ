import { z } from 'zod'
import * as schemas from '@/features/meetings/schemas'
import { idSchema } from '@/api/contracts/common'
import { callEndpoint, type RequestOptions } from './request'

export const meetingsApi = {
  summary: (projectId: string, meetingId: string, throughEntryId: string) =>
    callEndpoint(schemas.summaryResponseSchema, {
      method: 'POST',
      url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/meetings/${encodeURIComponent(String(idSchema.parse(meetingId)))}/bee-summary`,
      data: { through_entry_id: idSchema.parse(throughEntryId) },
      idempotent: true,
    }),
  revisionDraft: (
    projectId: string,
    meetingId: string,
    input: schemas.RevisionDraftInput,
  ) =>
    callEndpoint(schemas.revisionDraftResponseSchema, {
      method: 'POST',
      url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/meetings/${encodeURIComponent(String(idSchema.parse(meetingId)))}/revision-drafts`,
      data: schemas.revisionDraftInputSchema.parse(input),
      idempotent: true,
    }),
  latestRevisionDraft: (projectId: string, meetingId: string) =>
    callEndpoint(schemas.revisionDraftResponseSchema, {
      method: 'GET',
      url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/meetings/${encodeURIComponent(String(idSchema.parse(meetingId)))}/revision-drafts/latest`,
    }),
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
  // MTG-A11: 팀장(회의 참여자)만. AI(SUMMARY)를 서버가 아직 거절하면 502 ai_unavailable 이다.
  summarize: (
    projectId: string,
    meetingId: string,
    input: schemas.SummarizeInput,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.summaryResponseSchema,
      {
        method: 'POST',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/meetings/${encodeURIComponent(String(idSchema.parse(meetingId)))}/bee-summary`,
        data: schemas.summarizeInputSchema.parse(input),
        idempotent: true,
      },
      options,
    ),
  // MTG-A15: 회의의 최신 정리를 구조화한 값으로 읽는다. 정리가 아직 없으면 404 not_found.
  latestSummary: (
    projectId: string,
    meetingId: string,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.summaryResponseSchema,
      {
        method: 'GET',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/meetings/${encodeURIComponent(String(idSchema.parse(meetingId)))}/bee-summary/latest`,
      },
      options,
    ),
  // MTG-A12: 최신 정리만 고칠 수 있다(아니면 409 summary_outdated). 새 정리가 최신이 되고 이전 정리는 기록에 남는다.
  reviseSummary: (
    projectId: string,
    meetingId: string,
    summaryId: string,
    input: schemas.ReviseSummaryInput,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.summaryResponseSchema,
      {
        method: 'POST',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/meetings/${encodeURIComponent(String(idSchema.parse(meetingId)))}/bee-summary/${encodeURIComponent(String(idSchema.parse(summaryId)))}/revise`,
        data: schemas.reviseSummaryInputSchema.parse(input),
        idempotent: true,
      },
      options,
    ),
  // MTG-A13: 팀장(회의 참여자)만. AI 를 부르지 않고, 정리에 바꿀 문서가 있으면 변경 제안을 만든다.
  close: (
    projectId: string,
    meetingId: string,
    input: schemas.CloseInput,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.closeResponseSchema,
      {
        method: 'POST',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/meetings/${encodeURIComponent(String(idSchema.parse(meetingId)))}/close`,
        data: schemas.closeInputSchema.parse(input),
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
