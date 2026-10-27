import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Controller, useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { Download, FileText, Search, Upload } from 'lucide-react'
import { documentsApi } from '@/api/services/documents'
import { ActionForm } from '@/components/ActionForm'
import { AsyncState } from '@/components/AsyncState'
import { EmptyState } from '@/components/EmptyState'
import { FormDialog } from '@/components/FormDialog'
import { StatusPill } from '@/components/StatusPill'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { NativeSelect, NativeSelectOption } from '@/components/ui/native-select'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import { statusLabel } from '@/lib/display'
import { apiMode } from '@/lib/apiMode'
import { useProjectAction } from '@/features/workspace/useProjectAction'
import type { Document, DocumentVersion } from '@/api/contracts/common'

const KINDS = ['PLAN', 'ERD', 'REQUIREMENTS', 'API_SPEC'] as const
const stamp = (value: string) =>
  new Intl.DateTimeFormat('ko-KR', {
    dateStyle: 'medium',
    timeStyle: 'short',
    timeZone: 'Asia/Seoul',
  }).format(new Date(value))
const uploadSchema = z.object({
  file: z.instanceof(File, { error: '등록할 파일을 선택해 주세요.' }),
  kind: z.enum(KINDS),
  title: z.string().trim().min(1, '문서 제목을 입력해 주세요.'),
  domain: z.string(),
})
type UploadValues = z.infer<typeof uploadSchema>

export function DocumentsPanel({
  projectId,
  isLeader,
}: {
  projectId: string
  isLeader: boolean
}) {
  const focusId = useWorkspaceStore((state) => state.focusId)
  const openPane = useWorkspaceStore((state) => state.openPane)
  const [uploading, setUploading] = useState(false)
  const [cursor, setCursor] = useState<string>()
  const [search, setSearch] = useState<{
    q: string
    mode: 'KEYWORD' | 'SEMANTIC'
    domain?: string
  }>()
  const documents = useQuery({
    queryKey: ['projects', projectId, 'documents', cursor],
    queryFn: () => documentsApi.list(projectId, { cursor }),
  })
  const results = useQuery({
    queryKey: ['projects', projectId, 'document-search', search],
    queryFn: () => documentsApi.search(projectId, search!),
    enabled: !!search,
  })
  const selected = focusId ?? documents.data?.items[0]?.id
  return (
    <div className="grid gap-6">
      <header className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <p className="text-xs font-medium tracking-widest text-muted-foreground">
            PROJECT KNOWLEDGE
          </p>
          <h2 className="mt-2 text-2xl font-semibold">공용 문서함</h2>
          <p className="mt-2 text-sm leading-6 text-muted-foreground">
            기획안·ERD·요구사항 명세서·API 명세서를 보관하고, 정확한 버전으로
            함께 검토해요.
          </p>
        </div>
        <Button onClick={() => setUploading(true)}>
          <Upload />
          문서 등록
        </Button>
      </header>
      <div className="flex flex-wrap items-center justify-between gap-3 rounded-xl border bg-muted/40 p-4">
        <p className="text-sm text-muted-foreground">
          문서 초안은 승인 전 자료예요. 기획 분석에서 인간 팀장이 기준 설계를
          승인·발행해요.
        </p>
        <Button
          variant="outline"
          size="sm"
          onClick={() => openPane('planning')}
        >
          {isLeader ? '기획 분석에서 승인·발행' : '기획 분석 보기'}
        </Button>
      </div>
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2 text-base">
            <Search className="size-4" />
            문서·설계 검색
          </CardTitle>
        </CardHeader>
        <CardContent>
          <ActionForm
            fields={[
              {
                name: 'q',
                label: '검색어',
                required: true,
                placeholder: '예: 로그인 실패 시 재시도',
              },
              {
                name: 'mode',
                label: '검색 방식',
                type: 'select',
                value: 'KEYWORD',
                options: [
                  { value: 'KEYWORD', label: '키워드 검색' },
                  { value: 'SEMANTIC', label: '의미 검색' },
                ],
              },
              {
                name: 'domain',
                label: '담당 영역 (선택)',
                placeholder: '예: 인증',
              },
            ]}
            submitLabel="검색"
            onSubmit={async (values) => {
              setSearch({
                q: values.q.trim(),
                mode: values.mode === 'SEMANTIC' ? 'SEMANTIC' : 'KEYWORD',
                domain: values.domain.trim() || undefined,
              })
            }}
          />
          {search && (
            <div className="mt-5">
              <div className="mb-3 flex items-center justify-between">
                <h3 className="text-sm font-medium">검색 결과</h3>
                <Button
                  variant="ghost"
                  size="sm"
                  onClick={() => setSearch(undefined)}
                >
                  검색 닫기
                </Button>
              </div>
              <AsyncState query={results}>
                {(data) =>
                  data.items.length ? (
                    <ul className="grid gap-3">
                      {data.items.map((hit) => (
                        <li
                          key={`${hit.type}:${hit.id}`}
                          className="rounded-lg border p-4"
                        >
                          <button
                            type="button"
                            className="text-left font-medium underline-offset-4 hover:underline"
                            disabled={!hit.source}
                            onClick={() => {
                              if (hit.source)
                                openPane('documents', hit.source.document_id)
                            }}
                          >
                            {hit.title}
                          </button>
                          <p className="mt-2 text-sm leading-6 whitespace-pre-wrap">
                            {hit.snippet}
                          </p>
                          <small className="mt-2 block text-muted-foreground">
                            {hit.source
                              ? `문서 #${hit.source.document_id} · v${hit.source.version_no} · ${hit.source.location}`
                              : '문서 출처 없이 승인된 설계 항목'}
                          </small>
                        </li>
                      ))}
                    </ul>
                  ) : (
                    <EmptyState>
                      조건에 맞는 문서가 없어요. 검색어나 담당 영역을 바꿔
                      보세요.
                    </EmptyState>
                  )
                }
              </AsyncState>
            </div>
          )}
        </CardContent>
      </Card>
      <div className="grid gap-5 lg:grid-cols-[15rem_minmax(0,1fr)]">
        <aside>
          <AsyncState query={documents}>
            {(data) => (
              <>
                <nav className="grid gap-2" aria-label="프로젝트 문서">
                  {data.items.map((document) => (
                    <button
                      key={document.id}
                      type="button"
                      aria-current={
                        selected === document.id ? 'true' : undefined
                      }
                      className={`rounded-xl border p-4 text-left transition-colors ${selected === document.id ? 'border-primary bg-primary/5' : 'hover:bg-muted/50'}`}
                      onClick={() => openPane('documents', document.id)}
                    >
                      <span className="flex items-center gap-2 text-xs text-muted-foreground">
                        <FileText className="size-4" />
                        {statusLabel(document.kind)}
                      </span>
                      <strong className="mt-2 block text-sm">
                        {document.title}
                      </strong>
                      <small className="mt-2 block text-muted-foreground">
                        {document.approved_version
                          ? `승인본 v${document.approved_version.version_no}`
                          : '승인 전'}
                        {document.latest_draft
                          ? ` · 초안 v${document.latest_draft.version_no}`
                          : ''}
                      </small>
                    </button>
                  ))}
                </nav>
                {!data.items.length && (
                  <EmptyState>
                    첫 문서를 등록하고 팀의 기준을 준비해 보세요.
                  </EmptyState>
                )}
                {data.has_more && (
                  <Button
                    variant="outline"
                    className="mt-3 w-full"
                    onClick={() => setCursor(data.next_cursor)}
                  >
                    다음 문서
                  </Button>
                )}
                {cursor && (
                  <Button
                    variant="ghost"
                    className="mt-2 w-full"
                    onClick={() => setCursor(undefined)}
                  >
                    처음 목록
                  </Button>
                )}
              </>
            )}
          </AsyncState>
        </aside>
        {selected ? (
          <DocumentDetail
            key={`${projectId}:${selected}`}
            projectId={projectId}
            documentId={selected}
            initialVersionNo={
              documents.data?.items.find((item) => item.id === selected)
                ?.approved_version
                ? undefined
                : documents.data?.items.find((item) => item.id === selected)
                    ?.latest_draft?.version_no
            }
          />
        ) : (
          <EmptyState>
            등록한 문서를 선택하면 본문과 버전 이력이 보여요.
          </EmptyState>
        )}
      </div>
      {uploading && (
        <UploadDocumentDialog
          projectId={projectId}
          onClose={() => setUploading(false)}
          onDone={(id) => {
            setUploading(false)
            openPane('documents', id)
          }}
        />
      )}
    </div>
  )
}

function UploadDocumentDialog({
  projectId,
  onClose,
  onDone,
}: {
  projectId: string
  onClose: () => void
  onDone: (id: string) => void
}) {
  const action = useProjectAction(projectId)
  const {
    register,
    handleSubmit,
    control,
    formState: { errors, isSubmitting },
  } = useForm<UploadValues>({
    resolver: zodResolver(uploadSchema),
    defaultValues: { kind: 'PLAN', title: '', domain: '' },
  })
  const submit = handleSubmit(async (values) => {
    try {
      let documentId: string | undefined
      await action.run(async () => {
        const result = await documentsApi.upload(projectId, {
          ...values,
          domain: values.domain.trim() || undefined,
        })
        documentId = result.document.id
      }, '문서를 등록했어요.')
      if (documentId) onDone(documentId)
    } catch {
      // Keep the selected file and entered values available for a retry.
    }
  })
  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open && !isSubmitting) onClose()
      }}
    >
      <DialogContent
        onEscapeKeyDown={(event) => {
          if (isSubmitting) event.preventDefault()
        }}
        onInteractOutside={(event) => {
          if (isSubmitting) event.preventDefault()
        }}
      >
        <DialogHeader>
          <DialogTitle>공용 문서 등록</DialogTitle>
          <DialogDescription>
            원본 파일을 보관하고 분석을 준비해요. 등록한 문서는 승인 전 초안으로
            남아요.
            {apiMode === 'mock' &&
              ' 예시 환경의 분석은 실제 AI 호출 없이 처리해요.'}
          </DialogDescription>
        </DialogHeader>
        <form
          className="grid gap-4"
          noValidate
          onSubmit={(event) => void submit(event)}
        >
          <div className="grid gap-2">
            <Label htmlFor="document-title">문서 제목</Label>
            <Input
              id="document-title"
              {...register('title')}
              disabled={isSubmitting}
            />
            {errors.title && (
              <p className="text-sm text-destructive" role="alert">
                {errors.title.message}
              </p>
            )}
          </div>
          <div className="grid gap-2">
            <Label htmlFor="document-kind">문서 종류</Label>
            <NativeSelect
              id="document-kind"
              {...register('kind')}
              disabled={isSubmitting}
            >
              {KINDS.map((kind) => (
                <NativeSelectOption key={kind} value={kind}>
                  {statusLabel(kind)}
                </NativeSelectOption>
              ))}
            </NativeSelect>
          </div>
          <div className="grid gap-2">
            <Label htmlFor="document-domain">담당 영역 (선택)</Label>
            <Input
              id="document-domain"
              {...register('domain')}
              disabled={isSubmitting}
            />
          </div>
          <div className="grid gap-2">
            <Label htmlFor="document-file">원본 파일</Label>
            <p className="text-xs text-muted-foreground">
              UTF-8 .md/.txt · 파일 1 MiB 이하 · 텍스트 10만 문자 이하
            </p>
            <Controller
              name="file"
              control={control}
              render={({ field }) => (
                <Input
                  id="document-file"
                  type="file"
                  accept=".md,.txt"
                  name={field.name}
                  ref={field.ref}
                  onBlur={field.onBlur}
                  disabled={isSubmitting}
                  onChange={(event) => field.onChange(event.target.files?.[0])}
                />
              )}
            />
            {errors.file && (
              <p className="text-sm text-destructive" role="alert">
                {errors.file.message}
              </p>
            )}
          </div>
          {action.error && (
            <p className="text-sm text-destructive" role="alert">
              {action.error}
            </p>
          )}
          <Button type="submit" disabled={isSubmitting}>
            {isSubmitting ? '등록 중…' : '문서 등록'}
          </Button>
        </form>
      </DialogContent>
    </Dialog>
  )
}

function DocumentDetail({
  projectId,
  documentId,
  initialVersionNo,
}: {
  projectId: string
  documentId: string
  initialVersionNo?: number
}) {
  const action = useProjectAction(projectId)
  const [versionNo, setVersionNo] = useState<number>()
  const [editing, setEditing] = useState(false)
  const [classification, setClassification] = useState(false)
  const [comparison, setComparison] = useState<{
    from_version: number
    to_version: number
  }>()
  const document = useQuery({
    queryKey: [
      'projects',
      projectId,
      'document',
      documentId,
      versionNo ?? initialVersionNo,
    ],
    queryFn: () =>
      documentsApi.get(projectId, documentId, {
        version_no: versionNo ?? initialVersionNo,
      }),
  })
  const versions = useQuery({
    queryKey: ['projects', projectId, 'document-versions', documentId],
    queryFn: () => documentsApi.versions(projectId, documentId),
  })
  const difference = useQuery({
    queryKey: ['projects', projectId, 'document-diff', documentId, comparison],
    queryFn: () => documentsApi.diff(projectId, documentId, comparison!),
    enabled: !!comparison,
  })
  const download = async (version: DocumentVersion) => {
    await action.run(async () => {
      const blob = await documentsApi.download(
        projectId,
        documentId,
        version.version_no,
      )
      const url = URL.createObjectURL(blob),
        link = window.document.createElement('a')
      link.href = url
      link.download = version.file_name
      link.click()
      URL.revokeObjectURL(url)
    }, '원본 파일을 내려받았어요.')
  }
  return (
    <AsyncState query={document}>
      {(data) => (
        <Card className="min-w-0">
          <CardHeader>
            <div className="flex flex-wrap items-start justify-between gap-3">
              <div>
                <p className="text-xs text-muted-foreground">
                  {statusLabel(data.document.kind)} · 문서 #{data.document.id}
                </p>
                <CardTitle className="mt-2 text-xl">
                  {data.document.title}
                </CardTitle>
              </div>
              <StatusPill value={data.version.status} />
            </div>
            <div className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
              <span>담당 영역: {data.document.domain || '미분류'}</span>
              <Button
                variant="ghost"
                size="sm"
                onClick={() => setClassification(true)}
              >
                분류 수정
              </Button>
            </div>
          </CardHeader>
          <CardContent className="grid gap-5">
            <div className="flex flex-wrap items-center justify-between gap-3">
              <div>
                <Label htmlFor="document-version" className="sr-only">
                  열람할 문서 버전
                </Label>
                <NativeSelect
                  id="document-version"
                  value={versionNo?.toString() ?? 'latest'}
                  onChange={(event) =>
                    setVersionNo(
                      event.target.value === 'latest'
                        ? undefined
                        : Number(event.target.value),
                    )
                  }
                >
                  <NativeSelectOption value="latest">
                    최신 버전
                  </NativeSelectOption>
                  {versions.data?.items.map((version) => (
                    <NativeSelectOption
                      key={version.id}
                      value={String(version.version_no)}
                    >
                      v{version.version_no} · {statusLabel(version.status)}
                    </NativeSelectOption>
                  ))}
                </NativeSelect>
              </div>
              <Button
                variant="outline"
                size="sm"
                disabled={action.busy}
                onClick={() =>
                  void download(data.version).catch(() => undefined)
                }
              >
                <Download />
                원본 다운로드
              </Button>
            </div>
            <p className="text-xs text-muted-foreground">
              v{data.version.version_no} · {data.version.file_name} ·{' '}
              {stamp(data.version.created_at)}
            </p>
            {data.version.content !== undefined ? (
              <pre className="max-h-[36rem] overflow-auto rounded-xl border bg-muted/20 p-5 font-sans text-sm leading-7 whitespace-pre-wrap">
                {data.version.content}
              </pre>
            ) : (
              <EmptyState>
                이 파일은 본문 미리보기를 제공하지 않아요. 원본 파일을 내려받아
                확인해 주세요.
              </EmptyState>
            )}
            <div className="flex flex-wrap items-center gap-3">
              <Button variant="outline" onClick={() => setEditing(true)}>
                수정 초안 작성
              </Button>
              <p className="text-xs text-muted-foreground">
                v{data.version.version_no}을 기준으로 새 초안을 보관해요. 승인된
                본문은 바꾸지 않아요.
              </p>
            </div>
            {action.error && (
              <p className="text-sm text-destructive" role="alert">
                {action.error}
              </p>
            )}
            {action.message && (
              <p className="text-sm text-emerald-700" role="status">
                {action.message}
              </p>
            )}
            <section className="grid gap-3 border-t pt-5">
              <h3 className="font-medium">버전 이력</h3>
              <AsyncState query={versions}>
                {(history) => (
                  <ul className="grid gap-2">
                    {history.items.map((version) => (
                      <li
                        key={version.id}
                        className="flex flex-wrap items-center justify-between gap-2 rounded-lg bg-muted/40 p-3 text-sm"
                      >
                        <button
                          type="button"
                          className="font-medium hover:underline"
                          onClick={() => setVersionNo(version.version_no)}
                        >
                          v{version.version_no} · {version.file_name}
                        </button>
                        <span className="flex items-center gap-3">
                          <StatusPill value={version.status} />
                          <small className="text-muted-foreground">
                            {stamp(version.created_at)}
                          </small>
                        </span>
                      </li>
                    ))}
                  </ul>
                )}
              </AsyncState>
            </section>
            {(versions.data?.items.length ?? 0) > 1 && (
              <section className="grid gap-3 border-t pt-5">
                <h3 className="font-medium">버전 비교</h3>
                <ActionForm
                  fields={['from_version', 'to_version'].map((name, index) => ({
                    name,
                    label: index ? '비교할 버전' : '기준 버전',
                    type: 'select' as const,
                    value: String(
                      versions.data!.items[index ? 0 : 1].version_no,
                    ),
                    options: versions.data!.items.map((version) => ({
                      value: String(version.version_no),
                      label: `v${version.version_no}`,
                    })),
                  }))}
                  submitLabel="변경 내용 비교"
                  onSubmit={async (values) =>
                    setComparison({
                      from_version: Number(values.from_version),
                      to_version: Number(values.to_version),
                    })
                  }
                />
                {comparison && (
                  <AsyncState query={difference}>
                    {(diff) =>
                      diff.format === 'TEXT' ? (
                        <pre className="max-h-80 overflow-auto rounded-lg bg-muted p-4 text-xs leading-6 whitespace-pre-wrap">
                          {diff.diff || '두 버전의 본문이 같아요.'}
                        </pre>
                      ) : (
                        <p className="text-sm text-muted-foreground">
                          파일 형식은 본문 차이를 표시하지 않아요. 각 버전의
                          원본 파일을 내려받아 비교해 주세요.
                        </p>
                      )
                    }
                  </AsyncState>
                )}
              </section>
            )}
            {classification && (
              <FormDialog
                title="문서 분류 수정"
                fields={[
                  {
                    name: 'domain',
                    label: '담당 영역',
                    value: data.document.domain ?? '',
                  },
                ]}
                onClose={() => setClassification(false)}
                onSubmit={async (values) => {
                  await action.run(
                    () =>
                      documentsApi.update(projectId, documentId, {
                        domain: values.domain.trim(),
                      }),
                    '담당 영역을 저장했어요.',
                  )
                  setClassification(false)
                }}
              />
            )}
            {editing && (
              <DraftDialog
                projectId={projectId}
                document={data.document}
                version={data.version}
                onClose={() => setEditing(false)}
              />
            )}
          </CardContent>
        </Card>
      )}
    </AsyncState>
  )
}

function DraftDialog({
  projectId,
  document,
  version,
  onClose,
}: {
  projectId: string
  document: Document
  version: DocumentVersion
  onClose: () => void
}) {
  const action = useProjectAction(projectId)
  return (
    <FormDialog
      title="문서 수정 초안"
      description={`v${version.version_no}을 기준으로 새 파일 버전을 등록해요. 기준이 바뀌면 저장이 거부되며 입력 내용은 유지돼요.`}
      fields={[
        {
          name: 'content',
          label: '수정할 본문',
          type: 'textarea',
          required: true,
          value: version.content ?? '',
        },
      ]}
      onClose={onClose}
      submitLabel="새 초안 저장"
      onSubmit={async (values) => {
        await action.run(
          () =>
            documentsApi.uploadDraft(projectId, document.id, {
              file: new File([values.content], `${document.title}.md`, {
                type: 'text/markdown',
              }),
              base_document_version_id: version.id,
            }),
          '새 초안을 등록했어요.',
        )
        onClose()
      }}
    />
  )
}
