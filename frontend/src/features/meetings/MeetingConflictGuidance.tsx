import { useId, useState } from 'react'
import type { MeetingConflictGuidance as Guidance } from '@/api/contracts/common'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { useWorkspaceStore } from '@/stores/workspaceStore'

// 충돌 회의의 안건(화면 검수 회의실 1·4, S15P31A108-437): 처음에는 '정할 것' 한 줄(질문, 걸린 작업, 요구사항 번호)만 보여
// 대화가 첫 화면에 보이게 하고, 비비의 안내 전문과 '회의에서 정할 내용'은 펼쳐서 본다. 카드는 이 한 겹뿐이다.
export function MeetingConflictGuidance({
  summary,
  taskIds,
  guidance,
  brief,
  closed,
  taskKeyOf,
  documentTitleOf,
}: {
  summary?: string
  taskIds: string[]
  guidance?: Guidance
  brief?: string
  closed: boolean
  taskKeyOf: (id: string) => string | undefined
  documentTitleOf: (id: string) => string
}) {
  const openPane = useWorkspaceStore((state) => state.openPane)
  const [open, setOpen] = useState(false)
  const detailId = useId()
  const [first, second] = guidance?.tasks ?? []
  const hasComparison =
    first &&
    second &&
    guidance?.conflicts.some((item) => item.differences.length > 0)
  const requirementKeys = [
    ...new Set(
      guidance?.conflicts.flatMap((item) =>
        item.design_item ? [item.design_item.key] : [],
      ),
    ),
  ]
  const hasDetail = !!guidance?.conflicts.length || !!brief || !closed
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
      aria-label="안건"
      className={cn(
        'grid gap-3 rounded-xl border border-[#f3e2a6] bg-[#fff8e0] px-3.5 py-2.5 text-[13px]',
        // 넓은 화면에서 펼친 안내가 길면 회의 대화 칸과 남는 높이를 나누고 안에서 스크롤한다(S15P31A108-438). 짧으면 제 높이만 쓴다.
        // 창이 낮아도 '정할 것' 줄과 안내 몇 줄(128px)은 남기고, 그래도 모자라면 회의실 창이 스크롤한다.
        open &&
          'lg:max-h-max lg:min-h-32 lg:flex-1 lg:content-start lg:overflow-y-auto',
      )}
    >
      <div className="flex flex-wrap items-baseline gap-x-2.5 gap-y-1">
        <strong className="shrink-0">정할 것</strong>
        <span className="min-w-0 flex-1 basis-48">{summary}</span>
        <span className="flex flex-wrap items-baseline gap-x-2 text-xs text-muted-foreground">
          {taskIds.map((id) => (
            <button
              key={id}
              type="button"
              className="font-semibold text-foreground underline-offset-2 hover:underline"
              onClick={() => openPane('tasks', id)}
            >
              {taskKeyOf(id) ?? '작업'}
            </button>
          ))}
          {requirementKeys.map((key) => (
            <span key={key}>{key}</span>
          ))}
          {hasDetail && (
            <button
              type="button"
              aria-expanded={open}
              aria-controls={detailId}
              className="font-semibold text-[#8a6a00]"
              onClick={() => setOpen((value) => !value)}
            >
              {open ? '접기' : '펼치기'}
            </button>
          )}
        </span>
      </div>
      {open && (
        <section
          id={detailId}
          aria-label="비비의 충돌 안내"
          className="grid gap-3 border-t border-[#f3e2a6] pt-3"
        >
          {guidance?.conflicts.length ? (
            <ul className="grid gap-3">
              {guidance.conflicts.map((conflict, index) => {
                const item = conflict.design_item
                const documentId = item?.document_id
                return (
                  <li key={index} className="grid min-w-0 gap-2">
                    {item && (
                      <p className="font-semibold">
                        {item.key} · {item.title}
                      </p>
                    )}
                    <p className="leading-5">{conflict.message}</p>
                    {first && second && conflict.differences.length > 0 && (
                      <table className="w-full table-fixed border-collapse bg-white text-left text-xs [overflow-wrap:anywhere]">
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
            brief && <p className="leading-5 whitespace-pre-wrap">{brief}</p>
          )}
          {!closed && (
            <dl className="grid gap-2 border-t border-[#f3e2a6] pt-3 leading-5">
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
                  계획을 바꾸기로 했다면 담당자가 위의 작업 번호를 눌러 수정하고
                  다시 검증해요.
                </dd>
              </div>
              <div>
                <dt className="font-semibold">공용 문서 수정</dt>
                <dd>
                  공용 문서를 바꾸기로 했다면 팀장이 비비 정리에서 문안을
                  확인하고 회의를 끝내요. 변경 제안을 승인·발행하면 공용 문서에
                  반영돼요. 설계 항목의 내용도 달라지면 해당 항목을 변경하는
                  제안도 필요해요.
                </dd>
              </div>
            </dl>
          )}
        </section>
      )}
    </section>
  )
}
