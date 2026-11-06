import { useEffect } from 'react'
import { create } from 'zustand'
import { persist } from 'zustand/middleware'

// 단축 바 1~6(documents, changes, meetings, tasks, relations, dashboard)과 가운데 비비(chat),
// 톱니(settings), 지난 알림(inbox). planning·context는 단축 바에서 뺐지만 문서함·작업에서 아직 연다.
export type WorkspacePane =
  | 'documents'
  | 'planning'
  | 'changes'
  | 'tasks'
  | 'meetings'
  | 'relations'
  | 'dashboard'
  | 'settings'
  | 'inbox'
  | 'chat'
  | 'context'
export type BuildingDesign = 'glass' | 'brick' | 'modern' | 'tower' | 'wood'
type Placement = { lot: number; design: BuildingDesign }
interface WorkspaceState {
  pane: WorkspacePane | null
  focusId: string | null
  viewMode: 'first' | 'third'
  outdoor: boolean
  placements: Record<string, Placement>
  /** 계정 id → 마지막으로 연 프로젝트 id. 나의 도시가 그 건물을 가운데에 두고 시작한다(D33). */
  lastVisited: Record<string, string>
  openPane: (pane: WorkspacePane | null, focusId?: string | null) => void
  setViewMode: (viewMode: 'first' | 'third') => void
  setOutdoor: (outdoor: boolean) => void
  /** 도시에 보이는 칸(빈 칸을 당긴 번호)을 한꺼번에 저장한다. 칸 없는(lot null) 항목은 건너뛴다. */
  placeProjects: (
    accountId: string,
    items: { id: string; lot: number | null; design: BuildingDesign }[],
  ) => void
  visitProject: (accountId: string, projectId: string) => void
}
export const placementKey = (accountId: string, projectId: string) =>
  `${accountId}:${projectId}`
export const useWorkspaceStore = create<WorkspaceState>()(
  persist(
    (set) => ({
      pane: null,
      focusId: null,
      viewMode: 'third',
      outdoor: false,
      placements: {},
      lastVisited: {},
      openPane: (pane, focusId = null) => set({ pane, focusId }),
      setViewMode: (viewMode) => set({ viewMode }),
      setOutdoor: (outdoor) => set({ outdoor }),
      placeProjects: (accountId, items) =>
        set((state) => ({
          placements: {
            ...state.placements,
            ...Object.fromEntries(
              items.flatMap(({ id, lot, design }) =>
                lot === null
                  ? []
                  : [[placementKey(accountId, id), { lot, design }]],
              ),
            ),
          },
        })),
      visitProject: (accountId, projectId) =>
        set((state) => ({
          lastVisited: { ...state.lastVisited, [accountId]: projectId },
        })),
    }),
    {
      name: 'beez.scene.preferences.v1',
      partialize: (state) => ({
        placements: state.placements,
        lastVisited: state.lastVisited,
        viewMode: state.viewMode,
      }),
    },
  ),
)

// 워크스페이스를 떠나거나 다른 프로젝트로 바뀌면(Workspace는 프로젝트 id를 key로 다시 마운트된다) 열려 있던 창과 대상을
// 비운다. 남아 있으면 다음 프로젝트에서 이전 프로젝트의 작업 id로 상세를 열어 404 창이 뜬다(PR #72 4차 조사 FOURTH-UI-01).
export function useClearPaneOnLeave() {
  const openPane = useWorkspaceStore((state) => state.openPane)
  useEffect(() => () => openPane(null), [openPane])
}
