import { useState } from 'react'
import type { ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { agentsApi, membersApi } from '@/api/services'
import type { IssueTokenResponse } from './schemas'
import { allTokens } from './allTokens'
import { useAgentCheckStore } from './agentCheckStore'
import { agentStatusLabels } from './agentStatus'
import { agentConnectionsQuery, useAgentStatusOf } from './useAgentStatusOf'
import { getApiErrorMessage, unlessMissing } from '@/api/error'
import { useProjectAction } from '@/features/workspace/useProjectAction'
import { useSession } from '@/features/auth/useSession'
import { AsyncState } from '@/components/AsyncState'
import { PaneSheet } from '@/components/PaneSheet'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import { formatDate } from '@/lib/formatDate'
import { apiMode } from '@/lib/apiMode'
import { cn } from '@/lib/utils'

function Step({
  number,
  title,
  children,
}: {
  number: number
  title: string
  children: ReactNode
}) {
  return (
    <li className="grid grid-cols-[24px_minmax(0,1fr)] gap-3">
      <span className="grid size-6 place-items-center rounded-full bg-[#f3f1eb] text-xs font-bold">
        {number}
      </span>
      <div className="grid min-w-0 gap-2">
        <h4 className="text-sm leading-6 font-semibold">{title}</h4>
        {children}
      </div>
    </li>
  )
}

// 어두운 코드 한 줄과 오른쪽 '복사'(캔버스 SCR-06-v2-settings-agent). 버튼 이름은 무엇을 복사하는지 알린다.
function CodeLine({
  value,
  label,
  disabled,
}: {
  value: string
  label: string
  disabled?: boolean
}) {
  const [copied, setCopied] = useState(false)
  return (
    <div className="flex items-center justify-between gap-3 rounded-[10px] bg-foreground px-3 py-2.5">
      {/* w-0: 줄바꿈 없는 긴 코드가 바깥 grid 칸의 최소 너비를 늘려 '복사'를 창 밖으로 밀지 않게 한다. */}
      <code className="w-0 flex-1 truncate font-mono text-[12.5px] text-[#f5f1e3] select-text">
        {value.split('\n')[0]}
        {value.includes('\n') && ' …'}
      </code>
      <button
        type="button"
        aria-label={label}
        disabled={disabled}
        className="shrink-0 text-xs font-semibold text-primary disabled:opacity-40"
        onClick={() =>
          void navigator.clipboard
            .writeText(value)
            .then(() => setCopied(true))
            .catch(() => setCopied(false))
        }
      >
        {copied ? '복사했어요' : '복사'}
      </button>
    </div>
  )
}

// 연결 도움말(AGT-04)은 새 탭으로 연다. 같은 탭이면 사무실과 설정 창이 닫혀 한 번만 보이는 토큰을 잃는다(D43).
function HelpLink({ section, label }: { section: string; label: string }) {
  return (
    <a
      href={`/help/agent-connection#${section}`}
      target="_blank"
      rel="noreferrer"
      className="shrink-0 text-[12.5px] font-semibold whitespace-nowrap underline underline-offset-3"
    >
      {label}
      <span aria-hidden="true"> ↗</span>
    </a>
  )
}

const tokenSchema = z.object({
  label: z.string().trim().max(40, '40자 이하로 입력해 주세요.'),
})

// 토큰 이름 한 줄 + '토큰 발급'. 한도 초과 같은 발급 오류는 바로 아래에 알린다.
function TokenForm({ onIssue }: { onIssue: (label: string) => Promise<void> }) {
  const [error, setError] = useState('')
  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<z.infer<typeof tokenSchema>>({
    resolver: zodResolver(tokenSchema),
    defaultValues: { label: '내 Claude Code' },
  })
  const message = errors.label?.message ?? error
  return (
    <form
      noValidate
      className="grid gap-1.5"
      onSubmit={handleSubmit(async (values) => {
        setError('')
        try {
          await onIssue(values.label)
        } catch (cause) {
          setError(getApiErrorMessage(cause))
        }
      })}
    >
      <div className="flex gap-2">
        <Input
          aria-label="토큰 이름"
          className="h-10 flex-1"
          aria-invalid={Boolean(message)}
          {...register('label')}
        />
        <Button type="submit" className="h-10" disabled={isSubmitting}>
          토큰 발급
        </Button>
      </div>
      {message && (
        <p role="alert" className="text-xs text-destructive">
          {message}
        </p>
      )}
    </form>
  )
}

// 연결 안내(AGT-A04)가 없을 때 쓰는 고정 단계
const fallbackSteps = [
  '터미널에서 Claude Code를 켜요.',
  '아래 MCP 주소와 발급한 토큰으로 BEEZ MCP 서버를 추가해요.',
  'Claude Code를 다시 시작해요.',
]

// 설정 › 내 에이전트 · 연결 상태 → 토큰 발급 → Claude Code에 BEEZ 추가 → CLAUDE.md 붙여넣기를 한 화면에.
export function AgentsPanel({ projectId }: { projectId: string }) {
  const session = useSession()
  const action = useProjectAction(projectId)
  const [fresh, setFresh] = useState<IssueTokenResponse | null>(null)
  const [revoking, setRevoking] = useState<string | null>(null)
  // 발급 오류(예: 활성 토큰 5개 한도)는 토큰 이름 폼이 알려 주고, 폐기 오류는 폐기 확인 상자에 둔다.
  const [revokeError, setRevokeError] = useState('')
  const setCheckedMember = useAgentCheckStore((state) => state.setCheckedMember)
  const statusOf = useAgentStatusOf(projectId)
  // 토큰(AGT-A01~A03)과 팀원은 따로 불러 연결 안내·현황이 없어도 발급·목록·폐기는 그대로 쓴다.
  const query = useQuery({
    queryKey: ['projects', projectId, 'agent-settings'],
    queryFn: async () => {
      const [tokens, members] = await Promise.all([
        allTokens(projectId),
        membersApi.list(projectId),
      ])
      return { tokens, members }
    },
  })
  // API 없음: AGT-A04·A05(-178 구현 전). 404면 연결 안내는 발급 응답(mcp_url·claude_md_snippet)으로, 연결 현황은 '준비 중'으로 둔다.
  const guide = useQuery({
    queryKey: ['projects', projectId, 'agent-guide'],
    queryFn: () => unlessMissing(agentsApi.connectionGuide(projectId)),
  })
  const agents = useQuery(agentConnectionsQuery(projectId))
  const shownGuide =
    guide.data ??
    (fresh
      ? {
          steps: fallbackSteps,
          mcp_url: fresh.mcp_url,
          claude_md_snippet: fresh.claude_md_snippet,
        }
      : undefined)
  const statusReady = agents.isSuccess && agents.data !== null
  // 재조회 실패에도 이전 data가 남으므로 조회 결과로 가른다. 없는 API(null)는 오류가 아니라 '준비 중'이다.
  const statusFailed = agents.isError
  // 캔버스 SCR-06-v2-settings-agent: 상태 한 줄 → 1 토큰 발급 → 2 Claude Code에 BEEZ 추가 → 3 CLAUDE.md → 내 토큰
  const mcpUrl = shownGuide?.mcp_url
  // MCP는 매 요청 Authorization: Bearer {agent_token}으로 인증한다(02-mcp-tools). 발급 직후에는 그 토큰을 넣고,
  // 이미 발급한 토큰처럼 값을 모르면 자리표시자를 두고 바꿔 넣으라고 안내한다.
  const tokenValue = fresh?.token ?? '<에이전트 토큰>'
  const command = `claude mcp add --transport http beez ${mcpUrl ?? 'https://<BEEZ 서버>/mcp'} --header "Authorization: Bearer ${tokenValue}"`
  return (
    <div className="space-y-5">
      <div>
        <h3 className="text-xl font-bold">내 에이전트</h3>
        <p className="mt-1 text-[13px] leading-5 text-muted-foreground">
          이 프로젝트에서만 쓰는 개인 토큰으로 로컬 Claude Code를 연결해요. 연결
          상태는 에이전트가 보낸 보고를 기준으로 보여 줘요.
        </p>
      </div>
      <AsyncState query={query}>
        {(data) => {
          const me = data.members.items.find(
            (member) => member.account_id === session.data?.id,
          )
          const tokens = data.tokens.filter((token) => !token.revoked_at)
          const { status: state, lastReportAt } = statusOf({
            id: me?.id,
            agent_connected: Boolean(me?.agent_connected),
          })
          const tone = statusFailed ? 'recheck' : state
          // 연결 끊김은 연결 전과 같은 회색이다(사용자 결정 2026-11-09, S15P31A108-341).
          const idle = tone === 'none' || tone === 'disconnected'
          const revokingToken = tokens.find((token) => token.id === revoking)
          return (
            <>
              <div
                role="status"
                className={cn(
                  'flex flex-wrap items-center gap-x-2.5 gap-y-1 rounded-xl px-3.5 py-3 text-[13px]',
                  tone === 'connected'
                    ? 'bg-emerald-50'
                    : tone === 'failed'
                      ? 'bg-[#fdecec]'
                      : idle
                        ? 'bg-muted'
                        : 'bg-primary/14',
                )}
              >
                <span
                  aria-hidden="true"
                  className={cn(
                    'size-2 shrink-0 rounded-full',
                    tone === 'connected'
                      ? 'bg-emerald-600'
                      : tone === 'failed'
                        ? 'bg-[#c10007]'
                        : idle
                          ? 'bg-[#c4c4c4]'
                          : 'bg-[#e0b400]',
                  )}
                />
                <strong
                  className={cn(
                    'font-semibold whitespace-nowrap',
                    tone === 'failed' && 'text-[#a3141b]',
                  )}
                >
                  {statusFailed
                    ? '연결 상태를 확인하지 못했어요'
                    : !statusReady
                      ? '연결 상태 확인은 준비 중이에요'
                      : agentStatusLabels[state]}
                </strong>
                <span className="min-w-0 flex-1 text-[#57534e]">
                  {/* 실패·연결 전에 옛 보고 시각을 붙이면 말이 엇갈린다(보드 '방금 확인 · …'). */}
                  {!statusFailed &&
                    (state === 'failed'
                      ? '방금 확인 · '
                      : state === 'disconnected'
                        ? lastReportAt &&
                          `마지막 통신 ${formatDate(lastReportAt)} · `
                        : (state === 'connected' || state === 'recheck') &&
                          lastReportAt &&
                          `최근 보고 ${formatDate(lastReportAt)} · `)}
                  {statusFailed
                    ? '서버에서 연결 현황을 받지 못했어요. 잠시 뒤 다시 확인해 주세요.'
                    : !statusReady
                      ? '토큰 발급과 연결 안내는 그대로 쓸 수 있어요. 연결 여부는 서버가 준비되면 여기에 보여요.'
                      : state === 'connected'
                        ? '에이전트가 이 프로젝트로 보고하고 있어요.'
                        : state === 'failed'
                          ? '아직 보고가 오지 않았어요. 토큰과 MCP 주소를 확인하고 Claude Code를 다시 시작해 주세요.'
                          : state === 'recheck'
                            ? '상태를 다시 확인해 주세요.'
                            : state === 'disconnected'
                              ? '2시간 넘게 통신이 없거나 세션이 끝났어요. Claude Code를 다시 시작한 뒤 연결 확인을 눌러 주세요.'
                              : '아래 단계를 마친 뒤 연결 확인을 눌러 주세요.'}
                </span>
                {/* 연결 끊김은 다시 연결하는 법(404·409 등)이 문제 해결에 있다(PR #230 리뷰). */}
                {!statusFailed &&
                  (state === 'failed' ||
                    state === 'recheck' ||
                    state === 'disconnected') && (
                    <HelpLink
                      section={
                        state === 'recheck' ? 'check' : 'troubleshooting'
                      }
                      label="연결 도움말"
                    />
                  )}
                {(statusReady || statusFailed) && (
                  <button
                    type="button"
                    className="ml-auto shrink-0 text-[12.5px] font-semibold whitespace-nowrap hover:underline"
                    disabled={agents.isFetching || !me}
                    onClick={() =>
                      void agents
                        .refetch()
                        // refetch()는 실패해도 reject하지 않는다. 연결 현황을 받았을 때만 확인 결과로 남기고, 아니면 앞선 결과도 지운다.
                        .then((result) =>
                          setCheckedMember(
                            result.isSuccess && result.data
                              ? (me?.id ?? null)
                              : null,
                          ),
                        )
                    }
                  >
                    {agents.isFetching
                      ? '확인 중…'
                      : statusFailed || state === 'failed'
                        ? '다시 확인'
                        : '연결 확인'}
                  </button>
                )}
              </div>
              <p className="-mt-3 text-xs text-muted-foreground">
                같은 계정의 연결은 프로젝트마다 하나만 살아 있어요. 다른 PC나
                터미널에서 새로 연결하면 나중 연결이 이기고 먼저 붙어 있던
                연결은 끊겨요.
              </p>
              <ol className="grid gap-4">
                <Step
                  number={1}
                  title={
                    fresh
                      ? `토큰 발급 완료 · ${fresh.token_info.label || '내 에이전트 토큰'}`
                      : '토큰 발급'
                  }
                >
                  {fresh ? (
                    <div
                      className="grid gap-2 rounded-xl border border-[#f0dc9a] bg-primary/10 px-3.5 py-3"
                      aria-label="한 번만 표시하는 새 토큰"
                    >
                      <strong className="text-[13px]">
                        이 화면을 닫으면 토큰 값을 다시 볼 수 없어요.
                      </strong>
                      <CodeLine value={fresh.token} label="토큰 복사" />
                      <span className="text-xs text-muted-foreground">
                        이 프로젝트에서만 써요 · 발급{' '}
                        {formatDate(fresh.token_info.created_at)}
                        {/* API 없음: 토큰이 쓸 수 있는 범위와 만료일(리뷰 M16)이 응답에 없다. */}
                        {apiMode === 'mock' &&
                          ' · 예시 토큰은 실제 서버에 쓸 수 없어요.'}
                      </span>
                      <button
                        type="button"
                        className="justify-self-start text-xs text-muted-foreground underline"
                        onClick={() => setFresh(null)}
                      >
                        값 숨기기
                      </button>
                    </div>
                  ) : (
                    <>
                      <TokenForm
                        onIssue={async (label) => {
                          await action.run(async () => {
                            setFresh(
                              await agentsApi.issueToken(projectId, { label }),
                            )
                          }, '토큰을 발급했어요.')
                        }}
                      />
                      <p className="text-xs text-muted-foreground/70">
                        토큰 값은 발급할 때 한 번만 보여요. 잃어버리면 폐기하고
                        다시 발급해요.
                      </p>
                    </>
                  )}
                </Step>
                <Step number={2} title="Claude Code에 BEEZ 추가">
                  <CodeLine
                    value={command}
                    label="MCP 주소 복사"
                    disabled={!mcpUrl}
                  />
                  <p className="text-xs text-muted-foreground">
                    명령은 등록만 하고 끝나요. 대화 창이 뜨지 않는 게
                    정상이에요.{' '}
                    <HelpLink section="add" label="자세한 연결 방법" />
                  </p>
                  {mcpUrl && !fresh && (
                    <p className="text-xs text-muted-foreground">
                      &lt;에이전트 토큰&gt; 자리에 발급할 때 받은 토큰을 넣어요.
                      토큰 값은 다시 볼 수 없어서, 잃어버렸으면 폐기하고 새로
                      발급해요.
                    </p>
                  )}
                  {!shownGuide && (
                    <p className="text-xs text-muted-foreground">
                      토큰을 발급하면 연결 안내가 나와요.
                    </p>
                  )}
                </Step>
                <Step number={3} title="CLAUDE.md에 작업 절차 붙여넣기">
                  {shownGuide ? (
                    <CodeLine
                      value={shownGuide.claude_md_snippet}
                      label="CLAUDE.md 내용 복사"
                    />
                  ) : (
                    <p className="text-xs text-muted-foreground">
                      토큰을 발급하면 붙여넣을 내용이 나와요.
                    </p>
                  )}
                </Step>
              </ol>
              {tokens.length > 0 && (
                <div className="grid gap-1">
                  <h4 className="text-sm font-semibold">내 토큰</h4>
                  <ul>
                    {tokens.map((token) => (
                      <li
                        key={token.id}
                        className="flex items-center justify-between gap-3 border-b px-1 py-3 text-[13px]"
                      >
                        <span>
                          <strong className="font-semibold">
                            {token.label || '내 에이전트 토큰'}
                          </strong>
                          <span className="text-muted-foreground">
                            {' '}
                            · 발급 {formatDate(token.created_at)} ·{' '}
                            {token.last_used_at
                              ? `최근 호출 ${formatDate(token.last_used_at)}`
                              : '최근 호출 기록 없음'}
                          </span>
                        </span>
                        <Button
                          size="sm"
                          variant="outline"
                          className="border-destructive/35 text-[#c10007]"
                          disabled={action.busy}
                          onClick={() => {
                            setRevokeError('')
                            setRevoking(token.id)
                          }}
                        >
                          폐기
                        </Button>
                      </li>
                    ))}
                  </ul>
                </div>
              )}
              <p className="text-xs text-muted-foreground">
                팀원들의 에이전트 연결과 작업은 대시보드 › 에이전트 현황에서
                봐요.
                {apiMode === 'mock' &&
                  ' 예시 화면은 실제 Claude Code 연결을 하지 않아요.'}
              </p>
              {action.message && <p className="text-sm">{action.message}</p>}
              {revokingToken && (
                <PaneSheet
                  back="에이전트"
                  title="Claude Code 토큰을 폐기할까요?"
                  description={revokingToken.label || '내 에이전트 토큰'}
                  busy={action.busy}
                  onClose={() => setRevoking(null)}
                >
                  <div className="grid gap-4 rounded-xl border border-destructive/30 p-4 text-[13px]">
                    <ul className="grid list-disc gap-1 pl-5">
                      <li>
                        폐기하면 이 토큰을 쓰는 에이전트는 바로 연결이 끊겨요.
                        되돌릴 수 없어요.
                      </li>
                      <li>에이전트가 남긴 보고와 작업 기록은 그대로 남아요.</li>
                      <li>
                        다시 연결하려면 새 토큰을 발급해 MCP 설정에 넣어요.
                      </li>
                    </ul>
                    {revokeError && (
                      <p role="alert" className="text-destructive">
                        {revokeError}
                      </p>
                    )}
                    <span className="flex justify-end gap-2">
                      <Button
                        size="sm"
                        variant="outline"
                        onClick={() => setRevoking(null)}
                      >
                        취소
                      </Button>
                      <Button
                        size="sm"
                        variant="destructive"
                        disabled={action.busy}
                        onClick={() =>
                          void action
                            .run(
                              () => agentsApi.revokeToken(revokingToken.id),
                              '토큰을 폐기했어요.',
                            )
                            .then(() => {
                              setRevoking(null)
                              if (fresh?.token_info.id === revokingToken.id)
                                setFresh(null)
                            })
                            .catch((error) =>
                              setRevokeError(getApiErrorMessage(error)),
                            )
                        }
                      >
                        폐기하기
                      </Button>
                    </span>
                  </div>
                </PaneSheet>
              )}
            </>
          )
        }}
      </AsyncState>
    </div>
  )
}
