import * as THREE from 'three'

type StencilState = Pick<
  THREE.Material,
  | 'stencilWrite'
  | 'stencilWriteMask'
  | 'stencilFunc'
  | 'stencilRef'
  | 'stencilFuncMask'
  | 'stencilFail'
  | 'stencilZFail'
  | 'stencilZPass'
>

function stencilState(material: THREE.Material): StencilState {
  return {
    stencilWrite: material.stencilWrite,
    stencilWriteMask: material.stencilWriteMask,
    stencilFunc: material.stencilFunc,
    stencilRef: material.stencilRef,
    stencilFuncMask: material.stencilFuncMask,
    stencilFail: material.stencilFail,
    stencilZFail: material.stencilZFail,
    stencilZPass: material.stencilZPass,
  }
}

/** Clips the exterior pass to the actual, currently visible office glazing. */
export class WindowCityView {
  readonly maskScene = new THREE.Scene()

  private readonly maskMaterial = new THREE.MeshBasicMaterial({
    side: THREE.DoubleSide,
    colorWrite: false,
    depthWrite: false,
    depthTest: false,
    toneMapped: false,
    stencilWrite: true,
    stencilWriteMask: 0xff,
    stencilFunc: THREE.AlwaysStencilFunc,
    stencilRef: 1,
    stencilFuncMask: 0xff,
    stencilFail: THREE.KeepStencilOp,
    stencilZFail: THREE.KeepStencilOp,
    stencilZPass: THREE.ReplaceStencilOp,
  })
  private readonly panes = new Map<THREE.Mesh, THREE.Mesh>()
  private readonly roots = new WeakMap<
    THREE.Object3D,
    { materials: THREE.Material[]; enabled?: boolean; version: number }
  >()
  private readonly originalStates = new Map<THREE.Material, StencilState>()
  private disposed = false

  constructor(skyColor?: THREE.ColorRepresentation) {
    if (skyColor !== undefined) {
      this.maskMaterial.color.set(skyColor)
      this.maskMaterial.colorWrite = true
    }
  }

  /** Geometry stays owned by the office renderer; these are transform-only proxies. */
  addPane(source: THREE.Mesh): void {
    if (this.panes.has(source)) return
    const proxy = new THREE.Mesh(source.geometry, this.maskMaterial)
    proxy.name = `city-window-mask:${source.name || source.id}`
    proxy.matrixAutoUpdate = false
    proxy.matrixWorldAutoUpdate = false
    proxy.frustumCulled = false
    this.panes.set(source, proxy)
    this.maskScene.add(proxy)
  }

  /** Call after cutaway visibility updates and before rendering maskScene. */
  sync(): void {
    for (const [source, proxy] of this.panes) {
      let visible = true
      for (
        let ancestor: THREE.Object3D | null = source;
        ancestor;
        ancestor = ancestor.parent
      ) {
        if (!ancestor.visible) {
          visible = false
          break
        }
      }
      proxy.visible = visible
      if (!visible) continue
      source.updateWorldMatrix(true, false)
      proxy.matrix.copy(source.matrixWorld)
      proxy.matrixWorld.copy(source.matrixWorld)
    }
  }

  clear(): void {
    this.maskScene.clear()
    this.panes.clear()
  }

  /**
   * A city root is indexed once per content version; switching view modes only updates GL state.
   * 건물을 나중에 끼우면(Kenney 모델) root.userData.cityVersion이 바뀌어 새 재질도 다시 모은다.
   */
  setCityStencil(root: THREE.Object3D, enabled: boolean): void {
    const version = (root.userData.cityVersion as number | undefined) ?? 0
    let entry = this.roots.get(root)
    if (entry && entry.version !== version) {
      // 새 재질에도 지금 상태를 다시 걸도록 다시 모은다.
      entry = undefined
    }
    if (!entry) {
      const materials = new Set<THREE.Material>()
      root.traverse((object) => {
        const mesh = object as THREE.Mesh
        if (!mesh.isMesh) return
        for (const material of Array.isArray(mesh.material)
          ? mesh.material
          : [mesh.material]) {
          materials.add(material)
        }
      })
      entry = { materials: [...materials], version }
      this.roots.set(root, entry)
    }
    if (entry.enabled === enabled) return
    entry.enabled = enabled
    for (const material of entry.materials) {
      if (!this.originalStates.has(material))
        this.originalStates.set(material, stencilState(material))
      if (enabled) {
        material.stencilWrite = true
        material.stencilWriteMask = 0
        material.stencilFunc = THREE.EqualStencilFunc
        material.stencilRef = 1
        material.stencilFuncMask = 0xff
        material.stencilFail = THREE.KeepStencilOp
        material.stencilZFail = THREE.KeepStencilOp
        material.stencilZPass = THREE.KeepStencilOp
      } else {
        Object.assign(material, this.originalStates.get(material))
        material.stencilWrite = false
      }
    }
  }

  dispose(): void {
    if (this.disposed) return
    this.disposed = true
    this.clear()
    for (const [material, state] of this.originalStates)
      Object.assign(material, state)
    this.originalStates.clear()
    this.maskMaterial.dispose()
  }
}
