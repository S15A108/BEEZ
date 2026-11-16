import { ACTIVE_POLL_MS } from '@/lib/pollIntervals'
import { useState } from 'react'
import type { ReactNode } from 'react'
import {
  useInfiniteQuery,
  useQueries,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query'
import {
  ChevronDown,
  Circle,
  CircleAlert,
  CircleCheck,
  CircleDashed,
  FileCheck2,
  ListChecks,
  LoaderCircle,
  RefreshCw,
} from 'lucide-react'
import { documentsApi, planningApi } from '@/api/services'
import type { DesignItemVersion, DocumentVersion } from '@/api/contracts/common'
import { structuredContentSchema } from '@/api/contracts/common'
import { getApiErrorStatus, getValidationMessage } from '@/api/error'
import { AsyncState } from '@/components/AsyncState'
import { FormDialog } from '@/components/FormDialog'
import { Button } from '@/components/ui/button'
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from '@/components/ui/popover'
import { useDocumentTitle } from '@/features/documents/useDocumentTitle'
import { cn } from '@/lib/utils'
import { DesignItemList } from './DesignItemList'
import { DocumentCandidates } from './DocumentCandidates'
import {
  answerJobs,
  candidateJobs,
  currentVersionJobs,
  documentJobs,
  hasActiveJob,
  isActiveJob,
  latestJobs,
  publishNotice,
} from './draftJobs'
import { DraftCandidates } from './DraftCandidates'
import { TaskBreakdown } from './TaskBreakdown'
import { sendInBatches } from './sendInBatches'
import { PANEL_HEIGHT, TAB_AREA_WITH_BREAKDOWN } from './splitLayout'
import { usePlanningQuestions } from './usePlanningQuestions'
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
import type {
  AnalysisResponse,
  AnswerQuestionInput,
  BatchCandidatesResponse,
  DraftJob,
} from './schemas'
import { useProjectAction } from '@/features/workspace/useProjectAction'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import { statusLabel } from '@/lib/display'

// 발행 창을 연 뒤 검토 내용이 바뀌어 지금은 발행할 수 없을 때. 글자를 틀린 것이 아니므로 따로 알린다.
class StaleReviewError extends Error {}
const STALE_REVIEW_MESSAGE =
  '검토 후 내용이 바뀌었어요. 창을 닫고 최신 내용을 다시 검토해 주세요.'

const DRAFT_ACCEPTED =
  '초안 만들기를 접수했어요. 후보가 만들어지면 확인해 주세요.'

// 설계 초안을 만드는 흐름 순서의 탭. 기준 설계를 발행한 뒤에는 후보 탭이 없다.
type PlanningTab = 'documents' | 'questions' | 'answers' | 'items'
const TAB_LABEL: Record<PlanningTab, string> = {
  documents: '문서 후보',
  questions: '보완 질문',
  answers: '답변 보완 후보',
  items: '설계 항목 초안',
}
type TabState = 'fail' | 'run' | undefined
const DOCUMENT_KINDS = ['PLAN', 'ERD', 'REQUIREMENTS', 'API_SPEC'] as const

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
  // 서버가 보완 질문을 만드는 기준 버전(QuestionBasisVersion)과 같은 규칙이다 — 바꾸면 두 곳을 함께 바꾼다(S15P31A108-396).
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
  // 발행 조건과 스냅샷 해시는 받아 둔 전체 초안을 기준으로 한다.
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
  const documentTitle = useDocumentTitle(projectId)
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
  // 보완 질문 자동 저장(PLN-A04). 저장하면 질문 목록만 새로 읽는다. 질문이 바뀌었거나(question_superseded)
  // 이전 본문 기준이 되어(question_outdated) 409로 거절되면 안내는 답변 칸이 하고, 목록을 새로 불러온다.
  const queryClient = useQueryClient()
  const refreshQuestions = () =>
    queryClient.invalidateQueries({
      queryKey: ['projects', projectId, 'planning-questions'],
    })
  const saveAnswer = async (id: string, input: AnswerQuestionInput) => {
    try {
      await planningApi.answerQuestion(projectId, id, input)
    } catch (cause) {
      if (getApiErrorStatus(cause) === 409) void refreshQuestions()
      throw cause
    }
    void refreshQuestions()
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

  // 탭에 붙는 개수와 상태. 다른 탭에 있어도 만드는 중·실패를 알 수 있게 한다.
  const [picked, setPicked] = useState<PlanningTab>()
  const generating = generations.some((item) => item.status !== 'FAILED')
  const questions = usePlanningQuestions(projectId, generating)
  const questionEntries =
    questions.data?.pages.flatMap((page) => page.items) ?? []
  const answeredCount = questionEntries.filter(
    (item) => !!item.answer && item.status !== 'DISMISSED',
  ).length
  const openCount = questionEntries.filter(
    (item) => !item.answer && item.status !== 'DISMISSED',
  ).length
  const jobs = currentVersionJobs(draftJobs.data?.jobs ?? [], versionIds)
  const documentWork = documentJobs(jobs)
  const answerWork = answerJobs(jobs)
  const pendingIn = (list: DraftJob[]) =>
    list
      .flatMap((job) => job.candidates)
      .filter((candidate) => candidate.status === 'PENDING').length
  const jobStateOf = (list: DraftJob[]): TabState =>
    list.some((job) => job.status === 'FAILED')
      ? 'fail'
      : list.some(isActiveJob)
        ? 'run'
        : undefined
  const tabInfo: Record<
    PlanningTab,
    { count: number | string; state: TabState }
  > = {
    documents: {
      count: pendingIn(documentWork),
      state: jobStateOf(documentWork),
    },
    questions: {
      count: questions.data?.pages[0]?.total_count ?? questionEntries.length,
      state: generations.some((item) => item.status === 'FAILED')
        ? 'fail'
        : generating
          ? 'run'
          : undefined,
    },
    answers: {
      count: pendingIn(candidateJobs(answerWork)),
      state: jobStateOf(latestJobs(answerWork)),
    },
    // 초안은 100개씩 받는다. 더 있으면 받아 둔 수에 +를 붙인다.
    items: {
      count: drafts.hasNextPage ? `${items.length}+` : items.length,
      state: undefined,
    },
  }
  const tabs: PlanningTab[] = baseline.data
    ? ['questions', 'items']
    : ['documents', 'questions', 'answers', 'items']
  // 처음 데이터가 들어왔을 때 한 번만 정한다: 문서 후보에 할 일(고를 후보·만드는 중·실패)이 있으면 문서 후보, 아니면 보완 질문.
  // 그 뒤에는 새 문서 후보 작업이 생기거나 마지막 후보를 채택해도 탭을 저절로 옮기지 않는다(답하던 중에 탭이 바뀌지 않게).
  const loaded =
    !baseline.isPending && (!!baseline.data || !draftJobs.isPending)
  if (!picked && loaded)
    setPicked(
      tabs.includes('documents') &&
        (pendingIn(documentWork) > 0 || tabInfo.documents.state)
        ? 'documents'
        : 'questions',
    )
  const tab = picked && tabs.includes(picked) ? picked : 'questions'
  const documentsReady =
    allKinds.size === 4 &&
    !checkingApproval &&
    !unapproved.length &&
    !documents.data?.has_more
  const checks: [boolean, string][] = [
    [documentsReady, '문서 4종 승인'],
    [analysesReady, '문서 분석 완료'],
    [
      items.length > 0,
      items.length
        ? `설계 항목 초안 ${items.length}개`
        : '설계 항목 초안 1개 이상',
    ],
  ]
  const doneChecks = checks.filter(([ok]) => ok).length
  const publishHint = canPublish
    ? publishNotice(jobs).trim()
    : doneChecks === checks.length
      ? '설계 항목 초안을 모두 불러오고 최신인지 확인한 뒤 발행할 수 있어요.'
      : ''
  const drafting = !baseline.isPending && !baseline.data
  // 발행 뒤 팀장 화면은 탭 위에 작업 만들기가 들어와 패널 높이를 고정하지 않는다.
  const breakdown = isLeader && !!baseline.data
  const panels: Record<PlanningTab, ReactNode> = {
    documents: drafting && (
      <AsyncState query={draftJobs}>
        {(data) => (
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
                // 서버는 한 번에 50개까지 받는다. 더 많이 고르면 나눠 보낸다.
                () =>
                  sendInBatches(input, (part) =>
                    planningApi.adoptCandidates(projectId, part),
                  ),
                '고른 후보를 처리했어요.',
              )) as BatchCandidatesResponse
            }
            onDiscard={async (input) =>
              (await action.run(
                () =>
                  sendInBatches(input, (part) =>
                    planningApi.discardCandidates(projectId, part),
                  ),
                '고른 후보를 폐기 처리했어요.',
              )) as BatchCandidatesResponse
            }
          />
        )}
      </AsyncState>
    ),
    questions: (
      <PlanningQuestions
        projectId={projectId}
        isLeader={isLeader}
        busy={action.busy}
        generations={generations}
        answerable={!baseline.isPending && !baseline.data}
        published={!!baseline.data}
        // 답변 입력이 실패 이유를 바로 아래에 알려 주므로 위쪽 일반 오류 띠는 띄우지 않는다.
        // 답은 자동 저장이라 저장할 때마다 위쪽 안내를 띄우거나 창 전체를 다시 읽지 않고 질문 목록만 새로 읽는다.
        onAnswer={(id, input) =>
          input.status === 'DISMISSED'
            ? inForm(() =>
                action.run(
                  () => planningApi.answerQuestion(projectId, id, input),
                  '질문을 답변 없이 닫았어요.',
                ),
              )
            : saveAnswer(id, input)
        }
      />
    ),
    answers: drafting && (
      <AsyncState query={draftJobs}>
        {(data) => (
          <DraftCandidates
            jobs={currentVersionJobs(data.jobs, versionIds)}
            labelOfVersion={labelOfVersion}
            titleOf={documentTitle}
            isLeader={isLeader}
            busy={action.busy}
            canGenerate={analysesReady}
            // 질문을 다 받았을 때만 센다. 덜 받은 수를 사실처럼 보이지 않는다.
            answerSummary={
              questionEntries.length && !questions.hasNextPage
                ? `지금 만들면 답한 질문 ${answeredCount}개를 반영해요. 남은 질문 ${openCount}개는 반영하지 않아요.`
                : undefined
            }
            onGenerate={generate}
            // 후보 상세가 실패 이유를 알려 주므로 위쪽 일반 오류 띠는 띄우지 않는다.
            onAdopt={(candidate, input) =>
              inForm(() =>
                action.run(
                  () =>
                    planningApi.adoptCandidate(projectId, candidate.id, input),
                  '후보를 채택했어요. 설계 항목 초안에 반영했어요.',
                ),
              )
            }
            onDiscard={(candidate) =>
              inForm(() =>
                action.run(
                  () => planningApi.discardCandidate(projectId, candidate.id),
                  '후보를 폐기했어요.',
                ),
              )
            }
          />
        )}
      </AsyncState>
    ),
    items: (
      <AsyncState query={drafts}>
        {() => (
          <DesignItemList
            items={items}
            hasNextPage={drafts.hasNextPage}
            loadingNext={drafts.isFetchingNextPage}
            loadNext={() => void drafts.fetchNextPage()}
            isLeader={isLeader}
            busy={action.busy}
            onAdd={() => setAdding(true)}
            onEdit={setEditing}
            onRemove={(item) =>
              perform(
                () =>
                  planningApi.removeItem(item.design_item_id, {
                    project_id: projectId,
                    expected_version_id: item.id,
                    expected_content_hash: item.content_hash!,
                  }),
                '발행 대상에서 제외했어요. 기록은 유지해요.',
              )
            }
          />
        )}
      </AsyncState>
    ),
  }

  return (
    // 창 높이를 채우고, 탭 안의 목록·상세가 남은 높이를 차지한다.
    <section className={cn('flex flex-col gap-4', !breakdown && PANEL_HEIGHT)}>
      {breakdown && baseline.data && (
        <TaskBreakdown key={baseline.data.id} projectId={projectId} />
      )}
      {action.message && (
        <p
          role="status"
          className="rounded-lg bg-primary/10 px-3.5 py-2 text-sm"
        >
          {action.message}
        </p>
      )}
      {action.error && (
        <p
          role="alert"
          className="rounded-lg bg-destructive/5 px-3.5 py-2 text-sm text-destructive"
        >
          {action.error}
        </p>
      )}
      <AsyncState query={documents}>
        {(data) => (
          <div className="flex flex-wrap items-center gap-x-5 gap-y-2 rounded-lg border bg-card/70 px-4 py-2 text-[13px]">
            <span className="font-semibold">문서 4종</span>
            <span aria-hidden className="h-4 w-px bg-border" />
            {DOCUMENT_KINDS.map((kind) => {
              const document = data.items.find((item) => item.kind === kind)
              const version = document ? versionOf(document) : undefined
              const analysis = version
                ? analyses[versions.findIndex((item) => item.id === version.id)]
                : undefined
              const status =
                analysis?.data?.status ??
                (document?.approved_version ? 'APPROVED' : 'NOT_RUN')
              const running = status === 'PENDING' || status === 'RUNNING'
              const Icon = !document
                ? CircleDashed
                : status === 'COMPLETED'
                  ? CircleCheck
                  : status === 'FAILED'
                    ? CircleAlert
                    : running
                      ? LoaderCircle
                      : Circle
              return (
                <span
                  key={kind}
                  title={
                    [document?.title, analysis?.data?.message]
                      .filter(Boolean)
                      .join(' · ') || undefined
                  }
                  className="flex items-center gap-1.5"
                >
                  <Icon
                    aria-hidden
                    className={cn(
                      'size-4',
                      !document || status === 'APPROVED' || status === 'NOT_RUN'
                        ? 'text-muted-foreground/70'
                        : status === 'COMPLETED'
                          ? 'text-[#00753a]'
                          : status === 'FAILED'
                            ? 'text-destructive'
                            : 'animate-spin text-amber-600 motion-reduce:animate-none',
                    )}
                  />
                  {statusLabel(kind)}
                  {version && (
                    <span className="text-muted-foreground">
                      v{version.version_no}
                    </span>
                  )}
                  {!document ? (
                    <span className="text-muted-foreground">등록 전</span>
                  ) : analysis?.error ? (
                    <span role="alert" className="text-destructive">
                      분석 상태를 불러오지 못했어요.
                    </span>
                  ) : (
                    status !== 'COMPLETED' && (
                      <span
                        className={
                          status === 'FAILED'
                            ? 'text-destructive'
                            : 'text-muted-foreground'
                        }
                      >
                        {statusLabel(status)}
                      </span>
                    )
                  )}
                  {status === 'FAILED' && isLeader && version && document && (
                    <Button
                      size="xs"
                      variant="outline"
                      disabled={action.busy}
                      onClick={() =>
                        perform(
                          () =>
                            planningApi.retryAnalysis(projectId, document.id, {
                              document_version_id: version.id,
                            }),
                          '분석 재시도를 접수했어요.',
                        )
                      }
                    >
                      <RefreshCw />
                      분석 재시도
                    </Button>
                  )}
                </span>
              )
            })}
            <Button
              size="sm"
              variant="outline"
              className="ml-auto"
              onClick={() => openPane('documents')}
            >
              <FileCheck2 />
              공용 문서함
            </Button>
          </div>
        )}
      </AsyncState>
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
      <div className="flex flex-wrap items-end gap-x-7 gap-y-2 border-b">
        <div
          role="tablist"
          aria-label="설계 초안 만들기 단계"
          className="flex items-end gap-7"
        >
          {tabs.map((key) => {
            const info = tabInfo[key]
            const on = key === tab
            return (
              <button
                key={key}
                type="button"
                role="tab"
                id={`planning-tab-${key}`}
                aria-controls={`planning-panel-${key}`}
                aria-selected={on}
                onClick={() => setPicked(key)}
                className={cn(
                  '-mb-px flex items-center gap-1.5 border-b-[3px] pt-1 pb-2.5 text-[15px] transition-colors focus-visible:ring-2 focus-visible:ring-ring/50 focus-visible:outline-none',
                  on
                    ? 'border-primary font-semibold text-foreground'
                    : 'border-transparent font-medium text-muted-foreground hover:text-foreground',
                )}
              >
                {TAB_LABEL[key]}
                <span
                  className={cn(
                    'text-[13px] tabular-nums',
                    on ? 'text-foreground/60' : 'text-muted-foreground/80',
                  )}
                >
                  {info.count}
                </span>
                {info.state && (
                  <>
                    <span
                      aria-hidden
                      className={cn(
                        'size-1.5 rounded-full',
                        info.state === 'fail'
                          ? 'bg-destructive'
                          : 'bg-amber-500',
                      )}
                    />
                    <span className="sr-only">
                      {info.state === 'fail' ? '실패 있음' : '만드는 중'}
                    </span>
                  </>
                )}
              </button>
            )
          })}
        </div>
        <div className="ml-auto flex flex-wrap items-center gap-2 pb-2">
          {baseline.error && (
            <p role="alert" className="text-sm text-destructive">
              현재 기준 설계를 확인하지 못했어요.
            </p>
          )}
          {baseline.data ? (
            <>
              <span className="text-sm text-muted-foreground">
                기준 설계 v{baseline.data.version_no}이 발행되어 있어요. 이후
                변경은 변경 제안에서 검토해 주세요.
              </span>
              <Button
                size="sm"
                variant="outline"
                onClick={() => openPane('changes')}
              >
                변경 제안으로 이동
              </Button>
            </>
          ) : isLeader ? (
            <>
              <Popover>
                <PopoverTrigger asChild>
                  <Button size="sm" variant="ghost">
                    <ListChecks />
                    발행 조건 {doneChecks}/{checks.length}
                    <ChevronDown />
                  </Button>
                </PopoverTrigger>
                <PopoverContent align="end" className="w-80 text-sm">
                  <p className="font-semibold">팀의 첫 기준으로 발행하려면</p>
                  <ul className="mt-2 grid gap-1.5">
                    {checks.map(([ok, label]) => (
                      <li
                        key={label}
                        className={cn(
                          'flex items-center gap-2',
                          !ok && 'text-muted-foreground',
                        )}
                      >
                        {ok ? (
                          <CircleCheck className="size-4 text-[#00753a]" />
                        ) : (
                          <Circle className="size-4 text-muted-foreground/60" />
                        )}
                        {label}
                      </li>
                    ))}
                  </ul>
                  {publishHint && (
                    <p className="mt-3 border-t pt-3 text-xs leading-5 text-muted-foreground">
                      {publishHint}
                    </p>
                  )}
                </PopoverContent>
              </Popover>
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
            </>
          ) : (
            <span className="text-sm text-muted-foreground">
              인간 팀장의 검토와 승인을 기다려 주세요.
            </span>
          )}
        </div>
      </div>
      {tabs.map((key) => (
        <div
          key={key}
          role="tabpanel"
          id={`planning-panel-${key}`}
          aria-labelledby={`planning-tab-${key}`}
          hidden={key !== tab}
          className={cn(
            'flex min-h-0 flex-col',
            breakdown ? TAB_AREA_WITH_BREAKDOWN : 'flex-1',
          )}
        >
          {panels[key]}
        </div>
      ))}
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
