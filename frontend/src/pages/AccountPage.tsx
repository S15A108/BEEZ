import { Link, useNavigate } from 'react-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { authApi, agentsApi, projectsApi } from '@/api/services'
import { RequireAccount } from '@/features/auth/RequireAccount'
import { useSession } from '@/features/auth/useSession'
import { ActionForm } from '@/components/ActionForm'
import { AsyncState } from '@/components/AsyncState'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { formatDate } from '@/lib/formatDate'

function Account() {
  const session = useSession()
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const mutation = useMutation({
    mutationFn: (action: () => Promise<unknown>) => action(),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['account'] }),
        queryClient.invalidateQueries({ queryKey: ['my-tokens'] }),
      ])
    },
  })
  const tokens = useQuery({
    queryKey: ['my-tokens'],
    queryFn: async () => {
      const [tokens, projects] = await Promise.all([
        agentsApi.myTokens(),
        projectsApi.list(),
      ])
      return { tokens, projects }
    },
  })
  return (
    <main className="mx-auto max-w-4xl space-y-7 px-6 py-10">
      <div className="flex items-center justify-between">
        <h2 className="text-2xl font-bold">회원 설정</h2>
        <Button variant="outline" asChild>
          <Link to="/">나의 도시로</Link>
        </Button>
      </div>
      <div className="grid gap-6 md:grid-cols-2">
        <Card>
          <CardHeader>
            <CardTitle>프로필</CardTitle>
          </CardHeader>
          <CardContent>
            <p className="mb-5 text-xs text-muted-foreground">
              {session.data?.email}
            </p>
            <ActionForm
              key={session.data?.id}
              fields={[
                {
                  name: 'name',
                  label: '표시 이름',
                  required: true,
                  max: 40,
                  value: session.data?.name,
                },
                {
                  name: 'avatar',
                  label: '아바타',
                  type: 'select',
                  value: session.data?.avatar ?? 'human',
                  options: [
                    { value: 'human', label: '사람' },
                    { value: 'robot', label: '로봇' },
                    { value: 'bee', label: '벌' },
                  ],
                },
              ]}
              submitLabel="프로필 저장"
              onSubmit={(values) =>
                mutation.mutateAsync(() =>
                  authApi.updateMe({
                    name: values.name,
                    avatar: values.avatar,
                  }),
                )
              }
            />
          </CardContent>
        </Card>
        <Card>
          <CardHeader>
            <CardTitle>비밀번호 변경</CardTitle>
          </CardHeader>
          <CardContent>
            <ActionForm
              fields={[
                {
                  name: 'current_password',
                  label: '현재 비밀번호',
                  type: 'password',
                  required: true,
                },
                {
                  name: 'new_password',
                  label: '새 비밀번호',
                  type: 'password',
                  min: 8,
                  max: 128,
                  required: true,
                },
                {
                  name: 'confirm',
                  label: '새 비밀번호 확인',
                  type: 'password',
                  required: true,
                  matches: 'new_password',
                },
              ]}
              submitLabel="비밀번호 바꾸기"
              onSubmit={(values) =>
                mutation.mutateAsync(() =>
                  authApi.changePassword({
                    current_password: values.current_password,
                    new_password: values.new_password,
                  }),
                )
              }
            />
          </CardContent>
        </Card>
      </div>
      <Card>
        <CardHeader>
          <CardTitle>내 에이전트 토큰</CardTitle>
        </CardHeader>
        <CardContent>
          <AsyncState query={tokens}>
            {(data) => (
              <div className="grid gap-3">
                {data.tokens.items
                  .filter((token) => !token.revoked_at)
                  .map((token) => (
                    <div
                      key={token.id}
                      className="flex flex-wrap items-center justify-between gap-3 rounded-xl border p-4"
                    >
                      <div>
                        <Link
                          to={`/projects/${token.project_id}`}
                          className="text-sm font-semibold underline"
                        >
                          {data.projects.items.find(
                            (project) => project.id === token.project_id,
                          )?.name ?? '프로젝트'}
                        </Link>
                        <p className="mt-2 text-xs text-muted-foreground">
                          {token.label || '개인 토큰'} · 발급{' '}
                          {formatDate(token.created_at)}
                        </p>
                      </div>
                      <Button
                        variant="outline"
                        disabled={mutation.isPending}
                        onClick={() => {
                          if (
                            window.confirm(
                              '이 토큰을 폐기할까요? 기존 연결은 사용할 수 없게 돼요.',
                            )
                          )
                            mutation.mutate(() =>
                              agentsApi.revokeToken(token.id),
                            )
                        }}
                      >
                        폐기
                      </Button>
                    </div>
                  ))}
                {data.tokens.items.every((token) => token.revoked_at) && (
                  <p className="text-sm text-muted-foreground">
                    발급한 토큰이 없어요. 프로젝트의 에이전트 연결에서 발급할 수
                    있어요.
                  </p>
                )}
              </div>
            )}
          </AsyncState>
        </CardContent>
      </Card>
      <Card>
        <CardHeader>
          <CardTitle>회원 탈퇴</CardTitle>
        </CardHeader>
        <CardContent>
          <p className="mb-5 text-sm leading-7 text-muted-foreground">
            프로젝트 팀장을 위임하고 참여 중인 프로젝트에서 나간 뒤 탈퇴할 수
            있어요. 작업과 문서 이력은 보존 정책에 따라 처리돼요.
          </p>
          <ActionForm
            fields={[
              {
                name: 'password',
                label: '탈퇴 확인 비밀번호',
                type: 'password',
                required: true,
              },
            ]}
            submitLabel="회원 탈퇴"
            onSubmit={async (values) => {
              if (!window.confirm('정말 회원 탈퇴할까요?')) return
              await mutation.mutateAsync(() =>
                authApi.withdraw({ password: values.password }),
              )
              queryClient.clear()
              navigate('/login')
            }}
          />
        </CardContent>
      </Card>
    </main>
  )
}
export default function AccountPage() {
  return (
    <RequireAccount>
      <Account />
    </RequireAccount>
  )
}
