import { useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { authApi } from '@/api/services'
import { ActionForm, type FormField } from '@/components/ActionForm'
import { EmailVerification } from '@/features/auth/EmailVerification'
import { PASSWORD_HINT, passwordRule } from '@/features/auth/passwordRule'

// 가입 이메일 인증은 mock 전용이다(emailCode.ts). env를 그 자리에서 비교해 live 빌드에서는 인증 칸과 흉내 코드가 빠진다.
const emailVerification = import.meta.env.VITE_API_MODE !== 'live'

const passwordFields: FormField[] = [
  {
    name: 'password',
    label: '비밀번호',
    type: 'password',
    required: true,
    check: passwordRule,
    hint: PASSWORD_HINT,
  },
  {
    name: 'confirm',
    label: '비밀번호 확인',
    type: 'password',
    required: true,
    matches: 'password',
  },
]

export default function RegisterPage() {
  const [params] = useSearchParams()
  const navigate = useNavigate()
  const [verifiedEmail, setVerifiedEmail] = useState<string | null>(null)
  const [needVerify, setNeedVerify] = useState(false)
  const invite = params.get('invite')
  const login = invite
    ? `/login?invite=${encodeURIComponent(invite)}`
    : '/login'
  return (
    // 캔버스 SCR-01-v2-register: 위쪽에 둔 카드 하나, 이름 → 이메일·인증 코드 → 비밀번호 → 확인 순서
    <main className="px-4 py-10">
      <section
        aria-labelledby="register-title"
        className="mx-auto max-w-[480px] rounded-[22px] border bg-card px-10 py-9 shadow-sm"
      >
        <h2 id="register-title" className="text-2xl font-bold">
          회원가입
        </h2>
        <div className="mt-6">
          <ActionForm
            block
            auth
            fields={[
              { name: 'name', label: '이름', required: true, max: 40 },
              ...(emailVerification
                ? [
                    {
                      name: 'verification',
                      label: '이메일',
                      // 인증 계약(ACC-A11·A12)이 없는 live 모드는 인증 없이 가입한다. emailCode.ts 참고
                      custom: (
                        <EmailVerification
                          needed={needVerify}
                          onVerified={setVerifiedEmail}
                        />
                      ),
                    },
                  ]
                : [
                    {
                      name: 'email',
                      label: '이메일',
                      type: 'email',
                      required: true,
                    } as const,
                  ]),
              ...passwordFields,
            ]}
            submitLabel="가입하기"
            onSubmit={async (values) => {
              const email = emailVerification ? verifiedEmail : values.email
              if (!email) {
                setNeedVerify(true)
                return
              }
              await authApi.register({
                name: values.name,
                email,
                password: values.password,
              })
              navigate(login, { state: { registered: email } })
            }}
          />
        </div>
        <p className="mt-5 text-center text-sm text-muted-foreground">
          이미 계정이 있나요?{' '}
          <Link to={login} className="font-semibold text-foreground">
            로그인
          </Link>
        </p>
      </section>
    </main>
  )
}
