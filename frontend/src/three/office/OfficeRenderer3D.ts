import * as THREE from 'three'
import { RoundedBoxGeometry } from 'three/addons/geometries/RoundedBoxGeometry.js'
import type { Floor, Furniture, WallDecor } from './floors'
import { stepDrop, type DropBody } from './drop-physics'
import { OfficeOrbit, officeOrbitFrame } from './office-orbit'
import { FirstPersonLook, firstPersonFrame } from './first-person'
import { OfficeCity } from './office-city'
import { WindowCityView } from './window-city-view'
import type { OfficeLifeActivity } from './office-life'
import {
  officeFurnitureKey,
  type OfficeWorkHit,
  type OfficeWorkState,
} from './office-work'
import { OfficeWorkSurface } from './office-work-surface'
import { officeObjectKey } from './office-layout'
import {
  OfficeAimOutline,
  pickOfficeInteraction,
  setOfficeViewVisibility,
} from './office-targeting'

export interface OfficeSceneHit {
  x: number
  y: number
  entityId?: string
  workHit?: OfficeWorkHit
  furniture?: Furniture
  outdoorExit?: boolean
}
export interface OfficeAimTarget extends OfficeSceneHit {
  label: string
  surface?: 'monitor' | 'board' | 'projection' | 'note'
}

/** Positions keep the existing simulation's 32 px tile coordinate system. */
export interface OfficeEntity3D {
  id: string
  x: number
  y: number
  dir: 'up' | 'down' | 'left' | 'right'
  heading?: number
  moving: boolean
  hidden?: boolean
  riding?: number
  floor: number
  look?: unknown
  paused?: boolean
  seat?: unknown
  selected?: boolean
  color?: string
  kind?: string
  appearance?: 'human' | 'robot' | 'bibi'
  status?: string
  seated?: boolean
  seatLift?: number
  running?: boolean
  activity?: OfficeLifeActivity
}

interface Robot {
  appearance: 'human' | 'robot' | 'bibi'
  root: THREE.Group
  body: THREE.Group
  head: THREE.Group
  arms: THREE.Group[]
  elbows: THREE.Group[]
  legs: THREE.Group[]
  knees: THREE.Group[]
  ring: THREE.Mesh
  badge: THREE.Mesh
  phase: number
  lastX: number
  lastY: number
}

const TILE = 32
const DREAM_BUILD_SPEED = 2
const CITY_SKY = '#8fcff5'
const CITY_VIEW_DISTANCE = 1200
const C = {
  ivory: '#faf9ef',
  shell: '#fffefa',
  white: '#ffffff',
  wood: '#cfa05f',
  woodEdge: '#aa7d43',
  oakLight: '#e2b76e',
  plaster: '#e9ddbd',
  honey: '#ffd24a',
  dark: '#2c2a24',
  metal: '#a09b90',
  leaf: '#719239',
  leafLight: '#9eb64d',
  leafDark: '#426d35',
  navy: '#293840',
}

/** A real, interactive orthographic dollhouse; all assets are local procedural meshes. */
export class OfficeRenderer3D {
  readonly canvas: HTMLCanvasElement
  private readonly renderer: THREE.WebGLRenderer
  private readonly ownsRenderer: boolean
  private readonly scene = new THREE.Scene()
  private readonly exteriorScene = new THREE.Scene()
  private windowCityView = new WindowCityView(CITY_SKY)
  private readonly orbitCamera = new THREE.OrthographicCamera(
    -20,
    20,
    15,
    -15,
    0.1,
    CITY_VIEW_DISTANCE,
  )
  private readonly eyeCamera = new THREE.PerspectiveCamera(
    68,
    1,
    0.05,
    CITY_VIEW_DISTANCE,
  )
  private camera: THREE.OrthographicCamera | THREE.PerspectiveCamera =
    this.orbitCamera
  private readonly firstLook = new FirstPersonLook()
  private firstPerson = false
  private readonly room = new THREE.Group()
  private readonly architecture = new THREE.Group()
  private readonly overhead = new THREE.Group()
  private readonly firstPersonEnclosure = new THREE.Group()
  private readonly rearWindows = new THREE.Group()
  private readonly sideWindows = new THREE.Group()
  private readonly windowMullions: THREE.Mesh[] = []
  private city: OfficeCity | null = null
  // Clear air across the surrounding districts; only the far horizon meets the sky.
  private readonly cityFog = new THREE.Fog(CITY_SKY, 650, 850)
  private environmentKey = ''
  private environmentEdge = { x: 0, z: 0 }
  private readonly people = new THREE.Group()
  private readonly robots = new Map<string, Robot>()
  private readonly furnitureObjects: THREE.Group[] = []
  private outdoorPortal: THREE.Group | null = null
  private outdoorPortalKey = ''
  private readonly geometries = new Map<string, THREE.BufferGeometry>()
  private readonly materials = new Map<string, THREE.MeshStandardMaterial>()
  private readonly lineMaterials = new Map<string, THREE.LineBasicMaterial>()
  private readonly textures: THREE.Texture[] = []
  private readonly workSurfaces: {
    surface: OfficeWorkSurface
    binding?: string
  }[] = []
  private lastWork: OfficeWorkState | undefined
  private workSurfacesDirty = true
  private readonly raycaster = new THREE.Raycaster()
  private readonly aimOutline = new OfficeAimOutline()
  private aimTarget: OfficeAimTarget | null = null
  get aimedTarget() {
    return this.aimTarget
  }
  private readonly ground = new THREE.Plane(new THREE.Vector3(0, 1, 0), 0)
  private readonly shadowTexture: THREE.CanvasTexture
  private width = 1
  private height = 1
  private floor: Floor | null = null
  private floorKey = ''
  private cameraKey = ''
  private readonly orbit = new OfficeOrbit()
  private lastTime = 0
  private car: THREE.Group | null = null
  private carColor = ''
  private ghost: THREE.Group | null = null
  private ghostKey = ''
  private grid: THREE.GridHelper | null = null
  // 사무실 확장·축소: 바깥 타일이 뒤집히며 바닥이 되거나(in) 바닥이 뒤집혀 사라진다(out)
  private flips: {
    mesh: THREE.InstancedMesh
    items: { x: number; z: number; delay: number; dir: 'in' | 'out' }[]
  }[] = []
  private flipFurniture: { group: THREE.Group; delay: number }[] = []
  private flipStart = -1
  private flipEnd = 0
  private dreamTiles = new Map<
    string,
    { mesh: THREE.Mesh; start: number; delay: number }
  >()
  private dreamObjects = new Map<
    string,
    { group: THREE.Group; body: DropBody; start: number; delay: number }
  >()
  private dreamTime = 0
  private physicsAccumulator = 0
  private lastDreamTime = 0
  private dreamGroundMaterial: THREE.ShadowMaterial | null = null
  private dreamGround: THREE.Mesh<
    THREE.PlaneGeometry,
    THREE.ShadowMaterial
  > | null = null

  private readonly dream: boolean
  private readonly settleInitial: boolean
  constructor(
    host: HTMLElement,
    dream = false,
    settleInitial = false,
    externalRenderer?: THREE.WebGLRenderer,
  ) {
    this.dream = dream
    this.settleInitial = settleInitial
    this.ownsRenderer = !externalRenderer
    this.renderer =
      externalRenderer ??
      new THREE.WebGLRenderer({
        antialias: true,
        alpha: true,
        stencil: true,
        powerPreference: 'high-performance',
      })
    if (this.ownsRenderer)
      this.renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2))
    this.renderer.setClearColor('#e9e8e6', 0)
    this.renderer.outputColorSpace = THREE.SRGBColorSpace
    this.renderer.toneMapping = THREE.ACESFilmicToneMapping
    this.renderer.toneMappingExposure = 1
    this.renderer.shadowMap.enabled = true
    this.renderer.shadowMap.type = THREE.PCFShadowMap
    this.canvas = this.renderer.domElement
    if (this.ownsRenderer) {
      this.canvas.className = 'office-three-canvas'
      this.canvas.setAttribute('aria-hidden', 'true')
      Object.assign(this.canvas.style, {
        position: 'absolute',
        inset: '0',
        width: '100%',
        height: '100%',
        pointerEvents: 'none',
        zIndex: '0',
      })
      host.prepend(this.canvas)
    }
    this.scene.add(this.room, this.people, this.architecture)
    if (dream) {
      // Let the soft workspace gradient show through the transparent shadow catcher.
      this.scene.background = null
      this.exteriorScene.fog = this.cityFog
      this.dreamGroundMaterial = new THREE.ShadowMaterial({
        opacity: 0.1,
        depthWrite: false,
      })
      const ground = new THREE.Mesh(
        new THREE.PlaneGeometry(1, 1),
        this.dreamGroundMaterial,
      )
      ground.rotation.x = -Math.PI / 2
      ground.position.set(15, -0.04, 10)
      ground.receiveShadow = true
      ground.visible = false
      this.dreamGround = ground
      this.scene.add(ground)
      this.geometries.set('dreamGround', ground.geometry)
    }
    const ambient = new THREE.HemisphereLight('#fffaf1', '#bebbb3', 1.5)
    this.scene.add(ambient)
    this.exteriorScene.add(
      new THREE.HemisphereLight('#e0f4ff', '#a09b90', 1.75),
    )
    const light = new THREE.DirectionalLight('#fff1dc', 2.2)
    light.position.set(7, 26, 14)
    light.castShadow = true
    light.shadow.mapSize.set(2048, 2048)
    Object.assign(light.shadow.camera, {
      left: -26,
      right: 26,
      top: 26,
      bottom: -26,
      near: 0.5,
      far: 80,
    })
    light.shadow.bias = -0.0003
    light.shadow.normalBias = 0.035
    light.shadow.radius = 5
    light.target.position.set(15, 0, 9)
    this.scene.add(light, light.target)
    const exteriorSun = light.clone()
    exteriorSun.castShadow = false
    exteriorSun.color.set('#fff8e8')
    exteriorSun.intensity = 2.4
    this.exteriorScene.add(exteriorSun, exteriorSun.target)
    const fill = new THREE.DirectionalLight('#edecea', 0.55)
    fill.position.set(25, 15, -10)
    this.scene.add(fill)
    const exteriorFill = fill.clone()
    exteriorFill.color.set('#b6d7ff')
    exteriorFill.intensity = 0.45
    this.exteriorScene.add(exteriorFill)
    const shadow = document.createElement('canvas')
    shadow.width = shadow.height = 128
    const ctx = shadow.getContext('2d')!
    const gradient = ctx.createRadialGradient(64, 64, 4, 64, 64, 64)
    gradient.addColorStop(0, 'rgba(66,52,25,.24)')
    gradient.addColorStop(0.5, 'rgba(66,52,25,.12)')
    gradient.addColorStop(1, 'rgba(66,52,25,0)')
    ctx.fillStyle = gradient
    ctx.fillRect(0, 0, 128, 128)
    this.shadowTexture = new THREE.CanvasTexture(shadow)
    this.textures.push(this.shadowTexture)
  }

  setSize(width: number, height: number) {
    this.width = Math.max(1, width)
    this.height = Math.max(1, height)
    if (this.ownsRenderer) this.renderer.setSize(this.width, this.height, false)
    this.aimOutline.setSize(this.width, this.height)
    this.cameraKey = ''
  }

  private material(color: string, roughness = 0.65, metalness = 0) {
    const key = `${color}/${roughness}/${metalness}`
    let material = this.materials.get(key)
    if (!material) {
      material = new THREE.MeshStandardMaterial({
        color: color.startsWith('#') ? color : '#ffffff',
        roughness,
        metalness,
      })
      this.materials.set(key, material)
    }
    return material
  }

  private geometry(key: string, create: () => THREE.BufferGeometry) {
    let geometry = this.geometries.get(key)
    if (!geometry) {
      geometry = create()
      this.geometries.set(key, geometry)
    }
    return geometry
  }

  private box(
    parent: THREE.Object3D,
    x: number,
    y: number,
    z: number,
    w: number,
    h: number,
    d: number,
    color: string,
    radius = 0.05,
  ) {
    const r = Math.min(radius, w / 3, h / 3, d / 3)
    const geometry = this.geometry(`b${w},${h},${d},${r}`, () =>
      r > 0.005
        ? new RoundedBoxGeometry(w, h, d, 2, r)
        : new THREE.BoxGeometry(w, h, d),
    )
    const mesh = new THREE.Mesh(geometry, this.material(color))
    mesh.position.set(x, y, z)
    mesh.castShadow = true
    mesh.receiveShadow = true
    parent.add(mesh)
    return mesh
  }

  private sphere(
    parent: THREE.Object3D,
    x: number,
    y: number,
    z: number,
    sx: number,
    sy: number,
    sz: number,
    color: string,
  ) {
    const mesh = new THREE.Mesh(
      this.geometry('sphere', () => new THREE.SphereGeometry(1, 24, 18)),
      this.material(color, 0.34),
    )
    mesh.position.set(x, y, z)
    mesh.scale.set(sx, sy, sz)
    mesh.castShadow = true
    mesh.receiveShadow = true
    parent.add(mesh)
    return mesh
  }

  private cylinder(
    parent: THREE.Object3D,
    x: number,
    y: number,
    z: number,
    top: number,
    bottom: number,
    height: number,
    color: string,
  ) {
    const mesh = new THREE.Mesh(
      this.geometry(
        `c${top}/${bottom}/${height}`,
        () => new THREE.CylinderGeometry(top, bottom, height, 20),
      ),
      this.material(color),
    )
    mesh.position.set(x, y, z)
    mesh.castShadow = true
    mesh.receiveShadow = true
    parent.add(mesh)
    return mesh
  }

  private rod(
    parent: THREE.Object3D,
    from: THREE.Vector3,
    to: THREE.Vector3,
    radius: number,
    color: string,
  ) {
    const delta = to.clone().sub(from)
    const mesh = new THREE.Mesh(
      this.geometry(
        `rod/${radius}`,
        () => new THREE.CylinderGeometry(radius, radius, 1, 10),
      ),
      this.material(color),
    )
    mesh.position.copy(from).add(to).multiplyScalar(0.5)
    mesh.quaternion.setFromUnitVectors(
      new THREE.Vector3(0, 1, 0),
      delta.clone().normalize(),
    )
    mesh.scale.y = delta.length()
    mesh.castShadow = true
    parent.add(mesh)
    return mesh
  }

  private contact(
    parent: THREE.Object3D,
    x: number,
    z: number,
    w: number,
    d: number,
    opacity = 0.8,
  ) {
    const key = `contact/${opacity}`
    let material = this.materials.get(key)
    if (!material) {
      material = new THREE.MeshStandardMaterial({
        map: this.shadowTexture,
        transparent: true,
        depthWrite: false,
        opacity,
        roughness: 1,
      })
      this.materials.set(key, material)
    }
    const mesh = new THREE.Mesh(
      this.geometry('plane', () => new THREE.PlaneGeometry(1, 1)),
      material,
    )
    mesh.rotation.x = -Math.PI / 2
    mesh.position.set(x, 0.012, z)
    mesh.scale.set(w, d, 1)
    mesh.userData.ignorePicking = true
    parent.add(mesh)
  }

  private plant(
    parent: THREE.Object3D,
    x: number,
    z: number,
    size = 1,
    baseY = 0,
  ) {
    const g = new THREE.Group()
    g.position.set(x, baseY, z)
    g.scale.setScalar(size)
    parent.add(g)
    this.cylinder(g, 0, 0.23, 0, 0.235, 0.18, 0.44, '#e1dfd4')
    this.cylinder(g, 0, 0.452, 0, 0.213, 0.213, 0.018, '#6d6558')
    for (const [px, pz] of [
      [-0.09, 0.04],
      [0.08, 0.08],
      [0.04, -0.08],
    ])
      this.sphere(g, px, 0.468, pz, 0.045, 0.025, 0.035, '#b9b4a3')
    // Sparse, tapered arching leaves instead of a dense fan of broad foliage.
    const leafGeometry = this.geometry('orchid-leaf', () => {
      const positions: number[] = [],
        indices: number[] = []
      for (let step = 0; step <= 18; step++) {
        const t = step / 18,
          width = 0.045 * Math.sin(Math.PI * t)
        const height = 0.45 + Math.sin(t * Math.PI * 0.9) * 0.5 - t * 0.17,
          depth = t * 0.78
        positions.push(
          -width,
          height,
          depth,
          0,
          height + 0.014,
          depth,
          width,
          height,
          depth,
        )
        if (step < 18)
          for (let side = 0; side < 2; side++) {
            const a = step * 3 + side
            indices.push(a, a + 3, a + 1, a + 1, a + 3, a + 4)
          }
      }
      const geometry = new THREE.BufferGeometry()
      geometry.setAttribute(
        'position',
        new THREE.Float32BufferAttribute(positions, 3),
      )
      geometry.setIndex(indices)
      geometry.computeVertexNormals()
      return geometry
    })
    for (let i = 0; i < 4; i++) {
      const material = this.material(i % 2 ? '#57715a' : '#6c8664', 0.9)
      material.side = THREE.DoubleSide
      const leaf = new THREE.Mesh(leafGeometry, material)
      leaf.rotation.y = i * 2.39996
      leaf.scale.setScalar(i % 2 ? 0.86 : 1)
      leaf.position.y = i % 2 ? 0.06 : 0
      leaf.castShadow = true
      g.add(leaf)
    }
    const stemCurve = new THREE.CubicBezierCurve3(
      new THREE.Vector3(0.02, 0.46, 0),
      new THREE.Vector3(0.02, 1.2, 0),
      new THREE.Vector3(0.12, 1.65, 0.02),
      new THREE.Vector3(0.45, 1.46, 0.05),
    )
    const stem = new THREE.Mesh(
      this.geometry(
        'orchid-stem',
        () => new THREE.TubeGeometry(stemCurve, 20, 0.012, 7, false),
      ),
      this.material('#748169'),
    )
    stem.castShadow = true
    g.add(stem)
    for (const t of [0.64, 0.82, 1]) {
      const flower = new THREE.Group()
      flower.position.copy(stemCurve.getPoint(t))
      flower.rotation.y = (t - 0.8) * 2
      for (let petal = 0; petal < 5; petal++) {
        const angle = (petal * Math.PI * 2) / 5
        const mesh = this.sphere(
          flower,
          Math.sin(angle) * 0.085,
          Math.cos(angle) * 0.085,
          0.015,
          0.068,
          0.105,
          0.026,
          petal % 2 ? '#e8dce7' : '#d7bdd1',
        )
        mesh.rotation.z = -angle
      }
      this.sphere(flower, 0, -0.025, 0.05, 0.035, 0.045, 0.025, '#b491ab')
      this.sphere(flower, 0, 0.008, 0.069, 0.02, 0.021, 0.012, '#d5b578')
      g.add(flower)
    }
    if (baseY === 0) this.contact(parent, x, z, size * 0.95, size * 0.95)
  }

  private books(
    parent: THREE.Object3D,
    x: number,
    y: number,
    z: number,
    count = 5,
  ) {
    const colors = [
      '#f3efdc',
      '#a39e92',
      '#ccb283',
      '#e5d6b6',
      '#928c7e',
      '#b5b2a9',
    ]
    for (let i = 0; i < count; i++) {
      const h = 0.45 + (i % 3) * 0.08
      this.box(
        parent,
        x + i * 0.16,
        y + h / 2,
        z,
        0.12,
        h,
        0.42,
        colors[i % colors.length],
        0.015,
      )
      this.box(
        parent,
        x + i * 0.16,
        y + h * 0.72,
        z + 0.216,
        0.065,
        0.025,
        0.008,
        '#f7f5e9',
        0.002,
      )
    }
  }

  private workSurface(
    parent: THREE.Object3D,
    kind: 'monitor' | 'board' | 'projection' | 'note',
    x: number,
    y: number,
    z: number,
    width: number,
    height: number,
    binding?: string,
  ) {
    const surface = new OfficeWorkSurface(kind, width, height)
    surface.mesh.position.set(x, y, z)
    parent.add(surface.mesh)
    this.workSurfaces.push({ surface, binding })
    this.workSurfacesDirty = true
    return surface.mesh
  }

  private screen(
    parent: THREE.Object3D,
    x: number,
    y: number,
    z: number,
    width = 0.95,
    laptop = false,
    binding?: string,
  ) {
    const height = width * 0.64
    const group = new THREE.Group()
    group.position.set(x, y, z)
    parent.add(group)
    if (!laptop) {
      this.box(group, 0, 0.06, -0.03, width * 0.43, 0.055, 0.34, '#bcb9b1')
      this.box(group, 0, 0.24, -0.09, 0.065, 0.36, 0.07, '#c2bfb8')
    } else {
      this.box(
        group,
        0,
        0.025,
        0.23,
        width * 1.06,
        0.045,
        width * 0.62,
        '#c8c5be',
        0.025,
      )
      this.box(
        group,
        0,
        0.052,
        0.2,
        width * 0.78,
        0.009,
        width * 0.28,
        '#969083',
        0.005,
      )
    }
    const sy = laptop ? height * 0.53 : 0.4 + height * 0.5
    const monitor = this.box(
      group,
      0,
      sy,
      0,
      width,
      height,
      0.07,
      '#f5f5e9',
      0.045,
    )
    if (laptop) monitor.rotation.x = -0.14
    this.workSurface(
      group,
      'monitor',
      0,
      sy,
      0.046,
      width * 0.9,
      height * 0.84,
      binding,
    )
  }

  private chair(
    parent: THREE.Object3D,
    x: number,
    z: number,
    facing = 'up',
    scale = 1,
  ) {
    const g = new THREE.Group()
    g.position.set(x, 0, z)
    g.rotation.y =
      (
        {
          up: 0,
          down: Math.PI,
          left: Math.PI / 2,
          right: -Math.PI / 2,
        } as Record<string, number>
      )[facing] ?? 0
    g.scale.setScalar(scale)
    parent.add(g)
    this.cylinder(g, 0, 0.36, 0, 0.045, 0.055, 0.58, '#9c978b')
    this.box(g, 0, 0.65, 0, 0.8, 0.16, 0.73, '#d5d1b9', 0.1)
    this.box(g, 0, 1.12, 0.31, 0.8, 0.9, 0.15, '#e2ddca', 0.1)
    for (const side of [-1, 1]) {
      this.box(g, side * 0.44, 0.82, 0.02, 0.055, 0.42, 0.06, '#a3a493')
      this.box(g, side * 0.44, 1.01, -0.01, 0.12, 0.07, 0.52, '#d6d4c1')
    }
    for (let i = 0; i < 5; i++) {
      const angle = i * Math.PI * 0.4
      const leg = this.box(
        g,
        Math.cos(angle) * 0.22,
        0.12,
        Math.sin(angle) * 0.22,
        0.48,
        0.055,
        0.06,
        '#a3a493',
        0.02,
      )
      leg.rotation.y = -angle
      this.sphere(
        g,
        Math.cos(angle) * 0.46,
        0.09,
        Math.sin(angle) * 0.46,
        0.075,
        0.08,
        0.055,
        '#6d685c',
      )
    }
    g.traverse((object) => {
      object.userData.floorX = x * TILE
      object.userData.floorY = z * TILE
    })
  }

  private desk(parent: THREE.Object3D, f: Furniture) {
    const x = f.x + f.w / 2,
      z = f.y + f.h / 2
    const w = Math.max(0.8, f.w - 0.12),
      d = Math.max(0.85, f.h - 0.1)
    this.contact(parent, x, z, w + 0.5, d + 0.7)
    this.box(parent, x, 1.23, z, w, 0.16, d, C.oakLight, 0.08)
    this.box(parent, x, 1.14, z, w - 0.12, 0.07, d - 0.12, C.woodEdge, 0.02)
    for (const sx of [-1, 1])
      for (const sz of [-1, 1]) {
        this.box(
          parent,
          x + sx * (w / 2 - 0.2),
          0.56,
          z + sz * Math.max(0.2, d / 2 - 0.17),
          0.085,
          1.12,
          0.085,
          '#c4c5ad',
          0.025,
        )
      }
    if (f.kind === 'table' && f.tag === 'coffee-table') {
      this.plant(parent, x, z, 0.45, 1.32)
      this.box(
        parent,
        x - w * 0.28,
        1.35,
        z + 0.05,
        0.7,
        0.06,
        0.48,
        '#938d80',
        0.015,
      )
      this.box(
        parent,
        x - w * 0.28,
        1.4,
        z + 0.05,
        0.63,
        0.045,
        0.43,
        '#e8dfc5',
        0.015,
      )
    } else if (f.kind === 'table') {
      if (f.tag !== 'meeting')
        for (const sx of [-1, 1])
          this.screen(
            parent,
            x + sx * w * 0.26,
            1.32,
            z + 0.15,
            Math.min(1.2, w * 0.2),
            true,
          )
      this.plant(parent, x, z, 0.44, 1.32)
    } else {
      const binding = officeFurnitureKey(f)
      this.screen(
        parent,
        x - 0.12,
        1.32,
        z - 0.15,
        Math.min(1.15, w * 0.45),
        false,
        binding,
      )
      this.box(
        parent,
        x - 0.1,
        1.335,
        z + 0.28,
        0.75,
        0.035,
        0.24,
        '#cbc8c1',
        0.02,
      )
      this.box(
        parent,
        x + w * 0.31,
        1.35,
        z + 0.15,
        0.41,
        0.065,
        0.32,
        '#a9a59b',
        0.015,
      )
      this.box(
        parent,
        x + w * 0.31,
        1.39,
        z + 0.15,
        0.36,
        0.025,
        0.28,
        '#f8f4df',
        0.008,
      )
      const note = this.workSurface(
        parent,
        'note',
        x + w * 0.31,
        1.406,
        z + 0.15,
        0.36,
        0.28,
        binding,
      )
      note.rotation.x = -Math.PI / 2
      if (w >= 3) this.plant(parent, x - w * 0.36, z - 0.05, 0.36, 1.32)
      this.cylinder(
        parent,
        x + 0.67,
        1.46,
        z + 0.2,
        0.085,
        0.075,
        0.25,
        C.ivory,
      )
    }
    if (f.kind === 'desk' || f.kind === 'execdesk')
      this.chair(parent, x, f.y + f.h + 0.62, 'up')
  }

  private furniture(parent: THREE.Object3D, f: Furniture, index: number) {
    const x = f.x + f.w / 2,
      z = f.y + f.h / 2
    const w = Math.max(0.65, f.w - 0.14),
      d = Math.max(0.65, f.h - 0.14)
    if (
      ['desk', 'execdesk', 'table', 'counter', 'requestdesk'].includes(f.kind)
    ) {
      this.desk(parent, f)
      return
    }
    if (f.kind === 'plant' || f.kind === 'tree') {
      this.plant(parent, x, z, f.kind === 'tree' ? 1.48 : 1.06)
      return
    }
    if (f.kind === 'chair') {
      this.chair(parent, x, z, f.facing)
      return
    }
    if (f.kind === 'rug') {
      this.box(
        parent,
        x,
        0.018,
        z,
        w,
        0.025,
        d,
        ['#d1cfca', '#e4d6b9', '#d8d6d1'][index % 3],
        0.12,
      )
      return
    }
    if (f.kind === 'glass-partition') {
      const group = new THREE.Group(),
        length = Math.max(f.w, f.h) - 0.12
      group.position.set(x, 0, z)
      if (f.h > f.w) group.rotation.y = Math.PI / 2
      parent.add(group)
      const glass = this.material('office-partition-glass')
      glass.color.set('#cac7c1')
      glass.transparent = true
      glass.opacity = 0.16
      glass.depthWrite = false
      glass.roughness = 0.8
      glass.side = THREE.DoubleSide
      const pane = this.box(
        group,
        0,
        1.15,
        0,
        length,
        2.14,
        0.045,
        '#cac7c1',
        0.006,
      )
      pane.material = glass
      pane.castShadow = false
      for (const sx of [-1, 1])
        this.box(
          group,
          (sx * length) / 2,
          1.17,
          0,
          0.045,
          2.26,
          0.065,
          '#b3afa5',
          0.01,
        )
      for (const sy of [0.075, 2.28])
        this.box(group, 0, sy, 0, length + 0.06, 0.045, 0.075, '#b3afa5', 0.01)
      const stripe = this.material('office-partition-stripe')
      stripe.color.set('#f3f2f1')
      stripe.transparent = true
      stripe.opacity = 0.32
      stripe.depthWrite = false
      for (const sy of [1.02, 1.15])
        this.box(
          group,
          0,
          sy,
          0.03,
          length - 0.08,
          0.032,
          0.008,
          '#f3f2f1',
          0,
        ).material = stripe
      return
    }
    this.contact(parent, x, z, w + 0.5, d + 0.5)
    if (f.kind === 'shelf' || f.kind === 'locker') {
      this.box(parent, x, 1.12, z - d * 0.4, w, 2.24, 0.12, C.wood)
      for (const sy of [0.12, 0.88, 1.65, 2.34])
        this.box(parent, x, sy, z, w + 0.08, 0.09, d, C.oakLight, 0.025)
      for (const sx of [-1, 1])
        this.box(parent, x + (sx * w) / 2, 1.22, z, 0.1, 2.42, d, C.wood)
      for (let col = 0; col < Math.floor(w / 0.9); col++) {
        const bx = f.x + 0.25 + col * 0.9
        this.books(parent, bx, 0.18, z + 0.05, 4)
        this.box(parent, bx + 0.25, 1.22, z, 0.6, 0.52, 0.62, '#f1edda', 0.025)
        this.box(
          parent,
          bx + 0.25,
          1.26,
          z + 0.32,
          0.21,
          0.055,
          0.008,
          '#a39f94',
          0.005,
        )
      }
      this.plant(parent, x - w * 0.22, z, 0.55, 2.4)
      return
    }
    if (f.kind === 'sofa' || f.kind === 'bench' || f.kind === 'beanbag') {
      if (f.kind === 'beanbag') {
        this.sphere(parent, x, 0.48, z, w / 2, 0.6, d / 2, '#bbb8b0')
        return
      }
      for (const side of [-1, 1])
        this.box(
          parent,
          x + side * w * 0.38,
          0.22,
          z,
          0.13,
          0.4,
          0.13,
          C.woodEdge,
        )
      this.box(
        parent,
        x,
        0.59,
        z,
        w,
        0.28,
        d,
        f.kind === 'bench' ? C.wood : '#ceccc6',
        0.12,
      )
      this.box(parent, x, 1.03, z - d * 0.42, w, 0.78, 0.18, '#c0bdb6', 0.1)
      for (const side of [-1, 1])
        this.box(
          parent,
          x + side * w * 0.48,
          0.83,
          z,
          0.18,
          0.5,
          d,
          '#c0bdb6',
          0.09,
        )
      if (f.kind === 'sofa')
        for (let i = 0; i < Math.floor(w); i++)
          this.box(
            parent,
            x - w / 2 + 0.56 + i,
            0.77,
            z + 0.06,
            0.84,
            0.14,
            d * 0.7,
            '#e3e4cc',
            0.07,
          )
      return
    }
    if (f.kind === 'reception') {
      this.box(parent, x, 0.83, z, w, 1.6, d, '#eadabb', 0.12)
      this.box(parent, x, 1.66, z, w + 0.15, 0.13, d + 0.12, C.oakLight, 0.065)
      for (let i = 0; i < Math.floor(w * 5); i++)
        this.box(
          parent,
          x - w / 2 + 0.1 + i * 0.2,
          0.8,
          z + d / 2 + 0.02,
          0.035,
          1.3,
          0.045,
          '#d3b685',
          0.009,
        )
      this.screen(parent, x - w * 0.25, 1.74, z, 1.12)
      this.plant(parent, x + w * 0.32, z, 0.53, 1.74)
      return
    }
    if (
      f.kind === 'designboard' ||
      f.kind === 'projector' ||
      f.kind === 'console'
    ) {
      if (f.kind === 'console') {
        this.desk(parent, f)
        return
      }
      const screenWidth = w - 0.16,
        screenHeight = 2.18,
        screenY = 2.15
      this.box(
        parent,
        x,
        screenY,
        z,
        w,
        screenHeight + 0.1,
        0.045,
        '#e8e7e4',
        0.015,
      )
      this.box(parent, x, 3.34, z, w + 0.2, 0.15, 0.21, '#e5e3e0', 0.04)
      this.box(
        parent,
        x,
        1.01,
        z + 0.025,
        w + 0.04,
        0.055,
        0.075,
        '#676257',
        0.01,
      )
      for (const sx of [-1, 1])
        this.box(
          parent,
          x + sx * w * 0.43,
          3.88,
          z,
          0.028,
          1.05,
          0.028,
          '#a9a49a',
          0.008,
        )
      this.workSurface(
        parent,
        'projection',
        x,
        screenY,
        z + 0.03,
        screenWidth,
        screenHeight,
      )
      const device = new THREE.Group()
      device.name = 'office-projector-device'
      device.userData.firstPersonOnly = true
      device.userData.aimRoot = true
      parent.add(device)
      const pz = z + 3.7
      this.box(device, x, 4.27, pz, 0.38, 0.1, 0.4, '#c8c5be', 0.025)
      this.box(device, x, 4.07, pz, 0.055, 0.35, 0.055, '#a9a49a', 0.012)
      this.box(device, x, 3.84, pz, 0.88, 0.23, 0.66, '#f6f6f5', 0.07)
      const lens = this.cylinder(
        device,
        x - 0.18,
        3.84,
        pz - 0.36,
        0.1,
        0.1,
        0.08,
        '#8bbec7',
      )
      lens.rotation.x = Math.PI / 2
      const glow = this.material('projector-lens')
      glow.color.set('#c8eff6')
      glow.emissive.set('#9edbea')
      glow.emissiveIntensity = 0.65
      lens.material = glow
      for (let i = 0; i < 4; i++)
        this.box(
          device,
          x + 0.23,
          3.84 + (i - 1.5) * 0.032,
          pz - 0.334,
          0.2,
          0.012,
          0.009,
          '#c6c3bd',
          0.002,
        )
      const beamGeometry = this.geometry(
        `projector-beam/${screenWidth}`,
        () => {
          const corners = [
            [-screenWidth / 2, screenY - screenHeight / 2, 0.04],
            [screenWidth / 2, screenY - screenHeight / 2, 0.04],
            [screenWidth / 2, screenY + screenHeight / 2, 0.04],
            [-screenWidth / 2, screenY + screenHeight / 2, 0.04],
          ]
          const vertices = corners.flatMap((corner, i) => [
            -0.18,
            3.84,
            3.34,
            ...corner,
            ...corners[(i + 1) % 4],
          ])
          const geometry = new THREE.BufferGeometry()
          geometry.setAttribute(
            'position',
            new THREE.Float32BufferAttribute(vertices, 3),
          )
          geometry.computeVertexNormals()
          return geometry
        },
      )
      const beamMaterial = this.material('projector-beam')
      beamMaterial.color.set('#d4ebff')
      beamMaterial.emissive.set('#c7e5ff')
      beamMaterial.emissiveIntensity = 0.4
      beamMaterial.transparent = true
      beamMaterial.opacity = 0.025
      beamMaterial.depthWrite = false
      beamMaterial.side = THREE.DoubleSide
      const beam = new THREE.Mesh(beamGeometry, beamMaterial)
      beam.position.set(x, 0, z)
      beam.userData.ignorePicking = true
      device.add(beam)
      return
    }
    if (f.kind === 'gate') {
      this.box(parent, x, 0.75, z, 0.28, 1.5, 0.6, '#d6d6be', 0.07)
      this.box(parent, x, 1.52, z, 0.31, 0.07, 0.63, C.honey)
      this.box(parent, x, 0.93, z + 0.24, 0.85, 0.06, 0.07, '#c5c2ba')
      return
    }
    if (f.kind === 'lamp') {
      this.cylinder(parent, x, 0.06, z, 0.35, 0.38, 0.12, '#d5c394')
      this.cylinder(parent, x, 1.1, z, 0.035, 0.035, 2.1, '#ad936a')
      this.cylinder(parent, x, 2.22, z, 0.3, 0.56, 0.6, '#fff4c8')
      return
    }
    if (f.kind === 'rack') {
      this.box(parent, x, 1.25, z, w, 2.5, d, '#767165', 0.07)
      for (let i = 0; i < 5; i++) {
        this.box(
          parent,
          x,
          0.4 + i * 0.42,
          z + d / 2 + 0.02,
          w - 0.17,
          0.3,
          0.055,
          '#49463e',
          0.02,
        )
        for (let j = 0; j < 3; j++)
          this.sphere(
            parent,
            x - w * 0.3 + j * 0.15,
            0.4 + i * 0.42,
            z + d / 2 + 0.06,
            0.028,
            0.028,
            0.014,
            j % 2 ? '#e9d58a' : '#b8b4ab',
          )
      }
      return
    }
    // Appliances retain their footprints, with rounded porcelain and brass details.
    const height = f.kind === 'trophy' ? 0.9 : f.kind === 'printer' ? 1.2 : 1.85
    this.box(
      parent,
      x,
      height / 2,
      z,
      w,
      height,
      d,
      f.kind === 'safe' ? '#b3afa6' : '#f1eedc',
      0.09,
    )
    if (f.kind === 'aquarium') {
      this.box(
        parent,
        x,
        1.1,
        z + d / 2 + 0.02,
        w * 0.86,
        1.1,
        0.035,
        '#b9b5ad',
      )
      this.plant(parent, x, z, 0.35, 0.55)
    } else if (f.kind === 'cooler') {
      this.cylinder(parent, x, 2.05, z, 0.23, 0.28, 0.6, '#cbc9c3')
      this.box(parent, x, 1.35, z + d / 2 + 0.03, 0.4, 0.35, 0.02, '#989386')
    } else {
      this.box(
        parent,
        x,
        height * 0.65,
        z + d / 2 + 0.025,
        w * 0.67,
        height * 0.27,
        0.04,
        f.kind === 'coffee' ? '#4b4840' : '#bfbcb4',
        0.025,
      )
      this.box(
        parent,
        x,
        height * 0.36,
        z + d / 2 + 0.055,
        w * 0.5,
        0.06,
        0.05,
        '#a9a991',
        0.015,
      )
    }
  }

  private wallDecor(parent: THREE.Object3D, decor: WallDecor) {
    const x = decor.x + decor.w / 2
    const z = Math.max(0.57, decor.y + 0.56)
    if (decor.kind === 'window') {
      this.box(
        parent,
        x,
        2.05,
        z,
        Math.max(1.18, decor.w),
        1.95,
        0.1,
        '#faf7e8',
      )
      this.box(
        parent,
        x,
        2.05,
        z + 0.06,
        Math.max(1, decor.w - 0.15),
        1.76,
        0.025,
        '#e0dfdb',
      )
      this.box(parent, x, 2.05, z + 0.09, 0.055, 1.85, 0.055, '#fffbed', 0.01)
      this.box(
        parent,
        x,
        2.05,
        z + 0.09,
        Math.max(1.1, decor.w),
        0.055,
        0.055,
        '#fffbed',
        0.01,
      )
      this.box(
        parent,
        x,
        1.05,
        z + 0.12,
        Math.max(1.3, decor.w + 0.15),
        0.09,
        0.35,
        C.oakLight,
      )
      return
    }
    if (decor.kind === 'clock') {
      const clock = this.cylinder(
        parent,
        x,
        2.48,
        z + 0.04,
        0.36,
        0.36,
        0.09,
        C.woodEdge,
      )
      clock.rotation.x = Math.PI / 2
      const face = this.cylinder(
        parent,
        x,
        2.48,
        z + 0.1,
        0.31,
        0.31,
        0.025,
        C.ivory,
      )
      face.rotation.x = Math.PI / 2
      this.box(parent, x, 2.57, z + 0.13, 0.025, 0.2, 0.01, '#757064', 0.004)
      const hand = this.box(
        parent,
        x + 0.065,
        2.49,
        z + 0.14,
        0.17,
        0.025,
        0.01,
        '#757064',
        0.004,
      )
      hand.rotation.z = -0.3
      return
    }
    const width = Math.max(1.7, decor.w - 0.1)
    const dark = ['tv', 'videowall', 'testwall'].includes(decor.kind)
    this.box(
      parent,
      x,
      2.15,
      z,
      width,
      1.65,
      0.12,
      dark ? '#615d53' : C.woodEdge,
      0.055,
    )
    this.box(
      parent,
      x,
      2.15,
      z + 0.075,
      width - 0.14,
      1.5,
      0.035,
      dark ? '#e3e1de' : '#f9f4dd',
      0.025,
    )
    if (
      decor.kind === 'kanban' ||
      decor.kind === 'requestboard' ||
      decor.kind === 'roster'
    ) {
      const board = this.workSurface(
        parent,
        'board',
        x,
        2.15,
        z + 0.101,
        width - 0.14,
        1.5,
      )
      board.userData.floorX = x * TILE
      board.userData.floorY = z * TILE
    } else if (decor.kind === 'logo') {
      // A clean little brand mark, modeled into the room rather than a HUD panel.
      for (let i = 0; i < 4; i++)
        this.box(
          parent,
          x - 0.65 + i * 0.42,
          2.13 + (i % 2) * 0.12,
          z + 0.12,
          0.25,
          0.5 + i * 0.09,
          0.045,
          ['#9e998d', '#b9b5ad', '#d9b876', '#a9a59b'][i],
          0.06,
        )
    } else {
      for (let i = 0; i < 5; i++) {
        const h = 0.3 + ((i * 3) % 5) * 0.16
        this.box(
          parent,
          x - width * 0.34 + i * width * 0.15,
          1.65 + h / 2,
          z + 0.115,
          width * 0.085,
          h,
          0.018,
          ['#9e998d', '#b9b5ac', '#9caed1'][i % 3],
          0.015,
        )
      }
      this.box(
        parent,
        x,
        2.68,
        z + 0.12,
        width * 0.75,
        0.055,
        0.015,
        '#c4c1b9',
        0.007,
      )
    }
  }

  private buildFloor(floor: Floor) {
    if (this.dream) {
      this.buildDreamFloor(floor)
      return
    }
    this.workSurfaces.forEach(({ surface }) => surface.dispose())
    this.workSurfaces.length = 0
    this.workSurfacesDirty = true
    const previous = this.floor
    this.room.traverse((object) => {
      if (object instanceof THREE.InstancedMesh) object.dispose()
    })
    this.room.clear()
    this.furnitureObjects.length = 0
    this.flips = []
    this.flipFurniture = []
    this.flipStart = -1
    this.floor = floor
    // 이전 바닥과 같은 좌표끼리 비교해 새로 열린 칸·닫힌 칸을 찾는다 (처음 그릴 때는 애니메이션 없음)
    const inside = (tile: string | undefined) =>
      !!tile && !['#', 'o', 's', 'g'].includes(tile)
    const flipIn = new Map<string, number>(),
      flipOut: { x: number; z: number; tile: string }[] = []
    if (previous && previous.id === floor.id && previous !== floor) {
      const changed: { x: number; z: number }[] = []
      for (let z = 0; z < floor.H; z++)
        for (let x = 0; x < floor.W; x++) {
          const before =
            z < previous.H && x < previous.W ? previous.grid[z]?.[x] : undefined
          const after = floor.grid[z]?.[x]
          if (before === undefined || (inside(after) && !inside(before)))
            changed.push({ x, z })
        }
      for (let z = 0; z < previous.H; z++)
        for (let x = 0; x < previous.W; x++) {
          const before = previous.grid[z]?.[x]
          const after =
            z < floor.H && x < floor.W ? floor.grid[z]?.[x] : undefined
          if (
            (after === undefined && before !== '#') ||
            (inside(before) && after !== undefined && !inside(after))
          )
            flipOut.push({ x, z, tile: before })
        }
      if (changed.length < floor.W * floor.H * 0.6) {
        const minX = Math.min(...changed.map((c) => c.x)),
          minZ = Math.min(...changed.map((c) => c.z))
        for (const c of changed)
          flipIn.set(
            `${c.x},${c.z}`,
            Math.min(1.3, (c.x - minX + (c.z - minZ) * 0.7) * 0.045),
          )
      }
    }
    const W = floor.W,
      H = floor.H
    if (this.grid) {
      this.grid.geometry.dispose()
      const gridMaterials = Array.isArray(this.grid.material)
        ? this.grid.material
        : [this.grid.material]
      gridMaterials.forEach((material) => material.dispose())
      this.grid = null
    }
    this.box(
      this.room,
      W / 2,
      -0.2,
      H / 2,
      W + 0.4,
      0.4,
      H + 0.4,
      '#dbc398',
      0.22,
    )
    const surface = this.geometry(
      'floorTile',
      () => new THREE.BoxGeometry(0.998, 0.035, 0.998),
    )
    const colors: Record<string, string> = {
      l: '#ddc89e',
      d: '#e2c99a',
      m: '#d3d3b6',
      b: '#dfc99c',
      L: '#dfbd83',
      r: '#d7d5b7',
      h: '#e4d9bc',
      E: '#b4b1a8',
      u: '#ddc9a1',
      c: '#c5c2ba',
      k: '#dfcba5',
      s: '#dedac7',
      o: '#b9b5ac',
    }
    // Continuous perimeter plaster keeps the room soft and architectural.
    // The openings and low internal partitions still follow the collision grid.
    this.box(this.room, W / 2, 1.725, 0.5, W, 3.45, 1, C.plaster, 0.055)
    this.box(
      this.room,
      W / 2,
      3.49,
      0.5,
      W + 0.04,
      0.09,
      1.025,
      '#d9c69d',
      0.025,
    )
    this.box(this.room, W / 2, 0.18, 1.01, W, 0.2, 0.055, C.woodEdge, 0.01)
    const wallEnd = floor.id === 1 ? Math.min(18, H - 1) : H - 1
    this.box(
      this.room,
      0.5,
      1.4,
      (wallEnd + 1) / 2,
      1,
      2.8,
      wallEnd - 1,
      C.plaster,
      0.045,
    )
    this.box(
      this.room,
      0.5,
      2.84,
      (wallEnd + 1) / 2,
      1.025,
      0.09,
      wallEnd - 1,
      '#d9c69d',
      0.025,
    )
    const batches = new Map<string, { x: number; z: number }[]>()
    const flipColors = new Map<string, string>()
    for (let z = 0; z < H; z++)
      for (let x = 0; x < W; x++) {
        const tile = floor.grid[z]?.[x]
        let color = colors[tile] || '#e8dcc0'
        if (floor.theme === 'mint' && !['o', 's', '#', 'g'].includes(tile))
          color = '#d3e4ca'
        if (floor.theme === 'marble' && !['o', 's', '#', 'g'].includes(tile))
          color = '#eeeadd'
        if (floor.theme === 'wood' && !['o', 's', '#', 'g'].includes(tile))
          color = '#dfc38c'
        if (
          floor.theme === 'carpet' &&
          !['o', 's', '#', 'g', 'h', 'E'].includes(tile)
        )
          color = '#536a87'
        if (
          floor.theme === 'night' &&
          !['o', 's', '#', 'g', 'h', 'E'].includes(tile)
        )
          color = '#334054'
        if (flipIn.has(`${x},${z}`)) {
          flipColors.set(`${x},${z}`, color)
        } else {
          if (!batches.has(color)) batches.set(color, [])
          batches.get(color)!.push({ x, z })
        }
        if (tile === '#') {
          const back = z === 0,
            left = x === 0 && z < H - 1
          const edge = x === W - 1 || z === H - 1
          if ((floor.id !== 1 || z < 18) && !back && !left && !edge) {
            const height = 0.62
            this.box(
              this.room,
              x + 0.5,
              height / 2,
              z + 0.5,
              1,
              height,
              1,
              C.plaster,
              0,
            )
            this.box(
              this.room,
              x + 0.5,
              height + 0.035,
              z + 0.5,
              1,
              0.09,
              1,
              '#dfd1ae',
              0,
            )
          }
        } else if (tile === 'g') {
          const horizontal =
            floor.grid[z]?.[x + 1] === 'g' || floor.grid[z]?.[x - 1] === 'g'
          this.box(
            this.room,
            x + 0.5,
            0.12,
            z + 0.5,
            horizontal ? 1 : 0.07,
            0.24,
            horizontal ? 0.07 : 1,
            '#aca79d',
            0.015,
          )
          const glass = this.box(
            this.room,
            x + 0.5,
            0.98,
            z + 0.5,
            horizontal ? 1 : 0.035,
            1.64,
            horizontal ? 0.035 : 1,
            '#c8c5bf',
            0.005,
          )
          const glassMaterial = this.material('glass')
          glassMaterial.color.set('#cdcac4')
          glassMaterial.transparent = true
          glassMaterial.opacity = 0.27
          glassMaterial.depthWrite = false
          glass.material = glassMaterial
          this.box(
            this.room,
            x + 0.5,
            1.84,
            z + 0.5,
            horizontal ? 1 : 0.05,
            0.045,
            horizontal ? 0.05 : 1,
            '#b1ada4',
            0.01,
          )
        }
      }
    const matrix = new THREE.Matrix4()
    // 뒤집히는 타일: 윗면은 바닥 색, 아랫면은 바깥 잔디 색
    const lawn = colors.o,
      edge = '#cdbf9c'
    const flipGeometry = this.geometry(
      'flipTile',
      () => new THREE.BoxGeometry(0.998, 0.06, 0.998),
    )
    const groups = new Map<
      string,
      {
        top: string
        bottom: string
        items: { x: number; z: number; delay: number; dir: 'in' | 'out' }[]
      }
    >()
    const addFlip = (
      top: string,
      bottom: string,
      item: { x: number; z: number; delay: number; dir: 'in' | 'out' },
    ) => {
      const key = `${top}|${bottom}`
      if (!groups.has(key)) groups.set(key, { top, bottom, items: [] })
      groups.get(key)!.items.push(item)
    }
    for (const [key, color] of flipColors) {
      const [x, z] = key.split(',').map(Number)
      addFlip(color, lawn, { x, z, delay: flipIn.get(key)!, dir: 'in' })
    }
    const maxIn = Math.max(0, ...flipIn.values())
    flipOut.forEach((t, i) =>
      addFlip(colors[t.tile] || '#e8dcc0', lawn, {
        x: t.x,
        z: t.z,
        delay: Math.min(1, (flipOut.length - 1 - i) * 0.02),
        dir: 'out',
      }),
    )
    for (const g of groups.values()) {
      const mesh = new THREE.InstancedMesh(
        flipGeometry,
        [
          this.material(edge),
          this.material(edge),
          this.material(g.top),
          this.material(g.bottom),
          this.material(edge),
          this.material(edge),
        ],
        g.items.length,
      )
      mesh.receiveShadow = true
      mesh.castShadow = true
      this.room.add(mesh)
      this.flips.push({ mesh, items: g.items })
    }
    this.flipEnd =
      Math.max(
        maxIn,
        flipOut.length ? Math.min(1, (flipOut.length - 1) * 0.02) : 0,
      ) + 1.1
    for (const [color, points] of batches) {
      const tiles = new THREE.InstancedMesh(
        surface,
        this.material(color),
        points.length,
      )
      points.forEach((point, i) => {
        matrix.makeTranslation(point.x + 0.5, -0.006, point.z + 0.5)
        tiles.setMatrixAt(i, matrix)
      })
      tiles.receiveShadow = true
      this.room.add(tiles)
    }
    // Delicate wood seams read as planks instead of a pixel grid.
    if (!floor.theme || floor.theme === 'default' || floor.theme === 'wood') {
      for (let x = 1; x < W; x += 2)
        this.box(
          this.room,
          x,
          0.014,
          Math.min(H - 1, 17) / 2 + 0.5,
          0.016,
          0.004,
          Math.min(H - 2, 16),
          '#c6ad7f',
          0,
        ).castShadow = false
    }
    const addFurniture = (f: Furniture, index: number) => {
      const group = new THREE.Group()
      group.userData.officeFurniture = f
      this.furniture(group, f, index)
      group.traverse((object) => {
        if (object instanceof THREE.Mesh && !object.userData.ignorePicking) {
          object.userData.floorX ??= (f.x + f.w / 2) * TILE
          object.userData.floorY ??= (f.y + f.h / 2) * TILE
        }
      })
      this.furnitureObjects.push(group)
      this.room.add(group)
      const delay = flipIn.get(`${f.x},${f.y}`)
      if (delay !== undefined) {
        group.scale.setScalar(0.001)
        this.flipFurniture.push({ group, delay: delay + 0.45 })
      }
    }
    floor.furniture.filter((f) => f.kind === 'rug').forEach(addFurniture)
    floor.furniture.filter((f) => f.kind !== 'rug').forEach(addFurniture)
    floor.decor.forEach((decor) => this.wallDecor(this.room, decor))
    // Open elevator door with a small honey call panel.
    if (floor.elevator.length) {
      const x = floor.elevator[0].x + 1
      this.box(this.room, x, 1.43, 0.94, 2.1, 2.87, 0.12, '#c1bea9', 0.07)
      this.box(this.room, x, 1.35, 1.025, 1.72, 2.5, 0.08, '#b0aca2', 0.035)
      this.box(this.room, x, 1.35, 1.075, 0.025, 2.5, 0.018, '#d9d9c6', 0.005)
      this.box(this.room, x, 2.87, 1.08, 0.66, 0.16, 0.035, '#777266', 0.025)
      this.box(
        this.room,
        x + 1.25,
        1.38,
        1.08,
        0.16,
        0.32,
        0.04,
        '#f4f0db',
        0.025,
      )
      this.sphere(
        this.room,
        x + 1.25,
        1.4,
        1.12,
        0.035,
        0.035,
        0.012,
        '#a9a59b',
      )
    }
    if (floor.id === 1) {
      for (let x = 1; x < W; x += 4)
        this.box(
          this.room,
          x + 0.5,
          0.025,
          21,
          1.8,
          0.016,
          0.07,
          '#f5ecd2',
          0.005,
        )
    }
    this.grid = new THREE.GridHelper(
      Math.max(W, H),
      Math.max(W, H),
      '#aca79d',
      '#bfbbb3',
    )
    this.grid.position.set(W / 2, 0.045, H / 2)
    const gridMaterials = Array.isArray(this.grid.material)
      ? this.grid.material
      : [this.grid.material]
    gridMaterials.forEach((material) => {
      material.transparent = true
      material.opacity = 0.25
      material.depthWrite = false
    })
    this.grid.visible = false
    this.room.add(this.grid)
    this.cameraKey = ''
  }

  private buildDreamFloor(floor: Floor) {
    const hydrate = this.settleInitial && !this.floor
    this.floor = floor
    this.buildOfficeSurroundings(floor)
    this.buildOutdoorPortal(floor)
    const furnitureKeys = new Set(floor.furniture.map(officeObjectKey))
    for (const [key, object] of this.dreamObjects)
      if (!furnitureKeys.has(key)) {
        // Removed workstations must disappear together with their screens, picking and textures.
        for (let i = this.workSurfaces.length - 1; i >= 0; i--) {
          const entry = this.workSurfaces[i]
          for (
            let node: THREE.Object3D | null = entry.surface.mesh;
            node;
            node = node.parent
          )
            if (node === object.group) {
              entry.surface.dispose()
              this.workSurfaces.splice(i, 1)
              this.workSurfacesDirty = true
              break
            }
        }
        object.group.removeFromParent()
        const index = this.furnitureObjects.indexOf(object.group)
        if (index >= 0) this.furnitureObjects.splice(index, 1)
        this.dreamObjects.delete(key)
      }
    for (const [key, tile] of this.dreamTiles) {
      const [x, y] = key.split(',').map(Number)
      if (!floor.grid[y]?.[x] || floor.grid[y][x] === '#') {
        tile.mesh.removeFromParent()
        this.dreamTiles.delete(key)
      }
    }
    const newTiles: { x: number; z: number }[] = []
    for (let z = 0; z < floor.H; z++)
      for (let x = 0; x < floor.W; x++)
        if (floor.grid[z][x] !== '#' && !this.dreamTiles.has(`${x},${z}`))
          newTiles.push({ x, z })
    newTiles.sort(
      (a, b) =>
        Math.hypot(a.x - 4, a.z - 3) - Math.hypot(b.x - 4, b.z - 3) ||
        a.z - b.z ||
        a.x - b.x,
    )
    const palette: Record<string, string> = {
      l: '#e7ddbf',
      d: '#ead5ae',
      m: '#dddbd7',
      L: '#e7d6af',
      b: '#e3d8bd',
      c: '#dad8d3',
      r: '#dcdad6',
      k: '#e7d7bd',
      u: '#e6dbc3',
      h: '#efeadb',
    }
    newTiles.forEach(({ x, z }, i) => {
      const mesh = this.box(
        this.room,
        x + 0.5,
        0.04,
        z + 0.5,
        0.98,
        0.08,
        0.98,
        palette[floor.grid[z][x]] ?? '#efeadb',
        0.025,
      )
      mesh.visible = false
      this.dreamTiles.set(`${x},${z}`, {
        mesh,
        start: this.dreamTime - (hydrate ? 1 : 0),
        delay: hydrate ? 0 : i * 0.025,
      })
    })
    floor.furniture.forEach((f, index) => {
      const key = officeObjectKey(f)
      if (this.dreamObjects.has(key)) return
      const content = new THREE.Group()
      this.furniture(content, f, index)
      const bounds = new THREE.Box3().setFromObject(content),
        center = bounds.getCenter(new THREE.Vector3()),
        size = bounds.getSize(new THREE.Vector3())
      content.position.copy(center).multiplyScalar(-1)
      const group = new THREE.Group()
      group.userData.officeFurniture = f
      group.add(content)
      this.room.add(group)
      group.position.set(center.x, 10 + (index % 4), center.z)
      group.visible = false
      group.traverse((object) => {
        if (object instanceof THREE.Mesh && !object.userData.ignorePicking) {
          object.userData.floorX = (f.x + f.w / 2) * TILE
          object.userData.floorY = (f.y + f.h / 2) * TILE
        }
      })
      this.furnitureObjects.push(group)
      let readyAt = this.dreamTime
      for (let y = f.y; y < f.y + f.h; y++)
        for (let x = f.x; x < f.x + f.w; x++) {
          const tile = this.dreamTiles.get(`${x},${y}`)
          if (tile) readyAt = Math.max(readyAt, tile.start + tile.delay + 0.72)
        }
      const angle = (index % 2 ? 1 : -1) * (0.5 + (index % 3) * 0.25)
      const halfHeight = Math.max(0.025, size.y / 2)
      if (hydrate) {
        group.visible = true
        group.position.y = halfHeight + 0.035
      }
      this.dreamObjects.set(key, {
        group,
        start: this.dreamTime,
        delay: hydrate ? 0 : readyAt - this.dreamTime + 0.15,
        body: {
          y: hydrate ? halfHeight + 0.035 : 10 + (index % 4),
          vy: 0,
          angle: hydrate ? 0 : angle,
          omega: hydrate ? 0 : (index % 2 ? 1 : -1) * 2.8,
          halfHeight,
          radius: Math.max(size.x, size.z) / 2,
          impacts: 0,
          sleeping: hydrate,
        },
      })
    })
    this.cameraKey = ''
  }

  private buildOutdoorPortal(floor: Floor) {
    const exit = floor.outdoorExit,
      key = JSON.stringify(exit ?? null)
    if (key === this.outdoorPortalKey) return
    this.outdoorPortalKey = key
    if (this.outdoorPortal) {
      this.outdoorPortal.removeFromParent()
      const i = this.furnitureObjects.indexOf(this.outdoorPortal)
      if (i >= 0) this.furnitureObjects.splice(i, 1)
      this.outdoorPortal = null
    }
    if (!exit) return
    const group = new THREE.Group()
    const cx = 0,
      z = 0
    group.name = 'street-elevator'
    group.userData.outdoorExit = true
    group.position.set(exit.x + exit.w / 2, 0, exit.y + exit.h / 2)
    group.scale.set(exit.w / 3, 0.82, exit.h / 1.1)
    this.box(group, cx, 1.7, z, 3, 3.4, 0.82, '#b6bbb7', 0.06)
    for (const face of [-1, 1]) {
      this.box(
        group,
        cx,
        1.6,
        z + face * 0.44,
        2.45,
        2.95,
        0.08,
        '#3c4b4d',
        0.035,
      )
      for (const side of [-1, 1])
        this.box(
          group,
          cx + side * 0.55,
          1.5,
          z + face * 0.5,
          1.08,
          2.72,
          0.06,
          '#a1afac',
          0.025,
        )
      this.box(
        group,
        cx,
        1.5,
        z + face * 0.54,
        0.025,
        2.72,
        0.015,
        '#d7dfd9',
        0.005,
      )
      this.box(
        group,
        cx + 1.28,
        1.5,
        z + face * 0.48,
        0.18,
        0.55,
        0.07,
        '#354b4c',
        0.025,
      )
      this.sphere(
        group,
        cx + 1.28,
        1.5,
        z + face * 0.54,
        0.055,
        0.055,
        0.025,
        '#ffd24a',
      )
    }
    const canvas = document.createElement('canvas')
    canvas.width = 512
    canvas.height = 96
    const context = canvas.getContext('2d')!
    context.fillStyle = '#31574b'
    context.fillRect(0, 0, 512, 96)
    context.fillStyle = '#fff9da'
    context.font = '600 34px "Malgun Gothic",sans-serif'
    context.textAlign = 'center'
    context.fillText('1F  ↓ ', 256, 61)
    const texture = new THREE.CanvasTexture(canvas)
    texture.colorSpace = THREE.SRGBColorSpace
    this.textures.push(texture)
    const material = this.material('street-elevator-sign')
    material.color.set('#ffffff')
    material.map = texture
    for (const face of [-1, 1]) {
      const sign = new THREE.Mesh(
        this.geometry(
          'street-elevator-sign',
          () => new THREE.PlaneGeometry(2.55, 0.48),
        ),
        material,
      )
      sign.position.set(cx, 3.1, z + face * 0.5)
      sign.rotation.y = face < 0 ? Math.PI : 0
      group.add(sign)
    }
    this.room.add(group)
    this.furnitureObjects.push(group)
    this.outdoorPortal = group
  }

  private buildOfficeSurroundings(floor: Floor) {
    // Follow the currently revealed footprint, including the small onboarding office.
    let left = floor.W,
      back = floor.H,
      right = 0,
      front = 0
    for (let z = 0; z < floor.H; z++)
      for (let x = 0; x < floor.W; x++) {
        if (floor.grid[z][x] === '#') continue
        left = Math.min(left, x)
        back = Math.min(back, z)
        right = Math.max(right, x + 1)
        front = Math.max(front, z + 1)
      }
    const key = `${left}/${back}/${right}/${front}`
    if (key === this.environmentKey) return
    this.environmentKey = key
    this.windowCityView.dispose()
    this.windowCityView = new WindowCityView(CITY_SKY)
    this.architecture.clear()
    this.overhead.clear()
    this.firstPersonEnclosure.clear()
    this.rearWindows.clear()
    this.sideWindows.clear()
    this.windowMullions.length = 0
    if (this.city) {
      this.exteriorScene.remove(this.city.root)
      this.city.dispose()
      this.city = null
    }
    if (this.dreamGround)
      this.dreamGround.visible = right > left && front > back
    if (right <= left || front <= back) return
    this.environmentEdge = { x: left, z: back }
    this.architecture.add(
      this.rearWindows,
      this.sideWindows,
      this.overhead,
      this.firstPersonEnclosure,
    )
    this.city = new OfficeCity({ left, back, right, front })
    this.exteriorScene.add(this.city.root)
    const width = right - left,
      depth = front - back
    const cx = (left + right) / 2,
      cz = (back + front) / 2
    if (this.dreamGround) {
      this.dreamGround.position.set(cx, -0.04, cz)
      this.dreamGround.scale.set(width, depth, 1)
    }
    const frame = '#b6b2a9',
      sill = '#d4d2bf'
    let glass = this.materials.get('office-curtain-glass')
    if (!glass) {
      glass = new THREE.MeshStandardMaterial({
        color: '#d7d4cf',
        transparent: true,
        opacity: 0.06,
        roughness: 1,
        metalness: 0,
        envMapIntensity: 0,
        depthWrite: false,
        side: THREE.DoubleSide,
      })
      this.materials.set('office-curtain-glass', glass)
    }

    // Keep the original two-wall cutaway; the remaining shell is only visible at eye level.
    const windowWall = (parent: THREE.Group, length: number) => {
      this.box(parent, length / 2, 0.15, 0, length, 0.14, 0.16, '#dedbcb')
      this.box(parent, length / 2, 0.27, 0, length + 0.08, 0.065, 0.22, sill)
      this.box(parent, length / 2, 4.25, 0, length, 0.14, 0.16, frame)
      const bays = Math.max(1, Math.ceil(length / 3.5))
      for (let i = 0; i <= bays; i++) {
        const mullion = this.box(
          parent,
          (i * length) / bays,
          2.25,
          0,
          0.065,
          3.93,
          0.1,
          frame,
          0.015,
        )
        this.windowMullions.push(mullion)
      }
      for (let i = 0; i < bays; i++) {
        const pane = this.box(
          parent,
          ((i + 0.5) * length) / bays,
          2.25,
          0,
          length / bays - 0.07,
          3.9,
          0.025,
          '#ffffff',
          0,
        )
        pane.material = glass
        pane.castShadow = false
        pane.receiveShadow = false
        this.windowCityView.addPane(pane)
      }
      this.box(parent, length / 2, 3.35, 0, length, 0.035, 0.08, frame, 0.01)
    }
    windowWall(this.rearWindows, width)
    this.rearWindows.position.set(left, 0, back - 0.12)
    windowWall(this.sideWindows, depth)
    this.sideWindows.rotation.y = -Math.PI / 2
    this.sideWindows.position.set(left - 0.12, 0, back)

    const frontWindows = new THREE.Group(),
      rightWindows = new THREE.Group()
    frontWindows.name = 'office-front-windows'
    rightWindows.name = 'office-right-windows'
    this.firstPersonEnclosure.name = 'office-first-person-enclosure'
    this.firstPersonEnclosure.add(frontWindows, rightWindows)
    windowWall(frontWindows, width)
    frontWindows.rotation.y = Math.PI
    frontWindows.position.set(right, 0, front + 0.12)
    windowWall(rightWindows, depth)
    rightWindows.rotation.y = Math.PI / 2
    rightWindows.position.set(right + 0.12, 0, front)

    // Opaque spandrels meet the floor and ceiling so there are no open strips around the glass.
    for (const z of [back - 0.12, front + 0.12]) {
      this.box(
        this.firstPersonEnclosure,
        cx,
        0.17,
        z,
        width + 0.4,
        0.34,
        0.18,
        '#dedbcb',
        0,
      )
      this.box(
        this.firstPersonEnclosure,
        cx,
        4.42,
        z,
        width + 0.4,
        0.36,
        0.18,
        '#dedbcb',
        0,
      )
    }
    for (const x of [left - 0.12, right + 0.12]) {
      this.box(
        this.firstPersonEnclosure,
        x,
        0.17,
        cz,
        0.18,
        0.34,
        depth + 0.4,
        '#dedbcb',
        0,
      )
      this.box(
        this.firstPersonEnclosure,
        x,
        4.42,
        cz,
        0.18,
        0.36,
        depth + 0.4,
        '#dedbcb',
        0,
      )
    }
    for (const x of [left - 0.06, right + 0.06])
      for (const z of [back - 0.06, front + 0.06]) {
        this.box(
          this.firstPersonEnclosure,
          x,
          2.27,
          z,
          0.24,
          4.06,
          0.24,
          frame,
          0,
        )
      }
    const ceiling = this.box(
      this.firstPersonEnclosure,
      cx,
      4.67,
      cz,
      width + 0.44,
      0.18,
      depth + 0.44,
      '#e5e2d5',
      0,
    )
    ceiling.name = 'office-ceiling'
    // Preserve the existing soft room lighting when closing the shell.
    ceiling.castShadow = false
    for (let x = left + 2; x < right; x += 2)
      this.box(
        this.firstPersonEnclosure,
        x,
        4.575,
        cz,
        0.014,
        0.012,
        depth,
        '#cecec2',
        0,
      )
    for (let z = back + 2; z < front; z += 2)
      this.box(
        this.firstPersonEnclosure,
        cx,
        4.575,
        z,
        width,
        0.012,
        0.014,
        '#cecec2',
        0,
      )

    // A shallow foundation and floating linear fixtures anchor the cutaway room.
    this.box(
      this.architecture,
      cx,
      -0.12,
      cz,
      width + 0.2,
      0.22,
      depth + 0.2,
      '#d0cdbd',
      0.06,
    )
    const lamp = this.material('office-linear-light')
    lamp.color.set('#fff6dd')
    lamp.emissive.set('#ffedc2')
    lamp.emissiveIntensity = 0.65
    for (const fraction of [0.3, 0.72]) {
      const x = left + width * fraction
      const length = Math.max(1, depth - 3)
      this.box(this.overhead, x, 4.4, cz, 0.09, 0.09, length, '#9d988c', 0.02)
      for (const offset of [-0.27, 0.27]) {
        const z = cz + length * offset
        this.box(this.overhead, x, 4.28, z, 1.75, 0.1, 0.22, '#aca89e', 0.025)
        const diffuser = this.box(
          this.overhead,
          x,
          4.22,
          z,
          1.62,
          0.025,
          0.18,
          '#fff6dd',
          0.01,
        )
        diffuser.material = lamp
      }
    }
  }

  private animateDream(time: number) {
    for (const tile of this.dreamTiles.values()) {
      const age = time - tile.start - tile.delay
      tile.mesh.visible = age >= 0
      const k = Math.min(1, Math.max(0, age / 0.7))
      tile.mesh.rotation.x = (1 - (1 - Math.pow(1 - k, 3))) * Math.PI
      tile.mesh.position.y = 0.04 + Math.sin(k * Math.PI) * 0.35
      tile.mesh.scale.setScalar(Math.max(0.001, Math.min(1, k * 4)))
    }
    // render()가 lastTime을 먼저 갱신하므로 꿈 사무실 물리 시간은 따로 잰다 (같은 값을 빼면 dt가 늘 0이 되어 가구가 떨어지지 않음)
    const dt = this.lastDreamTime
      ? Math.min(
          0.1,
          Math.max(0, (time - this.lastDreamTime) / DREAM_BUILD_SPEED),
        ) * DREAM_BUILD_SPEED
      : 1 / 60
    this.lastDreamTime = time
    this.physicsAccumulator += dt
    while (this.physicsAccumulator >= 1 / 120) {
      for (const object of this.dreamObjects.values()) {
        if (time < object.start + object.delay) continue
        object.group.visible = true
        stepDrop(object.body, 1 / 120)
        object.group.position.y = object.body.y
        object.group.rotation.z = object.body.angle
      }
      this.physicsAccumulator -= 1 / 120
    }
  }

  get dreamSettled() {
    return (
      [...this.dreamTiles.values()].every(
        (tile) => this.dreamTime >= tile.start + tile.delay + 0.7,
      ) &&
      [...this.dreamObjects.values()].every((object) => object.body.sleeping)
    )
  }

  private robot(
    id: string,
    color: string,
    appearance: 'human' | 'robot' | 'bibi' = 'robot',
  ): Robot {
    const human = appearance === 'human'
    const bibi = appearance === 'bibi'
    const robotShell = bibi ? '#ffd24a' : C.shell
    const skin = '#dcb89e',
      jacket = '#485c69',
      trousers = '#56636c',
      hair = '#393934'
    const root = new THREE.Group()
    const body = new THREE.Group()
    root.add(body)
    this.people.add(root)
    this.contact(root, 0, 0, human ? 1.15 : 1.6, human ? 1 : 1.25, 0.9)
    let badge: THREE.Mesh
    const head = new THREE.Group()
    head.position.set(0, human ? 2.01 : 1.8, 0)
    body.add(head)
    if (human) {
      // A separate, adult-proportioned office worker: blazer, shirt, tie, ID and loafers.
      this.box(body, 0, 1.31, 0, 0.62, 0.77, 0.36, jacket, 0.1)
      this.box(body, 0, 0.87, -0.005, 0.46, 0.24, 0.31, trousers, 0.055)
      this.box(body, 0, 1.46, 0.187, 0.24, 0.4, 0.028, '#efeee5', 0.015)
      for (const side of [-1, 1]) {
        const lapel = this.box(
          body,
          side * 0.14,
          1.46,
          0.205,
          0.105,
          0.37,
          0.035,
          '#5c6f7a',
          0.02,
        )
        lapel.rotation.z = side * -0.26
        const collar = this.box(
          body,
          side * 0.067,
          1.65,
          0.205,
          0.1,
          0.11,
          0.035,
          '#faf8ef',
          0.015,
        )
        collar.rotation.z = side * 0.36
      }
      this.box(body, 0, 1.47, 0.221, 0.048, 0.28, 0.025, '#857f72', 0.012)
      this.box(body, 0, 1.62, 0.227, 0.065, 0.06, 0.03, '#6d685c', 0.014)
      this.cylinder(body, 0, 1.75, 0, 0.095, 0.095, 0.18, skin)
      this.rod(
        body,
        new THREE.Vector3(-0.07, 1.69, 0.2),
        new THREE.Vector3(0.14, 1.25, 0.225),
        0.008,
        '#b4b0a7',
      )
      this.rod(
        body,
        new THREE.Vector3(0.09, 1.69, 0.2),
        new THREE.Vector3(0.19, 1.25, 0.225),
        0.008,
        '#b4b0a7',
      )
      badge = this.box(body, 0.165, 1.19, 0.23, 0.13, 0.17, 0.025, color, 0.014)
      this.box(body, 0.165, 1.2, 0.247, 0.105, 0.112, 0.008, '#efeee5', 0.007)
      this.box(body, 0.165, 1.15, 0.254, 0.055, 0.011, 0.004, '#999487', 0.002)
      this.sphere(head, 0, 0, 0, 0.285, 0.32, 0.262, skin)
      this.sphere(head, 0, 0.17, -0.055, 0.3, 0.19, 0.26, hair)
      this.box(head, -0.246, 0.05, -0.042, 0.07, 0.24, 0.31, hair, 0.03)
      this.box(head, 0.246, 0.07, -0.06, 0.065, 0.2, 0.28, hair, 0.03)
      const fringe = this.sphere(
        head,
        -0.055,
        0.195,
        0.159,
        0.217,
        0.09,
        0.077,
        hair,
      )
      fringe.rotation.z = -0.18
      for (const side of [-1, 1]) {
        this.sphere(
          head,
          side * 0.105,
          0.015,
          0.249,
          0.019,
          0.026,
          0.013,
          '#3c3a34',
        )
        this.box(
          head,
          side * 0.106,
          0.079,
          0.244,
          0.062,
          0.013,
          0.014,
          hair,
          0.005,
        )
        this.sphere(
          head,
          side * 0.282,
          -0.018,
          -0.008,
          0.045,
          0.068,
          0.043,
          skin,
        )
      }
      this.sphere(head, 0, -0.055, 0.262, 0.033, 0.045, 0.03, '#d0a68b')
      this.box(head, 0, -0.143, 0.235, 0.076, 0.014, 0.015, '#9d7867', 0.006)
    } else {
      const shell = this.sphere(body, 0, 0.95, 0, 0.36, 0.46, 0.3, robotShell)
      shell.rotation.x = -0.05
      this.sphere(
        body,
        0,
        1.03,
        0.265,
        0.2,
        0.21,
        0.055,
        bibi ? '#f2bd32' : '#e5e3e0',
      )
      badge = this.sphere(body, 0, 1.05, 0.325, 0.06, 0.06, 0.018, color)
      this.cylinder(
        body,
        0,
        1.39,
        0,
        0.14,
        0.14,
        0.18,
        bibi ? '#d5ad42' : '#908a7c',
      )
      this.sphere(head, 0, 0, 0, 0.55, 0.51, 0.49, robotShell)
      this.sphere(head, 0, -0.035, 0.386, 0.435, 0.332, 0.172, '#2c2a24')
      // The curved honey eyes and smile stay crisp at small orthographic sizes.
      for (const side of [-1, 1]) {
        this.sphere(
          head,
          side * 0.17,
          0.005,
          0.548,
          0.055,
          0.088,
          0.018,
          '#ffd24a',
        )
        this.sphere(
          head,
          side * 0.18,
          0.035,
          0.555,
          0.025,
          0.033,
          0.008,
          '#fff6d6',
        )
        this.sphere(
          head,
          side * 0.285,
          -0.14,
          0.52,
          0.05,
          0.022,
          0.009,
          '#6b5414',
        )
        const ear = this.cylinder(
          head,
          side * 0.535,
          -0.02,
          -0.015,
          0.18,
          0.18,
          0.1,
          bibi ? robotShell : '#ececda',
        )
        ear.rotation.z = Math.PI / 2
        const inner = this.cylinder(
          head,
          side * 0.591,
          -0.02,
          -0.015,
          0.107,
          0.107,
          0.012,
          bibi ? '#d5ad42' : '#d0cec8',
        )
        inner.rotation.z = Math.PI / 2
      }
      const curve = new THREE.QuadraticBezierCurve3(
        new THREE.Vector3(-0.12, -0.13, 0.552),
        new THREE.Vector3(0, -0.235, 0.571),
        new THREE.Vector3(0.12, -0.13, 0.552),
      )
      const smile = new THREE.Mesh(
        this.geometry(
          'smile',
          () => new THREE.TubeGeometry(curve, 14, 0.017, 6, false),
        ),
        this.material('#f5b800', 0.3),
      )
      head.add(smile)
      this.box(
        head,
        0,
        0.16,
        -0.469,
        0.22,
        0.035,
        0.025,
        bibi ? '#e4b632' : '#dad8d4',
        0.01,
      )
    }
    if (bibi) {
      // Bibi's yellow exterior and team-lead badge distinguish the whole character.
      this.cylinder(body, 0, 1.38, 0, 0.19, 0.19, 0.08, '#d5ad42')
      for (const side of [-1, 1])
        this.box(
          body,
          side * 0.35,
          1.23,
          0.05,
          0.16,
          0.085,
          0.26,
          '#d5ad42',
          0.025,
        )
      this.box(body, 0, 1.08, 0.325, 0.18, 0.23, 0.035, '#344b58', 0.035)
      this.box(
        body,
        0,
        1.1,
        0.35,
        0.1,
        0.1,
        0.018,
        '#e9c65e',
        0.015,
      ).rotation.z = Math.PI / 4
    }
    const arms: THREE.Group[] = [],
      elbows: THREE.Group[] = [],
      legs: THREE.Group[] = [],
      knees: THREE.Group[] = []
    for (const side of [-1, 1]) {
      const arm = new THREE.Group()
      arm.position.set(side * (human ? 0.35 : 0.365), human ? 1.59 : 1.2, 0)
      arm.rotation.z = side * 0.075
      body.add(arm)
      const upper = human ? 0.34 : 0.27,
        lower = human ? 0.3 : 0.23
      this.sphere(
        arm,
        0,
        -0.015,
        0,
        human ? 0.09 : 0.095,
        0.095,
        0.09,
        human ? jacket : bibi ? '#e4b632' : '#bbb8b0',
      )
      this.box(
        arm,
        0,
        -upper / 2,
        0,
        human ? 0.17 : 0.15,
        upper,
        human ? 0.2 : 0.17,
        human ? jacket : robotShell,
        0.06,
      )
      const elbow = new THREE.Group()
      elbow.position.y = -upper
      arm.add(elbow)
      this.sphere(
        elbow,
        0,
        0,
        0,
        0.075,
        0.075,
        0.075,
        human ? jacket : bibi ? '#e4b632' : '#aba79d',
      )
      this.box(
        elbow,
        0,
        -lower / 2,
        0,
        human ? 0.15 : 0.135,
        lower,
        human ? 0.18 : 0.155,
        human ? jacket : robotShell,
        0.05,
      )
      if (human)
        this.box(
          elbow,
          0,
          -lower + 0.015,
          0,
          0.14,
          0.045,
          0.17,
          '#efeee5',
          0.014,
        )
      this.sphere(
        elbow,
        0,
        -lower - 0.055,
        0.012,
        0.078,
        0.09,
        0.075,
        human ? skin : bibi ? robotShell : '#e6e5e2',
      )
      arms.push(arm)
      elbows.push(elbow)
      const leg = new THREE.Group()
      leg.position.set(side * (human ? 0.14 : 0.17), human ? 0.84 : 0.59, 0)
      body.add(leg)
      const thigh = human ? 0.37 : 0.24,
        shin = human ? 0.34 : 0.19
      this.box(
        leg,
        0,
        -thigh / 2,
        0,
        human ? 0.19 : 0.195,
        thigh,
        0.23,
        human ? trousers : robotShell,
        0.055,
      )
      const knee = new THREE.Group()
      knee.position.y = -thigh
      leg.add(knee)
      this.sphere(
        knee,
        0,
        0,
        0,
        0.08,
        0.075,
        0.085,
        human ? trousers : bibi ? '#e4b632' : '#b8b4ab',
      )
      this.box(
        knee,
        0,
        -shin / 2,
        0,
        human ? 0.17 : 0.18,
        shin,
        0.21,
        human ? trousers : robotShell,
        0.04,
      )
      this.box(
        knee,
        0,
        -shin - 0.045,
        0.055,
        human ? 0.205 : 0.24,
        0.14,
        human ? 0.32 : 0.34,
        human ? '#3c4546' : bibi ? robotShell : '#e4e2df',
        0.05,
      )
      legs.push(leg)
      knees.push(knee)
    }
    const ringMaterial = this.material('selection-ring')
    ringMaterial.color.set('#f5b800')
    ringMaterial.emissive.set('#6b5414')
    ringMaterial.emissiveIntensity = 0.24
    ringMaterial.transparent = true
    ringMaterial.opacity = 0.95
    const ring = new THREE.Mesh(
      this.geometry(
        `selection-ring/${appearance}`,
        () =>
          new THREE.RingGeometry(human ? 0.47 : 0.67, human ? 0.53 : 0.73, 56),
      ),
      ringMaterial,
    )
    ring.rotation.x = -Math.PI / 2
    ring.position.y = 0.04
    root.add(ring)
    root.traverse((object) => {
      object.userData.entityId = id
    })
    const robot = {
      appearance,
      root,
      body,
      head,
      arms,
      elbows,
      legs,
      knees,
      ring,
      badge,
      phase: 0,
      lastX: NaN,
      lastY: NaN,
    }
    this.robots.set(id, robot)
    return robot
  }

  private animateFlips(time: number) {
    if (!this.flips.length && !this.flipFurniture.length) return
    if (this.flipStart < 0) this.flipStart = time
    const t = time - this.flipStart
    const ease = (k: number) => 1 - Math.pow(1 - k, 3)
    const m = new THREE.Matrix4(),
      q = new THREE.Quaternion(),
      pos = new THREE.Vector3(),
      scale = new THREE.Vector3(),
      axis = new THREE.Vector3(1, 0, 0)
    for (const flip of this.flips) {
      flip.items.forEach((item, i) => {
        const k = Math.min(1, Math.max(0, (t - item.delay) / 0.55))
        const angle =
          item.dir === 'in' ? Math.PI * (1 - ease(k)) : Math.PI * ease(k)
        const lift = Math.sin(Math.PI * k) * 0.55
        const gone = item.dir === 'out' && k >= 1
        q.setFromAxisAngle(axis, angle)
        pos.set(
          item.x + 0.5,
          -0.01 + lift - (item.dir === 'out' ? k * k * 0.3 : 0),
          item.z + 0.5,
        )
        scale.setScalar(gone ? 0 : 1)
        m.compose(pos, q, scale)
        flip.mesh.setMatrixAt(i, m)
      })
      flip.mesh.instanceMatrix.needsUpdate = true
    }
    for (const f of this.flipFurniture) {
      const k = Math.min(1, Math.max(0, (t - f.delay) / 0.45))
      const back =
        k === 0
          ? 0.001
          : 1 + 2.4 * Math.pow(k - 1, 3) + 1.4 * Math.pow(k - 1, 2)
      f.group.scale.set(1, Math.max(0.001, back), 1)
    }
    if (t > this.flipEnd + 0.6) {
      // 끝난 뒤에는 계산을 멈추고, 사라진 타일은 장면에서 뺀다
      for (const flip of this.flips)
        if (flip.items.every((item) => item.dir === 'out'))
          this.room.remove(flip.mesh)
      for (const f of this.flipFurniture) f.group.scale.set(1, 1, 1)
      // in 타일은 마지막(바로 놓인) 상태로 장면에 남는다
      this.flipFurniture = []
      this.flips = []
    }
  }

  rotateView(dx: number, dy: number) {
    if (this.firstPerson) this.firstLook.drag(dx, dy)
    else this.orbit.drag(dx, dy)
  }

  get viewHeading() {
    return this.firstLook.yaw
  }
  get isFirstPerson() {
    return this.firstPerson
  }

  resetView() {
    if (this.firstPerson) this.firstLook.level()
    else this.orbit.reset()
  }

  private updateCamera(
    floor: Floor,
    zoom: number,
    focus: { x: number; y: number } | null | undefined,
    elapsed: number,
    eye?: { x: number; y: number; heading: number },
  ) {
    if (eye) {
      if (!this.firstPerson) this.firstLook.face(eye.heading)
      this.firstPerson = true
      this.firstLook.step(elapsed)
      const frame = firstPersonFrame(
        eye.x,
        eye.y,
        this.firstLook.yaw,
        this.firstLook.pitch,
      )
      const camera = this.eyeCamera
      const aspect = this.width / this.height
      if (camera.aspect !== aspect) {
        camera.aspect = aspect
        camera.updateProjectionMatrix()
      }
      camera.position.set(frame.eye.x, frame.eye.y, frame.eye.z)
      camera.lookAt(
        frame.eye.x + frame.forward.x,
        frame.eye.y + frame.forward.y,
        frame.eye.z + frame.forward.z,
      )
      camera.updateMatrixWorld(true)
      this.camera = camera
      this.cameraKey = ''
      return
    }
    this.firstPerson = false
    this.camera = this.orbitCamera
    const camera = this.orbitCamera
    this.orbit.step(elapsed)
    const { yaw, pitch } = this.orbit
    const cameraKey = `${floor.W}/${floor.H}/${this.width}/${this.height}/${zoom}/${focus?.x}/${focus?.y}/${yaw}/${pitch}`
    if (cameraKey === this.cameraKey) return
    this.cameraKey = cameraKey
    const frame = officeOrbitFrame(
      floor.W,
      floor.H,
      this.width,
      this.height,
      zoom,
      yaw,
      pitch,
    )
    const target = new THREE.Vector3(
      focus ? focus.x / TILE : floor.W / 2,
      1.3,
      focus ? focus.y / TILE : floor.H / 2,
    )
    camera.position
      .copy(target)
      .add(new THREE.Vector3(frame.offset.x, frame.offset.y, frame.offset.z))
    camera.lookAt(target)
    camera.left = frame.left
    camera.right = frame.right
    camera.top = frame.top
    camera.bottom = frame.bottom
    camera.far = this.dream
      ? Math.max(frame.far, CITY_VIEW_DISTANCE)
      : frame.far
    camera.updateProjectionMatrix()
    camera.updateMatrixWorld(true)
  }

  private makeCar(color: string) {
    const car = new THREE.Group()
    this.box(car, 0, 0.56, 0, 3.15, 0.7, 1.35, color, 0.22)
    this.box(car, -0.1, 1.13, 0, 1.8, 0.78, 1.22, color, 0.25)
    this.box(car, -0.1, 1.24, 0.62, 1.38, 0.42, 0.035, '#bab6ad', 0.08)
    this.box(car, 0.74, 1.23, 0, 0.035, 0.4, 1.05, '#bab6ad', 0.06)
    for (const sx of [-1, 1])
      for (const sz of [-1, 1]) {
        const wheel = this.cylinder(
          car,
          sx * 1.02,
          0.31,
          sz * 0.67,
          0.3,
          0.3,
          0.18,
          '#48453d',
        )
        wheel.rotation.x = Math.PI / 2
        const hub = this.cylinder(
          car,
          sx * 1.02,
          0.31,
          sz * 0.77,
          0.15,
          0.15,
          0.025,
          '#cbc8c1',
        )
        hub.rotation.x = Math.PI / 2
      }
    this.contact(car, 0, 0, 4, 2)
    this.scene.add(car)
    return car
  }

  render(
    floor: Floor & { key?: string },
    entities: OfficeEntity3D[],
    time: number,
    opts: {
      zoom: number
      selectedId?: string
      hoverId?: string
      car?: { x: number; color: string } | null
      focus?: { x: number; y: number } | null
      ghost?: {
        kind: string
        x: number
        y: number
        w: number
        h: number
        valid: boolean
      } | null
      decorMode?: boolean
      firstPersonId?: string
      work?: OfficeWorkState
    },
  ) {
    const elapsed = this.lastTime
      ? Math.min(0.06, Math.max(0, (time - this.lastTime) / 1000))
      : 1 / 60
    this.lastTime = time
    this.dreamTime = (time / 1000) * DREAM_BUILD_SPEED
    const floorKey = `${floor.id}/${floor.key ?? ''}/${floor.staticKey ?? ''}/${floor.theme ?? ''}/${floor.furniture.length}`
    if (floorKey !== this.floorKey || this.floor !== floor) {
      // A replaced immutable floor can have the same static key, but new decor.
      this.floorKey = floorKey
      this.buildFloor(floor)
    }
    if (this.workSurfacesDirty || this.lastWork !== opts.work) {
      this.lastWork = opts.work
      this.workSurfacesDirty = false
      for (const { surface, binding } of this.workSurfaces)
        surface.update(
          opts.work,
          binding ? opts.work?.monitors[binding] : opts.work?.summary,
        )
    }
    const eye = opts.firstPersonId
      ? entities.find((entity) => entity.id === opts.firstPersonId)
      : undefined
    const heading =
      eye?.heading ??
      (eye
        ? { down: 0, left: -Math.PI / 2, up: Math.PI, right: Math.PI / 2 }[
            eye.dir
          ]
        : 0)
    this.updateCamera(
      floor,
      opts.zoom,
      opts.focus,
      elapsed,
      eye ? { x: eye.x, y: eye.y, heading } : undefined,
    )
    for (const group of this.furnitureObjects)
      setOfficeViewVisibility(group, this.firstPerson)
    this.animateFlips(time)
    if (this.dream) this.animateDream(this.dreamTime)
    if (this.grid) this.grid.visible = !!opts.decorMode
    if (opts.ghost) {
      const ghost = opts.ghost
      const ghostKey = `${ghost.kind}/${ghost.w}/${ghost.h}/${ghost.valid}`
      if (!this.ghost || ghostKey !== this.ghostKey) {
        if (this.ghost) this.scene.remove(this.ghost)
        this.ghost = new THREE.Group()
        this.ghostKey = ghostKey
        const color = ghost.valid ? '#9f9a8e' : '#dc917e'
        const mesh = this.box(
          this.ghost,
          ghost.w / 2,
          0.08,
          ghost.h / 2,
          ghost.w - 0.06,
          0.12,
          ghost.h - 0.06,
          color,
          0.06,
        )
        const material = this.material(`ghost-${ghost.valid}`)
        material.color.set(color)
        material.transparent = true
        material.opacity = 0.65
        material.depthWrite = false
        material.emissive.set(color)
        material.emissiveIntensity = 0.2
        mesh.material = material
        mesh.castShadow = false
        let lineMaterial = this.lineMaterials.get(color)
        if (!lineMaterial) {
          lineMaterial = new THREE.LineBasicMaterial({
            color,
            transparent: true,
            opacity: 0.9,
          })
          this.lineMaterials.set(color, lineMaterial)
        }
        const outline = new THREE.LineSegments(
          this.geometry(
            `ghost-edge/${ghost.w}/${ghost.h}`,
            () =>
              new THREE.EdgesGeometry(
                new THREE.BoxGeometry(ghost.w - 0.04, 0.6, ghost.h - 0.04),
              ),
          ),
          lineMaterial,
        )
        outline.position.set(ghost.w / 2, 0.33, ghost.h / 2)
        this.ghost.add(outline)
        this.scene.add(this.ghost)
      }
      this.ghost.visible = true
      this.ghost.position.set(ghost.x, 0, ghost.y)
    } else if (this.ghost) this.ghost.visible = false
    const present = new Set<string>()
    for (const entity of entities) {
      present.add(entity.id)
      const look = entity.look as { top?: string } | undefined
      const color = entity.color || look?.top || '#a09b90'
      const appearance = entity.appearance ?? 'robot'
      let robot = this.robots.get(entity.id)
      if (robot && robot.appearance !== appearance) {
        this.people.remove(robot.root)
        this.robots.delete(entity.id)
        robot = undefined
      }
      robot ??= this.robot(entity.id, color, appearance)
      robot.root.visible =
        entity.floor === floor.id &&
        !entity.hidden &&
        !(entity.riding && entity.riding > 0.15) &&
        entity.id !== opts.firstPersonId
      if (!robot.root.visible) continue
      const dx = entity.x - robot.lastX,
        dy = entity.y - robot.lastY
      const distance = Number.isFinite(dx) ? Math.hypot(dx, dy) / TILE : 0
      // Walking advances from real displacement; a paused simulation stays still.
      if (entity.moving && !entity.paused && distance > 0.0001)
        robot.phase += Math.min(distance, 0.35) * 7.3
      robot.lastX = entity.x
      robot.lastY = entity.y
      const walking = entity.moving && !entity.paused && distance > 0.0001
      const swing = walking
        ? Math.sin(robot.phase) * (entity.running ? 0.72 : 0.52)
        : 0
      const idleTime =
        time / 1000 +
        [...entity.id].reduce((sum, char) => sum + char.charCodeAt(0), 0)
      const resting = !walking && entity.activity === 'resting'
      const windowWatching = !walking && entity.activity === 'window'
      const drinking = !walking && entity.activity === 'coffee'
      const typing =
        !walking &&
        entity.activity === 'working' &&
        entity.seated &&
        entity.status !== 'meeting' &&
        entity.status !== 'briefing'
      const breathing =
        !walking && entity.activity ? Math.sin(idleTime * 1.7) * 0.012 : 0
      robot.root.position.set(entity.x / TILE, 0, entity.y / TILE)
      const direction =
        entity.heading ??
        { down: 0, left: -Math.PI / 2, up: Math.PI, right: Math.PI / 2 }[
          entity.dir
        ]
      let delta = direction - robot.body.rotation.y
      delta = Math.atan2(Math.sin(delta), Math.cos(delta))
      robot.body.rotation.y += delta * Math.min(1, elapsed * 14 || 1)
      robot.body.position.y =
        (entity.seated
          ? (entity.seatLift ?? 0.08)
          : walking
            ? Math.abs(Math.sin(robot.phase)) * (entity.running ? 0.085 : 0.055)
            : 0) + breathing
      robot.body.rotation.x =
        entity.running && walking ? 0.13 : resting ? -0.09 : typing ? 0.055 : 0
      robot.body.rotation.z = resting ? Math.sin(idleTime * 0.45) * 0.025 : 0
      robot.head.rotation.y = windowWatching
        ? Math.sin(idleTime * 0.5) * 0.23
        : 0
      robot.head.rotation.z = walking
        ? Math.sin(robot.phase) * 0.025
        : resting
          ? -0.07
          : 0
      const poseBlend = 1 - Math.exp(-elapsed * 16)
      for (let side = 0; side < 2; side++) {
        const stride = side ? -swing : swing
        const armPose =
          drinking && side === 1
            ? -0.65
            : resting
              ? -0.1
              : typing
                ? -0.46 + Math.sin(idleTime * 8 + side * Math.PI) * 0.035
                : entity.seated
                  ? -0.38
                  : stride * 0.6
        const elbowPose =
          drinking && side === 1
            ? -1.9 + Math.sin(idleTime * 0.7) * 0.08
            : resting
              ? -0.5
              : entity.seated
                ? -1.05
                : -0.12 - Math.abs(stride) * 0.16
        const legPose = entity.seated ? -1.35 : -stride * 0.8
        const kneePose = entity.seated ? 1.35 : Math.max(0, stride) * 0.55
        robot.arms[side].rotation.x +=
          (armPose - robot.arms[side].rotation.x) * poseBlend
        robot.elbows[side].rotation.x +=
          (elbowPose - robot.elbows[side].rotation.x) * poseBlend
        robot.legs[side].rotation.x +=
          (legPose - robot.legs[side].rotation.x) * poseBlend
        robot.knees[side].rotation.x +=
          (kneePose - robot.knees[side].rotation.x) * poseBlend
      }
      robot.ring.visible =
        entity.selected ||
        entity.id === opts.selectedId ||
        entity.id === opts.hoverId
      robot.ring.scale.setScalar(
        entity.id === opts.hoverId && entity.id !== opts.selectedId ? 0.9 : 1,
      )
      robot.badge.material = this.material(color, 0.34)
    }
    for (const [id, robot] of this.robots)
      if (!present.has(id)) {
        this.people.remove(robot.root)
        this.robots.delete(id)
      }
    if (opts.car && floor.id === 1) {
      if (!this.car || this.carColor !== opts.car.color) {
        if (this.car) this.scene.remove(this.car)
        this.car = this.makeCar(opts.car.color)
        this.carColor = opts.car.color
      }
      this.car.visible = true
      this.car.position.set(opts.car.x / TILE + 1.6, 0, 21.15)
    } else if (this.car) this.car.visible = false
    // Open the wall nearest an outside orbit camera, like an architectural cutaway.
    this.rearWindows.visible =
      this.firstPerson || this.camera.position.z >= this.environmentEdge.z
    this.sideWindows.visible =
      this.firstPerson || this.camera.position.x >= this.environmentEdge.x
    this.overhead.visible = this.firstPerson
    this.firstPersonEnclosure.visible = this.firstPerson
    // Aim after camera, NPC and furniture transforms settle for this frame.
    this.scene.updateMatrixWorld(true)
    this.camera.updateMatrixWorld(true)
    const aimed = this.firstPerson
      ? this.pickScene(this.width / 2, this.height / 2)
      : null
    this.aimTarget = aimed?.target ?? null
    this.aimOutline.update(aimed?.outline)
    // Overlap pane edges at eye level; restore the original cutaway frame widths outside.
    for (const mullion of this.windowMullions)
      mullion.scale.x = this.firstPerson ? 1.3 : 1
    // A complete sky replaces the transparent canvas beyond the finite city in first person.
    this.exteriorScene.background = this.firstPerson ? this.cityFog.color : null
    this.city?.update(time / 1000, this.camera.position, this.firstPerson)
    if (this.dream && this.city) {
      // In the cutaway view the exterior exists only through the real glass panes.
      // Draw the office last so furniture and window frames naturally occlude it.
      this.renderer.autoClear = false
      this.renderer.clear(true, true, true)
      this.windowCityView.setCityStencil(this.city.root, !this.firstPerson)
      if (!this.firstPerson) {
        this.windowCityView.sync()
        this.renderer.render(this.windowCityView.maskScene, this.camera)
      }
      this.renderer.render(this.exteriorScene, this.camera)
      this.renderer.clearDepth()
      this.renderer.render(this.scene, this.camera)
      this.renderer.autoClear = true
    } else this.renderer.render(this.scene, this.camera)
    this.aimOutline.render(this.renderer, this.scene, this.camera)
  }

  project(x: number, y: number, height = 0) {
    const vector = new THREE.Vector3(x / TILE, height, y / TILE).project(
      this.camera,
    )
    return {
      x: ((vector.x + 1) * this.width) / 2,
      y: ((1 - vector.y) * this.height) / 2,
    }
  }

  private pickScene(
    screenX: number,
    screenY: number,
  ): { target: OfficeAimTarget; outline: THREE.Object3D } | null {
    if (!this.floor) return null
    this.raycaster.setFromCamera(
      new THREE.Vector2(
        (screenX / this.width) * 2 - 1,
        1 - (screenY / this.height) * 2,
      ),
      this.camera,
    )
    const hitObjects: THREE.Object3D[] = [
      ...[...this.robots.values()]
        .filter((robot) => robot.root.visible)
        .map((robot) => robot.body),
      ...this.furnitureObjects,
      ...this.workSurfaces.map(({ surface }) => surface.mesh),
      this.architecture,
    ]
    const interaction = pickOfficeInteraction(this.raycaster, hitObjects)
    if (!interaction) return null
    const { hit, entityId, workHit } = interaction
    if (
      interaction.outdoorExit &&
      this.floor.outdoorExit &&
      this.outdoorPortal
    ) {
      const exit = this.floor.outdoorExit
      return {
        target: { ...exit.landing, outdoorExit: true, label: exit.label },
        outline: this.outdoorPortal,
      }
    }
    if (entityId) {
      const robot = this.robots.get(entityId)
      if (!robot) return null
      return {
        target: {
          x: robot.root.position.x * TILE,
          y: robot.root.position.z * TILE,
          entityId,
          label: '동료와 대화',
        },
        outline: robot.body,
      }
    }
    if (!workHit) return null
    let furniture: Furniture | undefined
    for (
      let node: THREE.Object3D | null = hit.object;
      node;
      node = node.parent
    ) {
      if (node.userData.officeFurniture) {
        furniture = node.userData.officeFurniture
        break
      }
    }
    const x = furniture
      ? (furniture.x + furniture.w / 2) * TILE
      : hit.point.x * TILE
    const y = furniture
      ? (furniture.y + furniture.h / 2) * TILE
      : hit.point.z * TILE
    const surface = (
      hit.object.userData.workSurface as OfficeWorkSurface | undefined
    )?.kind
    return {
      target: { x, y, furniture, workHit, label: workHit.title, surface },
      outline: hit.object,
    }
  }

  screenToWorld(screenX: number, screenY: number): OfficeSceneHit | null {
    const picked = this.pickScene(screenX, screenY)
    if (picked) return picked.target
    if (!this.floor) return null
    const position = this.raycaster.ray.intersectPlane(
      this.ground,
      new THREE.Vector3(),
    )
    if (
      !position ||
      position.x < 0 ||
      position.z < 0 ||
      position.x > this.floor.W ||
      position.z > this.floor.H
    )
      return null
    return { x: position.x * TILE, y: position.z * TILE }
  }

  dispose() {
    this.aimTarget = null
    this.aimOutline.dispose()
    this.workSurfaces.forEach(({ surface }) => surface.dispose())
    this.workSurfaces.length = 0
    this.windowCityView.dispose()
    this.dreamGroundMaterial?.dispose()
    this.room.traverse((object) => {
      if (object instanceof THREE.InstancedMesh) object.dispose()
    })
    this.geometries.forEach((geometry) => geometry.dispose())
    this.materials.forEach((material) => material.dispose())
    this.lineMaterials.forEach((material) => material.dispose())
    if (this.grid) {
      this.grid.geometry.dispose()
      const gridMaterials = Array.isArray(this.grid.material)
        ? this.grid.material
        : [this.grid.material]
      gridMaterials.forEach((material) => material.dispose())
    }
    this.textures.forEach((texture) => texture.dispose())
    if (this.ownsRenderer) {
      this.renderer.dispose()
      this.canvas.remove()
    }
    this.robots.clear()
    this.room.clear()
    this.architecture.clear()
    this.windowMullions.length = 0
    this.city?.dispose()
    this.exteriorScene.clear()
    this.people.clear()
  }
}
