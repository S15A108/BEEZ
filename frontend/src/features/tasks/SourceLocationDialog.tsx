import { useState } from 'react'
import { zodResolver } from '@hookform/resolvers/zod'
import { useQuery } from '@tanstack/react-query'
import { useForm } from 'react-hook-form'
import type { Source } from '@/api/contracts/common'
import { getApiErrorMessage } from '@/api/error'
import { readAllPages } from '@/api/readAllPages'
import { documentsApi, planningApi } from '@/api/services'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  documentLines,
  sourceLinesSchema,
  type SourceLinesForm,
} from './sourceLocation'

export type SourceFixTarget = {
  design_item_id: string
  design_item_version_id: string
  key: string
}
export type SourceConfirmation =
  { start_line: number; end_line: number } | { no_quote: true }

// 검증이 근거 수집에서 막힌 설계 항목의 출처 위치를 팀장이 확정한다(PLN-A11, S15P31A108-422).
// 항목 버전은 바뀌지 않으므로 작업 계획은 그대로 두고 새 검증만 요청하면 된다.
export function SourceLocationDialog({
  projectId,
  target,
  source: knownSource,
  onClose,
  onConfirm,
}: {
  projectId: string
  target: SourceFixTarget
  // 현재 기준에서 이 항목의 출처를 화면이 이미 알면 넘긴다. 없으면 승인 항목 전체에서 찾는다.
  source?: Source
  onClose: () => void
  onConfirm: (input: SourceConfirmation) => Promise<unknown>
}) {
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  // 작업 패널은 승인 항목을 첫 페이지(100개)만 읽는다. 그 밖의 항목이면 마지막 페이지까지 읽어 찾는다(리뷰 반영).
  const lookup = useQuery({
    queryKey: ['projects', projectId, 'approved-design-items-all'],
    queryFn: () =>
      readAllPages((cursor) =>
        planningApi.items(projectId, {
          status: 'APPROVED',
          cursor,
          limit: 100,
        }),
      ),
    enabled: !knownSource,
  })
  const source =
    knownSource ??
    lookup.data?.items.find(
      (item) => item.design_item_id === target.design_item_id,
    )?.source
  const document = useQuery({
    queryKey: [
      'projects',
      projectId,
      'documents',
      source?.document_id,
      'version',
      source?.version_no,
    ],
    queryFn: () =>
      documentsApi.get(projectId, source!.document_id, {
        version_no: source!.version_no,
      }),
    enabled: !!source,
  })
  const lines = documentLines(document.data?.version.content ?? '')
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<SourceLinesForm>({
    resolver: zodResolver(sourceLinesSchema(lines.length)),
    defaultValues: { start_line: '', end_line: '' },
  })
  const confirm = async (input: SourceConfirmation) => {
    setError('')
    setBusy(true)
    try {
      await onConfirm(input)
      onClose()
    } catch (failure) {
      setError(getApiErrorMessage(failure))
    } finally {
      setBusy(false)
    }
  }
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-[85dvh] overflow-y-auto sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>{target.key} 출처 위치 지정</DialogTitle>
          <DialogDescription>
            이 항목의 근거가 되는 원문 줄을 고르거나, 원문에 인용할 줄이 없으면
            인용 없음으로 확정해요. 작업 계획은 바뀌지 않아요. 지정한 뒤 새
            검증을 요청해 주세요.
          </DialogDescription>
        </DialogHeader>
        {!source && !knownSource && lookup.isPending ? (
          <p className="text-sm text-muted-foreground">
            항목의 원문 출처를 찾는 중이에요.
          </p>
        ) : !source ? (
          <p className="text-sm text-muted-foreground">
            현재 기준 설계에서 이 항목의 원문 출처를 찾지 못했어요.
          </p>
        ) : (
          <div className="space-y-3">
            <p className="text-xs text-muted-foreground">
              문서 v{source.version_no} · 지금 위치:{' '}
              {source.location || '비어 있음'}
            </p>
            {document.isPending ? (
              <p className="text-sm text-muted-foreground">
                원문을 불러오는 중이에요.
              </p>
            ) : document.isError || !lines.length ? (
              <p className="text-sm text-muted-foreground">
                원문을 불러오지 못했어요. 줄 번호를 알면 직접 입력할 수 있어요.
              </p>
            ) : (
              <ol
                aria-label="원문 줄"
                className="max-h-64 overflow-y-auto rounded-md border bg-muted/30 p-2 font-mono text-xs leading-5"
              >
                {lines.map((line, index) => (
                  <li key={index} className="flex gap-3">
                    <span className="w-8 shrink-0 text-right text-muted-foreground select-none">
                      {index + 1}
                    </span>
                    <span className="break-all whitespace-pre-wrap">
                      {line || ' '}
                    </span>
                  </li>
                ))}
              </ol>
            )}
            <form
              id="source-lines"
              className="flex flex-wrap items-start gap-3"
              onSubmit={handleSubmit((values) =>
                confirm({
                  start_line: Number(values.start_line),
                  end_line: Number(values.end_line),
                }),
              )}
            >
              <div className="space-y-1">
                <Label htmlFor="source-start-line">시작 줄</Label>
                <Input
                  id="source-start-line"
                  inputMode="numeric"
                  className="w-24"
                  {...register('start_line')}
                />
                {errors.start_line && (
                  <p role="alert" className="text-xs text-destructive">
                    {errors.start_line.message}
                  </p>
                )}
              </div>
              <div className="space-y-1">
                <Label htmlFor="source-end-line">끝 줄</Label>
                <Input
                  id="source-end-line"
                  inputMode="numeric"
                  className="w-24"
                  {...register('end_line')}
                />
                {errors.end_line && (
                  <p role="alert" className="text-xs text-destructive">
                    {errors.end_line.message}
                  </p>
                )}
              </div>
            </form>
          </div>
        )}
        {error && (
          <p role="alert" className="text-sm text-destructive">
            {error}
          </p>
        )}
        <DialogFooter>
          <Button variant="ghost" onClick={onClose}>
            닫기
          </Button>
          {source && (
            <>
              <Button
                variant="outline"
                disabled={busy}
                onClick={() => void confirm({ no_quote: true })}
              >
                인용 없음으로 확정
              </Button>
              <Button type="submit" form="source-lines" disabled={busy}>
                이 줄로 지정
              </Button>
            </>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
