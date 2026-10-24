import * as schemas from '@/features/tasks/schemas'
import { idSchema } from '@/api/contracts/common'
import { callEndpoint, type RequestOptions } from './request'

export const tasksApi = {
  create: (
    projectId: string,
    input: schemas.CreateInput,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.createResponseSchema,
      {
        method: 'POST',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/tasks`,
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
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/tasks`,
        params: schemas.listQuerySchema.parse(query ?? {}),
      },
      options,
    ),
  get: (projectId: string, taskId: string, options?: RequestOptions) =>
    callEndpoint(
      schemas.getResponseSchema,
      {
        method: 'GET',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/tasks/${encodeURIComponent(String(idSchema.parse(taskId)))}`,
      },
      options,
    ),
  savePlan: (
    projectId: string,
    taskId: string,
    input: schemas.SavePlanInput,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.savePlanResponseSchema,
      {
        method: 'POST',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/tasks/${encodeURIComponent(String(idSchema.parse(taskId)))}/plan-revisions`,
        data: schemas.savePlanInputSchema.parse(input),
        idempotent: true,
      },
      options,
    ),
  planRevisions: (
    projectId: string,
    taskId: string,
    query?: schemas.PlanRevisionsQuery,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.planRevisionsResponseSchema,
      {
        method: 'GET',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/tasks/${encodeURIComponent(String(idSchema.parse(taskId)))}/plan-revisions`,
        params: schemas.planRevisionsQuerySchema.parse(query ?? {}),
      },
      options,
    ),
  assign: (
    projectId: string,
    taskId: string,
    input: schemas.AssignInput,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.assignResponseSchema,
      {
        method: 'PATCH',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/tasks/${encodeURIComponent(String(idSchema.parse(taskId)))}/assignee`,
        data: schemas.assignInputSchema.parse(input),
      },
      options,
    ),
  state: (
    projectId: string,
    taskId: string,
    input: schemas.StateInput,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.stateResponseSchema,
      {
        method: 'POST',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/tasks/${encodeURIComponent(String(idSchema.parse(taskId)))}/state-events`,
        data: schemas.stateInputSchema.parse(input),
        idempotent: true,
      },
      options,
    ),
}
