import { useState } from 'react'
import type { ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { agentsApi, membersApi } from '@/api/services'
import type { IssueTokenResponse } from './schemas'
import { allTokens } from './allTokens'
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
      <code className="min-w-0 truncate font-mono text-[12.5px] text-[#f5f1e3] select-text">
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
  const [checked, setChecked] = useState(false)
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
  const agents = useQuery({
    queryKey: ['projects', projectId, 'agent-connections'],
    queryFn: () => unlessMissing(agentsApi.list(projectId)),
  })
  const shownGuide =
    guide.data ??
    (fresh
      ? {
          steps: fallbackSteps,
          mcp_url: fresh.mcp_url,
          claude_md_snippet: fresh.claude_md_snippet,
        }
      : undefined)
  const statusReady = agents.isSuccess && agents.data !== undefined
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
          const mine = agents.data?.items.find(
            (agent) => agent.member_id === me?.id,
          )
          const tokens = data.tokens.filter((token) => !token.revoked_at)
          const state = mine?.connected
            ? mine.status_check_required
              ? 'recheck'
              : 'connected'
            : checked
              ? 'failed'
              : 'none'
          const revokingToken = tokens.find((token) => token.id === revoking)
          return (
            <>
              <div
                role="status"
                className={cn(
                  'flex flex-wrap items-center gap-x-2.5 gap-y-1 rounded-xl px-3.5 py-3 text-[13px]',
                  state === 'connected'
                    ? 'bg-emerald-50'
                    : state === 'failed'
                      ? 'bg-[#fdecec]'
                      : state === 'none'
                        ? 'bg-muted'
                        : 'bg-primary/14',
                )}
              >
                <span
                  aria-hidden="true"
                  className={cn(
                    'size-2 shrink-0 rounded-full',
                    state === 'connected'
                      ? 'bg-emerald-600'
                      : state === 'failed'
                        ? 'bg-[#c10007]'
                        : state === 'none'
                          ? 'bg-[#c4c4c4]'
                          : 'bg-[#e0b400]',
                  )}
                />
                <strong
                  className={cn(
                    'font-semibold whitespace-nowrap',
                    state === 'failed' && 'text-[#a3141b]',
                  )}
                >
                  {!statusReady
                    ? '연결 상태 확인은 준비 중이에요'
                    : state === 'connected'
                      ? '연결됨'
                      : state === 'recheck'
                        ? '연결 미확인'
                        : state === 'failed'
                          ? '연결 실패'
                          : '연결 전'}
                </strong>
                <span className="min-w-0 flex-1 text-[#57534e]">
                  {mine?.last_report_at &&
                    `최근 보고 ${formatDate(mine.last_report_at)} · `}
                  {!statusReady
                    ? '토큰 발급과 연결 안내는 그대로 쓸 수 있어요. 연결 여부는 서버가 준비되면 여기에 보여요.'
                    : state === 'connected'
                      ? '에이전트가 이 프로젝트로 보고하고 있어요.'
                      : state === 'failed'
                        ? '아직 보고가 오지 않았어요. 토큰과 MCP 주소를 확인하고 Claude Code를 다시 시작해 주세요.'
                        : state === 'recheck'
                          ? '상태를 다시 확인해 주세요.'
                          : '아래 단계를 마친 뒤 연결 확인을 눌러 주세요.'}
                </span>
                {statusReady && (
                  <button
                    type="button"
                    className="ml-auto shrink-0 text-[12.5px] font-semibold whitespace-nowrap hover:underline"
                    disabled={agents.isFetching}
                    onClick={() =>
                      void agents.refetch().then(() => setChecked(true))
                    }
                  >
                    {agents.isFetching
                      ? '확인 중…'
                      : state === 'failed'
                        ? '다시 확인'
                        : '연결 확인'}
                  </button>
                )}
              </div>
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
