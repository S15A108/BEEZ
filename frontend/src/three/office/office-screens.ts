import { statusLabel } from '@/lib/display'

// 사무실 화면 소품(메모 벽·관계 TV·대시보드)에 그릴 값(D47). REST 응답 모양에 기대지 않게 필요한 필드만 받는다.

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
