import type { BuildingDesign } from '@/stores/workspaceStore'
import { placementKey } from '@/stores/workspaceStore'
import { RING_SLOTS } from '@/three/city/ring'

export function projectPlacements(
  ids: string[],
  accountId: string,
  saved: Record<string, { lot: number; design: BuildingDesign }>,
) {
  const used = new Set(
    ids
      .map((id) => saved[placementKey(accountId, id)]?.lot)
      .filter((lot): lot is number => lot !== undefined),
  )
  return ids.map((id) => {
    const existing = saved[placementKey(accountId, id)]
    if (existing) return { id, ...existing }
    // 원형 도시 한 바퀴(12칸)까지 앞 칸부터 채우고, 넘는 프로젝트는 부지 없이 목록에만 남긴다(D26).
    const lot = Array.from({ length: RING_SLOTS }, (_, index) => index).find(
      (index) => !used.has(index),
    )
    if (lot !== undefined) used.add(lot)
    return { id, lot: lot ?? null, design: 'modern' as BuildingDesign }
  })
}
