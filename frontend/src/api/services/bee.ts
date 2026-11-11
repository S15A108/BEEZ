import * as schemas from '@/features/bee/schemas'
import { idSchema } from '@/api/contracts/common'
import { callEndpoint, type RequestOptions } from './request'

export const beeApi = {
  ask: (
    projectId: string,
    input: schemas.AskInput,
    options?: Pick<RequestOptions, 'signal'>,
  ) =>
    callEndpoint(
      schemas.askResponseSchema,
      {
        method: 'POST',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/bee/questions`,
        data: schemas.askInputSchema.parse(input),
        // BEE-A01은 멱등 키를 사용하지 않는다. 명시적으로 다시 보내면 새 답변을 만든다.
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
      },
      options,
    ),
  status: (projectId: string, options?: RequestOptions) =>
    callEndpoint(
      schemas.statusResponseSchema,
      {
        method: 'GET',
        url: `/projects/${encodeURIComponent(String(idSchema.parse(projectId)))}/bee/status`,
      },
      options,
    ),
}
