import * as THREE from 'three'
import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js'
import { clone as cloneSkinned } from 'three/addons/utils/SkeletonUtils.js'

// Kenney 에셋(CC0, public/models/kenney). GLB는 같은 폴더의 Textures/colormap.png를 참조한다.
// 한 URL은 한 번만 받아 앱 전체가 나눠 쓰고, 장면에는 복제본을 넣는다(공유 geometry·material은 장면에서 dispose하지 않는다).
export interface KenneyModel {
  scene: THREE.Group
  animations: THREE.AnimationClip[]
  /** 원본 크기(바닥 기준) */
  size: THREE.Vector3
}

const cache = new Map<string, Promise<KenneyModel>>()

export function loadKenney(url: string): Promise<KenneyModel> {
  let entry = cache.get(url)
  if (!entry) {
    entry = new GLTFLoader().loadAsync(url).then((gltf) => {
      const size = new THREE.Box3()
        .setFromObject(gltf.scene)
        .getSize(new THREE.Vector3())
      return { scene: gltf.scene, animations: gltf.animations, size }
    })
    cache.set(url, entry)
    entry.catch(() => cache.delete(url))
  }
  return entry
}

/** 뼈대가 있는 캐릭터도 따로 움직이게 복제한다. */
export const cloneKenney = (model: KenneyModel) =>
  cloneSkinned(model.scene) as THREE.Group
