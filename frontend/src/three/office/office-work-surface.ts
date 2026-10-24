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

/** One reusable texture per physical display; snapshots update pixels without rebuilding the room. */
export class OfficeWorkSurface {
  readonly mesh: THREE.Mesh<THREE.PlaneGeometry, THREE.MeshBasicMaterial>
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

  readonly kind: 'monitor' | 'board' | 'projection' | 'note'
  constructor(
    kind: 'monitor' | 'board' | 'projection' | 'note',
    width: number,
    height: number,
  ) {
    this.kind = kind
    this.canvas.width =
      kind === 'board' || kind === 'projection'
        ? 1536
        : kind === 'note'
          ? 512
          : 896
    this.canvas.height =
      kind === 'projection'
        ? 600
        : kind === 'board' || kind === 'note'
          ? 480
          : 560
    this.ctx = this.canvas.getContext('2d')!
    this.texture = new THREE.CanvasTexture(this.canvas)
    this.texture.colorSpace = THREE.SRGBColorSpace
    this.texture.anisotropy = 4
    this.mesh = new THREE.Mesh(
      new THREE.PlaneGeometry(width, height),
      new THREE.MeshBasicMaterial({
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
  ) {
    const ctx = this.ctx
    ctx.font = `${weight} ${size}px ${FONT}`
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

  update(work?: OfficeWorkState, content?: WorkMonitor) {
    const monitor = content ?? emptyMonitor
    const board = this.kind === 'board' || this.kind === 'projection',
      projected = this.kind === 'projection'
    const signature = JSON.stringify(
      board
        ? { project: work?.project, notes: work?.notes, counts: work?.counts }
        : monitor,
    )
    if (signature === this.signature) return
    this.signature = signature
    this.regions = []
    const ctx = this.ctx,
      w = this.canvas.width,
      h = this.canvas.height
    ctx.clearRect(0, 0, w, h)
    if (board) {
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
