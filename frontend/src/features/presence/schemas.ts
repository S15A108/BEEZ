import { z } from 'zod'
import { idSchema, timeSchema } from '@/api/contracts/common'

// MON-A06·A07 팀원 접속 상태·위치(S15P31A108-386). 허용 값은 서버가 거절하는 값과 같다
// (docs/api/01-backend-rest.md MON-A06). zone 은 사무실의 Zone(three/types.ts), panel 은 WorkspacePane 이다.
export const presenceZones = [
  'meeting',
  'lead',
  'dev',
  'review',
  'board',
  'lobby',
  'humandesk',
  'control',
  'hr',
] as const

export const presencePanels = [
  'documents',
  'planning',
  'changes',
  'tasks',
  'meetings',
  'relations',
  'dashboard',
  'settings',
  'inbox',
  'chat',
  'context',
] as const

/** 보고는 마지막 값으로 통째로 덮어쓰므로 매번 현재 값을 모두 보낸다. 모두 선택이다. */
export const presenceReportSchema = z.object({
  zone: z.enum(presenceZones).optional(),
  seat: z
    .string()
    .regex(/^[A-Za-z0-9_-]{1,16}$/)
    .optional(),
  panel: z.enum(presencePanels).optional(),
})
export type PresenceReport = z.infer<typeof presenceReportSchema>

// 응답은 서버가 나중에 값을 늘려도 한 사람 때문에 목록 전체가 깨지지 않게 zone·panel 을 문자열로 받는다.
// 값이 없는 필드는 서버가 생략한다(null 아님). 접속한 적 없으면 online=false 뿐이다.
export const presenceItemSchema = z.object({
  member_id: idSchema,
  online: z.boolean(),
  last_seen_at: timeSchema.optional(),
  zone: z.string().optional(),
  seat: z.string().optional(),
  panel: z.string().optional(),
})
export type PresenceItem = z.infer<typeof presenceItemSchema>

export const presenceResponseSchema = z.object({
  items: z.array(presenceItemSchema),
})
export type PresenceResponse = z.infer<typeof presenceResponseSchema>
