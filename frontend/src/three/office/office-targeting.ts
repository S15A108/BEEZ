import * as THREE from 'three'
import { FullScreenQuad } from 'three/addons/postprocessing/Pass.js'
import type { Furniture } from './floors'
import type { OfficeWorkHit } from './office-work'
import type { OfficeWorkSurface } from './office-work-surface'

export function officeFurnitureLabel(furniture: Furniture): string {
  const labels: Record<Furniture['kind'], string> = {
    table: '테이블',
    chair: '의자',
    desk: '작업 책상',
    plant: '화분',
    tree: '나무',
    sofa: '소파',
    shelf: '책장',
    counter: '카운터',
    execdesk: '팀장 책상',
    rug: '러그',
    cooler: '정수기',
    printer: '프린터',
    reception: '안내 데스크',
    rack: '서버 랙',
    gate: '게이트',
    designboard: '프로젝터 스크린',
    projector: '프로젝터 스크린',
    coffee: '커피 머신',
    bench: '벤치',
    console: '관제 데스크',
    safe: '금고',
    requestdesk: '요청 데스크',
    locker: '보관함',
    beanbag: '빈백',
    vending: '자판기',
    arcade: '게임기',
    aquarium: '수족관',
    lamp: '조명',
    trophy: '트로피',
    'glass-partition': '유리 파티션',
  }
  return labels[furniture.kind]
}

/** Raycaster includes hidden descendants, so check the entire visibility chain. */
export function isOfficePickVisible(object: THREE.Object3D): boolean {
  for (let node: THREE.Object3D | null = object; node; node = node.parent) {
    if (!node.visible || node.userData.ignorePicking) return false
  }
  return true
}

/** Return the front visible surface, including walls that block a selectable object. */
export function pickOfficeObject(
  raycaster: THREE.Raycaster,
  roots: THREE.Object3D[],
) {
  return raycaster
    .intersectObjects(roots, true)
    .find(
      (hit) =>
        hit.object instanceof THREE.Mesh && isOfficePickVisible(hit.object),
    )
}

/** Only an actionable front surface can be highlighted; decorations still occlude actions behind them. */
export function pickOfficeInteraction(
  raycaster: THREE.Raycaster,
  roots: THREE.Object3D[],
): {
  hit: THREE.Intersection
  entityId?: string
  workHit?: OfficeWorkHit
  outdoorExit?: boolean
} | null {
  const hit = pickOfficeObject(raycaster, roots)
  if (!hit) return null
  for (let node: THREE.Object3D | null = hit.object; node; node = node.parent) {
    if (typeof node.userData.entityId === 'string')
      return { hit, entityId: node.userData.entityId }
    if (node.userData.outdoorExit) return { hit, outdoorExit: true }
  }
  const surface = hit.object.userData.workSurface as
    OfficeWorkSurface | undefined
  const workHit = surface?.hit(hit.uv?.x, hit.uv?.y)
  return workHit ? { hit, workHit } : null
}

export function setOfficeViewVisibility(
  root: THREE.Object3D,
  firstPerson: boolean,
) {
  root.traverse((object) => {
    if (object.userData.firstPersonOnly) object.visible = firstPerson
  })
}

/** Merge the selected meshes into one visible silhouette, then draw only its outer edge. */
export class OfficeAimOutline {
  readonly maskScene = new THREE.Scene()
  private readonly proxies = new Map<THREE.Mesh, THREE.Mesh>()
  private readonly mask = new THREE.WebGLRenderTarget(1, 1, { samples: 4 })
  private readonly depthMaterial = new THREE.MeshBasicMaterial({
    colorWrite: false,
    side: THREE.DoubleSide,
  })
  private readonly maskMaterial = new THREE.MeshBasicMaterial({
    color: '#ffffff',
    side: THREE.DoubleSide,
    toneMapped: false,
    depthWrite: false,
  })
  private readonly edgeMaterial = new THREE.ShaderMaterial({
    uniforms: {
      maskTexture: { value: this.mask.texture },
      texelSize: { value: new THREE.Vector2(1, 1) },
      edgeColor: { value: new THREE.Color('#ffcf32') },
    },
    vertexShader: `varying vec2 vUv;
      void main() { vUv = uv; gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0); }`,
    fragmentShader: `varying vec2 vUv;
      uniform sampler2D maskTexture;
      uniform vec2 texelSize;
      uniform vec3 edgeColor;
      void main() {
        float center = texture2D(maskTexture, vUv).r;
        float expanded = center;
        for (int y = -3; y <= 3; y++) {
          for (int x = -3; x <= 3; x++) {
            vec2 offset = vec2(float(x), float(y));
            if (dot(offset, offset) <= 9.0)
              expanded = max(expanded, texture2D(maskTexture, vUv + offset * texelSize).r);
          }
        }
        float edge = clamp(expanded - center, 0.0, 1.0);
        if (edge < 0.01) discard;
        gl_FragColor = vec4(edgeColor, edge);
        #include <colorspace_fragment>
      }`,
    transparent: true,
    depthTest: false,
    depthWrite: false,
    toneMapped: false,
  })
  private readonly quad = new FullScreenQuad(this.edgeMaterial)
  private readonly clearColor = new THREE.Color()

  constructor() {
    this.maskScene.name = 'office-aim-silhouette'
    this.mask.texture.generateMipmaps = false
  }

  setSize(width: number, height: number) {
    const w = Math.max(1, Math.round(width)),
      h = Math.max(1, Math.round(height))
    this.mask.setSize(w, h)
    this.edgeMaterial.uniforms.texelSize.value.set(1 / w, 1 / h)
  }

  update(target?: THREE.Object3D) {
    const present = new Set<THREE.Mesh>()
    if (target && isOfficePickVisible(target))
      target.traverseVisible((object) => {
        if (!(object instanceof THREE.Mesh) || !isOfficePickVisible(object))
          return
        present.add(object)
        let proxy = this.proxies.get(object)
        if (!proxy) {
          proxy = new THREE.Mesh(object.geometry, this.maskMaterial)
          proxy.matrixAutoUpdate = false
          proxy.matrixWorldAutoUpdate = false
          this.proxies.set(object, proxy)
          this.maskScene.add(proxy)
        }
        proxy.matrix.copy(object.matrixWorld)
        proxy.matrixWorld.copy(object.matrixWorld)
      })
    for (const [source, proxy] of this.proxies)
      if (!present.has(source)) {
        proxy.removeFromParent()
        this.proxies.delete(source)
      }
  }

  render(
    renderer: THREE.WebGLRenderer,
    scene: THREE.Scene,
    camera: THREE.Camera,
  ) {
    if (!this.proxies.size) return
    const previous = {
      target: renderer.getRenderTarget(),
      autoClear: renderer.autoClear,
      alpha: renderer.getClearAlpha(),
      shadowAutoUpdate: renderer.shadowMap.autoUpdate,
      background: scene.background,
      material: scene.overrideMaterial,
    }
    renderer.getClearColor(this.clearColor)
    const hidden: THREE.Object3D[] = []
    scene.traverseVisible((object) => {
      if (!(object instanceof THREE.Mesh)) return
      const materials = Array.isArray(object.material)
        ? object.material
        : [object.material]
      // Glass, projector light and contact shadows do not hide the visible silhouette.
      if (
        object.userData.ignorePicking ||
        materials.every((material) => !material.depthWrite)
      )
        hidden.push(object)
    })
    try {
      renderer.autoClear = false
      renderer.shadowMap.autoUpdate = false
      hidden.forEach((object) => {
        object.visible = false
      })
      scene.background = null
      scene.overrideMaterial = this.depthMaterial
      renderer.setRenderTarget(this.mask)
      renderer.setClearColor(0x000000, 0)
      renderer.clear(true, true, false)
      // The room's depth hides parts behind desks or sofas; all selected parts share one mask.
      renderer.render(scene, camera)
      renderer.render(this.maskScene, camera)
      renderer.setRenderTarget(previous.target)
      this.quad.render(renderer)
    } finally {
      hidden.forEach((object) => {
        object.visible = true
      })
      scene.background = previous.background
      scene.overrideMaterial = previous.material
      renderer.setRenderTarget(previous.target)
      renderer.setClearColor(this.clearColor, previous.alpha)
      renderer.autoClear = previous.autoClear
      renderer.shadowMap.autoUpdate = previous.shadowAutoUpdate
    }
  }

  dispose() {
    this.proxies.clear()
    this.maskScene.clear()
    this.mask.dispose()
    this.depthMaterial.dispose()
    this.maskMaterial.dispose()
    this.edgeMaterial.dispose()
    this.quad.dispose()
  }
}
