import { useQuery } from '@tanstack/react-query'
import { BibiFace } from '@/components/BibiFace'
import { beeStatusQuery } from '@/features/bee/beeStatusQuery'
import { useWorkspaceStore } from '@/stores/workspaceStore'

const paneOf = { DOCUMENT_REVIEW: 'documents', MEETING: 'meetings' } as const

// HUD 팀 목록의 비비 줄(BEE-A04, SCR-06-v2-workspace-hotbar). 비비는 평소 목록에 없고(사용자 요청 10-30)
// 문서를 검토하거나 회의를 진행하는 동안만 한 줄로 보인다. 누르면 그 문서·회의를 연다.
export function BibiStatusRow({ projectId }: { projectId: string }) {
  const openPane = useWorkspaceStore((state) => state.openPane)
  const { data } = useQuery(beeStatusQuery(projectId))
  if (!data || data.activity === 'IDLE' || !data.label) return null
  const { activity, target_id: targetId, label } = data
  return (
    <li>
      <button
        type="button"
        onClick={() => openPane(paneOf[activity], targetId)}
        className="flex w-full items-center gap-2.5 rounded-lg px-1.5 py-1 text-left hover:bg-muted"
      >
        <BibiFace className="size-7" />
        <span className="min-w-0">
          <strong className="block truncate text-[13px] leading-5">
            비비 · AI 팀장
          </strong>
          <span className="block truncate text-xs leading-[18px] text-muted-foreground">
            {label}
          </span>
        </span>
      </button>
    </li>
  )
}
