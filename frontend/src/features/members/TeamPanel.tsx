import { useState } from 'react'
import {
  useInfiniteQuery,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query'
import { useNavigate } from 'react-router'
import {
  Copy,
  MailPlus,
  Pencil,
  Settings,
  ShieldCheck,
  UserMinus,
  Users,
} from 'lucide-react'
import type { Member } from '@/api/contracts/common'
import { membersApi, projectsApi } from '@/api/services'
import { AsyncState } from '@/components/AsyncState'
import { EmptyState } from '@/components/EmptyState'
import { FormDialog } from '@/components/FormDialog'
import { StatusPill } from '@/components/StatusPill'
import { Button } from '@/components/ui/button'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { useSession } from '@/features/auth/useSession'
import { useProjectAction } from '@/features/workspace/useProjectAction'
import { formatDate } from '@/lib/formatDate'

type DialogAction =
  | 'invite'
  | 'project'
  | 'delete'
  | 'leave'
  | { action: 'edit' | 'remove'; member: Member }
  | 'transfer'

export function TeamPanel({
  projectId,
  isLeader,
}: {
  projectId: string
  isLeader: boolean
}) {
  const session = useSession()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const action = useProjectAction(projectId)
  const [dialog, setDialog] = useState<DialogAction | null>(null)
  const [inviteUrl, setInviteUrl] = useState('')
  const [copyMessage, setCopyMessage] = useState('')
  const [viewTime] = useState(() => Date.now())
  const project = useQuery({
    queryKey: ['projects', projectId, 'team-project'],
    queryFn: () => projectsApi.get(projectId),
  })
  const members = useInfiniteQuery({
    queryKey: ['projects', projectId, 'members'],
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
    enabled: isLeader,
  })
  const people = members.data?.pages.flatMap((page) => page.items) ?? []
  const otherMembers = people.filter(
    (member) => member.account_id !== session.data?.id,
  )
  const leaveProject = async () => {
    await queryClient.invalidateQueries({ queryKey: ['projects'] })
    navigate('/')
  }
  const perform = (operation: () => Promise<unknown>, message: string) => {
    void action.run(operation, message).catch(() => undefined)
  }
  return (
    <section className="space-y-6">
      <header className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <p className="mb-2 text-xs font-semibold tracking-widest text-primary">
            PEOPLE & PROJECT
          </p>
          <h2 className="text-2xl font-semibold">함께 일하는 사람들</h2>
          <p className="mt-2 text-sm leading-6 text-muted-foreground">
            인간 팀장과 팀원의 역할, 담당 영역, 프로젝트 참여를 관리해요.
          </p>
        </div>
        {isLeader && (
          <Button
            onClick={() => {
              setInviteUrl('')
              setDialog('invite')
            }}
          >
            <MailPlus />
            팀원 초대
          </Button>
        )}
      </header>
      {action.message && (
        <p role="status" className="rounded-lg bg-primary/5 p-3 text-sm">
          {action.message}
        </p>
      )}
      {action.error && (
        <p
          role="alert"
          className="rounded-lg bg-destructive/5 p-3 text-sm text-destructive"
        >
          {action.error}
        </p>
      )}
      {inviteUrl && (
        <Card>
          <CardHeader>
            <CardTitle className="text-base">초대 링크가 준비됐어요</CardTitle>
            <CardDescription>
              초대한 이메일의 팀원에게 이 링크를 전달해 주세요.
            </CardDescription>
          </CardHeader>
          <CardContent className="flex flex-wrap gap-2">
            <Input
              aria-label="팀원 초대 링크"
              readOnly
              value={inviteUrl}
              className="min-w-48 flex-1"
              onFocus={(event) => event.currentTarget.select()}
            />
            <Button
              variant="outline"
              onClick={() =>
                void navigator.clipboard
                  .writeText(inviteUrl)
                  .then(() => setCopyMessage('초대 링크를 복사했어요.'))
                  .catch(() =>
                    setCopyMessage('링크를 선택해 직접 복사해 주세요.'),
                  )
              }
            >
              <Copy />
              복사
            </Button>
            {copyMessage && (
              <p role="status" className="w-full text-sm text-muted-foreground">
                {copyMessage}
              </p>
            )}
          </CardContent>
        </Card>
      )}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2 text-lg">
            <Users className="size-5" />
            팀원
          </CardTitle>
          <CardDescription>
            표시 역할과 담당 영역은 권한을 바꾸지 않아요. 권한은 인간 팀장·팀원
            두 종류예요.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <AsyncState query={members}>
            {(data) => (
              <>
                <div className="divide-y">
                  {data.pages
                    .flatMap((page) => page.items)
                    .map((member) => (
                      <article
                        key={member.id}
                        className="flex flex-wrap items-center justify-between gap-4 py-4"
                      >
                        <div className="flex min-w-0 items-center gap-3">
                          <span
                            className="grid size-10 shrink-0 place-items-center rounded-full text-sm font-semibold"
                            style={{
                              backgroundColor: `${member.color ?? '#e5b743'}25`,
                              color: member.color ?? '#a98419',
                            }}
                          >
                            {member.name.slice(0, 2)}
                          </span>
                          <div>
                            <div className="flex flex-wrap items-center gap-2">
                              <h3 className="text-sm font-semibold">
                                {member.name}
                                {member.account_id === session.data?.id
                                  ? ' · 나'
                                  : ''}
                              </h3>
                              <StatusPill value={member.role} />
                            </div>
                            <p className="mt-1 text-xs text-muted-foreground">
                              {member.role_label ?? '표시 역할 미등록'} ·{' '}
                              {member.domain ?? '담당 영역 미등록'}
                            </p>
                            <p className="mt-1 text-xs text-muted-foreground">
                              에이전트{' '}
                              {member.agent_connected
                                ? '최근 외부 연결 확인'
                                : '최근 연결 미확인'}
                            </p>
                          </div>
                        </div>
                        <div className="flex flex-wrap gap-2">
                          {(isLeader ||
                            member.account_id === session.data?.id) && (
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
                          )}
                          {isLeader && member.role !== 'LEADER' && (
                            <Button
                              variant="ghost"
                              size="sm"
                              disabled={action.busy}
                              onClick={() =>
                                setDialog({ action: 'remove', member })
                              }
                            >
                              <UserMinus />
                              제외
                            </Button>
                          )}
                        </div>
                      </article>
                    ))}
                </div>
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
        </CardContent>
      </Card>
      {isLeader && (
        <Card>
          <CardHeader>
            <CardTitle className="text-lg">초대 현황</CardTitle>
            <CardDescription>
              만료되거나 폐기된 링크는 사용할 수 없어요.
            </CardDescription>
          </CardHeader>
          <CardContent>
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
                              <small className="mt-1 block text-muted-foreground">
                                {invitation.accepted_at
                                  ? '수락됨'
                                  : invitation.revoked_at
                                    ? '폐기됨'
                                    : Date.parse(invitation.expires_at) <
                                        viewTime
                                      ? '만료됨'
                                      : `만료 ${formatDate(invitation.expires_at)}`}
                              </small>
                            </span>
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
                                      '초대를 폐기했어요.',
                                    )
                                  }
                                >
                                  초대 폐기
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
          </CardContent>
        </Card>
      )}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2 text-lg">
            <Settings className="size-5" />
            프로젝트 관리
          </CardTitle>
          <CardDescription>
            이름·소개, 인간 팀장 위임과 프로젝트 참여를 관리해요.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <AsyncState query={project}>
            {(data) => (
              <div className="space-y-4">
                <div>
                  <h3 className="font-medium">{data.name}</h3>
                  <p className="mt-1 text-sm text-muted-foreground">
                    {data.description || '소개가 없어요.'}
                  </p>
                </div>
                <div className="flex flex-wrap gap-2">
                  {isLeader ? (
                    <>
                      <Button
                        variant="outline"
                        disabled={action.busy}
                        onClick={() => setDialog('project')}
                      >
                        <Pencil />
                        프로젝트 정보
                      </Button>
                      <Button
                        variant="outline"
                        disabled={action.busy || !otherMembers.length}
                        onClick={() => setDialog('transfer')}
                      >
                        <ShieldCheck />
                        팀장 위임
                      </Button>
                      <Button
                        variant="destructive"
                        disabled={action.busy}
                        onClick={() => setDialog('delete')}
                      >
                        프로젝트 삭제
                      </Button>
                    </>
                  ) : (
                    <Button
                      variant="outline"
                      disabled={action.busy}
                      onClick={() => setDialog('leave')}
                    >
                      프로젝트 나가기
                    </Button>
                  )}
                </div>
                {isLeader && !otherMembers.length && (
                  <p className="text-xs text-muted-foreground">
                    팀원을 초대하면 인간 팀장을 위임할 수 있어요.
                  </p>
                )}
              </div>
            )}
          </AsyncState>
        </CardContent>
      </Card>
      {dialog === 'invite' && (
        <FormDialog
          title="팀원 초대"
          description="초대한 이메일로 로그인한 사용자가 참여할 수 있어요."
          fields={[
            {
              name: 'email',
              label: '팀원 이메일',
              type: 'email',
              required: true,
            },
          ]}
          submitLabel="초대 링크 만들기"
          onClose={() => setDialog(null)}
          onSubmit={async (values) => {
            await action.run(async () => {
              const result = await membersApi.createInvitation(projectId, {
                email: values.email,
              })
              setInviteUrl(result.invite_url)
            }, '초대 링크를 만들었어요.')
            setDialog(null)
          }}
        />
      )}
      {dialog === 'project' && project.data && (
        <FormDialog
          title="프로젝트 정보 수정"
          fields={[
            {
              name: 'name',
              label: '프로젝트 이름',
              required: true,
              max: 80,
              value: project.data.name,
            },
            {
              name: 'description',
              label: '프로젝트 소개',
              type: 'textarea',
              value: project.data.description,
            },
          ]}
          onClose={() => setDialog(null)}
          onSubmit={async (values) => {
            await action.run(
              () =>
                projectsApi.update(projectId, {
                  name: values.name,
                  description: values.description,
                }),
              '프로젝트 정보를 저장했어요.',
            )
            await queryClient.invalidateQueries({ queryKey: ['projects'] })
            setDialog(null)
          }}
        />
      )}
      {dialog === 'transfer' && (
        <FormDialog
          title="인간 팀장 위임"
          description="선택한 팀원이 인간 팀장이 되고, 나는 팀원이 됩니다."
          fields={[
            {
              name: 'target_member_id',
              label: '새 인간 팀장',
              type: 'select',
              required: true,
              options: otherMembers.map((member) => ({
                value: member.id,
                label: member.name,
              })),
            },
          ]}
          submitLabel="팀장 위임"
          onClose={() => setDialog(null)}
          onSubmit={async (values) => {
            await action.run(
              () =>
                membersApi.transfer(projectId, {
                  target_member_id: values.target_member_id,
                }),
              '인간 팀장을 위임했어요.',
            )
            await queryClient.invalidateQueries({ queryKey: ['projects'] })
            setDialog(null)
          }}
        />
      )}
      {dialog === 'delete' && project.data && (
        <FormDialog
          title="프로젝트 삭제"
          description="프로젝트 이름을 정확하게 입력해 삭제하세요. 참여 권한은 해제되고 문서와 작업의 기록은 정책에 따라 보관됩니다."
          fields={[
            {
              name: 'confirm_name',
              label: '삭제할 프로젝트 이름',
              required: true,
              placeholder: project.data.name,
            },
          ]}
          submitLabel="프로젝트 삭제"
          onClose={() => setDialog(null)}
          onSubmit={async (values) => {
            await action.run(
              () =>
                projectsApi.remove(projectId, {
                  confirm_name: values.confirm_name,
                }),
              '프로젝트를 삭제했어요.',
            )
            await leaveProject()
          }}
        />
      )}
      {dialog === 'leave' && (
        <FormDialog
          title="프로젝트 나가기"
          description="프로젝트 접근 권한과 에이전트 토큰이 해제돼요. 작성한 기록은 남습니다."
          fields={[]}
          submitLabel="프로젝트 나가기"
          onClose={() => setDialog(null)}
          onSubmit={async () => {
            await action.run(
              () => membersApi.leave(projectId, {}),
              '프로젝트에서 나갔어요.',
            )
            await leaveProject()
          }}
        />
      )}
      {typeof dialog === 'object' && dialog?.action === 'edit' && (
        <FormDialog
          title={`${dialog.member.name} 역할·담당 영역`}
          fields={[
            {
              name: 'role_label',
              label: '표시 역할',
              value: dialog.member.role_label,
              max: 40,
            },
            { name: 'domain', label: '담당 영역', value: dialog.member.domain },
            {
              name: 'color',
              label: '표시 색',
              value: dialog.member.color ?? '#e5b743',
              placeholder: '#e5b743',
            },
          ]}
          onClose={() => setDialog(null)}
          onSubmit={async (values) => {
            await action.run(
              () => membersApi.update(projectId, dialog.member.id, values),
              '역할과 담당 영역을 저장했어요.',
            )
            setDialog(null)
          }}
        />
      )}
      {typeof dialog === 'object' && dialog?.action === 'remove' && (
        <FormDialog
          title={`${dialog.member.name} 프로젝트에서 제외`}
          description="프로젝트 접근과 토큰이 해제됩니다. 진행 작업을 넘겨받을 동료를 선택하세요."
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
          submitLabel="프로젝트에서 제외"
          onClose={() => setDialog(null)}
          onSubmit={async (values) => {
            await action.run(
              () =>
                membersApi.remove(projectId, dialog.member.id, {
                  reassign_to_member_id:
                    values.reassign_to_member_id || undefined,
                }),
              '팀원을 프로젝트에서 제외했어요.',
            )
            setDialog(null)
          }}
        />
      )}
    </section>
  )
}
