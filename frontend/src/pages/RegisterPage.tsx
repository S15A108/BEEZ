import { useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { getApiErrorCode } from '@/api/error'
import { authApi } from '@/api/services'
import { ActionForm, type FormField } from '@/components/ActionForm'
import { EmailVerification } from '@/features/auth/EmailVerification'
import { PASSWORD_HINT, passwordRule } from '@/features/auth/passwordRule'

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
  const [verified, setVerified] = useState<{
    email: string
    verified_token: string
  } | null>(null)
  const [needVerify, setNeedVerify] = useState(false)
  // 토큰이 만료·사용됐다는 가입 오류(email_not_verified) 뒤에 인증 칸을 처음 상태로 다시 그린다.
  const [verifyKey, setVerifyKey] = useState(0)
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
              {
                name: 'verification',
                label: '이메일',
                custom: (
                  <EmailVerification
                    key={verifyKey}
                    needed={needVerify}
                    onVerified={setVerified}
                  />
                ),
              },
              ...passwordFields,
            ]}
            submitLabel="가입하기"
            onSubmit={async (values) => {
              if (!verified) {
                setNeedVerify(true)
                return
              }
              try {
                await authApi.register({
                  name: values.name,
                  email: verified.email,
                  password: values.password,
                  verified_token: verified.verified_token,
                })
              } catch (cause) {
                if (getApiErrorCode(cause) === 'email_not_verified') {
                  setVerified(null)
                  setVerifyKey((key) => key + 1)
                }
                throw cause
              }
              navigate(login, { state: { registered: verified.email } })
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
