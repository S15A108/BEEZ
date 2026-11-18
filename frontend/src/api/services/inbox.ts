import * as schemas from '@/features/inbox/schemas'
import { callEndpoint, type RequestOptions } from './request'

export const inboxApi = {
  list: (query?: schemas.ListQuery, options?: RequestOptions) =>
    callEndpoint(
      schemas.listResponseSchema,
      {
        method: 'GET',
        url: `/me/notifications`,
        params: schemas.listQuerySchema.parse(query ?? {}),
      },
      options,
    ),
  markRead: (input: schemas.MarkReadInput, options?: RequestOptions) =>
    callEndpoint(
      schemas.markReadResponseSchema,
      {
        method: 'POST',
        url: `/me/notifications/read`,
        data: schemas.markReadInputSchema.parse(input),
        idempotent: true,
      },
      options,
    ),
}
