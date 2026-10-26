import { useState } from 'react'
import { useInfiniteQuery, useQuery } from '@tanstack/react-query'
import { ArrowLeftRight, MessageSquare, Plus, Sparkles } from 'lucide-react'
import {
  changesApi,
  documentsApi,
  membersApi,
  meetingsApi,
} from '@/api/services'
import type { Baseline, ChangeProposal, Document } from '@/api/contracts/common'
import { AsyncState } from '@/components/AsyncState'
import { EmptyState } from '@/components/EmptyState'
import { FormDialog } from '@/components/FormDialog'
import { StatusPill } from '@/components/StatusPill'
import { Button } from '@/components/ui/button'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { NativeSelect, NativeSelectOption } from '@/components/ui/native-select'
import { useProjectAction } from '@/features/workspace/useProjectAction'
import { useSession } from '@/features/auth/useSession'
import { useWorkspaceStore } from '@/stores/workspaceStore'

interface Props {
  projectId: string
  isLeader: boolean
}
type ChangeStatus = ChangeProposal['status']
type Compose = {
  after_content?: string
  document_id?: string
  template?: boolean
} | null
const readable = (value: unknown) =>
  typeof value === 'string' ? value : JSON.stringify(value, null, 2)

export function ChangesPanel({ projectId, isLeader }: Props) {
  const session = useSession()
  const focusId = useWorkspaceStore((state) => state.focusId)
  const openPane = useWorkspaceStore((state) => state.openPane)
  const [selected, setSelected] = useState<{
    id: string
    focus: string | null
  } | null>(null)
  const [filter, setFilter] = useState<ChangeStatus | ''>('')
  const [composing, setComposing] = useState<Compose>(null)
  const [generating, setGenerating] = useState(false)
  const [commenting, setCommenting] = useState(false)
  const [rejecting, setRejecting] = useState(false)
  const [discussing, setDiscussing] = useState(false)
  const [resubmitting, setResubmitting] = useState(false)
  const [formProposal, setFormProposal] = useState<ChangeProposal | null>(null)
  const [formBaseline, setFormBaseline] = useState<Baseline | null>(null)
  const [formDocuments, setFormDocuments] = useState<Document[]>([])
  const action = useProjectAction(projectId)
  const changes = useInfiniteQuery({
    queryKey: ['projects', projectId, 'changes', filter],
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) =>
      changesApi.list(projectId, {
        status: filter || undefined,
        cursor: pageParam,
        limit: 30,
      }),
    getNextPageParam: (page) => (page.has_more ? page.next_cursor : undefined),
  })
  const entries = changes.data?.pages.flatMap((page) => page.items) ?? []
  const selectedId =
    selected?.focus === focusId ? selected.id : (focusId ?? entries[0]?.id)
  const detail = useQuery({
    queryKey: ['projects', projectId, 'change', selectedId],
    queryFn: () => changesApi.get(projectId, selectedId!),
    enabled: !!selectedId,
  })
  const impact = useQuery({
    queryKey: ['projects', projectId, 'impact', selectedId],
    queryFn: () => changesApi.impact(projectId, selectedId!),
    enabled: !!selectedId,
    refetchInterval: (query) =>
      ['PENDING', 'RUNNING'].includes(query.state.data?.analysis_status ?? '')
        ? 1500
        : false,
  })
  const comments = useInfiniteQuery({
    queryKey: ['projects', projectId, 'change-comments', selectedId],
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) =>
      changesApi.comments(projectId, selectedId!, {
        cursor: pageParam,
        limit: 30,
      }),
    enabled: !!selectedId,
    getNextPageParam: (page) => (page.has_more ? page.next_cursor : undefined),
  })
  const approval = useQuery({
    queryKey: ['projects', projectId, 'change-approval', selectedId],
    queryFn: () => changesApi.approval(projectId, selectedId!),
    enabled: detail.data?.proposal.status === 'PUBLISHED',
  })
  const baseline = useQuery({
    queryKey: ['projects', projectId, 'baseline'],
    queryFn: () => documentsApi.baseline(projectId),
  })
  const documents = useQuery({
    queryKey: ['projects', projectId, 'change-documents'],
    queryFn: () => documentsApi.list(projectId, { limit: 100 }),
  })
  const members = useQuery({
    queryKey: ['projects', projectId, 'members'],
    queryFn: () => membersApi.list(projectId, { limit: 100 }),
  })
  const proposal = detail.data?.proposal
  const editable =
    proposal && ['PROPOSED', 'IN_REVIEW'].includes(proposal.status)
  const approvedDocuments =
    documents.data?.items.filter((document) => document.approved_version) ?? []
  const memberName = (id: string) =>
    members.data?.items.find((member) => member.id === id)?.name ??
    '담당자 확인 필요'
  const prepareForm = (proposal?: ChangeProposal) => {
    setFormProposal(proposal ?? null)
    setFormBaseline(baseline.data ?? null)
    setFormDocuments(approvedDocuments)
  }
  const formDocumentOptions = formDocuments.map((document) => ({
    value: document.id,
    label: document.title,
  }))
  const perform = (operation: () => Promise<unknown>, message: string) => {
    void action.run(operation, message).catch(() => undefined)
  }

  return (
    <section className="space-y-6">
      <header className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <p className="mb-2 text-xs font-semibold tracking-[.18em] text-primary">
            DESIGN REVIEW
          </p>
          <h2 className="text-2xl font-semibold tracking-tight">
            더 좋은 설계로, 함께 바꾸기
          </h2>
          <p className="mt-2 max-w-2xl text-sm leading-6 text-muted-foreground">
            바뀌는 내용과 영향을 살펴보고, 인간 팀장의 승인으로 팀의 새 기준을
            만들어요.
          </p>
        </div>
        <div className="flex flex-wrap gap-2">
          <Button
            variant="outline"
            disabled={!baseline.data || !approvedDocuments.length}
            onClick={() => {
              prepareForm()
              setGenerating(true)
            }}
          >
            <Sparkles />
            수정안 도움받기
          </Button>
          <Button
            disabled={!baseline.data || !approvedDocuments.length}
            onClick={() => {
              prepareForm()
              setComposing({})
            }}
          >
            <Plus />
            변경 제안
          </Button>
        </div>
      </header>
      {baseline.error && (
        <p className="rounded-lg bg-muted p-3 text-sm text-muted-foreground">
          현재 승인 기준을 확인할 수 없어요. 기획 분석에서 첫 기준 설계를 발행한
          뒤 변경을 제안할 수 있어요.
        </p>
      )}
      {action.message && (
        <p role="status" className="rounded-lg bg-primary/5 p-3 text-sm">
          {action.message}
        </p>
      )}
      {action.error && (
        <p
          role="alert"
          className="rounded-lg bg-destructive/5 p-3 text-sm text-destructive"
        >
          {action.error}
        </p>
      )}
      <div className="grid gap-6 lg:grid-cols-[.7fr_1.3fr]">
        <Card>
          <CardHeader>
            <CardTitle>제안 목록</CardTitle>
            <NativeSelect
              aria-label="제안 상태"
              value={filter}
              onChange={(event) =>
                setFilter(event.target.value as ChangeStatus | '')
              }
            >
              <NativeSelectOption value="">모든 상태</NativeSelectOption>
              {(
                ['PROPOSED', 'IN_REVIEW', 'PUBLISHED', 'REJECTED'] as const
              ).map((status) => (
                <NativeSelectOption key={status} value={status}>
                  {
                    {
                      PROPOSED: '새 제안',
                      IN_REVIEW: '검토 중',
                      PUBLISHED: '발행 완료',
                      REJECTED: '반려',
                    }[status]
                  }
                </NativeSelectOption>
              ))}
            </NativeSelect>
          </CardHeader>
          <CardContent>
            <AsyncState query={changes}>
              {() => (
                <div className="space-y-2">
                  {entries.length ? (
                    entries.map((entry) => (
                      <button
                        type="button"
                        key={entry.id}
                        onClick={() =>
                          setSelected({ id: entry.id, focus: focusId })
                        }
                        className={`w-full rounded-lg border p-4 text-left transition-colors hover:bg-muted/50 ${selectedId === entry.id ? 'border-primary/30 bg-primary/5' : ''}`}
                      >
                        <span className="mb-2 block text-sm font-medium">
                          {entry.title}
                        </span>
                        <StatusPill value={entry.status} />
                        {entry.requires_refresh && (
                          <span className="ml-2 text-xs text-amber-700">
                            기준 갱신 필요
                          </span>
                        )}
                      </button>
                    ))
                  ) : (
                    <EmptyState>
                      설계에 관해 바꾸고 싶은 점을 제안해 보세요.
                    </EmptyState>
                  )}
                  {changes.hasNextPage && (
                    <Button
                      variant="outline"
                      disabled={changes.isFetchingNextPage}
                      onClick={() => void changes.fetchNextPage()}
                    >
                      제안 더 보기
                    </Button>
                  )}
                </div>
              )}
            </AsyncState>
          </CardContent>
        </Card>
        {!selectedId ? (
          <EmptyState>
            제안을 선택하면 비교 내용과 영향을 확인할 수 있어요.
          </EmptyState>
        ) : (
          <AsyncState query={detail}>
            {(data) => (
              <div className="space-y-5">
                <Card>
                  <CardHeader>
                    <div className="flex flex-wrap items-center justify-between gap-3">
                      <CardTitle>{data.proposal.title}</CardTitle>
                      <StatusPill value={data.proposal.status} />
                    </div>
                    <CardDescription>{data.proposal.reason}</CardDescription>
                  </CardHeader>
                  <CardContent className="space-y-4">
                    {data.proposal.requires_refresh && (
                      <p className="rounded-lg bg-amber-50 p-3 text-sm text-amber-900">
                        제안 후 기준 설계가 바뀌었어요. 최신 원문을 확인하고 새
                        기준으로 다시 제출해 주세요.
                      </p>
                    )}
                    {data.proposal.rejection_reason && (
                      <p className="rounded-lg bg-destructive/5 p-3 text-sm">
                        반려 사유: {data.proposal.rejection_reason}
                      </p>
                    )}
                    {data.comparisons.map((comparison, index) => (
                      <div
                        key={`${comparison.target_id}-${index}`}
                        className="space-y-3 border-t pt-4"
                      >
                        <p className="flex items-center gap-2 text-sm font-medium">
                          <ArrowLeftRight className="size-4" />
                          {comparison.target_type === 'DOCUMENT'
                            ? '문서 변경'
                            : '설계 항목 변경'}
                        </p>
                        {comparison.format === 'FILE' ? (
                          <p className="text-sm text-muted-foreground">
                            파일 버전 변경이에요. 공용 문서함에서 두 원본을
                            내려받아 확인하세요.
                          </p>
                        ) : (
                          <div className="grid gap-3 xl:grid-cols-2">
                            <div>
                              <p className="mb-2 text-xs font-medium text-muted-foreground">
                                현재 기준
                              </p>
                              <pre className="max-h-64 overflow-auto rounded-lg bg-muted/50 p-3 text-xs leading-6 break-words whitespace-pre-wrap">
                                {readable(
                                  comparison.current_content ??
                                    comparison.base_content,
                                )}
                              </pre>
                            </div>
                            <div>
                              <p className="mb-2 text-xs font-medium text-primary">
                                제안 내용
                              </p>
                              <pre className="max-h-64 overflow-auto rounded-lg border border-primary/15 bg-primary/5 p-3 text-xs leading-6 break-words whitespace-pre-wrap">
                                {readable(comparison.proposed_content)}
                              </pre>
                            </div>
                          </div>
                        )}
                      </div>
                    ))}
                    {editable &&
                      data.proposal.proposer_account_id === session.data?.id &&
                      data.proposal.documents.length === 1 &&
                      data.proposal.items.length === 0 &&
                      data.proposal.documents[0].after_content !==
                        undefined && (
                        <Button
                          variant="outline"
                          disabled={action.busy || !baseline.data}
                          onClick={() => {
                            prepareForm(data.proposal)
                            setResubmitting(true)
                          }}
                        >
                          제안 수정·최신 기준으로 재제출
                        </Button>
                      )}
                    {isLeader && editable && (
                      <div className="flex flex-wrap gap-2 border-t pt-4">
                        {data.proposal.status === 'PROPOSED' && (
                          <Button
                            variant="outline"
                            disabled={action.busy}
                            onClick={() =>
                              perform(
                                () =>
                                  changesApi.review(
                                    projectId,
                                    data.proposal.id,
                                    {
                                      expected_updated_at:
                                        data.proposal.updated_at,
                                    },
                                  ),
                                '제안 검토를 시작했어요.',
                              )
                            }
                          >
                            검토 시작
                          </Button>
                        )}
                        <Button
                          disabled={
                            action.busy || data.proposal.requires_refresh
                          }
                          onClick={() =>
                            perform(
                              () =>
                                changesApi.approve(
                                  projectId,
                                  data.proposal.id,
                                  {
                                    expected_base_baseline_id:
                                      data.proposal.base_baseline_id,
                                    expected_updated_at:
                                      data.proposal.updated_at,
                                  },
                                ),
                              '제안을 승인하고 새 기준을 발행했어요.',
                            )
                          }
                        >
                          승인하고 기준 발행
                        </Button>
                        <Button
                          variant="outline"
                          disabled={action.busy}
                          onClick={() => {
                            prepareForm(data.proposal)
                            setRejecting(true)
                          }}
                        >
                          반려·보완 요청
                        </Button>
                      </div>
                    )}
                    {data.proposal.status === 'PUBLISHED' && (
                      <AsyncState query={approval}>
                        {(result) => (
                          <p className="text-sm text-muted-foreground">
                            승인자:{' '}
                            {members.data?.items.find(
                              (member) =>
                                member.account_id === result.approved_by,
                            )?.name ?? '인간 팀장'}{' '}
                            · 새 기준을 발행했어요.
                          </p>
                        )}
                      </AsyncState>
                    )}
                  </CardContent>
                </Card>
                <Card>
                  <CardHeader>
                    <div className="flex flex-wrap items-center justify-between gap-3">
                      <CardTitle>변경 영향 확인</CardTitle>
                      <Button
                        size="sm"
                        variant="outline"
                        disabled={
                          action.busy ||
                          ['PENDING', 'RUNNING'].includes(
                            impact.data?.analysis_status ?? '',
                          )
                        }
                        onClick={() =>
                          perform(
                            () =>
                              changesApi.retryImpact(
                                projectId,
                                data.proposal.id,
                              ),
                            '추가 영향 분석을 접수했어요.',
                          )
                        }
                      >
                        <Sparkles />
                        AI 심층 분석
                      </Button>
                    </div>
                    <CardDescription>
                      명시된 관계와 AI가 제안한 영향 후보를 구분해요.
                    </CardDescription>
                  </CardHeader>
                  <CardContent>
                    <AsyncState query={impact}>
                      {(result) => (
                        <div className="space-y-3">
                          <StatusPill value={result.analysis_status} />
                          {result.analysis_status === 'FAILED' && (
                            <p
                              role="alert"
                              className="text-sm text-destructive"
                            >
                              추가 분석을 완료하지 못했어요. 명시 관계의 영향은
                              아래에서 계속 확인할 수 있어요.
                            </p>
                          )}
                          {result.explicit.length ? (
                            result.explicit.map((entry, index) => (
                              <p
                                key={index}
                                className="rounded-lg border p-3 text-sm"
                              >
                                연결 작업 {entry.task_id} ·{' '}
                                {memberName(entry.member_id)}
                                <span className="mt-1 block text-xs text-muted-foreground">
                                  설계 항목 {entry.design_item_id}
                                  {entry.validation_id
                                    ? ` · 관련 검증 ${entry.validation_id}`
                                    : ''}
                                </span>
                              </p>
                            ))
                          ) : (
                            <p className="text-sm text-muted-foreground">
                              등록된 명시 관계의 영향이 없어요.
                            </p>
                          )}
                          {result.candidates.map((candidate, index) => (
                            <article
                              key={index}
                              className="rounded-lg border border-dashed p-3"
                            >
                              <p className="text-xs font-medium text-primary">
                                영향 후보 · 추가 확인 필요
                              </p>
                              <p className="mt-2 text-sm leading-6">
                                {candidate.reason}
                              </p>
                              {candidate.member_id && (
                                <p className="mt-1 text-xs text-muted-foreground">
                                  관련 담당자: {memberName(candidate.member_id)}
                                </p>
                              )}
                              {candidate.sources.map((source, sourceIndex) => (
                                <p
                                  key={sourceIndex}
                                  className="mt-1 text-xs text-muted-foreground"
                                >
                                  문서 {source.document_id} v{source.version_no}{' '}
                                  · {source.location}
                                </p>
                              ))}
                            </article>
                          ))}
                          <p className="text-xs leading-5 text-muted-foreground">
                            후보가 없더라도 영향이 없음을 보증하지 않아요.
                            {result.generation?.origin === 'TEMPLATE'
                              ? ' 현재 결과는 모델 분석이 아닌 템플릿 설명이에요.'
                              : ''}
                          </p>
                        </div>
                      )}
                    </AsyncState>
                  </CardContent>
                </Card>
                <Card>
                  <CardHeader>
                    <div className="flex flex-wrap items-center justify-between gap-3">
                      <CardTitle>의견과 검토 요청</CardTitle>
                      <div className="flex gap-2">
                        <Button
                          size="sm"
                          variant="outline"
                          onClick={() => {
                            prepareForm(data.proposal)
                            setDiscussing(true)
                          }}
                        >
                          회의로 논의
                        </Button>
                        <Button
                          size="sm"
                          variant="outline"
                          onClick={() => {
                            prepareForm(data.proposal)
                            setCommenting(true)
                          }}
                        >
                          <MessageSquare />
                          의견 남기기
                        </Button>
                      </div>
                    </div>
                  </CardHeader>
                  <CardContent>
                    <AsyncState query={comments}>
                      {(result) => (
                        <div className="space-y-3">
                          {result.pages
                            .flatMap((page) => page.items)
                            .map((comment) => (
                              <article
                                key={comment.id}
                                className="rounded-lg bg-muted/40 p-3"
                              >
                                <p className="mb-1 text-xs text-muted-foreground">
                                  {members.data?.items.find(
                                    (member) =>
                                      member.account_id ===
                                      comment.author_account_id,
                                  )?.name ?? '팀원'}{' '}
                                  ·{' '}
                                  {comment.kind === 'REVIEW_REQUEST'
                                    ? '검토 요청'
                                    : '의견'}
                                </p>
                                <p className="text-sm leading-6 whitespace-pre-wrap">
                                  {comment.body}
                                </p>
                              </article>
                            ))}
                          {!result.pages.some((page) => page.items.length) && (
                            <p className="text-sm text-muted-foreground">
                              검토 의견을 남겨 함께 확인해 보세요.
                            </p>
                          )}
                          {comments.hasNextPage && (
                            <Button
                              size="sm"
                              variant="outline"
                              onClick={() => void comments.fetchNextPage()}
                            >
                              의견 더 보기
                            </Button>
                          )}
                        </div>
                      )}
                    </AsyncState>
                  </CardContent>
                </Card>
              </div>
            )}
          </AsyncState>
        )}
      </div>
      {composing && formBaseline && (
        <FormDialog
          title="설계 변경 제안"
          description={
            composing.template
              ? '현재 수정안은 템플릿으로 만들었어요. 승인 원문과 비교해 전체 내용을 확인한 뒤 제출해 주세요.'
              : '승인 원문과 달라지는 전체 내용을 작성해 주세요. 제출만으로 승인본이 바뀌지는 않아요.'
          }
          fields={[
            {
              name: 'document_id',
              label: '변경할 문서',
              type: 'select',
              required: true,
              value: composing.document_id ?? formDocumentOptions[0]?.value,
              options: formDocumentOptions,
            },
            { name: 'title', label: '제안 제목', required: true },
            {
              name: 'reason',
              label: '바꾸려는 이유',
              type: 'textarea',
              required: true,
            },
            {
              name: 'after_content',
              label: '수정한 원문 전체',
              type: 'textarea',
              required: true,
              value: composing.after_content,
            },
          ]}
          submitLabel="제안 제출"
          onClose={() => setComposing(null)}
          onSubmit={async (values) => {
            const document = formDocuments.find(
              (item) => item.id === values.document_id,
            )
            if (!document?.approved_version)
              throw new Error('승인 원문 확인 필요')
            await action.run(async () => {
              const result = await changesApi.create(projectId, {
                base_baseline_id: formBaseline!.id,
                title: values.title,
                reason: values.reason,
                items: [],
                documents: [
                  {
                    document_id: document.id,
                    base_document_version_id: document.approved_version!.id,
                    after_content: values.after_content,
                  },
                ],
              })
              setSelected({ id: result.id, focus: focusId })
            }, '변경 제안을 제출했어요.')
            setComposing(null)
          }}
        />
      )}
      {generating && (
        <FormDialog
          title="비비와 문서 수정안 만들기"
          description="생성된 수정안을 확인하고 변경 제안으로 제출할 수 있어요."
          fields={[
            {
              name: 'document_id',
              label: '대상 문서',
              type: 'select',
              value: formDocumentOptions[0]?.value,
              options: formDocumentOptions,
              required: true,
            },
            {
              name: 'instruction',
              label: '수정하고 싶은 내용',
              type: 'textarea',
              required: true,
            },
          ]}
          submitLabel="수정안 생성"
          onClose={() => setGenerating(false)}
          onSubmit={async (values) => {
            const document = formDocuments.find(
              (item) => item.id === values.document_id,
            )
            if (!document?.approved_version)
              throw new Error('승인 원문 확인 필요')
            await action.run(async () => {
              const result = await changesApi.generateDraft(projectId, {
                document_id: document.id,
                base_document_version_id: document.approved_version!.id,
                instruction: values.instruction,
              })
              setComposing({
                document_id: document.id,
                after_content: result.after_content,
                template: result.generation.origin === 'TEMPLATE',
              })
              setGenerating(false)
            }, '수정안을 만들었어요. 원문과 비교해 제출해 주세요.')
          }}
        />
      )}
      {rejecting && formProposal && (
        <FormDialog
          title="반려·보완 요청"
          fields={[
            {
              name: 'reason',
              label: '보완이 필요한 이유',
              type: 'textarea',
              required: true,
            },
          ]}
          onClose={() => setRejecting(false)}
          onSubmit={async (values) => {
            await action.run(
              () =>
                changesApi.reject(projectId, formProposal.id, {
                  reason: values.reason,
                  expected_updated_at: formProposal.updated_at,
                }),
              '반려 사유를 남겼어요.',
            )
            setRejecting(false)
          }}
        />
      )}
      {resubmitting && formProposal && formBaseline && (
        <FormDialog
          title="제안 수정·재제출"
          description="최신 승인 원문과 수정안을 다시 비교해 주세요. 검토 이력은 보존하며 제안 상태는 검토 전으로 돌아가요."
          fields={[
            {
              name: 'title',
              label: '제안 제목',
              value: formProposal.title,
              required: true,
            },
            {
              name: 'reason',
              label: '바꾸려는 이유',
              value: formProposal.reason,
              type: 'textarea',
              required: true,
            },
            {
              name: 'after_content',
              label: '수정한 원문 전체',
              value: formProposal.documents[0].after_content,
              type: 'textarea',
              required: true,
            },
          ]}
          onClose={() => setResubmitting(false)}
          submitLabel="최신 기준으로 재제출"
          onSubmit={async (values) => {
            const currentDocument = formDocuments.find(
              (document) =>
                document.id === formProposal.documents[0].document_id,
            )
            if (!currentDocument?.approved_version)
              throw new Error('현재 승인 원문 확인 필요')
            await action.run(
              () =>
                changesApi.update(projectId, formProposal.id, {
                  base_baseline_id: formBaseline!.id,
                  title: values.title,
                  reason: values.reason,
                  task_id: formProposal.task_id,
                  validation_id: formProposal.validation_id,
                  items: [],
                  documents: [
                    {
                      document_id: currentDocument.id,
                      base_document_version_id:
                        currentDocument.approved_version!.id,
                      after_content: values.after_content,
                    },
                  ],
                  expected_updated_at: formProposal.updated_at,
                }),
              '제안을 최신 기준으로 다시 제출했어요.',
            )
            setResubmitting(false)
          }}
        />
      )}
      {commenting && formProposal && (
        <FormDialog
          title="의견 남기기"
          fields={[
            {
              name: 'kind',
              label: '의견 종류',
              type: 'select',
              value: 'COMMENT',
              options: [
                { value: 'COMMENT', label: '검토 의견' },
                { value: 'REVIEW_REQUEST', label: '검토 요청' },
              ],
            },
            { name: 'body', label: '내용', type: 'textarea', required: true },
          ]}
          onClose={() => setCommenting(false)}
          onSubmit={async (values) => {
            await action.run(
              () =>
                changesApi.comment(projectId, formProposal.id, {
                  body: values.body,
                  kind:
                    values.kind === 'REVIEW_REQUEST'
                      ? 'REVIEW_REQUEST'
                      : 'COMMENT',
                }),
              '의견을 남겼어요.',
            )
            setCommenting(false)
          }}
        />
      )}
      {discussing && formProposal && (
        <FormDialog
          title="변경 제안을 함께 논의하기"
          description="관련 팀원을 선택해 회의를 만들어요. 전체 팀원을 자동으로 호출하지 않아요."
          fields={[
            {
              name: 'title',
              label: '회의 제목',
              value: formProposal.title,
              required: true,
            },
            {
              name: 'member_id',
              label: '함께 논의할 팀원',
              type: 'select',
              required: true,
              options: members.data?.items.map((member) => ({
                value: member.id,
                label: member.name,
              })),
            },
          ]}
          submitLabel="회의 만들기"
          onClose={() => setDiscussing(false)}
          onSubmit={async (values) => {
            await action.run(async () => {
              const meeting = await meetingsApi.create(projectId, {
                title: values.title,
                change_proposal_id: formProposal.id,
                participant_member_ids: [values.member_id],
              })
              setDiscussing(false)
              openPane('meetings', meeting.id)
            }, '관련 팀원과 논의할 회의를 만들었어요.')
          }}
        />
      )}
    </section>
  )
}
