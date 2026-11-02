import { useState } from 'react'
import { Link, useNavigate } from 'react-router'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, TriangleAlert } from 'lucide-react'
import { authApi, membersApi, projectsApi } from '@/api/services'
import { RequireAccount } from '@/features/auth/RequireAccount'
import { ActionForm } from '@/components/ActionForm'
import { AsyncState } from '@/components/AsyncState'

// 회원 탈퇴는 가운데 한 장 카드(캔버스 SCR-05-v2-withdraw): 탈퇴하면 프로젝트가 어떻게 되는지, 막는 프로젝트와 이유, 확인 폼.
function AccountWithdraw() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [agreed, setAgreed] = useState(false)
  // API 없음: 탈퇴를 막는 프로젝트 목록은 서버가 아직 주지 않는다.
  // 그 전까지는 내가 팀장인 프로젝트의 팀원 수를 화면에서 센다. 최종 판정은 탈퇴 요청 때 서버가 한다.
  const projects = useQuery({
    queryKey: ['projects', 'withdraw-blockers'],
    queryFn: async () => {
      const projects = await projectsApi.list({ limit: 100 })
      const led = projects.items.filter((item) => item.my_role === 'LEADER')
      // 팀원 수는 서버에 있는 팀원 목록(MEM-A02)으로 센다.
      const members = await Promise.all(
        led.map((item) => membersApi.list(item.id, { limit: 100 })),
      )
      const counted = led.map((item, index) => ({
        ...item,
        member_count: members[index].items.length,
      }))
      return {
        blockers: counted.filter((item) => item.member_count > 1),
        solo: counted.filter((item) => item.member_count <= 1),
      }
    },
  })
  return (
    <main className="px-4 pt-12 pb-10">
      <section
        aria-labelledby="withdraw-title"
        className="mx-auto max-w-[520px] rounded-[22px] border bg-card px-10 py-9 shadow-sm"
      >
        <Link
          to="/account"
          className="inline-flex items-center gap-1.5 text-sm text-muted-foreground hover:text-foreground"
        >
          <ArrowLeft className="size-4" />
          회원 설정
        </Link>
        <h2 id="withdraw-title" className="mt-4 text-2xl font-bold">
          회원 탈퇴
        </h2>
        <AsyncState query={projects}>
          {({ blockers, solo }) => (
            <>
              <section
                aria-labelledby="after-title"
                className="mt-5 rounded-[14px] bg-[#f7f5ef] p-4"
              >
                <h3 id="after-title" className="text-sm font-semibold">
                  탈퇴하면 기존 프로젝트는 이렇게 돼요
                </h3>
                {/* 백엔드 결정 대기: 팀원 프로젝트 자동 탈퇴, 개인 프로젝트 삭제 여부에 따라 문구가 바뀐다. */}
                <ul className="mt-2 grid gap-1 text-[13px] leading-5">
                  {[
                    '팀원으로 참여한 프로젝트에서는 자동으로 빠져요.',
                    `혼자 쓰는 프로젝트${solo.length ? `(${solo.map((item) => item.name).join(', ')})` : ''}는 함께 삭제돼요.`,
                    '팀 프로젝트에 남긴 문서와 작업 기록은 보존 정책에 따라 남아요.',
                  ].map((text) => (
                    <li key={text} className="flex gap-2">
                      <span
                        aria-hidden="true"
                        className="mt-2 size-1 shrink-0 rounded-full bg-[#a3a3a3]"
                      />
                      {text}
                    </li>
                  ))}
                </ul>
              </section>
              {blockers.length > 0 && (
                <section
                  aria-labelledby="blocked-title"
                  className="mt-3 rounded-[14px] border border-destructive/20 bg-destructive/[0.04] p-4"
                >
                  <h3
                    id="blocked-title"
                    className="flex items-center gap-2 text-sm font-semibold text-[#c10007]"
                  >
                    <TriangleAlert aria-hidden="true" className="size-4" />이
                    프로젝트 때문에 지금은 탈퇴할 수 없어요
                  </h3>
                  <p className="mt-1.5 text-[13px] leading-5 text-[#57534e]">
                    팀원이 있는 프로젝트의 팀장이에요. 팀장을 위임하거나
                    프로젝트를 삭제하면 탈퇴할 수 있어요.
                  </p>
                  <ul className="mt-3 grid gap-2">
                    {blockers.map((project) => (
                      <li
                        key={project.id}
                        className="flex items-center justify-between gap-3 rounded-[10px] bg-card px-3 py-2.5 text-sm ring-1 ring-border"
                      >
                        <span>
                          <strong className="font-semibold">
                            {project.name}
                          </strong>
                          <span className="block text-xs text-muted-foreground">
                            팀장 · 팀원 {project.member_count - 1}명
                          </span>
                        </span>
                        <span className="flex shrink-0 items-center gap-3 text-[13px] font-semibold [&>a]:underline [&>a]:underline-offset-3">
                          <Link to={`/projects/${project.id}?settings=team`}>
                            팀장 위임
                          </Link>
                          <Link
                            to={`/projects/${project.id}?settings=project`}
                            className="text-[#c10007]"
                          >
                            프로젝트 삭제
                          </Link>
                        </span>
                      </li>
                    ))}
                  </ul>
                </section>
              )}
              <div className="mt-6">
                <ActionForm
                  auth
                  block
                  fields={[
                    {
                      name: 'password',
                      label: '비밀번호 확인',
                      type: 'password',
                      required: true,
                    },
                  ]}
                  footer={
                    <label className="flex cursor-pointer items-start gap-2.5 text-sm">
                      <input
                        type="checkbox"
                        className="mt-0.5 size-4 accent-[#e7000b]"
                        checked={agreed}
                        onChange={(event) => setAgreed(event.target.checked)}
                      />
                      탈퇴하면 이 계정으로 다시 로그인할 수 없다는 것을
                      확인했어요.
                    </label>
                  }
                  submitLabel="회원 탈퇴"
                  destructive
                  disabled={!agreed || blockers.length > 0}
                  onCancel={() => navigate('/account')}
                  onSubmit={async (values) => {
                    await authApi.withdraw({ password: values.password })
                    queryClient.clear()
                    // 랜딩 페이지가 정해지면 그쪽으로 보낸다.
                    navigate('/login')
                  }}
                />
                {blockers.length > 0 && (
                  <p className="mt-4 text-right text-xs text-muted-foreground">
                    위 프로젝트를 정리하면 버튼이 켜져요.
                  </p>
                )}
              </div>
            </>
          )}
        </AsyncState>
      </section>
    </main>
  )
}

export default function AccountWithdrawPage() {
  return (
    <RequireAccount>
      <AccountWithdraw />
    </RequireAccount>
  )
}
