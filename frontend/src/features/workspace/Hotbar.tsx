import { useEffect } from 'react'
import {
  CheckCheck,
  ChartNoAxesCombined,
  FileText,
  GitBranch,
  Lock,
  Network,
  Users,
} from 'lucide-react'
import { BibiFace } from '@/components/BibiFace'
import { cn } from '@/lib/utils'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import type { WorkspacePane } from '@/stores/workspaceStore'

const hotbarSlots = [
  { pane: 'documents', label: '공용 문서함', icon: FileText },
  { pane: 'changes', label: '변경 제안', icon: GitBranch },
  { pane: 'meetings', label: '회의실', icon: Users },
  { pane: 'tasks', label: '작업 현황', icon: CheckCheck },
  { pane: 'relations', label: '설계 관계', icon: Network },
  { pane: 'dashboard', label: '대시보드', icon: ChartNoAxesCombined },
] satisfies { pane: WorkspacePane; label: string; icon: typeof FileText }[]

// 단축 바 D안(캔버스 v437~438 이름 보이는 얇은 핫바): 높이 44 바에 칸을 하나씩 떼어 숫자 키캡·아이콘·이름을 늘 보이고,
// 비비는 가운데 46px 둥근 버튼(Enter). 배지는 칸 오른쪽 위.
export function Hotbar({
  locked,
  badges,
  highlight,
  lockedReason = '문서 4종을 올리면 열려요',
  shortcutsOff,
}: {
  /** 둘러보기·환영 창이 열린 동안 숫자 단축키를 끈다(PR #72 전수 리뷰 UI-1) */
  shortcutsOff?: boolean
  /** 프로젝트가 시작되기 전에 잠그는 칸 */
  locked: WorkspacePane[]
  /** 잠긴 칸을 눌렀을 때 알려 줄 이유 */
  lockedReason?: string
  badges: Partial<Record<WorkspacePane, number>>
  /** '여기부터 시작' 표시를 붙일 칸 */
  highlight?: WorkspacePane
}) {
  const pane = useWorkspaceStore((state) => state.pane)
  const openPane = useWorkspaceStore((state) => state.openPane)
  // 숫자 키 1~6으로 칸을 연다. 열린 창의 번호를 다시 누르면 닫는다(창 머리 'N 또는 Esc로 닫기'). 입력 중이면 무시한다.
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      const target = event.target as HTMLElement | null
      if (
        shortcutsOff ||
        event.repeat ||
        event.altKey ||
        event.ctrlKey ||
        event.metaKey ||
        target?.closest('input, textarea, select, [contenteditable="true"]')
      )
        return
      const slot = hotbarSlots[Number(event.key) - 1]
      if (pane) {
        if (slot?.pane === pane) openPane(null)
        return
      }
      if (slot && !locked.includes(slot.pane)) openPane(slot.pane)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [pane, locked, openPane, shortcutsOff])
  const slot = (item: (typeof hotbarSlots)[number], index: number) => {
    const isLocked = locked.includes(item.pane)
    const isOpen = pane === item.pane
    const badge = badges[item.pane]
    return (
      <button
        key={item.pane}
        type="button"
        aria-label={item.label}
        aria-keyshortcuts={String(index + 1)}
        aria-current={isOpen ? 'page' : undefined}
        aria-disabled={isLocked || undefined}
        title={isLocked ? lockedReason : item.label}
        onClick={() => !isLocked && openPane(isOpen ? null : item.pane)}
        className={cn(
          'relative inline-flex h-[34px] items-center gap-1.5 rounded-[10px] bg-[#f5f2ea] pr-2.5 pl-1.5 text-[12.5px] font-semibold whitespace-nowrap text-[#3f3a2e] shadow-[inset_0_0_0_1px_rgba(44,41,31,0.07)] transition-colors hover:bg-[#ece7da]',
          isOpen &&
            'bg-foreground font-bold text-white shadow-[inset_0_0_0_1px_var(--foreground)] hover:bg-foreground',
          isLocked &&
            'cursor-not-allowed bg-[#f8f6f1] text-[#bdb8ac] shadow-[inset_0_0_0_1px_rgba(44,41,31,0.05)] hover:bg-[#f8f6f1]',
          highlight === item.pane &&
            'bg-[#fff3c4] shadow-[0_0_0_2px_var(--primary)] hover:bg-[#fff3c4]',
        )}
      >
        <span
          aria-hidden="true"
          className={cn(
            'inline-grid h-4 min-w-4 place-items-center rounded-[5px] border border-[#e2ddcf] bg-white text-[9.5px] leading-none font-bold text-[#8a8478]',
            isOpen && 'border-white/35 bg-transparent text-white/85',
            isLocked && 'border-[#e7e3d6] text-[#cfcabd]',
          )}
        >
          {index + 1}
        </span>
        <item.icon aria-hidden="true" className="size-[17px]" />
        {/* 좁은 화면(xl 미만)에서는 이름을 숨겨 바를 줄인다. 이름은 aria-label과 title로 남는다. */}
        <span className="max-xl:hidden">{item.label}</span>
        {isLocked && (
          <Lock
            aria-hidden="true"
            className="size-2.5 text-[#c9c3b3]"
            strokeWidth={2.6}
          />
        )}
        {highlight === item.pane && (
          <span className="absolute bottom-11 left-1/2 -translate-x-1/2 rounded-full bg-primary px-2.5 py-[5px] text-xs font-bold whitespace-nowrap text-primary-foreground shadow-md">
            여기부터 시작
            <span
              aria-hidden="true"
              className="absolute -bottom-1 left-1/2 -ml-1 size-2 rotate-45 bg-primary"
            />
          </span>
        )}
        {badge ? (
          <span
            aria-label={`새 항목 ${badge}개`}
            className="absolute -top-2 -right-1.5 grid h-[18px] min-w-[18px] place-items-center rounded-full bg-[#e7000b] px-[5px] text-[11px] font-bold text-white shadow-[0_0_0_2px_#fff]"
          >
            {badge}
          </span>
        ) : null}
      </button>
    )
  }
  return (
    <nav
      aria-label="프로젝트 메뉴 단축 바"
      data-tour="hotbar"
      className={cn(
        // 좁은 화면에서는 왼쪽 아래 시점·산책 버튼과 겹치지 않게 오른쪽 아래로 옮긴다(PR #72 전수 리뷰 UI-2).
        'pointer-events-auto absolute bottom-[18px] left-1/2 z-20 flex h-11 -translate-x-1/2 items-center gap-1 rounded-[14px] border border-[rgba(44,41,31,0.08)] bg-white/92 px-[5px] shadow-[0_18px_32px_-16px_rgba(44,41,31,0.35),0_2px_6px_rgba(44,41,31,0.06)] backdrop-blur-[8px] max-xl:right-4 max-xl:left-auto max-xl:translate-x-0',
        // 업무 창이 열려도 단축 바는 어두운 막 위에 밝게 남는다.
        pane && pane !== 'settings' && 'z-[60]',
      )}
    >
      {hotbarSlots.slice(0, 3).map((item, index) => slot(item, index))}
      {/* 비비는 가운데 46px 둥근 버튼. 칸 높이(34px) 자리만 차지하고 위아래로 넘친다. */}
      <button
        type="button"
        aria-label="비비와 대화"
        aria-keyshortcuts="Enter"
        data-tour="bibi"
        aria-pressed={pane === 'chat'}
        onClick={() => openPane(pane === 'chat' ? null : 'chat')}
        className="relative mx-[3px] block h-[34px] w-[58px] rounded-full"
      >
        <span
          className={cn(
            'absolute top-1/2 left-1/2 block size-[46px] -translate-1/2 overflow-hidden rounded-full shadow-[0_0_0_3px_#fff,0_0_0_5px_var(--primary),0_14px_22px_-10px_rgba(44,41,31,0.55)]',
            // 비비 대화가 열려 있으면 열린 칸처럼 테두리를 검정으로(PNL-12-v2-bibi-hotbar).
            pane === 'chat' &&
              'shadow-[0_0_0_3px_#fff,0_0_0_5px_var(--foreground),0_14px_22px_-10px_rgba(44,41,31,0.55)]',
          )}
        >
          <BibiFace className="size-full" />
        </span>
        <span
          aria-hidden="true"
          className="absolute -bottom-[13px] left-1/2 inline-grid h-[15px] -translate-x-1/2 place-items-center rounded-[4px] border border-[#e2ddcf] bg-white px-1 text-[8.5px] leading-none font-bold text-[#8a8478]"
        >
          Enter
        </span>
      </button>
      {hotbarSlots.slice(3).map((item, index) => slot(item, index + 3))}
    </nav>
  )
}
