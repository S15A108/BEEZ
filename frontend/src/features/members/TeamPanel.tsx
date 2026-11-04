import { useState } from 'react'
import {
  useInfiniteQuery,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query'
import { useNavigate } from 'react-router'
import { z } from 'zod'
import { Copy, Ellipsis, Pencil, TriangleAlert } from 'lucide-react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import type { Member, TaskDetail } from '@/api/contracts/common'
import { membersApi, projectsApi, tasksApi } from '@/api/services'
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
import { Toast } from '@/components/Toast'
import { useSession } from '@/features/auth/useSession'
import { BuildingDesignPicker } from '@/features/projects/BuildingDesignPicker'
import { projectPlacements } from '@/features/projects/placements'
import { useProjectAction } from '@/features/workspace/useProjectAction'
import { getApiErrorCode, getApiErrorMessage } from '@/api/error'
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

type DialogAction = { action: 'edit' | 'remove' | 'transfer'; member: Member }

export type TeamView = 'team' | 'project' | 'remove'

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
  const [design, setDesign] = useState<BuildingDesign>(
    placements[placementKey(accountId, projectId)]?.design ?? 'modern',
  )
  const [dialog, setDialog] = useState<DialogAction | null>(null)
  const [fresh, setFresh] = useState<
    { id: string; url: string; email: string }[]
  >([])
  const [copyMessage, setCopyMessage] = useState('')
  const [inviteFailures, setInviteFailures] = useState<
    { email: string; message: string }[]
  >([])
  const project = useQuery({
    queryKey: ['projects', projectId, 'team-project'],
    queryFn: () => projectsApi.get(projectId),
  })
  // 부지는 도시와 같은 규칙(전체 프로젝트 순서, 저장된 부지 먼저)으로 정한다. 이 프로젝트만 넘기면
  // 다른 프로젝트가 쓰는 0번 부지를 골라 덮어썼다(PR #72 전수 리뷰 AMP-04).
  const cityProjects = useQuery({
    queryKey: ['projects', 'placement-ids'],
    queryFn: () => projectsApi.list({ limit: 100 }),
    enabled: view === 'project',
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
  // 팀원이 나갈 때도 맡은 열린 작업을 넘겨야 한다. 넘길 동료가 없으면 서버가 409 active_tasks_exist로 막는다
  // (PR #72 5차 조사 FIFTH-AMP-01). 추방과 같은 작업 목록과 동료 선택을 쓴다.
  const leaving = view === 'remove' && !isLeader
  const tasks = useQuery({
    queryKey: ['projects', projectId, 'team-tasks'],
    queryFn: () => tasksApi.list(projectId, { limit: 100 }),
    enabled: !!removing || leaving,
  })
  const openTasksOf = (memberId?: string) =>
    tasks.data?.items.filter(
      (task) =>
        task.assignee_member_id === memberId &&
        !['COMPLETED', 'CANCELLED'].includes(task.status),
    ) ?? []
  const handover = openTasksOf(removing?.id)
  const otherMembers = people.filter(
    (member) => member.account_id !== session.data?.id,
  )
  const myHandover = openTasksOf(
    people.find((member) => member.account_id === session.data?.id)?.id,
  )
  // 여러 명은 한 명씩 만든다. 이미 초대 중이거나 이미 팀원(409 already_member)이면 건너뛴다(Q05 3 ALREADY_INVITED).
  // API 없음: 초대 메일 발송·여러 명 한 번에(emails, PR #42). 지금은 만든 링크를 아래 '초대 중'에 보여 준다.
  // 초대 목록 조회는 링크(토큰)를 다시 주지 않아, 만든 링크는 바로 보관한다. 중간에 하나가 실패해도 나머지를 계속 만들고
  // 목록을 새로 고친 뒤 성공과 실패를 나눠 보여 준다(PR #72 전수 리뷰 AMP-03). 실패한 이메일은 입력 칸에 남긴다.
  const invite = async (emails: string[]) => {
    const pending = new Set(
      invitations.data?.pages
        .flatMap((page) => page.items)
        // 명세에는 만료가 없지만 백엔드는 7일 뒤 만료한다. 만료된 초대는 대기로 세지 않아 같은 이메일로 다시 초대한다
        // (PR #72 mock/live 전수 대조 LIVE-AMP-05).
        .filter(
          (item) =>
            !item.accepted_at &&
            !item.revoked_at &&
            !(item.expires_at && Date.parse(item.expires_at) <= Date.now()),
        )
        .map((item) => item.invited_email?.toLowerCase()),
    )
    let created = 0
    let skipped = 0
    const failed: { email: string; message: string }[] = []
    setInviteFailures([])
    setCopyMessage('')
    for (const email of emails) {
      if (pending.has(email.toLowerCase())) {
        skipped++
        continue
      }
      try {
        const result = await membersApi.createInvitation(projectId, {
          email,
        })
        created++
        setFresh((items) => [
          ...items,
          { id: result.invitation.id, url: result.invite_url, email },
        ])
      } catch (cause) {
        if (getApiErrorCode(cause) === 'already_member') skipped++
        else failed.push({ email, message: getApiErrorMessage(cause) })
      }
    }
    await queryClient.invalidateQueries({
      queryKey: ['projects', projectId, 'invitations'],
    })
    setInviteFailures(failed)
    setCopyMessage(
      [
        created > 0 && `초대 링크 ${created}개를 만들었어요.`,
        skipped > 0 && `이미 팀원이거나 초대 중인 ${skipped}명은 건너뛰었어요.`,
      ]
        .filter(Boolean)
        .join(' '),
    )
    return failed.map((item) => item.email)
  }
  // 제거·나가기 뒤에는 바로 도시로 간다. 이 프로젝트를 다시 부르면 404·403이라 캐시에서 지우고, 목록만 뒤에서 새로 고친다.
  const leaveProject = () => {
    navigate('/')
    queryClient.removeQueries({ queryKey: ['projects', projectId] })
    void queryClient.invalidateQueries({ queryKey: ['projects'] })
  }
  const perform = (operation: () => Promise<unknown>, message: string) => {
    void action.run(operation, message).catch(() => undefined)
  }
  const title =
    view === 'project'
      ? '프로젝트 정보'
      : view === 'remove'
        ? isLeader
          ? '프로젝트 제거'
          : '프로젝트 나가기'
        : '팀'
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
      {view === 'team' && isLeader && <InviteForm onInvite={invite} />}
      {inviteFailures.length > 0 && (
        <div
          role="alert"
          className="rounded-lg bg-destructive/5 p-3 text-sm text-destructive"
        >
          <strong className="block">
            {inviteFailures.length}명은 초대하지 못했어요. 입력 칸에 남겨
            뒀어요.
          </strong>
          <ul className="mt-1 grid gap-0.5">
            {inviteFailures.map((item) => (
              <li key={item.email}>
                {item.email} · {item.message}
              </li>
            ))}
          </ul>
        </div>
      )}
      {/* 결과는 화면 안 글 대신 오른쪽 위 토스트로 잠깐 보여 준다. */}
      {action.message && <Toast>{action.message}</Toast>}
      {handoff.done && (
        <Toast tone="success">
          <strong className="block">팀장을 넘겼어요</strong>
          <span className="text-muted-foreground">
            {handoff.done}님이 이제 팀장이에요. 나는 팀원이 됐어요.
          </span>
        </Toast>
      )}
      {copyMessage && <Toast key={copyMessage}>{copyMessage}</Toast>}
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
                          {member.domain ?? '담당 영역 미등록'}
                        </span>
                        <span className="inline-flex items-center gap-1.5 text-xs">
                          <span
                            aria-hidden="true"
                            className={`size-1.5 rounded-full ${member.agent_connected ? 'bg-green-600' : 'bg-primary'}`}
                          />
                          {member.agent_connected
                            ? '에이전트 연결됨'
                            : '에이전트 연결 미확인'}
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
                                역할·담당 영역
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
      {/* 초대가 하나도 없다고 확인되면 '초대 중' 칸을 숨긴다(캔버스 SCR-06-v2-settings). 목록을 불러오는 중이거나
          실패했으면 칸을 두고 AsyncState로 오류·다시 시도를 보인다. 방금 만든 초대 링크는 한 번만 받으므로 목록에 아직
          없어도(목록 조회 실패 포함) 따로 보인다(PR #72 4차 조사 FOURTH-AMP-01). */}
      {view === 'team' &&
        isLeader &&
        (!invitations.isSuccess ||
          invitations.data.pages.some((page) => page.items.length) ||
          fresh.length > 0) && (
          <div className="space-y-3">
            <h3 className="text-sm font-semibold">초대 중</h3>
            {fresh
              .filter(
                (item) =>
                  !invitations.data?.pages.some((page) =>
                    page.items.some((invitation) => invitation.id === item.id),
                  ),
              )
              .map((item) => (
                <div key={item.id} className="grid gap-1 text-sm">
                  <b className="font-medium">{item.email} · 방금 초대</b>
                  <FreshLink url={item.url} onCopied={setCopyMessage} />
                </div>
              ))}
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
                                {fresh.some((item) => item.id === invitation.id)
                                  ? '방금 초대 · '
                                  : ''}
                                {/* Q05(2026-10-29 확정): 초대받은 사람이 로그인·가입해야 팀원이 된다. 만료는 없다. */}
                                {invitation.accepted_at
                                  ? `참여함 · ${formatDate(invitation.accepted_at)}`
                                  : invitation.revoked_at
                                    ? '취소됨'
                                    : '같은 이메일로 로그인하거나 가입하면 팀원이 돼요'}
                              </small>
                            </span>
                            {/* API 없음: 초대 메일 발송이 없어 방금 만든 초대는 링크를 직접 전달한다. 메일이 생기면 토스트를 '초대 메일을 보냈어요'로 바꾼다. */}
                            {fresh.some(
                              (item) => item.id === invitation.id,
                            ) && (
                              <FreshLink
                                url={
                                  fresh.find(
                                    (item) => item.id === invitation.id,
                                  )!.url
                                }
                                onCopied={setCopyMessage}
                              />
                            )}
                            {!invitation.revoked_at &&
                              !invitation.accepted_at && (
                                // API 없음: 초대 메일 다시 보내기
                                <Button
                                  variant="ghost"
                                  size="sm"
                                  disabled
                                  title="메일 발송이 준비되면 열려요"
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
      {view === 'project' && (
        <AsyncState query={project}>
          {(data) => (
            <div className="space-y-6">
              {/* API 없음: 건물 디자인은 이 브라우저에만 저장된다. Project에 design 필드가 생기면 수정 요청에 넣는다. */}
              <BuildingDesignPicker
                value={design}
                onChange={setDesign}
                small
                note={
                  typeof lot === 'number' && (
                    <p className="grid justify-items-center gap-1 text-center text-xs leading-[17px] text-[#6b6658]">
                      <span className="inline-flex h-[22px] items-center rounded-full bg-white px-2 font-semibold text-foreground">
                        지금 부지 {lot + 1}
                      </span>
                      자리는 그대로예요. 건물 디자인은 이 브라우저에만 저장돼요.
                    </p>
                  )
                }
              />
              <ActionForm
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
                    () =>
                      projectsApi.update(projectId, {
                        name: values.name,
                        description: values.description,
                      }),
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
              <div className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-destructive/30 p-4">
                <span className="text-sm">
                  <strong className="block">프로젝트 삭제</strong>
                  <span className="text-muted-foreground">
                    팀원이 있으면 팀장을 먼저 위임해야 해요.
                  </span>
                </span>
                <Button
                  variant="destructive"
                  onClick={() => onViewChange?.('remove')}
                >
                  프로젝트 삭제
                </Button>
              </div>
            </div>
          )}
        </AsyncState>
      )}
      {view === 'remove' && (
        <AsyncState query={project}>
          {(data) => (
            // 팀원 수를 모른 채(조회 중·실패) '혼자 쓰는 프로젝트' 삭제 폼을 보이면 팀 프로젝트가 지워질 수 있어, 팀원 조회가 성공한 뒤에만 고른다.
            <AsyncState query={members}>
              {() =>
                isLeader && otherMembers.length > 0 ? (
                  // 서버(PRJ-A05)는 아직 팀원이 있어도 지운다. 막는 응답(409)이 생기기 전까지 화면에서 먼저 막는다.
                  <div className="space-y-5">
                    <div
                      role="alert"
                      className="flex gap-2.5 rounded-xl border border-primary/50 bg-primary/10 p-4 text-sm"
                    >
                      <TriangleAlert className="mt-0.5 size-4 shrink-0 text-amber-700" />
                      <div>
                        <strong className="font-semibold">
                          팀원 {otherMembers.length}명이 함께 쓰고 있어서 바로
                          제거할 수 없어요
                        </strong>
                        <p className="mt-0.5 text-muted-foreground">
                          먼저 설정 › 팀에서 다른 팀원에게 팀장을 위임하세요.
                          위임하면 나는 팀원이 되고, 그다음 프로젝트에서 나갈 수
                          있어요. 프로젝트는 남은 팀원에게 그대로 남아요.
                        </p>
                      </div>
                    </div>
                    <ol className="grid gap-3.5 text-sm">
                      <li className="grid grid-cols-[24px_minmax(0,1fr)] gap-3">
                        <span className="grid size-6 place-items-center rounded-full bg-primary text-xs font-bold">
                          1
                        </span>
                        <div className="grid gap-2">
                          <strong className="font-semibold">팀장 위임</strong>
                          <span className="text-[12.5px] text-muted-foreground">
                            설정 › 팀에서 넘길 팀원 줄의 ⋯ → &apos;팀장으로
                            위임&apos;으로 넘겨요. 넘기면 나는 팀원이 되고,
                            2단계로 이어가요.
                          </span>
                          <Button
                            className="justify-self-start"
                            onClick={() => onViewChange?.('team')}
                          >
                            팀에서 위임하기
                          </Button>
                        </div>
                      </li>
                      <li className="grid grid-cols-[24px_minmax(0,1fr)] gap-3 text-muted-foreground">
                        <span className="grid size-6 place-items-center rounded-full bg-muted text-xs font-bold">
                          2
                        </span>
                        <div className="grid gap-1">
                          <strong className="font-semibold">
                            프로젝트 나가기
                          </strong>
                          <span className="text-[12.5px]">
                            위임이 끝나면 팀원 화면의 프로젝트 나가기로
                            이어져요.
                          </span>
                        </div>
                      </li>
                    </ol>
                  </div>
                ) : (
                  <div className="space-y-4">
                    <p className="rounded-xl border border-destructive/30 bg-destructive/5 p-4 text-sm">
                      {isLeader
                        ? '혼자 쓰는 프로젝트예요. 제거하면 문서와 작업에 다시 들어올 수 없어요.'
                        : '나가면 문서·작업을 볼 수 없고 이 프로젝트 토큰도 바로 막혀요. 다시 들어오려면 새 초대가 필요해요.'}
                    </p>
                    <ActionForm
                      // 넘길 작업을 늦게 알게 되면 칸이 바뀌므로 폼을 새로 그린다.
                      key={myHandover.length ? 'handover' : 'plain'}
                      footer={
                        !isLeader &&
                        myHandover.length > 0 && (
                          <HandoverTasks tasks={myHandover} />
                        )
                      }
                      fields={[
                        ...(!isLeader && myHandover.length
                          ? [
                              {
                                name: 'reassign_to_member_id',
                                label: '작업을 넘겨받을 동료',
                                type: 'select' as const,
                                required: true,
                                // 빈 칸을 먼저 두어 첫 동료에게 저절로 넘기지 않고 직접 고르게 한다.
                                options: [
                                  { value: '', label: '동료 고르기' },
                                  ...otherMembers.map((member) => ({
                                    value: member.id,
                                    label: member.name,
                                  })),
                                ],
                              },
                            ]
                          : []),
                        {
                          name: 'confirm_name',
                          label: '프로젝트 이름 입력',
                          required: true,
                          placeholder: data.name,
                          check: (value) =>
                            value === data.name
                              ? undefined
                              : '프로젝트 이름이 맞지 않아요.',
                        },
                      ]}
                      destructive
                      submitLabel={
                        isLeader ? '프로젝트 제거' : '프로젝트 나가기'
                      }
                      onSubmit={async (values) => {
                        // action.run은 성공 뒤 이 프로젝트를 다시 불러 '접근 권한' 오류가 뜨므로 직접 부른다. 실패하면 폼이 이유를 보여 준다.
                        await (isLeader
                          ? projectsApi.remove(projectId, {
                              confirm_name: values.confirm_name,
                            })
                          : membersApi.leave(projectId, {
                              reassign_to_member_id:
                                values.reassign_to_member_id || undefined,
                            }))
                        leaveProject()
                      }}
                    />
                  </div>
                )
              }
            </AsyncState>
          )}
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
          back="팀"
          title={`${dialog.member.name} · 역할 수정`}
          description="표시 역할과 담당 영역은 팀 목록과 3D 이름표에 보여요."
          fields={[
            {
              name: 'role_label',
              label: '표시 역할',
              value: dialog.member.role_label,
              max: 50,
            },
            {
              name: 'domain',
              label: '담당 영역',
              value: dialog.member.domain,
              max: 50,
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
              '역할과 담당 영역을 저장했어요.',
            )
            setDialog(null)
          }}
        />
      )}
      {typeof dialog === 'object' && dialog?.action === 'remove' && (
        <FormDialog
          back="팀"
          title={`${dialog.member.name} 추방`}
          description="프로젝트 접근과 토큰이 바로 해제돼요. 진행 중인 작업을 넘겨받을 동료를 골라 주세요."
          fields={[
            {
              name: 'reassign_to_member_id',
              label: '작업을 넘겨받을 동료',
              type: 'select',
              options: people
                .filter((member) => member.id !== dialog.member.id)
                .map((member) => ({ value: member.id, label: member.name })),
            },
          ]}
          submitLabel="추방"
          destructive
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
          {/* 넘길 작업을 목록으로 보여 주고 넘겨받을 동료를 고른다(L9). */}
          <HandoverTasks tasks={handover} />
        </FormDialog>
      )}
    </section>
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
    ),
})

// 팀 목록 위 초대 칸(노션처럼). 쉼표나 줄바꿈으로 여러 명을 넣고, 보내면 칸을 비운다.
// 추방·나가기에서 넘길 열린 작업 목록
function HandoverTasks({ tasks }: { tasks: TaskDetail[] }) {
  return (
    <section aria-label="넘길 작업" className="grid gap-1.5 text-sm">
      <p className="font-semibold">넘길 작업</p>
      {tasks.length ? (
        <ul className="grid gap-1">
          {tasks.map((task) => (
            <li key={task.id} className="rounded-lg border px-3 py-2">
              {task.key} · {task.current_plan_revision.plan.title}
            </li>
          ))}
        </ul>
      ) : (
        <p className="text-muted-foreground">넘길 작업이 없어요.</p>
      )}
      {/* PR #50 d4db914: 진행 중·대기 작업은 넘기면 대기(재검증)가 된다. 보드에 없는 안내라 짧게 둔다. */}
      {tasks.some((task) =>
        ['IN_PROGRESS', 'WAITING'].includes(task.status),
      ) && (
        <p className="text-xs text-muted-foreground">
          진행 중인 작업은 넘기면 대기(재검증)로 바뀌고, 넘겨받은 동료가 다시
          검증받아야 해요.
        </p>
      )}
    </section>
  )
}

// 방금 만든 초대 링크. 응답에서 한 번만 받으므로 복사하게 둔다.
function FreshLink({
  url,
  onCopied,
}: {
  url: string
  onCopied: (message: string) => void
}) {
  return (
    <span className="flex w-full gap-2">
      <Input
        aria-label="팀원 초대 링크"
        readOnly
        value={url}
        className="min-w-48 flex-1"
        onFocus={(event) => event.currentTarget.select()}
      />
      <Button
        size="sm"
        variant="outline"
        onClick={() =>
          void navigator.clipboard
            .writeText(url)
            .then(() => onCopied('초대 링크를 복사했어요.'))
            .catch(() => onCopied('링크를 선택해 직접 복사해 주세요.'))
        }
      >
        <Copy />
        복사
      </Button>
    </span>
  )
}

function InviteForm({
  onInvite,
}: {
  /** 초대하지 못한 이메일을 돌려준다. 그 이메일은 입력 칸에 남긴다. */
  onInvite: (emails: string[]) => Promise<string[]>
}) {
  const {
    register,
    handleSubmit,
    reset,
    formState: { errors, isSubmitting },
  } = useForm<z.infer<typeof inviteSchema>>({
    resolver: zodResolver(inviteSchema),
    defaultValues: { emails: '' },
  })
  return (
    <form
      noValidate
      aria-label="팀원 초대"
      className="grid gap-1.5"
      onSubmit={handleSubmit((values) =>
        onInvite(splitEmails(values.emails))
          .then((failed) => reset({ emails: failed.join(', ') }))
          .catch(() => undefined),
      )}
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
      {errors.emails ? (
        <p role="alert" className="text-xs text-destructive">
          {errors.emails.message}
        </p>
      ) : (
        <span className="text-xs text-muted-foreground">
          받은 사람이 같은 이메일로 로그인하거나 가입하면 팀원이 돼요. 들어오기
          전까지는 &apos;초대 중&apos;으로 보여요.
        </span>
      )}
    </form>
  )
}
