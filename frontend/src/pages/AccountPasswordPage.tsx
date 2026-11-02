import { Link, useNavigate } from 'react-router'
import { ArrowLeft } from 'lucide-react'
import { authApi } from '@/api/services'
import { AccountLayout } from '@/features/auth/AccountLayout'
import { RequireAccount } from '@/features/auth/RequireAccount'
import { passwordRule } from '@/features/auth/passwordRule'
import { ActionForm } from '@/components/ActionForm'

// 회원 설정 틀 안의 비밀번호 변경(캔버스 SCR-05-v2-password). 바꾸거나 취소하면 회원 설정으로 돌아간다.
function AccountPassword() {
  const navigate = useNavigate()
  return (
    <AccountLayout>
      <section
        aria-labelledby="password-title"
        className="max-w-[560px] rounded-[14px] bg-card px-8 py-7 ring-1 ring-foreground/10"
      >
        <Link
          to="/account"
          className="inline-flex items-center gap-1.5 text-sm text-muted-foreground hover:text-foreground"
        >
          <ArrowLeft className="size-4" />
          회원 설정
        </Link>
        <h3 id="password-title" className="mt-4 text-2xl font-bold">
          비밀번호 변경
        </h3>
        <p className="mt-2 text-sm text-muted-foreground">
          현재 비밀번호를 확인한 뒤 새 비밀번호로 바꿔요.
        </p>
        <div className="mt-6">
          <ActionForm
            auth
            block
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
                required: true,
                check: passwordRule,
              },
              {
                name: 'confirm',
                label: '새 비밀번호 확인',
                type: 'password',
                required: true,
                matches: 'new_password',
              },
            ]}
            submitLabel="비밀번호 변경"
            onCancel={() => navigate('/account')}
            onSubmit={async (values) => {
              await authApi.changePassword({
                current_password: values.current_password,
                new_password: values.new_password,
              })
              navigate('/account', {
                state: {
                  toast:
                    '비밀번호를 바꿨어요. 다음 로그인부터 새 비밀번호를 쓰세요.',
                },
              })
            }}
          />
        </div>
      </section>
    </AccountLayout>
  )
}

export default function AccountPasswordPage() {
  return (
    <RequireAccount>
      <AccountPassword />
    </RequireAccount>
  )
}
