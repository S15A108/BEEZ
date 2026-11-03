import * as THREE from 'three'
import { cloneKenney, loadKenney } from '../kenney'
import { kenneyBuildingOf, kenneyBuildingUrl } from '../buildingModels'
import { RING_RADIUS as R, RING_SLOTS as N, ringSlot } from './ring'

// 원형 도시(캔버스 SCR-04-v2-city v474, 그림 코드 ring2.html). 칸은 고리를 따라 놓이고 고리가 통째로 돈다.
// 주변(순환 도로·보도·가로수·공원·가로등·벤치·흐린 먼 건물)은 고리와 함께 돌고, 고리 안쪽은 비워 둔다(D26).
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
// 화면 폭에 담는 단위 수와, 고리 중심을 화면 위 끝에서 띄우는 거리(단위). 나의 도시 보드 1300/62, 만들기 배경 820/70.
const FRAME = {
  city: { view: 1300, top: 62 },
  backdrop: { view: 820, top: 70 },
}
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

interface Slot {
  group: THREE.Group
  lawn: THREE.Group
  building: THREE.Group
  /** 쓰지 않은 칸의 꾸밈(잔디·나무·벤치·꽃밭). 부지 모양은 프로젝트 칸과 빈 부지 하나에만 둔다. */
  filler: THREE.Group
  number: THREE.Sprite
  key: string
  height: number
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
  /** 이웃 칸이 둘 다 쓰일 때만 보이는 생울타리·화단. gaps[s]는 칸 s와 s+1 사이 */
  private readonly gaps: THREE.Object3D[][] = []
  private readonly highlight: THREE.Mesh
  private used = new Set<number>()
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
    Object.assign(sun.shadow.camera, {
      left: -1100,
      right: 1100,
      top: 1100,
      bottom: -1100,
      near: 10,
      far: 2600,
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
    const frame = view.backdrop ? FRAME.backdrop : FRAME.city
    return this.width / frame.view
  }

  /** 프로젝트 칸과 빈 부지 칸을 맞춘다. 같은 내용이면 아무것도 하지 않는다. */
  setProjects(projects: readonly RingProject[], next: number | null) {
    if (this.disposed) return
    const signature = JSON.stringify([projects, next])
    if (signature === this.signature) return
    this.signature = signature
    this.used = new Set(projects.map((project) => project.slot))
    if (next !== null) this.used.add(next)
    for (let s = 0; s < N; s++) {
      const project = projects.find((item) => item.slot === s)
      const slot = this.slot(s)
      const key = project ? `p:${project.design}` : s === next ? 'next' : ''
      if (slot.key === key) continue
      slot.key = key
      slot.lawn.clear()
      slot.building.clear()
      slot.height = 0
      if (!key) continue
      slot.lawn.add(this.lawn(project ? 'project' : 'next'))
      if (project) this.building(slot, project.design)
    }
    this.gaps.forEach((items, s) => {
      const on = this.used.has(s) && this.used.has((s + 1) % N)
      for (const item of items) item.visible = on
    })
  }

  render(view: RingView) {
    if (this.disposed) return
    this.ring.rotation.y = (-view.rot * 2 * Math.PI) / N
    const ppu = this.pixelsPerUnit(view)
    for (const [s, slot] of this.slots.entries()) {
      const { size } = ringSlot(s, view.rot)
      const used = this.used.has(s)
      slot.group.visible = size > 0.001
      slot.group.scale.setScalar(Math.max(size, 0.001))
      slot.filler.visible = !used
      slot.number.visible = used && size > 0.85
      // 보드 그림(2배 해상도)에서 64×48px 글자판을 0.42배로 찍은 크기: 화면에서 13×10px
      slot.number.scale.set((64 * 0.21) / ppu, (48 * 0.21) / ppu, 1)
    }
    const hovered =
      view.hover !== null && this.used.has(view.hover)
        ? this.slots[view.hover]
        : undefined
    if (hovered) hovered.group.add(this.highlight)
    else this.highlight.removeFromParent()

    const frame = view.backdrop ? FRAME.backdrop : FRAME.city
    const w = frame.view
    const halfH = (w * this.height) / this.width / 2
    const d = (halfH - (frame.top * w) / frame.view) / Math.sin(EL)
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

  /** 이름표(HTML, data-slot)를 칸 위로 옮긴다. 반원 끝을 넘어 줄어든 칸과, 위 끝에서 top px 안으로 올라간 칸의 이름표는 숨긴다. */
  placeTags(overlay: HTMLElement | null, top: number) {
    for (const element of overlay?.children ?? []) {
      const tag = element as HTMLElement
      const at = this.anchor(Number(tag.dataset.slot))
      const shown = !!at && at.y > top
      tag.style.visibility = shown ? 'visible' : 'hidden'
      if (at)
        tag.style.transform = `translate(${at.x}px, ${at.y - 6}px) translate(-50%, -100%)`
    }
  }

  /** 칸 위 이름표 자리(캔버스 css px). 건물이 있으면 건물 꼭대기, 빈 부지는 땅 위 30단위. */
  private anchor(slot: number) {
    const item = this.slots[slot]
    if (!item?.group.visible || !item.number.visible) return null
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

  /** 화면 점 아래의 칸(부지·건물). 없으면 undefined */
  pick(x: number, y: number) {
    this.ndc.set((x / this.width) * 2 - 1, -(y / this.height) * 2 + 1)
    this.raycaster.setFromCamera(this.ndc, this.camera)
    const targets = this.slots
      .filter((slot) => slot.group.visible)
      .flatMap((slot) => [slot.lawn, slot.building])
    const hit = this.raycaster.intersectObjects(targets, true)[0]
    let object: THREE.Object3D | null = hit?.object ?? null
    while (object && object.userData.slot === undefined) object = object.parent
    return object?.userData.slot as number | undefined
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
    const number = this.numberSprite(s + 1)
    number.position.set(0, 6, -70)
    group.add(lawn, building, filler, number)
    slot = { group, lawn, building, filler, number, key: '', height: 0 }
    this.slots[s] = slot
    return slot
  }

  // 쓰지 않은 칸: 부지 모양 없이 둥근 잔디에 나무 셋, 벤치, 꽃밭(v469 꾸밈의 결). 칸마다 조금씩 다르게 놓는다.
  private filler(s: number) {
    const group = new THREE.Group()
    const patch = this.flat(this.sharedGeometry('filler'), '#d5e1bd', 1.9)
    group.add(patch)
    const turn = s % 2 ? 1 : -1
    const parts = this.decorParts()
    for (const [x, z, size, color] of [
      [-24 * turn, 12, 0.95, '#adc196'],
      [20 * turn, -8, 0.8, '#9fb68b'],
      [-4 * turn, -26, 0.7, '#adc196'],
    ] as const) {
      const tree = new THREE.Group()
      const stem = new THREE.Mesh(parts.trunk, this.material('#a39078'))
      stem.position.y = 4.5
      const top = new THREE.Mesh(parts.crown, this.material(color))
      top.position.y = 14
      top.rotation.y = s + x
      stem.castShadow = top.castShadow = true
      tree.add(stem, top)
      tree.position.set(x, 1.9, z)
      tree.scale.setScalar(size)
      group.add(tree)
    }
    const bench = new THREE.Mesh(parts.seat, this.material('#bca889'))
    bench.position.set(16 * turn, 4.5, 20)
    bench.rotation.y = 0.4 * turn
    const bed = new THREE.Mesh(parts.bed, this.material('#c9b9a0'))
    bed.position.set(-10 * turn, 3.5, 26)
    for (const [dx, color] of [
      [-6, '#e3b7b0'],
      [0, '#e8d58f'],
      [6, '#d9b8cf'],
    ] as const) {
      const flower = new THREE.Mesh(parts.flower, this.material(color))
      flower.position.set(dx, 3, 0)
      bed.add(flower)
    }
    group.add(bench, bed)
    return group
  }

  private parts?: Record<
    'trunk' | 'crown' | 'seat' | 'bed' | 'flower',
    THREE.BufferGeometry
  >
  private decorParts() {
    this.parts ??= {
      trunk: this.own(new THREE.CylinderGeometry(1.5, 2, 9, 6)),
      crown: this.own(new THREE.IcosahedronGeometry(8.5, 0)),
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
    const line = new THREE.LineLoop(
      this.sharedGeometry('lawn-edge'),
      this.own(new THREE.LineBasicMaterial({ color: edge })),
    )
    group.add(lawn, line)
    return group
  }

  private readonly shared = new Map<string, THREE.BufferGeometry>()
  private sharedGeometry(key: 'lawn' | 'lawn-edge' | 'filler') {
    let geometry = this.shared.get(key)
    if (!geometry) {
      geometry =
        key === 'lawn'
          ? new THREE.PlaneGeometry(110, 110)
          : key === 'filler'
            ? new THREE.CircleGeometry(50, 28)
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
    const shell = this.mesh(new THREE.BoxGeometry(64, 80, 64), '#ebe7dc')
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
            mesh.material = this.own((mesh.material as THREE.Material).clone())
            ;(mesh.material as THREE.MeshStandardMaterial).color.set(pick.tint)
          }
        })
        holder.remove(shell)
        holder.add(figure)
        slot.height = box.max.y - box.min.y
      })
      .catch(() => {})
  }

  private numberSprite(n: number) {
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
    return new THREE.Sprite(
      this.own(new THREE.SpriteMaterial({ map: texture, transparent: true })),
    )
  }

  // 캔버스 decor(): 고리 바깥(R + 86부터)에만 두고 작고 흐리게 칠해 부지·건물·빈 부지가 가장 눈에 띄게 한다.
  private buildDecor() {
    let seed = 7
    const rnd = () => (seed = (seed * 16807) % 2147483647) / 2147483647
    const { trunk, crown, seat, bed, flower } = this.decorParts()
    const tree = (r: number, th: number, s = 1) => {
      const group = new THREE.Group()
      const stem = new THREE.Mesh(trunk, this.material('#a39078'))
      stem.position.y = 4.5
      const top = new THREE.Mesh(
        crown,
        this.material(rnd() < 0.5 ? '#adc196' : '#9fb68b'),
      )
      top.position.y = 14
      top.rotation.y = rnd() * 3
      stem.castShadow = top.castShadow = true
      group.add(stem, top)
      group.scale.setScalar(s)
      return this.at(group, r, th)
    }
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
          tree(r, th0, 0.85).translateX(side * 38)
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

    // 고리 위: 칸 사이 낮은 생울타리와 안쪽 화단(이웃 칸이 둘 다 쓰일 때만), 가로등과 칸 사이 나무
    const hedge = this.own(new THREE.BoxGeometry(9, 7, 92))
    for (let s = 0; s < N; s++) {
      const h = this.at(
        new THREE.Mesh(hedge, this.material('#a9bd92')),
        R,
        s + 0.5,
        3.5,
      )
      h.castShadow = true
      const box = this.at(
        new THREE.Mesh(bed, this.material('#c9b9a0')),
        R - 92,
        s + 0.5,
        2,
      )
      box.rotation.y += Math.PI / 2
      for (const [dx, color] of [
        [-6, '#e3b7b0'],
        [0, '#e8d58f'],
        [6, '#d9b8cf'],
      ] as const) {
        const f = new THREE.Mesh(flower, this.material(color))
        f.position.set(0, 3, dx)
        box.add(f)
      }
      h.visible = box.visible = false
      this.gaps.push([h, box])
      lamp(R + 95, s + 0.25)
      if (s % 3 !== 0) tree(R + 95, s + 0.5, 0.75)
      tree(R + 178, s + 0.15, 0.8)
      tree(R + 178, s + 0.85, 0.8)
    }
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
