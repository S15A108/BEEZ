import * as THREE from 'three'
import { RoundedBoxGeometry } from 'three/addons/geometries/RoundedBoxGeometry.js'
import type { WorldProject, WorldSummary } from '../types'

type Point = { x: number; y: number }
type Selected =
  { type: 'lot'; index: number } | { type: 'project'; id: string } | null
type Actor = Point & {
  id: string
  dir: string
  moving: boolean
  color?: string
  heading?: number
  running?: boolean
}
type CityCar = Point & { id: string; color: string; facing?: string }
/** 건물 짓기 모드: 빈 부지 위에 단계별로 올라가는 공사 미리보기 (0 측량 · 1 기초 · 2 골조 · 3 외관 · 4 준공 준비 · 5 완공) */
export interface BuildPreview {
  lot: number
  stage: number
  name: string
  design: string
  floors: number
}
interface Options {
  preview?: BuildPreview | null
  zoom: number
  focus: Point
  selected?: Selected
  hover?: number | null
  commuters?: Actor[]
  cars?: CityCar[]
}

const UNIT = 32
const WIDTH = 1200 / UNIT
const DEPTH = 760 / UNIT
const LOT_SIZE = 250 / UNIT
const lotAt = (i: number) => ({
  x: (34 + (i % 4) * 292) / UNIT,
  z: (i < 4 ? 66 : 440) / UNIT,
})
const colors = {
  cream: '#faf5e5',
  white: '#fffdf3',
  oak: '#ccad76',
  oakDark: '#9c8053',
  leaf: '#71914c',
  leafLight: '#94aa64',
  leafDark: '#456a3f',
  glass: '#bbb7af',
  road: '#aca89e',
  stone: '#e6e2d1',
  honey: '#f5b800',
}

/** A view of the existing city coordinates. Movement, access rules and transitions stay in WorldCanvas. */
export class CityRenderer3D {
  readonly canvas: HTMLCanvasElement
  private readonly renderer: THREE.WebGLRenderer
  private readonly ownsRenderer: boolean
  private readonly scene = new THREE.Scene()
  private readonly camera = new THREE.OrthographicCamera(
    -25,
    25,
    20,
    -20,
    0.1,
    180,
  )
  private readonly city = new THREE.Group()
  private readonly dynamic = new THREE.Group()
  private readonly raycaster = new THREE.Raycaster()
  private readonly groundPlane = new THREE.Plane(new THREE.Vector3(0, 1, 0), 0)
  private readonly direction = new THREE.Vector3(0.64, 1.02, 1.08).normalize()
  private readonly materials = new Map<string, THREE.MeshStandardMaterial>()
  private readonly geometries = new Map<string, THREE.BufferGeometry>()
  private readonly actors = new Map<string, THREE.Group>()
  private readonly cars = new Map<string, THREE.Group>()
  private readonly highlights: THREE.Mesh[] = []
  private readonly pickable: THREE.Object3D[] = []
  private readonly signTextures: THREE.Texture[] = []
  private readonly scratch = new THREE.Vector3()
  private readonly ndc = new THREE.Vector2()
  private width = 1
  private height = 1
  private signature = ''
  private disposed = false
  private readonly emptyMarkers: THREE.Object3D[][] = []
  private previewGroup: THREE.Group | null = null
  private previewFresh: THREE.Group | null = null
  private previewSig = ''
  private previewAt = 0
  private previewTextures: THREE.Texture[] = []

  private readonly compact: boolean
  constructor(
    host: HTMLElement,
    compact = false,
    externalRenderer?: THREE.WebGLRenderer,
  ) {
    this.compact = compact
    this.ownsRenderer = !externalRenderer
    this.renderer =
      externalRenderer ??
      new THREE.WebGLRenderer({
        antialias: true,
        alpha: false,
        powerPreference: 'high-performance',
      })
    if (this.ownsRenderer)
      this.renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 1.75))
    this.renderer.outputColorSpace = THREE.SRGBColorSpace
    this.renderer.toneMapping = THREE.ACESFilmicToneMapping
    this.renderer.toneMappingExposure = 1.18
    this.renderer.shadowMap.enabled = true
    this.renderer.shadowMap.type = THREE.PCFShadowMap
    this.canvas = this.renderer.domElement
    if (this.ownsRenderer) {
      this.canvas.className = 'city-three-canvas'
      this.canvas.setAttribute('aria-hidden', 'true')
      Object.assign(this.canvas.style, {
        position: 'absolute',
        inset: '0',
        width: '100%',
        height: '100%',
        zIndex: '0',
        pointerEvents: 'none',
      })
      host.prepend(this.canvas)
    }

    this.scene.background = new THREE.Color(compact ? '#fffefa' : '#f1edda')
    this.scene.add(new THREE.HemisphereLight('#fff8de', '#aeaaa0', 2.4))
    const sun = new THREE.DirectionalLight('#fff2cf', 3.7)
    sun.position.set(-12, 35, 12)
    sun.castShadow = true
    sun.shadow.mapSize.set(2048, 2048)
    Object.assign(sun.shadow.camera, {
      left: -36,
      right: 36,
      top: 30,
      bottom: -30,
      near: 0.5,
      far: 95,
    })
    sun.shadow.normalBias = 0.035
    sun.shadow.bias = -0.00015
    sun.shadow.radius = 4
    sun.target.position.set(WIDTH / 2, 0, DEPTH / 2)
    this.scene.add(sun, sun.target)
    const fill = new THREE.DirectionalLight('#eeedeb', 0.65)
    fill.position.set(35, 18, -15)
    this.scene.add(fill, this.city, this.dynamic)
  }

  setSize(w: number, h: number) {
    if (this.disposed) return
    this.width = Math.max(1, w)
    this.height = Math.max(1, h)
    if (this.ownsRenderer) this.renderer.setSize(this.width, this.height, false)
  }

  private material(color: string, roughness = 0.68, metalness = 0) {
    const key = `${color}|${roughness}|${metalness}`
    let material = this.materials.get(key)
    if (!material) {
      material = new THREE.MeshStandardMaterial({ color, roughness, metalness })
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
    width: number,
    height: number,
    depth: number,
    x: number,
    y: number,
    z: number,
    color: string,
    radius = 0.08,
  ) {
    const r = Math.min(radius, width / 2, height / 2, depth / 2)
    const key = `b|${width}|${height}|${depth}|${r}`
    const mesh = new THREE.Mesh(
      this.geometry(key, () =>
        r > 0.015
          ? new RoundedBoxGeometry(width, height, depth, 2, r)
          : new THREE.BoxGeometry(width, height, depth),
      ),
      this.material(color),
    )
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
      this.geometry('sphere', () => new THREE.SphereGeometry(1, 16, 12)),
      this.material(color, 0.52),
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
    top: number,
    bottom: number,
    height: number,
    x: number,
    y: number,
    z: number,
    color: string,
  ) {
    const key = `c|${top}|${bottom}|${height}`
    const mesh = new THREE.Mesh(
      this.geometry(
        key,
        () => new THREE.CylinderGeometry(top, bottom, height, 12),
      ),
      this.material(color),
    )
    mesh.position.set(x, y, z)
    mesh.castShadow = true
    mesh.receiveShadow = true
    parent.add(mesh)
    return mesh
  }

  private beam(
    parent: THREE.Object3D,
    a: THREE.Vector3,
    b: THREE.Vector3,
    radius: number,
    color: string,
  ) {
    const delta = b.clone().sub(a)
    const mesh = this.cylinder(
      parent,
      radius,
      radius,
      delta.length(),
      (a.x + b.x) / 2,
      (a.y + b.y) / 2,
      (a.z + b.z) / 2,
      color,
    )
    mesh.quaternion.setFromUnitVectors(
      new THREE.Vector3(0, 1, 0),
      delta.normalize(),
    )
    return mesh
  }

  private plant(
    parent: THREE.Object3D,
    x: number,
    z: number,
    size: number,
    tree = false,
    base = 0.15,
  ) {
    const group = new THREE.Group()
    group.position.set(x, base, z)
    group.scale.setScalar(size)
    parent.add(group)
    this.cylinder(group, 0.31, 0.22, 0.45, 0, 0.225, 0, colors.cream)
    this.cylinder(group, 0.28, 0.28, 0.04, 0, 0.46, 0, '#77694c')
    if (tree) {
      this.cylinder(group, 0.065, 0.095, 1.15, 0, 0.92, 0, colors.oakDark)
      ;[
        [0, 1.78, 0, 0.55],
        [-0.37, 1.55, 0.1, 0.43],
        [0.32, 1.63, 0.14, 0.46],
        [0.04, 1.55, -0.35, 0.43],
      ].forEach(([lx, ly, lz, s], i) =>
        this.sphere(
          group,
          lx,
          ly,
          lz,
          s,
          s * 1.1,
          s,
          [colors.leaf, colors.leafLight, colors.leafDark][i % 3],
        ),
      )
    } else {
      for (let i = 0; i < 7; i++) {
        const angle = (i * Math.PI * 2) / 7
        const leaf = this.sphere(
          group,
          Math.sin(angle) * 0.22,
          0.78 + (i % 2) * 0.14,
          Math.cos(angle) * 0.22,
          0.13,
          0.43,
          0.18,
          i % 3 ? colors.leaf : colors.leafLight,
        )
        leaf.rotation.set(
          Math.cos(angle) * 0.65,
          angle,
          -Math.sin(angle) * 0.65,
        )
      }
    }
    return group
  }

  private label(
    parent: THREE.Object3D,
    text: string,
    x: number,
    y: number,
    z: number,
    width: number,
    color = '#625e54',
  ) {
    const surface = document.createElement('canvas')
    surface.width = 512
    surface.height = 112
    const ctx = surface.getContext('2d')!
    ctx.fillStyle = '#fffdf1'
    ctx.beginPath()
    ctx.roundRect(3, 3, 506, 106, 22)
    ctx.fill()
    ctx.strokeStyle = '#dddcc6'
    ctx.lineWidth = 3
    ctx.stroke()
    ctx.fillStyle = color
    ctx.font = '600 37px Pretendard, "Malgun Gothic", sans-serif'
    ctx.textAlign = 'center'
    ctx.textBaseline = 'middle'
    let fitted = text
    while (ctx.measureText(fitted).width > 455 && fitted.length > 2)
      fitted = fitted.slice(0, -2) + '…'
    ctx.fillText(fitted, 256, 57)
    const texture = new THREE.CanvasTexture(surface)
    texture.colorSpace = THREE.SRGBColorSpace
    this.signTextures.push(texture)
    const material = new THREE.SpriteMaterial({
      map: texture,
      depthTest: true,
      transparent: true,
    })
    const sprite = new THREE.Sprite(material)
    sprite.position.set(x, y, z)
    sprite.scale.set(width, (width * surface.height) / surface.width, 1)
    parent.add(sprite)
    return sprite
  }

  private buildEnvironment() {
    this.box(
      this.city,
      WIDTH + 1.5,
      0.65,
      DEPTH + 1.7,
      WIDTH / 2,
      -0.55,
      DEPTH / 2,
      '#d7cfaa',
      0.45,
    )
    this.box(
      this.city,
      WIDTH + 1.25,
      0.28,
      DEPTH + 1.45,
      WIDTH / 2,
      -0.15,
      DEPTH / 2,
      '#e5e6c9',
      0.34,
    )
    this.box(
      this.city,
      WIDTH + 0.6,
      0.09,
      98 / UNIT,
      WIDTH / 2,
      0.04,
      385 / UNIT,
      colors.stone,
      0.18,
    )
    this.box(
      this.city,
      WIDTH + 0.7,
      0.07,
      74 / UNIT,
      WIDTH / 2,
      0.11,
      385 / UNIT,
      colors.road,
      0.13,
    )
    this.box(
      this.city,
      WIDTH + 0.6,
      0.08,
      52 / UNIT,
      WIDTH / 2,
      0.04,
      716 / UNIT,
      colors.stone,
      0.12,
    )
    this.box(
      this.city,
      WIDTH + 0.7,
      0.07,
      34 / UNIT,
      WIDTH / 2,
      0.11,
      721 / UNIT,
      colors.road,
      0.1,
    )
    for (let x = 14; x < 1200; x += 62) {
      this.box(
        this.city,
        30 / UNIT,
        0.012,
        0.07,
        (x + 15) / UNIT,
        0.151,
        386 / UNIT,
        '#f6efcf',
        0.015,
      )
      this.box(
        this.city,
        27 / UNIT,
        0.012,
        0.055,
        (x + 15) / UNIT,
        0.151,
        721 / UNIT,
        '#f6efcf',
        0.015,
      )
    }
    for (let i = 0; i < 3; i++)
      this.box(
        this.city,
        18 / UNIT,
        0.065,
        670 / UNIT,
        (305 + i * 292) / UNIT,
        0.05,
        380 / UNIT,
        '#ece8d6',
        0.05,
      )
    for (let i = 0; i < 4; i++) {
      const x = (85 + i * 292) / UNIT
      this.plant(this.city, x, 0.3, 0.75, true, 0.03)
      this.plant(this.city, x + 4.8, 0.15, 0.48, false, 0.03)
      this.cylinder(
        this.city,
        0.055,
        0.055,
        1.4,
        x + 6.3,
        0.75,
        330 / UNIT,
        '#8e887b',
      )
      const light = this.sphere(
        this.city,
        x + 6.3,
        1.48,
        330 / UNIT,
        0.15,
        0.16,
        0.15,
        '#fff8d6',
      )
      light.castShadow = false
      this.box(
        this.city,
        1.2,
        0.15,
        0.42,
        x + 3.9,
        0.42,
        330 / UNIT,
        colors.oak,
        0.055,
      )
      this.box(
        this.city,
        0.08,
        0.4,
        0.34,
        x + 3.48,
        0.22,
        330 / UNIT,
        colors.oakDark,
        0.02,
      )
      this.box(
        this.city,
        0.08,
        0.4,
        0.34,
        x + 4.32,
        0.22,
        330 / UNIT,
        colors.oakDark,
        0.02,
      )
    }
  }

  private buildLot(index: number, project?: WorldProject) {
    const lot = lotAt(index)
    const group = new THREE.Group()
    group.userData.lot = index
    this.city.add(group)
    const centerX = lot.x + LOT_SIZE / 2
    const centerZ = lot.z + LOT_SIZE / 2
    const pad = this.box(
      group,
      LOT_SIZE,
      0.12,
      LOT_SIZE,
      centerX,
      0.1,
      centerZ,
      project ? '#ece9d8' : '#d3d1cb',
      0.22,
    )
    this.pickable.push(pad)
    const border = this.box(
      group,
      LOT_SIZE + 0.12,
      0.035,
      LOT_SIZE + 0.12,
      centerX,
      0.035,
      centerZ,
      '#a5a095',
      0.24,
    )
    border.visible = false
    this.highlights[index] = border
    this.plant(group, lot.x + 0.45, lot.z + LOT_SIZE - 0.58, 0.68, true)
    this.plant(
      group,
      lot.x + LOT_SIZE - 0.38,
      lot.z + LOT_SIZE - 0.55,
      0.56,
      false,
    )
    if (!project) {
      for (let i = 0; i < 5; i++) {
        const x = lot.x + 0.38 + i * 1.56
        this.box(
          group,
          0.72,
          0.025,
          0.045,
          x,
          0.175,
          lot.z + 0.22,
          '#f8f9e8',
          0.01,
        )
        this.box(
          group,
          0.72,
          0.025,
          0.045,
          x,
          0.175,
          lot.z + LOT_SIZE - 0.22,
          '#f8f9e8',
          0.01,
        )
      }
      const sign = new THREE.Group()
      sign.position.set(centerX, 0.17, centerZ)
      group.add(sign)
      this.cylinder(sign, 0.1, 0.13, 0.6, 0, 0.3, 0, colors.oak)
      this.box(sign, 1.6, 1.25, 0.2, 0, 1.13, 0, colors.white, 0.17)
      this.box(sign, 0.12, 0.56, 0.045, 0, 1.15, 0.125, '#979184', 0.035)
      this.box(sign, 0.56, 0.12, 0.045, 0, 1.15, 0.125, '#979184', 0.035)
      const caption = this.label(
        group,
        `새 프로젝트 · ${index + 1}`,
        centerX,
        0.53,
        centerZ + 1,
        2.6,
      )
      this.emptyMarkers[index] = [sign, caption]
    } else this.buildBuilding(group, project, index)
    group.traverse((object) => {
      object.userData.lot = index
    })
    this.pickable.push(group)
  }

  private buildUnexploredLot(index: number) {
    const lot = lotAt(index)
    const centerX = lot.x + LOT_SIZE / 2
    const centerZ = lot.z + LOT_SIZE / 2
    const group = new THREE.Group()
    this.city.add(group)
    this.box(
      group,
      LOT_SIZE - 0.15,
      0.045,
      LOT_SIZE - 0.15,
      centerX,
      0.045,
      centerZ,
      this.compact ? '#f0efdf' : '#d4d2cc',
      0.35,
    )
    if (!this.compact) {
      this.plant(group, lot.x + 1.25, lot.z + 1.65, 0.8, true)
      this.plant(
        group,
        lot.x + LOT_SIZE - 1.3,
        lot.z + LOT_SIZE - 1.4,
        0.63,
        false,
      )
      this.plant(group, lot.x + LOT_SIZE - 1.6, lot.z + 1.1, 0.45, false)
    }
    this.label(
      group,
      this.compact ? '🔒' : '미개척 지역',
      centerX,
      0.45,
      centerZ + 0.65,
      this.compact ? 0.65 : 2.35,
    )
  }

  private buildBuilding(parent: THREE.Group, p: WorldProject, index: number) {
    const lot = lotAt(index)
    // Match buildingGeom in WorldView: front door = bx + 75, ground + 8.
    const bx = lot.x + 40 / UNIT
    const front = lot.z + 214 / UNIT
    const width = 150 / UNIT
    const depth = (Math.max(1, p.floors) * 22 + 60) / UNIT
    const floors = Math.max(1, p.floors)
    const height = floors * 0.84 + 0.38
    const cx = bx + width / 2
    const cz = front - depth / 2
    const facade =
      (
        {
          glass: '#e0dfdb',
          brick: '#dfb49a',
          modern: colors.white,
          tower: '#dbd9d4',
          wood: '#dfc596',
        } as Record<string, string>
      )[p.design] ?? colors.white
    this.box(
      parent,
      width + 0.34,
      0.22,
      depth + 0.38,
      cx,
      0.25,
      cz,
      '#f7f3e4',
      0.14,
    )
    this.box(
      parent,
      width,
      height,
      depth,
      cx,
      height / 2 + 0.35,
      cz,
      facade,
      0.14,
    )
    this.box(
      parent,
      width + 0.27,
      0.2,
      depth + 0.22,
      cx,
      height + 0.43,
      cz,
      colors.white,
      0.11,
    )
    for (let floor = 0; floor < floors; floor++) {
      const level = 0.73 + floor * 0.84
      this.box(
        parent,
        width + 0.08,
        0.075,
        depth + 0.08,
        cx,
        0.43 + floor * 0.84,
        cz,
        floor % 2 === 0 ? colors.oak : '#f4ecda',
        0.025,
      )
      for (let col = 0; col < 4; col++) {
        if (floor === 0 && (col === 1 || col === 2)) continue
        const wx = bx + 0.65 + (col * (width - 1.3)) / 3
        this.box(
          parent,
          0.67,
          0.48,
          0.065,
          wx,
          level,
          front + 0.028,
          '#ebead9',
          0.035,
        )
        this.box(
          parent,
          0.55,
          0.36,
          0.04,
          wx,
          level + 0.015,
          front + 0.068,
          colors.glass,
          0.025,
        )
        this.box(
          parent,
          0.57,
          0.035,
          0.055,
          wx,
          level - 0.03,
          front + 0.1,
          '#e1ddc5',
          0.01,
        )
      }
      for (
        let col = 0;
        col < Math.max(2, Math.min(4, Math.floor(depth)));
        col++
      ) {
        const wz =
          front -
          0.65 -
          col *
            Math.max(
              0.63,
              (depth - 1.3) / Math.max(1, Math.min(3, Math.floor(depth) - 1)),
            )
        this.box(
          parent,
          0.055,
          0.43,
          0.58,
          bx + width + 0.025,
          level,
          wz,
          colors.glass,
          0.025,
        )
      }
    }
    this.box(parent, 0.85, 0.97, 0.1, cx, 0.82, front + 0.08, colors.oak, 0.05)
    this.box(parent, 0.65, 0.8, 0.06, cx, 0.82, front + 0.145, '#817b6e', 0.055)
    this.box(
      parent,
      0.035,
      0.76,
      0.035,
      cx,
      0.82,
      front + 0.19,
      '#f7ebcd',
      0.008,
    )
    this.sphere(
      parent,
      cx + 0.2,
      0.82,
      front + 0.2,
      0.035,
      0.035,
      0.035,
      '#e6ce8d',
    )
    this.box(
      parent,
      1.35,
      0.12,
      0.7,
      cx,
      1.38,
      front + 0.28,
      colors.white,
      0.065,
    )
    this.box(parent, 1.3, 0.05, 0.65, cx, 0.18, front + 0.31, '#d5c9a8', 0.025)
    this.label(
      parent,
      p.name,
      cx,
      height + 1,
      cz,
      Math.min(4.7, width + 0.2),
      p.has_badge ? '#756f63' : '#9a967f',
    )
    this.plant(parent, bx + 0.47, front + 0.23, 0.47, false, 0.34)
    this.plant(parent, bx + width - 0.46, front + 0.23, 0.47, false, 0.34)
    const roof = height + 0.55
    this.box(
      parent,
      width - 0.45,
      0.05,
      depth - 0.5,
      cx,
      roof,
      cz,
      '#e9e7d6',
      0.03,
    )
    this.box(
      parent,
      0.85,
      0.25,
      0.75,
      bx + width - 0.72,
      roof + 0.14,
      cz,
      '#d5d2cd',
      0.07,
    )
    for (let i = 0; i < 4; i++)
      this.box(
        parent,
        0.6,
        0.025,
        0.035,
        bx + width - 0.72,
        roof + 0.28,
        cz - 0.22 + i * 0.14,
        '#afaba1',
        0.008,
      )
    if (p.design === 'wood' || p.design === 'modern')
      this.plant(parent, bx + 0.6, cz, 0.43, false, roof + 0.03)
    if (p.status === 'constructing')
      this.scaffold(parent, bx, front, width, depth, height)
  }

  private scaffold(
    parent: THREE.Group,
    bx: number,
    front: number,
    width: number,
    depth: number,
    height: number,
  ) {
    const scaffoldZ = front + 0.36
    for (let col = 0; col < 3; col++) {
      const x = bx - 0.2 + (col * (width + 0.4)) / 2
      this.cylinder(
        parent,
        0.035,
        0.035,
        height + 0.8,
        x,
        (height + 0.8) / 2 + 0.13,
        scaffoldZ,
        '#bd9b5c',
      )
    }
    for (let floor = 0; floor < Math.max(2, Math.ceil(height)); floor++) {
      const y = 0.6 + floor * 0.85
      this.box(
        parent,
        width + 0.65,
        0.055,
        0.42,
        bx + width / 2,
        y,
        scaffoldZ,
        '#ead8ab',
        0.02,
      )
      if (floor % 2 === 0)
        this.beam(
          parent,
          new THREE.Vector3(bx - 0.2, y, scaffoldZ),
          new THREE.Vector3(
            bx + width / 2,
            Math.min(height + 0.8, y + 0.85),
            scaffoldZ,
          ),
          0.025,
          '#bd9b5c',
        )
    }
    const craneX = bx + width + 0.61,
      craneZ = front - depth + 0.6
    this.box(
      parent,
      0.45,
      height + 2.2,
      0.45,
      craneX,
      (height + 2.2) / 2,
      craneZ,
      '#d9bb68',
      0.05,
    )
    this.box(
      parent,
      3.4,
      0.18,
      0.22,
      craneX - 0.9,
      height + 2.2,
      craneZ,
      '#d9bb68',
      0.04,
    )
    this.cylinder(
      parent,
      0.018,
      0.018,
      1.5,
      craneX - 2.25,
      height + 1.4,
      craneZ,
      '#91896b',
    )
    this.box(
      parent,
      0.4,
      0.28,
      0.37,
      craneX - 2.25,
      height + 0.58,
      craneZ,
      '#a39c7d',
      0.035,
    )
  }

  private rebuild(data: WorldSummary) {
    this.city.traverse((object) => {
      if (object instanceof THREE.Sprite) object.material.dispose()
    })
    this.city.clear()
    this.pickable.length = 0
    this.highlights.length = 0
    this.emptyMarkers.length = 0
    for (const texture of this.signTextures) texture.dispose()
    this.signTextures.length = 0
    if (!this.compact) this.buildEnvironment()
    const availablePlot = data.lots.find((lot) => !lot.project_id)?.index
    for (const lot of data.lots) {
      const before = this.city.children.length
      if (lot.project_id || lot.index === availablePlot)
        this.buildLot(
          lot.index,
          data.projects.find((project) => project.id === lot.project_id),
        )
      else this.buildUnexploredLot(lot.index)
      if (this.compact) {
        const origin = lotAt(lot.index)
        const positions = [
          [0, 0],
          [1, 0],
          [0, -1],
          [-1, 0],
          [0, 1],
          [1, -1],
          [-1, -1],
          [1, 1],
        ]
        const [x, z] = positions[lot.index] ?? [2, 0]
        for (const group of this.city.children.slice(before)) {
          group.position.set(
            x * (LOT_SIZE + 0.18) - origin.x - LOT_SIZE / 2,
            0,
            z * (LOT_SIZE + 0.18) - origin.z - LOT_SIZE / 2,
          )
        }
      }
    }
  }

  /** 공사 미리보기: 단계가 바뀌면 다시 만들고, 이번 단계에 새로 생긴 부분만 땅에서 솟아오르게 한다 */
  private syncPreview(preview: BuildPreview | null | undefined, time: number) {
    const sig = preview ? JSON.stringify(preview) : ''
    if (sig !== this.previewSig) {
      this.previewSig = sig
      if (this.previewGroup) {
        this.dynamic.remove(this.previewGroup)
        this.previewGroup = null
        this.previewFresh = null
      }
      for (const texture of this.previewTextures) texture.dispose()
      this.previewTextures = []
      if (preview) {
        const before = this.signTextures.length
        const stable = new THREE.Group(),
          fresh = new THREE.Group()
        this.buildPreview(stable, fresh, preview)
        // 미리보기 라벨 텍스처는 도시 재구성과 따로 정리한다
        this.previewTextures = this.signTextures.splice(before)
        const group = new THREE.Group()
        group.add(stable, fresh)
        this.dynamic.add(group)
        this.previewGroup = group
        this.previewFresh = fresh
        this.previewAt = time
      }
    }
    this.emptyMarkers.forEach((markers, i) =>
      markers?.forEach((m) => {
        m.visible = preview?.lot !== i
      }),
    )
    if (this.previewFresh) {
      const k = Math.min(1, Math.max(0, (time - this.previewAt) / 0.75))
      const back = 1 + 2.2 * Math.pow(k - 1, 3) + 1.2 * Math.pow(k - 1, 2)
      this.previewFresh.scale.set(1, Math.max(0.01, back), 1)
    }
    if (this.previewGroup && preview)
      this.previewGroup.position.y =
        preview.stage === 0 ? Math.sin(time * 4) * 0.02 : 0
  }

  private buildPreview(
    stable: THREE.Group,
    fresh: THREE.Group,
    p: BuildPreview,
  ) {
    const lot = lotAt(p.lot)
    const bx = lot.x + 40 / UNIT,
      front = lot.z + 214 / UNIT,
      width = 150 / UNIT
    const floors = Math.max(1, p.floors)
    const depth = (floors * 22 + 60) / UNIT
    const cx = bx + width / 2,
      cz = front - depth / 2
    const at = (stage: number) => (stage === p.stage ? fresh : stable)
    // 0 측량: 모서리 말뚝과 점선 경계
    const survey = at(0)
    for (const [x, z] of [
      [bx, front],
      [bx + width, front],
      [bx, front - depth],
      [bx + width, front - depth],
    ]) {
      this.cylinder(survey, 0.05, 0.06, 0.7, x, 0.45, z, '#d98f2b')
      this.box(survey, 0.22, 0.12, 0.03, x + 0.12, 0.72, z, '#f2b443', 0.01)
    }
    const dash = (x1: number, z1: number, x2: number, z2: number) => {
      const n = Math.max(2, Math.round(Math.hypot(x2 - x1, z2 - z1) / 0.45))
      for (let i = 0; i < n; i += 2) {
        const a = i / n,
          b = Math.min(1, (i + 1) / n)
        const x = x1 + ((x2 - x1) * (a + b)) / 2,
          z = z1 + ((z2 - z1) * (a + b)) / 2
        this.box(
          survey,
          Math.abs(x2 - x1) > 0.01 ? Math.abs(x2 - x1) * (b - a) : 0.05,
          0.03,
          Math.abs(z2 - z1) > 0.01 ? Math.abs(z2 - z1) * (b - a) : 0.05,
          x,
          0.19,
          z,
          '#e7a33e',
          0.005,
        )
      }
    }
    dash(bx, front, bx + width, front)
    dash(bx, front - depth, bx + width, front - depth)
    dash(bx, front - depth, bx, front)
    dash(bx + width, front - depth, bx + width, front)
    if (p.stage === 0) {
      this.label(survey, '📍 부지 측량 중', cx, 1.4, cz, 2.6, '#a26b17')
      return
    }
    // 1 기초: 슬래브 + 이름 팻말
    const base = at(1)
    this.box(
      base,
      width + 0.34,
      0.24,
      depth + 0.38,
      cx,
      0.26,
      cz,
      '#d8d1b8',
      0.1,
    )
    this.box(base, width + 0.1, 0.05, depth + 0.1, cx, 0.4, cz, '#bfb595', 0.02)
    if (p.stage < 3)
      this.label(
        base,
        p.name || '새 프로젝트',
        cx,
        1.9,
        front + 0.4,
        Math.min(4.2, width + 0.4),
        '#7c5a1e',
      )
    if (p.stage === 1) return
    // 2 골조: 층마다 기둥과 바닥판, 비계와 크레인
    const height = floors * 0.84 + 0.38
    if (p.stage === 2) {
      for (let floor = 0; floor < floors; floor++) {
        const y0 = 0.43 + floor * 0.84
        for (const [x, z] of [
          [bx + 0.1, front - 0.1],
          [bx + width - 0.1, front - 0.1],
          [bx + 0.1, front - depth + 0.1],
          [bx + width - 0.1, front - depth + 0.1],
          [cx, front - 0.1],
          [cx, front - depth + 0.1],
        ]) {
          this.box(fresh, 0.12, 0.84, 0.12, x, y0 + 0.42, z, '#c9c3ad', 0.02)
        }
        this.box(
          fresh,
          width,
          0.07,
          depth,
          cx,
          y0 + 0.84,
          cz,
          floor % 2 ? '#e7dfc8' : colors.oak,
          0.02,
        )
      }
      this.scaffold(stable, bx, front, width, depth, height)
      return
    }
    // 3 외관 · 4 준공 준비 · 5 완공: 실제 건물 모양 (공사 중이면 비계 포함)
    const project = {
      id: '__preview',
      name: p.name || '새 프로젝트',
      design: p.design,
      floors,
      status: p.stage >= 5 ? 'ready' : 'constructing',
      has_badge: true,
    } as WorldProject
    this.buildBuilding(p.stage === 3 ? fresh : stable, project, p.lot)
    if (p.stage === 4) {
      // 준공 테이프와 기둥
      this.box(fresh, 1.9, 0.07, 0.03, cx, 0.75, front + 0.75, '#e2554c', 0.01)
      this.cylinder(
        fresh,
        0.03,
        0.03,
        1.1,
        cx - 1,
        0.7,
        front + 0.75,
        '#8a7b5c',
      )
      this.cylinder(
        fresh,
        0.03,
        0.03,
        1.1,
        cx + 1,
        0.7,
        front + 0.75,
        '#8a7b5c',
      )
      this.label(
        fresh,
        '🎀 준공 테이프 커팅 대기',
        cx,
        1.55,
        front + 0.9,
        2.8,
        '#b8433b',
      )
    }
    if (p.stage >= 5) {
      for (let i = 0; i < 14; i++) {
        const a = (i / 14) * Math.PI * 2
        this.box(
          fresh,
          0.12,
          0.12,
          0.02,
          cx + Math.cos(a) * 1.6,
          height + 1.2 + Math.sin(i * 1.7) * 0.5,
          cz + Math.sin(a) * 1.2,
          ['#f2c94c', '#e2554c', '#f5b800', '#8fb3e8'][i % 4],
          0.01,
        )
      }
    }
  }

  private createRobot(id: string, color?: string) {
    const root = new THREE.Group()
    this.dynamic.add(root)
    const body = new THREE.Group()
    root.add(body)
    root.userData.body = body
    this.sphere(body, 0, 0.58, 0, 0.23, 0.29, 0.19, '#fcfcf1')
    this.sphere(body, 0, 1.02, 0.015, 0.34, 0.32, 0.3, '#fffdf0')
    this.sphere(body, 0, 1.015, 0.248, 0.258, 0.19, 0.105, '#2b2924')
    this.sphere(body, -0.095, 1.04, 0.342, 0.033, 0.047, 0.014, colors.honey)
    this.sphere(body, 0.095, 1.04, 0.342, 0.033, 0.047, 0.014, colors.honey)
    this.box(body, 0.09, 0.025, 0.02, 0, 0.945, 0.353, colors.honey, 0.01)
    this.sphere(body, -0.338, 1.01, 0, 0.052, 0.115, 0.13, colors.oak)
    this.sphere(body, 0.338, 1.01, 0, 0.052, 0.115, 0.13, colors.oak)
    this.box(body, 0.15, 0.08, 0.035, 0, 0.63, 0.185, color ?? '#a09b90', 0.035)
    const leftArm = this.sphere(
      body,
      -0.255,
      0.58,
      0,
      0.075,
      0.2,
      0.085,
      '#f0f0e4',
    )
    const rightArm = this.sphere(
      body,
      0.255,
      0.58,
      0,
      0.075,
      0.2,
      0.085,
      '#f0f0e4',
    )
    const leftLeg = this.box(
      body,
      0.145,
      0.24,
      0.22,
      -0.115,
      0.25,
      0.02,
      '#efefe3',
      0.065,
    )
    const rightLeg = this.box(
      body,
      0.145,
      0.24,
      0.22,
      0.115,
      0.25,
      0.02,
      '#efefe3',
      0.065,
    )
    root.userData.limbs = [leftArm, rightArm, leftLeg, rightLeg]
    root.userData.id = id
    if (id === '__player') {
      const ring = new THREE.Mesh(
        this.geometry(
          'player-ring',
          () => new THREE.TorusGeometry(0.43, 0.025, 8, 32),
        ),
        this.material('#9a9588'),
      )
      ring.rotation.x = Math.PI / 2
      ring.position.y = 0.12
      root.add(ring)
    }
    return root
  }

  private syncRobot(actor: Actor, time: number) {
    let root = this.actors.get(actor.id)
    if (!root) {
      root = this.createRobot(actor.id, actor.color)
      this.actors.set(actor.id, root)
    }
    root.visible = true
    root.position.set(actor.x / UNIT, 0.05, actor.y / UNIT)
    const body = root.userData.body as THREE.Group
    body.rotation.y =
      actor.heading ??
      (
        {
          down: 0,
          right: Math.PI / 2,
          up: Math.PI,
          left: -Math.PI / 2,
        } as Record<string, number>
      )[actor.dir] ??
      0
    const gait = time * (actor.running ? 17 : 11)
    body.position.y = actor.moving
      ? Math.abs(Math.sin(gait)) * (actor.running ? 0.075 : 0.045)
      : Math.sin(time * 2) * 0.01
    body.rotation.x = actor.moving && actor.running ? 0.13 : 0
    const limbs = root.userData.limbs as THREE.Object3D[]
    limbs.forEach((limb, i) => {
      limb.rotation.x = actor.moving
        ? Math.sin(gait + (i % 2) * Math.PI) * (actor.running ? 0.62 : 0.42)
        : 0
    })
  }

  private createCar(color: string) {
    const root = new THREE.Group()
    this.dynamic.add(root)
    this.box(root, 1.65, 0.45, 0.82, 0, 0.43, 0, color, 0.2)
    this.box(root, 0.9, 0.42, 0.73, -0.1, 0.79, 0, '#f6f3e6', 0.17)
    this.box(root, 0.52, 0.23, 0.75, -0.09, 0.83, 0, '#a09b90', 0.055)
    for (const x of [-0.49, 0.49])
      for (const z of [-0.41, 0.41]) {
        const wheel = this.cylinder(
          root,
          0.18,
          0.18,
          0.1,
          x,
          0.25,
          z,
          '#706b60',
        )
        wheel.rotation.x = Math.PI / 2
        const hub = this.cylinder(
          root,
          0.075,
          0.075,
          0.115,
          x,
          0.25,
          z,
          '#dfdec9',
        )
        hub.rotation.x = Math.PI / 2
      }
    this.box(root, 0.035, 0.12, 0.17, 0.834, 0.48, -0.23, '#fff2b5', 0.025)
    this.box(root, 0.035, 0.12, 0.17, 0.834, 0.48, 0.23, '#fff2b5', 0.025)
    return root
  }

  render(
    data: WorldSummary,
    player: Point & {
      dir: string
      moving: boolean
      heading?: number
      running?: boolean
    },
    time: number,
    opts: Options,
  ) {
    if (this.disposed) return
    const signature = JSON.stringify([
      data.lots,
      data.projects.map((p) => [
        p.id,
        p.name,
        p.lot,
        p.design,
        p.floors,
        p.status,
        p.has_badge,
      ]),
    ])
    if (this.signature !== signature) {
      this.signature = signature
      this.rebuild(data)
      this.previewSig = '__stale'
    }
    const preview =
      opts.preview &&
      data.lots.some((l) => l.index === opts.preview!.lot && !l.project_id)
        ? opts.preview
        : null
    this.syncPreview(preview, time)
    const selection = opts.selected
    const selectedLot =
      selection?.type === 'lot'
        ? selection.index
        : selection?.type === 'project'
          ? data.projects.find((p) => p.id === selection.id)?.lot
          : null
    this.highlights.forEach((highlight, i) => {
      highlight.visible = i === selectedLot || i === opts.hover
      highlight.material = this.material(
        i === selectedLot ? '#f5b800' : '#f2c230',
      )
      highlight.scale.setScalar(
        i === selectedLot ? 1.025 + Math.sin(time * 3) * 0.003 : 1.01,
      )
    })
    const actorIds = new Set([
      '__player',
      ...(opts.commuters ?? []).map((a) => a.id),
    ])
    for (const [id, actor] of this.actors)
      if (!actorIds.has(id)) actor.visible = false
    if (!this.compact)
      this.syncRobot({ ...player, id: '__player', color: '#b2a0ca' }, time)
    for (const actor of opts.commuters ?? []) this.syncRobot(actor, time)
    const cars = opts.cars ?? []
    const carIds = new Set(cars.map((car) => car.id))
    for (const [id, car] of this.cars) if (!carIds.has(id)) car.visible = false
    for (const car of cars) {
      let model = this.cars.get(car.id)
      if (!model) {
        model = this.createCar(car.color)
        this.cars.set(car.id, model)
      }
      model.visible = true
      model.position.set(car.x / UNIT, 0.06, car.y / UNIT)
      model.rotation.y = car.facing === 'left' ? Math.PI : 0
    }
    const aspect = this.width / this.height
    // Fit the entire miniature at zoom=1; original entrance/arrival camera zooms are retained.
    const span =
      Math.max(this.compact ? 29 : 31.5, (this.compact ? 36 : 47) / aspect) /
      Math.max(0.7, opts.zoom)
    this.camera.left = (-span * aspect) / 2
    this.camera.right = (span * aspect) / 2
    this.camera.top = span / 2
    this.camera.bottom = -span / 2
    const focus = opts.zoom <= 1.001 ? { x: 600, y: 380 } : opts.focus
    const target = this.compact
      ? new THREE.Vector3(0, 0.4, 0)
      : new THREE.Vector3(
          focus.x / UNIT,
          opts.zoom <= 1.001 ? 0.7 : 0.2,
          focus.y / UNIT,
        )
    this.camera.position.copy(target).addScaledVector(this.direction, 65)
    this.camera.lookAt(target)
    this.camera.updateProjectionMatrix()
    this.camera.updateMatrixWorld()
    this.renderer.render(this.scene, this.camera)
  }

  project(x: number, y: number, height = 0): Point {
    this.scratch.set(x / UNIT, height / UNIT, y / UNIT).project(this.camera)
    return {
      x: ((this.scratch.x + 1) * this.width) / 2,
      y: ((1 - this.scratch.y) * this.height) / 2,
    }
  }

  screenToWorld(
    screenX: number,
    screenY: number,
  ): (Point & { lot?: number }) | null {
    if (this.disposed || this.width <= 1 || this.height <= 1) return null
    this.ndc.set(
      (screenX / this.width) * 2 - 1,
      1 - (screenY / this.height) * 2,
    )
    this.raycaster.setFromCamera(this.ndc, this.camera)
    const hit = this.raycaster
      .intersectObjects(this.pickable, true)
      .find((intersection) =>
        Number.isInteger(intersection.object.userData.lot),
      )
    if (hit) {
      const lot = Number(hit.object.userData.lot)
      const pos = lotAt(lot)
      return {
        x: (pos.x + LOT_SIZE / 2) * UNIT,
        y: (pos.z + LOT_SIZE / 2) * UNIT,
        lot,
      }
    }
    const point = this.raycaster.ray.intersectPlane(
      this.groundPlane,
      this.scratch,
    )
    return point ? { x: point.x * UNIT, y: point.z * UNIT } : null
  }

  dispose() {
    if (this.disposed) return
    this.disposed = true
    this.scene.traverse((object) => {
      if (object instanceof THREE.Sprite) object.material.dispose()
    })
    for (const texture of [...this.signTextures, ...this.previewTextures])
      texture.dispose()
    for (const geometry of this.geometries.values()) geometry.dispose()
    for (const material of this.materials.values()) material.dispose()
    if (this.ownsRenderer) {
      this.renderer.dispose()
      this.renderer.forceContextLoss()
      this.canvas.remove()
    }
    this.actors.clear()
    this.cars.clear()
    this.pickable.length = 0
  }
}
