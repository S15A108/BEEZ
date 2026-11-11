import { ACTIVE_POLL_MS } from '@/lib/pollIntervals'
import { useState } from 'react'
import { useInfiniteQuery, useQueries, useQuery } from '@tanstack/react-query'
import { FileCheck2, RefreshCw } from 'lucide-react'
import { documentsApi, planningApi } from '@/api/services'
import type { DesignItemVersion, DocumentVersion } from '@/api/contracts/common'
import { structuredContentSchema } from '@/api/contracts/common'
import { getApiErrorStatus, getValidationMessage } from '@/api/error'
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
import { DocumentCandidates } from './DocumentCandidates'
import { currentVersionJobs, publishNotice } from './draftJobs'
import { DraftCandidates } from './DraftCandidates'
import { TaskBreakdown } from './TaskBreakdown'
import { hasActiveJob } from './draftJobs'
import { useShowMore } from './useShowMore'
import {
  checkDesignContent,
  DESIGN_CONTENT_EXAMPLES,
  DESIGN_CONTENT_GUIDE,
  DESIGN_CONTENT_PLACEHOLDER,
  DESIGN_ITEM_SCHEMA_VERSION,
  parseDesignContent,
} from './parseDesignContent'
import { PlanningQuestions } from './PlanningQuestions'
import type { QuestionGenerationView } from './PlanningQuestions'
import {
  generationOf,
  isGenerating,
  isQuestionTarget,
  pollIntervalOf,
} from './questionGeneration'
import type { AnalysisResponse, BatchCandidatesResponse } from './schemas'
import { useProjectAction } from '@/features/workspace/useProjectAction'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import { statusLabel } from '@/lib/display'

// 발행 창을 연 뒤 검토 내용이 바뀌어 지금은 발행할 수 없을 때. 글자를 틀린 것이 아니므로 따로 알린다.
class StaleReviewError extends Error {}
const STALE_REVIEW_MESSAGE =
  '검토 후 내용이 바뀌었어요. 창을 닫고 최신 내용을 다시 검토해 주세요.'

const DRAFT_ACCEPTED =
  '초안 만들기를 접수했어요. 후보가 만들어지면 확인해 주세요.'

interface Props {
  projectId: string
  isLeader: boolean
}

export function PlanningPanel({ projectId, isLeader }: Props) {
  const action = useProjectAction(projectId)
  // 입력 창 안에서 난 실패는 창이 원인을 알려 주므로, 위쪽 일반 오류 띠가 같은 실패를 틀린 안내로 또 보이지 않게 지운다.
  const inForm = async <T,>(run: () => Promise<T>) => {
    try {
      return await run()
    } catch (cause) {
      action.reset()
      throw cause
    }
  }
  const [editing, setEditing] = useState<DesignItemVersion | null>(null)
  const [publishing, setPublishing] = useState<{
    hash: string
    documents: string[]
    items: string[]
  } | null>(null)
  const [adding, setAdding] = useState(false)
  const openPane = useWorkspaceStore((state) => state.openPane)
  const documents = useQuery({
    queryKey: ['projects', projectId, 'planning-documents'],
    queryFn: () => documentsApi.list(projectId, { limit: 100 }),
  })
  // 문서별 승인(DOC-A21)을 마친 문서는 그 승인본을, 아직이면 최신 초안을 기준 후보로 본다(dev 89b2c60).
  const versionOf = (document: {
    approved_version?: DocumentVersion
    latest_draft?: DocumentVersion
  }) => document.approved_version ?? document.latest_draft
  const versions =
    documents.data?.items.flatMap((document) => {
      const version = versionOf(document)
      return version ? [version] : []
    }) ?? []
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
  // 서버는 기준 설계가 없을 때만 질문을 만든다. 기준 조회 전에는 대상으로 보고 기다린다.
  const hasBaseline = !!baseline.data
  const analyses = useQueries({
    queries: versions.map((version) => ({
      queryKey: ['projects', projectId, 'analysis', version.id],
      queryFn: () =>
        planningApi.analysis(projectId, version.document_id, {
          document_version_id: version.id,
        }),
      refetchInterval: (query: {
        state: { data: AnalysisResponse | undefined }
      }) =>
        pollIntervalOf(
          query.state.data,
          isQuestionTarget(version, hasBaseline),
        ),
    })),
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
  })
  // 초안 생성 작업과 후보(PLN-A05). 작업이 도는 동안에는 끝날 때까지 다시 불러온다.
  const draftJobs = useQuery({
    queryKey: ['projects', projectId, 'design-draft-jobs'],
    queryFn: () => planningApi.drafts(projectId),
    refetchInterval: (query) =>
      !query.state.error && hasActiveJob(query.state.data?.jobs ?? [])
        ? ACTIVE_POLL_MS
        : false,
  })
  const items = drafts.data?.pages.flatMap((page) => page.items) ?? []
  const versionIds = versions.map((version) => version.id)
  // 설계 항목 초안은 10개씩 보인다. 발행 조건과 스냅샷 해시는 받아 둔 전체 초안을 기준으로 한다.
  const itemList = useShowMore(items, 10)
  const snapshotHash = drafts.data?.pages[0]?.draft_snapshot_hash
  const snapshotConsistent =
    !!snapshotHash &&
    drafts.data?.pages.every(
      (page) => page.draft_snapshot_hash === snapshotHash,
    )
  const allKinds = new Set(
    documents.data?.items
      .filter((document) => versionOf(document))
      .map((document) => document.kind),
  )
  // 서버는 문서 4종이 모두 팀장 승인을 받고 승인 전 작성 건이 없어야 기준을 만든다(409 document_not_approved).
  // 공용 문서함의 단계와 같은 기준(승인본이 있고 작성 건이 승인 상태)으로 보고, 아니면 발행을 끈다(PR #72 6차 조사 SIXTH-DOC-02).
  const copies = useQueries({
    queries: (documents.data?.items ?? []).map((document) => ({
      queryKey: ['projects', projectId, 'working-copy', document.id],
      queryFn: () => documentsApi.workingCopy(projectId, document.id),
      retry: false,
    })),
  })
  const checkingApproval = copies.some((query) => query.isPending)
  const unapproved = (documents.data?.items ?? []).filter(
    (document, index) =>
      !document.approved_version ||
      (!!copies[index]?.data && copies[index].data.status !== 'APPROVED'),
  )
  const analysesReady =
    versions.length > 0 &&
    analyses.every((query) => query.data?.status === 'COMPLETED')
  // 최초 기준 설계 생성(PLN-A10)이 프로젝트 시작이다. 서버 거절 코드(analysis_not_completed·document_not_approved·baseline_exists)는 승인 창이 알려 준다.
  // API 없음: 문서별 색인 완료(index_ready)가 dev 명세에 없어 분석 완료(analysesReady)로 대신 판단한다(PR #42 요청 중).
  const canPublish =
    isLeader &&
    !baseline.data &&
    !baseline.isPending &&
    !baseline.error &&
    allKinds.size === 4 &&
    !checkingApproval &&
    !unapproved.length &&
    analysesReady &&
    items.length > 0 &&
    !drafts.hasNextPage &&
    snapshotConsistent &&
    !documents.data?.has_more
  const labelOfVersion = (versionId: string) => {
    const version = versions.find((item) => item.id === versionId)
    const document = documents.data?.items.find(
      (item) => item.id === version?.document_id,
    )
    return document && version
      ? `${statusLabel(document.kind)} v${version.version_no}`
      : '문서'
  }
  // versionIds 가 없으면 모든 문서, 있으면 그 문서만 만든다. 문서마다 AI 를 한 번씩 부르므로(PLN-A05 작업이 문서마다 하나),
  // 실패한 문서 하나를 다시 만들 때 나머지까지 보내면 이미 잘 끝난 문서의 후보까지 다시 만들고 비용이 든다(S15P31A108-382).
  const generate = (versionIds?: string[]) =>
    perform(
      () =>
        planningApi.generateDrafts(projectId, {
          document_version_ids:
            versionIds ?? versions.map((version) => version.id),
        }),
      DRAFT_ACCEPTED,
    )
  // 자동으로 만들어지지 않은 문서(자동 생성이 꺼져 있거나 이 프로젝트가 대상이 아닐 때)는 팀장이 직접 처음 만든다.
  const createDocumentDrafts = (versionIds: string[]) =>
    perform(
      () =>
        planningApi.generateDrafts(projectId, {
          document_version_ids: versionIds,
          mode: 'DOCUMENT_ONLY',
        }),
      '문서에서 후보를 만들도록 접수했어요. 끝나면 여기에 나와요.',
    )
  // 문서에서 뽑은 후보 중 실패한 문서 하나의 실패한 부분만 다시 만든다(서버가 실패한 부분만 되돌려 돌린다).
  const retryDocumentDrafts = (versionId: string) =>
    perform(
      () =>
        planningApi.generateDrafts(projectId, {
          document_version_ids: [versionId],
          mode: 'DOCUMENT_ONLY',
        }),
      '실패한 부분만 다시 만들도록 접수했어요.',
    )
  const perform = (operation: () => Promise<unknown>, message: string) => {
    void action.run(operation, message).catch(() => undefined)
  }
  // 문서마다 따로 본다. 한 문서가 실패해도 다른 문서의 진행·완료는 계속 따라간다.
  const documentGenerations = versions.map((version, index) => ({
    version,
    generation: generationOf(
      analyses[index]?.data,
      isQuestionTarget(version, hasBaseline),
    ),
  }))
  const generations: QuestionGenerationView[] = documentGenerations.flatMap(
    ({ version, generation }) =>
      generation && (isGenerating(generation) || generation.status === 'FAILED')
        ? [
            {
              versionId: version.id,
              label: labelOfVersion(version.id),
              status: generation.status,
              errorCode: generation.errorCode,
              retryable: generation.retryable,
              onRetry: () =>
                perform(
                  () =>
                    planningApi.retryAnalysis(projectId, version.document_id, {
                      document_version_id: version.id,
                    }),
                  '보완 질문을 다시 만들도록 접수했어요.',
                ),
            },
          ]
        : [],
  )

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
      {isLeader && baseline.data && (
        <TaskBreakdown key={baseline.data.id} projectId={projectId} />
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
      <AsyncState query={documents}>
        {(data) => (
          <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
            {(['PLAN', 'ERD', 'REQUIREMENTS', 'API_SPEC'] as const).map(
              (kind) => {
                const document = data.items.find((item) => item.kind === kind)
                const version = document ? versionOf(document) : undefined
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
                          원본 v{version.version_no}
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
        <PlanningQuestions
          projectId={projectId}
          isLeader={isLeader}
          busy={action.busy}
          generations={generations}
          answerable={!baseline.isPending && !baseline.data}
          published={!!baseline.data}
          onAnswer={(id, input) =>
            action.run(
              () => planningApi.answerQuestion(projectId, id, input),
              input.status === 'DISMISSED'
                ? '질문을 답변 없이 닫았어요.'
                : '답변을 저장했어요.',
            )
          }
        />
        <div className="space-y-6">
          {!baseline.isPending && !baseline.data && (
            <AsyncState query={draftJobs}>
              {(data) => (
                <>
                  <DocumentCandidates
                    jobs={currentVersionJobs(data.jobs, versionIds)}
                    labelOfVersion={labelOfVersion}
                    isLeader={isLeader}
                    busy={action.busy}
                    onRetry={retryDocumentDrafts}
                    creatableVersionIds={
                      analysesReady
                        ? versions
                            .filter(
                              (version) =>
                                !data.jobs.some(
                                  (job) =>
                                    job.mode === 'DOCUMENT_ONLY' &&
                                    job.document_version_id === version.id,
                                ),
                            )
                            .map((version) => version.id)
                        : []
                    }
                    onCreate={createDocumentDrafts}
                    onAdopt={async (input) =>
                      (await action.run(
                        () => planningApi.adoptCandidates(projectId, input),
                        '고른 후보를 처리했어요.',
                      )) as BatchCandidatesResponse
                    }
                    onDiscard={async (input) =>
                      (await action.run(
                        () => planningApi.discardCandidates(projectId, input),
                        '고른 후보를 폐기 처리했어요.',
                      )) as BatchCandidatesResponse
                    }
                  />
                  <DraftCandidates
                    jobs={currentVersionJobs(data.jobs, versionIds)}
                    labelOfVersion={labelOfVersion}
                    isLeader={isLeader}
                    busy={action.busy}
                    canGenerate={analysesReady}
                    onGenerate={generate}
                    onAdopt={(candidate, input) =>
                      action.run(
                        () =>
                          planningApi.adoptCandidate(
                            projectId,
                            candidate.id,
                            input,
                          ),
                        '후보를 채택했어요. 설계 항목 초안에 반영했어요.',
                      )
                    }
                    onDiscard={(candidate) =>
                      action.run(
                        () =>
                          planningApi.discardCandidate(projectId, candidate.id),
                        '후보를 폐기했어요.',
                      )
                    }
                  />
                </>
              )}
            </AsyncState>
          )}
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
                      itemList.shown.map((item) => (
                        <article
                          key={item.id}
                          className="rounded-lg border p-4"
                        >
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
                    {(itemList.hidden > 0 || drafts.hasNextPage) && (
                      <Button
                        variant="outline"
                        disabled={drafts.isFetchingNextPage}
                        onClick={() => {
                          itemList.more()
                          if (itemList.hidden === 0 && drafts.hasNextPage)
                            void drafts.fetchNextPage()
                        }}
                      >
                        다음 항목 검토
                        {itemList.hidden > 0
                          ? ` (${itemList.hidden}개 남음)`
                          : ''}
                      </Button>
                    )}
                  </div>
                )}
              </AsyncState>
            </CardContent>
          </Card>
        </div>
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
          {!baseline.data && isLeader && unapproved.length > 0 && (
            <p
              role="status"
              className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground"
            >
              문서 4종이 모두 팀장 승인을 받아야 발행할 수 있어요. 승인 전:{' '}
              {unapproved.map((document) => document.title).join(', ')}
              <Button
                size="xs"
                variant="outline"
                onClick={() => openPane('documents')}
              >
                공용 문서함에서 승인하기
              </Button>
            </p>
          )}
          {!baseline.data && !canPublish && isLeader && !unapproved.length && (
            <p className="text-xs text-muted-foreground">
              문서 4종·분석 완료·전체 항목 검토·최신 초안을 확인해 주세요.
            </p>
          )}
        </CardContent>
      </Card>
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
              check: checkDesignContent,
              hint: DESIGN_CONTENT_GUIDE,
            },
          ]}
          errorMessage={getValidationMessage}
          onClose={() => setEditing(null)}
          onSubmit={async (values) => {
            const content = structuredContentSchema.parse(
              parseDesignContent(values.content),
            )
            await inForm(() =>
              action.run(
                () =>
                  planningApi.updateItem(editing.design_item_id, {
                    project_id: projectId,
                    expected_version_id: editing.id,
                    expected_content_hash: editing.content_hash!,
                    content,
                  }),
                '초안을 수정했어요.',
              ),
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
              name: 'content',
              label: '설계 내용',
              type: 'textarea',
              placeholder: DESIGN_CONTENT_PLACEHOLDER,
              required: true,
              check: checkDesignContent,
              hint: DESIGN_CONTENT_GUIDE,
            },
          ]}
          errorMessage={getValidationMessage}
          onClose={() => setAdding(false)}
          onSubmit={async (values) => {
            const content = structuredContentSchema.parse(
              parseDesignContent(values.content),
            )
            await inForm(() =>
              action.run(
                () =>
                  planningApi.createItem(projectId, {
                    kind: values.kind as DesignItemVersion['kind'],
                    schema_version: DESIGN_ITEM_SCHEMA_VERSION,
                    content,
                  }),
                '초안을 추가했어요.',
              ),
            )
            setAdding(false)
          }}
        >
          <pre className="rounded-lg bg-muted/50 p-3 text-xs break-all whitespace-pre-wrap text-muted-foreground">
            {DESIGN_CONTENT_EXAMPLES}
          </pre>
        </FormDialog>
      )}
      {publishing && (
        <FormDialog
          title="최초 기준 설계 승인·발행"
          description={`문서 ${publishing.documents.length}개와 검토한 항목 ${publishing.items.length}개를 함께 승인해요. 승인할 원본·항목·초안이 달라지면 최신 내용을 다시 검토해야 해요.${publishNotice(currentVersionJobs(draftJobs.data?.jobs ?? [], versionIds))}`}
          fields={[
            {
              name: 'confirmation',
              label: '검토를 마쳤다면 ‘승인’을 입력해 주세요.',
              required: true,
              check: (value) =>
                value.trim() === '승인' ? undefined : "'승인'을 입력해 주세요.",
            },
          ]}
          submitLabel="승인하고 기준 발행"
          errorMessage={(cause) =>
            cause instanceof StaleReviewError
              ? STALE_REVIEW_MESSAGE
              : getValidationMessage(cause)
          }
          onClose={() => setPublishing(null)}
          onSubmit={async () => {
            if (
              snapshotHash !== publishing.hash ||
              publishing.documents.length !== versions.length ||
              !versions.every((version) =>
                publishing.documents.includes(version.id),
              ) ||
              !canPublish
            )
              throw new StaleReviewError()
            await inForm(() =>
              action.run(
                () =>
                  planningApi.publishBaseline({
                    project_id: projectId,
                    document_version_ids: publishing.documents,
                    design_item_version_ids: publishing.items,
                    draft_snapshot_hash: publishing.hash,
                  }),
                '첫 기준 설계를 발행했어요.',
              ),
            )
            setPublishing(null)
          }}
        />
      )}
    </section>
  )
}
