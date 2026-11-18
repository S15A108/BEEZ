import * as schemas from '@/features/relations/schemas'
import { idSchema } from '@/api/contracts/common'
import { callEndpoint, type RequestOptions } from './request'

export const relationsApi = {
  dependencies: (projectId: string, itemId: string, options?: RequestOptions) =>
    callEndpoint(
      schemas.dependenciesResponseSchema,
      {
        method: 'GET',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/design-items/${encodeURIComponent(String(idSchema.parse(itemId)))}/dependencies`,
      },
      options,
    ),
}
