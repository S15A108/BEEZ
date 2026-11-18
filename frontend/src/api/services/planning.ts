import { z } from 'zod'
import * as schemas from '@/features/planning/schemas'
import { idSchema } from '@/api/contracts/common'
import { callEndpoint, type RequestOptions } from './request'

export const planningApi = {
  analysis: (
    projectId: string,
    documentId: string,
    query: schemas.AnalysisQuery,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.analysisResponseSchema,
      {
        method: 'GET',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/documents/${encodeURIComponent(String(idSchema.parse(documentId)))}/analysis`,
        params: schemas.analysisQuerySchema.parse(query),
      },
      options,
    ),
  retryAnalysis: (
    projectId: string,
    documentId: string,
    input: schemas.RetryAnalysisInput,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.retryAnalysisResponseSchema,
      {
        method: 'POST',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/documents/${encodeURIComponent(String(idSchema.parse(documentId)))}/analysis/retry`,
        data: schemas.retryAnalysisInputSchema.parse(input),
        idempotent: true,
      },
      options,
    ),
  questions: (
    projectId: string,
    query?: schemas.QuestionsQuery,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.questionsResponseSchema,
      {
        method: 'GET',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/planning-questions`,
        params: schemas.questionsQuerySchema.parse(query ?? {}),
      },
      options,
    ),
  answerQuestion: (
    projectId: string,
    questionId: string,
    input: schemas.AnswerQuestionInput,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.answerQuestionResponseSchema,
      {
        method: 'PATCH',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/planning-questions/${encodeURIComponent(String(idSchema.parse(questionId)))}`,
        data: schemas.answerQuestionInputSchema.parse(input),
      },
      options,
    ),
  generateDrafts: (
    projectId: string,
    input: schemas.GenerateDraftsInput,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.generateDraftsResponseSchema,
      {
        method: 'POST',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/design-drafts`,
        data: schemas.generateDraftsInputSchema.parse(input),
        idempotent: true,
      },
      options,
    ),
  drafts: (
    projectId: string,
    query?: schemas.DraftsQuery,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.draftsResponseSchema,
      {
        method: 'GET',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/design-drafts`,
        params: schemas.draftsQuerySchema.parse(query ?? {}),
      },
      options,
    ),
  adoptCandidate: (
    projectId: string,
    candidateId: string,
    input?: schemas.AdoptCandidateInput,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.adoptCandidateResponseSchema,
      {
        method: 'POST',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/design-draft-candidates/${encodeURIComponent(String(idSchema.parse(candidateId)))}/adopt`,
        data: schemas.adoptCandidateInputSchema.parse(input ?? {}),
      },
      options,
    ),
  adoptCandidates: (
    projectId: string,
    input: schemas.BatchCandidatesInput,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.batchCandidatesResponseSchema,
      {
        method: 'POST',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/design-draft-candidates/adopt`,
        data: schemas.batchCandidatesInputSchema.parse(input),
      },
      options,
    ),
  discardCandidates: (
    projectId: string,
    input: schemas.BatchCandidatesInput,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.batchCandidatesResponseSchema,
      {
        method: 'POST',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/design-draft-candidates/discard`,
        data: schemas.batchCandidatesInputSchema.parse(input),
      },
      options,
    ),
  discardCandidate: (
    projectId: string,
    candidateId: string,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.discardCandidateResponseSchema,
      {
        method: 'POST',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/design-draft-candidates/${encodeURIComponent(String(idSchema.parse(candidateId)))}/discard`,
      },
      options,
    ),
  items: (
    projectId: string,
    query?: schemas.ItemsQuery,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.itemsResponseSchema,
      {
        method: 'GET',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/design-items`,
        params: schemas.itemsQuerySchema.parse(query ?? {}),
      },
      options,
    ),
  createItem: (
    projectId: string,
    input: schemas.CreateItemInput,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.createItemResponseSchema,
      {
        method: 'POST',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/design-items`,
        data: schemas.createItemInputSchema.parse(input),
        idempotent: true,
      },
      options,
    ),
  updateItem: (
    itemId: string,
    input: schemas.UpdateItemInput,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.updateItemResponseSchema,
      {
        method: 'PATCH',
        url: `/human/design-items/${encodeURIComponent(String(idSchema.parse(itemId)))}`,
        data: schemas.updateItemInputSchema.parse(input),
      },
      options,
    ),
  removeItem: (
    itemId: string,
    query: schemas.RemoveItemQuery,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      z.void(),
      {
        method: 'DELETE',
        url: `/human/design-items/${encodeURIComponent(String(idSchema.parse(itemId)))}`,
        params: schemas.removeItemQuerySchema.parse(query),
      },
      options,
    ),
  publishBaseline: (
    input: schemas.PublishBaselineInput,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.publishBaselineResponseSchema,
      {
        method: 'POST',
        url: `/human/baselines`,
        data: schemas.publishBaselineInputSchema.parse(input),
        idempotent: true,
      },
      options,
    ),
}
