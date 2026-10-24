import { lazy, Suspense, useMemo, useRef } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { useIsMutating, useQuery } from '@tanstack/react-query'
import {
  Bell,
  BookOpen,
  Bot,
  ChartNoAxesCombined,
  CheckCheck,
  ChevronLeft,
  FileText,
  Footprints,
  GitBranch,
  LayoutDashboard,
  Lightbulb,
  MessageCircle,
  Network,
  NotebookPen,
  Settings,
  Users,
} from 'lucide-react'
import { agentsApi, membersApi, projectsApi, tasksApi } from '@/api/services'
import type { SceneAgent, SceneTask } from '@/three/types'
import { RequireAccount } from '@/features/auth/RequireAccount'
import { useSession } from '@/features/auth/useSession'
import { OverviewPanel } from '@/features/workspace/OverviewPanel'
import { DocumentsPanel } from '@/features/documents/DocumentsPanel'
import { PlanningPanel } from '@/features/planning/PlanningPanel'
import { ChangesPanel } from '@/features/changes/ChangesPanel'
import { TasksPanel } from '@/features/tasks/TasksPanel'
import { MeetingsPanel } from '@/features/meetings/MeetingsPanel'
import { TeamPanel } from '@/features/members/TeamPanel'
import { AgentsPanel } from '@/features/agents/AgentsPanel'
import { ActivityPanel } from '@/features/dashboard/ActivityPanel'
import { InboxPanel } from '@/features/inbox/InboxPanel'
import { BibiPanel } from '@/features/bee/BibiPanel'
import { ContextPanel } from '@/features/contexts/ContextPanel'
import { AsyncState } from '@/components/AsyncState'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import type { WorkspacePane } from '@/stores/workspaceStore'

const OfficeScene = lazy(() => import('@/three/OfficeScene'))
const menu = [
  { id: 'overview', label: '설계 현황', icon: LayoutDashboard },
  { id: 'documents', label: '공용 문서함', icon: FileText },
  { id: 'planning', label: '기획 분석', icon: Lightbulb },
  { id: 'changes', label: '변경 제안', icon: GitBranch },
  { id: 'tasks', label: '작업 검증', icon: CheckCheck },
  { id: 'meetings', label: '회의실', icon: Users },
  { id: 'team', label: '팀 관리', icon: Network },
  { id: 'agents', label: '에이전트 연결', icon: Bot },
  { id: 'activity', label: '활동 · 비용', icon: ChartNoAxesCombined },
  { id: 'context', label: '작업 맥락', icon: NotebookPen },
  { id: 'inbox', label: '알림함', icon: Bell },
  { id: 'git', label: 'Git · PR (확장)', icon: GitBranch },
] satisfies { id: WorkspacePane; label: string; icon: typeof FileText }[]

function Workspace({ projectId }: { projectId: string }) {
  const sceneHost = useRef<HTMLElement>(null)
  const focusScene = () =>
    sceneHost.current?.querySelector('canvas')?.focus({ preventScroll: true })
  const session = useSession()
  const navigate = useNavigate()
  const pane = useWorkspaceStore((state) => state.pane)
  const openPane = useWorkspaceStore((state) => state.openPane)
  const viewMode = useWorkspaceStore((state) => state.viewMode)
  const setViewMode = useWorkspaceStore((state) => state.setViewMode)
  const outdoor = useWorkspaceStore((state) => state.outdoor)
  const setOutdoor = useWorkspaceStore((state) => state.setOutdoor)
  const pendingMutations = useIsMutating()
  const query = useQuery({
    queryKey: ['projects', projectId, 'scene'],
    queryFn: async () => {
      const [project, members, agents, tasks] = await Promise.all([
        projectsApi.get(projectId),
        membersApi.list(projectId),
        agentsApi.list(projectId),
        tasksApi.list(projectId, { limit: 100 }),
      ])
      return { project, members, agents, tasks }
    },
  })
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
            className="relative h-[calc(100dvh-4rem)] min-h-[620px] overflow-hidden bg-muted/30"
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
                  openPane(
                    id === 'bibi' || id.includes('reception')
                      ? 'chat'
                      : 'agents',
                    id,
                  )
                }
                onTaskSelect={(id) => openPane('tasks', id)}
                className="absolute inset-0 h-full w-full"
              />
            </Suspense>
            <aside className="pointer-events-none absolute top-4 bottom-4 left-4 z-10 flex max-w-[calc(100%-2rem)] gap-4">
              <nav
                className="pointer-events-auto flex w-14 flex-col items-center gap-1 overflow-y-auto rounded-2xl border bg-card/95 p-2 shadow-lg backdrop-blur sm:w-20"
                aria-label="프로젝트 메뉴"
              >
                <Button
                  variant="ghost"
                  size="icon"
                  className="mb-2 shrink-0"
                  aria-label="나의 도시로"
                  onClick={() => {
                    openPane(null)
                    setOutdoor(false)
                    navigate('/')
                  }}
                >
                  <ChevronLeft />
                </Button>
                {menu.map((item) => (
                  <Button
                    key={item.id}
                    variant={pane === item.id ? 'secondary' : 'ghost'}
                    className="h-auto w-full shrink-0 flex-col gap-1.5 px-1 py-3"
                    aria-label={item.label}
                    onClick={() => openPane(item.id)}
                  >
                    <item.icon className="size-4" />
                    <span className="hidden text-[10px] sm:inline">
                      {item.label}
                    </span>
                  </Button>
                ))}
              </nav>
              <div className="pointer-events-auto flex h-fit max-w-[calc(100vw-7rem)] items-center gap-4 rounded-2xl border bg-card/95 p-4 shadow-sm backdrop-blur">
                <div>
                  <p className="text-[9px] tracking-widest text-muted-foreground">
                    WORKSPACE / 나의 사무실
                  </p>
                  <h2 className="mt-1 max-w-60 truncate text-base font-bold">
                    {data.project.name}
                  </h2>
                </div>
                <Badge variant="outline">
                  {isLeader ? '인간 팀장' : '팀원'}
                </Badge>
              </div>
            </aside>
            <div className="absolute top-4 right-4 z-10 flex gap-2">
              <Button
                size="icon"
                variant="secondary"
                aria-label="알림 열기"
                onClick={() => openPane('inbox')}
              >
                <Bell />
              </Button>
              <Button
                size="icon"
                variant="secondary"
                aria-label="회원 설정"
                asChild
              >
                <Link to="/account">
                  <Settings />
                </Link>
              </Button>
            </div>
            {!pane && (
              <p className="pointer-events-none absolute bottom-6 left-28 z-10 hidden rounded-lg bg-card/90 px-3 py-2 text-[11px] text-muted-foreground lg:block">
                바닥 클릭 · WASD/방향키 이동 · Shift 달리기 · 드래그로 둘러보기
              </p>
            )}
            <div className="absolute right-5 bottom-5 z-10 flex flex-wrap items-center justify-end gap-2">
              <div className="mr-2 rounded-xl border bg-card/95 p-1 shadow-sm">
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
              <Button
                onClick={() => openPane('chat')}
                className="rounded-full px-5 shadow-md"
              >
                <MessageCircle />
                비비와 대화
              </Button>
            </div>
            {!data.project.current_baseline && !pane && (
              <div className="absolute right-5 bottom-24 z-10 max-w-80 rounded-2xl border bg-card/95 p-5 shadow-sm">
                <BookOpen className="mb-3 size-5 text-primary" />
                <h3 className="font-semibold">문서로 시작을 준비해요</h3>
                <p className="mt-2 text-xs leading-6 text-muted-foreground">
                  기획안·ERD·요구사항 명세서·API 명세서를 등록하고 인간 팀장이
                  기준을 발행해요.
                </p>
                <Button
                  className="mt-4"
                  size="sm"
                  onClick={() => openPane('documents')}
                >
                  공용 문서함 열기
                </Button>
              </div>
            )}
            <Dialog
              open={pane !== null}
              onOpenChange={(open) => {
                if (!open && !pendingMutations) openPane(null)
              }}
            >
              <DialogContent
                className="max-h-[87dvh] overflow-y-auto bg-background p-5 sm:max-w-[min(1180px,94vw)] sm:p-8"
                onEscapeKeyDown={(event) => {
                  if (pendingMutations) event.preventDefault()
                }}
                onInteractOutside={(event) => {
                  if (pendingMutations) event.preventDefault()
                }}
              >
                <DialogHeader>
                  <DialogTitle>
                    {pane === 'chat'
                      ? 'AI 팀장 비비'
                      : (menu.find((item) => item.id === pane)?.label ??
                        '프로젝트 업무')}
                  </DialogTitle>
                  <DialogDescription>
                    {data.project.name} · 팀의 설계와 작업 맥락
                  </DialogDescription>
                </DialogHeader>
                <div key={`${projectId}:${pane}`} className="mt-4 min-w-0">
                  {pane === 'overview' && (
                    <OverviewPanel projectId={projectId} />
                  )}
                  {pane === 'documents' && <DocumentsPanel {...panelProps} />}
                  {pane === 'planning' && <PlanningPanel {...panelProps} />}
                  {pane === 'changes' && <ChangesPanel {...panelProps} />}
                  {pane === 'tasks' && <TasksPanel {...panelProps} />}
                  {pane === 'meetings' && <MeetingsPanel {...panelProps} />}
                  {pane === 'team' && <TeamPanel {...panelProps} />}
                  {pane === 'agents' && <AgentsPanel projectId={projectId} />}
                  {pane === 'activity' && <ActivityPanel {...panelProps} />}
                  {pane === 'inbox' && <InboxPanel projectId={projectId} />}
                  {pane === 'chat' && <BibiPanel projectId={projectId} />}
                  {pane === 'context' && <ContextPanel projectId={projectId} />}
                  {pane === 'git' && (
                    <div className="space-y-4 rounded-xl border border-dashed p-6">
                      <GitBranch className="size-8 text-muted-foreground" />
                      <h3 className="font-semibold">
                        Git · PR 연동은 확장 기능이에요
                      </h3>
                      <p className="text-sm leading-7 text-muted-foreground">
                        화면의 진입점은 유지했어요. 현재 BEEZ API 명세에는
                        저장소·PR 조회와 게시의 상세 계약이 없어 실제 연결
                        버튼은 제공하지 않아요.
                      </p>
                    </div>
                  )}
                </div>
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
