import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { agentsApi, membersApi } from '@/api/services'
import type { IssueTokenResponse } from './schemas'
import { useProjectAction } from '@/features/workspace/useProjectAction'
import { AsyncState } from '@/components/AsyncState'
import { ActionForm } from '@/components/ActionForm'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { formatDate } from '@/lib/formatDate'
import { apiMode } from '@/lib/apiMode'

export function AgentsPanel({ projectId }: { projectId: string }) {
  const action = useProjectAction(projectId)
  const [fresh, setFresh] = useState<IssueTokenResponse | null>(null)
  const [copied, setCopied] = useState(false)
  const query = useQuery({
    queryKey: ['projects', projectId, 'agent-settings'],
    queryFn: async () => {
      const [tokens, guide, agents, members] = await Promise.all([
        agentsApi.myTokens({ project_id: projectId }),
        agentsApi.connectionGuide(projectId),
        agentsApi.list(projectId),
        membersApi.list(projectId),
      ])
      return { tokens, guide, agents, members }
    },
  })
  return (
    <div className="space-y-6">
      <div>
        <h3 className="text-xl font-bold">개인 에이전트 연결</h3>
        <p className="mt-2 text-sm leading-7 text-muted-foreground">
          프로젝트별 개인 토큰으로 로컬 Claude Code를 연결하세요. 실제 연결과
          작업 상태는 외부 호출의 보고를 기준으로 표시해요.
        </p>
      </div>
      <AsyncState query={query}>
        {(data) => (
          <>
            <Card>
              <CardHeader>
                <CardTitle>내 프로젝트 토큰</CardTitle>
              </CardHeader>
              <CardContent className="space-y-5">
                <ActionForm
                  fields={[
                    {
                      name: 'label',
                      label: '토큰 이름',
                      value: '내 Claude Code',
                      max: 40,
                    },
                  ]}
                  submitLabel="토큰 발급"
                  onSubmit={async (values) => {
                    await action.run(async () => {
                      const issued = await agentsApi.issueToken(projectId, {
                        label: values.label,
                      })
                      setFresh(issued)
                      setCopied(false)
                    }, '토큰을 발급했어요.')
                  }}
                />
                {fresh && (
                  <div
                    className="space-y-3 rounded-xl border border-primary/40 bg-primary/5 p-4"
                    aria-label="한 번만 표시하는 새 토큰"
                  >
                    <p className="text-sm font-semibold">
                      이 화면을 닫으면 토큰 값을 다시 볼 수 없어요.
                    </p>
                    {apiMode === 'mock' && (
                      <p className="text-xs">
                        목데이터 토큰은 실제 서버에 사용할 수 없어요.
                      </p>
                    )}
                    <code className="block overflow-x-auto rounded-lg bg-background p-3 text-xs">
                      {fresh.token}
                    </code>
                    <Button
                      size="sm"
                      variant="outline"
                      onClick={() =>
                        void navigator.clipboard
                          .writeText(fresh.token)
                          .then(() => setCopied(true))
                          .catch(() => setCopied(false))
                      }
                    >
                      {copied ? '복사했어요' : '토큰 복사'}
                    </Button>
                    <Button
                      size="sm"
                      variant="ghost"
                      onClick={() => setFresh(null)}
                    >
                      값 숨기기
                    </Button>
                  </div>
                )}
                <ul className="grid gap-3">
                  {data.tokens.items
                    .filter((token) => !token.revoked_at)
                    .map((token) => (
                      <li
                        key={token.id}
                        className="flex flex-wrap items-center justify-between gap-3 rounded-xl border p-4"
                      >
                        <div>
                          <strong className="text-sm">
                            {token.label || '내 에이전트 토큰'}
                          </strong>
                          <p className="mt-1 text-xs text-muted-foreground">
                            발급 {formatDate(token.created_at)} · 최근 호출{' '}
                            {formatDate(token.last_used_at)}
                          </p>
                        </div>
                        <Button
                          variant="outline"
                          disabled={action.busy}
                          onClick={() => {
                            if (
                              window.confirm(
                                '토큰을 폐기하면 이 토큰의 MCP 호출이 거부돼요. 폐기할까요?',
                              )
                            )
                              void action
                                .run(
                                  () => agentsApi.revokeToken(token.id),
                                  '토큰을 폐기했어요.',
                                )
                                .then(() => {
                                  if (fresh?.token_info.id === token.id)
                                    setFresh(null)
                                })
                                .catch(() => undefined)
                          }}
                        >
                          폐기
                        </Button>
                      </li>
                    ))}
                </ul>
              </CardContent>
            </Card>
            <Card>
              <CardHeader>
                <CardTitle>Claude Code 설정</CardTitle>
              </CardHeader>
              <CardContent className="space-y-4">
                <ol className="list-inside list-decimal text-sm leading-8">
                  {data.guide.steps.map((step) => (
                    <li key={step}>{step}</li>
                  ))}
                </ol>
                <p className="text-xs text-muted-foreground">MCP 주소</p>
                <code className="block overflow-x-auto rounded-lg bg-muted p-3 text-xs">
                  {data.guide.mcp_url}
                </code>
                <pre className="overflow-x-auto rounded-lg bg-muted p-4 text-xs whitespace-pre-wrap">
                  {data.guide.claude_md_snippet}
                </pre>
              </CardContent>
            </Card>
            <Card>
              <CardHeader>
                <CardTitle>실제 연결 보고</CardTitle>
              </CardHeader>
              <CardContent className="grid gap-3">
                {data.agents.items.map((agent) => (
                  <div
                    key={agent.id}
                    className="flex items-center justify-between rounded-xl border p-4"
                  >
                    <div>
                      <p className="text-sm font-semibold">
                        {data.members.items.find(
                          (member) => member.id === agent.member_id,
                        )?.name ?? '팀원'}
                        의 에이전트
                      </p>
                      <p className="mt-2 text-xs text-muted-foreground">
                        최근 보고 {formatDate(agent.last_report_at)}
                      </p>
                    </div>
                    <Badge variant={agent.connected ? 'secondary' : 'outline'}>
                      {agent.status_check_required
                        ? '상태 재확인 필요'
                        : agent.connected
                          ? '최근 연결 확인'
                          : '미연결'}
                    </Badge>
                  </div>
                ))}
                {!data.agents.items.length && (
                  <p className="text-sm text-muted-foreground">
                    아직 연결을 보고한 에이전트가 없어요.
                  </p>
                )}
                {apiMode === 'mock' && (
                  <p className="text-xs text-muted-foreground">
                    목데이터 화면은 실제 Claude Code 연결을 수행하지 않아요.
                  </p>
                )}
              </CardContent>
            </Card>
            {action.error && (
              <p role="alert" className="text-sm text-destructive">
                {action.error}
              </p>
            )}
            {action.message && (
              <p role="status" className="text-sm">
                {action.message}
              </p>
            )}
          </>
        )}
      </AsyncState>
    </div>
  )
}
