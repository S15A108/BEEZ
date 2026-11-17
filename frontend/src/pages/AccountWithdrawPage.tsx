import { useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { TriangleAlert } from 'lucide-react'
import { getApiErrorCode, getHandoverErrorMessage } from '@/api/error'
import type { Project } from '@/api/contracts/common'
import { authApi, projectsApi } from '@/api/services'
import { AccountLayout } from '@/features/auth/AccountLayout'
import { RequireAccount } from '@/features/auth/RequireAccount'
import type { BlockedProject } from '@/features/auth/schemas'
import { ActionForm } from '@/components/ActionForm'
import { AsyncState } from '@/components/AsyncState'
import { Button } from '@/components/ui/button'

const checkKey = ['projects', 'withdrawal-check']
type Check = { blockers: BlockedProject[]; solo: Project[] }
// 내가 팀장인데 막히지 않은 프로젝트는 혼자 쓰는 프로젝트라 탈퇴하면 함께 지워진다.
const soloOf = (led: Project[], blockers: BlockedProject[]) =>
  led.filter(
    (item) => !blockers.some((blocker) => blocker.project_id === item.id),
  )

// 회원 탈퇴(캔버스 SCR-05-v2-withdraw)는 회원 설정과 같은 틀이다: 막는 프로젝트와 해결 길, 탈퇴하면 프로젝트가 어떻게 되는지, 확인 폼.
function AccountWithdraw() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [agreed, setAgreed] = useState(false)
  // 탈퇴를 누른 뒤 409로 막혔는지. 다시 읽어 막힘 안내가 생기면 누른 버튼(폼)이 사라지므로 포커스를 그 안내로 옮겨 알린다.
  const blockedOnSubmit = useRef(false)
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
    <AccountLayout title="회원 탈퇴" toAccount>
      <AsyncState query={check}>
        {({ blockers, solo }) => (
          <>
            {/* 막혔으면 이유와 해결 길만 보이고, 쓸 수 없는 확인 폼은 정리한 뒤(화면을 다시 열면 다시 판정한다) 보인다. */}
            {blockers.length > 0 && (
              <section
                aria-labelledby="blocked-title"
                tabIndex={-1}
                ref={(node) => {
                  if (!node || !blockedOnSubmit.current) return
                  blockedOnSubmit.current = false
                  node.focus()
                }}
                className="rounded-[14px] bg-card p-6 ring-1 ring-destructive/25"
              >
                <h3
                  id="blocked-title"
                  className="flex items-center gap-2 font-semibold text-[#c10007]"
                >
                  <TriangleAlert aria-hidden="true" className="size-4" />이
                  프로젝트 때문에 지금은 탈퇴할 수 없어요
                </h3>
                <p className="mt-1.5 text-[13px] leading-5 text-[#57534e]">
                  팀원이 있는 프로젝트의 팀장이에요. 팀장을 위임하거나
                  프로젝트를 삭제하면 탈퇴할 수 있어요.
                </p>
                <ul className="mt-4 divide-y border-t">
                  {blockers.map((project) => (
                    <li
                      key={project.project_id}
                      className="flex flex-wrap items-center justify-between gap-3 py-3 text-sm"
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
                      {/* 팀장 위임(MEM-A08) 또는 프로젝트 삭제(PRJ-A05, 팀원이 있어도 지울 수 있다). 삭제는 팀장의 나가기 창에 있다. */}
                      <span className="flex shrink-0 items-center gap-2">
                        <Button variant="outline" asChild>
                          <Link
                            to={`/projects/${project.project_id}?settings=team`}
                          >
                            팀장 위임
                          </Link>
                        </Button>
                        <Button
                          variant="outline"
                          className="text-[#c10007]"
                          asChild
                        >
                          <Link
                            to={`/projects/${project.project_id}?settings=remove`}
                          >
                            프로젝트 삭제
                          </Link>
                        </Button>
                      </span>
                    </li>
                  ))}
                </ul>
              </section>
            )}
            <section
              aria-labelledby="after-title"
              className="rounded-[14px] bg-card p-6 ring-1 ring-foreground/10"
            >
              <h3 id="after-title" className="font-semibold">
                탈퇴하면 기존 프로젝트는 이렇게 돼요
              </h3>
              {/* 서버(PR #118·#182) 처리: 팀원 프로젝트에서는 빠지며 맡은 열린 작업의 담당자를 비우고, 혼자 쓰는 프로젝트는 지우며, 남긴 기록은 '탈퇴한 사용자'로 보인다. */}
              <ul className="mt-2 grid gap-1 text-[13px] leading-5">
                {[
                  '팀원으로 참여한 프로젝트에서는 자동으로 빠져요.',
                  '그 프로젝트에서 맡은 열린 작업은 담당자가 비워져요.',
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
              {blockers.length === 0 && (
                <div className="mt-6 border-t pt-6">
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
                    disabled={!agreed}
                    onCancel={() => navigate('/account')}
                    onSubmit={async (values) => {
                      try {
                        await authApi.withdraw({ password: values.password })
                      } catch (cause) {
                        // 화면을 연 뒤 팀 프로젝트의 팀장이 됐으면 409 leader_of_team_projects다. 그사이 새로 혼자 쓰게 된
                        // 프로젝트도 있을 수 있어 409 details로 손대지 않고 탈퇴 가능 여부(ACC-A13)와 프로젝트 목록을 다시 읽는다.
                        // 다시 읽어 막는 프로젝트가 생기면 이 폼은 거두고 그 이유를 보인다.
                        if (
                          getApiErrorCode(cause) === 'leader_of_team_projects'
                        ) {
                          blockedOnSubmit.current = true
                          await queryClient.invalidateQueries({
                            queryKey: checkKey,
                          })
                        }
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
                        : getHandoverErrorMessage(cause)
                    }
                  />
                </div>
              )}
            </section>
          </>
        )}
      </AsyncState>
    </AccountLayout>
  )
}

export default function AccountWithdrawPage() {
  return (
    <RequireAccount>
      <AccountWithdraw />
    </RequireAccount>
  )
}
