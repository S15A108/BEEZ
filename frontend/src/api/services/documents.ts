import { z } from 'zod'
import * as schemas from '@/features/documents/schemas'
import {
  beeReviewRequestSchema,
  documentBeeReviewSchema,
  documentReviewSubmissionDetailSchema,
  documentReviewSubmissionSchema,
  idSchema,
} from '@/api/contracts/common'
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
        idempotent: true,
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
  workingCopy: (
    projectId: string,
    documentId: string,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.workingCopyResponseSchema,
      {
        method: 'GET',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/documents/${encodeURIComponent(String(idSchema.parse(documentId)))}/working-copy`,
      },
      options,
    ),
  updateWorkingCopy: (
    projectId: string,
    documentId: string,
    input: schemas.UpdateWorkingCopyInput,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.workingCopyResponseSchema,
      {
        method: 'PATCH',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/documents/${encodeURIComponent(String(idSchema.parse(documentId)))}/working-copy`,
        data: schemas.updateWorkingCopyInputSchema.parse(input),
      },
      options,
    ),
  reuploadWorkingCopy: (
    projectId: string,
    documentId: string,
    input: schemas.ReuploadWorkingCopyInput,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.workingCopyResponseSchema,
      {
        method: 'POST',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/documents/${encodeURIComponent(String(idSchema.parse(documentId)))}/working-copy/file`,
        data: toFormData(schemas.reuploadWorkingCopyInputSchema.parse(input)),
      },
      options,
    ),
  // 새 검토는 접수(202)되면 요청 상태(RUNNING)를, 같은 키로 이미 끝난 검토는 201로 검토 결과를 돌려준다.
  // 검토 결과에는 id가 있고 요청 상태에는 없어서 모양으로 가른다. 받는 쪽은 'id' in 결과로 구분한다(S15P31A108-391).
  requestBeeReview: (
    projectId: string,
    documentId: string,
    input: schemas.RequestBeeReviewInput,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      z.union([documentBeeReviewSchema, beeReviewRequestSchema]),
      {
        method: 'POST',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/documents/${encodeURIComponent(String(idSchema.parse(documentId)))}/bee-reviews`,
        data: schemas.requestBeeReviewInputSchema.parse(input),
        idempotent: true,
      },
      options,
    ),
  decideSuggestion: (
    projectId: string,
    documentId: string,
    reviewId: string,
    suggestionId: string,
    input: schemas.DecideSuggestionInput,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.workingCopyResponseSchema,
      {
        method: 'PATCH',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/documents/${encodeURIComponent(String(idSchema.parse(documentId)))}/bee-reviews/${encodeURIComponent(String(idSchema.parse(reviewId)))}/suggestions/${encodeURIComponent(String(idSchema.parse(suggestionId)))}`,
        data: schemas.decideSuggestionInputSchema.parse(input),
      },
      options,
    ),
  discussSuggestion: (
    projectId: string,
    documentId: string,
    reviewId: string,
    suggestionId: string,
    input: schemas.DiscussSuggestionInput,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.discussSuggestionResponseSchema,
      {
        method: 'POST',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/documents/${encodeURIComponent(String(idSchema.parse(documentId)))}/bee-reviews/${encodeURIComponent(String(idSchema.parse(reviewId)))}/suggestions/${encodeURIComponent(String(idSchema.parse(suggestionId)))}/messages`,
        data: schemas.discussSuggestionInputSchema.parse(input),
        idempotent: true,
      },
      options,
    ),
  requestBeeSuggestion: (
    projectId: string,
    documentId: string,
    reviewId: string,
    input: schemas.RequestBeeSuggestionInput,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.requestBeeSuggestionResponseSchema,
      {
        method: 'POST',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/documents/${encodeURIComponent(String(idSchema.parse(documentId)))}/bee-reviews/${encodeURIComponent(String(idSchema.parse(reviewId)))}/requests`,
        data: schemas.requestBeeSuggestionInputSchema.parse(input),
        idempotent: true,
      },
      options,
    ),
  submitReview: (
    projectId: string,
    documentId: string,
    input: schemas.SubmitReviewInput,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      documentReviewSubmissionSchema,
      {
        method: 'POST',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/documents/${encodeURIComponent(String(idSchema.parse(documentId)))}/review-submissions`,
        data: schemas.submitReviewInputSchema.parse(input),
        idempotent: true,
      },
      options,
    ),
  reviewSubmissions: (
    projectId: string,
    query?: schemas.ReviewSubmissionsQuery,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.reviewSubmissionsResponseSchema,
      {
        method: 'GET',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/document-review-submissions`,
        params: schemas.reviewSubmissionsQuerySchema.parse(query ?? {}),
      },
      options,
    ),
  reviewSubmission: (
    projectId: string,
    documentId: string,
    submissionId: string,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      documentReviewSubmissionDetailSchema,
      {
        method: 'GET',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/documents/${encodeURIComponent(String(idSchema.parse(documentId)))}/review-submissions/${encodeURIComponent(String(idSchema.parse(submissionId)))}`,
      },
      options,
    ),
  decideSubmission: (
    projectId: string,
    documentId: string,
    submissionId: string,
    input: schemas.DecideSubmissionInput,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      documentReviewSubmissionSchema,
      {
        method: 'POST',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/documents/${encodeURIComponent(String(idSchema.parse(documentId)))}/review-submissions/${encodeURIComponent(String(idSchema.parse(submissionId)))}/decision`,
        data: schemas.decideSubmissionInputSchema.parse(input),
      },
      options,
    ),
  // DOC-A25: 승인된 적 없는 문서를 등록자·팀장이 취소한다. 지우지 않고 목록에서 치우며 같은 종류를 다시 올릴 수 있다.
  discardDocument: (
    projectId: string,
    documentId: string,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.discardDocumentResponseSchema,
      {
        method: 'POST',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/documents/${encodeURIComponent(String(idSchema.parse(documentId)))}/discard`,
        idempotent: true,
      },
      options,
    ),
  cancelSubmission: (
    projectId: string,
    documentId: string,
    submissionId: string,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.workingCopyResponseSchema,
      {
        method: 'POST',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/documents/${encodeURIComponent(String(idSchema.parse(documentId)))}/review-submissions/${encodeURIComponent(String(idSchema.parse(submissionId)))}/withdraw`,
        idempotent: true,
      },
      options,
    ),
}

export const documentsApi = { ...service, content: service.get }
