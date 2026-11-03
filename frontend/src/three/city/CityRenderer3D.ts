import * as THREE from 'three'
import { cloneKenney, loadKenney } from '../kenney'
import { kenneyBuildingOf, kenneyBuildingUrl } from '../buildingModels'
import { QUEUE_PITCH as PITCH, queueSlot } from './queue'

// 나의 도시(D26): 곧은 거리 하나를 따라 부지가 한 줄로 늘어선 일직선 순환 큐(queue.ts). 거리는 화면 가로로 흐르고,
// 넘기면 칸과 주변(보도·가로수·공원·가로등·벤치·흐린 먼 건물, v469 결)이 함께 옆으로 흐른다.
// 지역 좌표: 거리 방향 = +x(화면 오른쪽), +z = 카메라 쪽. 부지는 z = 0, 그 앞(+z)에 보도와 차도가 있다.
export interface CityLot {
  id: string
  /** 부지 번호(0부터) */
  slot: number
  design: string
}
export interface CityView {
  /** 몇 칸 넘겼는지(소수 가능) */
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
const EL = Math.PI / 4
const AZ = Math.PI / 4
// 주변 꾸밈은 3칸마다 되풀이되는 무늬다(넘길 때 무늬 한 마디만큼 되감아 끝없이 이어 보인다).
const PERIOD = 3 * PITCH
const COPIES = 3
// 먼 건물은 한 가지 흐린 색으로 칠한다(캔버스 b·c·d·f·h·k 중 레포에 없는 c·k 대신 a·g).
const FAR_MODELS = [
  'building-b',
  'building-a',
  'building-d',
  'building-f',
  'building-h',
  'building-g',
]

type Box = { left: number; top: number; right: number; bottom: number }

interface Lot {
  group: THREE.Group
  lawn: THREE.Group
  building: THREE.Group
  /** 부지·건물을 45° 돌려 담는 판 */
  plot: THREE.Group
  /** 오른쪽 이웃과의 사이 꾸밈 */
  between: THREE.Object3D
  number: THREE.Sprite
  slot: number
  key: string
  height: number
}

export class CityRenderer3D {
  readonly canvas: HTMLCanvasElement
  private readonly renderer: THREE.WebGLRenderer
  private readonly ownsRenderer: boolean
  private readonly scene = new THREE.Scene()
  private readonly camera = new THREE.OrthographicCamera(-1, 1, 1, -1, 1, 8000)
  /** 거리 좌표계(화면 가로로 돌려 둠) */
  private readonly street = new THREE.Group()
  /** 넘길 때 함께 흐르는 주변 꾸밈 */
  private readonly decor = new THREE.Group()
  private readonly raycaster = new THREE.Raycaster()
  private readonly ndc = new THREE.Vector2()
  private readonly scratch = new THREE.Vector3()
  private readonly materials = new Map<string, THREE.MeshStandardMaterial>()
  private readonly owned = new Set<{ dispose(): void }>()
  /** 줄 순서대로: 프로젝트(부지 번호 순), 빈 부지 */
  private lots: Lot[] = []
  private readonly highlight: THREE.Mesh
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

    // 거리를 화면 가로로: 카메라가 +x+z 쪽에서 45°로 보므로 지역 +x를 (1, 0, -1) 방향으로 돌린다.
    this.street.rotation.y = Math.PI / 4
    this.scene.add(
      this.flat(new THREE.PlaneGeometry(8000, 8000), '#e1e9cd', 0),
      this.street,
    )
    this.street.add(this.decor)
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

  /** 화면 1단위가 몇 px인지. 끌기 거리를 칸 수로 바꿀 때 쓴다. */
  pixelsPerUnit(view: CityView) {
    return this.width / this.viewUnits(view)
  }

  // 화면 폭에 담는 단위 수. 1단위를 1.25px로 그려 넓은 화면은 1050단위(가운데 다섯 칸과 양 끝)까지 담고, 좁은 화면은
  // 480단위(가운데 칸과 양옆)까지 줄인다. 만들기 창 배경은 늘 760단위로 가깝게 본다.
  private viewUnits(view: CityView) {
    return view.backdrop
      ? 760
      : Math.min(1050, Math.max(480, this.width / 1.25))
  }

  /** 줄을 맞춘다: 프로젝트(부지 번호 순) 다음에 빈 부지. 같은 내용이면 아무것도 하지 않는다. */
  setProjects(projects: readonly CityLot[], next: number | null) {
    if (this.disposed) return
    const signature = JSON.stringify([projects, next])
    if (signature === this.signature) return
    this.signature = signature
    const wanted = [
      ...[...projects]
        .sort((a, b) => a.slot - b.slot)
        .map((project) => ({ slot: project.slot, key: `p:${project.design}` })),
      ...(next === null ? [] : [{ slot: next, key: 'next' }]),
    ]
    const old = this.lots
    this.lots = wanted.map(({ slot, key }) => {
      const reuse = old.findIndex((lot) => lot.slot === slot && lot.key === key)
      if (reuse >= 0) return old.splice(reuse, 1)[0]
      return this.lot(slot, key)
    })
    for (const lot of old) lot.group.removeFromParent()
  }

  render(view: CityView) {
    if (this.disposed) return
    const count = this.lots.length
    const ppu = this.pixelsPerUnit(view)
    // 주변 무늬는 넘긴 거리만큼 흐르고 한 마디마다 되감는다.
    this.decor.position.x = -((((view.rot * PITCH) % PERIOD) + PERIOD) % PERIOD)
    this.lots.forEach((lot, index) => {
      const { d, size } = queueSlot(index, view.rot, count)
      lot.group.position.x = d * PITCH
      lot.group.visible = size > 0.001
      lot.group.scale.setScalar(Math.max(size, 0.001))
      lot.number.visible = size > 0.85
      // 보드 그림(2배 해상도)에서 64×48px 글자판을 0.42배로 찍은 크기: 화면에서 13×10px
      lot.number.scale.set((64 * 0.21) / ppu, (48 * 0.21) / ppu, 1)
      // 오른쪽 이웃과의 사이 꾸밈. 줄 끝(빈 부지)과 처음이 만나는 이음매에는 두지 않는다.
      lot.between.visible = index < count - 1
    })
    const hovered = this.lots.find((lot) => lot.slot === view.hover)
    if (hovered) hovered.plot.add(this.highlight)
    else this.highlight.removeFromParent()

    const w = this.viewUnits(view)
    const halfH = (w * this.height) / this.width / 2
    // 부지 줄을 화면 높이의 64%에 둔다(위에 건물, 아래에 거리). 바라보는 점을 거리 뒤쪽(-z)으로 그만큼 옮긴다.
    const look = this.street.localToWorld(
      this.scratch.set(0, 0, -((0.64 - 0.5) * 2 * halfH) / Math.sin(EL)),
    )
    Object.assign(this.camera, {
      left: -w / 2,
      right: w / 2,
      top: halfH,
      bottom: -halfH,
    })
    this.camera.position.set(
      look.x + 3000 * Math.sin(AZ) * Math.cos(EL),
      3000 * Math.sin(EL),
      look.z + 3000 * Math.cos(AZ) * Math.cos(EL),
    )
    this.camera.lookAt(look.x, 0, look.z)
    this.camera.updateProjectionMatrix()
    this.renderer.render(this.scene, this.camera)
  }

  /**
   * 이름표(HTML, data-slot)를 칸 위로 옮긴다. 양 끝에서 가라앉는 칸의 이름표와, 겹치면 안 되는 화면 글자(blocked, 캔버스
   * 기준 사각형)와 겹치거나 화면 양옆에서 잘리는 이름표는 숨긴다. 좁은 화면에서 이웃 이름표끼리 겹치면 가운데에 가까운
   * 쪽만 남긴다.
   */
  placeTags(overlay: HTMLElement | null, blocked: readonly Box[]) {
    const taken = [...blocked]
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
  private anchor(slot: number) {
    const item = this.lots.find((lot) => lot.slot === slot)
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

  /** 화면 점 아래의 칸(부지·건물)의 부지 번호. 없으면 undefined */
  pick(x: number, y: number) {
    this.ndc.set((x / this.width) * 2 - 1, -(y / this.height) * 2 + 1)
    this.raycaster.setFromCamera(this.ndc, this.camera)
    const targets = this.lots
      .filter((lot) => lot.group.visible)
      .flatMap((lot) => [lot.lawn, lot.building])
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

  private mesh(geometry: THREE.BufferGeometry, color: string) {
    return new THREE.Mesh(geometry, this.material(color))
  }

  private flat(geometry: THREE.BufferGeometry, color: string, y: number) {
    const mesh = this.mesh(this.own(geometry), color)
    mesh.rotation.x = -Math.PI / 2
    mesh.position.y = y
    mesh.receiveShadow = true
    return mesh
  }

  private readonly shared = new Map<string, THREE.BufferGeometry>()
  private geometry(key: string, create: () => THREE.BufferGeometry) {
    let geometry = this.shared.get(key)
    if (!geometry) {
      geometry = this.own(create())
      this.shared.set(key, geometry)
    }
    return geometry
  }

  // 한 칸: 부지(잔디 또는 노란 빈 부지)와 건물, 칸 번호, 오른쪽 이웃과의 생울타리, 뒤쪽 꽃밭
  private lot(slot: number, key: string): Lot {
    const group = new THREE.Group()
    group.userData.slot = slot
    const lawn = new THREE.Group()
    const project = key !== 'next'
    const [fill, edge] = project
      ? ['#d5e4b6', '#b8cc94']
      : ['#fff0bd', '#f3c530']
    const patch = new THREE.Mesh(
      this.geometry('lawn', () => new THREE.PlaneGeometry(110, 110)),
      this.material(fill),
    )
    patch.rotation.x = -Math.PI / 2
    patch.position.y = 3.2
    patch.receiveShadow = true
    const line = new THREE.LineLoop(
      this.geometry('lawn-edge', () =>
        new THREE.BufferGeometry().setFromPoints(
          [
            [-56, -56],
            [56, -56],
            [56, 56],
            [-56, 56],
          ].map(([x, z]) => new THREE.Vector3(x, 3.4, z)),
        ),
      ),
      this.own(new THREE.LineBasicMaterial({ color: edge })),
    )
    lawn.add(patch, line)
    // 건물 모델은 정면이 -z라 반 바퀴 돌려 거리 쪽을 보게 한다(원형 도시 때와 같다).
    const building = new THREE.Group()
    building.rotation.y = Math.PI
    // 부지와 건물은 땅의 격자를 따라 45° 돌려 둔다. 거리는 화면 가로여도 부지는 마름모, 건물은 두 면이 보인다.
    const plot = new THREE.Group()
    plot.rotation.y = -Math.PI / 4
    plot.add(lawn, building)
    // 오른쪽 이웃과의 사이: 앞쪽 틈에 꽃밭, 뒤쪽 틈에 나무 한 그루
    const between = new THREE.Group()
    between.position.x = PITCH / 2
    const bed = new THREE.Mesh(
      this.geometry('bed', () => new THREE.BoxGeometry(22, 4, 10)),
      this.material('#c9b9a0'),
    )
    bed.position.set(0, 2, 50)
    const stem = new THREE.Mesh(
      this.geometry('trunk', () => new THREE.CylinderGeometry(1.5, 2, 9, 6)),
      this.material('#a39078'),
    )
    stem.position.set(0, 4.5, -52)
    const crown = new THREE.Mesh(
      this.geometry('crown', () => new THREE.IcosahedronGeometry(8.5, 0)),
      this.material('#adc196'),
    )
    crown.position.set(0, 14, -52)
    crown.rotation.y = slot
    stem.castShadow = crown.castShadow = true
    between.add(bed, stem, crown)
    for (const [dx, color] of [
      [-6, '#e3b7b0'],
      [0, '#e8d58f'],
      [6, '#d9b8cf'],
    ] as const) {
      const flower = new THREE.Mesh(
        this.geometry('flower', () => new THREE.IcosahedronGeometry(3, 0)),
        this.material(color),
      )
      flower.position.set(dx, 3, 0)
      bed.add(flower)
    }
    const number = this.numberSprite(slot + 1)
    number.position.set(0, 6, 88)
    group.add(plot, between, number)
    this.street.add(group)
    const lot: Lot = {
      group,
      plot,
      lawn,
      building,
      between,
      number,
      slot,
      key,
      height: 0,
    }
    if (project) this.building(lot, key.slice(2))
    return lot
  }

  // 모델을 받기 전과 받지 못한 환경에서는 흐린 상자를 세운다(D21). 건물 앞(+z)이 거리를 본다.
  private building(lot: Lot, design: string) {
    const holder = lot.building
    const shell = this.mesh(
      this.geometry('shell', () => new THREE.BoxGeometry(64, 80, 64)),
      '#ebe7dc',
    )
    shell.position.y = 40
    shell.castShadow = true
    holder.add(shell)
    lot.height = 80
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
        lot.height = box.max.y - box.min.y
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

  // 거리와 주변(v469 결): 부지 뒤 광장 띠, 앞 보도·차도(점선)·건너편 보도, 3칸마다 건너편으로 뻗는 길, 가로등·가로수,
  // 작은 공원과 연못·벤치, 흐린 먼 건물. 한 마디(3칸) 무늬를 여러 번 되풀이해 깐다. 작고 흐리게 칠해 부지·건물이 가장 눈에 띈다.
  private buildDecor() {
    const span = PERIOD * (2 * COPIES + 1)
    const strip = (z0: number, z1: number, color: string, y: number) => {
      const mesh = this.flat(new THREE.PlaneGeometry(span, z1 - z0), color, y)
      mesh.position.z = (z0 + z1) / 2
      this.decor.add(mesh)
    }
    strip(-100, 92, '#eee6d2', 1.5)
    strip(92, 110, '#e9e3d6', 1.1)
    strip(110, 156, '#cdc7bb', 1.0)
    strip(156, 174, '#e9e3d6', 1.1)
    for (const z of [-92, 88]) strip(z - 1, z + 1, '#e2d8c2', 1.6)
    let seed = 7
    const rnd = () => (seed = (seed * 16807) % 2147483647) / 2147483647
    const trunk = this.geometry(
      'trunk',
      () => new THREE.CylinderGeometry(1.5, 2, 9, 6),
    )
    const crown = this.geometry(
      'crown',
      () => new THREE.IcosahedronGeometry(8.5, 0),
    )
    const pole = this.geometry(
      'pole',
      () => new THREE.CylinderGeometry(0.9, 1.1, 26, 6),
    )
    const lampHead = this.geometry(
      'lamp',
      () => new THREE.BoxGeometry(5, 2.5, 3),
    )
    const seat = this.geometry('seat', () => new THREE.BoxGeometry(14, 3, 5))
    const dash = this.geometry('dash', () => new THREE.BoxGeometry(9, 0.4, 1.6))
    // 한 마디(x 0 ~ PERIOD) 무늬를 만들어 두고 copy 번 되풀이한다.
    const pattern: ((x: number) => void)[] = []
    const tree = (x: number, z: number, s = 1) => {
      const color = rnd() < 0.5 ? '#adc196' : '#9fb68b'
      const turn = rnd() * 3
      pattern.push((offset) => {
        const group = new THREE.Group()
        const stem = new THREE.Mesh(trunk, this.material('#a39078'))
        stem.position.y = 4.5
        const top = new THREE.Mesh(crown, this.material(color))
        top.position.y = 14
        top.rotation.y = turn
        stem.castShadow = top.castShadow = true
        group.add(stem, top)
        group.scale.setScalar(s)
        group.position.set(offset + x, 0, z)
        this.decor.add(group)
      })
    }
    const lamp = (x: number, z: number) =>
      pattern.push((offset) => {
        const group = new THREE.Group()
        const stick = new THREE.Mesh(pole, this.material('#9a948a'))
        stick.position.y = 13
        const head = new THREE.Mesh(lampHead, this.material('#f1e7c6'))
        head.position.y = 26
        group.add(stick, head)
        group.position.set(offset + x, 0, z)
        this.decor.add(group)
      })
    const bench = (x: number, z: number, turn = 0) =>
      pattern.push((offset) => {
        const board = new THREE.Mesh(seat, this.material('#bca889'))
        board.position.set(offset + x, 3, z)
        board.rotation.y = turn
        this.decor.add(board)
      })
    const flat = (
      geometry: THREE.BufferGeometry,
      color: string,
      x: number,
      z: number,
      y: number,
    ) =>
      pattern.push((offset) => {
        const mesh = new THREE.Mesh(geometry, this.material(color))
        mesh.rotation.x = -Math.PI / 2
        mesh.position.set(offset + x, y, z)
        mesh.receiveShadow = true
        this.decor.add(mesh)
      })
    // 차도 가운데 점선, 앞 보도 가로등(칸 사이), 건너편 보도 가로수
    for (let x = 0; x < PERIOD; x += 30)
      pattern.push((offset) => {
        const mesh = new THREE.Mesh(dash, this.material('#f7f3ea'))
        mesh.position.set(offset + x, 1.3, 133)
        this.decor.add(mesh)
      })
    for (let x = PITCH / 2; x < PERIOD; x += PITCH) lamp(x, 101)
    for (let x = 20; x < PERIOD; x += 75) tree(x, 165, 0.75)
    // 건너편으로 뻗는 길(마디마다 하나)과 길가 가로수
    const crossing = PERIOD - PITCH / 2
    flat(
      this.geometry('cross', () => new THREE.PlaneGeometry(40, 520)),
      '#cdc7bb',
      crossing,
      434,
      0.9,
    )
    for (const side of [-1, 1])
      for (let z = 210; z < 690; z += 46) tree(crossing + side * 38, z, 0.85)
    // 앞쪽 작은 공원과 연못·벤치
    const parkX = PITCH * 0.9
    flat(
      this.geometry('park', () => new THREE.CircleGeometry(70, 40)),
      '#d5e1bd',
      parkX,
      300,
      0.8,
    )
    flat(
      this.geometry('pond', () => new THREE.CircleGeometry(24, 28)),
      '#c7d9da',
      parkX + 18,
      290,
      0.9,
    )
    for (let i = 0; i < 6; i++)
      tree(
        parkX + (rnd() - 0.5) * 120,
        300 + (rnd() - 0.5) * 110,
        0.8 + rnd() * 0.35,
      )
    bench(parkX - 40, 255, 0.2)
    bench(parkX + 10, 245, -0.1)
    // 부지 뒤 나무 띠와 흐린 먼 건물
    for (let x = 10; x < PERIOD; x += 38)
      tree(x + (rnd() - 0.5) * 16, -108 - rnd() * 30, 0.75 + rnd() * 0.3)
    const far: { x: number; z: number; s: number; i: number }[] = []
    for (let x = 30, i = 0; x < PERIOD; x += 110, i++)
      far.push({
        x: x + (rnd() - 0.5) * 30,
        z: -180 - rnd() * 50,
        s: 20 + rnd() * 8,
        i,
      })
    for (let copy = -COPIES; copy <= COPIES; copy++)
      for (const place of pattern) place(copy * PERIOD)
    void Promise.all(
      FAR_MODELS.map((name) => loadKenney(kenneyBuildingUrl(name))),
    )
      .then((models) => {
        if (this.disposed) return
        const pale = this.material('#dcd6ca')
        for (let copy = -COPIES; copy <= COPIES; copy++)
          for (const spot of far) {
            const figure = cloneKenney(models[spot.i % models.length])
            figure.traverse((object) => {
              const mesh = object as THREE.Mesh
              if (!mesh.isMesh) return
              mesh.material = pale
              mesh.receiveShadow = true
            })
            figure.rotation.y = -Math.PI / 4
            figure.scale.setScalar(spot.s)
            figure.position.set(copy * PERIOD + spot.x, 0, spot.z)
            this.decor.add(figure)
          }
      })
      .catch(() => {})
  }
}
