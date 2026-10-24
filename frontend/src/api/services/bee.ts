import * as schemas from '@/features/bee/schemas'
import { idSchema } from '@/api/contracts/common'
import { callEndpoint, type RequestOptions } from './request'

export const beeApi = {
  ask: (projectId: string, input: schemas.AskInput, options?: RequestOptions) =>
    callEndpoint(
      schemas.askResponseSchema,
      {
        method: 'POST',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/bee/questions`,
        data: schemas.askInputSchema.parse(input),
        idempotent: true,
      },
      options,
    ),
  suggestAssignments: (
    projectId: string,
    input: schemas.SuggestAssignmentsInput,
    options?: RequestOptions,
  ) =>
    callEndpoint(
      schemas.suggestAssignmentsResponseSchema,
      {
        method: 'POST',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/assignment-suggestions`,
        data: schemas.suggestAssignmentsInputSchema.parse(input),
        idempotent: true,
      },
      options,
    ),
}
