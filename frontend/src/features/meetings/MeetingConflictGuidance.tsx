import type { MeetingConflictGuidance as Guidance } from '@/api/contracts/common'
import { Button } from '@/components/ui/button'
import { useWorkspaceStore } from '@/stores/workspaceStore'

export function MeetingConflictGuidance({
  guidance,
  brief,
  closed,
  taskKeyOf,
  documentTitleOf,
}: {
  guidance?: Guidance
  brief?: string
  closed: boolean
  taskKeyOf: (id: string) => string | undefined
  documentTitleOf: (id: string) => string
}) {
  const openPane = useWorkspaceStore((state) => state.openPane)
  const [first, second] = guidance?.tasks ?? []
  const hasComparison =
    first &&
    second &&
    guidance?.conflicts.some((item) => item.differences.length > 0)
  const taskLabel = (task: Guidance['tasks'][number]) =>
    [
      taskKeyOf(task.task_id),
      task.title,
      task.revision_no && `계획 r${task.revision_no}`,
    ]
      .filter(Boolean)
      .join(' · ')

  return (
    <section
      aria-label="비비의 충돌 안내"
      className="grid gap-3 rounded-xl border border-[#e4e0d4] bg-white p-3"
    >
      <h4 className="font-bold">비비의 충돌 안내</h4>
      {guidance?.conflicts.length ? (
        <ul className="grid gap-3">
          {guidance.conflicts.map((conflict, index) => {
            const item = conflict.design_item
            const documentId = item?.document_id
            return (
              <li key={index} className="grid min-w-0 gap-2 text-[13px]">
                {item && (
                  <p className="font-semibold">
                    {item.key} · {item.title}
                  </p>
                )}
                <p className="leading-5">{conflict.message}</p>
                {first && second && conflict.differences.length > 0 && (
                  <table className="w-full table-fixed border-collapse text-left text-xs [overflow-wrap:anywhere]">
                    <thead className="bg-[#f7f5ef]">
                      <tr>
                        <th scope="col" className="w-1/4 p-2">
                          제안이 다른 항목
                        </th>
                        <th scope="col" className="p-2">
                          {taskLabel(first)}
                        </th>
                        <th scope="col" className="p-2">
                          {taskLabel(second)}
                        </th>
                      </tr>
                    </thead>
                    <tbody>
                      {conflict.differences.map((difference) => (
                        <tr
                          key={difference.path}
                          className="border-b border-[#e4e0d4]"
                        >
                          <th scope="row" className="p-2 font-normal">
                            <code>{difference.path}</code>
                          </th>
                          <td className="p-2 whitespace-pre-wrap">
                            {difference.first_value ?? '제안 값 없음'}
                          </td>
                          <td className="p-2 whitespace-pre-wrap">
                            {difference.second_value ?? '제안 값 없음'}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}
                {!closed && conflict.next_action && (
                  <p className="leading-5 text-muted-foreground">
                    {conflict.next_action}
                  </p>
                )}
                {documentId && (
                  <Button
                    variant="outline"
                    size="sm"
                    className="h-auto min-w-0 justify-self-start py-1.5 text-left whitespace-normal"
                    onClick={() =>
                      openPane(
                        'documents',
                        item.document_version_id
                          ? `version:${documentId}:${item.document_version_id}`
                          : documentId,
                      )
                    }
                  >
                    근거 문서 · {documentTitleOf(documentId)}
                    {item.location && ` · ${item.location}`}
                  </Button>
                )}
              </li>
            )
          })}
        </ul>
      ) : (
        brief && (
          <p className="text-[13px] leading-5 whitespace-pre-wrap">{brief}</p>
        )
      )}
      {!closed && (
        <dl className="grid gap-2 border-t border-[#e4e0d4] pt-3 text-[13px] leading-5">
          <div>
            <dt className="font-semibold">회의에서 정할 내용</dt>
            <dd>
              {hasComparison
                ? '누가 설계 변경을 맡을지와 표에 나온 항목의 최종 규격을 정해 주세요. 합의한 규격에 맞춰 작업 범위를 나누고 회의 대화에 남겨요.'
                : '각 작업이 맡을 범위와 적용할 내용을 정하고, 합의를 회의 대화에 남겨 주세요.'}
            </dd>
          </div>
          <div>
            <dt className="font-semibold">작업 계획 수정</dt>
            <dd>
              계획을 바꾸기로 했다면 담당자가 아래 작업을 열어 수정하고 다시
              검증해요.
            </dd>
          </div>
          <div>
            <dt className="font-semibold">공용 문서 수정</dt>
            <dd>
              공용 문서를 바꾸기로 했다면 팀장이 비비 정리에서 문안을 확인하고
              회의를 끝내요. 변경 제안을 승인·발행하면 공용 문서에 반영돼요.
              설계 항목의 내용도 달라지면 해당 항목을 변경하는 제안도 필요해요.
            </dd>
          </div>
        </dl>
      )}
    </section>
  )
}
