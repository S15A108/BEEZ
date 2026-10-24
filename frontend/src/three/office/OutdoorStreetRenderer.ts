import * as THREE from 'three'
import { OfficeCity, CITY_GROUND_Y, type OfficeCityBounds } from './office-city'
import { FirstPersonLook, firstPersonFrame } from './first-person'
import type { Point } from './motion'

export const streetSpawn = (bounds: OfficeCityBounds): Point => ({
  x: (bounds.left + bounds.right) * 16,
  y: (bounds.front + 3.2) * 32,
})
export const streetDoor = (bounds: OfficeCityBounds): Point => ({
  x: (bounds.left + bounds.right) * 16,
  y: (bounds.front + 0.2) * 32,
})

/** The same district seen through the office windows, viewed from street level. */
export class OutdoorStreetRenderer {
  private readonly renderer: THREE.WebGLRenderer
  private readonly ownsRenderer: boolean
  private readonly scene = new THREE.Scene()
  private readonly camera = new THREE.PerspectiveCamera(65, 1, 0.08, 900)
  readonly city: OfficeCity
  readonly look = new FirstPersonLook()
  private readonly geometry = new THREE.BoxGeometry(1, 1, 1)
  private readonly materials = new Map<string, THREE.MeshStandardMaterial>()
  private readonly textures: THREE.Texture[] = []
  private readonly ray = new THREE.Raycaster()
  private readonly door: THREE.Mesh
  private width = 1
  private height = 1

  private readonly bounds: OfficeCityBounds
  constructor(
    host: HTMLElement,
    bounds: OfficeCityBounds,
    name: string,
    externalRenderer?: THREE.WebGLRenderer,
  ) {
    this.bounds = bounds
    this.ownsRenderer = !externalRenderer
    this.renderer =
      externalRenderer ??
      new THREE.WebGLRenderer({
        antialias: true,
        powerPreference: 'high-performance',
      })
    if (this.ownsRenderer)
      this.renderer.setPixelRatio(Math.min(devicePixelRatio || 1, 1.75))
    this.renderer.outputColorSpace = THREE.SRGBColorSpace
    this.renderer.toneMapping = THREE.ACESFilmicToneMapping
    this.renderer.toneMappingExposure = 1
    if (this.ownsRenderer) host.appendChild(this.renderer.domElement)
    this.scene.background = new THREE.Color('#8fcff5')
    this.scene.fog = new THREE.Fog('#8fcff5', 650, 850)
    this.scene.add(new THREE.HemisphereLight('#e0f4ff', '#a09b90', 1.75))
    const sun = new THREE.DirectionalLight('#fff2d6', 2.1)
    sun.position.set(-35, 70, 25)
    this.scene.add(sun)
    this.city = new OfficeCity(bounds)
    this.city.root.position.y = -CITY_GROUND_Y
    this.scene.add(this.city.root)
    const cx = (bounds.left + bounds.right) / 2,
      cz = (bounds.back + bounds.front) / 2,
      w = bounds.right - bounds.left,
      d = bounds.front - bounds.back
    this.box(cx, 9.3, cz, w, 18.6, d, '#c5c9c3')
    this.box(cx, 1.45, cz, w + 0.12, 2.9, d + 0.12, '#dad6c5')
    this.box(cx, 18.65, cz, w + 0.7, 0.3, d + 0.7, '#8b9997')
    for (let y = 4; y < 18; y += 2.8) {
      for (let x = bounds.left + 1.2; x < bounds.right - 1; x += 2.6)
        for (const z of [bounds.back - 0.06, bounds.front + 0.06])
          this.box(x, y, z, 1.65, 1.5, 0.09, '#7999a2')
      for (let z = bounds.back + 1.2; z < bounds.front - 1; z += 2.6)
        for (const x of [bounds.left - 0.06, bounds.right + 0.06])
          this.box(x, y, z, 0.09, 1.5, 1.65, '#7999a2')
    }
    this.box(cx, 1.65, bounds.front + 0.14, 2.6, 3.3, 0.14, '#40595b')
    this.door = this.box(
      cx,
      1.6,
      bounds.front + 0.24,
      2.1,
      3.1,
      0.08,
      '#819e9e',
    )
    this.box(cx, 1.6, bounds.front + 0.3, 0.035, 3.1, 0.035, '#f1c75c')
    for (const side of [-1, 1]) {
      this.box(
        cx + side * 1.15,
        1.65,
        bounds.front + 0.24,
        0.1,
        3.3,
        0.13,
        '#f1c75c',
      )
      this.box(
        cx + side * 0.16,
        1.45,
        bounds.front + 0.32,
        0.035,
        0.45,
        0.05,
        '#f7e5b5',
      )
    }
    this.box(cx, 3.45, bounds.front + 0.9, 4.2, 0.18, 2.1, '#b7a47b')
    const canvas = document.createElement('canvas')
    canvas.width = 1024
    canvas.height = 128
    const ctx = canvas.getContext('2d')!
    ctx.fillStyle = '#304b49'
    ctx.fillRect(0, 0, 1024, 128)
    ctx.fillStyle = '#fff5ce'
    ctx.textAlign = 'center'
    ctx.font = '600 43px "Malgun Gothic",sans-serif'
    let title = `BEEZ · ${name}`
    while (ctx.measureText(title).width > 960) title = title.slice(0, -2) + '…'
    ctx.fillText(title, 512, 80)
    const texture = new THREE.CanvasTexture(canvas)
    texture.colorSpace = THREE.SRGBColorSpace
    this.textures.push(texture)
    const material = new THREE.MeshBasicMaterial({ map: texture })
    const sign = new THREE.Mesh(
      new THREE.PlaneGeometry(Math.min(8, w - 2), 1),
      material,
    )
    sign.position.set(cx, 4.6, bounds.front + 0.12)
    this.scene.add(sign)
    this.look.face(0)
  }
  private box(
    x: number,
    y: number,
    z: number,
    w: number,
    h: number,
    d: number,
    color: string,
  ) {
    let material = this.materials.get(color)
    if (!material) {
      material = new THREE.MeshStandardMaterial({
        color,
        roughness: 0.9,
        metalness: 0,
      })
      this.materials.set(color, material)
    }
    const mesh = new THREE.Mesh(this.geometry, material)
    mesh.position.set(x, y, z)
    mesh.scale.set(w, h, d)
    this.scene.add(mesh)
    return mesh
  }
  setSize(width: number, height: number) {
    this.width = Math.max(1, width)
    this.height = Math.max(1, height)
    if (this.ownsRenderer) this.renderer.setSize(this.width, this.height)
    this.camera.aspect = this.width / this.height
    this.camera.updateProjectionMatrix()
  }
  canWalk = (x: number, y: number) => this.city.canWalk(x / 32, y / 32)
  nearDoor(point: Point) {
    const door = streetDoor(this.bounds)
    return Math.hypot(point.x - door.x, point.y - door.y) < 3.6 * 32
  }
  aimedDoor(point: Point, x = this.width / 2, y = this.height / 2) {
    if (!this.nearDoor(point) || point.y < this.bounds.front * 32) return false
    this.ray.setFromCamera(
      new THREE.Vector2((x / this.width) * 2 - 1, 1 - (y / this.height) * 2),
      this.camera,
    )
    return this.ray.intersectObject(this.door).length > 0
  }
  render(point: Point, time: number, seconds: number) {
    this.look.step(seconds)
    const frame = firstPersonFrame(
      point.x,
      point.y,
      this.look.yaw,
      this.look.pitch,
    )
    this.camera.position.set(frame.eye.x, frame.eye.y, frame.eye.z)
    this.camera.lookAt(
      frame.eye.x + frame.forward.x,
      frame.eye.y + frame.forward.y,
      frame.eye.z + frame.forward.z,
    )
    this.camera.updateMatrixWorld(true)
    this.city.update(time / 1000, this.camera.position, true, {
      x: point.x / 32,
      z: point.y / 32,
    })
    this.renderer.render(this.scene, this.camera)
  }
  dispose() {
    this.city.root.removeFromParent()
    this.city.dispose()
    this.scene.traverse((object) => {
      if (object instanceof THREE.Mesh && object.geometry !== this.geometry) {
        object.geometry.dispose()
        const materials = Array.isArray(object.material)
          ? object.material
          : [object.material]
        materials.forEach((item) => item.dispose())
      }
    })
    this.geometry.dispose()
    this.materials.forEach((item) => item.dispose())
    this.textures.forEach((item) => item.dispose())
    if (this.ownsRenderer) {
      this.renderer.dispose()
      this.renderer.forceContextLoss()
      this.renderer.domElement.remove()
    }
    this.scene.clear()
  }
}
