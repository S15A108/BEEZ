import { useMutation } from '@tanstack/react-query'
import { beeApi } from '@/api/services'
import type { Member } from '@/api/contracts/common'
import { getApiErrorMessage } from '@/api/error'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'

/** 추천은 참고용이다. 이 화면은 담당자 변경 API를 호출하지 않는다. */
export function AssignmentRecommendations({
  projectId,
  taskId,
  members,
}: {
  projectId: string
  taskId: string
  members: Member[]
}) {
  const recommendation = useMutation({
    mutationFn: () =>
      beeApi.suggestAssignments(projectId, { task_ids: [taskId] }),
  })
  const name = (id: string) =>
    members.find((member) => member.id === id)?.name ?? '팀원 확인 필요'
  const suggestion = recommendation.data?.suggestions.find(
    (item) => item.task_id === taskId,
  )

  return (
    <Card>
      <CardHeader className="flex flex-row items-center justify-between gap-3">
        <CardTitle>비비의 담당 후보 추천</CardTitle>
        <Button
          variant="outline"
          disabled={recommendation.isPending}
          onClick={() => recommendation.mutate()}
        >
          {recommendation.isPending
            ? '추천 중…'
            : suggestion
              ? '다시 추천'
              : '후보 추천받기'}
        </Button>
      </CardHeader>
      <CardContent className="space-y-3 text-sm">
        <p className="text-muted-foreground">
          담당 도메인, 현재 업무량과 선행 작업을 참고해요. 추천만 표시하며
          담당자를 자동으로 바꾸지 않아요.
        </p>
        {recommendation.isPending && (
          <p role="status">비비가 후보를 살펴보고 있어요.</p>
        )}
        {recommendation.error && (
          <p role="alert">{getApiErrorMessage(recommendation.error)}</p>
        )}
        {recommendation.isSuccess && !suggestion && (
          <div role="status" className="space-y-2 text-muted-foreground">
            <p>이 작업에 대한 담당 후보를 받지 못했어요. 다시 추천해 주세요.</p>
            {recommendation.data.generation.unresolved.map((item) => (
              <p key={item}>{item}</p>
            ))}
          </div>
        )}
        {suggestion && !recommendation.isPending && !recommendation.error && (
          <div className="space-y-3" aria-label="담당 후보 추천 결과">
            <div className="space-y-2">
              <Badge variant="outline">
                {recommendation.data?.generation.origin === 'MODEL'
                  ? '모델 추천'
                  : '템플릿 추천'}
              </Badge>
              <p className="whitespace-pre-wrap text-muted-foreground">
                {recommendation.data?.generation.text}
              </p>
            </div>
            <div className="rounded-lg bg-primary/5 p-3">
              <p className="font-semibold">
                우선 후보 · {name(suggestion.member_id)}
              </p>
              <p className="mt-1 whitespace-pre-wrap">{suggestion.reason}</p>
            </div>
            {suggestion.alternatives.map((alternative) => (
              <div
                key={alternative.member_id}
                className="rounded-lg border p-3"
              >
                <p className="font-semibold">
                  대안 · {name(alternative.member_id)}
                </p>
                <p className="mt-1 whitespace-pre-wrap">{alternative.reason}</p>
              </div>
            ))}
            {recommendation.data?.generation.unresolved.map((item) => (
              <p key={item} className="text-muted-foreground">
                {item}
              </p>
            ))}
            <p className="text-muted-foreground">
              현재 정보를 다시 확인한 뒤 담당자를 직접 정해 주세요.
            </p>
          </div>
        )}
      </CardContent>
    </Card>
  )
}
