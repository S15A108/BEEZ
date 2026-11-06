import { useState } from 'react'
import { Link, useLocation } from 'react-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Check, CircleCheckBig } from 'lucide-react'
import { zodResolver } from '@hookform/resolvers/zod'
import { useForm, useWatch } from 'react-hook-form'
import { z } from 'zod'
import { getApiErrorMessage } from '@/api/error'
import { agentsApi, authApi, projectsApi } from '@/api/services'
import { allTokens } from '@/features/agents/allTokens'
import { RequireAccount } from '@/features/auth/RequireAccount'
import { useSession } from '@/features/auth/useSession'
import { AccountLayout } from '@/features/auth/AccountLayout'
import { AsyncState } from '@/components/AsyncState'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { characterOf, characters } from '@/lib/characters'
import { formatDate } from '@/lib/formatDate'
import { cn } from '@/lib/utils'

const profileSchema = z.object({
  name: z
    .string()
    .trim()
    .min(1, '표시 이름을 입력해 주세요.')
    .max(40, '40자 이내로 입력해 주세요.'),
})
type ProfileInput = z.infer<typeof profileSchema>

// 표시 이름과 이메일(아이디)을 나란히, 아래에 안내와 저장 버튼(캔버스 SCR-05-v2-account).
function ProfileForm({
  name,
  email,
  avatarChanged,
  onSave,
}: {
  name: string
  email: string
  avatarChanged: boolean
  onSave: (name: string) => Promise<unknown>
}) {
  const {
    register,
    handleSubmit,
    control,
    setError,
    formState: { errors, isSubmitting, isSubmitSuccessful },
  } = useForm<ProfileInput>({
    resolver: zodResolver(profileSchema),
    defaultValues: { name },
  })
  // name은 서버 값이라, 저장 뒤 계정을 다시 읽으면 입력과 같아져 버튼이 저절로 꺼진다.
  const typedName = useWatch({ control, name: 'name' })
  const changed = avatarChanged || typedName.trim() !== name
  return (
    <form
      onSubmit={handleSubmit(async (values) => {
        try {
          await onSave(values.name)
        } catch (cause) {
          setError('root', { message: getApiErrorMessage(cause) })
        }
      })}
      className="grid gap-5"
    >
      <div className="grid gap-4 sm:grid-cols-2">
        <div className="grid content-start gap-2">
          <Label htmlFor="profile-name">표시 이름</Label>
          <Input
            id="profile-name"
            maxLength={40}
            aria-invalid={!!errors.name}
            className="h-10"
            {...register('name')}
          />
          {errors.name && (
            <p role="alert" className="text-xs text-destructive">
              {errors.name.message}
            </p>
          )}
        </div>
        <div className="grid content-start gap-2">
          <Label htmlFor="profile-email">이메일 (아이디)</Label>
          <Input
            id="profile-email"
            value={email}
            readOnly
            className="h-10 bg-muted text-muted-foreground"
          />
        </div>
      </div>
      {errors.root && (
        <p role="alert" className="text-sm text-destructive">
          {errors.root.message}
        </p>
      )}
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-[13px] text-muted-foreground">
          저장하면 워크스페이스 HUD, 팀 목록, 3D 사무실에 이 캐릭터로 보여요.
        </p>
        <div className="ml-auto flex items-center gap-3">
          {isSubmitSuccessful && !changed && (
            <p
              role="status"
              className="flex items-center gap-1 text-[13px] text-muted-foreground"
            >
              <Check aria-hidden="true" className="size-3.5" />
              저장했어요
            </p>
          )}
          <Button type="submit" disabled={isSubmitting || !changed}>
            프로필 저장
          </Button>
        </div>
      </div>
    </form>
  )
}

function Account() {
  const session = useSession()
  const queryClient = useQueryClient()
  const location = useLocation()
  const toast = (location.state as { toast?: string } | null)?.toast
  const [revoking, setRevoking] = useState<string | null>(null)
  const [avatar, setAvatar] = useState(session.data?.avatar)
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
        allTokens(),
        projectsApi.list(),
      ])
      return { tokens, projects }
    },
  })
  const picked = characterOf(avatar)
  return (
    <AccountLayout character={picked}>
      {toast && (
        <p
          role="status"
          className="fixed top-[84px] right-8 z-30 flex max-w-[360px] items-center gap-2.5 rounded-xl bg-foreground px-4 py-3 text-sm text-white shadow-lg"
        >
          <CircleCheckBig
            aria-hidden="true"
            className="size-[18px] shrink-0 text-[#7bd88f]"
          />
          {toast}
        </p>
      )}
      <section
        aria-labelledby="profile-title"
        className="grid gap-5 rounded-[14px] bg-card p-6 ring-1 ring-foreground/10"
      >
        <div>
          <h3 id="profile-title" className="font-semibold">
            프로필 · 내 캐릭터
          </h3>
          <p className="mt-1 text-[13px] text-muted-foreground">
            3D 사무실에서 내가 쓰는 캐릭터예요. 프로젝트마다 같은 캐릭터로
            보여요.
          </p>
        </div>
        <fieldset className="grid gap-2">
          <legend className="mb-2 text-sm font-medium">캐릭터</legend>
          <div className="grid grid-cols-3 gap-3">
            {characters.map((item) => {
              const checked = picked.key === item.key
              return (
                <label
                  key={item.key}
                  className={cn(
                    'relative grid cursor-pointer gap-1.5 rounded-[14px] border bg-card p-2 pb-2.5',
                    checked &&
                      'border-2 border-[#c99e1e] bg-[#fffaeb] p-[7px] pb-[9px]',
                  )}
                >
                  <input
                    type="radio"
                    name="character"
                    className="sr-only"
                    checked={checked}
                    onChange={() => setAvatar(item.key)}
                  />
                  <span
                    className="grid h-[156px] place-items-center overflow-hidden rounded-[10px]"
                    style={{ backgroundColor: `${item.color}29` }}
                  >
                    <img
                      src={item.portrait}
                      alt=""
                      className="h-[150px] w-auto"
                    />
                  </span>
                  <span
                    className={cn(
                      'flex items-center justify-center gap-1.5 text-[13px] font-semibold',
                      checked && 'font-bold',
                    )}
                  >
                    <span
                      aria-hidden="true"
                      className="size-2 shrink-0 rounded-full"
                      style={{ backgroundColor: item.color }}
                    />
                    {item.label}
                  </span>
                  {checked && (
                    <span
                      aria-hidden="true"
                      className="absolute top-1.5 right-1.5 grid size-5 place-items-center rounded-full bg-primary text-primary-foreground"
                    >
                      <Check className="size-3" strokeWidth={3.5} />
                    </span>
                  )}
                </label>
              )
            })}
          </div>
        </fieldset>
        <ProfileForm
          key={session.data?.id}
          name={session.data?.name ?? ''}
          email={session.data?.email ?? ''}
          // 예전 값(bee-yellow)이나 빈 값도 모든 화면에서 같은 캐릭터로 보이므로, 화면에 보이는 캐릭터가 같으면 바뀌지 않은 것이다.
          avatarChanged={picked.key !== characterOf(session.data?.avatar).key}
          onSave={(name) =>
            mutation.mutateAsync(() =>
              authApi.updateMe({ name, avatar: picked.key }),
            )
          }
        />
      </section>
      <section
        aria-labelledby="password-section-title"
        className="flex flex-wrap items-center justify-between gap-4 rounded-[14px] bg-card p-6 ring-1 ring-foreground/10"
      >
        <div>
          <h3 id="password-section-title" className="font-semibold">
            비밀번호 변경
          </h3>
          <p className="mt-1 text-[13px] text-muted-foreground">
            현재 비밀번호를 확인한 뒤 새 비밀번호로 바꿀 수 있어요.
          </p>
        </div>
        <Button
          variant="outline"
          className="border-[#d4d4d4] font-semibold"
          asChild
        >
          <Link to="/account/password">비밀번호 변경</Link>
        </Button>
      </section>
      <section
        aria-labelledby="tokens-title"
        className="rounded-[14px] bg-card p-6 ring-1 ring-foreground/10"
      >
        <h3 id="tokens-title" className="font-semibold">
          내 에이전트 토큰
        </h3>
        <p className="mt-1.5 text-[13px] text-muted-foreground">
          프로젝트마다 따로 발급하는 개인 토큰이에요. 내 컴퓨터의 Claude Code가
          그 프로젝트에 접속할 때 써요. 새 토큰은 각 프로젝트의 에이전트
          연결에서 발급해요.
        </p>
        <div className="mt-4">
          <AsyncState query={tokens}>
            {(data) => {
              const active = data.tokens.filter((token) => !token.revoked_at)
              return active.length ? (
                <table className="w-full text-left text-sm">
                  <thead className="text-xs text-muted-foreground">
                    <tr className="border-b">
                      <th className="py-2 font-medium">프로젝트</th>
                      <th className="py-2 font-medium">토큰 이름</th>
                      <th className="py-2 font-medium">발급일</th>
                      <th className="py-2 font-medium">최근 사용</th>
                      <th className="py-2" />
                    </tr>
                  </thead>
                  <tbody>
                    {active.map((token) => (
                      <tr key={token.id} className="border-b last:border-0">
                        <td className="py-3">
                          <Link
                            to={`/projects/${token.project_id}`}
                            className="font-semibold underline"
                          >
                            {data.projects.items.find(
                              (project) => project.id === token.project_id,
                            )?.name ?? '프로젝트'}
                          </Link>
                        </td>
                        <td>{token.label || '개인 토큰'}</td>
                        <td>{formatDate(token.created_at)}</td>
                        <td className="text-muted-foreground">
                          {token.last_used_at
                            ? formatDate(token.last_used_at)
                            : '기록 없음'}
                        </td>
                        <td className="text-right">
                          {revoking === token.id ? (
                            <span className="inline-flex items-center gap-2 text-xs">
                              연결이 바로 끊겨요.
                              <Button
                                variant="destructive"
                                size="sm"
                                disabled={mutation.isPending}
                                onClick={() =>
                                  mutation.mutate(
                                    () => agentsApi.revokeToken(token.id),
                                    { onSettled: () => setRevoking(null) },
                                  )
                                }
                              >
                                폐기하기
                              </Button>
                              <Button
                                variant="ghost"
                                size="sm"
                                onClick={() => setRevoking(null)}
                              >
                                취소
                              </Button>
                            </span>
                          ) : (
                            <Button
                              variant="outline"
                              size="sm"
                              onClick={() => setRevoking(token.id)}
                            >
                              폐기
                            </Button>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              ) : (
                <p className="text-sm text-muted-foreground">
                  발급한 토큰이 없어요.
                </p>
              )
            }}
          </AsyncState>
        </div>
      </section>
      <section
        aria-labelledby="withdraw-title"
        className="flex flex-wrap items-center justify-between gap-4 rounded-[14px] bg-card p-6 ring-1 ring-destructive/20"
      >
        <div>
          <h3 id="withdraw-title" className="font-semibold">
            회원 탈퇴
          </h3>
          <p className="mt-1 text-[13px] text-muted-foreground">
            팀원이 있는 팀 프로젝트는 팀장을 위임하거나 삭제한 뒤 탈퇴할 수
            있어요.
          </p>
        </div>
        <Button
          variant="outline"
          className="border-destructive/40 font-semibold text-[#c10007]"
          asChild
        >
          <Link to="/account/withdraw">회원 탈퇴</Link>
        </Button>
      </section>
    </AccountLayout>
  )
}
export default function AccountPage() {
  return (
    <RequireAccount>
      <Account />
    </RequireAccount>
  )
}
