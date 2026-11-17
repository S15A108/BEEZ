import { useState } from 'react'
import { Link, useNavigate, useSearchParams, useLocation } from 'react-router'
import { useQueryClient } from '@tanstack/react-query'
import { ArrowLeft } from 'lucide-react'
import { authApi } from '@/api/services'
import { getApiErrorMessage } from '@/api/error'
import { ActionForm } from '@/components/ActionForm'
import { Button } from '@/components/ui/button'
import { getApiErrorCode } from '@/api/error'
import { AuthLayout } from '@/features/auth/AuthLayout'
import { invitationQuery } from '@/features/members/invitationQuery'

const savedEmailKey = 'beez.login.email'
// 로그인 뒤 돌아갈 주소. RequireAccount가 보관한 앱 안 주소(/projects/…, /account/… 등)만 쓰고, 다른 사이트로 가는
// 주소(//host, 역슬래시로 시작하는 주소)는 막는다(PR #72 3차 조사 THIRD-AMP-03).
const returnPath = (from?: string) =>
  from?.startsWith('/') &&
  !from.startsWith('//') &&
  !from.startsWith('/\\') &&
  !from.startsWith('/login')
    ? from
    : '/'
const readSavedEmail = () => {
  try {
    return localStorage.getItem(savedEmailKey) ?? ''
  } catch {
    return ''
  }
}

export default function AuthPage() {
  const [params] = useSearchParams()
  const location = useLocation()
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const state = location.state as { from?: string; registered?: string } | null
  const [mode, setMode] = useState<'login' | 'forgot'>('login')
  // 비밀번호 찾기를 보낸 이메일. 있으면 입력 화면 대신 완료 화면을 보인다(캔버스 SCR-01-v2-forgot-temp).
  const [sentTo, setSentTo] = useState('')
  const [notice, setNotice] = useState(
    state?.registered ? '가입했어요. 등록한 계정으로 로그인해 주세요.' : '',
  )
  const [failure, setFailure] = useState('')
  // 실패하면 폼을 다시 그려 이메일은 남기고 비밀번호만 비운다.
  const [attempt, setAttempt] = useState(0)
  const [email, setEmail] = useState(state?.registered ?? readSavedEmail())
  const [remember, setRemember] = useState(Boolean(readSavedEmail()))
  // 초대 화면에서 왔으면 초대 토큰과 메일 링크의 프로젝트 ID를 가입·로그인 뒤까지 넘긴다.
  const invite = invitationQuery(params)
  // 예시 계정은 live 빌드에서 빠지도록 env를 그 자리에서 비교한다.
  const mockDemo =
    import.meta.env.VITE_API_MODE !== 'live' && !email && attempt === 0
  if (mode === 'forgot' && sentTo)
    return (
      <AuthLayout titleId="forgot-sent-title">
        {/* 서버는 이름·이메일이 맞지 않아도 같은 202를 준다(가입 여부를 숨김). 그래서 캔버스의 '보냈어요'로 단정하지
            않고 서버 문구('입력한 정보가 맞으면 …')를 그대로 보인다. */}
        <h2 id="forgot-sent-title" className="text-2xl font-bold">
          메일을 확인해 주세요
        </h2>
        <p className="mt-4 text-sm font-semibold break-all">{sentTo}</p>
        <p role="status" className="mt-2 text-sm text-muted-foreground">
          {notice}
        </p>
        <p className="mt-2 text-sm text-muted-foreground">
          임시 비밀번호로 로그인한 뒤 회원 설정에서 비밀번호를 바꿔 주세요.
        </p>
        <Button
          type="button"
          className="mt-6 h-11 w-full rounded-xl font-bold"
          onClick={() => {
            setEmail(sentTo)
            setSentTo('')
            setNotice('')
            setMode('login')
          }}
        >
          로그인하러 가기
        </Button>
      </AuthLayout>
    )
  if (mode === 'forgot')
    return (
      <AuthLayout titleId="forgot-title">
        <button
          type="button"
          className="inline-flex items-center gap-1.5 text-sm text-muted-foreground hover:text-foreground"
          onClick={() => {
            setMode('login')
            setNotice('')
          }}
        >
          <ArrowLeft className="size-4" />
          로그인
        </button>
        <h2 id="forgot-title" className="mt-4 text-2xl font-bold">
          비밀번호 찾기
        </h2>
        {notice && (
          <p
            role="status"
            className="mt-6 rounded-[10px] bg-muted px-3 py-2.5 text-sub"
          >
            {notice}
          </p>
        )}
        <div className="mt-6">
          <ActionForm
            block
            auth
            fields={[
              // ACC-A07 수정안 요청: 가입 때 쓴 이름을 이메일과 함께 보내 대조한다.
              {
                name: 'name',
                label: '이름',
                required: true,
                max: 40,
                placeholder: '가입할 때 입력한 이름',
              },
              {
                name: 'email',
                label: '이메일',
                type: 'email',
                required: true,
                value: email,
                placeholder: 'name@example.com',
              },
            ]}
            submitLabel="임시 비밀번호 받기"
            // 같은 이메일로 곧바로 다시 요청하거나 한도를 넘으면 429 rate_limited다(ACC-A07, PR #118).
            errorMessage={(cause) =>
              getApiErrorCode(cause) === 'rate_limited'
                ? '임시 비밀번호를 너무 자주 요청했어요. 잠시 뒤에 다시 시도해 주세요.'
                : getApiErrorMessage(cause)
            }
            onSubmit={async (values) => {
              const result = await authApi.forgotPassword({
                email: values.email,
                name: values.name.trim(),
              })
              setNotice(result.message)
              setSentTo(values.email)
            }}
          />
        </div>
      </AuthLayout>
    )
  return (
    <AuthLayout titleId="auth-title">
      <h2 id="auth-title" className="text-2xl font-bold">
        로그인
      </h2>
      <p className="mt-2 text-sm text-muted-foreground">
        나의 프로젝트로 돌아가요.
      </p>
      {(notice || failure) && (
        <p
          role={failure ? 'alert' : 'status'}
          className={
            failure
              ? 'mt-6 rounded-[10px] bg-[#fdecec] px-3 py-2.5 text-sub text-[#a3141b]'
              : 'mt-6 rounded-[10px] bg-muted px-3 py-2.5 text-sub'
          }
        >
          {failure || notice}
        </p>
      )}
      <div className={notice || failure ? 'mt-4' : 'mt-7'}>
        <ActionForm
          key={attempt}
          block
          auth
          fields={[
            {
              name: 'email',
              label: '이메일',
              type: 'email',
              required: true,
              value: mockDemo ? 'demo@beez.local' : email,
            },
            {
              name: 'password',
              label: '비밀번호',
              type: 'password',
              required: true,
              max: 128,
              value: mockDemo ? 'beez-demo-2026' : '',
            },
          ]}
          footer={
            <div className="flex flex-wrap items-center gap-5 text-sm">
              <label className="inline-flex min-h-6 items-center gap-2">
                <input
                  type="checkbox"
                  className="size-4 accent-primary"
                  checked={remember}
                  onChange={(event) => setRemember(event.target.checked)}
                />
                아이디 저장
              </label>
              {/* 로그인 유지는 서버에 remember_me가 없어(ACC-A02 수정안 요청, Q04) 세션이 2시간 고정이라 숨겼다. 생기면 체크박스를 다시 두고 로그인 요청에 넣는다. */}
            </div>
          }
          submitLabel="로그인"
          onSubmit={async (values) => {
            setFailure('')
            try {
              const account = await authApi.login({
                email: values.email,
                password: values.password,
              })
              try {
                if (remember) localStorage.setItem(savedEmailKey, values.email)
                else localStorage.removeItem(savedEmailKey)
              } catch {
                // 저장소를 못 쓰는 브라우저에서는 아이디 저장만 건너뛴다.
              }
              // 로그인 전 세션의 /me가 늦게 와 새 계정을 덮지 않도록 진행 중인 계정 조회를 먼저 취소한다
              // (PR #72 3차 조사 THIRD-AMP-01). 로그아웃은 queryClient.clear()가 같은 일을 한다.
              await queryClient.cancelQueries({ queryKey: ['account'] })
              queryClient.setQueryData(['account'], account)
              await queryClient.invalidateQueries({
                queryKey: ['projects'],
              })
              navigate(invite ? `/invite${invite}` : returnPath(state?.from))
            } catch (cause) {
              setNotice('')
              setFailure(
                getApiErrorCode(cause) === 'invalid_credentials'
                  ? '이메일 또는 비밀번호가 맞지 않아요. 다시 확인해 주세요.'
                  : getApiErrorMessage(cause),
              )
              setEmail(values.email)
              setAttempt((value) => value + 1)
            }
          }}
        />
      </div>
      <nav
        aria-label="계정 도움"
        className="mt-6 flex items-center justify-center gap-3 text-sm"
      >
        <Link
          to={`/register${invite}`}
          className="inline-flex min-h-6 items-center font-semibold"
        >
          회원가입
        </Link>
        <span aria-hidden="true" className="h-3 w-px bg-border" />
        <button
          type="button"
          className="inline-flex min-h-6 items-center text-muted-foreground"
          onClick={() => {
            setMode('forgot')
            setNotice('')
            setFailure('')
          }}
        >
          비밀번호 찾기
        </button>
      </nav>
    </AuthLayout>
  )
}
