import { useMutation } from '@tanstack/react-query'
import { beeApi } from '@/api/services'
import type { Member } from '@/api/contracts/common'
import { getApiErrorCode, getApiErrorMessage } from '@/api/error'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'

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
  // 서버에서 꺼 둔 기능이면 다시 눌러도 같은 결과다. 버튼을 잠가 같은 실패를 되풀이하지 않게 한다(S15P31A108-405).
  const disabled =
    getApiErrorCode(recommendation.error) === 'assignment_suggestion_disabled'

  // 작업 현황 '더 보기'가 여는 창 안 화면에 놓인다. 제목과 설명은 창 머리가 맡는다(S15P31A108-441).
  return (
    <div className="space-y-3 text-sm">
      <Button
        variant="outline"
        disabled={recommendation.isPending || disabled}
        onClick={() => recommendation.mutate()}
      >
        {recommendation.isPending
          ? '추천 중…'
          : suggestion
            ? '다시 추천'
            : '후보 추천받기'}
      </Button>
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
            <div key={alternative.member_id} className="rounded-lg border p-3">
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
    </div>
  )
}
