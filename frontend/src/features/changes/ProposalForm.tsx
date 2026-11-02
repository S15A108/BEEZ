import { useEffect, useState } from 'react'
import type { ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import { zodResolver } from '@hookform/resolvers/zod'
import { useForm, useWatch } from 'react-hook-form'
import { Plus } from 'lucide-react'
import type { TaskDetail } from '@/api/contracts/common'
import { getApiErrorMessage } from '@/api/error'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { NativeSelect, NativeSelectOption } from '@/components/ui/native-select'
import { Textarea } from '@/components/ui/textarea'
import { normalizeDocument } from './normalizeDocument'
import { proposalFormSchema } from './proposalItems'
import type { ProposalFormValues } from './proposalItems'

interface Props {
  heading: string
  submitLabel: string
  tasks: TaskDetail[]
  /** 고칠 수 있는 현재 문서와 목록이 알려 준 승인 버전. 본문은 fetchDocumentContent로 따로 받는다. */
  documents?: { id: string; label: string; versionId?: string }[]
  /** 고른 문서의 기준 원문과 그 버전(DOC-A03 문서 원문 조회) */
  fetchDocumentContent?: (
    documentId: string,
  ) => Promise<{ content: string; versionId: string }>
  /** 다시 내는 제안의 문서 변경. 폼에서 고치는 문서 말고는 그대로 함께 올라가고, 기준이 바뀐 문서(stale)는 빠진다. */
  sourceDocuments?: {
    id: string
    title: string
    afterContent: string
    stale: boolean
  }[]
  /** 다시 내는 제안의 기준이 지금 기준과 다르면 지금 기준 버전 */
  baseChangedTo?: number
  /** 에이전트·회의가 만든 제안의 설계 항목 변경. 보기만 하고 다시 낼 때 받은 그대로 보낸다. */
  fixedItems?: { label: string; detail?: string }[]
  defaultValues?: ProposalFormValues
  // 제안 고치기 · 고쳐서 새로 올리기에서 쓴다.
  status?: ReactNode
  description?: string
  footnote?: string
  comments?: { id: string; author: string; body: string }[]
  onShowComments?: () => void
  onSubmit: (values: ProposalFormValues) => Promise<unknown>
  onCancel: () => void
}

function FieldError({ message }: { message?: string }) {
  return message ? (
    <p role="alert" className="text-xs text-destructive">
      {message}
    </p>
  ) : null
}

// 문서 수정 제안 · 창 안에서. 공용 문서함 '고치기'로 그 문서를 고른 채 열리고, 필수는 제목, 이유, 고친 글이다.
// 제안 고치기는 같은 폼에 지금 내용을 채우고 받은 의견을 옆에 둔다. 설계 항목 변경은 보기만 한다.
export function ProposalForm({
  heading,
  submitLabel,
  tasks,
  documents = [],
  fetchDocumentContent,
  sourceDocuments = [],
  baseChangedTo,
  fixedItems = [],
  defaultValues,
  status,
  description,
  footnote = '올리면 관련 팀원에게 알림이 가고 AI 영향 분석이 시작돼요. 제출만으로 기준이 바뀌지는 않아요.',
  comments,
  onShowComments,
  onSubmit,
  onCancel,
}: Props) {
  const {
    register,
    control,
    handleSubmit,
    setValue,
    getValues,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<ProposalFormValues>({
    resolver: zodResolver(proposalFormSchema),
    defaultValues: defaultValues ?? {
      title: '',
      reason: '',
      task_id: '',
      document_id: '',
      after_content: '',
    },
  })
  // 문서함 '고치기'로 오면 고칠 문서 칸이 열린 채, 설계 항목만 있던 제안은 '+ 문서 고치기'로 연다.
  const [documentOpen, setDocumentOpen] = useState(
    !!defaultValues?.document_id || !fixedItems.length,
  )
  const documentId = useWatch({ control, name: 'document_id' })
  const listedVersion = documents.find(
    (item) => item.id === documentId,
  )?.versionId
  // 캐시 키에 목록이 알려 준 승인 버전을 넣어, 새 승인본이 나오면 옛 원문 캐시를 쓰지 않는다(PR #72 리뷰).
  const content = useQuery({
    queryKey: ['documents', documentId, 'approved-content', listedVersion],
    queryFn: () => fetchDocumentContent!(documentId!),
    enabled: !!documentId && !!fetchDocumentContent,
  })
  // 기준 원문을 받을 때마다: '고친 글'이 비었거나 마지막으로 채운 원문 그대로면(손대지 않음) 새 원문으로 바꿔 채운다.
  // 손댄 글은 두고 버전만 기억해 '기준 문서가 새 버전으로 바뀌었어요'를 띄운다. 다시 내는 제안처럼 처음부터 글이
  // 있으면 덮어쓰지 않고, 지금 받은 버전을 그 글의 기준으로 삼는다.
  useEffect(() => {
    const loaded = content.data
    if (!documentId || !loaded) return
    const text = getValues('after_content') ?? ''
    setValue('latest_version_id', loaded.versionId)
    if (!text || text === getValues('base_content')) {
      setValue('after_content', loaded.content)
      setValue('base_content', loaded.content)
      setValue('base_version_id', loaded.versionId)
    } else if (!getValues('base_version_id')) {
      setValue('base_content', loaded.content)
      setValue('base_version_id', loaded.versionId)
    }
  }, [documentId, content.data, getValues, setValue])
  // 원래 제안의 문서 중 폼에서 지금 고치는 문서만 빼고 함께 올라간다. 기준이 바뀐 문서는 그대로 낼 수 없어 뺀다.
  const kept = sourceDocuments.filter((item) => item.id !== documentId)
  const keptFresh = kept.filter((item) => !item.stale)
  const keptStale = kept.filter((item) => item.stale)
  const editingStale = sourceDocuments.find(
    (item) => item.id === documentId && item.stale,
  )
  useEffect(() => {
    setValue('kept_documents', keptFresh.length)
  }, [keptFresh.length, setValue])
  const shownVersion = useWatch({ control, name: 'base_version_id' })
  const latestVersion = useWatch({ control, name: 'latest_version_id' })
  const outdated =
    !!shownVersion && !!latestVersion && shownVersion !== latestVersion
  const rewriteFromLatest = () => {
    if (!content.data) return
    setValue('after_content', content.data.content)
    setValue('base_content', content.data.content)
    setValue('base_version_id', content.data.versionId)
  }
  return (
    <form
      noValidate
      onSubmit={handleSubmit((form) => {
        // 기준 원문을 아직 못 받았거나 보여 준 원문이 옛 버전이면, 어떤 기준에 대한 글인지 알 수 없어 막는다.
        if (form.document_id && !form.base_version_id) {
          setError('after_content', {
            message: '기준 원문을 받은 뒤에 올릴 수 있어요.',
          })
          return
        }
        if (form.document_id && outdated) {
          setError('after_content', {
            message:
              '기준 문서가 새 버전으로 바뀌었어요. 새 원문을 확인한 뒤 올려 주세요.',
          })
          return
        }
        // 서버는 본문을 정규화(BOM 제거, CRLF→LF)해 기준과 같은 문서를 거절한다(PR #46).
        // 같은 규칙으로 비교해 바뀌지 않은 문서는 빼고, 남는 변경이 없으면 막는다.
        const base = form.document_id ? form.base_content : undefined
        const unchanged =
          base !== undefined &&
          normalizeDocument(form.after_content ?? '') ===
            normalizeDocument(base)
        if (unchanged && !keptFresh.length && !fixedItems.length) {
          setError('after_content', {
            message: '기준 문서와 달라진 내용이 없어요.',
          })
          return
        }
        // 되돌린 문서는 원래 제안의 같은 문서 변경까지 빼도록 removed_document_id로 알린다.
        return onSubmit(
          unchanged
            ? {
                ...form,
                document_id: '',
                after_content: '',
                removed_document_id: form.document_id,
              }
            : form,
        ).catch(() => undefined)
      })}
      className="grid gap-4"
    >
      <div>
        <h3 className="flex flex-wrap items-center gap-2 text-lg font-bold">
          {heading}
          {status}
        </h3>
        {description && (
          <p className="mt-1 text-sm text-muted-foreground">{description}</p>
        )}
      </div>
      {(baseChangedTo !== undefined ||
        sourceDocuments.some((item) => item.stale)) && (
        <div
          role="alert"
          className="grid gap-1 rounded-[14px] bg-[#fdf8e6] px-3.5 py-3 text-[13px] leading-5"
        >
          <strong className="text-sm">
            {baseChangedTo !== undefined
              ? `기준이 v${baseChangedTo}(으)로 바뀌었어요`
              : '그 사이 문서의 승인본이 바뀌었어요'}
          </strong>
          <span className="text-muted-foreground">
            이 제안은 옛 기준에서 쓴 글이라 그대로 내면 그 사이 승인된 변경을
            되돌릴 수 있어요. 승인본이 바뀐 문서는 새 기준 원문에서 다시
            시작하고, 반려된 제안의 글은 비교용으로 보여 줘요.
          </span>
        </div>
      )}
      <div className="grid gap-5 xl:grid-cols-2">
        <div className="grid content-start gap-4">
          <div className="grid gap-1.5">
            <Label htmlFor="proposal-title">제목 *</Label>
            <Input
              id="proposal-title"
              aria-invalid={!!errors.title}
              {...register('title')}
            />
            <FieldError message={errors.title?.message} />
          </div>
          <div className="grid gap-1.5">
            <Label htmlFor="proposal-reason">바꾸려는 이유 *</Label>
            <Textarea
              id="proposal-reason"
              rows={3}
              aria-invalid={!!errors.reason}
              {...register('reason')}
            />
            <FieldError message={errors.reason?.message} />
          </div>
          {comments && (
            <section
              aria-label="받은 의견"
              className="grid gap-1.5 rounded-xl bg-muted/40 p-3 text-sm"
            >
              <p className="flex justify-between font-semibold">
                받은 의견 {comments.length}
                {onShowComments && (
                  <button
                    type="button"
                    className="text-xs font-normal underline"
                    onClick={onShowComments}
                  >
                    의견 탭에서 보기
                  </button>
                )}
              </p>
              {comments.length ? (
                comments.slice(0, 3).map((comment) => (
                  <p key={comment.id} className="text-xs leading-5">
                    <strong>{comment.author}</strong> · {comment.body}
                  </p>
                ))
              ) : (
                <p className="text-xs text-muted-foreground">
                  아직 받은 의견이 없어요.
                </p>
              )}
              <p className="text-xs text-muted-foreground">
                고쳐도 받은 의견은 그대로 남아요.
              </p>
            </section>
          )}
          <details className="rounded-xl border p-3 text-sm">
            <summary className="cursor-pointer font-medium">
              관련 작업 연결 (선택)
            </summary>
            <NativeSelect
              aria-label="관련 작업"
              className="mt-3"
              {...register('task_id')}
            >
              <NativeSelectOption value="">연결하지 않음</NativeSelectOption>
              {tasks.map((task) => (
                <NativeSelectOption key={task.id} value={task.id}>
                  {task.key} · {task.current_plan_revision.plan.title}
                </NativeSelectOption>
              ))}
            </NativeSelect>
            {/* API 없음: 검증·회의 출처 연결은 변경 제안 출처 필드가 정해지면 둔다(백엔드 메모 3). */}
          </details>
        </div>
        <div className="grid content-start gap-4">
          {documents.length > 0 && !documentOpen && (
            <Button
              type="button"
              variant="outline"
              size="sm"
              className="justify-self-start"
              onClick={() => setDocumentOpen(true)}
            >
              <Plus />
              문서 고치기
            </Button>
          )}
          {documents.length > 0 && documentOpen && (
            <div className="grid gap-2 rounded-xl border p-3">
              <Label htmlFor="proposal-document">고칠 문서</Label>
              <NativeSelect
                id="proposal-document"
                aria-invalid={!!errors.document_id}
                {...register('document_id', {
                  onChange: () => {
                    setValue('after_content', '')
                    setValue('base_content', undefined)
                    setValue('base_version_id', undefined)
                    setValue('latest_version_id', undefined)
                  },
                })}
              >
                <NativeSelectOption value="">
                  {sourceDocuments.length || fixedItems.length
                    ? '고치지 않음'
                    : '문서 고르기'}
                </NativeSelectOption>
                {documents.map((item) => (
                  <NativeSelectOption key={item.id} value={item.id}>
                    {item.label}
                  </NativeSelectOption>
                ))}
              </NativeSelect>
              <FieldError message={errors.document_id?.message} />
              {documentId && (
                <>
                  {content.isLoading && (
                    <p role="status" className="text-xs text-muted-foreground">
                      기준 원문을 불러오는 중…
                    </p>
                  )}
                  {content.error && (
                    <div
                      role="alert"
                      className="flex flex-wrap items-center gap-2 text-xs text-destructive"
                    >
                      기준 원문을 불러오지 못했어요.{' '}
                      {getApiErrorMessage(content.error)}
                      <Button
                        type="button"
                        size="xs"
                        variant="outline"
                        onClick={() => void content.refetch()}
                      >
                        다시 불러오기
                      </Button>
                    </div>
                  )}
                  {outdated && (
                    <div
                      role="alert"
                      className="flex flex-wrap items-center gap-2 rounded-lg bg-[#fdf8e6] p-2.5 text-xs text-[#6e5200]"
                    >
                      기준 문서가 새 버전으로 바뀌었어요. 고친 글은 그대로
                      두었어요.
                      <Button
                        type="button"
                        size="xs"
                        variant="outline"
                        onClick={rewriteFromLatest}
                      >
                        새 원문으로 다시 쓰기
                      </Button>
                    </div>
                  )}
                  <Textarea
                    aria-label="고친 글"
                    rows={10}
                    aria-invalid={!!errors.after_content}
                    {...register('after_content')}
                  />
                  <FieldError message={errors.after_content?.message} />
                  {/* API 없음: '파일 올리기'(DOC-A12 변경용 원문 → proposed_document_version_id)는 화면만 정해져 있어 고친 글 쓰기만 둔다. */}
                  <p className="text-xs text-muted-foreground">
                    올리면 지금 기준 원문과 비교해 보여 줘요.
                  </p>
                </>
              )}
            </div>
          )}
          {editingStale && (
            <details className="rounded-xl border p-3 text-sm">
              <summary className="cursor-pointer font-medium">
                반려된 제안의 글 보기 (비교용)
              </summary>
              <pre className="mt-2 max-h-48 overflow-auto font-mono text-xs whitespace-pre-wrap">
                {editingStale.afterContent}
              </pre>
            </details>
          )}
          {keptFresh.length > 0 && (
            <p className="rounded-xl bg-muted/40 p-3 text-sm">
              함께 올라가는 문서 변경 {keptFresh.length}개:{' '}
              {keptFresh.map((item) => item.title).join(', ')}
              <span className="block text-xs text-muted-foreground">
                고치지 않아도 처음 올린 글 그대로 다시 올라가요.
              </span>
            </p>
          )}
          {keptStale.length > 0 && (
            <p className="rounded-xl bg-[#fdf8e6] p-3 text-sm">
              승인본이 바뀐 문서 {keptStale.length}개:{' '}
              {keptStale.map((item) => item.title).join(', ')}
              <span className="block text-xs text-muted-foreground">
                옛 기준에서 쓴 글이라 이번에는 함께 보내지 않아요. 고칠 문서로
                골라 새 기준 원문에서 다시 써 주세요.
              </span>
            </p>
          )}
          {fixedItems.length > 0 && (
            <section
              aria-label="함께 올라가는 설계 항목 변경"
              className="grid gap-1.5 rounded-xl bg-muted/40 p-3 text-sm"
            >
              <p className="font-semibold">
                함께 올라가는 설계 항목 변경 {fixedItems.length}개
              </p>
              <ul className="grid gap-1">
                {fixedItems.map((item, index) => (
                  <li key={index} className="text-xs leading-5">
                    <strong>{item.label}</strong>
                    {item.detail && ` · ${item.detail}`}
                  </li>
                ))}
              </ul>
              <p className="text-xs text-muted-foreground">
                에이전트나 회의에서 만든 항목이라 여기서는 고치지 않고 처음 올린
                그대로 다시 올라가요.
              </p>
            </section>
          )}
        </div>
      </div>
      <div className="flex flex-wrap items-center justify-end gap-2 border-t pt-4">
        <p className="mr-auto text-xs text-muted-foreground">{footnote}</p>
        <Button type="button" variant="outline" onClick={onCancel}>
          취소
        </Button>
        <Button type="submit" disabled={isSubmitting}>
          {isSubmitting ? '처리 중…' : submitLabel}
        </Button>
      </div>
    </form>
  )
}
