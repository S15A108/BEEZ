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
  designBoard,
  proposalWall,
  type DesignBoard,
  type OfficeScreens,
  type ProgressRow,
  type WallColumn,
} from './office-screens'

const FONT = '"Malgun Gothic", "Apple SD Gothic Neo", sans-serif'
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
const EMPTY_COUNTS = { todo: 0, working: 0, review: 0, done: 0 }

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
  constructor(kind: OfficeSurfaceKind, width: number, height: number) {
    this.kind = kind
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
        title: `${monitor.owner} · ${monitor.status}`,
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
      } else {
        this.rect(0, 0, w, h, '#fcfbf7')
        this.rect(0, 0, w, 90, '#2c2a24')
        this.rect(26, 33, 20, 20, WORK_COLORS[monitor.tone], 10)
        this.text(monitor.owner, 62, 59, w - 310, 31, '#b7b3a8', 700)
        this.text(monitor.status, w - 240, 59, 216, 29, '#b7b3a8')
        this.text(
          monitor.taskLabel || '나의 작업',
          32,
          133,
          w - 64,
          25,
          '#5d5950',
        )
        this.lines(monitor.title, 32, 181, w - 64, 38, 2)
        this.rect(22, 278, w - 44, 214, '#f5f3ec', 16)
        this.text(
          monitor.at ? '최근 실행 기록' : '실행 기록',
          42,
          317,
          w - 84,
          24,
          '#5d5950',
        )
        this.text(monitor.action, 42, 363, w - 84, 32, '#3b3830', 700)
        if (monitor.files.length)
          monitor.files
            .slice(0, 2)
            .forEach((file, i) =>
              this.text(file, 42, 408 + i * 37, w - 84, 28, '#3b3830'),
            )
        else
          this.text(
            monitor.detail || '대상 파일이 보고되면 여기에 표시돼요',
            42,
            416,
            w - 84,
            26,
            '#5d5950',
          )
        const timestamp =
          monitor.at && !Number.isNaN(Date.parse(monitor.at))
            ? new Date(monitor.at).toLocaleTimeString('ko-KR', {
                hour: '2-digit',
                minute: '2-digit',
              })
            : ''
        this.text(
          timestamp ? `${timestamp} 기록 · 눌러서 자세히` : '눌러서 작업 보기',
          32,
          535,
          w - 64,
          23,
          '#706c62',
        )
      }
    }
    this.texture.needsUpdate = true
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
