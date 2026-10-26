import * as schemas from '@/features/dashboard/schemas'
import { idSchema } from '@/api/contracts/common'
import { callEndpoint, type RequestOptions } from './request'

export const dashboardApi = {
  documents: (projectId: string, options?: RequestOptions) =>
    callEndpoint(
      schemas.documentsResponseSchema,
      {
        method: 'GET',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/documents/summary`,
      },
      options,
    ),
  changes: (projectId: string, options?: RequestOptions) =>
    callEndpoint(
      schemas.changesResponseSchema,
      {
        method: 'GET',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/change-proposals/summary`,
      },
      options,
    ),
  tasks: (projectId: string, options?: RequestOptions) =>
    callEndpoint(
      schemas.tasksResponseSchema,
      {
        method: 'GET',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/tasks/summary`,
      },
      options,
    ),
  validations: (projectId: string, options?: RequestOptions) =>
    callEndpoint(
      schemas.validationsResponseSchema,
      {
        method: 'GET',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/validations/summary`,
      },
      options,
    ),
  usage: (
    projectId: string,
    query: schemas.UsageQuery,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.usageResponseSchema,
      {
        method: 'GET',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/usage`,
        params: schemas.usageQuerySchema.parse(query),
      },
      options,
    ),
  relations: (
    projectId: string,
    query?: schemas.RelationsQuery,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.relationsResponseSchema,
      {
        method: 'GET',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/relations`,
        params: schemas.relationsQuerySchema.parse(query ?? {}),
      },
      options,
    ),
  activities: (
    projectId: string,
    query?: schemas.ActivitiesQuery,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.activitiesResponseSchema,
      {
        method: 'GET',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/activities`,
        params: schemas.activitiesQuerySchema.parse(query ?? {}),
      },
      options,
    ),
  agentWork: (
    projectId: string,
    query?: schemas.AgentWorkQuery,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.agentWorkResponseSchema,
      {
        method: 'GET',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/agents/work-status`,
        params: schemas.agentWorkQuerySchema.parse(query ?? {}),
      },
      options,
    ),
}
