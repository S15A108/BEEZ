import { useEffect } from 'react'
import { Link } from 'react-router'
import { useIsMutating } from '@tanstack/react-query'
import { AgentsPanel } from '@/features/agents/AgentsPanel'
import { useAgentCheckStore } from '@/features/agents/agentCheckStore'
import { agentStatusLabels } from '@/features/agents/agentStatus'
import { useAgentStatusOf } from '@/features/agents/useAgentStatusOf'
import { TeamPanel } from '@/features/members/TeamPanel'
import type { TeamView } from '@/features/members/TeamPanel'
import { Dialog, DialogContent, DialogTitle } from '@/components/ui/dialog'
import { PaneSheetHost } from '@/components/PaneSheet'
import { cn } from '@/lib/utils'
import { useWorkspaceStore } from '@/stores/workspaceStore'

type Section = TeamView | 'agent'

// 오른쪽 위 톱니로 여는 설정 창. 사이드바는 회원 설정 → 팀 관리 → 내 에이전트 관리 → 프로젝트 관리 → 프로젝트 나가기 순서다
// (S15P31A108-376, 사용자 요청 11-10). 프로젝트 나가기는 위험한 동작이라 맨 아래에 따로 둔다.
export function SettingsDialog({
  projectId,
  isLeader,
  agentConnected,
  memberId,
}: {
  projectId: string
  isLeader: boolean
  agentConnected: boolean
  memberId?: string
}) {
  const statusOf = useAgentStatusOf(projectId)
  const setCheckedMember = useAgentCheckStore((state) => state.setCheckedMember)
  // 이 창은 워크스페이스와 같이 생기고 없어진다(프로젝트마다 다시 만든다).
  useEffect(() => () => setCheckedMember(null), [setCheckedMember])
  const pane = useWorkspaceStore((state) => state.pane)
  const focusId = useWorkspaceStore((state) => state.focusId)
  const openPane = useWorkspaceStore((state) => state.openPane)
  const pending = useIsMutating()
  // 예전 사무실 화면(office)은 프로젝트 관리 아래쪽 시간대로 합쳤다. 예전 링크도 프로젝트 관리를 연다.
  const section: Section =
    focusId === 'project' || focusId === 'office'
      ? 'project'
      : focusId === 'agent' || focusId === 'remove'
        ? focusId
        : 'team'
  const go = (next: Section) => openPane('settings', next)
  // 토큰 발급처럼 요청이 처리 중이면 다른 메뉴로 옮기지 않는다. 옮기면 발급이 끝나도 한 번만 보이는 평문 토큰을
  // 받을 화면이 없어 토큰을 잃는다(PR #72 재전수 SECOND-AMP-02). 창 닫기도 처리 중에는 막는다.
  const busy = pending > 0
  const item = (value: Section, label: string, extra?: string) => {
    const current = section === value
    return (
      <button
        type="button"
        aria-current={current ? 'page' : undefined}
        disabled={busy && !current}
        onClick={() => go(value)}
        className={cn(
          'flex h-9 items-center justify-between rounded-lg px-3 text-left text-sm font-medium hover:bg-muted disabled:cursor-not-allowed disabled:opacity-50 disabled:hover:bg-transparent',
          current && 'bg-muted font-semibold',
          value === 'remove' && 'text-destructive',
        )}
      >
        {label}
        {extra && (
          <span className="text-[11px] font-semibold text-muted-foreground">
            {extra}
          </span>
        )}
      </button>
    )
  }
  return (
    <Dialog
      open={pane === 'settings'}
      onOpenChange={(open) => {
        if (!open && !pending) openPane(null)
      }}
    >
      <DialogContent className="grid h-[min(680px,90dvh)] grid-cols-[220px_minmax(0,1fr)] gap-0 overflow-hidden p-0 sm:max-w-[min(1040px,94vw)]">
        <nav
          aria-label="설정 메뉴"
          className="flex flex-col gap-5 border-r bg-muted/30 px-3 py-6"
        >
          <DialogTitle className="px-3 text-xl font-bold">설정</DialogTitle>
          {busy && (
            <p
              role="status"
              className="mx-3 rounded-lg bg-primary/10 px-3 py-2 text-xs leading-5 text-[#6e5200]"
            >
              처리 중이에요. 끝나면 다른 메뉴로 옮길 수 있어요.
            </p>
          )}
          <div className="grid gap-0.5">
            {/* 회원 설정은 계정 전체에 걸린 화면(비밀번호 변경·탈퇴 하위 페이지 포함)이라 프로젝트 창에 넣지 않고 /account로 잇는다. */}
            <Link
              to="/account"
              aria-disabled={busy || undefined}
              onClick={(event) => busy && event.preventDefault()}
              className="flex h-9 items-center justify-between rounded-lg px-3 text-sm font-medium hover:bg-muted aria-disabled:cursor-not-allowed aria-disabled:opacity-50"
            >
              회원 설정
              <span className="text-[11px] text-muted-foreground">↗</span>
            </Link>
            {item('team', '팀 관리')}
            {item(
              'agent',
              '내 에이전트 관리',
              agentStatusLabels[
                statusOf({ id: memberId, agent_connected: agentConnected })
                  .status
              ],
            )}
            {item('project', '프로젝트 관리')}
          </div>
          {/* S15P31A108-377: 팀장도 '프로젝트 나가기'다. 안에서 위임하고 나가기와 프로젝트 삭제를 따로 고른다. */}
          <div className="grid gap-0.5 border-t pt-3">
            {item('remove', '프로젝트 나가기')}
          </div>
        </nav>
        <PaneSheetHost className="min-w-0 overflow-y-auto px-8 py-7">
          {section === 'agent' ? (
            <AgentsPanel projectId={projectId} />
          ) : (
            <TeamPanel
              key={section}
              projectId={projectId}
              isLeader={isLeader}
              view={section}
            />
          )}
        </PaneSheetHost>
      </DialogContent>
    </Dialog>
  )
}
