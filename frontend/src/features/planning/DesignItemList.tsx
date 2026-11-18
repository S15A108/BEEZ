import { useState } from 'react'
import { FileText, Pencil, Plus } from 'lucide-react'
import type { DesignItemVersion } from '@/api/contracts/common'
import { EmptyState } from '@/components/EmptyState'
import { StatusPill } from '@/components/StatusPill'
import { Button } from '@/components/ui/button'
import { Tag } from '@/features/changes/Tag'
import { statusLabel } from '@/lib/display'
import { DETAIL_FOOTER, SPLIT_HEIGHT, listRowClass } from './splitLayout'
import { SplitView } from './SplitView'
import { useShowMore } from './useShowMore'

const descriptionOf = (item: DesignItemVersion) =>
  typeof item.content.description === 'string'
    ? item.content.description
    : JSON.stringify(item.content, null, 2)

// 기획 분석의 '설계 항목 초안' 탭. 왼쪽은 항목 키와 한 줄 설명, 오른쪽은 고른 항목의 내용과 수정·제외다.
// 기준 설계가 발행된 뒤에는 baselineVersion 을 넘겨 그 기준의 승인 항목을 읽기만 한다. 바꾸는 길은 변경 제안뿐이다.
export function DesignItemList({
  items,
  hasNextPage,
  loadingNext,
  loadNext,
  isLeader,
  busy,
  baselineVersion,
  onAdd,
  onEdit,
  onRemove,
}: {
  items: DesignItemVersion[]
  hasNextPage: boolean
  loadingNext: boolean
  loadNext: () => void
  isLeader: boolean
  busy: boolean
  baselineVersion?: number
  onAdd?: () => void
  onEdit?: (item: DesignItemVersion) => void
  onRemove?: (item: DesignItemVersion) => void
}) {
  const editable = isLeader && baselineVersion === undefined
  // 고른 항목은 항목 ID로 기억한다. 내용을 고치면 버전 ID가 바뀌어도 같은 항목을 계속 보인다.
  const [focusedId, setFocusedId] = useState<string>()
  const list = useShowMore(items, 20)
  const focused =
    items.find((item) => item.design_item_id === focusedId) ?? items[0]
  const add = editable && (
    <Button size="sm" variant="outline" onClick={() => onAdd?.()}>
      <Plus />
      항목 추가
    </Button>
  )
  if (!items.length)
    return (
      <EmptyState className={SPLIT_HEIGHT}>
        <p>
          {baselineVersion === undefined
            ? '아직 설계 항목 초안이 없어요. 후보를 채택하거나 항목을 직접 추가하면 여기에 쌓여요.'
            : `기준 설계 v${baselineVersion}에 든 설계 항목이 없어요.`}
        </p>
        {add}
      </EmptyState>
    )
  return (
    <SplitView
      header={
        <>
          <span className="text-[13px] text-muted-foreground">
            {baselineVersion === undefined
              ? '초안'
              : `기준 v${baselineVersion} 항목`}{' '}
            {items.length}
            {hasNextPage ? '개 이상' : '개'}
          </span>
          {add && <span className="ml-auto">{add}</span>}
        </>
      }
      list={
        <>
          <ul>
            {list.shown.map((item) => (
              <li key={item.id}>
                <button
                  type="button"
                  aria-current={
                    item.design_item_id === focused?.design_item_id || undefined
                  }
                  onClick={() => setFocusedId(item.design_item_id)}
                  className={listRowClass(
                    item.design_item_id === focused?.design_item_id,
                  )}
                >
                  <span className="min-w-0 flex-1">
                    <span className="flex items-baseline gap-2">
                      <span className="truncate text-sm font-medium">
                        {item.key}
                      </span>
                      <span className="shrink-0 text-xs text-muted-foreground">
                        {statusLabel(item.kind)}
                      </span>
                    </span>
                    <span className="mt-0.5 block truncate text-[13px] text-muted-foreground">
                      {descriptionOf(item)}
                    </span>
                  </span>
                </button>
              </li>
            ))}
          </ul>
          {(list.hidden > 0 || hasNextPage) && (
            <div className="p-3">
              <Button
                variant="ghost"
                size="sm"
                className="w-full text-muted-foreground"
                disabled={loadingNext}
                onClick={() => {
                  list.more()
                  if (list.hidden === 0 && hasNextPage) loadNext()
                }}
              >
                {baselineVersion === undefined
                  ? '다음 항목 검토'
                  : '다음 항목 보기'}
                {list.hidden > 0 ? ` (${list.hidden}개 남음)` : ''}
              </Button>
            </div>
          )}
        </>
      }
      detail={
        focused && (
          <article aria-label={focused.key} className="flex flex-1 flex-col">
            <div className="px-8 pt-5 pb-6">
              <div className="flex flex-wrap items-center gap-1.5">
                <Tag>{statusLabel(focused.kind)}</Tag>
                <StatusPill value={focused.status} />
                <span className="ml-1 text-xs text-muted-foreground">
                  {baselineVersion === undefined
                    ? '승인 전까지 프로젝트 정책으로 쓰지 않아요.'
                    : `기준 설계 v${baselineVersion}의 승인 항목이에요. 바꾸려면 변경 제안을 올려 주세요.`}
                </span>
              </div>
              <h3 className="mt-3 text-xl leading-8 font-semibold tracking-tight break-words">
                {focused.key}
              </h3>
              <p className="mt-3 text-[15px] leading-7 break-words whitespace-pre-wrap">
                {descriptionOf(focused)}
              </p>
              {focused.source && (
                <p className="mt-4 flex items-center gap-1.5 text-[13px] text-muted-foreground">
                  <FileText className="size-4 shrink-0" />
                  문서 v{focused.source.version_no} · {focused.source.location}
                </p>
              )}
            </div>
            {editable && (
              <div className={DETAIL_FOOTER}>
                <Button
                  variant="ghost"
                  disabled={busy || !focused.content_hash}
                  onClick={() => onRemove?.(focused)}
                >
                  발행 대상에서 제외
                </Button>
                <Button
                  variant="outline"
                  className="ml-auto"
                  disabled={busy || !focused.content_hash}
                  onClick={() => onEdit?.(focused)}
                >
                  <Pencil />
                  내용 수정
                </Button>
              </div>
            )}
          </article>
        )
      }
    />
  )
}
