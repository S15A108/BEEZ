import { useState } from 'react'
import { useInfiniteQuery, useQueries, useQuery } from '@tanstack/react-query'
import { FileCheck2, Lightbulb, RefreshCw, Sparkles } from 'lucide-react'
import { documentsApi, planningApi } from '@/api/services'
import type { DesignItemVersion } from '@/api/contracts/common'
import { structuredContentSchema } from '@/api/contracts/common'
import { getApiErrorStatus } from '@/api/error'
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
import { useProjectAction } from '@/features/workspace/useProjectAction'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import { statusLabel } from '@/lib/display'

interface Props {
  projectId: string
  isLeader: boolean
}

export function PlanningPanel({ projectId, isLeader }: Props) {
  const action = useProjectAction(projectId)
  const [editing, setEditing] = useState<DesignItemVersion | null>(null)
  const [answering, setAnswering] = useState<{
    id: string
    question: string
    answer?: string
  } | null>(null)
  const [publishing, setPublishing] = useState<{
    hash: string
    documents: string[]
    items: string[]
  } | null>(null)
  const [adding, setAdding] = useState(false)
  const [generationHash, setGenerationHash] = useState<string | null>(null)
  const openPane = useWorkspaceStore((state) => state.openPane)
  const documents = useQuery({
    queryKey: ['projects', projectId, 'planning-documents'],
    queryFn: () => documentsApi.list(projectId, { limit: 100 }),
  })
  const versions =
    documents.data?.items.flatMap((document) =>
      document.latest_draft ? [document.latest_draft] : [],
    ) ?? []
  const analyses = useQueries({
    queries: versions.map((version) => ({
      queryKey: ['projects', projectId, 'analysis', version.id],
      queryFn: () =>
        planningApi.analysis(projectId, version.document_id, {
          document_version_id: version.id,
        }),
      refetchInterval: (query: {
        state: { data: { status: string } | undefined }
      }) =>
        ['PENDING', 'RUNNING'].includes(query.state.data?.status ?? '')
          ? 1500
          : false,
    })),
  })
  const baseline = useQuery({
    queryKey: ['projects', projectId, 'baseline'],
    queryFn: async () => {
      try {
        return await documentsApi.baseline(projectId)
      } catch (error) {
        if (getApiErrorStatus(error) === 404) return null
        throw error
      }
    },
  })
  const drafts = useInfiniteQuery({
    queryKey: ['projects', projectId, 'design-drafts'],
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) =>
      planningApi.items(projectId, {
        status: 'DRAFT',
        cursor: pageParam,
        limit: 100,
      }),
    getNextPageParam: (page) => (page.has_more ? page.next_cursor : undefined),
    refetchInterval: (query) =>
      generationHash !== null &&
      query.state.data?.pages[0]?.draft_snapshot_hash === generationHash &&
      !query.state.error
        ? 1500
        : false,
  })
  const questions = useInfiniteQuery({
    queryKey: ['projects', projectId, 'planning-questions'],
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) =>
      planningApi.questions(projectId, { cursor: pageParam, limit: 100 }),
    getNextPageParam: (page) => (page.has_more ? page.next_cursor : undefined),
  })
  const items = drafts.data?.pages.flatMap((page) => page.items) ?? []
  const snapshotHash = drafts.data?.pages[0]?.draft_snapshot_hash
  const snapshotConsistent =
    !!snapshotHash &&
    drafts.data?.pages.every(
      (page) => page.draft_snapshot_hash === snapshotHash,
    )
  const allKinds = new Set(
    documents.data?.items
      .filter((document) => document.latest_draft)
      .map((document) => document.kind),
  )
  const analysesReady =
    versions.length > 0 &&
    analyses.every((query) => query.data?.status === 'COMPLETED')
  const canPublish =
    isLeader &&
    !baseline.data &&
    !baseline.isPending &&
    !baseline.error &&
    allKinds.size === 4 &&
    analysesReady &&
    items.length > 0 &&
    !drafts.hasNextPage &&
    snapshotConsistent &&
    !documents.data?.has_more
  const perform = (operation: () => Promise<unknown>, message: string) => {
    void action.run(operation, message).catch(() => undefined)
  }

  return (
    <section className="space-y-6">
      <header className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <p className="mb-2 text-xs font-semibold tracking-[.18em] text-primary">
            FROM IDEA TO DESIGN
          </p>
          <h2 className="text-2xl font-semibold tracking-tight">
            아이디어를 팀의 설계로
          </h2>
          <p className="mt-2 max-w-2xl text-sm leading-6 text-muted-foreground">
            문서에서 나온 요구사항과 비비의 질문을 검토하세요. 인간 팀장이
            확인한 문서와 항목을 팀의 첫 기준으로 발행해요.
          </p>
        </div>
        <Button variant="outline" onClick={() => openPane('documents')}>
          <FileCheck2 />
          공용 문서함
        </Button>
      </header>
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
      <AsyncState query={documents}>
        {(data) => (
          <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
            {(['PLAN', 'ERD', 'REQUIREMENTS', 'API_SPEC'] as const).map(
              (kind) => {
                const document = data.items.find((item) => item.kind === kind)
                const version = document?.latest_draft
                const analysis = version
                  ? analyses[
                      versions.findIndex((item) => item.id === version.id)
                    ]
                  : undefined
                return (
                  <Card key={kind}>
                    <CardHeader>
                      <CardTitle className="text-sm">
                        {statusLabel(kind)}
                      </CardTitle>
                      <CardDescription>
                        {document?.title ?? '문서를 등록해 주세요'}
                      </CardDescription>
                    </CardHeader>
                    <CardContent className="space-y-3">
                      <StatusPill
                        value={
                          analysis?.data?.status ??
                          (document?.approved_version ? 'APPROVED' : 'NOT_RUN')
                        }
                      />
                      {version && (
                        <p className="text-xs text-muted-foreground">
                          원본 v{version.version_no} · {version.file_name}
                        </p>
                      )}
                      {analysis?.error && (
                        <p role="alert" className="text-xs text-destructive">
                          분석 상태를 불러오지 못했어요.
                        </p>
                      )}
                      {analysis?.data?.message && (
                        <p className="text-xs text-muted-foreground">
                          {analysis.data.message}
                        </p>
                      )}
                      {analysis?.data?.status === 'FAILED' &&
                        isLeader &&
                        version && (
                          <Button
                            size="sm"
                            variant="outline"
                            disabled={action.busy}
                            onClick={() =>
                              perform(
                                () =>
                                  planningApi.retryAnalysis(
                                    projectId,
                                    document!.id,
                                    { document_version_id: version.id },
                                  ),
                                '분석 재시도를 접수했어요.',
                              )
                            }
                          >
                            <RefreshCw />
                            분석 재시도
                          </Button>
                        )}
                    </CardContent>
                  </Card>
                )
              },
            )}
          </div>
        )}
      </AsyncState>
      <div className="grid gap-6 lg:grid-cols-[.85fr_1.15fr]">
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <Lightbulb className="size-4 text-primary" />
              아직 정하지 않은 내용
            </CardTitle>
            <CardDescription>
              답변은 초안 생성의 참고 자료이며 설계 승인과 구분해요.
            </CardDescription>
          </CardHeader>
          <CardContent>
            <AsyncState query={questions}>
              {(data) => {
                const entries = data.pages.flatMap((page) => page.items)
                return (
                  <div className="space-y-3">
                    {entries.length ? (
                      entries.map((question) => (
                        <article
                          key={question.id}
                          className="rounded-lg border p-4"
                        >
                          <p className="text-sm leading-6">
                            {question.question}
                          </p>
                          {question.source && (
                            <p className="mt-2 text-xs text-muted-foreground">
                              원문 v{question.source.version_no} ·{' '}
                              {question.source.location}
                            </p>
                          )}
                          {question.answer && (
                            <p className="mt-3 rounded-md bg-muted/50 p-3 text-sm whitespace-pre-wrap">
                              {question.answer}
                            </p>
                          )}
                          {isLeader && (
                            <Button
                              className="mt-3"
                              size="sm"
                              variant="outline"
                              disabled={action.busy}
                              onClick={() => setAnswering(question)}
                            >
                              {question.answer ? '답변 수정' : '답변 작성'}
                            </Button>
                          )}
                        </article>
                      ))
                    ) : (
                      <EmptyState>현재 등록된 보완 질문이 없어요.</EmptyState>
                    )}
                    {questions.hasNextPage && (
                      <Button
                        variant="outline"
                        onClick={() => void questions.fetchNextPage()}
                        disabled={questions.isFetchingNextPage}
                      >
                        질문 더 보기
                      </Button>
                    )}
                  </div>
                )
              }}
            </AsyncState>
          </CardContent>
        </Card>
        <Card>
          <CardHeader>
            <div className="flex flex-wrap items-center justify-between gap-3">
              <CardTitle>검토할 설계 항목</CardTitle>
              {isLeader && (
                <div className="flex flex-wrap gap-2">
                  <Button
                    size="sm"
                    variant="outline"
                    onClick={() => setAdding(true)}
                  >
                    항목 추가
                  </Button>
                  <Button
                    size="sm"
                    disabled={action.busy || !analysesReady}
                    onClick={() =>
                      perform(async () => {
                        setGenerationHash(snapshotHash ?? '')
                        await planningApi.generateDrafts(projectId, {
                          document_version_ids: versions.map(
                            (version) => version.id,
                          ),
                        })
                      }, '초안 생성을 접수했어요. 생성된 항목을 확인해 주세요.')
                    }
                  >
                    <Sparkles />
                    답변으로 초안 만들기
                  </Button>
                </div>
              )}
            </div>
            <CardDescription>
              초안은 승인 전까지 프로젝트 정책으로 사용하지 않아요.
            </CardDescription>
          </CardHeader>
          <CardContent>
            <AsyncState query={drafts}>
              {() => (
                <div className="space-y-3">
                  {items.length ? (
                    items.map((item) => (
                      <article key={item.id} className="rounded-lg border p-4">
                        <div className="flex items-center justify-between gap-3">
                          <strong className="text-sm">{item.key}</strong>
                          <StatusPill value={item.status} />
                        </div>
                        <p className="mt-2 text-sm leading-6 break-words whitespace-pre-wrap">
                          {typeof item.content.description === 'string'
                            ? item.content.description
                            : JSON.stringify(item.content, null, 2)}
                        </p>
                        {item.source && (
                          <p className="mt-2 text-xs text-muted-foreground">
                            문서 v{item.source.version_no} ·{' '}
                            {item.source.location}
                          </p>
                        )}
                        {isLeader && (
                          <div className="mt-3 flex gap-2">
                            <Button
                              size="sm"
                              variant="outline"
                              disabled={action.busy || !item.content_hash}
                              onClick={() => setEditing(item)}
                            >
                              내용 수정
                            </Button>
                            <Button
                              size="sm"
                              variant="ghost"
                              disabled={action.busy || !item.content_hash}
                              onClick={() =>
                                perform(
                                  () =>
                                    planningApi.removeItem(
                                      item.design_item_id,
                                      {
                                        project_id: projectId,
                                        expected_version_id: item.id,
                                        expected_content_hash:
                                          item.content_hash!,
                                      },
                                    ),
                                  '발행 대상에서 제외했어요. 기록은 유지해요.',
                                )
                              }
                            >
                              발행 대상에서 제외
                            </Button>
                          </div>
                        )}
                      </article>
                    ))
                  ) : (
                    <EmptyState>
                      문서 분석을 완료하면 설계 항목 초안을 만들 수 있어요.
                    </EmptyState>
                  )}
                  {drafts.hasNextPage && (
                    <Button
                      variant="outline"
                      disabled={drafts.isFetchingNextPage}
                      onClick={() => void drafts.fetchNextPage()}
                    >
                      다음 항목 검토
                    </Button>
                  )}
                </div>
              )}
            </AsyncState>
          </CardContent>
        </Card>
      </div>
      <Card className="border-primary/20 bg-primary/5">
        <CardHeader>
          <CardTitle>팀의 첫 기준으로 발행</CardTitle>
          <CardDescription>
            {baseline.data
              ? `이미 기준 설계 v${baseline.data.version_no}이 발행되어 있어요. 이후 변경은 변경 제안에서 검토해 주세요.`
              : '필수 문서 4종의 분석을 완료하고 초안을 모두 검토한 뒤 승인할 수 있어요.'}
          </CardDescription>
        </CardHeader>
        <CardContent className="flex flex-wrap items-center gap-3">
          {baseline.error && (
            <p role="alert" className="text-sm text-destructive">
              현재 기준 설계를 확인하지 못했어요.
            </p>
          )}
          {baseline.data ? (
            <Button variant="outline" onClick={() => openPane('changes')}>
              변경 제안으로 이동
            </Button>
          ) : isLeader ? (
            <Button
              disabled={!canPublish || action.busy}
              onClick={() =>
                setPublishing({
                  hash: snapshotHash!,
                  documents: versions.map((version) => version.id),
                  items: items.map((item) => item.id),
                })
              }
            >
              검토한 설계 승인·발행
            </Button>
          ) : (
            <p className="text-sm text-muted-foreground">
              인간 팀장의 검토와 승인을 기다려 주세요.
            </p>
          )}
          {!baseline.data && !canPublish && isLeader && (
            <p className="text-xs text-muted-foreground">
              문서 4종·분석 완료·전체 항목 검토·최신 초안을 확인해 주세요.
            </p>
          )}
        </CardContent>
      </Card>
      {answering && (
        <FormDialog
          title="기획 보완 질문에 답변"
          description={answering.question}
          fields={[
            {
              name: 'answer',
              label: '답변',
              type: 'textarea',
              required: true,
              value: answering.answer,
            },
          ]}
          onClose={() => setAnswering(null)}
          onSubmit={async (values) => {
            await action.run(
              () =>
                planningApi.answerQuestion(projectId, answering.id, {
                  answer: values.answer,
                }),
              '답변을 저장했어요.',
            )
            setAnswering(null)
          }}
        />
      )}
      {editing && (
        <FormDialog
          title={`${editing.key} 초안 수정`}
          description="항목의 원문 구조를 유지해 내용을 수정하세요. 승인본에는 바로 반영되지 않아요."
          fields={[
            {
              name: 'content',
              label: '설계 내용',
              type: 'textarea',
              required: true,
              value: JSON.stringify(editing.content, null, 2),
            },
          ]}
          onClose={() => setEditing(null)}
          onSubmit={async (values) => {
            const content = structuredContentSchema.parse(
              JSON.parse(values.content),
            )
            await action.run(
              () =>
                planningApi.updateItem(editing.design_item_id, {
                  project_id: projectId,
                  expected_version_id: editing.id,
                  expected_content_hash: editing.content_hash!,
                  content,
                }),
              '초안을 수정했어요.',
            )
            setEditing(null)
          }}
        />
      )}
      {adding && (
        <FormDialog
          title="설계 항목 초안 추가"
          description="항목 종류와 원문 구조에 맞는 내용을 등록해 주세요."
          fields={[
            {
              name: 'kind',
              label: '항목 종류',
              type: 'select',
              value: 'REQUIREMENT',
              options: [
                { value: 'REQUIREMENT', label: '요구사항' },
                { value: 'API_CONTRACT', label: 'API 계약' },
                { value: 'DATA_CONTRACT', label: '데이터 계약' },
                { value: 'POLICY', label: '정책' },
                { value: 'CONSTRAINT', label: '제약' },
              ],
            },
            {
              name: 'schema_version',
              label: '내용 구조 버전',
              value: '1',
              required: true,
            },
            {
              name: 'content',
              label: '설계 내용',
              type: 'textarea',
              value: '{}',
              required: true,
            },
          ]}
          onClose={() => setAdding(false)}
          onSubmit={async (values) => {
            const content = structuredContentSchema.parse(
              JSON.parse(values.content),
            )
            await action.run(
              () =>
                planningApi.createItem(projectId, {
                  kind: values.kind as DesignItemVersion['kind'],
                  schema_version: Number(values.schema_version),
                  content,
                }),
              '초안을 추가했어요.',
            )
            setAdding(false)
          }}
        />
      )}
      {publishing && (
        <FormDialog
          title="최초 기준 설계 승인·발행"
          description={`문서 ${publishing.documents.length}개와 검토한 항목 ${publishing.items.length}개를 함께 승인해요. 승인할 원본·항목·초안이 달라지면 최신 내용을 다시 검토해야 해요.`}
          fields={[
            {
              name: 'confirmation',
              label: '검토를 마쳤다면 ‘승인’을 입력해 주세요.',
              required: true,
            },
          ]}
          submitLabel="승인하고 기준 발행"
          onClose={() => setPublishing(null)}
          onSubmit={async (values) => {
            if (
              values.confirmation !== '승인' ||
              snapshotHash !== publishing.hash ||
              publishing.documents.length !== versions.length ||
              !versions.every((version) =>
                publishing.documents.includes(version.id),
              ) ||
              !canPublish
            )
              throw new Error('최신 검토 내용 확인 필요')
            await action.run(
              () =>
                planningApi.publishBaseline({
                  project_id: projectId,
                  document_version_ids: publishing.documents,
                  design_item_version_ids: publishing.items,
                  draft_snapshot_hash: publishing.hash,
                }),
              '첫 기준 설계를 발행했어요.',
            )
            setPublishing(null)
          }}
        />
      )}
    </section>
  )
}
