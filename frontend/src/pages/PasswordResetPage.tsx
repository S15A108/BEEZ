import { Link, useSearchParams } from 'react-router'
import { useState } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import { authApi } from '@/api/services'
import { AsyncState } from '@/components/AsyncState'
import { ActionForm } from '@/components/ActionForm'

export default function PasswordResetPage() {
  const [params] = useSearchParams()
  const token = params.get('token') ?? params.get('reset') ?? ''
  const [complete, setComplete] = useState(false)
  const verification = useQuery({
    queryKey: ['password-reset', token],
    queryFn: () => authApi.verifyResetToken({ token }),
    retry: false,
    enabled: !!token,
  })
  const mutation = useMutation({
    mutationFn: (new_password: string) =>
      authApi.resetPassword({ token, new_password }),
  })
  return (
    <main className="mx-auto mt-16 max-w-lg rounded-3xl border bg-card p-8">
      <h2 className="mb-6 text-2xl font-bold">새 비밀번호 정하기</h2>
      {!token ? (
        <p className="text-sm text-muted-foreground">
          이메일로 받은 재설정 링크를 열어 주세요.
        </p>
      ) : complete ? (
        <p role="status" className="text-sm">
          비밀번호를 변경했어요. 새 비밀번호로 다시 로그인해 주세요.
        </p>
      ) : (
        <AsyncState query={verification}>
          {(data) =>
            data.valid ? (
              <ActionForm
                fields={[
                  {
                    name: 'new_password',
                    label: '새 비밀번호',
                    type: 'password',
                    required: true,
                    min: 8,
                    max: 128,
                  },
                  {
                    name: 'confirm',
                    label: '새 비밀번호 확인',
                    type: 'password',
                    matches: 'new_password',
                    required: true,
                  },
                ]}
                submitLabel="비밀번호 변경"
                onSubmit={async (values) => {
                  await mutation.mutateAsync(values.new_password)
                  setComplete(true)
                }}
              />
            ) : (
              <p role="alert">
                유효하지 않은 링크예요. 새 재설정 링크를 요청해 주세요.
              </p>
            )
          }
        </AsyncState>
      )}
      <Link to="/login" className="mt-6 block text-sm underline">
        로그인으로 돌아가기
      </Link>
    </main>
  )
}
