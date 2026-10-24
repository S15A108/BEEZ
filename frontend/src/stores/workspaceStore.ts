import { create } from 'zustand'
import { persist } from 'zustand/middleware'

export type WorkspacePane =
  | 'overview'
  | 'documents'
  | 'planning'
  | 'changes'
  | 'tasks'
  | 'meetings'
  | 'team'
  | 'agents'
  | 'activity'
  | 'inbox'
  | 'chat'
  | 'context'
  | 'git'
export type BuildingDesign = 'glass' | 'brick' | 'modern' | 'tower' | 'wood'
type Placement = { lot: number; design: BuildingDesign }
interface WorkspaceState {
  pane: WorkspacePane | null
  focusId: string | null
  viewMode: 'first' | 'third'
  outdoor: boolean
  placements: Record<string, Placement>
  openPane: (pane: WorkspacePane | null, focusId?: string | null) => void
  setViewMode: (viewMode: 'first' | 'third') => void
  setOutdoor: (outdoor: boolean) => void
  placeProject: (
    accountId: string,
    projectId: string,
    placement: Placement,
  ) => void
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
      openPane: (pane, focusId = null) => set({ pane, focusId }),
      setViewMode: (viewMode) => set({ viewMode }),
      setOutdoor: (outdoor) => set({ outdoor }),
      placeProject: (accountId, projectId, placement) =>
        set((state) => ({
          placements: {
            ...state.placements,
            [placementKey(accountId, projectId)]: placement,
          },
        })),
    }),
    {
      name: 'beez.scene.preferences.v1',
      partialize: (state) => ({
        placements: state.placements,
        viewMode: state.viewMode,
      }),
    },
  ),
)
