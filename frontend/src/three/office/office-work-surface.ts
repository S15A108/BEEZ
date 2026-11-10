import * as THREE from 'three'
import {
  WORK_COLORS,
  WORK_COLUMNS,
  WORK_COLUMN_LABELS,
  visibleBoardNotes,
  type OfficeWorkHit,
  type OfficeWorkState,
  type WorkMonitor,
} from './office-work'
import {
  CODING_APP_NAMES,
  codingAppOf,
  codingLines,
  codingScreen,
  designBoard,
  proposalWall,
  type CodingScreen,
  type DesignBoard,
  type OfficeScreens,
  type ProgressRow,
  type WallColumn,
} from './office-screens'

const FONT = '"Malgun Gothic", "Apple SD Gothic Neo", sans-serif'
const MONO = '"Cascadia Mono", Consolas, "D2Coding", "Malgun Gothic", monospace'
type HitRegion = {
  x: number
  y: number
  w: number
  h: number
  hit: OfficeWorkHit
}
const emptyMonitor: WorkMonitor = {
  owner: '빈 작업석',
  status: '대기',
  tone: 'idle',
  title: '담당 동료를 기다리고 있어요',
  taskLabel: '',
  action: '등록된 실행 기록 없음',
  files: [],
  detail: '',
  at: null,
  target: { type: 'zone', id: 'dev' },
  screen: 'locked',
}
export type OfficeSurfaceKind =
  | 'monitor'
  | 'board'
  | 'projection'
  | 'note'
  /** 변경 제안 메모 벽 */
  | 'notes'
  /** 설계 관계 TV */
  | 'graph'
  /** 팀 대시보드 */
  | 'dash1'
  /** 프로젝트 진행 */
  | 'dash2'
const SIZE: Record<OfficeSurfaceKind, [number, number]> = {
  monitor: [896, 560],
  board: [1536, 480],
  projection: [1536, 600],
  note: [512, 480],
  notes: [2048, 910],
  graph: [1280, 720],
  dash1: [1280, 720],
  dash2: [1280, 720],
}
// 사무실 색(D47): 종이 보드·카드, 따뜻한 어두운 화면, 크림 글자, BEEZ 노랑, 작업 칸 색(WORK_COLORS)
const PAPER = '#f8f3df',
  CARD = '#fcfbf7',
  SCREEN = '#2c2a24',
  SCREEN_2 = '#3b3830',
  CREAM = '#f5f3ec',
  MUTED = '#b7b3a8',
  INK = '#2c291f',
  INK_2 = '#5d5950',
  TAPE = '#e8d8b2',
  SHADOW = '#ded9c4',
  YELLOW = '#ffd24a'
// 변경 제안 상태 색. 제안은 할 일 칸, 검토 중은 작업 중, 승인은 검토, 발행은 완료, 반려는 막힘 색이다.
const PROPOSAL_COLORS: Record<string, string> = {
  PROPOSED: WORK_COLORS.todo,
  IN_REVIEW: WORK_COLORS.working,
  APPROVED: WORK_COLORS.review,
  PUBLISHED: WORK_COLORS.done,
  REJECTED: WORK_COLORS.blocked,
}
const WALL_STATUSES = [
  'PROPOSED',
  'IN_REVIEW',
  'APPROVED',
  'PUBLISHED',
  'REJECTED',
]
// 코딩 화면의 장식 코드(작업 내용이 아니다). 0 키워드, 1 이름, 2 함수, 3 문자열, 4 타입·인자, 5 제어·주석어
const CODE: [string, number][][] = [
  [
    ['import', 5],
    [' { ', 1],
    ['useQuery', 2],
    [' } ', 1],
    ['from', 5],
    [" '@tanstack/react-query'", 3],
  ],
  [],
  [
    ['export ', 5],
    ['function', 0],
    [' useRows', 2],
    ['(', 1],
    ['id', 4],
    [': ', 1],
    ['string', 4],
    [') {', 1],
  ],
  [
    ['  const', 0],
    [' rows = ', 1],
    ['parse', 2],
    ['(input)', 1],
  ],
  [
    ['  if', 5],
    [' (!rows.length) ', 1],
    ['return', 5],
    [' []', 1],
  ],
  [
    ['  return', 5],
    [' rows.', 1],
    ['map', 2],
    ['((', 1],
    ['row', 4],
    [') => ({ ...row, done: ', 1],
    ['true', 0],
    [' }))', 1],
  ],
  [['}', 1]],
  [],
  [
    ['test', 2],
    ["('saves the draft'", 3],
    [', ', 1],
    ['async', 0],
    [' () => {', 1],
  ],
  [
    ['  await', 5],
    [' user.', 1],
    ['click', 2],
    ['(button)', 1],
  ],
  [
    ['  expect', 2],
    ['(saved).', 1],
    ['toBe', 2],
    ['(', 1],
    ['true', 0],
    [')', 1],
  ],
  [['})', 1]],
]
// 앱마다 실제 앱에 가까운 색(사용자 요청 "화려하고 다양하고 특징 잘 나타나게"). 순서는 CODE의 토큰 번호다.
const VSCODE_CODE = [
  '#569cd6',
  '#9cdcfe',
  '#dcdcaa',
  '#ce9178',
  '#4ec9b0',
  '#c586c0',
]
const IDEA_CODE = [
  '#cf8e6d',
  '#bcbec4',
  '#56a8f5',
  '#6aab73',
  '#c77dbb',
  '#cf8e6d',
]
const fileTag = (file: string): [string, string] => {
  const ext = file.split('.').pop() ?? ''
  return ext === 'ts' || ext === 'tsx'
    ? ['TS', '#4fc1ff']
    : ext === 'js' || ext === 'jsx'
      ? ['JS', '#cbcb41']
      : ext === 'json'
        ? ['{}', '#cbcb41']
        : ext === 'md'
          ? ['M↓', '#519aba']
          : ['≡', '#c5c5c5']
}
const baseName = (file: string) => file.split('/').pop() || file
const EMPTY_COUNTS = { todo: 0, working: 0, review: 0, done: 0 }
/** 책상마다 다른 장식 코드 줄에서 시작한다. */
const codeSeed = (binding: string) =>
  [...binding].reduce((sum, char) => sum + char.charCodeAt(0), 0)

/** One reusable texture per physical display; snapshots update pixels without rebuilding the room. */
export class OfficeWorkSurface {
  readonly mesh: THREE.Mesh<
    THREE.PlaneGeometry,
    THREE.MeshBasicMaterial | THREE.MeshStandardMaterial
  >
  private readonly canvas = document.createElement('canvas')
  private readonly ctx: CanvasRenderingContext2D
  private readonly texture: THREE.CanvasTexture
  private signature = ''
  private regions: HitRegion[] = []
  private fallback: OfficeWorkHit = {
    title: '작업 보드',
    detail: '등록된 작업이 없어요',
    target: { type: 'zone', id: 'dev' },
  }

  readonly kind: OfficeSurfaceKind
  /** 모니터가 놓인 책상(작업 자리 키). 책상마다 같은 코딩 앱을 고른다. */
  private readonly binding: string
  constructor(
    kind: OfficeSurfaceKind,
    width: number,
    height: number,
    binding = '',
  ) {
    this.kind = kind
    this.binding = binding
    ;[this.canvas.width, this.canvas.height] = SIZE[kind]
    this.ctx = this.canvas.getContext('2d')!
    this.texture = new THREE.CanvasTexture(this.canvas)
    this.texture.colorSpace = THREE.SRGBColorSpace
    this.texture.anisotropy = 4
    this.mesh = new THREE.Mesh(
      new THREE.PlaneGeometry(width, height),
      // 메모 벽은 종이라 방 빛을 받는다(예전 칸반·메모 벽 그림과 같은 재질). 화면은 스스로 빛난다.
      kind === 'notes'
        ? new THREE.MeshStandardMaterial({
            map: this.texture,
            emissive: '#ffffff',
            emissiveMap: this.texture,
            emissiveIntensity: 0.08,
            roughness: 0.5,
          })
        : new THREE.MeshBasicMaterial({
            map: this.texture,
            toneMapped: false,
            side: THREE.DoubleSide,
          }),
    )
    this.mesh.userData.workSurface = this
    this.update()
  }

  private rect(
    x: number,
    y: number,
    w: number,
    h: number,
    fill: string,
    radius = 0,
  ) {
    const ctx = this.ctx
    ctx.fillStyle = fill
    ctx.beginPath()
    ctx.roundRect(x, y, w, h, radius)
    ctx.fill()
  }

  private text(
    value: string,
    x: number,
    y: number,
    width: number,
    size: number,
    color = '#2c2a24',
    weight = 500,
    family = FONT,
  ) {
    const ctx = this.ctx
    ctx.font = `${weight} ${size}px ${family}`
    ctx.fillStyle = color
    let text = value
    if (ctx.measureText(text).width > width) {
      const chars = [...text]
      while (
        chars.length &&
        ctx.measureText(chars.join('') + '…').width > width
      )
        chars.pop()
      text = chars.join('') + '…'
    }
    ctx.fillText(text, x, y)
    return ctx.measureText(text).width
  }

  private lines(
    value: string,
    x: number,
    y: number,
    width: number,
    size: number,
    limit = 2,
    color = '#2c2a24',
  ) {
    const ctx = this.ctx
    ctx.font = `650 ${size}px ${FONT}`
    let line = '',
      row = 0
    const chars = [...value]
    for (let i = 0; i < chars.length; i++) {
      if (line && ctx.measureText(line + chars[i]).width > width) {
        if (row === limit - 1) {
          this.text(
            line + chars.slice(i).join(''),
            x,
            y + row * size * 1.4,
            width,
            size,
            color,
            650,
          )
          return
        }
        this.text(line, x, y + row * size * 1.4, width, size, color, 650)
        row++
        line = ''
      }
      line += chars[i]
    }
    if (line) this.text(line, x, y + row * size * 1.4, width, size, color, 650)
  }

  /** 가운데 맞춤 글자 */
  private centered(
    value: string,
    x: number,
    y: number,
    width: number,
    size: number,
    color: string,
    weight = 700,
  ) {
    this.ctx.font = `${weight} ${size}px ${FONT}`
    const shown = Math.min(width, this.ctx.measureText(value).width)
    this.text(value, x - shown / 2, y, width, size, color, weight)
  }

  update(
    work?: OfficeWorkState,
    content?: WorkMonitor,
    screens?: OfficeScreens,
  ) {
    const monitor = content ?? emptyMonitor
    const board = this.kind === 'board' || this.kind === 'projection',
      projected = this.kind === 'projection'
    // 화면마다 그리는 값만 비교한다. 같으면 텍스처를 다시 올리지 않는다.
    const signature = String(
      JSON.stringify(
        this.kind === 'notes'
          ? [work?.project, screens?.wall]
          : this.kind === 'graph'
            ? screens?.design
            : this.kind === 'dash1'
              ? [work?.project, work?.counts, work?.agents]
              : this.kind === 'dash2'
                ? [work?.counts, screens?.progress, screens?.version]
                : board
                  ? {
                      project: work?.project,
                      notes: work?.notes,
                      counts: work?.counts,
                    }
                  : this.kind === 'monitor'
                    ? [
                        monitor,
                        work?.project,
                        // 브라우저 화면만 작업 칸별 수와 프로젝트 주소를 그린다.
                        codingAppOf(this.binding) === 'browser' && [
                          work?.counts,
                          work?.projectId,
                        ],
                      ]
                    : monitor,
      ),
    )
    if (signature === this.signature) return
    this.signature = signature
    this.regions = []
    const ctx = this.ctx,
      w = this.canvas.width,
      h = this.canvas.height
    ctx.clearRect(0, 0, w, h)
    if (this.kind === 'notes')
      this.drawWall(screens?.wall, work?.project ?? '우리 팀')
    else if (this.kind === 'graph') this.drawDesign(screens?.design)
    else if (this.kind === 'dash1') this.drawTeam(work)
    else if (this.kind === 'dash2') this.drawProgress(work, screens)
    else if (board) {
      this.rect(0, 0, w, h, projected ? '#f8fcff' : '#f8f3df')
      this.text(work?.project ?? '우리 팀', 40, 34, 1000, 27, '#5d5950', 750)
      this.text(
        projected ? '팀 작업 현황' : '작업 보드',
        w - 240,
        34,
        205,
        27,
        '#5d5950',
      )
      this.fallback = {
        title: projected ? '프로젝터 작업 현황' : '프로젝트 작업 보드',
        detail: work
          ? WORK_COLUMNS.map(
              (column) =>
                `${WORK_COLUMN_LABELS[column]} ${work.counts[column]}개`,
            ).join(' · ')
          : '아직 등록된 작업이 없어요',
        target: { type: 'zone', id: 'dev' },
      }
      const colWidth = (w - 80) / 4
      WORK_COLUMNS.forEach((column, col) => {
        const x = 32 + col * colWidth
        this.rect(x, 54, colWidth - 18, 42, WORK_COLORS[column], 9)
        this.text(
          `${WORK_COLUMN_LABELS[column]}  ${work?.counts[column] ?? 0}`,
          x + 20,
          84,
          colWidth - 48,
          27,
          '#3b3830',
          750,
        )
        const notes = work ? visibleBoardNotes(work, column) : []
        notes.forEach((note, index) => {
          const y = 114 + index * 166,
            color = note.blocked ? WORK_COLORS.blocked : WORK_COLORS[column]
          if (!projected)
            this.rect(x + 5, y + 5, colWidth - 32, 150, '#ded9c4', 5)
          this.rect(x, y, colWidth - 32, 150, color, projected ? 12 : 5)
          if (!projected)
            this.rect(x + (colWidth - 32) / 2 - 27, y - 6, 54, 17, '#e8d8b2', 2)
          this.text(note.label, x + 16, y + 27, colWidth - 64, 20, '#5d5950')
          this.lines(note.title, x + 16, y + 61, colWidth - 64, 28, 2)
          this.text(
            note.files[0] ?? note.owner,
            x + 16,
            y + 121,
            colWidth - 64,
            21,
            '#5d5950',
          )
          this.text(
            note.files.length ? note.owner : '눌러서 작업 보기',
            x + 16,
            y + 144,
            colWidth - 64,
            18,
            '#5d5950',
          )
          this.regions.push({
            x,
            y,
            w: colWidth - 32,
            h: 150,
            hit: {
              title: `${note.label} · ${note.title}`,
              detail: [note.owner, ...note.files].join(' · '),
              target: note.target,
            },
          })
        })
        if (!notes.length)
          this.text('아직 없어요', x + 20, 152, colWidth - 50, 24, '#706c62')
        const remaining = (work?.counts[column] ?? 0) - notes.length
        if (remaining > 0)
          this.text(
            `+${remaining}개 · 눌러서 전체 보기`,
            x + 10,
            466,
            colWidth - 35,
            21,
            '#5d5950',
          )
      })
      if (projected)
        this.text('팀의 작업과 실행 기록', 40, h - 28, w - 80, 25, '#8b9caa')
    } else {
      this.fallback = {
        title: `${monitor.agent ?? monitor.owner} · ${monitor.status}`,
        detail: [
          monitor.taskLabel,
          monitor.title,
          monitor.at ? `최근 실행: ${monitor.action}` : null,
          ...monitor.files,
        ]
          .filter(Boolean)
          .join(' · '),
        target: monitor.target,
      }
      if (this.kind === 'note') {
        this.rect(0, 0, w, h, WORK_COLORS[monitor.tone])
        this.rect(w / 2 - 45, 0, 90, 26, '#e8d8b2')
        this.text(
          monitor.taskLabel || monitor.status,
          28,
          72,
          w - 56,
          30,
          '#5d5950',
        )
        this.lines(monitor.title, 28, 144, w - 56, 43, 3)
        this.text(monitor.owner, 28, h - 43, w - 56, 29, '#5d5950')
      } else
        this.drawCoding(
          codingScreen(monitor, this.binding, work?.project ?? '우리 팀'),
          work,
        )
    }
    this.texture.needsUpdate = true
  }

  private mono(
    value: string,
    x: number,
    y: number,
    size: number,
    color: string,
    width = this.canvas.width - x - 18,
    weight = 500,
  ) {
    return this.text(value, x, y, width, size, color, weight, MONO)
  }

  // 변경 제안 메모 벽: 작업 보드와 같은 그림(종이, 상태 색 머리, 테이프 붙인 메모)으로 상태 칸마다 제안을 붙이고,
  // 누르면 그 제안을 연다.
  private drawWall(wall: WallColumn[] | undefined, project: string) {
    const ctx = this.ctx,
      w = this.canvas.width,
      h = this.canvas.height
    const columns = wall ?? proposalWall([])
    this.rect(0, 0, w, h, PAPER)
    this.text(project, 52, 54, 1300, 36, INK_2, 750)
    this.text('변경 제안', w - 300, 54, 250, 36, INK_2)
    const colWidth = (w - 104) / columns.length
    columns.forEach((column, col) => {
      const x = 42 + col * colWidth,
        nw = colWidth - 40
      this.rect(
        x,
        76,
        colWidth - 22,
        60,
        PROPOSAL_COLORS[WALL_STATUSES[col]],
        12,
      )
      this.text(
        wall ? `${column.label}  ${column.count}` : column.label,
        x + 26,
        118,
        colWidth - 64,
        36,
        SCREEN_2,
        750,
      )
      column.notes.forEach((note, k) => {
        const y = 170 + k * 236,
          nh = 206
        ctx.save()
        ctx.translate(x + nw / 2, y + nh / 2)
        ctx.rotate((((col * 3 + k) % 5) - 2) * 0.012)
        ctx.translate(-(x + nw / 2), -(y + nh / 2))
        this.rect(x + 7, y + 7, nw, nh, SHADOW, 6)
        this.rect(
          x,
          y,
          nw,
          nh,
          PROPOSAL_COLORS[note.status] ?? WORK_COLORS.idle,
          6,
        )
        this.rect(x + nw / 2 - 40, y - 9, 80, 24, TAPE, 3)
        this.text(note.key, x + 22, y + 42, nw - 44, 28, INK_2, 700)
        this.lines(note.title, x + 22, y + 94, nw - 44, 38, 2)
        this.text('눌러서 제안 보기', x + 22, y + 186, nw - 44, 24, INK_2)
        ctx.restore()
        this.regions.push({
          x,
          y,
          w: nw,
          h: nh,
          hit: {
            title: `${note.key} · ${note.title}`,
            detail: `${column.label} · 눌러서 변경 제안 보기`,
            target: { type: 'pane', id: 'changes', focus: note.id },
          },
        })
      })
      if (wall && !column.notes.length)
        this.text('아직 없어요', x + 26, 212, colWidth - 64, 32, '#706c62')
      const remaining = column.count - column.notes.length
      if (remaining > 0)
        this.text(
          `+${remaining}개 · 눌러서 전체 보기`,
          x + 14,
          890,
          colWidth - 40,
          28,
          INK_2,
        )
    })
    const missing = '변경 제안 현황을 아직 받지 못했어요'
    if (!wall) this.text(missing, 60, h / 2, w - 120, 44, '#706c62')
    this.fallback = {
      title: '변경 제안 메모 벽',
      detail: wall
        ? columns
            .map((column) => `${column.label} ${column.count}건`)
            .join(' · ')
        : missing,
      target: { type: 'pane', id: 'changes' },
    }
  }

  // 설계 관계 TV: 예전 관계 그래프 그림 그대로, 가운데는 기준 번호와 항목 수, 둘레는 설계 항목 종류별 수다. 진행 중
  // 변경 제안이 바꾸는 종류는 노란 굵은 테두리와 '변경 중 N'을 단다.
  private drawDesign(design?: DesignBoard | null) {
    const ctx = this.ctx,
      w = this.canvas.width,
      h = this.canvas.height
    this.rect(0, 0, w, h, '#f7f8fa')
    const center: [number, number] = [640, 360]
    const spots: [number, number][] = [
      [300, 180],
      [980, 180],
      [280, 560],
      [1000, 560],
      [640, 620],
    ]
    const kinds = (design ?? designBoard(0, [], false, [])).kinds
    ctx.strokeStyle = '#9aa3ad'
    ctx.lineWidth = 6
    for (const [x, y] of spots) {
      ctx.beginPath()
      ctx.moveTo(...center)
      ctx.lineTo(x, y)
      ctx.stroke()
    }
    const node = (
      x: number,
      y: number,
      width: number,
      fill: string,
      edge: string,
      line: number,
    ) => {
      ctx.fillStyle = fill
      ctx.strokeStyle = edge
      ctx.lineWidth = line
      ctx.beginPath()
      ctx.roundRect(x - width / 2, y - 55, width, 110, 22)
      ctx.fill()
      ctx.stroke()
    }
    kinds.forEach((kind, i) => {
      const [x, y] = spots[i]
      const touched = !!design && kind.touched > 0
      node(x, y, 260, '#ffffff', touched ? YELLOW : INK, touched ? 12 : 4)
      this.centered(
        design ? `${kind.label} ${kind.count}` : kind.label,
        x,
        touched ? y - 4 : y + 12,
        236,
        34,
        INK,
      )
      if (touched)
        this.centered(
          `변경 중 ${kind.touched}`,
          x,
          y + 36,
          236,
          26,
          '#8a6a00',
          600,
        )
    })
    const [cx, cy] = center
    node(cx, cy, 380, '#f3c530', INK, 4)
    this.centered(
      design
        ? `기준 v${design.version} · 항목 ${design.total}${design.more ? '+' : ''}`
        : design === null
          ? '기준 설계 없음'
          : '기준 확인 전',
      cx,
      cy + 12,
      350,
      34,
      INK,
    )
    const touched = kinds.reduce((sum, kind) => sum + kind.touched, 0)
    this.fallback = {
      title: design ? `설계 관계 · 기준 v${design.version}` : '설계 관계 TV',
      detail: design
        ? [
            ...kinds.map((kind) => `${kind.label} ${kind.count}개`),
            design.proposals
              ? `진행 중인 변경 제안 ${design.proposals}건이 설계 항목 ${touched}개를 바꿔요`
              : '진행 중인 변경 제안이 없어요',
          ].join(' · ')
        : design === null
          ? '아직 기준 설계가 없어요'
          : '설계 항목을 아직 받지 못했어요',
      target: { type: 'pane', id: 'relations' },
    }
  }

  // 팀 대시보드: 작업 칸 색 타일 넷(사무실 작업 현황과 같은 값)과 에이전트 연결 줄
  private drawTeam(work?: OfficeWorkState) {
    const w = this.canvas.width
    this.rect(0, 0, w, this.canvas.height, SCREEN)
    this.text('팀 대시보드', 60, 92, 560, 44, CREAM, 700)
    if (work) this.text(work.project, 680, 92, 540, 28, MUTED)
    const counts = work?.counts ?? EMPTY_COUNTS
    const total = WORK_COLUMNS.reduce((sum, column) => sum + counts[column], 0)
    WORK_COLUMNS.forEach((column, i) => {
      const x = 60 + i * 295
      this.rect(x, 140, 265, 220, WORK_COLORS[column], 16)
      this.text(WORK_COLUMN_LABELS[column], x + 28, 196, 220, 30, SCREEN_2, 600)
      this.text(String(counts[column]), x + 28, 322, 220, 96, INK, 700)
    })
    const agents =
      work?.agents &&
      `에이전트 ${work.agents.connected} 연결됨 · ${work.agents.waiting} 대기`
    if (agents) this.text(agents, 60, 460, 1160, 36, CREAM, 600)
    this.text(
      total ? `전체 작업 ${total}개` : '아직 등록된 작업이 없어요',
      60,
      540,
      1160,
      30,
      MUTED,
    )
    this.fallback = {
      title: '팀 대시보드',
      detail: [
        ...WORK_COLUMNS.map(
          (column) => `${WORK_COLUMN_LABELS[column]} ${counts[column]}개`,
        ),
        agents,
      ]
        .filter(Boolean)
        .join(' · '),
      target: { type: 'pane', id: 'tasks' },
    }
  }

  // 프로젝트 진행: 받은 값만 비율 막대로 그린다(작업 줄은 사무실 작업 현황, 나머지는 집계 API). 끝난 쪽은 완료 칸 색,
  // 검토를 기다리는 쪽은 노랑이다.
  private drawProgress(work?: OfficeWorkState, screens?: OfficeScreens) {
    const counts = work?.counts ?? EMPTY_COUNTS
    const tasks = WORK_COLUMNS.reduce((sum, column) => sum + counts[column], 0)
    const rows: ProgressRow[] = [
      {
        label: `작업 ${WORK_COLUMN_LABELS.done}`,
        done: counts.done,
        total: tasks,
        tone: 'done',
      },
      {
        label: `작업 ${WORK_COLUMN_LABELS.review}`,
        done: counts.review,
        total: tasks,
        tone: 'review',
      },
      ...(screens?.progress ?? []),
    ]
    this.rect(0, 0, this.canvas.width, this.canvas.height, SCREEN)
    this.text(
      screens?.version
        ? `프로젝트 진행 · 기준 v${screens.version}`
        : '프로젝트 진행',
      60,
      92,
      1160,
      44,
      CREAM,
      700,
    )
    rows.forEach((row, i) => {
      const y = 150 + i * 100
      this.text(row.label, 60, y + 30, 250, 30, CREAM)
      this.rect(330, y, 700, 40, SCREEN_2, 8)
      if (row.done && row.total)
        this.rect(
          330,
          y,
          700 * Math.min(1, row.done / row.total),
          40,
          row.tone === 'done' ? WORK_COLORS.done : YELLOW,
          8,
        )
      this.text(`${row.done} / ${row.total}`, 1060, y + 30, 180, 30, CREAM, 700)
    })
    this.fallback = {
      title: '프로젝트 진행',
      detail: rows
        .map((row) => `${row.label} ${row.done} / ${row.total}`)
        .join(' · '),
      target: { type: 'pane', id: 'dashboard' },
    }
  }

  // 에이전트 책상 모니터: 책상마다 정한 코딩 앱 다섯 가지 중 하나(D47). 로고 그림 없이 배치와 글자로만 앱을 알아보게
  // 하고, 작업 번호·제목·파일·상태·최근 실행·에이전트 이름은 실제 값을 그 앱의 제자리에 둔다. 연결 전이면 꺼진 화면이다.
  private drawCoding(screen: CodingScreen, work?: OfficeWorkState) {
    const w = this.canvas.width,
      h = this.canvas.height
    if (screen.mode === 'locked') {
      this.rect(0, 0, w, h, '#0b0b0a')
      this.centered(screen.who, w / 2, 262, w - 120, 28, '#3d3b36')
      this.centered(screen.state, w / 2, 306, w - 120, 24, '#36342f', 600)
    } else if (screen.app === 'claude') this.drawClaude(screen)
    else if (screen.app === 'vscode') this.drawVscode(screen)
    else if (screen.app === 'intellij') this.drawIdea(screen)
    else if (screen.app === 'terminal') this.drawTerminal(screen)
    else this.drawBrowser(screen, work)
    // 모니터 테두리(베젤). 밝은 화면도 사무실 책상 위 모니터로 보이게 한다.
    this.rect(0, 0, w, 10, '#0b0b0a')
    this.rect(0, h - 10, w, 10, '#0b0b0a')
    this.rect(0, 0, 10, h, '#0b0b0a')
    this.rect(w - 10, 0, 10, h, '#0b0b0a')
  }

  private drawClaude(screen: CodingScreen) {
    const w = this.canvas.width,
      working = screen.mode === 'working'
    this.rect(0, 0, w, this.canvas.height, '#1a1915')
    this.rect(26, 24, w - 52, 66, '#d97757', 12)
    this.rect(28, 26, w - 56, 62, '#1a1915', 10)
    let x = 48 + this.mono('✻ ', 48, 66, 22, '#d97757')
    x += this.mono(CODING_APP_NAMES.claude, x, 66, 22, '#f0eee6', 300, 700)
    this.mono(` · ~/beez/${screen.area}`, x, 66, 20, '#9a958a')
    let y = 134
    for (const { text, tone } of codingLines(screen).slice(0, 8)) {
      if (tone === 'add' || tone === 'del') {
        this.rect(
          30,
          y - 24,
          w - 60,
          33,
          tone === 'add' ? '#2e4a2b' : '#4a2b2b',
        )
        this.mono(text, 44, y, 19, tone === 'add' ? '#b6e3a8' : '#f0b0a8')
      } else if (tone === 'tool') {
        this.mono('⏺', 40, y, 19, working ? '#8fbf7f' : '#9a958a')
        this.mono(text.slice(2), 66, y, 19, '#f0eee6')
      } else if (tone === 'prompt') {
        this.mono('>', 40, y, 20, '#9a958a')
        this.mono(text.slice(2), 66, y, 20, '#f0eee6', undefined, 600)
      } else
        this.mono(
          text,
          40,
          y,
          19,
          tone === 'accent'
            ? '#d97757'
            : tone === 'todo'
              ? '#e0ddd2'
              : '#9a958a',
        )
      y += 36
    }
    this.rect(26, 446, w - 52, 58, '#6b6862', 10)
    this.rect(28, 448, w - 56, 54, '#1a1915', 9)
    this.mono('>', 46, 484, 22, '#f0eee6')
    this.rect(74, 462, 12, 28, working ? '#f0eee6' : '#6b6862')
    this.mono(`${screen.who} · ${screen.state}`, 30, 532, 16, '#9a958a')
  }

  private drawVscode(screen: CodingScreen) {
    const w = this.canvas.width,
      h = this.canvas.height,
      working = screen.mode === 'working'
    const tabs = screen.tree.length ? screen.tree.slice(0, 3) : [screen.task]
    this.rect(0, 0, w, h, '#1e1e1e')
    this.rect(0, 0, w, 36, '#323233')
    this.centered(
      `${baseName(tabs[0])} — beez — ${CODING_APP_NAMES.vscode}`,
      w / 2,
      24,
      w - 240,
      15,
      '#cccccc',
      500,
    )
    this.text('—   ▢   ✕', w - 110, 24, 100, 15, '#cccccc')
    this.rect(0, 36, 48, h - 64, '#333333')
    ;['⧉', '⌕', '⑂', '▷', '⊞'].forEach((glyph, i) => {
      if (!i) this.rect(0, 52, 3, 34, '#ffffff')
      this.text(glyph, 13, 78 + i * 46, 30, 22, i ? '#858585' : '#ffffff')
    })
    this.rect(48, 36, 206, h - 64, '#252526')
    this.text('탐색기', 64, 62, 180, 13, '#bbbbbb', 700)
    this.text('⌄ BEEZ', 60, 90, 180, 14, '#cccccc', 700)
    if (!screen.tree.length)
      this.text('보고된 파일 없음', 72, 118, 170, 14, '#858585')
    screen.tree.slice(0, 7).forEach((file, i) => {
      const y = 118 + i * 28
      if (!i) this.rect(48, y - 19, 206, 26, '#37373d')
      const [tag, color] = fileTag(file)
      this.text(tag, 72, y, 30, 12, color, 700)
      this.text(baseName(file), 100, y, 146, 15, '#cccccc')
    })
    this.rect(254, 36, w - 254, 36, '#252526')
    tabs.forEach((tab, i) => {
      const x = 254 + i * 172
      this.rect(x, 36, 170, 36, i ? '#2d2d2d' : '#1e1e1e')
      if (!i) this.rect(x, 36, 170, 2, '#0078d4')
      const [tag, color] = fileTag(tab)
      this.text(tag, x + 12, 59, 26, 11, color, 700)
      this.text(baseName(tab), x + 38, 59, 122, 14, i ? '#969696' : '#ffffff')
    })
    // 편집기: 첫 줄은 작업 번호·제목 주석, 나머지는 장식 코드. 쉬는 화면은 빈 편집기다.
    const seed = codeSeed(this.binding)
    if (working) this.rect(254, 81 + 4 * 24, w - 312, 24, '#2a2d2e')
    for (let i = 0; i < (working ? 12 : 3); i++) {
      const y = 98 + i * 24
      this.text(String(i + 1), 266, y, 30, 14, '#858585', 500, MONO)
      if (!i) this.mono(`// ${screen.title}`, 306, y, 15, '#6a9955', w - 380)
      else if (working) {
        let x = 306
        for (const [token, tone] of CODE[(seed + i) % CODE.length])
          x += this.mono(token, x, y, 15, VSCODE_CODE[tone], w - x - 64)
      }
    }
    // 오른쪽 미니맵
    for (let i = 0; i < (working ? 34 : 4); i++) {
      const length = CODE[(seed + i) % CODE.length].reduce(
        (sum, [token]) => sum + token.length,
        0,
      )
      if (length)
        this.rect(
          w - 54,
          80 + i * 7,
          Math.min(40, length * 1.1),
          3,
          'rgba(160,160,160,0.35)',
        )
    }
    this.rect(w - 58, 76, 48, 66, 'rgba(121,121,121,0.18)')
    // 아래 터미널 칸: 최근 실행
    this.rect(254, 386, w - 264, 1, '#3c3c3c')
    this.text(
      '문제    출력    디버그 콘솔    터미널',
      268,
      410,
      w - 290,
      13,
      '#969696',
    )
    this.rect(445, 418, 44, 1, '#e7e7e7')
    this.mono('›', 268, 450, 16, '#89d185')
    if (working) this.mono(screen.activity, 290, 450, 15, '#cccccc', w - 310)
    if (screen.at) this.mono(`${screen.at} 기록`, 268, 478, 13, '#8b8b8b')
    // 상태 줄은 모니터 테두리(10px) 안쪽에 둔다.
    this.rect(0, h - 38, w, 28, '#007acc')
    const x =
      14 +
      this.text(
        `⑂ ${screen.taskKey || 'main'}`,
        14,
        h - 19,
        220,
        14,
        '#ffffff',
        600,
      )
    this.text(
      `${screen.who} · ${screen.state}`,
      x + 24,
      h - 19,
      w - x - 160,
      14,
      '#ffffff',
      600,
    )
    this.text(screen.lang, w - 130, h - 19, 120, 13, '#ffffff')
  }

  private drawIdea(screen: CodingScreen) {
    const w = this.canvas.width,
      h = this.canvas.height,
      working = screen.mode === 'working'
    const tabs = screen.tree.length ? screen.tree.slice(0, 3) : [screen.task]
    this.rect(0, 0, w, h, '#1e1f22')
    this.rect(0, 0, w, 44, '#2b2d30')
    this.rect(16, 12, 22, 22, '#3574f0', 5)
    this.text('b', 22, 29, 14, 14, '#ffffff', 700)
    this.text('beez', 46, 29, 90, 15, '#dfe1e5', 700)
    this.text(`⑂ ${screen.taskKey || 'main'} ▾`, 130, 29, 320, 15, '#dfe1e5')
    this.text('▶', w - 128, 30, 24, 20, '#5fb865', 700)
    this.text('⟳', w - 92, 30, 24, 19, '#ced0d6')
    this.text('⋮', w - 54, 30, 24, 19, '#ced0d6')
    this.rect(0, 44, 40, h - 70, '#2b2d30')
    ;['▤', '⌕', '⑂', '◎'].forEach((glyph, i) => {
      if (!i) this.rect(5, 58, 30, 28, '#3d3f43', 6)
      this.text(glyph, 12, 78 + i * 40, 24, 16, '#ced0d6')
    })
    this.rect(40, 44, 210, h - 70, '#2b2d30')
    this.rect(250, 44, 1, h - 70, '#1e1f22')
    this.text('프로젝트 ⌄', 52, 70, 190, 14, '#dfe1e5', 700)
    this.text('⌄  beez', 52, 98, 190, 14, '#dfe1e5')
    if (!screen.tree.length)
      this.text('보고된 파일 없음', 70, 124, 170, 14, '#8c8f94')
    screen.tree.slice(0, 7).forEach((file, i) => {
      const y = 124 + i * 26,
        code = !file.endsWith('.md')
      this.rect(70, y - 14, 18, 18, code ? '#3574f0' : '#8c8c8c', 9)
      this.text(code ? 'c' : 'M', 75, y, 12, 12, '#ffffff', 700)
      this.text(baseName(file), 96, y, 148, 14, '#dfe1e5')
    })
    this.rect(251, 44, w - 251, 38, '#1e1f22')
    this.rect(251, 81, w - 251, 1, '#393b40')
    tabs.forEach((tab, i) => {
      const x = 251 + i * 172
      this.rect(x + 12, 56, 14, 14, '#3574f0', 7)
      this.text(baseName(tab), x + 34, 68, 128, 14, i ? '#9da0a8' : '#dfe1e5')
      if (!i) this.rect(x, 78, 170, 3, '#3574f0')
    })
    // 거터: 줄 번호, 시험 줄의 초록 실행 삼각형, 확인 줄의 빨간 중단점
    const seed = codeSeed(this.binding)
    for (let i = 0; i < (working ? 12 : 3); i++) {
      const y = 106 + i * 24
      this.text(String(i + 1), 262, y, 26, 14, '#4b5059', 500, MONO)
      if (!i) {
        this.mono(`// ${screen.title}`, 312, y, 15, '#7a7e85', w - 330)
        continue
      }
      if (!working) continue
      const line = CODE[(seed + i) % CODE.length]
      if (line[0]?.[0] === 'test')
        this.text('▶', 290, y, 14, 12, '#5fb865', 700)
      if (line[0]?.[0] === '  expect')
        this.rect(291, y - 12, 12, 12, '#db5c5c', 6)
      let x = 312
      for (const [token, tone] of line)
        x += this.mono(token, x, y, 15, IDEA_CODE[tone], w - x - 16)
    }
    this.rect(251, 386, w - 251, 110, '#2b2d30')
    this.text(
      `실행: ${screen.taskKey || 'beez'}`,
      266,
      410,
      w - 290,
      14,
      '#dfe1e5',
      600,
    )
    this.mono(
      working ? `▶ ${screen.activity}` : '실행 대기 중',
      266,
      446,
      15,
      working ? '#5fb865' : '#8c8f94',
      w - 290,
    )
    if (screen.at) this.mono(`${screen.at} 기록`, 266, 474, 13, '#8c8f94')
    this.rect(0, h - 36, w, 26, '#2b2d30')
    this.text(
      `${screen.who} · ${screen.state}`,
      14,
      h - 18,
      w - 300,
      14,
      '#9da0a8',
    )
    this.text(
      `UTF-8 · LF · ${screen.lang}`,
      w - 250,
      h - 18,
      240,
      13,
      '#9da0a8',
    )
  }

  private drawTerminal(screen: CodingScreen) {
    const w = this.canvas.width,
      working = screen.mode === 'working'
    this.rect(0, 0, w, this.canvas.height, '#0c0c0c')
    this.rect(0, 0, w, 44, '#1f1f1f')
    this.rect(12, 8, 300, 36, '#0c0c0c', 8)
    this.text(
      `${screen.who} — ${CODING_APP_NAMES.terminal}`,
      28,
      32,
      250,
      15,
      '#cccccc',
    )
    this.text('✕', 288, 32, 20, 13, '#8a8a8a')
    this.text('+   ⌄', 330, 32, 80, 16, '#cccccc')
    let y = 82
    const prompt = () => {
      let x = 22 + this.mono('agent@beez', 22, y, 17, '#16c60c', 200, 700)
      x += this.mono(':', x, y, 17, '#cccccc')
      x += this.mono(`~/${screen.area}`, x, y, 17, '#3b78ff', 240, 700)
      return x + this.mono('$ ', x, y, 17, '#cccccc')
    }
    for (const { text, tone } of codingLines(screen).slice(0, 13)) {
      if (tone === 'command') this.mono(text, prompt(), y, 17, '#cccccc')
      else if (tone === 'log') {
        const x = 22 + this.mono(text.slice(0, 7), 22, y, 17, '#f9f1a5')
        this.mono(text.slice(7), x, y, 17, '#cccccc')
      } else if (tone === 'changed') {
        const x = 22 + this.mono(' M', 22, y, 17, '#e74856')
        this.mono(text.slice(2), x, y, 17, '#cccccc')
      } else
        this.mono(text, 22, y, 17, tone === 'accent' ? '#16c60c' : '#767676')
      y += 30
    }
    this.rect(prompt(), y - 17, 11, 21, working ? '#cccccc' : '#5a5a5a')
  }

  private drawBrowser(screen: CodingScreen, work?: OfficeWorkState) {
    const w = this.canvas.width,
      h = this.canvas.height,
      working = screen.mode === 'working'
    this.rect(0, 0, w, h, '#ffffff')
    this.rect(0, 0, w, 48, '#dee1e6')
    this.rect(14, 10, 300, 38, '#ffffff', 10)
    this.rect(14, 34, 300, 14, '#ffffff')
    this.text(
      `${CODING_APP_NAMES.browser} · 작업 현황`,
      32,
      35,
      240,
      15,
      '#3c4043',
      600,
    )
    this.text('✕', 290, 35, 20, 13, '#5f6368')
    this.text('+', 332, 36, 20, 20, '#5f6368')
    this.text('←   →   ⟳', 18, 80, 100, 18, '#5f6368')
    this.rect(124, 58, w - 150, 32, '#f1f3f4', 16)
    this.text(
      `localhost:5173/projects/${work?.projectId ?? ''}`,
      146,
      80,
      w - 200,
      15,
      '#202124',
    )
    this.rect(0, 97, w, 1, '#dadce0')
    // 화면 본문: 사무실 작업 보드 타일(실제 칸별 수)과 이 에이전트의 작업
    this.rect(0, 98, w, 330, PAPER)
    this.text(screen.project, 28, 134, 480, 20, INK, 750)
    this.rect(w - 300, 112, 272, 32, working ? YELLOW : WORK_COLORS.idle, 16)
    this.centered(
      `${screen.who} · ${screen.state}`,
      w - 164,
      134,
      252,
      14,
      SCREEN_2,
      600,
    )
    const counts = work?.counts ?? EMPTY_COUNTS
    WORK_COLUMNS.forEach((column, i) => {
      const x = 28 + i * 212
      this.rect(x, 156, 196, 86, WORK_COLORS[column], 10)
      this.text(WORK_COLUMN_LABELS[column], x + 14, 184, 170, 14, SCREEN_2, 600)
      this.text(String(counts[column]), x + 14, 228, 170, 32, INK, 700)
    })
    this.rect(27, 259, w - 54, 154, '#e5dfc9', 13)
    this.rect(28, 260, w - 56, 152, CARD, 12)
    this.text(
      screen.label || '배정된 작업 없음',
      48,
      292,
      w - 96,
      15,
      INK_2,
      600,
    )
    this.lines(screen.task, 48, 334, w - 96, 24, 2, INK)
    this.text(screen.files.join(' · '), 48, 396, w - 96, 14, INK_2)
    // 개발자 도구: 콘솔에 최근 실행
    this.rect(0, 428, w, 1, '#cbcdd1')
    this.text(
      '요소      콘솔      네트워크      소스',
      18,
      454,
      w - 40,
      14,
      '#5f6368',
    )
    this.rect(70, 462, 34, 2, '#1a73e8')
    this.rect(0, 466, w, 1, '#f1f3f4')
    this.mono(
      `› ${working ? screen.activity : '대기 중'}`,
      20,
      494,
      15,
      '#202124',
      w - 140,
    )
    if (screen.at) this.mono(screen.at, w - 90, 494, 14, '#5f6368')
    this.mono(
      `› ${screen.who} · ${screen.state}`,
      20,
      524,
      14,
      '#5f6368',
      w - 40,
    )
  }

  hit(u = 0.5, v = 0.5): OfficeWorkHit {
    const x = u * this.canvas.width,
      y = (1 - v) * this.canvas.height
    return (
      this.regions.find(
        (region) =>
          x >= region.x &&
          x <= region.x + region.w &&
          y >= region.y &&
          y <= region.y + region.h,
      )?.hit ?? this.fallback
    )
  }

  dispose() {
    this.texture.dispose()
    this.mesh.material.dispose()
    this.mesh.geometry.dispose()
    this.mesh.removeFromParent()
  }
}
