import { useQuery } from '@tanstack/react-query'
import {
  ArrowRight,
  FileText,
  GitPullRequest,
  ListChecks,
  Users,
} from 'lucide-react'
import { dashboardApi, projectsApi } from '@/api/services'
import { AsyncState } from '@/components/AsyncState'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { StatusPill } from '@/components/StatusPill'
import { useWorkspaceStore } from '@/stores/workspaceStore'

export function OverviewPanel({ projectId }: { projectId: string }) {
  const openPane = useWorkspaceStore((state) => state.openPane)
  const query = useQuery({
    queryKey: ['projects', projectId, 'overview'],
    queryFn: async () => {
      const [documents, changes, tasks, validations, onboarding] =
        await Promise.all([
          dashboardApi.documents(projectId),
          dashboardApi.changes(projectId),
          dashboardApi.tasks(projectId),
          dashboardApi.validations(projectId),
          projectsApi.onboarding(projectId),
        ])
      return { documents, changes, tasks, validations, onboarding }
    },
  })
  return (
    <AsyncState query={query}>
      {(data) => (
        <div className="space-y-6">
          <div>
            <h3 className="text-2xl font-bold">우리 팀의 작업을 한눈에</h3>
            <p className="mt-2 text-sm text-muted-foreground">
              문서부터 변경 검토, 동료의 진행 상황까지 함께 확인하세요.
            </p>
          </div>
          <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
            {[
              {
                label: '승인 문서',
                value: data.documents.by_kind.reduce(
                  (sum, item) => sum + item.approved_count,
                  0,
                ),
                icon: FileText,
                action: () => openPane('documents'),
              },
              {
                label: '검토할 변경',
                value: data.changes.review_required_count,
                icon: GitPullRequest,
                action: () => openPane('changes'),
              },
              {
                label: '진행 중인 작업',
                value:
                  data.tasks.by_status.find(
                    (item) => item.status === 'IN_PROGRESS',
                  )?.count ?? 0,
                icon: ListChecks,
                action: () => openPane('tasks'),
              },
              {
                label: '함께하는 팀원',
                value: data.onboarding.member_count,
                icon: Users,
                action: () => openPane('team'),
              },
            ].map((item) => (
              <button
                type="button"
                key={item.label}
                onClick={item.action}
                className="rounded-2xl border bg-card p-5 text-left hover:bg-muted/50"
              >
                <div className="flex items-center justify-between text-xs text-muted-foreground">
                  {item.label}
                  <item.icon className="size-4" />
                </div>
                <strong className="mt-5 block text-3xl font-semibold">
                  {item.value}
                </strong>
              </button>
            ))}
          </div>
          <Card className="bg-primary/5">
            <CardHeader>
              <CardTitle>다음 작업의 기준을 함께 준비하세요</CardTitle>
            </CardHeader>
            <CardContent className="flex flex-wrap items-center justify-between gap-4">
              <p className="text-sm text-muted-foreground">
                기획 문서를 구체화하고 인간 팀장이 기준 설계를 발행해요.
              </p>
              <Button onClick={() => openPane('planning')}>
                기획 분석 시작
                <ArrowRight />
              </Button>
            </CardContent>
          </Card>
          <div className="grid gap-5 md:grid-cols-2">
            <Card>
              <CardHeader>
                <CardTitle>필수 문서 준비</CardTitle>
              </CardHeader>
              <CardContent className="grid gap-3">
                {data.onboarding.required_documents.map((item) => (
                  <div
                    key={item.kind}
                    className="flex items-center justify-between rounded-lg border p-3 text-sm"
                  >
                    <StatusPill value={item.kind} />
                    <span>{item.uploaded ? '등록됨' : '등록 필요'}</span>
                  </div>
                ))}
                <Button variant="outline" onClick={() => openPane('documents')}>
                  공용 문서함 열기
                </Button>
              </CardContent>
            </Card>
            <Card>
              <CardHeader>
                <CardTitle>작업 계획 검증 현황</CardTitle>
              </CardHeader>
              <CardContent className="grid gap-3">
                {data.validations.by_status.map((item) => (
                  <div
                    key={item.status}
                    className="flex items-center justify-between text-sm"
                  >
                    <StatusPill value={item.status} />
                    <strong>{item.count}</strong>
                  </div>
                ))}
                <p className="mt-3 text-xs leading-6 text-muted-foreground">
                  계획 검증은 승인 설계와의 정합성을 확인해요. 구현 코드나
                  테스트 통과를 보장하지 않아요.
                </p>
                <Button variant="outline" onClick={() => openPane('tasks')}>
                  작업 검증 열기
                </Button>
              </CardContent>
            </Card>
          </div>
        </div>
      )}
    </AsyncState>
  )
}
