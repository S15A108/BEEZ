import { presenceReportSchema } from '../features/presence/schemas'
import type { PresenceItem } from '../features/presence/schemas'
import { requireProject } from './helpers'
import type { MockContext, MockReply } from './helpers'

// MON-A06·A07 예시. 서버처럼 프로세스 메모리에만 두고(저장하지 않는다) 마지막 보고로 통째로 덮어쓴다.
// 서버 TTL(45초)과 같게 online 을 읽을 때 계산한다. 예시에서는 다른 팀원이 보고하지 않아 나만 접속 중으로 보인다.
const TTL_MS = 45_000
const entries = new Map<string, { at: string; zone?: string; panel?: string }>()

export function resetMockPresence() {
  entries.clear()
}

export function presenceRoutes(
  id: string,
  context: MockContext,
): MockReply | undefined {
  if (id !== 'MON-A06' && id !== 'MON-A07') return undefined
  const project = requireProject(context)
  if (id === 'MON-A06') {
    const report = presenceReportSchema.parse(context.body)
    entries.set(`${project.project.id}:${context.member!.id}`, {
      at: new Date().toISOString(),
      ...(report.zone ? { zone: report.zone } : {}),
      ...(report.panel ? { panel: report.panel } : {}),
    })
    return { status: 204 }
  }
  const items: PresenceItem[] = project.members.map((member) => {
    const entry = entries.get(`${project.project.id}:${member.id}`)
    if (!entry) return { member_id: member.id, online: false }
    return {
      member_id: member.id,
      online: Date.now() - Date.parse(entry.at) < TTL_MS,
      last_seen_at: entry.at,
      ...(entry.zone ? { zone: entry.zone } : {}),
      ...(entry.panel ? { panel: entry.panel } : {}),
    }
  })
  return { data: { items } }
}
