import { useMutation, useQueryClient } from '@tanstack/react-query'
import { authApi } from '@/api/services'
import { passwordRule } from '@/features/auth/passwordRule'
import { ActionForm } from '@/components/ActionForm'
import { ACCOUNT_MUTATION_KEY } from './useAccountDialog'

// 회원 설정 창(D48) 안의 비밀번호 변경(캔버스 SCR-05-v2-password). 바꾸거나 취소하면 회원 설정으로 돌아간다.
export function AccountPassword({
  onDone,
  onCancel,
}: {
  onDone: (message: string) => void
  onCancel: () => void
}) {
  const queryClient = useQueryClient()
  // 요청 중에는 회원 설정 창이 닫히지 않게 mutation으로 센다. 계정을 다시 불러와 회원 설정으로 돌아갈 때까지가
  // 한 요청이다. 그 전에 X가 켜지면 닫은 뒤 돌아가기가 한 번 더 기록을 되돌린다(PR #316 리뷰).
  const change = useMutation({
    mutationKey: ACCOUNT_MUTATION_KEY,
    mutationFn: (input: Parameters<typeof authApi.changePassword>[0]) =>
      authApi.changePassword(input),
    onSuccess: async () => {
      // 바꾸면 서버가 must_change_password를 푼다. 이 화면이 계정을 보고 있을 때 다시 불러 안내를 거둔다.
      await queryClient.invalidateQueries({ queryKey: ['account'] })
      onDone('비밀번호를 바꿨어요. 다음 로그인부터 새 비밀번호를 쓰세요.')
    },
  })
  return (
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
          onCancel={onCancel}
          onSubmit={(values) =>
            change.mutateAsync({
              current_password: values.current_password,
              new_password: values.new_password,
            })
          }
        />
      </div>
    </section>
  )
}
