import { z } from 'zod'
import { idSchema } from '@/api/contracts/common'
import * as schemas from '@/features/presence/schemas'
import { callEndpoint, type RequestOptions } from './request'

const url = (projectId: string) =>
  `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/presence`

export const presenceApi = {
  /** MON-A06. 204. 같은 값을 되풀이해도 결과가 같아 멱등 키를 보내지 않는다. */
  report: (
    projectId: string,
    input: schemas.PresenceReport,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      z.void(),
      {
        method: 'PUT',
        url: url(projectId),
        data: schemas.presenceReportSchema.parse(input),
      },
      options,
    ),
  /** MON-A07. 활성 멤버마다 하나. */
  list: (projectId: string, options?: RequestOptions) =>
    callEndpoint(
      schemas.presenceResponseSchema,
      { method: 'GET', url: url(projectId) },
      options,
    ),
}
