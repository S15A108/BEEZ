import { useQuery } from '@tanstack/react-query'
import { BibiFace } from '@/components/BibiFace'
import { beeStatusQuery } from '@/features/bee/beeStatusQuery'
import { useWorkspaceStore } from '@/stores/workspaceStore'

// HUD 팀 목록의 비비 줄(BEE-A04, SCR-06-v2-workspace-hotbar). 비비는 평소 목록에 없고(사용자 요청 10-30)
// 문서를 검토하거나 회의를 진행하는 동안만 한 줄로 보인다. 누르면 그 문서·회의를 연다.
// 비비 검토(DOC-A16)는 대개 첫 승인 전이라 문서는 상세(DOC-A03, 승인본이 없으면 오류)가 아니라 검토 화면('review:ID')으로 연다.
export function BibiStatusRow({ projectId }: { projectId: string }) {
  const openPane = useWorkspaceStore((state) => state.openPane)
  const { data } = useQuery(beeStatusQuery(projectId))
  if (!data || data.activity === 'IDLE' || !data.label) return null
  const { activity, target_id: targetId, label } = data
  return (
    <li>
      <button
        type="button"
        onClick={() =>
          activity === 'MEETING'
            ? openPane('meetings', targetId)
            : openPane('documents', targetId && `review:${targetId}`)
        }
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
