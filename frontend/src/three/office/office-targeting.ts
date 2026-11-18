import * as THREE from 'three'
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
    kit: '소품',
  }
  // 고정 사무실(D36): 모니터·노트북 자리, 칸반·메모 벽, 벽 화면
  if (furniture.tag?.startsWith('workstation/')) return '작업 자리'
  if (furniture.kit?.ui === 'kanban') return '칸반 보드'
  if (furniture.kit?.ui === 'notes') return '메모 벽'
  if (furniture.kind === 'projector' && furniture.kit) return '벽 화면'
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

/** Only an actionable front surface can be selected; decorations still occlude actions behind them. */
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
