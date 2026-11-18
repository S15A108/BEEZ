import * as THREE from 'three'
import { mergeGeometries } from 'three/addons/utils/BufferGeometryUtils.js'
import { loadKenney, type KenneyModel } from '../kenney'

export interface OfficeCityBounds {
  left: number
  back: number
  right: number
  front: number
}

type Finish = 'stone' | 'glass' | 'metal' | 'paint' | 'foliage' | 'light'
interface Stamp {
  matrix: THREE.Matrix4
  color: THREE.Color
}
interface Batch {
  geometry: THREE.BufferGeometry
  material: THREE.Material
  stamps: Stamp[]
}
interface Sector {
  root: THREE.Group
  outward: THREE.Vector2
  traffic: Traffic
}
/** Kenney 건물로 바꿔 끼울 자리. matrix는 그 root 기준 바닥 가운데이고 앞(+Z)이 길을 본다. */
interface KenneySpot {
  root: THREE.Group
  matrix: THREE.Matrix4
  w: number
  h: number
  d: number
  lod: 'near' | 'mid' | 'far'
  seed: number
}
// 가까운 건물은 비율이 맞는 모델을 고르고, 먼 건물은 저해상도 모델을 늘려 쓴다(Kenney City Kit, CC0).
const KENNEY_NEAR = [
  'building-a',
  'building-b',
  'building-d',
  'building-f',
  'building-g',
  'building-h',
  'building-l',
  'building-m',
  'building-skyscraper-b',
  'building-skyscraper-d',
]
const KENNEY_FAR = [
  'low-detail-building-a',
  'low-detail-building-b',
  'low-detail-building-c',
  'low-detail-building-d',
  'low-detail-building-g',
  'low-detail-building-h',
  'low-detail-building-j',
  'low-detail-building-m',
]
interface Car {
  phase: number
  speed: number
  direction: number
  lane: number
  color: string
}
interface CarPart {
  matrix: THREE.Matrix4
  color: string
  body?: boolean
}
interface Traffic {
  root: THREE.Group
  length: number
  cars: Car[]
  batches: { mesh: THREE.InstancedMesh; parts: CarPart[] }[]
  walkingPositions?: number[]
  walkingTime?: number
}
interface WalkObstacle {
  x: number
  z: number
  w: number
  d: number
}

// The office sits above the street: a downward orbit sees neighboring facades through its glass.
export const CITY_GROUND_Y = -14.6
export const CITY_WALK_RADIUS = 180
const GROUND = CITY_GROUND_Y
const DISTRICT_SPACING = 40
const DISTRICT_REACH = 880
const GROUND_REACH = 1200
const PALETTE = [
  '#dad8d4',
  '#ccc9c3',
  '#cdc9bc',
  '#c0cccf',
  '#ddd1bf',
  '#c7c4bd',
]
const GLASS = ['#76959e', '#87a2aa', '#9db4b8', '#bdbab2', '#839ea8']
const CAR_COLORS = [
  '#f3eee1',
  '#6f6a5e',
  '#bd775c',
  '#3b505f',
  '#b9b5ac',
  '#d4b06c',
]
const positiveMod = (value: number, size: number) =>
  ((value % size) + size) % size

/** An elevated office overlooks a complete street block. Everything is local geometry. */
export class OfficeCity {
  readonly root = new THREE.Group()
  private readonly geometries = new Map<string, THREE.BufferGeometry>()
  private readonly materials = new Map<string, THREE.MeshStandardMaterial>()
  private readonly sectors: Sector[] = []
  private readonly nearBuildingBounds: THREE.Box2[] = []
  private readonly firstPersonGround: THREE.Mesh
  private readonly center: THREE.Vector2
  private readonly matrix = new THREE.Matrix4()
  private readonly carMatrix = new THREE.Matrix4()
  private readonly position = new THREE.Vector3()
  private readonly rotation = new THREE.Quaternion()
  private readonly scale = new THREE.Vector3(1, 1, 1)
  private readonly axis = new THREE.Vector3(0, 1, 0)
  private readonly walkObstacles: WalkObstacle[] = []
  private trafficObstacles: WalkObstacle[] = []
  private readonly kenneySpots: KenneySpot[] = []
  private readonly kenneyMaterials: THREE.Material[] = []
  private disposed = false

  constructor(bounds: OfficeCityBounds) {
    this.root.name = 'office-city'
    this.root.userData.ignorePicking = true
    this.center = new THREE.Vector2(
      (bounds.left + bounds.right) / 2,
      (bounds.back + bounds.front) / 2,
    )
    this.walkObstacles.push({
      x: this.center.x,
      z: this.center.y,
      w: bounds.right - bounds.left,
      d: bounds.front - bounds.back,
    })
    const width = bounds.right - bounds.left,
      depth = bounds.front - bounds.back
    // A continuous foundation extends beyond the complete street grid and far clip.
    const groundMaterial = new THREE.MeshStandardMaterial({
      color: '#bdb9b1',
      roughness: 1,
      metalness: 0,
      envMapIntensity: 0,
    })
    this.materials.set('first-person-ground', groundMaterial)
    this.firstPersonGround = new THREE.Mesh(
      this.geometry('box'),
      groundMaterial,
    )
    this.firstPersonGround.name = 'first-person-ground'
    this.firstPersonGround.position.set(
      this.center.x,
      GROUND - 0.1,
      this.center.y,
    )
    this.firstPersonGround.scale.set(
      width + GROUND_REACH * 2,
      0.1,
      depth + GROUND_REACH * 2,
    )
    this.root.add(this.firstPersonGround)
    this.addSector(
      'back',
      this.center.x,
      bounds.back,
      Math.PI,
      width,
      new THREE.Vector2(0, -1),
      0,
    )
    this.addSector(
      'right',
      bounds.right,
      this.center.y,
      Math.PI / 2,
      depth,
      new THREE.Vector2(1, 0),
      1,
    )
    this.addSector(
      'front',
      this.center.x,
      bounds.front,
      0,
      width,
      new THREE.Vector2(0, 1),
      2,
    )
    this.addSector(
      'left',
      bounds.left,
      this.center.y,
      -Math.PI / 2,
      depth,
      new THREE.Vector2(-1, 0),
      3,
    )
    this.addDistrict(bounds)
    void this.useKenney().catch(() => {
      // 모델을 못 받으면(테스트 환경, 네트워크 오류) 직접 만든 건물을 그대로 둔다.
    })
  }

  private geometry(kind: string) {
    let geometry = this.geometries.get(kind)
    if (!geometry) {
      geometry =
        kind === 'sphere'
          ? new THREE.IcosahedronGeometry(1, 1)
          : kind === 'cylinder'
            ? new THREE.CylinderGeometry(1, 1, 1, 10)
            : new THREE.BoxGeometry(1, 1, 1)
      this.geometries.set(kind, geometry)
    }
    return geometry
  }

  private material(finish: Finish) {
    let material = this.materials.get(finish)
    if (!material) {
      material = new THREE.MeshStandardMaterial({
        // Window colors supply the glazing detail without moving specular highlights.
        color: '#ffffff',
        roughness: finish === 'glass' ? 1 : finish === 'metal' ? 0.9 : 0.85,
        metalness: 0,
        envMapIntensity: 0,
        ...(finish === 'light'
          ? { emissive: '#ffe5b6', emissiveIntensity: 0.32 }
          : {}),
      })
      this.materials.set(finish, material)
    }
    return material
  }

  private transform(
    x: number,
    y: number,
    z: number,
    w: number,
    h: number,
    d: number,
    rz = 0,
    ry = 0,
  ) {
    const q = new THREE.Quaternion().setFromEuler(new THREE.Euler(0, ry, rz))
    return new THREE.Matrix4().compose(
      new THREE.Vector3(x, y, z),
      q,
      new THREE.Vector3(w, h, d),
    )
  }

  private addSector(
    name: string,
    x: number,
    z: number,
    yaw: number,
    span: number,
    outward: THREE.Vector2,
    seed: number,
  ) {
    const root = new THREE.Group()
    root.name = `city-${name}`
    root.userData.citySector = name
    root.userData.outward = { x: outward.x, z: outward.y }
    root.position.set(x, GROUND, z)
    root.rotation.y = yaw
    this.root.add(root)
    const batches = new Map<string, Batch>()
    // 건물 몸체는 따로 묶어 Kenney 모델이 오면 숨긴다.
    let buildingPart = false
    const stamp = (
      kind: string,
      finish: Finish,
      matrix: THREE.Matrix4,
      color: string,
    ) => {
      const key = `${buildingPart ? 'building:' : ''}${kind}/${finish}`
      if (!batches.has(key))
        batches.set(key, {
          geometry: this.geometry(kind),
          material: this.material(finish),
          stamps: [],
        })
      batches.get(key)!.stamps.push({ matrix, color: new THREE.Color(color) })
    }
    const obstacle = (
      u: number,
      y: number,
      v: number,
      w: number,
      h: number,
      d: number,
    ) => {
      if (y + h / 2 < 0.4 || y - h / 2 > 2.1) return
      const c = Math.cos(yaw),
        s = Math.sin(yaw)
      this.walkObstacles.push({
        x: x + c * u + s * v,
        z: z - s * u + c * v,
        w: Math.abs(c) * w + Math.abs(s) * d,
        d: Math.abs(s) * w + Math.abs(c) * d,
      })
    }
    const box = (
      u: number,
      y: number,
      v: number,
      w: number,
      h: number,
      d: number,
      color: string,
      finish: Finish = 'stone',
      ry = 0,
    ) => {
      stamp('box', finish, this.transform(u, y, v, w, h, d, 0, ry), color)
      obstacle(u, y, v, w, h, d)
    }
    const cylinder = (
      u: number,
      y: number,
      v: number,
      radius: number,
      h: number,
      color: string,
      finish: Finish = 'metal',
    ) => {
      stamp(
        'cylinder',
        finish,
        this.transform(u, y, v, radius, h, radius),
        color,
      )
      obstacle(u, y, v, radius * 2, h, radius * 2)
    }
    const sphere = (
      u: number,
      y: number,
      v: number,
      w: number,
      h: number,
      d: number,
      color: string,
    ) => stamp('sphere', 'foliage', this.transform(u, y, v, w, h, d), color)
    const length = Math.max(48, span + 27)
    const junction = span / 2 + 8.4
    const junctionHalfWidth = 3.8
    const clearOfJunction = (u: number, halfWidth = 0) =>
      Math.abs(u - junction) > junctionHalfWidth + halfWidth &&
      Math.abs(u + junction) > junctionHalfWidth + halfWidth
    // Perpendicular sectors share the same asphalt at their four intersections.
    // Every raised edge and painted strip stops before that shared carriageway.
    const stretches: [number, number][] = []
    let start = -length / 2
    for (const center of [-junction, junction]) {
      const end = Math.min(length / 2, center - junctionHalfWidth)
      if (end > start) stretches.push([start, end])
      start = Math.max(start, center + junctionHalfWidth)
    }
    if (start < length / 2) stretches.push([start, length / 2])
    const strip = (
      y: number,
      v: number,
      h: number,
      d: number,
      color: string,
      finish: Finish = 'stone',
    ) => {
      for (const [from, to] of stretches)
        box((from + to) / 2, y, v, to - from, h, d, color, finish)
    }

    // A recessed boulevard keeps every piece outside the playable office footprint.
    box(0, -0.21, 8.4, length, 0.36, 15.2, '#bdb9b1')
    box(0, 0.015, 8.4, length, 0.07, 7.2, '#758283')
    strip(0.075, 3.2, 0.24, 3.2, '#c5c5b5')
    strip(0.075, 13.4, 0.24, 2.8, '#c5c5b5')
    strip(0.21, 4.72, 0.15, 0.18, '#e1dbca')
    strip(0.21, 12.08, 0.15, 0.18, '#e1dbca')
    strip(0.058, 8.4, 0.014, 0.075, '#ddc895', 'paint')
    strip(0.058, 8.58, 0.014, 0.075, '#ddc895', 'paint')
    for (let u = -length / 2 + 0.8; u < length / 2; u += 3) {
      if (!clearOfJunction(u, 0.625)) continue
      box(u, 0.06, 6.55, 1.25, 0.018, 0.075, '#deddd0', 'paint')
      box(u, 0.06, 10.3, 1.25, 0.018, 0.075, '#deddd0', 'paint')
    }
    // Paving joints, drain grates, two zebra crossings, and a protected cycle strip.
    strip(0.063, 5.27, 0.012, 0.46, '#8a8476', 'paint')
    for (let u = -length / 2; u < length / 2; u += 2) {
      if (!clearOfJunction(u, 0.025)) continue
      box(u, 0.203, 3.2, 0.025, 0.008, 2.8, '#b5b2a9')
      box(u, 0.203, 13.4, 0.025, 0.008, 2.5, '#b5b2a9')
    }
    for (const u of [-(junction - 5), junction - 5]) {
      for (let v = 5.3; v < 11.9; v += 0.65)
        box(u, 0.075, v, 2.2, 0.024, 0.32, '#e9e5d7', 'paint')
      if (clearOfJunction(u - 1.45, 0.065))
        box(u - 1.45, 0.075, 6.65, 0.13, 0.024, 2.55, '#e9e5d7', 'paint')
      if (clearOfJunction(u + 1.45, 0.065))
        box(u + 1.45, 0.075, 10.05, 0.13, 0.024, 2.55, '#e9e5d7', 'paint')
      const signalU = u - Math.sign(u) * 1.9
      for (const v of [4.1, 12.7]) {
        cylinder(signalU, 1.25, v, 0.045, 2.3, '#656056')
        box(signalU, 2.2, v, 0.22, 0.55, 0.18, '#344b4e', 'metal')
        box(signalU, 2.28, v - 0.1, 0.1, 0.1, 0.03, '#d2aa6a', 'light')
      }
    }
    for (let u = -length / 2 + 3; u < length / 2; u += 7.5) {
      for (const v of [3.1, 13.5]) {
        const treeU = u + (v > 8 ? 2 : 0)
        if (clearOfJunction(treeU, 1)) {
          box(treeU, 0.23, v, 1.3, 0.15, 1.3, '#a7a398')
          cylinder(treeU, 1.05, v, 0.11, 1.75, '#8f8871')
          sphere(
            treeU,
            2.25,
            v,
            0.82,
            1.04,
            0.78,
            seed % 2 ? '#8f897c' : '#9e998e',
          )
          sphere(treeU + 0.42, 2.2, v + 0.12, 0.56, 0.69, 0.62, '#a7a297')
        }
        if (clearOfJunction(treeU + 2.8, 0.16)) {
          cylinder(treeU + 2.8, 1.8, v, 0.035, 3.3, '#847e70')
          box(treeU + 2.8, 3.43, v - 0.27, 0.09, 0.08, 0.62, '#847e70', 'metal')
          box(treeU + 2.8, 3.38, v - 0.48, 0.2, 0.045, 0.34, '#efe5cb', 'light')
        }
      }
      if (clearOfJunction(u + 1.8, 0.4)) {
        box(u + 1.8, 0.23, 4.45, 0.7, 0.025, 0.18, '#767064', 'metal')
        for (let k = 0; k < 5; k++)
          box(
            u + 1.5 + k * 0.14,
            0.25,
            4.45,
            0.035,
            0.025,
            0.16,
            '#a8a398',
            'metal',
          )
      }
    }
    // A compact transit shelter and benches make the boulevard a lived-in place.
    const shelterU = -Math.min(length * 0.19, junction - 6)
    for (const u of [shelterU - 1.35, shelterU + 1.35])
      cylinder(u, 1.33, 13.8, 0.045, 2.28, '#7d776a')
    box(shelterU, 2.52, 13.8, 3.1, 0.12, 1.55, '#8f897c', 'metal')
    box(shelterU, 1.4, 14.35, 2.8, 1.95, 0.04, '#b0aca3', 'glass')
    box(shelterU, 0.68, 13.95, 2.1, 0.12, 0.48, '#bba88a')
    box(shelterU + 1.3, 1.55, 13.8, 0.09, 1.4, 0.78, '#d9cbae')
    box(shelterU + 1.355, 1.66, 13.8, 0.025, 0.78, 0.59, '#827c6e', 'paint')

    const frontage = 2 * (junction - junctionHalfWidth)
    const buildingCount = Math.max(2, Math.ceil(frontage / 10))
    const pitch = frontage / buildingCount
    for (let i = 0; i < buildingCount; i++) {
      const u = (i - (buildingCount - 1) / 2) * pitch
      const variant = (i + seed * 3) % 7
      const w =
        Math.min(7.1, pitch - 2) * [1, 0.89, 0.94, 1, 0.9, 0.95, 0.87][variant]
      const d = 4.4 + (variant % 3) * 0.75
      // Low masonry blocks, medium offices and an occasional taller neighbor leave sky between them.
      const levels = [8, 12, 9, 14, 10, 11, 8][variant]
      const h = 2.8 + levels * 1.48
      const v = 18.1 + [0, 3.4, 1.2, 5.2, 2.1, 4.1, 0.7][variant]
      buildingPart = true
      this.building(box, u, v, w, d, h, levels, variant)
      buildingPart = false
      this.kenneySpots.push({
        root,
        matrix: new THREE.Matrix4().compose(
          new THREE.Vector3(u, 0, v),
          new THREE.Quaternion().setFromAxisAngle(this.axis, Math.PI),
          new THREE.Vector3(1, 1, 1),
        ),
        w,
        h,
        d,
        lod: 'near',
        seed: variant + seed * 7,
      })
      // Reserve the existing detailed neighbors when the surrounding grid is filled.
      const cosine = Math.cos(yaw),
        sine = Math.sin(yaw)
      const worldX = x + cosine * u + sine * v
      const worldZ = z - sine * u + cosine * v
      const halfX = (Math.abs(cosine) * (w + 1) + Math.abs(sine) * (d + 2)) / 2
      const halfZ = (Math.abs(sine) * (w + 1) + Math.abs(cosine) * (d + 2)) / 2
      this.nearBuildingBounds.push(
        new THREE.Box2(
          new THREE.Vector2(worldX - halfX, worldZ - halfZ),
          new THREE.Vector2(worldX + halfX, worldZ + halfZ),
        ),
      )
    }

    for (const [key, batch] of batches) {
      const mesh = new THREE.InstancedMesh(
        batch.geometry,
        batch.material,
        batch.stamps.length,
      )
      mesh.name = `${root.name}-${key.replace('/', '-')}`
      if (key.startsWith('building:')) mesh.userData.kenneyReplaceable = true
      batch.stamps.forEach((item, i) => {
        mesh.setMatrixAt(i, item.matrix)
        mesh.setColorAt(i, item.color)
      })
      mesh.instanceMatrix.needsUpdate = true
      if (mesh.instanceColor) mesh.instanceColor.needsUpdate = true
      mesh.computeBoundingSphere()
      root.add(mesh)
    }
    const traffic = this.createTraffic(length, seed)
    traffic.root.name = `city-${name}-traffic`
    traffic.root.userData.cityTraffic = true
    root.add(traffic.root)
    this.sectors.push({ root, outward, traffic })
  }

  /** A connected street grid surrounds the original office block in every direction. */
  private addDistrict(bounds: OfficeCityBounds) {
    type Lod = 'near' | 'mid' | 'far'
    interface DistrictBatch extends Batch {
      layer: string
      lod?: Lod
    }
    const leftRoad = bounds.left - 8.4,
      rightRoad = bounds.right + 8.4
    const backRoad = bounds.back - 8.4,
      frontRoad = bounds.front + 8.4
    const axis = (negative: number, positive: number) => [
      ...Array.from(
        { length: DISTRICT_REACH / DISTRICT_SPACING + 1 },
        (_, i) => negative - DISTRICT_REACH + i * DISTRICT_SPACING,
      ),
      ...Array.from(
        { length: DISTRICT_REACH / DISTRICT_SPACING + 1 },
        (_, i) => positive + i * DISTRICT_SPACING,
      ),
    ]
    const xs = axis(leftRoad, rightRoad),
      zs = axis(backRoad, frontRoad)
    this.root.userData.districtGrid = {
      spacing: DISTRICT_SPACING,
      reach: DISTRICT_REACH,
      roadHalfWidth: 3.6,
      leftRoad,
      rightRoad,
      backRoad,
      frontRoad,
      nearDistance: 90,
      midDistance: 300,
    }
    const districtSectors = this.sectors.map((sector) => {
      sector.root.updateMatrix()
      const root = new THREE.Group()
      root.name = `${sector.root.name}-district`
      root.userData.cityDistrict = true
      sector.root.add(root)
      return {
        root,
        inverse: sector.root.matrix.clone().invert(),
        batches: new Map<string, DistrictBatch>(),
      }
    })
    const sectorAt = (x: number, z: number) => {
      const dx = x - this.center.x,
        dz = z - this.center.y
      return Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? 1 : 3) : dz > 0 ? 2 : 0
    }
    const box = (
      sector: number,
      layer: string,
      finish: Finish,
      x: number,
      y: number,
      z: number,
      w: number,
      h: number,
      d: number,
      color: string,
      lod?: Lod,
    ) => {
      if (w <= 0 || h <= 0 || d <= 0) return
      const target = districtSectors[sector],
        key = `${layer}/${finish}/${lod ?? ''}`
      let batch = target.batches.get(key)
      if (!batch) {
        batch = {
          geometry: this.geometry('box'),
          material: this.material(finish),
          stamps: [],
          layer,
          lod,
        }
        target.batches.set(key, batch)
      }
      const matrix = this.transform(x, GROUND + y, z, w, h, d)
      matrix.premultiply(target.inverse)
      batch.stamps.push({ matrix, color: new THREE.Color(color) })
    }
    const oldHorizontal = Math.max(48, bounds.right - bounds.left + 27) / 2
    const oldVertical = Math.max(48, bounds.front - bounds.back + 27) / 2
    const roadSegment = (
      horizontal: boolean,
      fixed: number,
      start: number,
      end: number,
      existing?: [number, number],
    ) => {
      const pieces: [number, number][] = existing
        ? [
            [start, Math.min(end, existing[0])],
            [Math.max(start, existing[1]), end],
          ]
        : [[start, end]]
      for (const [from, to] of pieces) {
        if (to <= from) continue
        const middle = (from + to) / 2
        const x = horizontal ? middle : fixed,
          z = horizontal ? fixed : middle
        const sector = sectorAt(x, z)
        box(
          sector,
          'road',
          'stone',
          x,
          0.015,
          z,
          horizontal ? to - from : 7.2,
          0.07,
          horizontal ? 7.2 : to - from,
          '#758283',
        )
        // Pavements and divider paint stop at the crossing, leaving an open junction.
        const clearStart = Math.max(from, start + 3.8),
          clearEnd = Math.min(to, end - 3.8)
        if (clearEnd <= clearStart) continue
        const clearMiddle = (clearStart + clearEnd) / 2,
          length = clearEnd - clearStart
        for (const side of [-1, 1]) {
          const px = horizontal ? clearMiddle : fixed + side * 5
          const pz = horizontal ? fixed + side * 5 : clearMiddle
          box(
            sector,
            'pavement',
            'stone',
            px,
            0.075,
            pz,
            horizontal ? length : 2.8,
            0.24,
            horizontal ? 2.8 : length,
            '#c5c5b5',
          )
          box(
            sector,
            'curb',
            'stone',
            horizontal ? clearMiddle : fixed + side * 3.68,
            0.21,
            horizontal ? fixed + side * 3.68 : clearMiddle,
            horizontal ? length : 0.16,
            0.15,
            horizontal ? 0.16 : length,
            '#d8d2c1',
          )
          box(
            sector,
            'marking',
            'paint',
            horizontal ? clearMiddle : fixed + side * 0.09,
            0.058,
            horizontal ? fixed + side * 0.09 : clearMiddle,
            horizontal ? length : 0.055,
            0.014,
            horizontal ? 0.055 : length,
            '#ddc895',
          )
        }
      }
    }
    for (const z of zs) {
      const existing: [number, number] | undefined =
        z === backRoad || z === frontRoad
          ? [this.center.x - oldHorizontal, this.center.x + oldHorizontal]
          : undefined
      for (let i = 0; i < xs.length - 1; i++)
        roadSegment(true, z, xs[i], xs[i + 1], existing)
    }
    for (const x of xs) {
      const existing: [number, number] | undefined =
        x === leftRoad || x === rightRoad
          ? [this.center.y - oldVertical, this.center.y + oldVertical]
          : undefined
      for (let i = 0; i < zs.length - 1; i++)
        roadSegment(false, x, zs[i], zs[i + 1], existing)
    }

    const buildingCounts = { near: 0, mid: 0, far: 0 }
    const footprint = new THREE.Box2()
    for (let ix = 0; ix < xs.length - 1; ix++)
      for (let iz = 0; iz < zs.length - 1; iz++) {
        const x0 = xs[ix],
          x1 = xs[ix + 1],
          z0 = zs[iz],
          z1 = zs[iz + 1]
        if (
          x0 < bounds.left &&
          x1 > bounds.right &&
          z0 < bounds.back &&
          z1 > bounds.front
        )
          continue
        const lotW = (x1 - x0 - 14) / 2,
          lotD = (z1 - z0 - 14) / 2
        for (let row = 0; row < 2; row++)
          for (let column = 0; column < 2; column++) {
            const seed =
              ((ix * 73856093) ^
                (iz * 19349663) ^
                ((row * 2 + column + 1) * 83492791)) >>>
              0
            const x = x0 + 7 + (column + 0.5) * lotW
            const z = z0 + 7 + (row + 0.5) * lotD
            const w = Math.min(lotW - 2.2, 7.6 + (seed % 9) * 0.4)
            const d = Math.min(lotD - 2.2, 7.4 + ((seed >>> 4) % 10) * 0.4)
            footprint.min.set(x - w / 2 - 0.3, z - d / 2 - 0.3)
            footprint.max.set(x + w / 2 + 0.3, z + d / 2 + 0.3)
            if (
              this.nearBuildingBounds.some((neighbor) =>
                neighbor.intersectsBox(footprint),
              )
            )
              continue
            const distance = Math.hypot(x - this.center.x, z - this.center.y)
            if (distance < CITY_WALK_RADIUS + 24)
              this.walkObstacles.push({ x, z, w, d })
            const lod: Lod =
              distance < 90 ? 'near' : distance < 300 ? 'mid' : 'far'
            const levels = (
              lod === 'near'
                ? [5, 7, 8, 10, 12, 15]
                : lod === 'mid'
                  ? [7, 10, 13, 18, 24, 32]
                  : [9, 13, 18, 24, 32, 42, 55]
            )[seed % (lod === 'far' ? 7 : 6)]
            const h = 3 + levels * (lod === 'near' ? 1.65 : 2.1)
            const sector = sectorAt(x, z),
              stone = PALETTE[(seed >>> 8) % PALETTE.length]
            const part = (
              finish: Finish,
              px: number,
              py: number,
              pz: number,
              pw: number,
              ph: number,
              pd: number,
              color: string,
            ) =>
              box(
                sector,
                'buildings',
                finish,
                px,
                py,
                pz,
                pw,
                ph,
                pd,
                color,
                lod,
              )
            buildingCounts[lod]++
            // 앞(+Z)을 사무실 쪽 길로 돌린다.
            const facing =
              Math.round(
                Math.atan2(this.center.x - x, this.center.y - z) /
                  (Math.PI / 2),
              ) *
              (Math.PI / 2)
            this.kenneySpots.push({
              root: districtSectors[sector].root,
              matrix: new THREE.Matrix4()
                .compose(
                  new THREE.Vector3(x, GROUND + 0.2, z),
                  new THREE.Quaternion().setFromAxisAngle(this.axis, facing),
                  new THREE.Vector3(1, 1, 1),
                )
                .premultiply(districtSectors[sector].inverse),
              w,
              h,
              d,
              lod,
              seed,
            })
            part('stone', x, h / 2 + 0.2, z, w, h, d, stone)
            // A varied roofline remains legible after distant window detail is removed.
            part(
              'stone',
              x + w * 0.08,
              h + 0.6,
              z + d * 0.08,
              w * 0.72,
              0.8,
              d * 0.68,
              '#bfbbb3',
            )
            if (lod === 'far') {
              if (h > 65)
                part(
                  'glass',
                  x,
                  h * 0.54,
                  z - d / 2 - 0.025,
                  w * 0.48,
                  h * 0.73,
                  0.04,
                  '#91a9af',
                )
              continue
            }
            if (lod === 'mid') {
              const rows = Math.min(6, Math.max(3, Math.floor(h / 8)))
              for (let r = 0; r < rows; r++) {
                const y = 4 + (r * (h - 6)) / rows
                for (const side of [-1, 1]) {
                  part(
                    'glass',
                    x,
                    y,
                    z + side * (d / 2 + 0.025),
                    w * 0.82,
                    0.72,
                    0.04,
                    GLASS[(seed + r) % GLASS.length],
                  )
                  part(
                    'glass',
                    x + side * (w / 2 + 0.025),
                    y,
                    z,
                    0.04,
                    0.72,
                    d * 0.82,
                    GLASS[(seed + r + 2) % GLASS.length],
                  )
                }
              }
              continue
            }
            part('stone', x, 1.15, z, w + 0.2, 2.3, d + 0.2, '#c8c3b5')
            part('metal', x, h + 1.1, z, w * 0.28, 0.35, d * 0.3, '#989387')
            for (let y = 3.4, r = 0; y < h - 0.7; y += 2.1, r++) {
              for (let column = 0; column < 3; column++)
                for (const side of [-1, 1]) {
                  const glass = GLASS[(seed + column + r) % GLASS.length]
                  part(
                    'glass',
                    x + ((column - 1) * w) / 3,
                    y,
                    z + side * (d / 2 + 0.025),
                    w * 0.21,
                    1.02,
                    0.04,
                    glass,
                  )
                  part(
                    'glass',
                    x + side * (w / 2 + 0.025),
                    y,
                    z + ((column - 1) * d) / 3,
                    0.04,
                    1.02,
                    d * 0.21,
                    glass,
                  )
                }
            }
          }
      }
    let drawCalls = 0,
      instances = 0,
      triangles = 0
    for (const target of districtSectors)
      for (const [key, batch] of target.batches) {
        const mesh = new THREE.InstancedMesh(
          batch.geometry,
          batch.material,
          batch.stamps.length,
        )
        mesh.name = `${target.root.name}-${key.replaceAll('/', '-')}`
        if (batch.layer === 'road') mesh.userData.citySurface = 'road'
        if (batch.layer === 'buildings') {
          mesh.userData.kenneyReplaceable = true
          mesh.userData.cityBuildings = true
          mesh.userData.cityLod = batch.lod
        }
        batch.stamps.forEach((stamp, i) => {
          mesh.setMatrixAt(i, stamp.matrix)
          mesh.setColorAt(i, stamp.color)
        })
        mesh.instanceMatrix.needsUpdate = true
        if (mesh.instanceColor) mesh.instanceColor.needsUpdate = true
        mesh.computeBoundingSphere()
        target.root.add(mesh)
        drawCalls++
        instances += mesh.count
        triangles +=
          (mesh.count *
            (mesh.geometry.index?.count ??
              mesh.geometry.attributes.position.count)) /
          3
      }
    this.root.userData.districtStats = {
      drawCalls,
      instances,
      triangles,
      buildings: buildingCounts,
    }
  }

  private building(
    box: (
      u: number,
      y: number,
      v: number,
      w: number,
      h: number,
      d: number,
      color: string,
      finish?: Finish,
      ry?: number,
    ) => void,
    u: number,
    v: number,
    w: number,
    d: number,
    h: number,
    levels: number,
    variant: number,
  ) {
    const stone = PALETTE[variant % PALETTE.length]
    const style = variant % 3
    const windowWidth = [0.73, 0.61, 0.89][style]
    const windowHeight = [1.04, 0.94, 1.14][style]
    const front = v - d / 2
    box(u, h / 2, v, w, h, d, stone)
    box(u, 1.15, v - 0.08, w + 0.3, 2.3, d + 0.3, '#c9c4b6')
    box(u, 1.18, front - 0.19, w - 0.6, 1.72, 0.08, '#728d93', 'glass')
    box(u, 2.25, front - 0.35, w + 0.46, 0.16, 0.82, '#b5b1a8', 'metal')
    // Shop entrances and warm interiors sit behind the podium's glazing.
    for (let col = 0; col < 3; col++) {
      const px = u + (col - 1) * w * 0.28
      box(px, 1.1, front - 0.26, 0.075, 1.95, 0.12, '#d6d2bf', 'metal')
      box(
        px + w * 0.12,
        1.84,
        front - 0.25,
        w * 0.19,
        0.18,
        0.04,
        col === variant % 3 ? '#e4c58e' : '#999488',
        col === variant % 3 ? 'light' : 'glass',
      )
    }
    box(
      u - w * 0.21,
      2.68,
      front - 0.055,
      w * 0.38,
      0.34,
      0.07,
      ['#878173', '#b49b75', '#6c8699'][variant % 3],
      'paint',
    )
    const cols = Math.max(3, Math.floor(w / (style === 1 ? 1.3 : 1.1)))
    const rows = levels
    for (let row = 0; row < rows; row++) {
      const y = 3.25 + row * 1.48
      if (y + 0.65 > h) continue
      for (let col = 0; col < cols; col++) {
        const px = u - w / 2 + ((col + 0.5) * w) / cols
        const warm = (row * 5 + col * 3 + variant) % 19 === 0
        const color = warm
          ? '#c7b58d'
          : GLASS[(row + col * 2 + variant) % GLASS.length]
        box(
          px,
          y,
          front - 0.024,
          (w / cols) * windowWidth,
          windowHeight,
          0.055,
          color,
          warm ? 'light' : 'glass',
        )
      }
      for (const side of [-1, 1]) {
        const sideCols = Math.max(3, Math.floor(d / 1.2))
        for (let col = 0; col < sideCols; col++) {
          const pz = v - d / 2 + ((col + 0.5) * d) / sideCols
          box(
            u + side * (w / 2 + 0.026),
            y,
            pz,
            0.055,
            windowHeight,
            (d / sideCols) * windowWidth,
            GLASS[(row + col + variant + 1) % GLASS.length],
            'glass',
          )
        }
      }
      if (style === 2 || row % (style === 1 ? 4 : 3) === 2)
        box(u, y + 0.68, v, w + 0.1, 0.075, d + 0.1, '#cfcdc7', 'metal')
    }
    if (style === 2)
      for (const side of [-1, 1])
        box(
          u + side * w * 0.31,
          (h + 2.7) / 2,
          front - 0.09,
          0.07,
          h - 2.7,
          0.16,
          '#d7d5d1',
          'metal',
        )
    // A recessed crown, parapet, plant room and HVAC make the roofs worth looking at.
    box(u, h + 0.055, v, w + 0.14, 0.13, d + 0.14, '#d7d5d0')
    box(u, h + 0.14, v, w - 0.35, 0.1, d - 0.35, '#989386')
    for (const side of [-1, 1]) {
      box(u + side * (w / 2 - 0.03), h + 0.26, v, 0.12, 0.43, d, '#cbc8c1')
      box(u, h + 0.26, v + side * (d / 2 - 0.03), w, 0.43, 0.12, '#cbc8c1')
    }
    box(u + w * 0.12, h + 0.6, v + d * 0.1, w * 0.36, 1.02, d * 0.35, '#bdb9b1')
    for (let k = 0; k < 2; k++) {
      box(
        u - w * 0.26,
        h + 0.38,
        v - 0.65 + k * 1.25,
        0.94,
        0.5,
        0.9,
        '#aaa59b',
        'metal',
      )
      for (let louver = 0; louver < 4; louver++)
        box(
          u - w * 0.26,
          h + 0.65,
          v - 0.96 + k * 1.25 + louver * 0.19,
          0.76,
          0.025,
          0.065,
          '#837d70',
          'metal',
        )
    }
    if (variant % 3 === 0) {
      box(
        u + w * 0.26,
        h + 0.75,
        v - d * 0.25,
        0.065,
        1.3,
        0.065,
        '#9c968a',
        'metal',
      )
      box(
        u + w * 0.26,
        h + 1.26,
        v - d * 0.25,
        0.74,
        0.04,
        0.06,
        '#9c968a',
        'metal',
      )
    }
  }

  private createTraffic(length: number, seed: number): Traffic {
    const root = new THREE.Group()
    const cars: Car[] = []
    // Equal spacing in each lane gives continuous motion without overlaps or overtaking.
    for (const direction of [-1, 1])
      for (let i = 0; i < 3; i++)
        cars.push({
          phase: (i * length) / 3 + seed * 3.7 + (direction < 0 ? 4.5 : 0),
          speed: direction > 0 ? 2.05 + seed * 0.08 : 1.8 + seed * 0.08,
          direction,
          lane: direction > 0 ? 7.15 : 9.65,
          color:
            CAR_COLORS[
              (i + seed * 2 + (direction < 0 ? 3 : 0)) % CAR_COLORS.length
            ],
        })
    const body = (
      x: number,
      y: number,
      z: number,
      w: number,
      h: number,
      d: number,
      color: string,
      colored = false,
    ): CarPart => ({
      matrix: this.transform(x, y, z, w, h, d),
      color,
      body: colored,
    })
    const definitions: { kind: string; finish: Finish; parts: CarPart[] }[] = [
      {
        kind: 'box',
        finish: 'paint',
        parts: [
          body(0, 0.43, 0, 1.88, 0.4, 0.82, '#ffffff', true),
          body(-0.13, 0.77, 0, 0.86, 0.11, 0.7, '#ffffff', true),
        ],
      },
      {
        kind: 'box',
        finish: 'glass',
        parts: [body(-0.14, 0.64, 0, 1.02, 0.29, 0.67, '#354f5b')],
      },
      {
        kind: 'box',
        finish: 'metal',
        parts: [
          body(0.92, 0.31, 0, 0.05, 0.12, 0.72, '#b6b2a9'),
          body(-0.92, 0.31, 0, 0.05, 0.1, 0.72, '#b6b2a9'),
          body(0.955, 0.46, 0, 0.035, 0.12, 0.3, '#45575a'),
        ],
      },
      {
        kind: 'box',
        finish: 'light',
        parts: [
          body(0.96, 0.49, -0.29, 0.03, 0.11, 0.18, '#fff0c5'),
          body(0.96, 0.49, 0.29, 0.03, 0.11, 0.18, '#fff0c5'),
          body(-0.96, 0.5, -0.29, 0.025, 0.1, 0.15, '#bd655a'),
          body(-0.96, 0.5, 0.29, 0.025, 0.1, 0.15, '#bd655a'),
        ],
      },
    ]
    const wheelParts: CarPart[] = [],
      hubParts: CarPart[] = []
    for (const x of [-0.58, 0.58])
      for (const z of [-0.43, 0.43]) {
        const q = new THREE.Quaternion().setFromAxisAngle(
          new THREE.Vector3(1, 0, 0),
          Math.PI / 2,
        )
        wheelParts.push({
          matrix: new THREE.Matrix4().compose(
            new THREE.Vector3(x, 0.25, z),
            q,
            new THREE.Vector3(0.205, 0.11, 0.205),
          ),
          color: '#34444a',
        })
        hubParts.push({
          matrix: new THREE.Matrix4().compose(
            new THREE.Vector3(x, 0.25, z + Math.sign(z) * 0.06),
            q,
            new THREE.Vector3(0.105, 0.015, 0.105),
          ),
          color: '#cbc9c3',
        })
      }
    definitions.push(
      { kind: 'cylinder', finish: 'stone', parts: wheelParts },
      { kind: 'cylinder', finish: 'metal', parts: hubParts },
    )
    const batches = definitions.map((definition, index) => {
      const mesh = new THREE.InstancedMesh(
        this.geometry(definition.kind),
        this.material(definition.finish),
        cars.length * definition.parts.length,
      )
      mesh.name = `traffic-${index}`
      mesh.instanceMatrix.setUsage(THREE.DynamicDrawUsage)
      // Cars travel beyond their initial bounds, so a stale instance bounding sphere must not cull them.
      mesh.frustumCulled = false
      cars.forEach((car, i) =>
        definition.parts.forEach((part, j) =>
          mesh.setColorAt(
            i * definition.parts.length + j,
            new THREE.Color(part.body ? car.color : part.color),
          ),
        ),
      )
      root.add(mesh)
      return { mesh, parts: definition.parts }
    })
    return { root, length, cars, batches }
  }

  canWalk(x: number, z: number, radius = 0.28): boolean {
    if (
      !Number.isFinite(x + z) ||
      Math.hypot(x - this.center.x, z - this.center.y) >
        CITY_WALK_RADIUS - radius
    )
      return false
    const intersects = (box: WalkObstacle) => {
      const dx = Math.max(Math.abs(x - box.x) - box.w / 2, 0),
        dz = Math.max(Math.abs(z - box.z) - box.d / 2, 0)
      return dx * dx + dz * dz < radius * radius
    }
    return (
      !this.walkObstacles.some(intersects) &&
      !this.trafficObstacles.some(intersects)
    )
  }

  update(
    timeSeconds: number,
    cameraPosition: THREE.Vector3,
    firstPerson: boolean,
    pedestrian?: { x: number; z: number },
  ) {
    const dx = cameraPosition.x - this.center.x,
      dz = cameraPosition.z - this.center.y
    const distance = Math.hypot(dx, dz) || 1
    this.trafficObstacles = []
    this.root.updateMatrixWorld(true)
    for (const sector of this.sectors) {
      // The two distant city blocks remain in a dollhouse view; foreground blocks open with the room.
      sector.root.visible =
        firstPerson ||
        (dx * sector.outward.x + dz * sector.outward.y) / distance < 0.14
      if (!sector.root.visible) continue
      const traffic = sector.traffic
      let positions = traffic.cars.map(
        (car) =>
          positiveMod(
            car.phase + timeSeconds * car.speed * car.direction,
            traffic.length,
          ) -
          traffic.length / 2,
      )
      if (pedestrian) {
        const local = sector.root.worldToLocal(
          new THREE.Vector3(pedestrian.x, GROUND + 1, pedestrian.z),
        )
        const prior = traffic.walkingPositions ?? positions,
          dt =
            traffic.walkingTime === undefined
              ? 0
              : Math.min(0.1, Math.max(0, timeSeconds - traffic.walkingTime))
        positions = traffic.cars.map((car, i) => {
          const ahead = positiveMod(
            (local.x - prior[i]) * car.direction,
            traffic.length,
          )
          const yields = Math.abs(local.z - car.lane) < 0.9 && ahead < 2.4
          const queued = traffic.cars.some(
            (other, j) =>
              i !== j &&
              other.lane === car.lane &&
              positiveMod(
                (prior[j] - prior[i]) * car.direction,
                traffic.length,
              ) < 2.5,
          )
          return (
            positiveMod(
              prior[i] +
                traffic.length / 2 +
                (yields || queued ? 0 : dt * car.speed * car.direction),
              traffic.length,
            ) -
            traffic.length / 2
          )
        })
        traffic.walkingPositions = positions
        traffic.walkingTime = timeSeconds
      } else {
        traffic.walkingPositions = positions
        traffic.walkingTime = timeSeconds
      }
      traffic.cars.forEach((car, i) => {
        const point = sector.root.localToWorld(
          new THREE.Vector3(positions[i], 0, car.lane),
        )
        const horizontal = Math.abs(sector.outward.y) > 0.5
        this.trafficObstacles.push({
          x: point.x,
          z: point.z,
          w: horizontal ? 2.05 : 1.05,
          d: horizontal ? 1.05 : 2.05,
        })
      })
      for (const batch of sector.traffic.batches) {
        sector.traffic.cars.forEach((car, i) => {
          const u = positions[i]
          this.position.set(u, 0, car.lane)
          this.rotation.setFromAxisAngle(
            this.axis,
            car.direction < 0 ? Math.PI : 0,
          )
          this.carMatrix.compose(this.position, this.rotation, this.scale)
          batch.parts.forEach((part, j) => {
            this.matrix.multiplyMatrices(this.carMatrix, part.matrix)
            batch.mesh.setMatrixAt(i * batch.parts.length + j, this.matrix)
          })
        })
        batch.mesh.instanceMatrix.needsUpdate = true
      }
    }
  }

  // Kenney City Kit 건물을 받아 같은 자리에 인스턴스로 깔고, 직접 만든 건물 묶음을 숨긴다.
  private async useKenney() {
    const load = (names: string[]) =>
      Promise.all(
        names.map((name) => loadKenney(`/models/kenney/city/${name}.glb`)),
      )
    const [near, far] = await Promise.all([load(KENNEY_NEAR), load(KENNEY_FAR)])
    if (this.disposed) return
    const prepare = (model: KenneyModel, name: string) => {
      model.scene.updateMatrixWorld(true)
      const parts: THREE.BufferGeometry[] = []
      const materials: THREE.Material[] = []
      model.scene.traverse((object) => {
        const mesh = object as THREE.Mesh
        if (!mesh.isMesh) return
        parts.push(mesh.geometry.clone().applyMatrix4(mesh.matrixWorld))
        materials.push(
          Array.isArray(mesh.material) ? mesh.material[0] : mesh.material,
        )
      })
      const geometry = mergeGeometries(parts, true)
      parts.forEach((part) => part.dispose())
      if (!geometry) return undefined
      // 재질은 복제해 쓴다. 스텐실 설정이 다른 화면과 나누는 원본 재질에 번지지 않게 한다.
      const own = materials.map((material) => {
        const copy = material.clone()
        this.kenneyMaterials.push(copy)
        return copy
      })
      // 바닥 가운데를 원점으로 옮긴다.
      geometry.computeBoundingBox()
      const box = geometry.boundingBox!
      const center = box.getCenter(new THREE.Vector3())
      geometry.translate(-center.x, -box.min.y, -center.z)
      this.geometries.set(`kenney/${name}`, geometry)
      return {
        geometry,
        material: own,
        size: box.getSize(new THREE.Vector3()),
      }
    }
    const nearSet = near
      .map((model, i) => prepare(model, KENNEY_NEAR[i]))
      .filter((item) => !!item)
    const farSet = far
      .map((model, i) => prepare(model, KENNEY_FAR[i]))
      .filter((item) => !!item)
    if (!nearSet.length || !farSet.length) return
    type Prepared = (typeof nearSet)[number]
    const groups = new Map<THREE.Group, Map<Prepared, THREE.Matrix4[]>>()
    const scale = new THREE.Matrix4()
    for (const spot of this.kenneySpots) {
      let pick: Prepared
      if (spot.lod === 'near') {
        // 가까운 건물: 높이/너비 비율이 가장 비슷한 모델을 고르고 비율을 지킨 채 키운다.
        const aspect = spot.h / Math.max(spot.w, spot.d)
        const ranked = nearSet.toSorted(
          (a, b) =>
            Math.abs(a.size.y / a.size.x - aspect) -
            Math.abs(b.size.y / b.size.x - aspect),
        )
        pick = ranked[spot.seed % Math.min(3, ranked.length)]
        const s = Math.min(spot.w / pick.size.x, spot.d / pick.size.z)
        scale.makeScale(s, s, s)
      } else {
        pick = farSet[spot.seed % farSet.length]
        scale.makeScale(
          spot.w / pick.size.x,
          spot.h / pick.size.y,
          spot.d / pick.size.z,
        )
      }
      const byModel = groups.get(spot.root) ?? new Map()
      groups.set(spot.root, byModel)
      const list = byModel.get(pick) ?? []
      byModel.set(pick, list)
      list.push(spot.matrix.clone().multiply(scale))
    }
    for (const [root, byModel] of groups)
      for (const [model, matrices] of byModel) {
        const mesh = new THREE.InstancedMesh(
          model.geometry,
          model.material,
          matrices.length,
        )
        mesh.name = `${root.name}-kenney`
        matrices.forEach((matrix, i) => mesh.setMatrixAt(i, matrix))
        mesh.instanceMatrix.needsUpdate = true
        mesh.computeBoundingSphere()
        root.add(mesh)
      }
    this.root.traverse((object) => {
      if (object.userData.kenneyReplaceable) object.visible = false
    })
    // 창밖 스텐실이 새 재질을 다시 모으게 한다(3인칭에서는 유리창 안으로만 보여야 한다).
    this.root.userData.cityVersion =
      ((this.root.userData.cityVersion as number | undefined) ?? 0) + 1
  }

  dispose() {
    this.disposed = true
    this.root.traverse((object) => {
      if (object instanceof THREE.InstancedMesh) object.dispose()
    })
    this.geometries.forEach((geometry) => geometry.dispose())
    this.materials.forEach((material) => material.dispose())
    this.kenneyMaterials.forEach((material) => material.dispose())
    this.kenneyMaterials.length = 0
    this.geometries.clear()
    this.materials.clear()
    this.sectors.length = 0
    this.walkObstacles.length = 0
    this.trafficObstacles = []
    this.root.clear()
  }
}
