import { z } from 'zod'
import * as schemas from '@/features/agents/schemas'
import { idSchema } from '@/api/contracts/common'
import { callEndpoint, type RequestOptions } from './request'

export const agentsApi = {
  issueToken: (
    projectId: string,
    input?: schemas.IssueTokenInput,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.issueTokenResponseSchema,
      {
        method: 'POST',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/agent-tokens`,
        data: schemas.issueTokenInputSchema.parse(input ?? {}),
      },
      options,
    ),
  myTokens: (query?: schemas.MyTokensQuery, options?: RequestOptions) =>
    callEndpoint(
      schemas.myTokensResponseSchema,
      {
        method: 'GET',
        url: `/me/agent-tokens`,
        params: schemas.myTokensQuerySchema.parse(query ?? {}),
      },
      options,
    ),
  revokeToken: (tokenId: string, options?: RequestOptions) =>
    callEndpoint(
      z.void(),
      {
        method: 'DELETE',
        url: `/agent-tokens/${encodeURIComponent(String(idSchema.parse(tokenId)))}`,
      },
      options,
    ),
  connectionGuide: (projectId: string, options?: RequestOptions) =>
    callEndpoint(
      schemas.connectionGuideResponseSchema,
      {
        method: 'GET',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/agent-connection-guide`,
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
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/agents`,
        params: schemas.listQuerySchema.parse(query ?? {}),
      },
      options,
    ),
  get: (projectId: string, agentSessionId: string, options?: RequestOptions) =>
    callEndpoint(
      schemas.getResponseSchema,
      {
        method: 'GET',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/agents/${encodeURIComponent(String(idSchema.parse(agentSessionId)))}`,
      },
      options,
    ),
}
