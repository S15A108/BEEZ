import { lazy, Suspense, useEffect, useMemo, useRef, useState } from 'react'
import { useParams, useSearchParams } from 'react-router'
import { useIsMutating, useQuery } from '@tanstack/react-query'
import { Footprints } from 'lucide-react'
import type { SceneAgent, SceneTask } from '@/three/types'
import { RequireAccount } from '@/features/auth/RequireAccount'
import { useSession } from '@/features/auth/useSession'
import { DocumentsPanel } from '@/features/documents/DocumentsPanel'
import { PlanningPanel } from '@/features/planning/PlanningPanel'
import { ChangesPanel } from '@/features/changes/ChangesPanel'
import { TasksPanel } from '@/features/tasks/TasksPanel'
import { MeetingsPanel } from '@/features/meetings/MeetingsPanel'
import { ActivityPanel } from '@/features/dashboard/ActivityPanel'
import { InboxPanel } from '@/features/inbox/InboxPanel'
import { BibiPanel } from '@/features/bee/BibiPanel'
import { ContextPanel } from '@/features/contexts/ContextPanel'
import { WorkspaceHud } from '@/features/workspace/WorkspaceHud'
import { SettingsDialog } from '@/features/workspace/SettingsDialog'
import { sceneQuery } from '@/features/workspace/sceneQuery'
import { AsyncState } from '@/components/AsyncState'
import { PaneSheetHost } from '@/components/PaneSheet'
import { PaneHeaderSlot } from '@/components/paneHeaderSlot'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import {
  placementKey,
  useClearPaneOnLeave,
  useWorkspaceStore,
} from '@/stores/workspaceStore'
import type { WorkspacePane } from '@/stores/workspaceStore'

const OfficeScene = lazy(() => import('@/three/OfficeScene'))
// 단축 바 번호(창 머리 'N 또는 Esc로 닫기')
const paneKeys: Partial<Record<WorkspacePane, number>> = {
  documents: 1,
  changes: 2,
  meetings: 3,
  tasks: 4,
  relations: 5,
  dashboard: 6,
}
const paneTitles: Record<Exclude<WorkspacePane, 'settings'>, string> = {
  documents: '공용 문서함',
  planning: '기획 분석',
  changes: '변경 제안',
  meetings: '회의실',
  tasks: '작업 현황',
  relations: '작업·설계 관계',
  dashboard: '대시보드',
  inbox: '알림함',
  chat: '비비',
  context: '작업 맥락',
}

function Workspace({ projectId }: { projectId: string }) {
  const sceneHost = useRef<HTMLElement>(null)
  const focusScene = () =>
    sceneHost.current?.querySelector('canvas')?.focus({ preventScroll: true })
  const session = useSession()
  const [params, setParams] = useSearchParams()
  const pane = useWorkspaceStore((state) => state.pane)
  const openPane = useWorkspaceStore((state) => state.openPane)
  const viewMode = useWorkspaceStore((state) => state.viewMode)
  const setViewMode = useWorkspaceStore((state) => state.setViewMode)
  const outdoor = useWorkspaceStore((state) => state.outdoor)
  const setOutdoor = useWorkspaceStore((state) => state.setOutdoor)
  const pendingMutations = useIsMutating()
  const [headerSlot, setHeaderSlot] = useState<HTMLDivElement | null>(null)
  useClearPaneOnLeave()
  // 이 브라우저에 저장한 건물 디자인(D18). 산책 화면의 우리 건물을 도시와 같은 모델로 그린다(D22).
  const design = useWorkspaceStore(
    (state) =>
      state.placements[placementKey(session.data?.id ?? '', projectId)]?.design,
  )
  // 회원 탈퇴 화면의 '팀장 위임'처럼 ?settings=team으로 들어오면 설정 창을 연다.
  const settingsParam = params.get('settings')
  useEffect(() => {
    if (!settingsParam) return
    openPane('settings', settingsParam)
    setParams({}, { replace: true })
  }, [settingsParam, openPane, setParams])
  const query = useQuery(sceneQuery(projectId, session.data?.id))
  // 들어갈 수 있는 프로젝트를 열었으면 이 계정의 마지막 방문으로 남긴다. 나의 도시가 그 건물을 가운데에 두고 시작한다(D26).
  const visitProject = useWorkspaceStore((state) => state.visitProject)
  const accountId = session.data?.id
  useEffect(() => {
    if (query.isSuccess && accountId) visitProject(accountId, projectId)
  }, [query.isSuccess, accountId, projectId, visitProject])
  const sceneAgents = useMemo<SceneAgent[]>(
    () => [
      { id: 'bibi', name: '비비', kind: 'lead', color: '#f5b800' },
      ...(query.data?.members.items ?? []).map((member) => ({
        id: `member:${member.id}`,
        name: member.name,
        kind:
          member.account_id === session.data?.id
            ? ('human' as const)
            : ('agent' as const),
        color: member.color ?? '#8ca9a0',
        status: 'idle',
      })),
    ],
    [query.data?.members, session.data?.id],
  )
  const sceneTasks = useMemo<SceneTask[]>(
    () =>
      (query.data?.tasks.items ?? []).map((task) => ({
        id: task.id,
        title: task.current_plan_revision.plan.title,
        state: task.status,
        assignee_id: `member:${task.assignee_member_id}`,
        description: task.current_plan_revision.plan.goal,
        waiting_reason: task.waiting_reason,
      })),
    [query.data?.tasks],
  )
  return (
    <AsyncState query={query}>
      {(data) => {
        const isLeader = data.project.my_role === 'LEADER'
        const currentUser = data.members.items.find(
          (member) => member.account_id === session.data?.id,
        )
        const panelProps = { projectId, isLeader }
        return (
          <main
            ref={sceneHost}
            data-tour="office"
            className="relative h-dvh min-h-[620px] overflow-hidden bg-muted/30"
            aria-label="프로젝트 워크스페이스"
          >
            <Suspense
              fallback={
                <p role="status" className="grid h-full place-items-center">
                  3D 사무실을 준비하는 중…
                </p>
              }
            >
              <OfficeScene
                key={projectId}
                project={data.project}
                design={design}
                agents={sceneAgents}
                tasks={sceneTasks}
                currentUserId={
                  currentUser ? `member:${currentUser.id}` : undefined
                }
                viewMode={viewMode}
                outdoor={outdoor}
                interactive={pane === null}
                phase={data.project.current_baseline ? 4 : 3}
                onOutdoorChange={setOutdoor}
                onAgentSelect={(id) =>
                  id === 'bibi' || id.includes('reception')
                    ? openPane('chat', id)
                    : openPane('settings', 'team')
                }
                onTaskSelect={(id) => openPane('tasks', id)}
                className="absolute inset-0 h-full w-full"
              />
            </Suspense>
            <WorkspaceHud
              project={data.project}
              members={data.members.items}
              me={currentUser}
              onboarding={data.onboarding}
              tasks={data.tasks.items}
              myTasks={data.tasks.items.filter(
                (task) => task.assignee_member_id === currentUser?.id,
              )}
            />
            <div
              data-tour="view"
              className="absolute bottom-4 left-4 z-10 flex items-center gap-2"
            >
              <div className="rounded-xl border bg-card/95 p-1 shadow-sm">
                <Button
                  size="sm"
                  variant={viewMode === 'first' ? 'secondary' : 'ghost'}
                  onClick={() => {
                    setViewMode('first')
                    focusScene()
                  }}
                >
                  1인칭
                </Button>
                <Button
                  size="sm"
                  variant={viewMode === 'third' ? 'secondary' : 'ghost'}
                  onClick={() => {
                    setViewMode('third')
                    focusScene()
                  }}
                >
                  3인칭
                </Button>
              </div>
              <Button
                variant="secondary"
                onClick={() => {
                  setOutdoor(!outdoor)
                  focusScene()
                }}
              >
                <Footprints />
                {outdoor ? '사무실로' : '산책'}
              </Button>
            </div>
            {/* 조작 안내는 처음(기준 v1 전)에만, 단축 바 위 가운데에 보여 준다(SCR-06-v2-workspace-preparing). */}
            {!pane && !data.project.current_baseline && (
              <p className="pointer-events-none absolute bottom-[82px] left-1/2 z-10 -translate-x-1/2 rounded-full bg-white/85 px-3 py-1 text-[11px] leading-4 whitespace-nowrap text-[#737373]">
                WASD 이동 · Shift 달리기 · 드래그로 둘러보기 · 숫자 키로 메뉴
                열기
              </p>
            )}
            <SettingsDialog
              projectId={projectId}
              isLeader={isLeader}
              agentConnected={data.onboarding.my_agent_connected}
            />
            <Dialog
              open={pane !== null && pane !== 'settings'}
              onOpenChange={(open) => {
                if (!open && !pendingMutations) openPane(null)
              }}
            >
              {/* 보드처럼 위에서 40px에 두고, 아래 단축 바 자리(120px)를 비운다.
                  비비 대화를 포함한 모든 업무 창은 내용이 적거나 비어 있어도 같은 폭·높이·바탕색으로 열고,
                  넘치는 내용만 안에서 스크롤한다. */}
              <DialogContent
                className="top-10 h-[calc(100dvh-160px)] translate-y-0 grid-rows-[auto_1fr] overflow-y-auto bg-background p-5 sm:max-w-[min(1180px,94vw)] sm:p-8"
                onEscapeKeyDown={(event) => {
                  if (pendingMutations) event.preventDefault()
                }}
                onInteractOutside={(event) => {
                  // 창이 열려도 단축 바는 밝게 남아 다른 메뉴로 바로 옮겨 간다.
                  const target = event.target as HTMLElement | null
                  if (
                    pendingMutations ||
                    target?.closest('nav[aria-label="프로젝트 메뉴 단축 바"]')
                  )
                    event.preventDefault()
                }}
              >
                <DialogHeader>
                  <div className="flex items-start justify-between gap-3 pr-8">
                    {/* 업무 창 머리 제목만 18px·600(사용자 요청 11-02). 공용 DialogTitle은 다른 확인 창도 쓰므로 그대로 둔다. */}
                    <DialogTitle className="text-lg font-semibold">
                      {pane && pane !== 'settings' ? paneTitles[pane] : ''}
                    </DialogTitle>
                    {/* 창별 머리 버튼 자리(작업 현황의 '+ 작업 계획', 캔버스 PNL-05-v2-tasks) */}
                    <div
                      ref={setHeaderSlot}
                      className="-mt-1 ml-auto flex shrink-0 items-center gap-2 empty:hidden"
                    />
                    {pane && paneKeys[pane] && (
                      <span className="mt-0.5 shrink-0 text-xs whitespace-nowrap text-muted-foreground">
                        <kbd className="rounded-md border px-1.5 py-px font-[inherit]">
                          {paneKeys[pane]}
                        </kbd>{' '}
                        또는{' '}
                        <kbd className="rounded-md border px-1.5 py-px font-[inherit]">
                          Esc
                        </kbd>
                        로 닫기
                      </span>
                    )}
                  </div>
                  {/* 공용 문서함은 머리 부제목 없이 제목만(PNL-02-v2 10-29 부제목 제거). */}
                  <DialogDescription
                    className={pane === 'documents' ? 'sr-only' : undefined}
                  >
                    {data.project.name}
                    {data.project.current_baseline &&
                      ` · 기준 v${data.project.current_baseline.version_no}`}
                  </DialogDescription>
                </DialogHeader>
                <PaneHeaderSlot value={headerSlot}>
                  <PaneSheetHost
                    key={`${projectId}:${pane}`}
                    // 본문 자리는 창 아래까지 채운다(비비 대화 입력줄, 하위 화면의 아래 버튼 줄).
                    className="mt-4 flex min-w-0 flex-col"
                  >
                    {pane === 'documents' && <DocumentsPanel {...panelProps} />}
                    {pane === 'planning' && <PlanningPanel {...panelProps} />}
                    {pane === 'changes' && <ChangesPanel {...panelProps} />}
                    {pane === 'tasks' && <TasksPanel {...panelProps} />}
                    {pane === 'meetings' && <MeetingsPanel {...panelProps} />}
                    {pane === 'relations' && (
                      <ActivityPanel projectId={projectId} view="relations" />
                    )}
                    {pane === 'dashboard' && (
                      <ActivityPanel projectId={projectId} view="dashboard" />
                    )}
                    {pane === 'inbox' && <InboxPanel projectId={projectId} />}
                    {pane === 'chat' && <BibiPanel projectId={projectId} />}
                    {pane === 'context' && (
                      <ContextPanel projectId={projectId} />
                    )}
                  </PaneSheetHost>
                </PaneHeaderSlot>
              </DialogContent>
            </Dialog>
          </main>
        )
      }}
    </AsyncState>
  )
}

export default function WorkspacePage() {
  const { projectId = '' } = useParams()
  return (
    <RequireAccount>
      <Workspace key={projectId} projectId={projectId} />
    </RequireAccount>
  )
}
