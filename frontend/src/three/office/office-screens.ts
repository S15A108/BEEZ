import { statusLabel } from '@/lib/display'
import type { WorkMonitor } from './office-work'

// 사무실 화면 소품(메모 벽·관계 TV·대시보드·책상 모니터)에 그릴 값(D47). REST 응답 모양에 기대지 않게 필요한 필드만 받는다.

export interface WallNote {
  id: string
  key: string
  title: string
  status: string
}
export interface WallColumn {
  label: string
  count: number
  notes: WallNote[]
}
export interface DesignKind {
  kind: string
  label: string
  count: number
  /** 진행 중 변경 제안이 바꾸거나 새로 만드는 항목 수 */
  touched: number
}
export interface DesignBoard {
  version: number
  total: number
  /** 기준 항목이 첫 쪽(100개)보다 많아 센 값이 일부다 */
  more: boolean
  /** 진행 중 변경 제안 수 */
  proposals: number
  kinds: DesignKind[]
}
export interface ProgressRow {
  label: string
  done: number
  total: number
  /** 막대 색: 끝난 쪽(완료 칸 색)인지 검토를 기다리는 쪽(노랑)인지 */
  tone: 'done' | 'review'
}
export interface OfficeScreens {
  /** 현재 기준 번호. 기준이 없으면 없다. */
  version?: number
  /** 변경 제안 메모 벽. 아직 못 받았거나 없는 API면 없다. */
  wall?: WallColumn[]
  /** 설계 관계 TV. null은 기준 설계가 없음, 없으면 아직 못 받음 */
  design?: DesignBoard | null
  /** 프로젝트 진행 화면의 줄(작업 줄은 화면이 사무실 작업 현황으로 붙인다) */
  progress: ProgressRow[]
}

// 메모 벽 칸은 변경 제안 창과 같은 상태 이름이다(lib/display statusLabels). 반려와 거둠은 한 칸에 모은다.
const WALL_COLUMNS: readonly (readonly string[])[] = [
  ['PROPOSED'],
  ['IN_REVIEW'],
  ['APPROVED'],
  ['PUBLISHED'],
  ['REJECTED', 'WITHDRAWN'],
]
/** 칸마다 붙이는 메모 수. 넘는 제안은 '+N'으로 센다. */
export const WALL_NOTES = 3

export function proposalWall(
  proposals: readonly {
    id: string
    key: string
    title: string
    status: string
  }[],
  counts?: readonly { status: string; count: number }[],
): WallColumn[] {
  return WALL_COLUMNS.map((statuses) => {
    const mine = proposals.filter((item) => statuses.includes(item.status))
    const counted = (counts ?? [])
      .filter((item) => statuses.includes(item.status))
      .reduce((sum, item) => sum + item.count, 0)
    return {
      label: statuses.map(statusLabel).join('·'),
      count: Math.max(counted, mine.length),
      notes: mine
        .slice(0, WALL_NOTES)
        .map(({ id, key, title, status }) => ({ id, key, title, status })),
    }
  })
}

const DESIGN_KINDS = [
  'REQUIREMENT',
  'API_CONTRACT',
  'DATA_CONTRACT',
  'POLICY',
  'CONSTRAINT',
] as const

export function designBoard(
  version: number,
  items: readonly { design_item_id: string; kind: string }[],
  more: boolean,
  proposals: readonly {
    items: readonly {
      operation: string
      design_item_id?: string
      kind?: string
    }[]
  }[],
): DesignBoard {
  const kindOf = new Map(items.map((item) => [item.design_item_id, item.kind]))
  // 같은 항목을 두 제안이 바꿔도 한 번만 센다. 새로 만드는 항목은 각자 하나다.
  const touched = new Map<string, Set<string>>()
  proposals.forEach((proposal, p) =>
    proposal.items.forEach((item, i) => {
      const kind =
        item.kind ?? (item.design_item_id && kindOf.get(item.design_item_id))
      if (!kind) return
      if (!touched.has(kind)) touched.set(kind, new Set())
      touched.get(kind)!.add(item.design_item_id ?? `new/${p}/${i}`)
    }),
  )
  return {
    version,
    total: items.length,
    more,
    proposals: proposals.length,
    kinds: DESIGN_KINDS.map((kind) => ({
      kind,
      label: statusLabel(kind),
      count: items.filter((item) => item.kind === kind).length,
      touched: touched.get(kind)?.size ?? 0,
    })),
  }
}

/**
 * 받은 집계만 줄로 만든다. 기준 문서는 종류(기획서·ERD·요구사항·API 명세) 중 승인된 것, 변경 제안은 발행된 것과
 * 검토할 것(대시보드 '검토할 변경 제안'과 같은 값)이다.
 */
export function progressRows(
  changes?: {
    by_status: readonly { status: string; count: number }[]
    review_required_count: number
  },
  documents?: { by_kind: readonly { kind: string; approved_count: number }[] },
): ProgressRow[] {
  const rows: ProgressRow[] = []
  if (documents)
    rows.push({
      label: '기준 문서 승인',
      done: documents.by_kind.filter((item) => item.approved_count > 0).length,
      total: documents.by_kind.length,
      tone: 'done',
    })
  if (changes) {
    const total = changes.by_status.reduce((sum, item) => sum + item.count, 0)
    rows.push(
      {
        label: '변경 제안 발행',
        done:
          changes.by_status.find((item) => item.status === 'PUBLISHED')
            ?.count ?? 0,
        total,
        tone: 'done',
      },
      {
        label: '변경 제안 검토',
        done: changes.review_required_count,
        total,
        tone: 'review',
      },
    )
  }
  return rows
}

export type CodingApp =
  'claude' | 'vscode' | 'intellij' | 'terminal' | 'browser'
const CODING_APPS: readonly CodingApp[] = [
  'claude',
  'vscode',
  'intellij',
  'terminal',
  'browser',
]
/** 창 제목줄에 쓰는 앱 이름. 로고·아이콘 그림은 그리지 않고 글자와 배치만 쓴다. */
export const CODING_APP_NAMES: Record<CodingApp, string> = {
  claude: 'Claude Code',
  vscode: 'Visual Studio Code',
  intellij: 'IntelliJ IDEA',
  terminal: 'bash',
  browser: 'BEEZ',
}

const fnv = (value: string) => {
  let hash = 2166136261
  for (const char of value)
    hash = Math.imul(hash ^ char.charCodeAt(0), 16777619)
  return hash >>> 0
}
/**
 * 책상(모니터 묶음 키)마다 늘 같은 앱. 문자열 해시(FNV-1a)를 앱 수로 나눈 나머지다. 키 앞의 '42/'는 다섯 외관(D39)
 * 사무실마다 다섯 앱이 모두 나오게 고른 값이다. 배치를 바꾸면 office-screens.test.ts가 알려 준다.
 */
export const codingAppOf = (binding: string): CodingApp =>
  CODING_APPS[fnv(`42/${binding}`) % CODING_APPS.length]

const LANGS: Record<string, string> = {
  ts: 'TypeScript',
  tsx: 'TypeScript JSX',
  js: 'JavaScript',
  jsx: 'JavaScript JSX',
  java: 'Java',
  kt: 'Kotlin',
  py: 'Python',
  md: 'Markdown',
  json: 'JSON',
  css: 'CSS',
  sql: 'SQL',
}

export interface CodingScreen {
  app: CodingApp
  mode: 'working' | 'idle' | 'locked'
  /** 상태 줄의 이름(에이전트, 팀원 자리가 아니면 주인)과 상태 */
  who: string
  state: string
  /** 창 제목·첫 주석 줄: 작업 번호·상태·제목 */
  title: string
  /** 작업 번호(없으면 빈 문자열), 번호·상태 줄, 작업 제목 */
  taskKey: string
  label: string
  task: string
  /** 보고된 파일 */
  files: string[]
  /** 탭·파일 트리: 보고된 파일, 없으면 작업이 없거나 쉬는 화면만 자리 표시 파일(README.md) */
  tree: string[]
  /** 최근 실행 기록과 시각(한국시간 'HH:MM', 없으면 빈 문자열) */
  activity: string
  at: string
  /** 경로의 마지막 폴더(~/beez/<area>), 첫 파일의 언어 */
  area: string
  lang: string
  /** 할 일 줄: 계획 등록 → 작업 시작 → 완료 보고 중 지난 단계 */
  steps: [string, boolean][]
  project: string
}

export function codingScreen(
  monitor: WorkMonitor,
  binding: string,
  project: string,
): CodingScreen {
  const mode = monitor.screen ?? 'idle'
  const hasTask = monitor.target.type === 'task'
  const at =
    monitor.at && !Number.isNaN(Date.parse(monitor.at))
      ? new Date(monitor.at).toLocaleTimeString('ko-KR', {
          hour: '2-digit',
          minute: '2-digit',
          hour12: false,
          timeZone: 'Asia/Seoul',
        })
      : ''
  const files = monitor.files
  const tree = files.length
    ? files
    : mode !== 'working' || !hasTask
      ? ['README.md']
      : []
  const first = tree[0] ?? ''
  const started =
    hasTask && ['working', 'review', 'done'].includes(monitor.tone)
  return {
    app: codingAppOf(binding),
    mode,
    who: monitor.agent ?? monitor.owner,
    state: monitor.status,
    title: hasTask ? `${monitor.taskLabel} · ${monitor.title}` : monitor.title,
    taskKey:
      monitor.target.type === 'task'
        ? (monitor.taskKey ?? monitor.target.id)
        : '',
    label: hasTask ? monitor.taskLabel : '',
    task: monitor.title,
    files,
    tree,
    activity: monitor.action,
    at,
    area: first.includes('/') ? first.split('/')[0] : 'frontend',
    lang: LANGS[first.split('.').pop() ?? ''] ?? '텍스트',
    steps: [
      ['계획 등록', hasTask],
      ['작업 시작', started],
      ['완료 보고', hasTask && monitor.tone === 'done'],
    ],
    project,
  }
}

export type CodingTone =
  | 'prompt'
  | 'tool'
  | 'muted'
  | 'add'
  | 'del'
  | 'todo'
  | 'accent'
  | 'command'
  | 'log'
  | 'changed'
/** 글자 위주 화면(Claude Code·터미널)의 본문 줄. 작업·파일·실행 기록은 실제 값이고 diff 두 줄만 장식이다. */
export function codingLines(
  screen: CodingScreen,
): { text: string; tone: CodingTone }[] {
  const lines: { text: string; tone: CodingTone }[] = []
  const add = (text: string, tone: CodingTone) => lines.push({ text, tone })
  const working = screen.mode === 'working'
  const todo = screen.steps
    .map(([step, done]) => `${done ? '☒' : '☐'} ${step}`)
    .join('  ')
  if (screen.app === 'claude') {
    add(`> ${screen.title}`, 'prompt')
    if (!working) {
      add('  ⎿ 작업을 기다리고 있어요', 'muted')
      add(todo, 'todo')
      return lines
    }
    const [read, update = read] = screen.files
    if (read) add(`⏺ Read(${read})`, 'tool')
    add(`  ⎿ ${screen.activity}`, 'muted')
    if (update) {
      add(`⏺ Update(${update})`, 'tool')
      add('+   const saved = await save(draft)', 'add')
      add('-   const saved = save(draft)', 'del')
    }
    add(todo, 'todo')
    add(`✻ ${screen.state}…`, 'accent')
  } else if (screen.app === 'terminal') {
    if (screen.taskKey) {
      add('git log --oneline -1', 'command')
      const hash = fnv(screen.taskKey).toString(16).padStart(8, '0')
      add(`${hash.slice(0, 7)} ${screen.taskKey} ${screen.task}`, 'log')
    }
    if (!working) {
      add(`# ${screen.state} · 작업을 기다리고 있어요`, 'muted')
      return lines
    }
    add('git status --short', 'command')
    for (const file of screen.files) add(` M ${file}`, 'changed')
    add(`# 최근 실행: ${screen.activity}`, 'muted')
    add(`[██████████░░░░░░] ${screen.state}`, 'accent')
  }
  return lines
}
