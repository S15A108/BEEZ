import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { contextsApi } from '@/api/services'
import type { ContextRecord } from '@/api/contracts/common'
import { useProjectAction } from '@/features/workspace/useProjectAction'
import { AsyncState } from '@/components/AsyncState'
import { EmptyState } from '@/components/EmptyState'
import { FormDialog } from '@/components/FormDialog'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Input } from '@/components/ui/input'
import { formatDate } from '@/lib/formatDate'

export function ContextPanel({ projectId }: { projectId: string }) {
  const [search, setSearch] = useState('')
  const [creating, setCreating] = useState(false)
  const [selected, setSelected] = useState<string | null>(null)
  const [editing, setEditing] = useState<ContextRecord | null>(null)
  const action = useProjectAction(projectId)
  const query = useQuery({
    queryKey: ['projects', projectId, 'contexts', search],
    queryFn: () => contextsApi.list(projectId, { q: search || undefined }),
  })
  const detail = useQuery({
    queryKey: ['projects', projectId, 'context', selected],
    queryFn: () => contextsApi.get(projectId, selected!),
    enabled: !!selected,
  })
  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h3 className="text-xl font-bold">작업 맥락</h3>
          <p className="mt-2 text-xs text-muted-foreground">
            판단·진행·중단·인수인계의 이유와 근거를 남겨요.
          </p>
        </div>
        <Button onClick={() => setCreating(true)}>기록 남기기</Button>
      </div>
      <Input
        aria-label="작업 맥락 검색"
        placeholder="요약이나 작업 ID 검색"
        value={search}
        onChange={(event) => setSearch(event.target.value)}
      />
      <AsyncState query={query}>
        {(data) => (
          <div className="grid gap-3">
            {data.items.map((item) => (
              <button
                type="button"
                key={item.id}
                className="rounded-xl border p-4 text-left hover:bg-muted/50"
                onClick={() => setSelected(item.id)}
              >
                <div className="flex items-center gap-2">
                  <Badge variant="outline">
                    {
                      {
                        DECISION: '판단',
                        PROGRESS: '진행',
                        ISSUE: '중단·이슈',
                        HANDOVER: '인수인계',
                      }[item.kind]
                    }
                  </Badge>
                  <span className="text-xs text-muted-foreground">
                    r{item.revision_no} ·{' '}
                    {item.visibility === 'PRIVATE' ? '개인' : '팀 공유'}
                  </span>
                </div>
                <p className="mt-3 text-sm font-semibold">{item.summary}</p>
                <time className="mt-2 block text-xs text-muted-foreground">
                  {formatDate(item.created_at)}
                </time>
              </button>
            ))}
            {!data.items.length && (
              <EmptyState>아직 남긴 작업 맥락이 없어요.</EmptyState>
            )}
          </div>
        )}
      </AsyncState>
      {selected && (
        <AsyncState query={detail}>
          {(item) => (
            <article className="space-y-4 rounded-xl border bg-muted/20 p-5">
              <h4 className="font-semibold">{item.summary}</h4>
              <p className="text-sm leading-7 whitespace-pre-wrap">
                {item.detail}
              </p>
              <p className="text-xs text-muted-foreground">
                {item.references_current
                  ? '현재 승인 근거와 연결됨'
                  : '근거 버전을 다시 확인해 주세요.'}
              </p>
              <Button
                variant="outline"
                onClick={() => {
                  setEditing(item)
                  setCreating(true)
                }}
              >
                새 리비전으로 기록
              </Button>
            </article>
          )}
        </AsyncState>
      )}
      {creating && (
        <FormDialog
          title={editing ? '작업 맥락 새 리비전' : '작업 맥락 남기기'}
          description="기존 기록을 덮어쓰지 않고 새 리비전으로 보존해요. 비밀값과 코드 원문은 넣지 마세요."
          onClose={() => {
            setCreating(false)
            setEditing(null)
          }}
          fields={[
            {
              name: 'summary',
              label: '공유 요약',
              required: true,
              value: editing?.summary,
            },
            {
              name: 'detail',
              label: '내용',
              type: 'textarea',
              required: true,
              value: editing?.detail,
            },
            {
              name: 'kind',
              label: '기록 종류',
              type: 'select',
              value: editing?.kind ?? 'PROGRESS',
              options: [
                { value: 'PROGRESS', label: '진행' },
                { value: 'DECISION', label: '판단' },
                { value: 'ISSUE', label: '중단·이슈' },
                { value: 'HANDOVER', label: '인수인계' },
              ],
            },
            {
              name: 'visibility',
              label: '공유 범위',
              type: 'select',
              value: editing?.visibility ?? 'SHARED',
              options: [
                { value: 'SHARED', label: '팀 공유' },
                { value: 'PRIVATE', label: '나만 보기' },
              ],
            },
          ]}
          onSubmit={async (values) => {
            await action.run(() =>
              contextsApi.create(projectId, {
                previous_id: editing?.id,
                task_id: editing?.task_id,
                summary: values.summary,
                detail: values.detail,
                kind: values.kind as
                  'DECISION' | 'PROGRESS' | 'ISSUE' | 'HANDOVER',
                visibility: values.visibility as 'SHARED' | 'PRIVATE',
                design_refs: editing?.design_refs ?? [],
              }),
            )
            setCreating(false)
            setEditing(null)
          }}
        />
      )}
    </div>
  )
}
