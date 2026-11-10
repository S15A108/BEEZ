import { useState } from 'react'
import {
  useInfiniteQuery,
  useMutation,
  useQuery,
  useQueryClient,
  type UseQueryResult,
} from '@tanstack/react-query'
import { useNavigate } from 'react-router'
import { z } from 'zod'
import { Ellipsis, Pencil, TriangleAlert } from 'lucide-react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import type { Invitation, Member, TaskDetail } from '@/api/contracts/common'
import { membersApi, projectsApi } from '@/api/services'
import { ActionForm } from '@/components/ActionForm'
import { AsyncState } from '@/components/AsyncState'
import { EmptyState } from '@/components/EmptyState'
import { FormDialog } from '@/components/FormDialog'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { Toast } from '@/components/Toast'
import { agentStatusLabels } from '@/features/agents/agentStatus'
import type { AgentStatus } from '@/features/agents/agentStatus'
import { useAgentStatusOf } from '@/features/agents/useAgentStatusOf'
import { useSession } from '@/features/auth/useSession'
import { BuildingDesignPicker } from '@/features/projects/BuildingDesignPicker'
import { projectPlacements } from '@/features/projects/placements'
import { openTaskCount, openTasksQuery } from '@/features/tasks/openTasksQuery'
import { OfficeTimeSettings } from '@/features/workspace/OfficeTimeSettings'
import { useProjectAction } from '@/features/workspace/useProjectAction'
import {
  getApiErrorCode,
  getApiErrorMessage,
  getApiErrorStatus,
  getHandoverErrorMessage,
} from '@/api/error'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { formatDate } from '@/lib/formatDate'
import { placementKey, useWorkspaceStore } from '@/stores/workspaceStore'
import type { BuildingDesign } from '@/stores/workspaceStore'
import type { EmailInvitationResult } from './schemas'

type DialogAction = { action: 'edit' | 'remove' | 'transfer'; member: Member }

export type TeamView = 'team' | 'project' | 'remove'

// 팀원 줄의 에이전트 상태 점(캔버스 SCR-06-v2-settings)
const agentDots: Record<AgentStatus, string> = {
  connected: 'bg-green-600',
  recheck: 'bg-[#e0b400]',
  failed: 'bg-[#c10007]',
  disconnected: 'bg-[#c4c4c4]',
  none: 'bg-[#c4c4c4]',
}

export function TeamPanel({
  projectId,
  isLeader,
  view = 'team',
  onViewChange,
}: {
  projectId: string
  isLeader: boolean
  view?: TeamView
  onViewChange?: (view: TeamView) => void
}) {
  const session = useSession()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const action = useProjectAction(projectId)
  const statusOf = useAgentStatusOf(projectId)
  // 팀장 넘기기 진행·결과. 끝나면 가운데 위 토스트를 띄우고 창은 팀원 권한으로 다시 그려진다.
  const [handoff, setHandoff] = useState<{
    busy?: boolean
    error?: string
    /** 넘겨받은 사람 이름 */
    done?: string
  }>({})
  const placements = useWorkspaceStore((state) => state.placements)
  const placeProjects = useWorkspaceStore((state) => state.placeProjects)
  const accountId = session.data?.id ?? ''
  const savedDesign =
    placements[placementKey(accountId, projectId)]?.design ?? 'modern'
  const [pickedDesign, setPickedDesign] = useState<BuildingDesign>()
  const design = pickedDesign ?? savedDesign
  const [dialog, setDialog] = useState<DialogAction | null>(null)
  const project = useQuery({
    queryKey: ['projects', projectId, 'team-project'],
    queryFn: () => projectsApi.get(projectId),
  })
  // 부지는 도시와 같은 규칙(전체 프로젝트 순서, 저장된 부지 먼저)으로 정한다. 이 프로젝트만 넘기면
  // 다른 프로젝트가 쓰는 0번 부지를 골라 덮어썼다(PR #72 전수 리뷰 AMP-04).
  const cityProjects = useQuery({
    queryKey: ['projects', 'placement-ids'],
    queryFn: () => projectsApi.list({ limit: 100 }),
    enabled: view === 'project' && isLeader,
  })
  const assigned = cityProjects.data
    ? projectPlacements(
        cityProjects.data.items.map((item) => item.id),
        accountId,
        placements,
      )
    : []
  const lot = cityProjects.data
    ? assigned.find((item) => item.id === projectId)?.lot
    : placements[placementKey(accountId, projectId)]?.lot
  // 부지가 없으면 디자인을 저장할 자리가 없으니 바뀐 것으로 치지 않는다.
  const designChanged = typeof lot === 'number' && design !== savedDesign
  const members = useInfiniteQuery({
    // 다른 창은 같은 팀원 목록을 useQuery로 ['projects', id, 'members']에 담는다. 모양이 달라 키를 나눈다.
    queryKey: ['projects', projectId, 'team-members'],
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) =>
      membersApi.list(projectId, { limit: 100, cursor: pageParam }),
    getNextPageParam: (page) => (page.has_more ? page.next_cursor : undefined),
  })
  const invitations = useInfiniteQuery({
    queryKey: ['projects', projectId, 'invitations'],
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) =>
      membersApi.invitations(projectId, { cursor: pageParam }),
    getNextPageParam: (page) => (page.has_more ? page.next_cursor : undefined),
    enabled: isLeader && view === 'team',
  })
  // 메일 다시 보내기(MEM-A11)는 새 링크로 보내 이전 링크를 막는다. 메일을 못 보내면(503) 토큰이 그대로라 이전 링크가 산다.
  // PR #146 리뷰: 보내는 줄의 버튼만 끄고, 결과 문구에 어느 이메일 건인지 붙인다.
  const resend = useMutation({
    mutationFn: (invitation: Invitation) =>
      membersApi.resendInvitation(projectId, invitation.id),
    onSettled: () =>
      queryClient.invalidateQueries({
        queryKey: ['projects', projectId, 'invitations'],
      }),
  })
  // 서버는 최근 들어온 순(PR #50 MEM-A06)이다. 팀장을 맨 위, 다음은 나, 나머지는 서버 순서로 그린다.
  const teamOrder = (member: Member) =>
    member.role === 'LEADER'
      ? 0
      : member.account_id === session.data?.id
        ? 1
        : 2
  const people = (
    members.data?.pages.flatMap((page) => page.items) ?? []
  ).toSorted((a, b) => teamOrder(a) - teamOrder(b))
  const removing =
    typeof dialog === 'object' && dialog?.action === 'remove'
      ? dialog.member
      : undefined
  const otherMembers = people.filter(
    (member) => member.account_id !== session.data?.id,
  )
  // 추방 대상이나 나가는 나의 열린 작업(11-04 사용자 결정). 막지 않고 경고만 보이며, 넘겨받을 사람을 고르지 않으면
  // 서버가 담당자만 비운다. 수는 TSK-A02를 담당자와 열린 상태로 걸러 한 번 불러 센다(MEM-A10 처리 규칙).
  const leaverId =
    removing?.id ??
    (view === 'remove'
      ? people.find((member) => member.account_id === session.data?.id)?.id
      : undefined)
  const openTasks = useQuery({
    ...openTasksQuery(projectId, { assignee_member_id: leaverId ?? '' }),
    enabled: !!leaverId,
  })
  // 수를 모르면(조회 실패) 고를 칸은 그대로 둔다. 고르지 않아도 나갈 수 있다.
  const hasOpenTasks = openTasks.isError || !!openTasks.data?.items.length
  // 빈 칸을 먼저 두어 첫 동료에게 저절로 넘기지 않는다. 기본은 '담당자 비우기'다(캔버스 SCR-06-v2-settings-member).
  const successorOptions = (candidates: Member[], empty: string) => [
    { value: '', label: empty },
    ...candidates.map((member) => ({
      value: member.id,
      label: `${member.name}${member.role === 'LEADER' ? ' (팀장)' : ''}`,
    })),
  ]
  // 제거·나가기 뒤에는 바로 도시로 간다. 이 프로젝트를 다시 부르면 404·403이라 캐시에서 지우고, 목록만 뒤에서 새로 고친다.
  const leaveProject = () => {
    navigate('/')
    queryClient.removeQueries({ queryKey: ['projects', projectId] })
    void queryClient.invalidateQueries({ queryKey: ['projects'] })
  }
  // 성공하면 이 프로젝트를 다시 부르지 않도록 action.run 없이 부른다. 409(leader_transfer_required·invalid_transfer_target)와
  // 403(이미 위임해 팀장이 아님, PR #170)은 그사이 역할이나 팀원이 바뀐 것이라 다시 불러오고, 이유는 폼이 보이도록 오류를 다시 던진다.
  const leave = async (input: Parameters<typeof membersApi.leave>[1]) => {
    try {
      await membersApi.leave(projectId, input)
    } catch (cause) {
      const status = getApiErrorStatus(cause)
      if (status === 403 || status === 409)
        await queryClient.invalidateQueries({
          queryKey: ['projects', projectId],
        })
      throw cause
    }
    leaveProject()
  }
  const perform = (operation: () => Promise<unknown>, message: string) => {
    void action.run(operation, message).catch(() => undefined)
  }
  const title =
    view === 'project'
      ? '프로젝트 관리'
      : view === 'remove'
        ? // S15P31A108-377: 팀장도 팀장을 넘기고 나갈 수 있어(MEM-A10) 제목은 '프로젝트 나가기'다. 삭제는 창 안의 다른 길이다.
          '프로젝트 나가기'
        : '팀 관리'
  return (
    <section className="space-y-6">
      <header className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h2 className="text-xl font-bold">{title}</h2>
          {view === 'team' && (
            <p className="mt-1 text-sm text-muted-foreground">
              {isLeader
                ? '권한은 인간 팀장과 팀원 두 가지예요. 역할 수정, 팀장 위임, 추방은 팀원 줄의 ⋯에서 해요.'
                : `권한은 인간 팀장과 팀원 두 가지예요. ${handoff.done ? `${handoff.done}님이 팀장이 됐어요. ` : ''}팀원은 팀을 보기만 할 수 있어요.`}
            </p>
          )}
        </div>
      </header>
      {view === 'team' && isLeader && <InviteForm projectId={projectId} />}
      {/* 결과는 화면 안 글 대신 오른쪽 위 토스트로 잠깐 보여 준다. */}
      {action.message && (
        <Toast>
          <span className={view === 'project' ? 'text-green-700' : undefined}>
            {action.message}
          </span>
        </Toast>
      )}
      {handoff.done && (
        <Toast tone="success">
          <strong className="block">팀장을 넘겼어요</strong>
          <span className="text-muted-foreground">
            {handoff.done}님이 이제 팀장이에요. 나는 팀원이 됐어요.
          </span>
        </Toast>
      )}
      {action.error && (
        <p
          role="alert"
          className="rounded-lg bg-destructive/5 p-3 text-sm text-destructive"
        >
          {action.error}
        </p>
      )}
      {view === 'team' && (
        <AsyncState query={members}>
          {(data) => (
            <>
              <ul className="divide-y border-y">
                {data.pages
                  .flatMap((page) => page.items)
                  .toSorted((a, b) => teamOrder(a) - teamOrder(b))
                  .map((member) => {
                    const mine = member.account_id === session.data?.id
                    return (
                      <li
                        key={member.id}
                        className="grid grid-cols-[minmax(0,1.4fr)_minmax(0,1fr)_minmax(0,1.2fr)_auto] items-center gap-4 px-1 py-3.5"
                      >
                        <span className="flex min-w-0 items-center gap-2.5">
                          <span
                            aria-hidden="true"
                            className="grid size-8 shrink-0 place-items-center rounded-full text-xs font-bold"
                            style={{
                              backgroundColor: `${member.color ?? '#e5b743'}25`,
                              color: member.color ?? '#a98419',
                            }}
                          >
                            {member.name.slice(0, 1)}
                          </span>
                          <span className="grid">
                            <strong className="text-sm font-semibold">
                              {member.name}
                              {mine ? ' · 나' : ''}
                            </strong>
                            <span className="text-xs text-muted-foreground">
                              {member.role === 'LEADER' ? '인간 팀장' : '팀원'}
                              {member.role_label
                                ? ` · ${member.role_label}`
                                : ''}
                            </span>
                          </span>
                        </span>
                        <span
                          className={
                            member.domain
                              ? 'text-sm'
                              : 'text-sm text-muted-foreground'
                          }
                        >
                          {member.domain ?? '담당 도메인 미등록'}
                        </span>
                        <span className="inline-flex items-center gap-1.5 text-xs">
                          <span
                            aria-hidden="true"
                            className={`size-1.5 rounded-full ${agentDots[statusOf(member).status]}`}
                          />
                          에이전트 {agentStatusLabels[statusOf(member).status]}
                        </span>
                        <span className="flex justify-end">
                          {isLeader && !mine && member.role !== 'LEADER' ? (
                            <DropdownMenu>
                              <DropdownMenuTrigger asChild>
                                <Button
                                  variant="outline"
                                  size="icon-sm"
                                  aria-label={`${member.name} 더보기`}
                                  disabled={action.busy}
                                >
                                  <Ellipsis />
                                </Button>
                              </DropdownMenuTrigger>
                              <DropdownMenuContent align="end">
                                <DropdownMenuItem
                                  onSelect={() =>
                                    setDialog({ action: 'edit', member })
                                  }
                                >
                                  역할 수정
                                </DropdownMenuItem>
                                <DropdownMenuItem
                                  onSelect={() =>
                                    setDialog({ action: 'transfer', member })
                                  }
                                >
                                  팀장으로 위임
                                </DropdownMenuItem>
                                <DropdownMenuSeparator />
                                <DropdownMenuItem
                                  variant="destructive"
                                  onSelect={() =>
                                    setDialog({ action: 'remove', member })
                                  }
                                >
                                  추방
                                </DropdownMenuItem>
                              </DropdownMenuContent>
                            </DropdownMenu>
                          ) : (
                            // 역할·담당 영역은 서버가 팀장만 고치게 한다(MEM-A07, PR #72 재전수 SECOND-AMP-01).
                            isLeader && (
                              <Button
                                variant="outline"
                                size="sm"
                                disabled={action.busy}
                                onClick={() =>
                                  setDialog({ action: 'edit', member })
                                }
                              >
                                <Pencil />
                                역할·담당 도메인
                              </Button>
                            )
                          )}
                        </span>
                      </li>
                    )
                  })}
              </ul>
              {members.hasNextPage && (
                <Button
                  variant="outline"
                  disabled={members.isFetchingNextPage}
                  onClick={() => void members.fetchNextPage()}
                >
                  팀원 더 보기
                </Button>
              )}
            </>
          )}
        </AsyncState>
      )}
      {/* 초대가 하나도 없다고 확인되면 '초대 내역' 칸을 숨긴다(캔버스 SCR-06-v2-settings). 목록을 불러오는 중이거나
          실패했으면 칸을 두고 AsyncState로 오류·다시 시도를 보인다. */}
      {view === 'team' &&
        isLeader &&
        (!invitations.isSuccess ||
          invitations.data.pages.some((page) => page.items.length)) && (
          <div className="space-y-3">
            {/* 참여함·취소됨 초대도 함께 보이므로 칸 이름은 '초대 내역'이다. 아직 참여 전인 줄에는 '초대 중' 배지가 붙는다(S15P31A108-346). */}
            <h3 className="text-sm font-semibold">초대 내역</h3>
            {resend.isSuccess && (
              <Toast key={resend.submittedAt}>
                {`${resend.variables.invited_email} · 초대 메일을 다시 보냈어요. 이전 링크는 이제 열리지 않아요.`}
              </Toast>
            )}
            {resend.isError && (
              <p
                role="alert"
                className="rounded-lg bg-destructive/5 p-3 text-sm text-destructive"
              >
                {`${resend.variables.invited_email} · ${
                  getApiErrorCode(resend.error) === 'mail_send_failed'
                    ? '초대 메일을 보내지 못했어요. 이전에 보낸 링크는 그대로 쓸 수 있어요.'
                    : getApiErrorMessage(resend.error)
                }`}
              </p>
            )}
            <AsyncState query={invitations}>
              {(data) => (
                <>
                  {data.pages.flatMap((page) => page.items).length ? (
                    <ul className="divide-y">
                      {data.pages
                        .flatMap((page) => page.items)
                        .map((invitation) => (
                          <li
                            key={invitation.id}
                            className="flex flex-wrap items-center justify-between gap-3 py-3 text-sm"
                          >
                            <span>
                              <b className="font-medium">
                                {invitation.invited_email ?? '초대 링크'}
                              </b>
                              {!invitation.accepted_at &&
                                !invitation.revoked_at && (
                                  <span className="ml-2 rounded-full bg-primary/20 px-2 py-0.5 text-xs font-semibold">
                                    초대 중
                                  </span>
                                )}
                              <small className="mt-1 block text-muted-foreground">
                                {/* Q05(2026-10-29 확정): 초대받은 사람이 로그인·가입해야 팀원이 된다. 만료는 없다. */}
                                {invitation.accepted_at
                                  ? `참여함 · ${formatDate(invitation.accepted_at)}`
                                  : invitation.revoked_at
                                    ? '취소됨'
                                    : '같은 이메일로 로그인하거나 가입하면 팀원이 돼요'}
                              </small>
                            </span>
                            {/* 이메일 없는 링크 초대(기존 호환)는 보낼 곳이 없어 서버가 404로 답한다. */}
                            {invitation.invited_email &&
                              !invitation.revoked_at &&
                              !invitation.accepted_at && (
                                <Button
                                  variant="ghost"
                                  size="sm"
                                  disabled={
                                    resend.isPending &&
                                    resend.variables.id === invitation.id
                                  }
                                  onClick={() => resend.mutate(invitation)}
                                >
                                  메일 다시 보내기
                                </Button>
                              )}
                            {!invitation.revoked_at &&
                              !invitation.accepted_at && (
                                <Button
                                  variant="outline"
                                  size="sm"
                                  disabled={action.busy}
                                  onClick={() =>
                                    perform(
                                      () =>
                                        membersApi.revokeInvitation(
                                          projectId,
                                          invitation.id,
                                        ),
                                      '초대를 취소했어요.',
                                    )
                                  }
                                >
                                  초대 취소
                                </Button>
                              )}
                          </li>
                        ))}
                    </ul>
                  ) : (
                    <EmptyState>보낸 초대가 없어요.</EmptyState>
                  )}
                  {invitations.hasNextPage && (
                    <Button
                      variant="outline"
                      onClick={() => void invitations.fetchNextPage()}
                    >
                      초대 더 보기
                    </Button>
                  )}
                </>
              )}
            </AsyncState>
          </div>
        )}
      {/* S15P31A108-376: 프로젝트 관리 한 페이지. 위는 이름·소개·건물(PRJ-A04, 팀장만), 아래는 사무실 시간대(이 브라우저), 맨 아래는 나가기. */}
      {view === 'project' && (
        <AsyncState query={project}>
          {(data) => (
            <div className="space-y-8">
              {isLeader ? (
                <div className="space-y-6">
                  {/* API 없음: 건물 디자인은 이 브라우저에만 저장된다. Project에 design 필드가 생기면 수정 요청에 넣는다. */}
                  <BuildingDesignPicker
                    value={design}
                    onChange={setPickedDesign}
                    small
                    note={
                      typeof lot === 'number' && (
                        <p className="grid justify-items-center gap-1 text-center text-xs leading-[17px] text-[#6b6658]">
                          <span className="inline-flex h-[22px] items-center rounded-full bg-white px-2 font-semibold text-foreground">
                            지금 부지 {lot + 1}
                          </span>
                          자리는 그대로예요. 건물 디자인은 이 브라우저에만
                          저장돼요.
                        </p>
                      )
                    }
                  />
                  <ActionForm
                    // 서버 값이 바뀌면 폼을 그 값으로 다시 채운다. 다른 곳에서 고친 사이 쓰던 내용은 사라진다.
                    key={`${data.name}\n${data.description ?? ''}`}
                    // 새로 그려진 폼은 제출 중 상태를 모르므로, 저장 처리가 끝날 때까지 끈다(PR #199 리뷰).
                    disabled={action.busy}
                    disableWhen={(values) =>
                      !designChanged && !projectInfoChanged(values, data)
                    }
                    fields={[
                      {
                        name: 'name',
                        label: '프로젝트 이름',
                        required: true,
                        max: 40,
                        counter: true,
                        value: data.name,
                      },
                      {
                        name: 'description',
                        label: '프로젝트 소개',
                        type: 'textarea',
                        column: 2,
                        max: 300,
                        counter: true,
                        value: data.description,
                      },
                    ]}
                    // 설정 창 높이(680)를 지키려고 이름·소개를 한 줄에 나란히 둔다(SCR-06-v2-settings-project 11-02).
                    columns
                    submitLabel="저장"
                    onSubmit={async (values) => {
                      await action.run(
                        // 건물 디자인은 이 브라우저에만 저장하므로, 이름·소개가 그대로면 서버에 보내지 않는다.
                        async () => {
                          if (projectInfoChanged(values, data))
                            await projectsApi.update(projectId, {
                              name: values.name,
                              description: values.description,
                            })
                        },
                        '프로젝트 정보를 저장했어요.',
                      )
                      // 도시 부지 12칸 밖(13번째 이후) 프로젝트는 3D 도시에 없어 디자인을 둘 자리가 없다.
                      // 도시와 같이 지금 보이는 칸을 함께 저장해, 당겨진 칸 번호로 다른 프로젝트와 순서가 뒤바뀌지 않는다(S15P31A108-256).
                      if (typeof lot === 'number')
                        placeProjects(accountId, [
                          ...assigned,
                          { id: projectId, lot, design },
                        ])
                      await queryClient.invalidateQueries({
                        queryKey: ['projects'],
                      })
                    }}
                  />
                </div>
              ) : (
                // PRJ-A04는 팀장만 고친다. 팀원은 보기만 하고, 건물 디자인은 팀장 브라우저의 설정이라 보이지 않는다.
                <div className="space-y-3">
                  <dl className="grid gap-3 text-sm">
                    <div className="grid gap-1">
                      <dt className="font-medium">프로젝트 이름</dt>
                      <dd>{data.name}</dd>
                    </div>
                    <div className="grid gap-1">
                      <dt className="font-medium">프로젝트 소개</dt>
                      <dd className="text-muted-foreground">
                        {data.description || '소개가 없어요.'}
                      </dd>
                    </div>
                  </dl>
                  <p className="text-xs text-muted-foreground">
                    프로젝트 이름·소개·건물은 팀장만 바꿀 수 있어요.
                  </p>
                </div>
              )}
              <OfficeTimeSettings />
              <div className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-destructive/30 p-4">
                <span className="text-sm">
                  <strong className="block">프로젝트 나가기</strong>
                  {/* S15P31A108-377: 팀장도 '프로젝트 나가기'로 들어가 위임하고 나가기와 삭제(PRJ-A05) 중 고른다(MEM-A10 처리 규칙). */}
                  <span className="text-muted-foreground">
                    {isLeader
                      ? '팀장을 넘기고 나가거나, 이름을 입력해 프로젝트를 삭제할 수 있어요.'
                      : '나가면 이 프로젝트의 문서와 작업을 더 볼 수 없어요.'}
                  </span>
                </span>
                <Button
                  variant="destructive"
                  onClick={() => onViewChange?.('remove')}
                >
                  프로젝트 나가기
                </Button>
              </div>
            </div>
          )}
        </AsyncState>
      )}
      {view === 'remove' && (
        <AsyncState query={project}>
          {(data) => {
            const confirmName = {
              name: 'confirm_name',
              label: '프로젝트 이름 입력',
              required: true,
              placeholder: data.name,
              check: (value: string) =>
                value === data.name
                  ? undefined
                  : '프로젝트 이름이 맞지 않아요.',
            }
            // 열린 작업을 넘겨받을 사람. 고르지 않으면 서버가 담당자만 비운다(MEM-A10 reassign_to_member_id).
            const reassign = hasOpenTasks
              ? [
                  {
                    name: 'reassign_to_member_id',
                    label: '넘겨받을 사람 선택',
                    type: 'select' as const,
                    options: successorOptions(
                      otherMembers,
                      '고르지 않음 · 담당자 비우기',
                    ),
                  },
                ]
              : []
            // action.run은 성공 뒤 이 프로젝트를 다시 불러 '접근 권한' 오류가 뜨므로 직접 부른다. 실패하면 폼이 이유를 보여 준다.
            const deleteForm = (
              <ActionForm
                fields={[confirmName]}
                destructive
                submitLabel="프로젝트 삭제"
                onSubmit={async (values) => {
                  await projectsApi.remove(projectId, {
                    confirm_name: values.confirm_name,
                  })
                  leaveProject()
                }}
              />
            )
            return (
              // 팀원 수를 모른 채(조회 중·실패) 고르면 팀 프로젝트를 혼자 쓰는 프로젝트로 볼 수 있어, 팀원 조회가 성공한 뒤에만 그린다.
              <AsyncState query={members}>
                {() =>
                  !isLeader ? (
                    <div className="space-y-4">
                      <LeaveWarning query={openTasks} />
                      <LeaveNotes />
                      <ActionForm
                        // 열린 작업을 늦게 알게 되면 칸이 바뀌므로 폼을 새로 그린다.
                        key={hasOpenTasks ? 'handover' : 'plain'}
                        fields={[...reassign, confirmName]}
                        destructive
                        submitLabel="프로젝트 나가기"
                        errorMessage={getHandoverErrorMessage}
                        onSubmit={(values) =>
                          leave({
                            reassign_to_member_id:
                              values.reassign_to_member_id || undefined,
                          })
                        }
                      />
                    </div>
                  ) : otherMembers.length ? (
                    // 11-04 사용자 결정: 팀장의 나가기 창은 두 길이다. 나가기와 삭제를 섞지 않는다(MEM-A10, PR #113 2차 리뷰).
                    <div className="space-y-8">
                      <section
                        aria-label="위임하고 나가기"
                        className="space-y-4"
                      >
                        <h3 className="text-base font-semibold">
                          위임하고 나가기
                        </h3>
                        <p className="text-sm text-muted-foreground">
                          팀장을 넘겨받을 팀원을 고르면 위임과 나가기를 한 번에
                          해요. 프로젝트는 남은 팀원에게 그대로 남아요.
                        </p>
                        <LeaveWarning query={openTasks} />
                        <LeaveNotes />
                        <ActionForm
                          key={hasOpenTasks ? 'handover' : 'plain'}
                          fields={[
                            {
                              name: 'transfer_leader_to_member_id',
                              label: '팀장을 넘겨받을 팀원',
                              type: 'select',
                              required: true,
                              options: successorOptions(
                                otherMembers,
                                '팀원 고르기',
                              ),
                            },
                            ...reassign,
                          ]}
                          destructive
                          submitLabel="위임하고 나가기"
                          errorMessage={getHandoverErrorMessage}
                          onSubmit={(values) =>
                            leave({
                              transfer_leader_to_member_id:
                                values.transfer_leader_to_member_id,
                              reassign_to_member_id:
                                values.reassign_to_member_id || undefined,
                            })
                          }
                        />
                      </section>
                      <section
                        aria-label="프로젝트 삭제"
                        className="space-y-4 border-t pt-6"
                      >
                        <h3 className="text-base font-semibold">
                          프로젝트 삭제
                        </h3>
                        {/* PRJ-A05 처리 규칙: 삭제 확인 창에 남은 팀원 수를 보이고 그 팀원들도 더 쓸 수 없다고 알린다. 삭제 알림은 없다. */}
                        <p className="rounded-xl border border-destructive/30 bg-destructive/5 p-4 text-sm">
                          프로젝트를 지우면 팀원 {otherMembers.length}명도 이
                          프로젝트를 더 쓸 수 없어요. 권한은 모두 해제되고
                          기록은 보관돼요.
                        </p>
                        {deleteForm}
                      </section>
                    </div>
                  ) : (
                    // 팀장은 넘길 팀원이 없으면 나갈 수 없다(MEM-A10 leader_transfer_required). 나가는 방법을 알리고 삭제만 보인다.
                    <div className="space-y-4">
                      <p className="text-sm text-muted-foreground">
                        팀장을 넘길 팀원이 없어 지금은 나갈 수 없어요. 팀
                        관리에서 팀원을 초대하고 팀장을 넘기면 나갈 수 있어요.
                      </p>
                      <section
                        aria-label="프로젝트 삭제"
                        className="space-y-4 border-t pt-6"
                      >
                        <h3 className="text-base font-semibold">
                          프로젝트 삭제
                        </h3>
                        <p className="rounded-xl border border-destructive/30 bg-destructive/5 p-4 text-sm">
                          혼자 쓰는 프로젝트예요. 삭제하면 문서와 작업에 다시
                          들어올 수 없어요.
                        </p>
                        {deleteForm}
                      </section>
                    </div>
                  )
                }
              </AsyncState>
            )
          }}
        </AsyncState>
      )}
      {typeof dialog === 'object' && dialog?.action === 'transfer' && (
        // 작은 확인 창(사용자 결정 10-30). 오류는 invalid_transfer_target 등 서버 문구를 그대로 보인다.
        <Dialog
          open
          onOpenChange={(open) => {
            if (!open && !handoff.busy) {
              setHandoff({})
              setDialog(null)
            }
          }}
        >
          <DialogContent className="sm:max-w-[420px]">
            <DialogHeader>
              <DialogTitle>
                {dialog.member.name}님에게 팀장을 넘길까요?
              </DialogTitle>
              <DialogDescription>
                넘기면 나는 팀원이 되고 {dialog.member.name}님이 팀장이 돼요.
                프로젝트 설정과 승인 권한도 함께 넘어가요.
              </DialogDescription>
            </DialogHeader>
            {handoff.error && (
              <p role="alert" className="text-sm text-destructive">
                {handoff.error}
              </p>
            )}
            <DialogFooter>
              <Button
                variant="outline"
                disabled={handoff.busy}
                onClick={() => {
                  setHandoff({})
                  setDialog(null)
                }}
              >
                취소
              </Button>
              <Button
                disabled={handoff.busy}
                onClick={async () => {
                  setHandoff({ busy: true })
                  try {
                    await membersApi.transfer(projectId, {
                      target_member_id: dialog.member.id,
                    })
                    setHandoff({ done: dialog.member.name })
                    setDialog(null)
                    await queryClient.invalidateQueries({
                      queryKey: ['projects'],
                    })
                  } catch (cause) {
                    setHandoff({ error: getApiErrorMessage(cause) })
                  }
                }}
              >
                팀장 넘기기
              </Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>
      )}
      {typeof dialog === 'object' && dialog?.action === 'edit' && (
        <FormDialog
          back="팀 관리"
          title={`${dialog.member.name} · 역할 수정`}
          description="비운 칸은 지워져요."
          fields={[
            {
              name: 'role_label',
              label: '표시 역할',
              value: dialog.member.role_label,
              max: 50,
              hint: roleLabelHint,
            },
            {
              name: 'domain',
              label: '담당 도메인',
              value: dialog.member.domain,
              max: 50,
              hint: domainHint,
            },
            {
              name: 'color',
              label: '표시 색',
              type: 'color',
              value: dialog.member.color ?? '#f3c530',
            },
          ]}
          onClose={() => setDialog(null)}
          onSubmit={async (values) => {
            await action.run(
              // 비운 칸은 ""로 보내 지운다(PR #50). 앞뒤 공백은 스키마가 뺀다.
              () =>
                membersApi.update(projectId, dialog.member.id, {
                  role_label: values.role_label,
                  domain: values.domain,
                  color: values.color,
                }),
              '역할과 담당 도메인을 저장했어요.',
            )
            setDialog(null)
          }}
        />
      )}
      {typeof dialog === 'object' && dialog?.action === 'remove' && (
        <FormDialog
          back="팀 관리"
          title={`${dialog.member.name} 추방`}
          description="프로젝트 접근과 토큰이 바로 해제돼요. 맡은 열린 작업은 넘겨받을 동료를 고르지 않으면 담당자만 비워져요."
          fields={[
            {
              name: 'reassign_to_member_id',
              label: '작업을 넘겨받을 동료 선택',
              type: 'select',
              options: successorOptions(
                people.filter((member) => member.id !== dialog.member.id),
                '고르지 않음 · 담당자 비우기',
              ),
            },
          ]}
          submitLabel="추방"
          destructive
          errorMessage={getHandoverErrorMessage}
          onClose={() => setDialog(null)}
          onSubmit={async (values) => {
            await action.run(
              () =>
                membersApi.remove(projectId, dialog.member.id, {
                  reassign_to_member_id:
                    values.reassign_to_member_id || undefined,
                }),
              '팀원을 추방했어요.',
            )
            setDialog(null)
          }}
        >
          {/* 맡은 열린 작업을 목록으로 보여 주고 넘겨받을 동료를 고른다(L9, SCR-06-v2-settings-remove). */}
          <HandoverTasks name={dialog.member.name} query={openTasks} />
        </FormDialog>
      )}
    </section>
  )
}

// 표시 역할·담당 도메인 설명(S15P31A108-378). 팀원 한 명을 고치는 수정 화면의 두 칸 아래에만 둔다(사용자 요청 11-10).
// 표시 역할은 팀 목록의 이름 아래에, 담당 도메인은 팀 목록과 사무실 HUD 팀원 목록에 보인다. 3D 이름표에는 나오지 않는다(office-actors.ts).
const roleLabelHint =
  '팀에서 맡은 역할 이름이에요(예: 프론트엔드). 팀 목록의 이름 아래에 보여요.'
const domainHint =
  '주로 맡는 기능이에요(예: 게시판 작업). 팀 목록과 사무실 팀원 목록에 보여요.'

// 프로젝트 이름·소개가 서버 값과 다른지. 앞뒤 공백은 서버가 빼므로(PRJ-A04) 바뀐 것으로 치지 않는다.
function projectInfoChanged(
  values: Record<string, string | undefined>,
  project: { name: string; description?: string },
) {
  return (
    (values.name ?? '').trim() !== project.name.trim() ||
    (values.description ?? '').trim() !== (project.description ?? '').trim()
  )
}

const splitEmails = (value: string) =>
  value
    .split(/[\s,]+/)
    .map((email) => email.trim())
    .filter(Boolean)
const inviteSchema = z.object({
  emails: z
    .string()
    .trim()
    .min(1, '초대할 이메일을 입력해 주세요.')
    .refine(
      (value) =>
        splitEmails(value).every((email) => z.email().safeParse(email).success),
      '이메일 형식을 확인해 주세요.',
    )
    // MEM-A01 emails는 1~20개다(PR #134).
    .refine(
      (value) => splitEmails(value).length <= 20,
      '한 번에 20명까지 초대할 수 있어요.',
    ),
  message: z.string().trim().max(500, '함께 보낼 말은 500자까지 쓸 수 있어요.'),
})

type OpenTasks = UseQueryResult<{ items: TaskDetail[]; has_more: boolean }>

// 추방에서 그 팀원이 맡은 열린 작업 목록(SCR-06-v2-settings-remove)
function HandoverTasks({ name, query }: { name: string; query: OpenTasks }) {
  return (
    <section aria-label="넘길 작업" className="grid gap-1.5 text-sm">
      <AsyncState query={query}>
        {(page) => (
          <>
            <p className="font-semibold">
              {name}님이 맡은 열린 작업 {openTaskCount(page)}
            </p>
            {page.items.length > 0 && (
              <ul className="grid gap-1">
                {page.items.map((task) => (
                  <li key={task.id} className="rounded-lg border px-3 py-2">
                    {task.key} · {task.current_plan_revision.plan.title}
                  </li>
                ))}
              </ul>
            )}
          </>
        )}
      </AsyncState>
      <p className="text-xs text-muted-foreground">
        넘기면 진행 중인 작업은 대기(재검증)로 바뀌고 넘겨받은 동료가 다시
        검증받아요. 고르지 않으면 상태는 그대로 두고 담당자만 비워요.
      </p>
    </section>
  )
}

// 나가기 전 내가 맡은 열린 작업 경고(SCR-06-v2-settings-member). 막지 않고 알리기만 한다.
function LeaveWarning({ query }: { query: OpenTasks }) {
  if (query.isError)
    return (
      <p className="text-sm text-muted-foreground">
        맡은 열린 작업을 확인하지 못했어요. 넘겨받을 사람을 고르지 않으면 열린
        작업의 담당자가 비워져요.
      </p>
    )
  if (!query.data?.items.length) return null
  return (
    <div className="flex gap-2.5 rounded-xl border border-primary/50 bg-primary/10 p-4 text-sm">
      <TriangleAlert className="mt-0.5 size-4 shrink-0 text-amber-700" />
      <div>
        <strong className="font-semibold">
          {`내가 맡은 열린 작업이 ${openTaskCount(query.data)} 있어요`}
        </strong>
        <p className="mt-0.5 text-muted-foreground">
          나가면 이 작업들의 담당자가 비워져요. 팀장이 배정하거나 다른 팀원이
          맡을 때까지 아무도 이어서 진행할 수 없어요. 넘겨받을 사람을 고르면
          바로 넘어가요.
        </p>
      </div>
    </div>
  )
}

function LeaveNotes() {
  return (
    <ul className="grid list-inside list-disc gap-1 text-sm text-muted-foreground">
      <li>나가면 이 프로젝트의 문서와 작업을 더 볼 수 없어요.</li>
      <li>이 프로젝트용 에이전트 토큰은 바로 쓸 수 없게 돼요.</li>
      <li>다시 들어오려면 팀장에게 새 초대를 받고 메일 링크를 열어야 해요.</li>
    </ul>
  )
}

// PR #146 리뷰: 서버는 초대를 저장한 뒤 메일을 따로 보내서, 발송이 실패해도 INVITED로 답한다. 그래서 "보냈어요"라고 하지 않는다.
// 첫 메일이 조용히 실패한 사람을 다시 초대하면 ALREADY_INVITED가 오므로, 다시 보낼 곳을 알려 준다.
const inviteResultLabels: Record<EmailInvitationResult['status'], string> = {
  INVITED: '초대했어요',
  ALREADY_INVITED: '이미 초대 중이에요. 아래에서 메일을 다시 보낼 수 있어요',
  ALREADY_MEMBER: '이미 팀원이에요',
}

// 팀 목록 위 초대 칸(노션처럼). 쉼표나 줄바꿈으로 여러 명을 넣고 한 번에 초대 메일을 보낸다(MEM-A01 emails, PR #134).
// 이미 팀원인지·초대 중인지는 서버가 이메일마다 알려 준다. 보내면 칸을 비우고, 요청이 실패하면 칸을 그대로 둔다.
function InviteForm({ projectId }: { projectId: string }) {
  const queryClient = useQueryClient()
  // 결과는 mutation이 들고 있다. 다시 보내면 비워지고, 실패하면 남지 않는다.
  const invite = useMutation({
    mutationFn: (values: z.infer<typeof inviteSchema>) =>
      membersApi.createEmailInvitations(projectId, {
        emails: splitEmails(values.emails),
        message: values.message || undefined,
      }),
    onSuccess: () =>
      queryClient.invalidateQueries({
        queryKey: ['projects', projectId, 'invitations'],
      }),
  })
  const results = invite.data?.results ?? []
  const {
    register,
    handleSubmit,
    reset,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<z.infer<typeof inviteSchema>>({
    resolver: zodResolver(inviteSchema),
    defaultValues: { emails: '', message: '' },
  })
  const error = errors.emails ?? errors.message ?? errors.root
  return (
    <form
      noValidate
      aria-label="팀원 초대"
      className="grid gap-1.5"
      onSubmit={handleSubmit(async (values) => {
        try {
          await invite.mutateAsync(values)
          reset()
        } catch (cause) {
          setError('root', { message: getApiErrorMessage(cause) })
        }
      })}
    >
      <span className="flex gap-2">
        <Input
          inputMode="email"
          aria-label="초대할 이메일"
          placeholder="이메일을 입력하세요 · 쉼표로 여러 명"
          aria-invalid={!!errors.emails}
          disabled={isSubmitting}
          {...register('emails')}
        />
        <Button type="submit" disabled={isSubmitting}>
          초대
        </Button>
      </span>
      <Textarea
        aria-label="함께 보낼 말"
        placeholder="함께 보낼 말 (선택) · 초대 메일에 넣어요"
        rows={2}
        aria-invalid={!!errors.message}
        disabled={isSubmitting}
        {...register('message')}
      />
      {error ? (
        <p role="alert" className="text-xs text-destructive">
          {error.message}
        </p>
      ) : (
        <span className="text-xs text-muted-foreground">
          받은 사람이 같은 이메일로 로그인하거나 가입하면 팀원이 돼요. 들어오기
          전까지는 &apos;초대 중&apos;으로 보여요.
        </span>
      )}
      {results.length > 0 && (
        <ul aria-label="초대 결과" className="grid gap-0.5 text-xs">
          {results.map((result) => (
            <li key={result.email}>
              {result.email} · {inviteResultLabels[result.status]}
            </li>
          ))}
        </ul>
      )}
    </form>
  )
}
