// BEEZ REST v0.2 및 공통 v0.3: snake_case 응답을 그대로 검증한다.
import { z } from 'zod'
import * as common from '@/api/contracts/common'

export const dependenciesResponseSchema = z.object({
  items: common.designDependencySchema.array(),
  candidates: common.dependencyCandidateSchema.array(),
})
export type DependenciesResponse = z.infer<typeof dependenciesResponseSchema>
