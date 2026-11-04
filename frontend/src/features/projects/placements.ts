import type { BuildingDesign } from '@/stores/workspaceStore'
import { placementKey } from '@/stores/workspaceStore'
import { RING_SLOTS } from '@/three/city/ring'

export function projectPlacements(
  ids: string[],
  accountId: string,
  saved: Record<string, { lot: number; design: BuildingDesign }>,
) {
  const savedOf = (id: string) => saved[placementKey(accountId, id)]
  // 저장된 칸 번호는 순서로만 쓴다. 지우거나 나가 빈 칸은 남은 프로젝트를 그 순서대로 앞 칸부터 당겨 채우고
  // (S15P31A108-256), 저장된 자리가 없는 프로젝트는 그 뒤에 목록 순서로 붙인다. 외관은 칸이 아니라 프로젝트를 따라간다.
  const order = [
    ...ids
      .filter((id) => savedOf(id))
      .sort((a, b) => (savedOf(a)?.lot ?? 0) - (savedOf(b)?.lot ?? 0)),
    ...ids.filter((id) => !savedOf(id)),
  ]
  return ids.map((id) => {
    const lot = order.indexOf(id)
    // 원형 도시 한 바퀴(12칸)까지 놓고, 넘는 프로젝트는 부지 없이 목록에만 남긴다(D26).
    return {
      id,
      lot: lot < RING_SLOTS ? lot : null,
      design: savedOf(id)?.design ?? ('modern' as BuildingDesign),
    }
  })
}
