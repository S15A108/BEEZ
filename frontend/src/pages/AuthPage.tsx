import { useState } from 'react'
import { Link, useNavigate, useSearchParams, useLocation } from 'react-router'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { ArrowRight, FileCheck, MessageCircle, Network } from 'lucide-react'
import { authApi } from '@/api/services'
import { ActionForm } from '@/components/ActionForm'
import { Button } from '@/components/ui/button'
import { apiMode } from '@/lib/apiMode'

export default function AuthPage() {
  const [mode, setMode] = useState<'login' | 'register' | 'forgot'>('login')
  const [notice, setNotice] = useState('')
  const [registeredEmail, setRegisteredEmail] = useState('')
  const [params] = useSearchParams()
  const location = useLocation()
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const mutation = useMutation({
    mutationFn: (action: () => Promise<void>) => action(),
  })
  return (
    <main className="mx-auto grid min-h-[calc(100dvh-4rem)] max-w-6xl items-center gap-14 px-6 py-12 lg:grid-cols-2 lg:px-10">
      <section className="space-y-7">
        <p className="text-xs font-semibold tracking-[0.24em] text-muted-foreground">
          YOUR TEAM, YOUR LITTLE CITY
        </p>
        <h2 className="text-4xl leading-tight font-bold sm:text-5xl">
          우리의 프로젝트가
          <br />
          <span className="text-primary">하나의 공간으로.</span>
        </h2>
        <p className="max-w-md text-base leading-8 text-muted-foreground">
          사람과 AI 팀장 비비가 함께 일하는 곳.
          <br />
          설계의 근거를 공유하고, 변경을 함께 검토하세요.
        </p>
        <div className="grid gap-4 pt-5">
          {[
            [FileCheck, '공용 문서와 승인 기준'],
            [MessageCircle, '근거를 기억하는 비비'],
            [Network, '작업과 동료를 연결하는 맥락'],
          ].map(([Icon, label]) => {
            const Glyph = Icon as typeof FileCheck
            return (
              <div
                key={String(label)}
                className="flex items-center gap-3 text-sm"
              >
                <span className="grid size-10 place-items-center rounded-xl border bg-card">
                  <Glyph className="size-4" />
                </span>
                {String(label)}
              </div>
            )
          })}
        </div>
      </section>
      <section className="rounded-3xl border bg-card p-7 shadow-sm sm:p-10">
        <div className="mb-8">
          <p className="text-xs font-medium tracking-widest text-muted-foreground">
            WELCOME TO BEEZ
          </p>
          <h2 className="mt-3 text-2xl font-bold">
            {mode === 'register'
              ? '첫 프로젝트를 준비하세요'
              : mode === 'forgot'
                ? '비밀번호 찾기'
                : '나의 프로젝트로 돌아가기'}
          </h2>
        </div>
        {mode !== 'forgot' && (
          <div className="mb-6 grid grid-cols-2 rounded-xl bg-muted p-1">
            {(['login', 'register'] as const).map((value) => (
              <Button
                key={value}
                variant={mode === value ? 'secondary' : 'ghost'}
                onClick={() => {
                  setMode(value)
                  setNotice('')
                }}
              >
                {value === 'login' ? '로그인' : '회원가입'}
              </Button>
            ))}
          </div>
        )}
        {notice && (
          <p
            role="status"
            className="mb-5 rounded-lg bg-primary/10 p-3 text-sm"
          >
            {notice}
          </p>
        )}
        <ActionForm
          key={mode}
          fields={[
            ...(mode === 'register'
              ? [{ name: 'name', label: '이름', required: true, max: 40 }]
              : []),
            {
              name: 'email',
              label: '이메일',
              type: 'email',
              required: true,
              value:
                mode === 'login'
                  ? registeredEmail ||
                    (apiMode === 'mock' ? 'demo@beez.local' : '')
                  : '',
            },
            ...(mode !== 'forgot'
              ? [
                  {
                    name: 'password',
                    label: '비밀번호',
                    type: 'password' as const,
                    required: true,
                    min: 8,
                    max: 128,
                    value:
                      apiMode === 'mock' && mode === 'login' && !registeredEmail
                        ? 'beez-demo-2026'
                        : '',
                  },
                ]
              : []),
          ]}
          submitLabel={
            mode === 'login'
              ? '로그인하기 →'
              : mode === 'register'
                ? '회원가입'
                : '재설정 링크 요청'
          }
          onSubmit={(values) =>
            mutation.mutateAsync(async () => {
              if (mode === 'register') {
                await authApi.register({
                  name: values.name,
                  email: values.email,
                  password: values.password,
                })
                setRegisteredEmail(values.email)
                setMode('login')
                setNotice('가입했어요. 등록한 계정으로 로그인해 주세요.')
              } else if (mode === 'forgot') {
                const result = await authApi.forgotPassword({
                  email: values.email,
                })
                setNotice(result.message)
              } else {
                const account = await authApi.login({
                  email: values.email,
                  password: values.password,
                })
                queryClient.setQueryData(['account'], account)
                await queryClient.invalidateQueries({ queryKey: ['projects'] })
                const invite = params.get('invite')
                const previous = location.state as { from?: string } | null
                navigate(
                  invite
                    ? `/invite?token=${encodeURIComponent(invite)}`
                    : previous?.from?.startsWith('/projects/')
                      ? previous.from
                      : '/',
                )
              }
            })
          }
        />
        <div className="mt-6 flex items-center justify-between text-xs text-muted-foreground">
          <button
            type="button"
            onClick={() => {
              setMode(mode === 'forgot' ? 'login' : 'forgot')
              setNotice('')
            }}
          >
            {mode === 'forgot'
              ? '로그인으로 돌아가기'
              : '비밀번호를 잊으셨나요?'}
          </button>
          {apiMode === 'mock' && (
            <Link to="/reset-password" className="flex items-center gap-1">
              재설정 화면 <ArrowRight className="size-3" />
            </Link>
          )}
        </div>
      </section>
    </main>
  )
}
