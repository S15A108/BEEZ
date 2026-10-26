import type { BuildingDesign } from '@/stores/workspaceStore'
import { placementKey } from '@/stores/workspaceStore'

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
    const lot = Array.from({ length: 8 }, (_, index) => index).find(
      (index) => !used.has(index),
    )
    if (lot !== undefined) used.add(lot)
    return { id, lot: lot ?? null, design: 'modern' as BuildingDesign }
  })
}
