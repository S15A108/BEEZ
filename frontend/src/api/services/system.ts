import * as schemas from '@/features/system/schemas'
import { callEndpoint, type RequestOptions } from './request'

export const systemApi = {
  ping: (options?: RequestOptions) =>
    callEndpoint(
      schemas.pingResponseSchema,
      { method: 'GET', url: `/ping` },
      options,
    ),
  status: (options?: RequestOptions) =>
    callEndpoint(
      schemas.statusResponseSchema,
      { method: 'GET', url: `/system/status` },
      options,
    ),
}
