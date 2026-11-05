import { useEffect, useRef } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { isAxiosError } from 'axios'
import { LoaderCircle, TriangleAlert } from 'lucide-react'
import { authApi, membersApi } from '@/api/services'
import { useSession } from '@/features/auth/useSession'
import { invitationQuery } from '@/features/members/invitationQuery'
import { Button } from '@/components/ui/button'
import {
  getApiErrorCode,
  getApiErrorMessage,
  getApiErrorStatus,
} from '@/api/error'
import NotFoundPage from './NotFoundPage'

// Q05 초대 규칙(PR #42, 2026-10-29 확정): 수락 버튼은 없다.
// 로그인한 채로 링크를 열면 저절로 참여(MEM-A05)하고 프로젝트로 간다. 로그아웃이면 로그인·가입을 거쳐 같은 화면으로 돌아온다.
// 미리보기(MEM-A04)는 조회만 한다. 만료는 없다. 메일 링크(/projects/{id}?invite=)는 WorkspacePage가 여기로 보낸다.
export default function InvitationPage() {
  const [params] = useSearchParams()
  const token = params.get('token') ?? params.get('invite') ?? ''
  const projectId = params.get('project')
  const session = useSession()
  const navigate = useNavigate()
  const client = useQueryClient()
  const invitation = useQuery({
    queryKey: ['invitation', token],
    queryFn: () => membersApi.previewInvitation({ token }),
    enabled: Boolean(token),
    retry: false,
  })
  const openProject = async (id?: string | null) => {
    await client.invalidateQueries({ queryKey: ['projects'] })
    navigate(id ? `/projects/${id}` : '/', { replace: true })
  }
  // 이미 팀원(409 already_member, 예: 로그인할 때 자동 참여)이면 그 프로젝트로 보낸다(PR #134).
  // 프로젝트 ID는 메일 링크에서만 알 수 있어, 옛 링크(/invite?token=)는 나의 도시로 보낸다.
  const accept = useMutation({
    mutationFn: () => membersApi.acceptInvitation({ token }),
    onSuccess: (data) => openProject(data.project.id),
    onError: (error) =>
      getApiErrorCode(error) === 'already_member'
        ? openProject(projectId)
        : undefined,
  })
  const joined = getApiErrorCode(accept.error) === 'already_member'
  const signedIn = Boolean(session.data)
  const ready = Boolean(invitation.data)
  // 서버는 다른 이메일 계정의 수락을 무효 초대와 같은 404로 준다(MEM-A05, Q05 -174). 그래서 오류 코드에 기대지 않고,
  // 로그인한 채 수락이 404거나 미리보기가 계정이 다르다고(account_matches=false) 하면 다른 계정일 수 있다고 알린다
  // (PR #72 mock/live 전수 대조 LIVE-AMP-04).
  const otherAccount =
    invitation.data?.account_matches === false ||
    getApiErrorStatus(accept.error) === 404 ||
    getApiErrorCode(accept.error) === 'invitation_email_mismatch'
  const started = useRef(false)
  useEffect(() => {
    if (!signedIn || !ready || otherAccount || started.current) return
    started.current = true
    accept.mutate()
  }, [signedIn, ready, otherAccount, accept])
  const status = getApiErrorStatus(invitation.error)
  // 통신 실패와 서버 오류만 다시 불러오기. 나머지는 원인을 알리지 않고 '찾을 수 없음'으로 통일한다.
  const networkFailure =
    invitation.error &&
    ((isAxiosError(invitation.error) && !invitation.error.response) ||
      (status !== undefined && status >= 500))
  if (!token || (invitation.error && !networkFailure)) return <NotFoundPage />
  const query = invitationQuery(params)
  const relogin = async () => {
    await authApi.logout().catch(() => undefined)
    await client.invalidateQueries()
    navigate(`/login${query}`)
  }
  return (
    <main className="px-4 pt-[72px] pb-10">
      <section className="mx-auto max-w-[480px] rounded-[22px] border bg-card p-10 shadow-sm">
        {invitation.isPending ? (
          <p
            role="status"
            className="flex items-center gap-2 text-sm text-muted-foreground"
          >
            <LoaderCircle className="size-4 animate-spin" />
            초대를 확인하는 중…
          </p>
        ) : networkFailure || !invitation.data ? (
          <div role="alert" className="grid gap-4">
            <p>초대를 불러오지 못했어요. 연결 상태를 확인해 주세요.</p>
            <Button
              variant="outline"
              className="justify-self-start"
              onClick={() => void invitation.refetch()}
            >
              다시 불러오기
            </Button>
          </div>
        ) : (
          <>
            <p className="text-[13px] font-medium text-muted-foreground">
              프로젝트 초대
            </p>
            {/* MEM-A04 수정안 요청 필드(inviter·project_description·invited_email)는 서버가 줄 때만 보인다. */}
            <div className="mt-3 rounded-2xl border p-5">
              <h2 className="text-2xl font-bold">
                {invitation.data.project_name}
              </h2>
              {invitation.data.project_description && (
                <p className="mt-1.5 line-clamp-2 text-sm text-muted-foreground">
                  {invitation.data.project_description}
                </p>
              )}
              {invitation.data.inviter && (
                <p className="mt-3 text-[13px] text-[#57534e]">
                  {invitation.data.inviter.name}님(
                  {invitation.data.inviter.email})이 초대했어요
                </p>
              )}
              {invitation.data.invited_email && (
                <p className="mt-1 text-[13px] text-[#57534e]">
                  초대받은 이메일 {invitation.data.invited_email}
                </p>
              )}
            </div>
            {signedIn ? (
              otherAccount || (accept.error && !joined) ? (
                otherAccount ? (
                  <>
                    <div
                      role="alert"
                      className="mt-5 flex gap-2.5 rounded-[14px] border border-[#c99e1e]/45 bg-primary/12 p-4 text-sm leading-[22px]"
                    >
                      <TriangleAlert className="mt-0.5 size-[18px] shrink-0 text-[#8a6a00]" />
                      <div>
                        <p className="font-semibold">다른 계정일 수 있어요</p>
                        <p className="mt-1 text-[#57534e]">
                          이 초대를 지금 계정({session.data?.email})으로는 열 수
                          없어요. 초대받은 이메일 계정으로 다시 로그인해 주세요.
                        </p>
                      </div>
                    </div>
                    <Button
                      className="mt-6 h-11 w-full rounded-xl text-[15px] font-bold"
                      onClick={() => void relogin()}
                    >
                      초대받은 계정으로 다시 로그인
                    </Button>
                    <Link
                      to="/"
                      className="mt-3 block text-center text-sm text-muted-foreground"
                    >
                      나의 도시로
                    </Link>
                  </>
                ) : (
                  <div className="mt-5 grid gap-3">
                    <p role="alert" className="text-sm text-destructive">
                      {getApiErrorMessage(accept.error)}
                    </p>
                    <Link to="/" className="text-sm text-muted-foreground">
                      나의 도시로
                    </Link>
                  </div>
                )
              ) : (
                <>
                  <dl className="mt-5 grid grid-cols-[auto_minmax(0,1fr)] gap-x-4 gap-y-1.5 border-t px-1 pt-4 text-[13px]">
                    <dt className="text-muted-foreground">로그인한 계정</dt>
                    <dd>{session.data?.email}</dd>
                  </dl>
                  <p
                    role="status"
                    className="mt-6 flex h-11 items-center justify-center gap-2 rounded-xl bg-[#fbfaf6] text-[15px] font-semibold"
                  >
                    <LoaderCircle className="size-[18px] animate-spin text-[#8a6a00]" />
                    팀에 들어가는 중이에요
                  </p>
                  <p className="mt-3 text-center text-[13px] text-muted-foreground">
                    모르는 초대라면 들어간 뒤 설정에서 프로젝트를 나갈 수
                    있어요.
                  </p>
                </>
              )
            ) : (
              <>
                <Button
                  asChild
                  className="mt-5 h-11 w-full rounded-xl text-[15px] font-bold"
                >
                  <Link to={`/login${query}`}>로그인하고 프로젝트 열기</Link>
                </Button>
                <p className="mt-4 text-center text-sm text-muted-foreground">
                  계정이 없나요?{' '}
                  <Link
                    to={`/register${query}`}
                    className="font-semibold text-foreground"
                  >
                    이 이메일로 회원가입하기
                  </Link>
                </p>
              </>
            )}
          </>
        )}
      </section>
    </main>
  )
}
