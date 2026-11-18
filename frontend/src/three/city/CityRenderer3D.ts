import * as THREE from 'three'
import { cloneKenney, loadKenney } from '../kenney'
import { kenneyBuildingOf, kenneyBuildingUrl } from '../buildingModels'
import {
  BUS_SPOTS,
  laneSpots,
  RING_PITCH as P,
  RING_RADIUS as R,
  RING_SLOTS as N,
  ringOrder,
  ringSlot,
} from './ring'

// 나의 도시(D33): 원형 도시(캔버스 SCR-04-v2-city v474, 그림 코드 ring2.html)를 앞쪽 호만 보이게 그린다(캔버스 미리보기
// city-ring-queue-preview 4번, 사용자 결정 '12칸 유지'). 칸은 고리를 따라 놓이고 고리가 통째로 돈다. 화면은 고리 앞쪽만 담아
// 부지가 가로로 한 줄 선 것처럼 보이고, 양 끝 칸은 가라앉지 않고 고리를 따라 뒤로 돌아 화면 밖으로 나간다.
// 주변(순환 도로와 차·버스, 보도·가로수·공원·가로등·벤치·사람, 칸 사이 쉼터, 고리 안쪽 공원, 작은 건물·흐린 먼 건물)은
// 고리와 함께 돈다.
export interface RingProject {
  id: string
  slot: number
  design: string
}
export interface RingView {
  /** 몇 칸 돌렸는지(소수 가능) */
  rot: number
  hover: number | null
  /** 프로젝트 만들기 창 뒤에 깔 때: 더 가깝게 본다(보드 SCR-04-v2-create-project) */
  backdrop?: boolean
}

// 디자인마다 같은 부지 폭에 맞춘 배율(캔버스 city_ring_spec.json). 건물 모델과 색은 D22 표를 따른다.
const FIT: Record<string, number> = {
  glass: 55.88,
  brick: 36.48,
  modern: 58.37,
  tower: 54.76,
  wood: 46.34,
}
// 화면 틀. 나의 도시는 1310px 화면에 1000단위를 담고(1단위 ≈ 1.31px) 앞 칸의 땅을 화면 높이 75%에 둔다(미리보기 4번).
// 칸 간격을 넓히며 860에서 1000으로 넓혀 건물은 0.86배, 부지 사이 빈 땅은 약 61px에서 118px가 됐다(목업 A안).
// 만들기 창 배경은 820단위를 담고 고리 중심을 화면 위 끝에서 70단위 아래에 둔다(보드 SCR-04-v2-create-project).
const FRAME = {
  city: { view: 1000, px: 1310, front: 0.75 },
  backdrop: { view: 820, top: 70 },
}
// 순환 도로의 차 색. 부지·건물보다 눈에 띄지 않게 흐리게 칠한다.
const CAR_COLORS = [
  '#e8a598',
  '#9fc1d9',
  '#f1d27a',
  '#b9c7a5',
  '#f7f3ea',
  '#c9b3d6',
]
const PEOPLE_COLORS = ['#e3a07f', '#7fa7c9', '#d9c27a', '#a5b98d', '#c49bb8']
// 나무 잎과 꽃 색. 자리마다 섞어 같은 모양이 줄지어 반복되지 않게 한다.
const GREENS = ['#adc196', '#9fb68b', '#b7c99a', '#93ab80']
const FLOWERS = [
  '#e3b7b0',
  '#e8d58f',
  '#d9b8cf',
  '#f0c9a0',
  '#c9d6ec',
  '#f2e2e8',
]
/** 시드를 받는 가벼운 난수. 같은 시드면 늘 같은 배치다. */
const seeded = (seed: number) => () =>
  (seed = (seed * 16807) % 2147483647) / 2147483647
// 가로수 띠 사이 작은 건물. 먼 건물처럼 한 가지씩 흐린 색으로 칠한다.
const SMALL_MODELS = [
  'low-detail-building-a',
  'low-detail-building-b',
  'low-detail-building-c',
  'low-detail-building-d',
  'low-detail-building-g',
  'low-detail-building-h',
  'low-detail-building-j',
  'low-detail-building-m',
]
const SMALL_TINTS = ['#ece3d3', '#e2e6d8', '#efe0d8', '#e0e3e8']
const EL = Math.PI / 4
const AZ = Math.PI / 4
// 먼 건물은 한 가지 흐린 색으로 칠한다. 캔버스는 b·c·d·f·h·k를 썼고, 레포에 없는 c·k 자리에 a·g를 쓴다.
const FAR_MODELS = [
  'building-b',
  'building-a',
  'building-d',
  'building-f',
  'building-h',
  'building-g',
]
const slotAngle = (slot: number) => (slot * 2 * Math.PI) / N + Math.PI / 4

type Box = { left: number; top: number; right: number; bottom: number }

interface Slot {
  group: THREE.Group
  lawn: THREE.Group
  building: THREE.Group
  /** 쓰지 않은 칸의 꾸밈(잔디·나무·벤치·꽃밭). 부지 모양은 프로젝트 칸과 빈 부지 하나에만 둔다. */
  filler: THREE.Group
  number: THREE.Sprite
  key: string
  height: number
  /** 이 칸에 지금 놓인 프로젝트·빈 부지의 칸 번호(lot). 꾸밈 칸이면 null */
  lot: number | null
}

export class CityRenderer3D {
  readonly canvas: HTMLCanvasElement
  private readonly renderer: THREE.WebGLRenderer
  private readonly ownsRenderer: boolean
  private readonly scene = new THREE.Scene()
  private readonly camera = new THREE.OrthographicCamera(-1, 1, 1, -1, 1, 8000)
  /** 고리와 함께 도는 것: 칸, 주변 */
  private readonly ring = new THREE.Group()
  private readonly raycaster = new THREE.Raycaster()
  private readonly ndc = new THREE.Vector2()
  private readonly scratch = new THREE.Vector3()
  private readonly materials = new Map<string, THREE.MeshStandardMaterial>()
  private readonly owned = new Set<{ dispose(): void }>()
  private readonly slots: Slot[] = []
  /** 이웃 칸이 둘 다 쓰일 때만 보이는 안쪽 화단. gaps[s]는 칸 s와 s+1 사이 */
  private readonly gaps: THREE.Object3D[][] = []
  private readonly highlight: THREE.Mesh
  /** 고리를 따라 이어 놓는 차례: 프로젝트(칸 번호 순) 다음 빈 부지. design이 없으면 빈 부지다. */
  private order: { lot: number; design?: string }[] = []
  private rot = 0
  private signature = ''
  private width = 1
  private height = 1
  private disposed = false

  constructor(
    host: HTMLElement,
    compact = false,
    externalRenderer?: THREE.WebGLRenderer,
  ) {
    void compact
    this.ownsRenderer = !externalRenderer
    this.renderer =
      externalRenderer ??
      new THREE.WebGLRenderer({ antialias: true, alpha: false })
    if (this.ownsRenderer)
      this.renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 1.75))
    this.renderer.shadowMap.enabled = true
    this.renderer.shadowMap.type = THREE.PCFSoftShadowMap
    this.canvas = this.renderer.domElement
    if (this.ownsRenderer) host.prepend(this.canvas)

    this.scene.background = new THREE.Color('#fcfaf4')
    this.scene.add(new THREE.HemisphereLight('#ffffff', '#d9d2bf', 2.0))
    const sun = new THREE.DirectionalLight('#fff4e0', 1.9)
    sun.position.set(-260, 700, 380)
    sun.castShadow = true
    // 그림자는 바깥 길 끝(R + 786)까지 받는다.
    const reach = R + 800
    Object.assign(sun.shadow.camera, {
      left: -reach,
      right: reach,
      top: reach,
      bottom: -reach,
      near: 10,
      far: 2600 + reach,
    })
    sun.shadow.mapSize.set(2048, 2048)
    sun.shadow.bias = -0.0006
    sun.shadow.radius = 3
    this.scene.add(sun)

    const ground = this.flat(new THREE.PlaneGeometry(8000, 8000), '#e1e9cd', 0)
    const plaza = this.flat(
      new THREE.RingGeometry(R - 105, R + 82, 160),
      '#eee6d2',
      1.5,
    )
    const inner = this.flat(
      new THREE.CircleGeometry(R - 175, 160),
      '#dfe8c7',
      1.8,
    )
    const paving = [R - 96, R + 74].map((r) =>
      this.flat(new THREE.RingGeometry(r - 1, r + 1, 192), '#e2d8c2', 1.6),
    )
    const hive = this.mesh(
      new THREE.CylinderGeometry(26, 26, 22, 6),
      '#f3c530',
      0.7,
    )
    hive.position.y = 13
    hive.castShadow = true
    this.scene.add(ground, plaza, inner, ...paving, hive, this.ring)

    const frame = new THREE.Mesh(
      this.own(new THREE.RingGeometry(82, 92, 4, 1, Math.PI / 4)),
      this.own(new THREE.MeshBasicMaterial({ color: '#f3c530' })),
    )
    frame.rotation.x = -Math.PI / 2
    frame.position.y = 3.6
    this.highlight = frame
    this.buildDecor()
  }

  setSize(w: number, h: number) {
    if (this.disposed) return
    this.width = Math.max(1, w)
    this.height = Math.max(1, h)
    if (this.ownsRenderer) this.renderer.setSize(this.width, this.height, false)
  }

  /** 화면 1px이 몇 단위인지의 역수(단위당 px). 끌기 거리를 칸 수로 바꿀 때 쓴다. */
  pixelsPerUnit(view: RingView) {
    return this.width / this.viewUnits(view)
  }

  // 화면 폭에 담는 단위 수. 나의 도시는 1단위 ≈ 1.31px로 그려 넓은 화면은 1000단위까지 담고,
  // 좁은 화면은 400단위(가운데 칸과 양옆)까지 줄여 칸이 너무 작아지지 않게 한다. 만들기 창 배경은 늘 820단위다.
  private viewUnits(view: RingView) {
    const { view: most, px } = FRAME.city
    return view.backdrop
      ? FRAME.backdrop.view
      : Math.min(most, Math.max(400, (this.width * most) / px))
  }

  /** 프로젝트와 빈 부지를 맞춘다. 같은 내용이면 아무것도 하지 않는다. */
  setProjects(projects: readonly RingProject[], next: number | null) {
    if (this.disposed) return
    const signature = JSON.stringify([projects, next])
    if (signature === this.signature) return
    this.signature = signature
    this.order = [
      ...[...projects]
        .sort((a, b) => a.slot - b.slot)
        .map((project) => ({ lot: project.slot, design: project.design })),
      ...(next === null ? [] : [{ lot: next }]),
    ]
    this.arrange()
  }

  // 칸마다 지금 놓을 차례를 정하고(ringOrder), 바뀐 칸만 부지·건물을 다시 놓는다. 고리가 돌면 고리 뒤를 지나는 칸의 차례가
  // 바뀐다.
  private arrange() {
    const lots = this.order.map((item) => item.lot)
    for (let s = 0; s < N; s++) {
      const item = this.order[ringOrder(s, this.rot, lots)]
      const slot = this.slot(s)
      const lot = item?.lot ?? null
      if (slot.lot !== lot && lot !== null)
        slot.number.material = this.numberMaterial(lot + 1)
      slot.lot = lot
      const key = !item
        ? ''
        : item.design === undefined
          ? 'next'
          : `p:${item.design}`
      if (slot.key === key) continue
      slot.key = key
      slot.lawn.clear()
      slot.building.clear()
      slot.height = 0
      if (!item) continue
      slot.lawn.add(this.lawn(item.design === undefined ? 'next' : 'project'))
      if (item.design !== undefined) this.building(slot, item.design)
    }
    this.gaps.forEach((items, s) => {
      const on =
        this.slots[s].lot !== null && this.slots[(s + 1) % N].lot !== null
      for (const item of items) item.visible = on
    })
  }

  /** 그 칸 번호(lot)가 놓인 칸 가운데 앞(가운데)에 가장 가까운 칸. 같은 프로젝트가 둘 이상 보일 수 있다. */
  private slotOf(lot: number) {
    let best: Slot | undefined
    let away = Infinity
    for (const [s, slot] of this.slots.entries()) {
      const off = ringSlot(s, this.rot).away
      if (slot.lot === lot && off < away) {
        best = slot
        away = off
      }
    }
    return best
  }

  render(view: RingView) {
    if (this.disposed) return
    this.rot = view.rot
    this.arrange()
    this.ring.rotation.y = (-view.rot * 2 * Math.PI) / N
    const ppu = this.pixelsPerUnit(view)
    for (const slot of this.slots) {
      slot.filler.visible = slot.lot === null
      slot.number.visible = slot.lot !== null
      // 보드 그림(2배 해상도)에서 64×48px 글자판을 0.42배로 찍은 크기: 화면에서 13×10px
      slot.number.scale.set((64 * 0.21) / ppu, (48 * 0.21) / ppu, 1)
    }
    const hovered = view.hover === null ? undefined : this.slotOf(view.hover)
    if (hovered) hovered.group.add(this.highlight)
    else this.highlight.removeFromParent()

    const w = this.viewUnits(view)
    const halfH = (w * this.height) / this.width / 2
    // 바라보는 점을 고리 중심에서 앞(카메라 쪽) 대각선으로 d만큼 둔다. 앞 칸(중심에서 R)은 화면 가운데보다
    // (R − d)·sin(고도)만큼 아래에 놓이므로, 75% 높이(가운데에서 반 높이의 절반 아래)가 되게 d를 고른다.
    const d = view.backdrop
      ? (halfH - (FRAME.backdrop.top * w) / FRAME.backdrop.view) / Math.sin(EL)
      : R - ((FRAME.city.front - 0.5) * 2 * halfH) / Math.sin(EL)
    const target = d / Math.SQRT2
    Object.assign(this.camera, {
      left: -w / 2,
      right: w / 2,
      top: halfH,
      bottom: -halfH,
    })
    this.camera.position.set(
      target + 3000 * Math.sin(AZ) * Math.cos(EL),
      3000 * Math.sin(EL),
      target + 3000 * Math.cos(AZ) * Math.cos(EL),
    )
    this.camera.lookAt(target, 0, target)
    this.camera.updateProjectionMatrix()
    this.renderer.render(this.scene, this.camera)
  }

  /**
   * 이름표(HTML, data-slot = 칸 번호 lot)를 그 프로젝트·빈 부지 가운데 앞에 가장 가까운 칸 위로 옮긴다. 화면 양옆·위에서 잘리는
   * 이름표는 숨긴다. 이웃 이름표끼리 겹치면 가운데에 가까운 쪽만 남긴다.
   */
  placeTags(overlay: HTMLElement | null) {
    const taken: Box[] = []
    const tags = [...(overlay?.children ?? [])]
      .map((element) => {
        const tag = element as HTMLElement
        return { tag, at: this.anchor(Number(tag.dataset.slot)) }
      })
      .sort(
        (a, b) =>
          Math.abs((a.at?.x ?? Infinity) - this.width / 2) -
          Math.abs((b.at?.x ?? Infinity) - this.width / 2),
      )
    for (const { tag, at } of tags) {
      const box = at && {
        left: at.x - tag.offsetWidth / 2 - 4,
        right: at.x + tag.offsetWidth / 2 + 4,
        top: at.y - 6 - tag.offsetHeight,
        bottom: at.y - 6,
      }
      const shown =
        !!box &&
        box.left >= 0 &&
        box.right <= this.width &&
        box.top >= 0 &&
        !taken.some(
          (rect) =>
            box.left < rect.right &&
            box.right > rect.left &&
            box.top < rect.bottom &&
            box.bottom > rect.top,
        )
      if (shown) taken.push(box)
      tag.style.visibility = shown ? 'visible' : 'hidden'
      if (at)
        tag.style.transform = `translate(${at.x}px, ${at.y - 6}px) translate(-50%, -100%)`
    }
  }

  /** 칸 위 이름표 자리(캔버스 css px). 건물이 있으면 건물 꼭대기, 빈 부지는 땅 위 30단위. */
  private anchor(lot: number) {
    const item = this.slotOf(lot)
    if (!item) return null
    item.group.updateWorldMatrix(true, false)
    const point = item.group.localToWorld(
      this.scratch.set(0, item.height || 30, 0),
    )
    point.project(this.camera)
    return {
      x: ((point.x + 1) / 2) * this.width,
      y: ((1 - point.y) / 2) * this.height,
    }
  }

  /** 화면 점 아래의 부지·건물이 가리키는 칸 번호(lot). 없으면 undefined */
  pick(x: number, y: number) {
    this.ndc.set((x / this.width) * 2 - 1, -(y / this.height) * 2 + 1)
    this.raycaster.setFromCamera(this.ndc, this.camera)
    const targets = this.slots
      .filter((slot) => slot.group.visible)
      .flatMap((slot) => [slot.lawn, slot.building])
    const hit = this.raycaster.intersectObjects(targets, true)[0]
    let object: THREE.Object3D | null = hit?.object ?? null
    while (object && object.userData.slot === undefined) object = object.parent
    return object
      ? (this.slots[object.userData.slot as number]?.lot ?? undefined)
      : undefined
  }

  dispose() {
    if (this.disposed) return
    this.disposed = true
    for (const item of this.owned) item.dispose()
    for (const material of this.materials.values()) material.dispose()
    this.scene.clear()
    if (this.ownsRenderer) {
      this.renderer.dispose()
      this.canvas.remove()
    }
  }

  private own<T extends { dispose(): void }>(item: T) {
    this.owned.add(item)
    return item
  }

  private material(color: string, roughness = 1) {
    const key = `${color}|${roughness}`
    let material = this.materials.get(key)
    if (!material) {
      material = new THREE.MeshStandardMaterial({
        color,
        roughness,
        flatShading: true,
      })
      this.materials.set(key, material)
    }
    return material
  }

  private mesh(geometry: THREE.BufferGeometry, color: string, roughness = 1) {
    return new THREE.Mesh(this.own(geometry), this.material(color, roughness))
  }

  private flat(geometry: THREE.BufferGeometry, color: string, y: number) {
    const mesh = this.mesh(geometry, color)
    mesh.rotation.x = -Math.PI / 2
    mesh.position.y = y
    mesh.receiveShadow = true
    return mesh
  }

  /** 고리 위 반지름 r, 칸 단위 각 th 자리에 고리 바깥을 등지고 놓는다(캔버스 at()). */
  private at<T extends THREE.Object3D>(
    object: T,
    r: number,
    th: number,
    y = 0,
  ) {
    const a = slotAngle(th)
    object.position.set(r * Math.sin(a), y, r * Math.cos(a))
    object.rotation.y = a
    this.ring.add(object)
    return object
  }

  private slot(s: number) {
    let slot = this.slots[s]
    if (slot) return slot
    const group = new THREE.Group()
    group.userData.slot = s
    // 칸의 앞(+z)은 고리 중심을 본다.
    this.at(group, R, s).rotation.y += Math.PI
    const lawn = new THREE.Group()
    const building = new THREE.Group()
    const filler = this.filler(s)
    const number = new THREE.Sprite(this.numberMaterial(s + 1))
    number.position.set(0, 6, -70)
    group.add(lawn, building, filler, number)
    slot = {
      group,
      lawn,
      building,
      filler,
      number,
      key: '',
      height: 0,
      lot: null,
    }
    this.slots[s] = slot
    return slot
  }

  // 쓰지 않은 칸: 부지 모양 없이 둥근 잔디에 나무 두세 그루, 벤치, 꽃밭(v469 꾸밈의 결). 칸 번호를 시드로 개수·자리·
  // 모양을 정해 칸마다 다르게 놓는다.
  private filler(s: number) {
    const rnd = seeded(101 + s * 7919)
    const group = new THREE.Group()
    const patch = this.flat(this.sharedGeometry('filler'), '#d5e1bd', 1.9)
    group.add(patch)
    // 잔디(반지름 50) 안의 아무 자리
    const spot = (most = 36) => {
      const a = rnd() * Math.PI * 2
      const d = Math.sqrt(rnd()) * most
      return [Math.cos(a) * d, Math.sin(a) * d] as const
    }
    for (let i = 2 + Math.floor(rnd() * 3); i > 0; i--) {
      const tree = this.makeTree(rnd, 0.75 + rnd() * 0.3)
      const [x, z] = spot()
      tree.position.set(x, 1.9, z)
      group.add(tree)
    }
    const parts = this.decorParts()
    if (rnd() < 0.7) {
      const bench = new THREE.Mesh(parts.seat, this.material('#bca889'))
      const [x, z] = spot(30)
      bench.position.set(x, 4.5, z)
      bench.rotation.y = rnd() * Math.PI
      group.add(bench)
    }
    if (rnd() < 0.6) {
      const bed = this.flowerBed(rnd)
      const [x, z] = spot(28)
      bed.position.set(x, 3.5, z)
      bed.rotation.y = rnd() * Math.PI
      group.add(bed)
    }
    return group
  }

  /** 작은 꽃상자: 꽃 셋의 색을 자리마다 고른다. */
  private flowerBed(rnd: () => number) {
    const parts = this.decorParts()
    const bed = new THREE.Mesh(parts.bed, this.material('#c9b9a0'))
    for (const dx of [-6, 0, 6]) {
      const flower = new THREE.Mesh(
        parts.flower,
        this.material(FLOWERS[Math.floor(rnd() * FLOWERS.length)]),
      )
      flower.position.set(dx, 3, 0)
      bed.add(flower)
    }
    return bed
  }

  /** 나무: 둥근 잎이 대부분이고 섞을 때(mixed)는 셋에 하나꼴로 뾰족한 잎, 크기도 조금씩 다르다. 가로수는 섞지 않는다. */
  private makeTree(rnd: () => number, size: number, mixed = true) {
    const { trunk, crown, cone } = this.decorParts()
    const group = new THREE.Group()
    const stem = new THREE.Mesh(trunk, this.material('#a39078'))
    stem.position.y = 4.5
    const pine = mixed && rnd() < 0.3
    const top = new THREE.Mesh(
      pine ? cone : crown,
      this.material(GREENS[Math.floor(rnd() * GREENS.length)]),
    )
    top.position.y = pine ? 16 : 14
    top.rotation.y = rnd() * 3
    stem.castShadow = top.castShadow = true
    group.add(stem, top)
    group.scale.setScalar(size * (mixed ? 0.8 + rnd() * 0.4 : 1))
    return group
  }

  private parts?: Record<
    'trunk' | 'crown' | 'cone' | 'seat' | 'bed' | 'flower',
    THREE.BufferGeometry
  >
  private decorParts() {
    this.parts ??= {
      trunk: this.own(new THREE.CylinderGeometry(1.5, 2, 9, 6)),
      crown: this.own(new THREE.IcosahedronGeometry(8.5, 0)),
      cone: this.own(new THREE.ConeGeometry(7.5, 20, 7)),
      seat: this.own(new THREE.BoxGeometry(14, 3, 5)),
      bed: this.own(new THREE.BoxGeometry(22, 4, 10)),
      flower: this.own(new THREE.IcosahedronGeometry(3, 0)),
    }
    return this.parts
  }

  private lawn(kind: 'project' | 'next') {
    const [fill, edge] =
      kind === 'project' ? ['#d5e4b6', '#b8cc94'] : ['#fff0bd', '#f3c530']
    const group = new THREE.Group()
    const lawn = this.flat(this.sharedGeometry('lawn'), fill, 3.2)
    let material = this.edges.get(edge)
    if (!material) {
      material = this.own(new THREE.LineBasicMaterial({ color: edge }))
      this.edges.set(edge, material)
    }
    group.add(
      lawn,
      new THREE.LineLoop(this.sharedGeometry('lawn-edge'), material),
    )
    return group
  }

  // 고리가 돌 때마다 칸의 부지·건물을 다시 놓으므로 geometry·재질은 나눠 쓴다(새로 만들면 돌릴수록 쌓인다).
  private readonly edges = new Map<string, THREE.LineBasicMaterial>()
  private readonly tints = new Map<string, THREE.Material>()
  private readonly shared = new Map<string, THREE.BufferGeometry>()
  private sharedGeometry(key: 'lawn' | 'lawn-edge' | 'filler' | 'shell') {
    let geometry = this.shared.get(key)
    if (!geometry) {
      geometry =
        key === 'lawn'
          ? new THREE.PlaneGeometry(110, 110)
          : key === 'filler'
            ? new THREE.CircleGeometry(50, 28)
            : key === 'shell'
              ? new THREE.BoxGeometry(64, 80, 64)
              : new THREE.BufferGeometry().setFromPoints(
                  [
                    [-56, -56],
                    [56, -56],
                    [56, 56],
                    [-56, 56],
                  ].map(([x, z]) => new THREE.Vector3(x, 3.4, z)),
                )
      this.own(geometry)
      this.shared.set(key, geometry)
    }
    return geometry
  }

  // 모델을 받기 전과 받지 못한 환경에서는 흐린 상자를 세운다(D21).
  private building(slot: Slot, design: string) {
    const holder = slot.building
    const shell = this.mesh(this.sharedGeometry('shell'), '#ebe7dc')
    shell.position.y = 40
    shell.castShadow = true
    holder.add(shell)
    slot.height = 80
    const pick = kenneyBuildingOf(design)
    loadKenney(kenneyBuildingUrl(pick.model))
      .then((model) => {
        if (this.disposed || shell.parent !== holder) return
        const figure = cloneKenney(model)
        figure.scale.setScalar(FIT[design] ?? FIT.modern)
        figure.updateMatrixWorld(true)
        const box = new THREE.Box3().setFromObject(figure)
        const center = box.getCenter(new THREE.Vector3())
        figure.position.set(-center.x, -box.min.y, -center.z)
        figure.traverse((object) => {
          const mesh = object as THREE.Mesh
          if (!mesh.isMesh) return
          mesh.castShadow = true
          mesh.receiveShadow = true
          if (pick.tint) {
            const base = mesh.material as THREE.Material
            const key = `${base.uuid}|${pick.tint}`
            let tinted = this.tints.get(key)
            if (!tinted) {
              tinted = this.own(base.clone())
              ;(tinted as THREE.MeshStandardMaterial).color.set(pick.tint)
              this.tints.set(key, tinted)
            }
            mesh.material = tinted
          }
        })
        holder.remove(shell)
        holder.add(figure)
        slot.height = box.max.y - box.min.y
      })
      .catch(() => {})
  }

  // 칸 아래 번호 글자판. 번호마다 하나를 만들어 여러 칸이 나눠 쓴다(차례가 되풀이되면 같은 번호가 여러 칸에 보인다).
  private readonly numbers = new Map<number, THREE.SpriteMaterial>()
  private numberMaterial(n: number) {
    let material = this.numbers.get(n)
    if (material) return material
    const canvas = document.createElement('canvas')
    canvas.width = 64
    canvas.height = 48
    const g = canvas.getContext('2d')
    if (g) {
      g.font = 'bold 30px Geist, "Malgun Gothic", sans-serif'
      g.textAlign = 'center'
      g.textBaseline = 'middle'
      g.lineWidth = 7
      g.strokeStyle = '#ffffff'
      g.strokeText(String(n), 32, 25)
      g.fillStyle = '#6f6650'
      g.fillText(String(n), 32, 25)
    }
    const texture = this.own(new THREE.CanvasTexture(canvas))
    texture.colorSpace = THREE.SRGBColorSpace
    material = this.own(
      new THREE.SpriteMaterial({ map: texture, transparent: true }),
    )
    this.numbers.set(n, material)
    return material
  }

  // 캔버스 decor(): 고리 바깥(R + 86부터)에만 두고 작고 흐리게 칠해 부지·건물·빈 부지가 가장 눈에 띄게 한다.
  private buildDecor() {
    const rnd = seeded(7)
    const { seat } = this.decorParts()
    const tree = (r: number, th: number, s = 1, mixed = true) =>
      this.at(this.makeTree(rnd, s, mixed), r, th)
    const pole = this.own(new THREE.CylinderGeometry(0.9, 1.1, 26, 6))
    const lampHead = this.own(new THREE.BoxGeometry(5, 2.5, 3))
    const lamp = (r: number, th: number) => {
      const group = new THREE.Group()
      const stick = new THREE.Mesh(pole, this.material('#9a948a'))
      stick.position.y = 13
      const head = new THREE.Mesh(lampHead, this.material('#f1e7c6'))
      head.position.y = 26
      group.add(stick, head)
      return this.at(group, r, th)
    }
    const bench = (r: number, th: number) => {
      const group = new THREE.Group()
      const board = new THREE.Mesh(seat, this.material('#bca889'))
      board.position.y = 3
      group.add(board)
      this.at(group, r, th).rotation.y += Math.PI / 2
    }
    const ringFlat = (
      geometry: THREE.BufferGeometry,
      color: string,
      y: number,
    ) => this.ring.add(this.flat(geometry, color, y))

    // 순환 도로: 보도, 가운데 점선이 있는 차도, 보도
    ringFlat(new THREE.RingGeometry(R + 86, R + 104, 192), '#e9e3d6', 1.1)
    ringFlat(new THREE.RingGeometry(R + 104, R + 150, 192), '#cdc7bb', 1.0)
    ringFlat(new THREE.RingGeometry(R + 150, R + 166, 192), '#e9e3d6', 1.1)
    const dash = this.own(new THREE.BoxGeometry(1.6, 0.4, 9))
    for (let th = 0; th < N; th += 0.2)
      this.at(
        new THREE.Mesh(dash, this.material('#f7f3ea')),
        R + 127,
        th,
        1.3,
      ).rotation.y += Math.PI / 2

    // 3칸마다 바깥으로 뻗는 길, 작은 공원
    const parks: number[] = []
    const len = 620
    const street = this.own(new THREE.BoxGeometry(40, 0.6, len))
    const walk = this.own(new THREE.BoxGeometry(12, 0.8, len))
    const park = this.own(new THREE.CircleGeometry(70, 40))
    const pond = this.own(new THREE.CircleGeometry(24, 28))
    for (let k = 0; k < N / 3; k++) {
      const th0 = 3 * k + 0.5
      const mid = R + 166 + len / 2
      this.at(new THREE.Mesh(street, this.material('#cdc7bb')), mid, th0, 0.9)
      for (const side of [-1, 1]) {
        this.at(
          new THREE.Mesh(walk, this.material('#e9e3d6')),
          mid,
          th0,
          0.95,
        ).translateX(side * 26)
        for (let r = R + 190; r < R + 700; r += 46)
          tree(r, th0, 0.85, false).translateX(side * 38)
      }
      const pr = R + 240
      const pth = 3 * k + 1.9
      const lawn = this.at(
        new THREE.Mesh(park, this.material('#d5e1bd')),
        pr,
        pth,
        0.8,
      )
      lawn.rotation.set(-Math.PI / 2, 0, 0)
      lawn.receiveShadow = true
      const water = this.at(
        new THREE.Mesh(pond, this.material('#c7d9da')),
        pr + 14,
        pth + 0.15,
        0.9,
      )
      water.rotation.set(-Math.PI / 2, 0, 0)
      for (let i = 0; i < 7; i++)
        tree(
          pr + (rnd() - 0.5) * 120,
          pth + (rnd() - 0.5) * 0.75,
          0.8 + rnd() * 0.35,
        )
      bench(pr - 40, pth - 0.05)
      bench(pr - 40, pth + 0.3)
      parks.push(pth)
    }
    // 화면 모서리에는 순환 도로 너머 R + 380쯤까지만 보여 가로수와 먼 건물은 그 띠에 둔다.
    const nearStreet = (th: number) =>
      Math.abs(((((th - 0.5) % 3) + 4.5) % 3) - 1.5) < 0.5
    const inPark = (r: number, th: number) =>
      parks.some(
        (p) => Math.hypot(r - (R + 240), ((th - p) * 2 * Math.PI * r) / N) < 90,
      )
    for (let th = 0; th < N; th += 0.28) {
      const t1 = th + (rnd() - 0.5) * 0.12
      const r1 = R + 185 + rnd() * 60
      if (!nearStreet(t1) && !inPark(r1, t1)) tree(r1, t1, 0.75 + rnd() * 0.3)
    }
    const farSpots: { r: number; th: number; s: number; i: number }[] = []
    for (let th = 0.2, i = 0; th < N; th += 0.42, i++) {
      const t1 = th + (rnd() - 0.5) * 0.1
      const r1 = R + 265 + rnd() * 70
      const s = 22 + rnd() * 10
      if (!nearStreet(t1) && !inPark(r1, t1))
        farSpots.push({ r: r1, th: t1, s, i })
    }
    this.farBuildings(farSpots)

    // 고리 위 r, 칸 th 자리에서 most단위 안의 아무 자리(반지름 쪽과 둘레 쪽으로 흩는다)
    const near = (r: number, th: number, most: number) => {
      const a = rnd() * Math.PI * 2
      const d = Math.sqrt(rnd()) * most
      return [
        r + Math.cos(a) * d,
        th + (Math.sin(a) * d * N) / (2 * Math.PI * r),
      ] as const
    }

    // 고리 위: 칸 사이 쉼터, 안쪽 화단(이웃 칸이 둘 다 쓰일 때만), 가로등과 가로수.
    // 쉼터는 부지(110단위) 사이 빈 땅(P − 110) 가운데의 둥근 잔디다. 잔디 크기, 나무 수(1~3), 벤치·꽃상자 유무와 자리를
    // 자리마다 다르게 정해 같은 모양이 줄지어 반복되지 않게 한다.
    const gapHalf = (P - 110) / 2
    const patch = this.own(new THREE.CircleGeometry(gapHalf - 8, 24))
    for (let s = 0; s < N; s++) {
      const g = s + 0.5
      const size = 0.75 + rnd() * 0.25
      const lawn = this.at(
        new THREE.Mesh(patch, this.material('#d5e1bd')),
        R,
        g,
        1.7,
      )
      lawn.rotation.set(-Math.PI / 2, 0, 0)
      lawn.scale.setScalar(size)
      lawn.receiveShadow = true
      const room = (gapHalf - 16) * size
      for (let i = 1 + Math.floor(rnd() * 3); i > 0; i--) {
        const [r, th] = near(R, g, room)
        tree(r, th, 0.75 + rnd() * 0.3)
      }
      if (rnd() < 0.55) {
        const [r, th] = near(R, g, room)
        this.at(
          new THREE.Mesh(seat, this.material('#bca889')),
          r,
          th,
          3,
        ).rotation.y += rnd() * Math.PI
      }
      if (rnd() < 0.3) {
        const [r, th] = near(R, g, room)
        this.at(this.flowerBed(rnd), r, th, 3.5).rotation.y += rnd() * Math.PI
      }
      const box = this.at(this.flowerBed(rnd), R - 92, g, 2)
      box.rotation.y += Math.PI / 2
      box.visible = false
      this.gaps.push([box])
      lamp(R + 95, s + 0.25)
      if (s % 3 !== 0) tree(R + 95, s + 0.5, 0.75, false)
      tree(R + 178, s + 0.15, 0.8, false)
      tree(R + 178, s + 0.85, 0.8, false)
    }

    // 고리 안쪽 공원: 벌집 둘레 연못, 세 칸마다 벌집에서 뻗는 길, 산책로와 가로등. 칸마다 숲·꽃밭·작은 광장·잔디밭 중
    // 하나를 고르고 자리·크기·색을 흩어, 칸마다 같은 구성이 되풀이되지 않게 한다.
    const inner0 = 72
    const inner1 = R - 105
    const spoke = this.own(new THREE.BoxGeometry(10, 0.5, inner1 - inner0))
    const soil = this.own(new THREE.BoxGeometry(44, 1, 26))
    const strip = this.own(new THREE.BoxGeometry(40, 1.6, 5))
    const paving = this.own(new THREE.CircleGeometry(20, 24))
    const basin = this.own(new THREE.CylinderGeometry(9, 10, 3, 16))
    const water = this.own(new THREE.CylinderGeometry(7, 7, 3.4, 16))
    const comb = this.own(new THREE.CylinderGeometry(5, 5, 9, 6))
    const blanket = this.own(new THREE.BoxGeometry(14, 0.6, 10))
    ringFlat(new THREE.RingGeometry(34, 62, 48), '#c7d9da', 1.95)
    ringFlat(new THREE.RingGeometry(62, 70, 48), '#e9e3d6', 1.95)
    ringFlat(new THREE.RingGeometry(R - 146, R - 134, 192), '#e9e3d6', 1.6)
    for (let s = 0; s < N; s++) {
      const g = s + 0.5
      if (s % 3 === 0)
        this.at(
          new THREE.Mesh(spoke, this.material('#eee6d2')),
          (inner0 + inner1) / 2,
          s,
          1.9,
        )
      lamp(R - 126, g)
      if (rnd() < 0.6)
        this.at(
          new THREE.Mesh(seat, this.material('#bca889')),
          R - 152,
          s + 0.1 + rnd() * 0.8,
          3,
        )
      for (let i = Math.floor(rnd() * 3); i > 0; i--)
        tree(R - 170 + rnd() * 16, s + 0.1 + rnd() * 0.8, 0.7)
      const r = R - 265 + rnd() * 50
      const th = s + 0.3 + rnd() * 0.4
      const pick = rnd()
      if (pick < 0.35) {
        // 숲: 나무 3~6그루
        for (let i = 3 + Math.floor(rnd() * 4); i > 0; i--) {
          const [tr, tt] = near(r, th, 34)
          tree(tr, tt, 0.7 + rnd() * 0.4)
        }
      } else if (pick < 0.6) {
        // 꽃밭: 크기·기울기·줄 수(2~4)·색이 다르다
        const garden = this.at(
          new THREE.Mesh(soil, this.material('#c9b9a0')),
          r,
          th,
          2,
        )
        garden.rotation.y += (rnd() - 0.5) * 0.8
        garden.scale.set(0.7 + rnd() * 0.5, 1, 0.8 + rnd() * 0.3)
        const rows = 2 + Math.floor(rnd() * 3)
        for (let i = 0; i < rows; i++) {
          const row = new THREE.Mesh(
            strip,
            this.material(FLOWERS[Math.floor(rnd() * FLOWERS.length)]),
          )
          row.position.set(0, 1, (i - (rows - 1) / 2) * (20 / rows))
          garden.add(row)
        }
        const [tr, tt] = near(r, th, 40)
        tree(tr, tt, 0.8)
      } else if (pick < 0.8) {
        // 작은 광장: 포장한 원 가운데 분수나 벌집 조형물, 마주 보는 벤치
        const floor = this.at(
          new THREE.Mesh(paving, this.material('#eee6d2')),
          r,
          th,
          1.9,
        )
        floor.rotation.set(-Math.PI / 2, 0, 0)
        floor.receiveShadow = true
        const middle = new THREE.Group()
        if (rnd() < 0.5) {
          const rim = new THREE.Mesh(basin, this.material('#d8d2c6'))
          rim.position.y = 1.5
          const top = new THREE.Mesh(water, this.material('#c7d9da'))
          top.position.y = 1.7
          middle.add(rim, top)
        } else {
          const pillar = new THREE.Mesh(comb, this.material('#f1d98a'))
          pillar.position.y = 4.5
          pillar.castShadow = true
          middle.add(pillar)
        }
        this.at(middle, r, th, 1.9)
        for (const side of [-1, 1])
          this.at(
            new THREE.Mesh(seat, this.material('#bca889')),
            r + side * 15,
            th,
            3,
          )
        for (let i = 1 + Math.floor(rnd() * 2); i > 0; i--) {
          const [tr, tt] = near(r, th, 44)
          tree(tr, tt, 0.8)
        }
      } else {
        // 잔디밭: 나무 한두 그루와 돗자리
        for (let i = 1 + Math.floor(rnd() * 2); i > 0; i--) {
          const [tr, tt] = near(r, th, 30)
          tree(tr, tt, 0.85)
        }
        if (rnd() < 0.6) {
          const [br, bt] = near(r, th, 14)
          this.at(
            new THREE.Mesh(
              blanket,
              this.material(FLOWERS[Math.floor(rnd() * FLOWERS.length)]),
            ),
            br,
            bt,
            2,
          ).rotation.y += rnd() * Math.PI
        }
      }
    }

    // 순환 도로의 차와 버스, 정류장, 바깥 길 입구의 건널목과 길가에 세운 차
    const body = this.own(new THREE.BoxGeometry(20, 6, 10))
    const cabin = this.own(new THREE.BoxGeometry(11, 5, 8))
    const car = (r: number, th: number, i: number) => {
      const group = new THREE.Group()
      const b = new THREE.Mesh(
        body,
        this.material(CAR_COLORS[i % CAR_COLORS.length]),
      )
      b.position.y = 4
      const c = new THREE.Mesh(cabin, this.material('#f4f1ea'))
      c.position.set(-1, 9.5, 0)
      b.castShadow = c.castShadow = true
      group.add(b, c)
      return this.at(group, r, th, 1)
    }
    // 차로마다 고르게 나눠 흔들어 놓아 차끼리 파고들지 않게 하고, 바깥 차로는 버스 자리를 비운다.
    let paint = 0
    for (const th of laneSpots(N * 0.75, rnd)) car(R + 116, th, paint++)
    for (const th of laneSpots(N * 0.75, rnd, BUS_SPOTS))
      car(R + 138, th, paint++)
    const coach = this.own(new THREE.BoxGeometry(46, 12, 13))
    const glass = this.own(new THREE.BoxGeometry(42, 3.5, 13.4))
    const roof = this.own(new THREE.BoxGeometry(24, 1.5, 9))
    const panel = this.own(new THREE.BoxGeometry(24, 10, 1))
    for (const at of BUS_SPOTS) {
      const bus = new THREE.Group()
      const b = new THREE.Mesh(coach, this.material('#f1d27a'))
      b.position.y = 7
      const w = new THREE.Mesh(glass, this.material('#e7eef0'))
      w.position.y = 9
      b.castShadow = true
      bus.add(b, w)
      this.at(bus, R + 138, at, 1)
      const stop = new THREE.Group()
      const top = new THREE.Mesh(roof, this.material('#d8d2c6'))
      top.position.y = 13
      const back = new THREE.Mesh(panel, this.material('#cfe0e3'))
      back.position.set(0, 7, 4)
      top.castShadow = true
      stop.add(top, back)
      this.at(stop, R + 158, at + 0.15)
    }
    const stripe = this.own(new THREE.BoxGeometry(3.5, 0.3, 14))
    for (let k = 0; k < N / 3; k++) {
      const th0 = 3 * k + 0.5
      for (let x = -15; x <= 15; x += 6)
        this.at(
          new THREE.Mesh(stripe, this.material('#f7f3ea')),
          R + 182,
          th0,
          1.2,
        ).translateX(x)
      for (let r = R + 210; r < R + 560; r += 34)
        if (rnd() < 0.45)
          car(r, th0, Math.floor(rnd() * 6)).translateX(
            rnd() < 0.5 ? -14 : 14,
          ).rotation.y += Math.PI / 2
    }

    // 사람: 칸 앞 광장, 안쪽 산책로, 바깥 보도, 정류장
    const torso = this.own(new THREE.CylinderGeometry(1.8, 2.2, 7, 6))
    const face = this.own(new THREE.IcosahedronGeometry(2, 0))
    const person = (r: number, th: number) => {
      const group = new THREE.Group()
      const b = new THREE.Mesh(
        torso,
        this.material(PEOPLE_COLORS[Math.floor(rnd() * PEOPLE_COLORS.length)]),
      )
      b.position.y = 3.5
      const h = new THREE.Mesh(face, this.material('#f1d9c0'))
      h.position.y = 9
      b.castShadow = h.castShadow = true
      group.add(b, h)
      return this.at(group, r, th, 1.5)
    }
    for (let s = 0; s < N; s++) {
      person(R + 68 + rnd() * 10, s + 0.5 + (rnd() - 0.5) * 0.2)
      if (rnd() < 0.6) person(R + 72, s + 0.35 + rnd() * 0.3)
      person(R - 140 + (rnd() - 0.5) * 6, s + rnd())
      person(R + 158, s + rnd())
      if (rnd() < 0.5) person(R + 95, s + rnd())
    }
    for (const at of BUS_SPOTS) {
      person(R + 154, at + 0.1)
      person(R + 154, at + 0.2)
    }

    // 가로수 띠 사이 작은 건물(Kenney low-detail)
    const small: { r: number; th: number; s: number; i: number }[] = []
    for (let th = 0.1, i = 0; th < N; th += 0.5, i++) {
      const t1 = th + (rnd() - 0.5) * 0.12
      const r1 = R + 212 + rnd() * 20
      if (!nearStreet(t1) && !inPark(r1, t1))
        small.push({ r: r1, th: t1, s: 13 + rnd() * 5, i })
    }
    this.smallBuildings(small)
  }

  private smallBuildings(
    spots: { r: number; th: number; s: number; i: number }[],
  ) {
    void Promise.all(
      SMALL_MODELS.map((name) => loadKenney(kenneyBuildingUrl(name))),
    )
      .then((models) => {
        if (this.disposed) return
        for (const spot of spots) {
          const figure = cloneKenney(models[spot.i % models.length])
          const tint = this.material(SMALL_TINTS[spot.i % SMALL_TINTS.length])
          figure.traverse((object) => {
            const mesh = object as THREE.Mesh
            if (!mesh.isMesh) return
            mesh.material = tint
            mesh.castShadow = mesh.receiveShadow = true
          })
          figure.scale.setScalar(spot.s)
          const group = new THREE.Group()
          group.add(figure)
          this.at(group, spot.r, spot.th).rotation.y += Math.PI
        }
      })
      .catch(() => {})
  }

  private farBuildings(
    spots: { r: number; th: number; s: number; i: number }[],
  ) {
    const pale = this.material('#dcd6ca')
    void Promise.all(
      FAR_MODELS.map((name) => loadKenney(kenneyBuildingUrl(name))),
    )
      .then((models) => {
        if (this.disposed) return
        for (const spot of spots) {
          const figure = cloneKenney(models[spot.i % models.length])
          figure.traverse((object) => {
            const mesh = object as THREE.Mesh
            if (!mesh.isMesh) return
            mesh.material = pale
            mesh.receiveShadow = true
          })
          figure.scale.setScalar(spot.s)
          const group = new THREE.Group()
          group.add(figure)
          this.at(group, spot.r, spot.th).rotation.y += Math.PI
        }
      })
      .catch(() => {})
  }
}
