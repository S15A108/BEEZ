import { useState } from 'react'
import { Link, useNavigate } from 'react-router'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, TriangleAlert } from 'lucide-react'
import { getApiErrorCode, getApiErrorMessage } from '@/api/error'
import type { Project } from '@/api/contracts/common'
import { authApi, projectsApi } from '@/api/services'
import { RequireAccount } from '@/features/auth/RequireAccount'
import type { BlockedProject } from '@/features/auth/schemas'
import { ActionForm } from '@/components/ActionForm'
import { AsyncState } from '@/components/AsyncState'

const checkKey = ['projects', 'withdrawal-check']
type Check = { blockers: BlockedProject[]; solo: Project[] }
// 내가 팀장인데 막히지 않은 프로젝트는 혼자 쓰는 프로젝트라 탈퇴하면 함께 지워진다.
const soloOf = (led: Project[], blockers: BlockedProject[]) =>
  led.filter(
    (item) => !blockers.some((blocker) => blocker.project_id === item.id),
  )

// 회원 탈퇴는 가운데 한 장 카드(캔버스 SCR-05-v2-withdraw): 탈퇴하면 프로젝트가 어떻게 되는지, 막는 프로젝트와 이유, 확인 폼.
function AccountWithdraw() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [agreed, setAgreed] = useState(false)
  // 막는 프로젝트는 서버 판정(ACC-A13)을 쓴다. 참고용이고 최종 판정은 탈퇴 요청 때 서버가 다시 한다(ACC-A10 409).
  const check = useQuery({
    queryKey: checkKey,
    queryFn: async (): Promise<Check> => {
      const [result, projects] = await Promise.all([
        authApi.withdrawalCheck(),
        projectsApi.list({ limit: 100 }),
      ])
      return {
        blockers: result.blocked_projects,
        solo: soloOf(
          projects.items.filter((item) => item.my_role === 'LEADER'),
          result.blocked_projects,
        ),
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
        <AsyncState query={check}>
          {({ blockers, solo }) => (
            <>
              <section
                aria-labelledby="after-title"
                className="mt-5 rounded-[14px] bg-[#f7f5ef] p-4"
              >
                <h3 id="after-title" className="text-sm font-semibold">
                  탈퇴하면 기존 프로젝트는 이렇게 돼요
                </h3>
                {/* 서버(PR #118) 처리: 팀원 프로젝트에서는 빠지고, 혼자 쓰는 프로젝트는 지우며, 남긴 기록은 '탈퇴한 사용자'로 보인다. */}
                <ul className="mt-2 grid gap-1 text-[13px] leading-5">
                  {[
                    '팀원으로 참여한 프로젝트에서는 자동으로 빠져요.',
                    `혼자 쓰는 프로젝트${solo.length ? `(${solo.map((item) => item.name).join(', ')})` : ''}는 함께 삭제돼요.`,
                    "팀 프로젝트에 남긴 문서와 작업 기록은 '탈퇴한 사용자'로 남아요.",
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
                    팀원이 있는 프로젝트의 팀장이에요. 팀장을 위임하면 탈퇴할 수
                    있어요.
                  </p>
                  <ul className="mt-3 grid gap-2">
                    {blockers.map((project) => (
                      <li
                        key={project.project_id}
                        className="flex items-center justify-between gap-3 rounded-[10px] bg-card px-3 py-2.5 text-sm ring-1 ring-border"
                      >
                        <span>
                          <strong className="font-semibold">
                            {project.name}
                          </strong>
                          {/* member_count는 본인을 뺀 활성 팀원 수다. */}
                          <span className="block text-xs text-muted-foreground">
                            팀장 · 팀원 {project.member_count}명
                          </span>
                        </span>
                        {/* 팀장 위임(MEM-A08). 프로젝트 삭제 링크는 설정 창이 팀원이 있어도 삭제를 허용하면(S15P31A108-282) 다시 넣는다. */}
                        <Link
                          to={`/projects/${project.project_id}?settings=team`}
                          className="shrink-0 text-[13px] font-semibold underline underline-offset-3"
                        >
                          팀장 위임
                        </Link>
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
                    try {
                      await authApi.withdraw({ password: values.password })
                    } catch (cause) {
                      // 화면을 연 뒤 팀 프로젝트의 팀장이 됐으면 409 leader_of_team_projects다. 그사이 새로 혼자 쓰게 된
                      // 프로젝트도 있을 수 있어 409 details로 손대지 않고 탈퇴 가능 여부(ACC-A13)와 프로젝트 목록을 다시 읽는다.
                      if (getApiErrorCode(cause) === 'leader_of_team_projects')
                        await queryClient.invalidateQueries({
                          queryKey: checkKey,
                        })
                      throw cause
                    }
                    queryClient.clear()
                    // 랜딩 페이지가 정해지면 그쪽으로 보낸다.
                    navigate('/login')
                  }}
                  // 비밀번호가 틀리면 400 invalid_credentials다(세션은 그대로). 로그인 문구의 '이메일'은 빼고 알린다.
                  errorMessage={(cause) =>
                    getApiErrorCode(cause) === 'invalid_credentials'
                      ? '비밀번호를 확인해 주세요.'
                      : getApiErrorMessage(cause)
                  }
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
