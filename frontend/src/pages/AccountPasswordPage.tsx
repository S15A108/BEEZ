import { useNavigate } from 'react-router'
import { useQueryClient } from '@tanstack/react-query'
import { authApi } from '@/api/services'
import { AccountLayout } from '@/features/auth/AccountLayout'
import { RequireAccount } from '@/features/auth/RequireAccount'
import { passwordRule } from '@/features/auth/passwordRule'
import { ActionForm } from '@/components/ActionForm'

// 회원 설정과 같은 틀(720px 한 열, 돌아가는 길 하나)의 비밀번호 변경(캔버스 SCR-05-v2-password). 바꾸거나 취소하면 회원 설정으로 돌아간다.
function AccountPassword() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  return (
    <AccountLayout title="비밀번호 변경" toAccount>
      <section className="rounded-[14px] bg-card p-6 ring-1 ring-foreground/10">
        <p className="text-sm text-muted-foreground">
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
              // 바꾸면 서버가 must_change_password를 푼다. 이 화면이 계정을 보고 있을 때 다시 불러 안내를 거둔다.
              await queryClient.invalidateQueries({ queryKey: ['account'] })
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
