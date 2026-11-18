import {
  MAX_RANGE_DAYS,
  rangeDays,
  seoulDay,
} from '../features/dashboard/period'
import { fail, omit } from './helpers'
import type { MockProject } from './state'

const FEATURES = [
  'DOCUMENT_PARSE',
  'TASK_VALIDATION',
  'CHANGE_IMPACT',
  'EMBEDDING',
  'BEE_CHAT',
  'BEE_DRAFT',
  'TASK_ASSIGNMENT',
  'MEETING_ANALYSIS',
]
const WEB_FEATURES = ['DOCUMENT_PARSE', 'BEE_CHAT', 'MEETING_ANALYSIS']
// 날마다 부른 호출 수. 일주일 모양으로 되풀이되지 않게 길이를 소수로 둔다.
const DAILY_CALLS = [3, 4, 0, 5, 2, 7, 1, 0, 6, 4, 9, 2, 3]
// 호출 기록이 있는 예시 프로젝트. 새로 만든 프로젝트는 호출이 없다.
const PROJECT_WITH_CALLS = '101'
const DAY_MS = 86_400_000

type Call = { day: string; agent: string; feature: string; task?: string }

// MON-A02 예시. 만들어진 뒤 날마다 정오(한국시간)에 부른 것으로 두고, 서버(UsageQueryService)처럼 한 호출 목록을 축마다
// 묶어 기능별·날짜별 합이 전체 합과 같다. by_agent는 명세대로 채운다(dev 백엔드는 아직 빈 배열).
export function mockUsage(project: MockProject, from: string, to: string) {
  const start = Date.parse(from)
  const end = Date.parse(to)
  if (!(start < end))
    fail(400, 'validation_failed', 'from 은 to 보다 앞이어야 합니다')
  const first = seoulDay(start)
  const last = seoulDay(end - 1)
  if (rangeDays({ from: first, to: last }) > MAX_RANGE_DAYS)
    fail(
      400,
      'validation_failed',
      `조회 기간은 ${MAX_RANGE_DAYS}일 이하여야 합니다`,
    )
  const days: string[] = []
  for (let day = Date.parse(first); day <= Date.parse(last); day += DAY_MS)
    days.push(new Date(day).toISOString().slice(0, 10))

  const agents = project.agents
    .filter((agent) => agent.connected)
    .map((agent) => agent.id)
  const created = Date.parse(project.project.created_at)
  const calls: Call[] =
    project.project.id !== PROJECT_WITH_CALLS
      ? []
      : days.flatMap((day) => {
          const at = Date.parse(`${day}T12:00:00+09:00`)
          if (at < created || at > Date.now() || at < start || at >= end)
            return []
          const count =
            DAILY_CALLS[(Date.parse(day) / DAY_MS) % DAILY_CALLS.length]
          return Array.from({ length: count }, (_, index) => {
            // 셋 중 하나는 에이전트가 부른 작업 검증, 나머지는 웹에서 직접 한 호출
            if (index % 3 || !agents.length)
              return {
                day,
                agent: 'WEB',
                feature: WEB_FEATURES[index % WEB_FEATURES.length],
              }
            return {
              day,
              agent: agents[(index / 3) % agents.length],
              feature: 'TASK_VALIDATION',
              task: project.tasks[(index / 3) % project.tasks.length]?.id,
            }
          })
        })

  const bucket = (key: string, rows: Call[], label?: string) => ({
    key,
    ...(label ? { label } : {}),
    input_tokens: rows.length * 1900,
    output_tokens: rows.length * 430,
    call_count: rows.length,
    // 서버처럼: 비용 있는 호출이 하나도 없으면 전체·모든 묶음에서 비용을 생략한다.
    ...(calls.length
      ? { estimated_cost_amount: String((rows.length * 19) / 1000) }
      : {}),
    tokens_unknown_call_count: 0,
    cost_unknown_call_count: 0,
  })
  const distinct = (values: (string | undefined)[]) =>
    [...new Set(values)].filter((value) => value !== undefined)

  return {
    from,
    to,
    ...omit(bucket('TOTAL', calls), 'key'),
    ...(calls.length ? { currency: 'USD' } : {}),
    by_feature: FEATURES.map((key) =>
      bucket(
        key,
        calls.filter((call) => call.feature === key),
      ),
    ),
    by_task: distinct(calls.map((call) => call.task)).map((key) =>
      bucket(
        key,
        calls.filter((call) => call.task === key),
        project.tasks.find((task) => task.id === key)?.key,
      ),
    ),
    by_validation: [],
    by_agent: distinct(calls.map((call) => call.agent)).map((key) =>
      bucket(
        key,
        calls.filter((call) => call.agent === key),
        key === 'WEB' ? '웹 직접 호출' : undefined,
      ),
    ),
    by_day: days.map((key) =>
      bucket(
        key,
        calls.filter((call) => call.day === key),
      ),
    ),
  }
}
