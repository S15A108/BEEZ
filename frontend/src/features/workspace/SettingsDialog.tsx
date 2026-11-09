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
import { OfficeTimeSettings } from './OfficeTimeSettings'

type Section = TeamView | 'agent' | 'office'

// 오른쪽 위 톱니로 여는 설정 창. 팀 관리·에이전트·프로젝트 정보를 한곳에 모았다.
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
  const section: Section =
    focusId === 'project' && isLeader
      ? 'project'
      : focusId === 'agent' || focusId === 'remove' || focusId === 'office'
        ? focusId
        : 'team'
  const go = (next: Section) => openPane('settings', next)
  // 토큰 발급처럼 요청이 처리 중이면 다른 메뉴로 옮기지 않는다. 옮기면 발급이 끝나도 한 번만 보이는 평문 토큰을
  // 받을 화면이 없어 토큰을 잃는다(PR #72 재전수 SECOND-AMP-02). 창 닫기도 처리 중에는 막는다.
  const busy = pending > 0
  const item = (value: Section, label: string, extra?: string) => (
    <button
      type="button"
      aria-current={section === value ? 'page' : undefined}
      disabled={busy && section !== value}
      onClick={() => go(value)}
      className={cn(
        'flex h-9 items-center justify-between rounded-lg px-3 text-left text-sm font-medium hover:bg-muted disabled:cursor-not-allowed disabled:opacity-50 disabled:hover:bg-transparent',
        section === value && 'bg-muted font-semibold',
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
            <p className="mb-1 flex justify-between px-3 text-[11px] font-bold text-muted-foreground">
              프로젝트 설정
              {isLeader && <span className="font-semibold">팀장만</span>}
            </p>
            {isLeader && item('project', '프로젝트 정보')}
            {item('team', '팀')}
          </div>
          <div className="grid gap-0.5">
            <p className="mb-1 px-3 text-[11px] font-bold text-muted-foreground">
              내 설정
            </p>
            {item(
              'agent',
              '에이전트',
              agentStatusLabels[
                statusOf({ id: memberId, agent_connected: agentConnected })
                  .status
              ],
            )}
            {item('office', '사무실 화면')}
            {item('remove', isLeader ? '프로젝트 제거' : '프로젝트 나가기')}
          </div>
          <div className="grid gap-0.5">
            <p className="mb-1 px-3 text-[11px] font-bold text-muted-foreground">
              계정
            </p>
            <Link
              to="/account"
              aria-disabled={busy || undefined}
              onClick={(event) => busy && event.preventDefault()}
              className="flex h-9 items-center justify-between rounded-lg px-3 text-sm font-medium hover:bg-muted aria-disabled:cursor-not-allowed aria-disabled:opacity-50"
            >
              회원 설정
              <span className="text-[11px] text-muted-foreground">↗</span>
            </Link>
          </div>
        </nav>
        <PaneSheetHost className="min-w-0 overflow-y-auto px-8 py-7">
          {section === 'agent' ? (
            <AgentsPanel projectId={projectId} />
          ) : section === 'office' ? (
            <OfficeTimeSettings />
          ) : (
            <TeamPanel
              key={section}
              projectId={projectId}
              isLeader={isLeader}
              view={section}
              onViewChange={go}
            />
          )}
        </PaneSheetHost>
      </DialogContent>
    </Dialog>
  )
}
