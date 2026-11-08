import { useState } from 'react'
import type { ReactNode } from 'react'
import {
  useInfiniteQuery,
  useQueries,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query'
import {
  ArrowLeft,
  Check,
  ChevronDown,
  CircleAlert,
  FolderOpen,
  Search,
} from 'lucide-react'
import {
  changesApi,
  documentsApi,
  // meetingsApi, // '회의로 논의'(주석 처리 중)
  planningApi,
  tasksApi,
} from '@/api/services'
import type {
  ChangeImpact,
  ChangeProposal,
  Comparison,
} from '@/api/contracts/common'
import { getApiErrorCode } from '@/api/error'
import { readAllPages } from '@/api/readAllPages'
import { ActionForm } from '@/components/ActionForm'
import { AsyncState } from '@/components/AsyncState'
import { PaneHeaderAction } from '@/components/PaneHeaderAction'
import { EmptyState } from '@/components/EmptyState'
// import { FormDialog } from '@/components/FormDialog' // '회의로 논의'(주석 처리 중)
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { NativeSelect, NativeSelectOption } from '@/components/ui/native-select'
import { useSession } from '@/features/auth/useSession'
import { useProjectAction } from '@/features/workspace/useProjectAction'
import { formatDate } from '@/lib/formatDate'
import { describeContent, LIST_SELECTED } from '@/lib/display'
import { cn } from '@/lib/utils'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import { changedFields, foldDiff, lineDiff } from './lineDiff'
import { ProposalForm } from './ProposalForm'
import { ApprovalPreview } from './ApprovalPreview'
import { PublishedAnalysisStatus } from './PublishedAnalysisStatus'
import { ProposeFlow } from './ProposeFlow'
import { StatusTag } from './StatusTag'
import { Tag } from './Tag'
import { operationTone } from './tagTones'
import {
  isStaleDocument,
  kindLabel,
  operationLabel,
  toDocumentChanges,
} from './proposalItems'
import type { ProposalFormValues } from './proposalItems'
import { membersQuery } from '@/features/members/membersQuery'

interface Props {
  projectId: string
  isLeader: boolean
}
type ChangeStatus = ChangeProposal['status']
type Tab = 'overview' | 'diff' | 'impact' | 'comments'
type Mode =
  | { kind: 'detail' }
  | { kind: 'approve' }
  | { kind: 'reject' }
  | { kind: 'history' }
  | {
      kind: 'form'
      source?: ChangeProposal
      update?: boolean
      /** 공용 문서함 '고치기'로 들어오면 그 문서를 미리 고른다. */
      documentId?: string
    }

const statusTabs = [
  ['', '전체'],
  ['PROPOSED', '새 제안'],
  ['IN_REVIEW', '검토 중'],
  ['PUBLISHED', '발행됨'],
  ['REJECTED', '반려'],
] as const
const tabs = [
  ['overview', '개요'],
  ['diff', '바뀌는 내용'],
  ['impact', '영향'],
  ['comments', '의견'],
] as const
const statusOrder: Record<ChangeStatus, number> = {
  IN_REVIEW: 0,
  PROPOSED: 1,
  APPROVED: 2,
  PUBLISHED: 3,
  REJECTED: 4,
  WITHDRAWN: 5,
}

// 이전 결과가 보이는 동안 새 분석이 돌고 있으면(reanalysis) 이전 상태와 관계없이 분석 중이다(S15P31A108-241).
const isImpactRunning = (impact: ChangeImpact) =>
  ['PENDING', 'RUNNING'].includes(impact.analysis_status) || !!impact.reanalysis
// 분석 이력이 없는 제안. 서버는 오류가 아니라 200 NOT_EVALUATED(analysis_not_started)로 알린다.
const isImpactNotStarted = (impact: ChangeImpact) =>
  impact.analysis_status === 'NOT_EVALUATED' &&
  impact.error_code === 'analysis_not_started'
const hasCurrentImpact = (impact: ChangeImpact) =>
  impact.analysis_status === 'COMPLETED' &&
  impact.is_current !== false &&
  !impact.reanalysis
function impactMessage(impact: ChangeImpact, editable: boolean) {
  if (isImpactRunning(impact))
    return impact.reanalysis
      ? '다시 분석하는 중이에요. 이전 결과는 흐리게 보여요.'
      : '분석하는 중이에요.'
  if (isImpactNotStarted(impact))
    return editable
      ? '아직 영향 분석을 하지 않았어요. 승인하기 전에 분석을 시작해 주세요.'
      : '발행 전 영향 분석이 진행되지 않았어요.'
  if (impact.analysis_status === 'FAILED') return '분석하지 못했어요.'
  if (impact.analysis_status === 'INSUFFICIENT_EVIDENCE')
    return '근거가 부족해서 영향을 확정하지 못했어요.'
  if (impact.analysis_status === 'NOT_EVALUATED')
    return '영향을 평가하지 못했어요.'
  if (impact.analysis_status === 'STALE' || impact.is_current === false)
    return editable
      ? '제안이 바뀌어 이전 분석이 오래됐어요. 다시 분석해 주세요.'
      : '이전 영향 분석은 현재 상태와 달라졌어요.'
  return '확인된 관계와 AI 후보를 나눠 보여요.'
}

// 변경 제안 창은 올라온 제안을 모아 보고 검토하는 곳이다(사용자 결정 11-02). 사람이 쓰는 제안은 공용 문서함의
// 문서 '고치기'로 만들고, 에이전트·회의가 만든 설계 항목 변경은 보기만 한다.
// API 없음: 문서만 바꾸는 제안을 거르는 필터, 제목·항목 검색과 정렬 파라미터(백엔드 메모 1·2). 지금은 받은 목록을 화면에서 거른다.
export function ChangesPanel({ projectId, isLeader }: Props) {
  const session = useSession()
  // 'doc:문서ID'는 그 문서를 고른 문서 수정 제안 폼(공용 문서함 '고치기'·'변경 제안 쓰기'). 그 밖은 제안 ID.
  const focus = useWorkspaceStore((state) => state.focusId)
  const focusId = focus?.startsWith('doc:') ? null : focus
  const openPane = useWorkspaceStore((state) => state.openPane)
  const [selected, setSelected] = useState<{
    id: string
    focus: string | null
  } | null>(null)
  const [filter, setFilter] = useState<ChangeStatus | ''>('')
  const [search, setSearch] = useState('')
  const [sort, setSort] = useState<'recent' | 'status'>('recent')
  const [tab, setTab] = useState<Tab>('overview')
  const [mode, setMode] = useState<Mode>(() =>
    focus?.startsWith('doc:')
      ? { kind: 'form', documentId: focus.slice(4) || undefined }
      : { kind: 'detail' },
  )
  // const [discussing, setDiscussing] = useState(false) // '회의로 논의'(주석 처리 중)
  const [withdrawing, setWithdrawing] = useState(false)
  const action = useProjectAction(projectId)
  const queryClient = useQueryClient()
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
    refetchInterval: (query) =>
      ['PENDING', 'RUNNING'].includes(
        query.state.data?.published_analysis?.status ?? '',
      )
        ? 15_000
        : false,
  })
  // 승인 확인창의 문서 새 버전 번호는 제안이 만든 문서 버전(proposed_document_version_id)을 버전 이력에서 찾는다.
  // 철회된 제안의 초안도 번호를 차지하므로 승인본+1로 짐작하지 않는다(PR #72 3차 조사 THIRD-DOC-03).
  const proposedDocuments =
    mode.kind === 'approve' ? (detail.data?.proposal.documents ?? []) : []
  const documentVersions = useQueries({
    queries: proposedDocuments.map((change) => ({
      queryKey: [
        'projects',
        projectId,
        'document-versions',
        change.document_id,
      ],
      queryFn: () =>
        readAllPages((cursor) =>
          documentsApi.versions(projectId, change.document_id, {
            cursor,
            limit: 100,
          }),
        ),
    })),
  })
  const proposedVersionNo = (versionId: string) =>
    documentVersions
      .flatMap((query) => query.data?.items ?? [])
      .find((version) => version.id === versionId)?.version_no
  const impact = useQuery({
    queryKey: ['projects', projectId, 'impact', selectedId],
    queryFn: () => changesApi.impact(projectId, selectedId!),
    enabled: !!selectedId,
    refetchInterval: (query) =>
      query.state.data && isImpactRunning(query.state.data) ? 1500 : false,
  })
  // 승인 확인 화면에서만, 팀장만 읽는다(CHG-A14는 LEADER 전용). 제안이 바뀌면(리비전·해시) 다시 읽는다.
  // 키를 ['projects', id] 밖에 둔다: 승인에 성공하면 프로젝트 전체를 새로 고치는데, 그때 이미 발행된 제안의
  // 미리 보기를 다시 불러 409 가 나기 때문이다(실제 BE 점검에서 확인). 화면이 열릴 때마다 새로 읽으므로 갱신은 잃지 않는다.
  const preview = useQuery({
    queryKey: [
      'approval-preview',
      projectId,
      selectedId,
      detail.data?.proposal.revision,
      detail.data?.proposal.snapshot_hash,
    ],
    queryFn: () => changesApi.approvalPreview(projectId, selectedId!),
    enabled: mode.kind === 'approve' && isLeader && !!detail.data,
    // 계산 결과는 그 순간의 기준·분석 상태에 달려 있어 캐시를 믿지 않는다. 오류도 재시도하지 않고 바로 보인다.
    gcTime: 0,
    retry: false,
    // 분석이 도는 동안은 끝났는지 계속 읽는다. 읽지 않으면 분석이 끝나도 승인 버튼이 계속 막혀 있다.
    refetchInterval: (query) => {
      const analysis = query.state.data?.unreviewed.ai_analysis
      return analysis &&
        (analysis.reanalysis_in_progress || analysis.status === 'RUNNING')
        ? 1500
        : false
    },
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
  const baselines = useQuery({
    queryKey: ['projects', projectId, 'baselines'],
    // 오래된 제안의 기준 번호도 찾게 마지막 페이지까지 읽는다(PR #72 재전수 ROUND2-DOC-03).
    queryFn: () =>
      readAllPages((cursor) =>
        documentsApi.baselines(projectId, { cursor, limit: 100 }),
      ),
    // 승인 이력 화면, 발행한 뒤 '기준 vN 보기' 버튼, 기준이 바뀐 제안의 만든 기준 번호가 쓴다.
    enabled:
      mode.kind === 'history' ||
      detail.data?.proposal.status === 'PUBLISHED' ||
      !!detail.data?.proposal.requires_refresh,
  })
  const documents = useQuery({
    queryKey: ['projects', projectId, 'planning-documents'],
    queryFn: () => documentsApi.list(projectId, { limit: 100 }),
  })
  const documentOptions =
    documents.data?.items.flatMap((document) =>
      document.approved_version
        ? [
            {
              id: document.id,
              label: `${document.title} · 기준 v${document.approved_version.version_no}`,
              title: document.title,
              versionNo: document.approved_version.version_no,
              versionId: document.approved_version.id,
            },
          ]
        : [],
    ) ?? []
  const items = useQuery({
    queryKey: ['projects', projectId, 'approved-items'],
    queryFn: () =>
      planningApi.items(projectId, { status: 'APPROVED', limit: 100 }),
  })
  const tasks = useQuery({
    queryKey: ['projects', projectId, 'change-tasks'],
    queryFn: () => tasksApi.list(projectId, { limit: 100 }),
  })
  const members = useQuery(membersQuery(projectId))
  const approved = items.data?.items ?? []
  const taskList = tasks.data?.items ?? []
  const people = members.data?.items ?? []
  const proposal = detail.data?.proposal
  const editable =
    !!proposal && ['PROPOSED', 'IN_REVIEW'].includes(proposal.status)
  const mine = proposal?.proposer_account_id === session.data?.id
  const leaderName =
    members.data?.items.find((member) => member.role === 'LEADER')?.name ??
    '팀장'
  const nameOfAccount = (id?: string) =>
    people.find((member) => member.account_id === id)?.name ?? '팀원'
  const nameOfMember = (id?: string) =>
    people.find((member) => member.id === id)?.name ?? '담당자 확인 필요'
  const itemKey = (id?: string) =>
    approved.find((item) => item.design_item_id === id)?.key ?? '설계 항목'
  const taskKey = (id?: string) =>
    taskList.find((task) => task.id === id)?.key ?? '작업'
  const impactCount =
    !impact.error && impact.data && hasCurrentImpact(impact.data)
      ? impact.data.explicit.length + impact.data.candidates.length
      : undefined
  const commentList = comments.data?.pages.flatMap((page) => page.items) ?? []
  const found = entries.filter((entry) => {
    const query = search.trim()
    if (!query) return true
    // 목록은 본문 없는 요약(PR #46)이라 제목과 제안 키로 거른다. 서버 검색(q)은 195 미구현.
    return entry.title.includes(query) || entry.key.includes(query)
  })
  // 최신순은 서버 순서(제안 ID 내림차순 = 처음 올린 순서)를 그대로 쓴다. updated_at으로 다시
  // 정렬하면 승인·발행·반려 때마다 그 제안이 맨 위로 올라온다(사용자 요청 2026-11-03).
  // 상태순도 같은 상태 안에서는 이 순서를 지킨다(안정 정렬).
  const visible =
    sort === 'status'
      ? found.toSorted((a, b) => statusOrder[a.status] - statusOrder[b.status])
      : found
  const select = (id: string) => {
    setSelected({ id, focus: focusId })
    setTab('overview')
    setMode({ kind: 'detail' })
  }
  const publishedVersion = (id: string) =>
    baselines.data?.items.find((item) => item.id === id)?.version_no
  const currentVersionOf = (documentId: string) =>
    documentOptions.find((item) => item.id === documentId)?.versionId
  const perform = (operation: () => Promise<unknown>, message: string) =>
    action.run(operation, message).catch(() => undefined)
  // 영향 분석이 완료·최신이 아니면 서버가 409 impact_not_confirmed 로 막는다(S15P31A108-241). 그때 사유 입력을 열고,
  // 사유를 넣어 다시 보낸다. 같은 키로 입력만 바꾸면 idempotency_conflict 라 매 호출이 새 Idempotency-Key 를 쓴다.
  // 분석이 진행 중이면(analysis_running) 사유로도 우회할 수 없어 끝난 뒤 다시 승인하라고 알린다.
  const [reasonRequired, setReasonRequired] = useState(false)
  const [approveReason, setApproveReason] = useState('')
  const [approveNotice, setApproveNotice] = useState('')
  const openApprove = () => {
    // 같은 제안의 승인 확인을 다시 열 때 이전 미리 보기가 잠깐 보이고 버튼이 눌리지 않게 비워 둔다.
    queryClient.removeQueries({ queryKey: ['approval-preview', projectId] })
    setReasonRequired(false)
    setApproveReason('')
    setApproveNotice('')
    setMode({ kind: 'approve' })
  }
  // 미리 보기가 '사유가 필요하다'고 알려 주면 409를 기다리지 않고 처음부터 사유 칸을 연다. 그사이 상태가 바뀌어 409가 오면 아래 catch 가 연다.
  const overrideNeeded =
    reasonRequired || !!preview.data?.unreviewed.requires_override_reason
  const previewAnalysis = preview.data?.unreviewed.ai_analysis
  // 분석이 도는 중이면(첫 분석이든 재분석이든) 서버가 409 analysis_running 으로 막는다. 사유로도 우회할 수 없다.
  const analysisRunning =
    !!previewAnalysis &&
    (previewAnalysis.reanalysis_in_progress ||
      previewAnalysis.status === 'RUNNING')
  // 미리 보기가 이 409들로 막히면 승인(CHG-A09)도 같은 검사로 반드시 실패한다(PublishPlanner 공유). 버튼을 막고 이유를 알린다.
  // 그 밖의 실패(네트워크 등)는 미리 보기만 못 받은 것이라 승인은 막지 않는다. 실제 BE 점검(live)에서 확인했다.
  const previewBlockedMessage =
    {
      baseline_conflict:
        '제안을 올린 뒤 기준이 바뀌었어요. 목록을 새로고침해서 최신 기준으로 다시 확인해 주세요.',
      analysis_not_completed:
        '제안한 문서의 색인이 아직 준비되지 않았어요. 준비가 끝난 뒤 다시 확인해 주세요.',
      invalid_transition:
        '이미 처리했거나 지금은 승인할 수 없는 제안이에요. 목록을 새로고침해 주세요.',
      // 아래 둘은 제안 자체의 문제(스냅샷·참조 불일치, 항목 내용)라 다시 불러와도 풀리지 않는다. 승인도 같은 검사로 실패한다.
      version_conflict:
        '제안의 문서·항목 참조가 지금 기준과 맞지 않아요. 이 제안을 반려하고 제안자가 다시 올리게 해 주세요.',
      validation_failed:
        '제안의 항목 내용에 문제가 있어 승인할 수 없어요. 반려하고 제안자가 고쳐 다시 올리게 해 주세요.',
    }[getApiErrorCode(preview.error) ?? ''] ?? undefined
  const approveNow = async (proposal: ChangeProposal, nextVersion: number) => {
    setApproveNotice('')
    const reason = overrideNeeded ? approveReason.trim() : ''
    try {
      await action.run(
        () =>
          changesApi.approve(projectId, proposal.id, {
            expected_base_baseline_id: proposal.base_baseline_id,
            expected_revision: proposal.revision,
            expected_snapshot_hash: proposal.snapshot_hash,
            ...(reason ? { impact_override_reason: reason } : {}),
          }),
        `기준 v${nextVersion} 발행을 마쳤어요.`,
      )
      setMode({ kind: 'detail' })
    } catch (cause) {
      const code = getApiErrorCode(cause)
      // 두 코드는 실패가 아니라 승인 과정의 정상 단계라 일반 오류 띠를 함께 보이지 않는다.
      if (code === 'impact_not_confirmed') {
        action.reset()
        setReasonRequired(true)
        void preview.refetch()
      } else if (code === 'analysis_running') {
        action.reset()
        setApproveNotice(
          '영향 분석이 진행 중이에요. 끝난 뒤 다시 승인해 주세요.',
        )
        void preview.refetch()
      } else setMode({ kind: 'detail' })
    }
  }
  const submitForm = async (
    values: ProposalFormValues,
    source?: ChangeProposal,
    update?: boolean,
  ) => {
    const input = {
      base_baseline_id: baseline.data!.id,
      title: values.title,
      reason: values.reason,
      ...(values.task_id ? { task_id: values.task_id } : {}),
      // 관련 작업이 그대로면 원래 제안의 검증 연결도 그대로 보낸다. 서버 PUT은 빠진 필드를 비우므로 빠뜨리면 제목만
      // 고쳐도 연결이 끊긴다(PR #72 4차 조사 FOURTH-DOC-02). 작업을 바꾸거나 뺐으면 보내지 않는다.
      ...(source?.validation_id &&
      values.task_id &&
      values.task_id === source.task_id
        ? { validation_id: source.validation_id }
        : {}),
      // 거둔 제안을 다시 올리면 새 제안에 잇는다(PR #46 previous_proposal_id).
      ...(source?.status === 'WITHDRAWN' && !update
        ? { previous_proposal_id: source.id }
        : {}),
      // 설계 항목 변경은 에이전트·회의가 만든 것만 있고, 다시 낼 때 받은 그대로 보낸다.
      items: source?.items ?? [],
      // 폼은 문서 하나를 고치고, 원래 제안의 나머지 문서 변경(폼에서 지금 고치는 문서만 빼고)은 받은 값 그대로 보낸다.
      // 기준이 바뀐 문서는 그대로 낼 수 없어 빼고(폼에 안내), 고친 문서의 기준 버전은 화면이 보여 준 원문의 버전이다.
      documents: toDocumentChanges(
        values,
        values.base_version_id,
        source?.documents.filter(
          (change) => !isStaleDocument(change, currentVersionOf),
        ),
      ),
    }
    await action.run(
      async () => {
        if (update && source) {
          await changesApi.update(projectId, source.id, {
            ...input,
            expected_revision: source.revision,
            expected_snapshot_hash: source.snapshot_hash,
          })
          select(source.id)
        } else {
          const created = await changesApi.create(projectId, input)
          select(created.id)
        }
      },
      update
        ? '고친 내용을 올렸어요. 다시 검토 대기예요.'
        : '변경 제안을 올렸어요.',
    )
  }

  return (
    <section className="space-y-4">
      {/* 설명 문구는 캔버스 PNL-04-v2-changes(v461 이후)처럼 두지 않고, 제안하러 가는 버튼만 업무 창 머리 줄에 둔다.
          새 제안·승인·반려·이력 화면에서는 숨긴다. 누르면 창이 바뀌어 쓰던 내용을 잃는다(PR #88 리뷰). */}
      {mode.kind === 'detail' && (
        <PaneHeaderAction>
          <Button variant="outline" onClick={() => openPane('documents')}>
            <FolderOpen />
            공용 문서함에서 제안하기
          </Button>
        </PaneHeaderAction>
      )}
      {baseline.error && (
        <p className="rounded-lg bg-muted p-3 text-sm text-muted-foreground">
          아직 기준 설계가 없어요. 첫 기준을 발행한 뒤 변경을 제안할 수 있어요.
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
      {/* 새 제안·승인·반려·이력은 목록을 접고 창 안 전체 화면으로 연다(캔버스 수정안 '‹ 뒤로'). */}
      <div
        className={cn(
          'grid gap-5',
          mode.kind === 'detail' && 'lg:grid-cols-[290px_minmax(0,1fr)]',
        )}
      >
        <div
          hidden={mode.kind !== 'detail'}
          className="grid content-start gap-2.5"
        >
          <div className="flex gap-2">
            <label className="relative flex-1">
              <span className="sr-only">제안 검색</span>
              <Search className="absolute top-2 left-2.5 size-4 text-muted-foreground" />
              <Input
                className="pl-8"
                placeholder="제목·항목 번호"
                value={search}
                onChange={(event) => setSearch(event.target.value)}
              />
            </label>
            <NativeSelect
              aria-label="정렬"
              value={sort}
              onChange={(event) =>
                setSort(event.target.value as 'recent' | 'status')
              }
            >
              <NativeSelectOption value="recent">최신순</NativeSelectOption>
              <NativeSelectOption value="status">상태순</NativeSelectOption>
            </NativeSelect>
          </div>
          {/* 상태 필터는 캔버스 작업 현황·변경 제안처럼 회색 바탕의 칸 나눔 탭이다. */}
          <div
            role="tablist"
            aria-label="제안 상태"
            className="flex gap-0.5 rounded-[10px] bg-[#e6e9ee] p-[3px]"
          >
            {statusTabs.map(([value, label]) => (
              <button
                key={value}
                type="button"
                role="tab"
                aria-selected={filter === value}
                onClick={() => setFilter(value)}
                className={cn(
                  'h-[30px] flex-1 rounded-lg px-1 text-[13px] whitespace-nowrap',
                  filter === value
                    ? 'bg-white font-bold shadow-[0_1px_2px_rgba(0,0,0,0.08)]'
                    : 'font-medium text-[#57534e]',
                )}
              >
                {label}
              </button>
            ))}
          </div>
          <AsyncState query={changes}>
            {() =>
              visible.length ? (
                <ul className="grid gap-1.5">
                  {visible.map((entry) => (
                    <li key={entry.id}>
                      <button
                        type="button"
                        aria-current={selectedId === entry.id}
                        onClick={() => select(entry.id)}
                        className={cn(
                          'grid w-full gap-[3px] rounded-xl border px-3 py-2.5 text-left hover:bg-muted/40',
                          selectedId === entry.id && LIST_SELECTED,
                        )}
                      >
                        <span className="flex min-w-0 items-center gap-1.5">
                          <StatusTag value={entry.status} />
                          {baseline.data &&
                            entry.base_baseline_id !== baseline.data.id &&
                            ['PROPOSED', 'IN_REVIEW', 'APPROVED'].includes(
                              entry.status,
                            ) && (
                              <Tag tone={operationTone.RETIRE}>기준 바뀜</Tag>
                            )}
                        </span>
                        <strong className="line-clamp-2 text-sm leading-5 font-semibold">
                          {entry.title}
                        </strong>
                        <span className="text-xs leading-[17px] text-muted-foreground">
                          {entry.key} ·{' '}
                          {nameOfAccount(entry.proposer_account_id)}
                          {entry.proposer_account_id === session.data?.id &&
                            '(나)'}
                        </span>
                      </button>
                    </li>
                  ))}
                </ul>
              ) : (
                <EmptyState>
                  {entries.length
                    ? '찾는 제안이 없어요.'
                    : '아직 변경 제안이 없어요.'}
                </EmptyState>
              )
            }
          </AsyncState>
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
        <div className="min-w-0">
          {mode.kind === 'form' && !mode.source ? (
            <ProposeFlow
              projectId={projectId}
              key={documents.isSuccess ? 'documents' : 'loading'}
              requestDraft={(input) =>
                changesApi.generateDraft(projectId, input)
              }
              onDraftConflict={() => void documents.refetch()}
              documents={documentOptions}
              baselineVersion={baseline.data?.version_no}
              itemKinds={Object.fromEntries(
                approved.map((item) => [item.key, kindLabel[item.kind]]),
              )}
              tasks={taskList}
              // 문서 목록 응답에는 본문이 없어(백엔드 DOC-A02) 고른 문서의 기준 원문을 따로 받는다.
              fetchDocumentContent={(documentId) =>
                documentsApi.get(projectId, documentId).then((result) => ({
                  content: result.version.content ?? '',
                  versionId: result.version.id,
                }))
              }
              defaultValues={
                mode.documentId
                  ? {
                      title: '',
                      reason: '',
                      task_id: '',
                      document_id: mode.documentId,
                      // 기준 원문은 폼이 받아서 채운다.
                      after_content: '',
                    }
                  : undefined
              }
              onCancel={() => setMode({ kind: 'detail' })}
              onSubmit={(values) => submitForm(values)}
            />
          ) : mode.kind === 'form' ? (
            <ProposalForm
              projectId={projectId}
              key={documents.isSuccess ? 'documents' : 'loading'}
              documents={documentOptions}
              // 문서 목록 응답에는 본문이 없어(백엔드 DOC-A02) 고른 문서의 기준 원문을 따로 받는다.
              fetchDocumentContent={(documentId) =>
                documentsApi.get(projectId, documentId).then((result) => ({
                  content: result.version.content ?? '',
                  versionId: result.version.id,
                }))
              }
              requestDraft={(input) =>
                changesApi.generateDraft(projectId, input)
              }
              onDraftConflict={() => void documents.refetch()}
              sourceDocuments={mode.source?.documents.map((change) => ({
                id: change.document_id,
                title:
                  documentOptions.find((item) => item.id === change.document_id)
                    ?.title ?? '문서',
                afterContent: change.after_content,
                stale: isStaleDocument(change, currentVersionOf),
              }))}
              baseChangedTo={
                mode.source &&
                baseline.data &&
                mode.source.base_baseline_id !== baseline.data.id
                  ? baseline.data.version_no
                  : undefined
              }
              fixedItems={mode.source?.items.map((item) => ({
                label: `${operationLabel[item.operation]} · ${
                  item.operation === 'CREATE'
                    ? item.kind
                      ? kindLabel[item.kind]
                      : '설계 항목'
                    : itemKey(item.design_item_id)
                }`,
                detail:
                  item.operation === 'RETIRE'
                    ? undefined
                    : describeContent(item.proposed_content),
              }))}
              // 올린 제안(PROPOSED·IN_REVIEW)은 잠기고, 반려된 제안만 같은 번호로 고쳐 다시 올린다(CHG-A04, 사용자 결정 10-30).
              heading={
                mode.update
                  ? '고쳐서 다시 올리기'
                  : mode.source
                    ? '새 제안으로 다시 올리기'
                    : '문서 수정 제안'
              }
              status={mode.source && <StatusTag value={mode.source.status} />}
              description={
                mode.update
                  ? `반려 사유: ${mode.source?.rejection_reason ?? ''} · 반려된 내용을 채웠어요. 같은 제안 번호로 다시 올리면 검토 대기가 되고, 이전 내용과 반려 사유는 이력에 남아요.`
                  : mode.source
                    ? '거둔 제안 내용을 채웠어요. 올리면 새 제안이 되고 거둔 제안과 이어져요.'
                    : undefined
              }
              footnote={
                mode.update
                  ? '올리면 다시 검토 대기가 되고 영향 분석을 다시 해요. 이전 제출과 의견은 이력으로 남아요.'
                  : undefined
              }
              submitLabel={mode.update ? '다시 올리기' : '제안 올리기'}
              tasks={taskList}
              comments={
                mode.source &&
                commentList.map((comment) => ({
                  id: comment.id,
                  author: nameOfAccount(comment.author_account_id),
                  body: comment.body,
                }))
              }
              onShowComments={() => {
                setMode({ kind: 'detail' })
                setTab('comments')
              }}
              defaultValues={
                mode.source
                  ? {
                      title: mode.source.title,
                      reason: mode.source.reason,
                      task_id: mode.source.task_id ?? '',
                      document_id: mode.source.documents[0]?.document_id ?? '',
                      // 기준이 바뀐 문서는 옛 글 대신 새 기준 원문에서 다시 시작한다(옛 글은 폼에서 비교용으로 보여 준다).
                      after_content:
                        mode.source.documents[0] &&
                        !isStaleDocument(
                          mode.source.documents[0],
                          currentVersionOf,
                        )
                          ? mode.source.documents[0].after_content
                          : '',
                      kept_items: mode.source.items.length,
                    }
                  : mode.documentId
                    ? {
                        title: '',
                        reason: '',
                        task_id: '',
                        document_id: mode.documentId,
                        // 기준 원문은 폼이 받아서 채운다.
                        after_content: '',
                      }
                    : undefined
              }
              onCancel={() => setMode({ kind: 'detail' })}
              onSubmit={(values) =>
                submitForm(values, mode.source, mode.update)
              }
            />
          ) : !selectedId ? (
            <EmptyState>
              <strong className="text-base text-foreground">
                변경 제안이 없어요
              </strong>
              공용 문서함에서 문서를 열고 고치기로 제안해요.
              <Button size="sm" onClick={() => openPane('documents')}>
                공용 문서함 열기
              </Button>
            </EmptyState>
          ) : (
            <AsyncState query={detail}>
              {(data) => {
                const current = data.proposal
                const nextVersion = (baseline.data?.version_no ?? 0) + 1
                if (mode.kind === 'history')
                  return (
                    <div className="grid content-start gap-4">
                      <Back onClick={() => setMode({ kind: 'detail' })}>
                        {current.title}
                      </Back>
                      <h3 className="text-lg font-bold">승인 이력</h3>
                      <AsyncState query={approval}>
                        {(result) => (
                          <div className="grid gap-1 text-sm">
                            <p>
                              {nameOfAccount(result.approved_by)} 승인 ·{' '}
                              {formatDate(result.approved_at)}
                            </p>
                            {result.impact_override_reason && (
                              <p className="rounded-lg border border-dashed px-3 py-2 whitespace-pre-wrap">
                                <span className="font-semibold">
                                  영향 분석 없이 승인한 사유
                                </span>
                                {'\n'}
                                {result.impact_override_reason}
                              </p>
                            )}
                          </div>
                        )}
                      </AsyncState>
                      {/* API 없음: 이 제안이 지나온 길(올림 → 검토 시작 → 검토 요청 → 의견 → 승인)은 활동을 제안 ID로 거르는 기능이 필요(백엔드 메모 10). */}
                      <h4 className="text-sm font-semibold">기준 이력</h4>
                      <AsyncState query={baselines}>
                        {(result) => (
                          <ul className="grid gap-2">
                            {result.items.map((item) => (
                              <li
                                key={item.id}
                                className="rounded-xl border px-3.5 py-2.5 text-sm"
                              >
                                <strong>기준 v{item.version_no}</strong>
                                {item.id === baseline.data?.id &&
                                  ' · 지금 기준'}
                                {item.id === current.published_baseline_id &&
                                  ' · 이 제안'}
                                <span className="block text-xs text-muted-foreground">
                                  {formatDate(item.created_at)}
                                  {/* API 없음: 기준마다 그때의 문서와 항목은 기준 상세(DOC-A10)를 하나씩 불러야 해서 목록에는 날짜만 둔다. */}
                                </span>
                              </li>
                            ))}
                          </ul>
                        )}
                      </AsyncState>
                    </div>
                  )
                if (mode.kind === 'approve')
                  return (
                    <div className="grid content-start gap-4">
                      <Back onClick={() => setMode({ kind: 'detail' })}>
                        {current.title}
                      </Back>
                      <h3 className="text-lg font-bold">
                        기준 v{nextVersion} 발행을 승인할까요?
                      </h3>
                      <p className="text-sm font-semibold">
                        기준 v{nextVersion}에 들어가는 것
                      </p>
                      {current.items.length > 0 && (
                        <p className="text-xs text-muted-foreground">
                          설계 항목
                        </p>
                      )}
                      <ul className="grid gap-1.5 text-sm">
                        {current.items.map((item, index) => (
                          <li
                            key={index}
                            className="rounded-lg border px-3 py-2"
                          >
                            {operationLabel[item.operation]} ·{' '}
                            {item.operation === 'CREATE'
                              ? '새 항목'
                              : `${itemKey(item.design_item_id)} 승인 v${approved.find((entry) => entry.design_item_id === item.design_item_id)?.version_no ?? '?'}`}
                            {item.operation === 'UPDATE' && ' → 새 버전'}
                          </li>
                        ))}
                      </ul>
                      {current.documents.length > 0 && (
                        <>
                          <p className="text-xs text-muted-foreground">문서</p>
                          <ul className="grid gap-1.5 text-sm">
                            {current.documents.map((change) => {
                              const document = documentOptions.find(
                                (item) => item.id === change.document_id,
                              )
                              const next = proposedVersionNo(
                                change.proposed_document_version_id,
                              )
                              const after = next ? `v${next}` : '새 버전'
                              return (
                                <li
                                  key={change.document_id}
                                  className="rounded-lg border px-3 py-2"
                                >
                                  {document
                                    ? `${document.title} v${document.versionNo} → ${after}`
                                    : `문서 → ${after}`}
                                </li>
                              )
                            })}
                          </ul>
                        </>
                      )}
                      {/* 미리 보기가 있으면 실제로 바뀌는 검증·작업을 보여 주므로 일반 안내는 미리 보기를 못 받았을 때만 둔다. */}
                      {!preview.data && (
                        <p className="rounded-lg bg-muted/50 p-3 text-sm leading-6">
                          승인하면 이 항목을 쓰는 검증은 &apos;다시 검증
                          필요&apos;, 진행 중 작업은 &apos;대기 ·
                          재검증&apos;으로 바뀌어요. 문서 원문도 함께 새 기준이
                          돼요.
                        </p>
                      )}
                      {preview.isLoading && (
                        <p
                          role="status"
                          className="text-sm text-muted-foreground"
                        >
                          승인하면 같이 바뀌는 것을 계산하는 중이에요…
                        </p>
                      )}
                      {preview.data && (
                        <ApprovalPreview
                          preview={preview.data}
                          changedDocuments={current.documents.length}
                          nameOfMember={nameOfMember}
                        />
                      )}
                      {preview.error && (
                        <div
                          role="alert"
                          className="grid gap-2 rounded-lg bg-destructive/5 p-3 text-sm text-destructive"
                        >
                          <p>
                            {previewBlockedMessage ??
                              '승인하면 같이 바뀌는 것을 불러오지 못했어요. 영향을 직접 확인한 뒤 승인해 주세요.'}
                          </p>
                          <Button
                            variant="outline"
                            size="sm"
                            className="justify-self-start"
                            onClick={() => void preview.refetch()}
                          >
                            다시 불러오기
                          </Button>
                        </div>
                      )}
                      {approveNotice && (
                        <p
                          role="alert"
                          className="rounded-lg bg-destructive/5 p-3 text-sm text-destructive"
                        >
                          {approveNotice}
                        </p>
                      )}
                      {overrideNeeded && (
                        <div className="grid gap-2 rounded-lg border border-amber-300 bg-amber-50 p-3">
                          <strong className="text-sm">
                            AI 영향 분석이 끝나지 않았어요
                          </strong>
                          <p className="text-sm leading-6">
                            이 제안의 영향 분석이 끝나지 않았거나(실패·근거
                            부족·아직 안 돌림·제안이 바뀜·문서 변경이라 분석 못
                            함) 믿을 수 없는 상태예요. 분석하지 못한 결과는 영향
                            0건으로 보지 않아요. 영향 범위를 직접 확인했다면
                            사유를 남기고 승인할 수 있어요. 사유는 승인 이력에
                            남아요.
                          </p>
                          <Button
                            size="sm"
                            variant="outline"
                            className="justify-self-start"
                            disabled={action.busy}
                            onClick={() =>
                              void perform(
                                () =>
                                  changesApi.retryImpact(projectId, current.id),
                                '다시 분석을 요청했어요.',
                              )
                            }
                          >
                            영향 다시 분석
                          </Button>
                          <label
                            htmlFor="impact-override-reason"
                            className="text-sm font-semibold"
                          >
                            영향 분석 없이 승인하는 사유
                          </label>
                          <Textarea
                            id="impact-override-reason"
                            maxLength={1000}
                            value={approveReason}
                            onChange={(event) =>
                              setApproveReason(event.target.value)
                            }
                          />
                        </div>
                      )}
                      <div className="flex justify-end gap-2">
                        <Button
                          variant="outline"
                          onClick={() => setMode({ kind: 'detail' })}
                        >
                          취소
                        </Button>
                        <Button
                          disabled={
                            action.busy ||
                            preview.isFetching ||
                            !!previewBlockedMessage ||
                            analysisRunning ||
                            (overrideNeeded && !approveReason.trim())
                          }
                          onClick={() => void approveNow(current, nextVersion)}
                        >
                          {overrideNeeded
                            ? `사유 남기고 기준 v${nextVersion} 발행`
                            : `승인하고 기준 v${nextVersion} 발행`}
                        </Button>
                      </div>
                    </div>
                  )
                if (mode.kind === 'reject')
                  return (
                    <div className="grid content-start gap-4">
                      <Back onClick={() => setMode({ kind: 'detail' })}>
                        {current.title}
                      </Back>
                      <h3 className="text-lg font-bold">제안을 반려할까요?</h3>
                      <p className="text-sm leading-6 text-muted-foreground">
                        반려하면 제안자가 같은 제안을 고쳐 다시 올릴 수 있어요.
                        제안자와 검토 요청을 보낸 팀원에게 알려요.
                      </p>
                      <ActionForm
                        fields={[
                          {
                            name: 'reason',
                            label: '반려 사유',
                            type: 'textarea',
                            required: true,
                            max: 2000,
                            // 기준이 바뀐 제안은 돌려보내는 이유를 채워 둔다(PNL-04 수정안 기준 바뀜).
                            value: current.requires_refresh
                              ? '기준이 바뀌었어요. 최신 기준으로 고쳐 다시 올려 주세요.'
                              : undefined,
                          },
                        ]}
                        submitLabel="반려하기"
                        destructive
                        onSubmit={async (values) => {
                          await action.run(
                            () =>
                              changesApi.reject(projectId, current.id, {
                                reason: values.reason,
                                expected_revision: current.revision,
                                expected_snapshot_hash: current.snapshot_hash,
                              }),
                            '제안을 반려했어요.',
                          )
                          setMode({ kind: 'detail' })
                        }}
                      />
                    </div>
                  )
                const baseVersion = current.requires_refresh
                  ? publishedVersion(current.base_baseline_id)
                  : baseline.data?.version_no
                const changedKeys = data.comparisons
                  .filter(
                    (comparison) =>
                      comparison.current_changed &&
                      comparison.target_type === 'DESIGN_ITEM',
                  )
                  .map((comparison) => itemKey(comparison.target_id ?? ''))
                return (
                  <div className="grid content-start gap-4">
                    {current.requires_refresh && editable && (
                      <div className="flex items-center gap-3 rounded-[14px] bg-[#fdf8e6] px-3.5 py-3">
                        <span className="grid flex-1 gap-0.5">
                          <strong className="flex items-center gap-1.5 text-sm">
                            <CircleAlert className="size-4 shrink-0" />
                            기준이 바뀌었어요
                            {baseline.data &&
                              ` · 지금 기준 v${baseline.data.version_no}`}
                          </strong>
                          <span className="text-[13px] leading-[19px] text-muted-foreground">
                            이 제안은{' '}
                            {baseVersion ? `v${baseVersion}` : '이전 기준'}
                            에서 만들어서 승인할 수 없어요. 팀장이 기준이
                            바뀌었다는 이유로 반려하면, 같은 번호에서 최신
                            기준으로 고쳐 다시 올려요.
                            {changedKeys.length > 0 &&
                              ` 이미 바뀐 항목: ${changedKeys.join(', ')}`}
                          </span>
                        </span>
                        <Button
                          variant="outline"
                          className="shrink-0"
                          onClick={() => setTab('diff')}
                        >
                          바뀐 항목 보기
                        </Button>
                      </div>
                    )}
                    <div>
                      <h3 className="flex flex-wrap items-center gap-2 text-xl leading-7 font-bold">
                        {current.title}
                        <StatusTag value={current.status} />
                        {current.requires_refresh && editable && (
                          <Tag tone={operationTone.RETIRE}>기준 바뀜</Tag>
                        )}
                      </h3>
                      <p className="text-xs text-muted-foreground">
                        {current.key} ·{' '}
                        {nameOfAccount(current.proposer_account_id)} 제안 · r
                        {current.revision} · {formatDate(current.created_at)}
                        {/* API 없음: 회의·작업 중 어디서 올렸는지 출처, 에이전트 제안이면 에이전트 이름(백엔드 메모 3·7) */}
                      </p>
                    </div>
                    {mine && editable && !isLeader && (
                      <div className="grid gap-1 rounded-[14px] bg-[#eef4fb] px-3.5 py-3 text-[13px] leading-5">
                        <strong className="text-sm text-[#1d4f91]">
                          {current.status === 'IN_REVIEW'
                            ? `${leaderName}님이 검토하고 있어요`
                            : `${leaderName}님의 검토를 기다리고 있어요`}
                        </strong>
                        <span>
                          승인과 반려는 팀장이 해요. 결과가 나오면 알림으로 알려
                          드려요. 올린 제안은 고칠 수 없어요. 반려되면 같은
                          제안을 고쳐 다시 올려요.
                        </span>
                      </div>
                    )}
                    {current.status === 'REJECTED' && (
                      <div className="grid gap-2 rounded-xl border border-destructive/40 bg-destructive/5 p-4">
                        <p className="text-sm font-semibold">반려 사유</p>
                        <p className="text-base leading-7">
                          {current.rejection_reason}
                        </p>
                        {mine && (
                          <Button
                            variant="outline"
                            className="justify-self-start"
                            onClick={() =>
                              setMode({
                                kind: 'form',
                                source: current,
                                update: true,
                              })
                            }
                          >
                            고쳐서 다시 올리기
                          </Button>
                        )}
                      </div>
                    )}
                    {current.status === 'WITHDRAWN' && (
                      <div className="grid gap-2 rounded-xl border bg-muted/40 p-4 text-sm">
                        <p>
                          {nameOfAccount(current.withdrawn_by)}님이 거둔
                          제안이에요
                          {current.withdrawn_at &&
                            ` · ${formatDate(current.withdrawn_at)}`}
                          . 거둔 제안은 고칠 수 없어요.
                        </p>
                        {mine && (
                          <Button
                            variant="outline"
                            className="justify-self-start"
                            onClick={() =>
                              setMode({ kind: 'form', source: current })
                            }
                          >
                            새 제안으로 다시 올리기
                          </Button>
                        )}
                      </div>
                    )}
                    {current.status === 'PUBLISHED' && (
                      <AsyncState query={approval}>
                        {(result) => (
                          <div className="grid gap-2 rounded-xl border border-primary/40 bg-primary/10 p-4 text-sm">
                            <p className="font-semibold">
                              새 기준을 발행했어요 ·{' '}
                              {nameOfAccount(result.approved_by)} 승인 ·{' '}
                              {formatDate(result.approved_at)}
                            </p>
                            {result.impact_override_reason && (
                              <p className="whitespace-pre-wrap">
                                <span className="font-semibold">
                                  영향 분석 없이 승인한 사유
                                </span>
                                {'\n'}
                                {result.impact_override_reason}
                              </p>
                            )}
                            <p className="text-muted-foreground">
                              이 승인으로 상태가 바뀐 검증과 작업은 작업
                              현황에서 확인하세요.
                            </p>
                            <div className="flex flex-wrap gap-2">
                              {/* 이 제안이 발행한 기준 버전의 문서를 연다. 지금 승인본은 그 뒤 기준에서 바뀌었을 수 있다(ROUND2-DOC-02). */}
                              <Button
                                size="sm"
                                variant="outline"
                                onClick={() =>
                                  openPane(
                                    'documents',
                                    publishedVersion(result.baseline_id)
                                      ? `baseline:${publishedVersion(result.baseline_id)}`
                                      : null,
                                  )
                                }
                              >
                                {publishedVersion(result.baseline_id)
                                  ? `기준 v${publishedVersion(result.baseline_id)} 보기`
                                  : '기준 보기'}
                              </Button>
                              <Button
                                size="sm"
                                variant="outline"
                                onClick={() => setMode({ kind: 'history' })}
                              >
                                승인 이력
                              </Button>
                              <Button
                                size="sm"
                                variant="outline"
                                onClick={() => openPane('tasks')}
                              >
                                작업 현황
                              </Button>
                            </div>
                          </div>
                        )}
                      </AsyncState>
                    )}
                    {current.status === 'PUBLISHED' && (
                      <PublishedAnalysisStatus
                        analysis={data.published_analysis}
                        observedAt={detail.dataUpdatedAt}
                        refreshing={detail.isFetching}
                        onRefresh={() => void detail.refetch()}
                      />
                    )}
                    <div
                      role="tablist"
                      aria-label="제안 보기"
                      className="flex gap-1 border-b"
                    >
                      {tabs.map(([value, label]) => (
                        <button
                          key={value}
                          type="button"
                          role="tab"
                          aria-selected={tab === value}
                          onClick={() => setTab(value)}
                          className={cn(
                            '-mb-px border-b-2 px-3 py-2 text-sm',
                            tab === value
                              ? 'border-foreground font-bold'
                              : 'border-transparent font-medium text-muted-foreground',
                          )}
                        >
                          {label}
                          {value === 'impact' &&
                            impactCount !== undefined &&
                            ` ${impactCount}`}
                          {value === 'comments' && ` ${commentList.length}`}
                        </button>
                      ))}
                    </div>
                    <div role="tabpanel" className="min-h-40">
                      {tab === 'overview' && (
                        <div className="grid gap-3 text-sm">
                          <p className="leading-[21px]">{current.reason}</p>
                          {/* 캔버스 PNL-04-v2 개요: 모서리 14px·안쪽 16/18px 카드, 15px·700 머리와 노란 글자 이동 버튼 */}
                          <section className={overviewCard}>
                            <h4 className={overviewHead}>
                              바뀌는 내용 {current.items.length}
                              <span className="flex items-center gap-2 font-medium">
                                <span className="text-xs text-muted-foreground">
                                  {(['UPDATE', 'CREATE', 'RETIRE'] as const)
                                    .map(
                                      (operation) =>
                                        [
                                          operation,
                                          current.items.filter(
                                            (item) =>
                                              item.operation === operation,
                                          ).length,
                                        ] as const,
                                    )
                                    .filter(([, count]) => count)
                                    .map(
                                      ([operation, count]) =>
                                        `${operationLabel[operation]} ${count}`,
                                    )
                                    .join(' · ')}
                                </span>
                                <button
                                  type="button"
                                  className={overviewLink}
                                  onClick={() => setTab('diff')}
                                >
                                  자세히
                                </button>
                              </span>
                            </h4>
                            <ul>
                              {current.items.map((item, index) => (
                                <li
                                  key={index}
                                  className="grid grid-cols-[auto_64px_minmax(0,1fr)] items-center gap-2 border-b py-[7px] text-[13px] last:border-0"
                                >
                                  <Tag tone={operationTone[item.operation]}>
                                    {operationLabel[item.operation]}
                                  </Tag>
                                  <strong className="truncate font-semibold">
                                    {item.operation === 'CREATE'
                                      ? item.kind
                                        ? kindLabel[item.kind]
                                        : '설계 항목'
                                      : itemKey(item.design_item_id)}
                                  </strong>
                                  <span className="truncate">
                                    {item.proposed_content
                                      ? describeContent(item.proposed_content)
                                      : ''}
                                  </span>
                                </li>
                              ))}
                              {current.documents.length > 0 && (
                                <li className="py-[7px] text-[13px] text-muted-foreground">
                                  문서 변경 {current.documents.length}건은 공용
                                  문서함 승인 흐름에서 다뤄요.
                                </li>
                              )}
                            </ul>
                          </section>
                          <div className="grid gap-3 sm:grid-cols-2">
                            <section className={overviewCard}>
                              <h4 className={overviewHead}>
                                영향
                                <button
                                  type="button"
                                  aria-label="영향 보기"
                                  className={overviewLink}
                                  onClick={() => setTab('impact')}
                                >
                                  보기
                                </button>
                              </h4>
                              <p className="text-[13px] leading-[19px]">
                                {impact.error
                                  ? '영향 분석 상태를 불러오지 못했어요.'
                                  : impact.data && hasCurrentImpact(impact.data)
                                    ? `확인된 관계 ${impact.data.explicit.length} · AI 후보 ${impact.data.candidates.length}`
                                    : impact.data
                                      ? impactMessage(impact.data, editable)
                                      : '영향 분석 상태를 확인하는 중이에요.'}
                              </p>
                            </section>
                            <section className={overviewCard}>
                              <h4 className={overviewHead}>
                                의견
                                <button
                                  type="button"
                                  aria-label="의견 보기"
                                  className={overviewLink}
                                  onClick={() => setTab('comments')}
                                >
                                  보기
                                </button>
                              </h4>
                              <div className="grid gap-1 text-[13px] leading-[19px]">
                                <span>의견 {commentList.length}</span>
                                {commentList[0] && (
                                  <span className="truncate text-xs leading-[18px] text-muted-foreground">
                                    {nameOfAccount(
                                      commentList[0].author_account_id,
                                    )}
                                    : {commentList[0].body}
                                  </span>
                                )}
                              </div>
                            </section>
                          </div>
                          {current.task_id && (
                            <p className="text-xs text-muted-foreground">
                              관련 작업 {taskKey(current.task_id)}
                            </p>
                          )}
                        </div>
                      )}
                      {tab === 'diff' && (
                        <div className="grid gap-2">
                          <p
                            className={cn(
                              'flex items-center gap-2 rounded-[10px] px-3 py-2 text-[13px]',
                              current.requires_refresh
                                ? 'bg-[#fdf8e6] text-[#6e5200]'
                                : 'bg-[#f3f8f4] text-[#00532a]',
                            )}
                          >
                            {current.requires_refresh ? (
                              <CircleAlert className="size-4 shrink-0" />
                            ) : (
                              <Check className="size-4 shrink-0" />
                            )}
                            제안을 만든 기준
                            {baseVersion ? `(v${baseVersion})` : ''}과 지금
                            기준이{' '}
                            {current.requires_refresh ? '달라요' : '같아요'}.
                          </p>
                          {data.comparisons.length ? (
                            <ul className="grid gap-2">
                              {data.comparisons.map((comparison, index) => {
                                const change = current.items.find(
                                  (item) =>
                                    item.design_item_id ===
                                    comparison.target_id,
                                )
                                const kind = approved.find(
                                  (item) =>
                                    item.design_item_id ===
                                    comparison.target_id,
                                )?.kind
                                return (
                                  <Diff
                                    key={`${comparison.target_id}-${index}`}
                                    open={index === 0}
                                    comparison={comparison}
                                    operation={
                                      comparison.target_id
                                        ? (change?.operation ?? 'UPDATE')
                                        : 'CREATE'
                                    }
                                    label={
                                      comparison.target_type === 'DOCUMENT'
                                        ? (documentOptions.find(
                                            (item) =>
                                              item.id === comparison.target_id,
                                          )?.title ?? '문서')
                                        : comparison.target_id
                                          ? itemKey(comparison.target_id)
                                          : ''
                                    }
                                    kind={
                                      comparison.target_type === 'DOCUMENT'
                                        ? '문서'
                                        : kind && kindLabel[kind]
                                    }
                                  />
                                )
                              })}
                            </ul>
                          ) : (
                            <p className="text-sm text-muted-foreground">
                              비교할 내용이 없어요.
                            </p>
                          )}
                        </div>
                      )}
                      {tab === 'impact' && (
                        <AsyncState query={impact}>
                          {(result) => {
                            const running = isImpactRunning(result)
                            const notStarted = isImpactNotStarted(result)
                            return (
                              <div className="grid gap-3 text-sm">
                                {current.status === 'PUBLISHED' && (
                                  <p className="text-muted-foreground">
                                    발행 전 영향 분석 결과예요. 발행 후 처리
                                    상태는 위에서 확인해 주세요.
                                  </p>
                                )}
                                <div className="flex flex-wrap items-center justify-between gap-2">
                                  <p className="text-muted-foreground">
                                    {impactMessage(result, editable)}
                                  </p>
                                  {editable && (
                                    <Button
                                      size="sm"
                                      variant="outline"
                                      disabled={action.busy || running}
                                      onClick={() =>
                                        void perform(
                                          () =>
                                            changesApi.retryImpact(
                                              projectId,
                                              current.id,
                                            ),
                                          notStarted
                                            ? '영향 분석을 시작했어요.'
                                            : '다시 분석을 요청했어요.',
                                        )
                                      }
                                    >
                                      {notStarted
                                        ? '영향 분석 시작'
                                        : '다시 분석'}
                                    </Button>
                                  )}
                                </div>
                                <div
                                  className={cn(
                                    'grid gap-2',
                                    running && 'opacity-50',
                                  )}
                                >
                                  <h4 className="font-semibold">확인된 관계</h4>
                                  {result.explicit.length ? (
                                    result.explicit.map((entry, index) => (
                                      <p
                                        key={index}
                                        className="rounded-lg border px-3 py-2"
                                      >
                                        {taskKey(entry.task_id)} ·{' '}
                                        {entry.member_id
                                          ? nameOfMember(entry.member_id)
                                          : '미배정'}{' '}
                                        · {itemKey(entry.design_item_id)}
                                        {entry.validation_id &&
                                          ' · 관련 검증 있음'}
                                      </p>
                                    ))
                                  ) : (
                                    <p className="text-muted-foreground">
                                      {hasCurrentImpact(result)
                                        ? '확인된 관계가 없어요.'
                                        : '확인된 관계를 아직 판단할 수 없어요.'}
                                    </p>
                                  )}
                                  <h4 className="font-semibold">
                                    AI 후보 · 확정이나 승인이 아니에요
                                  </h4>
                                  {result.candidates.map((candidate, index) => (
                                    <p
                                      key={index}
                                      className="rounded-lg border border-dashed px-3 py-2 leading-6"
                                    >
                                      {candidate.reason}
                                      {candidate.member_id &&
                                        ` · ${nameOfMember(candidate.member_id)}`}
                                    </p>
                                  ))}
                                  <p className="text-xs text-muted-foreground">
                                    후보가 없어도 영향이 없다는 뜻은 아니에요.
                                    {result.generation?.origin === 'TEMPLATE' &&
                                      ' 지금 결과는 모델 분석이 아닌 예시 설명이에요.'}
                                  </p>
                                </div>
                              </div>
                            )
                          }}
                        </AsyncState>
                      )}
                      {tab === 'comments' && (
                        <div className="grid gap-3 text-sm">
                          {commentList.map((comment) => (
                            <article
                              key={comment.id}
                              className="rounded-lg bg-muted/40 p-3"
                            >
                              <p className="mb-1 text-xs text-muted-foreground">
                                {nameOfAccount(comment.author_account_id)} ·{' '}
                                {comment.kind === 'REVIEW_REQUEST'
                                  ? '검토 요청'
                                  : '의견'}
                              </p>
                              <p className="leading-6 whitespace-pre-wrap">
                                {comment.body}
                              </p>
                            </article>
                          ))}
                          {comments.hasNextPage && (
                            <Button
                              size="sm"
                              variant="outline"
                              onClick={() => void comments.fetchNextPage()}
                            >
                              의견 더 보기
                            </Button>
                          )}
                          {/* 서버는 발행·철회된 제안의 의견을 409 invalid_transition으로 거절한다(PR #72 mock/live 전수 대조 DOC-LIVE-6). */}
                          {['PUBLISHED', 'WITHDRAWN'].includes(
                            current.status,
                          ) ? (
                            <p className="rounded-lg bg-muted p-3 text-sm text-muted-foreground">
                              끝난 제안이라 댓글을 달 수 없어요.
                            </p>
                          ) : (
                            <ActionForm
                              key={commentList.length}
                              fields={[
                                {
                                  name: 'kind',
                                  label: '남길 종류',
                                  type: 'select',
                                  value: 'COMMENT',
                                  options: [
                                    { value: 'COMMENT', label: '의견' },
                                    {
                                      value: 'REVIEW_REQUEST',
                                      label: '검토 요청',
                                    },
                                  ],
                                },
                                {
                                  name: 'member_id',
                                  label: '검토를 요청할 팀원',
                                  type: 'select',
                                  // 서버는 대상이 없는 검토 요청을 400으로 거절한다(PR #72 mock/live 전수 대조 DOC-LIVE-5).
                                  requiredWhen: (values) =>
                                    values.kind === 'REVIEW_REQUEST',
                                  options: [
                                    { value: '', label: '고르지 않음' },
                                    ...people.map((member) => ({
                                      value: member.id,
                                      label: member.name,
                                    })),
                                  ],
                                },
                                {
                                  name: 'body',
                                  label: '내용',
                                  type: 'textarea',
                                  required: true,
                                  max: 2000,
                                },
                              ]}
                              submitLabel="남기기"
                              onSubmit={async (values) => {
                                const review = values.kind === 'REVIEW_REQUEST'
                                await action.run(
                                  () =>
                                    changesApi.comment(projectId, current.id, {
                                      body: values.body,
                                      kind: review
                                        ? 'REVIEW_REQUEST'
                                        : 'COMMENT',
                                      ...(review && values.member_id
                                        ? {
                                            mentioned_member_ids: [
                                              values.member_id,
                                            ],
                                          }
                                        : {}),
                                    }),
                                  review
                                    ? '검토를 요청했어요.'
                                    : '의견을 남겼어요.',
                                )
                              }}
                            />
                          )}
                          <p className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
                            의견은 승인과 달라요. 승인은 팀장이 해요.
                            {/*
                              회의 생성(MTG-A01)은 폐지 예정이고 BE 에도 라우트가 없어, 열린 질문 Q5 가 정해질 때까지 '회의로 논의'를 주석으로 보존한다.
                              <Button size="sm" variant="ghost" onClick={() => setDiscussing(true)}>
                              회의로 논의
                              </Button>
                            */}
                          </p>
                        </div>
                      )}
                    </div>
                    {/* 서버는 팀장이어도 작성자면 거두게 한다(PR #72 5차 조사 FIFTH-DOC-02). */}
                    {mine && (editable || current.status === 'REJECTED') && (
                      <div className="flex flex-wrap items-center justify-end gap-2 border-t pt-4">
                        {withdrawing ? (
                          <>
                            <span className="mr-auto text-sm">
                              제안을 거둘까요? 거둔 제안은 다시 고칠 수 없고,
                              다시 올리면 새 제안이 돼요.
                            </span>
                            <Button
                              variant="outline"
                              onClick={() => setWithdrawing(false)}
                            >
                              취소
                            </Button>
                            {/* CHG-A15 거두기(PR #46): 올렸거나 반려된 내 제안만 */}
                            <Button
                              variant="destructive"
                              disabled={action.busy}
                              onClick={() =>
                                void perform(
                                  () =>
                                    changesApi.withdraw(projectId, current.id, {
                                      expected_revision: current.revision,
                                      expected_snapshot_hash:
                                        current.snapshot_hash,
                                    }),
                                  '제안을 거뒀어요.',
                                ).then(() => setWithdrawing(false))
                              }
                            >
                              거두기
                            </Button>
                          </>
                        ) : (
                          <>
                            <span className="mr-auto text-[13px] leading-[19px] text-muted-foreground">
                              올린 제안은 잠겨 있어요. 거두면 이 제안은 끝나고,
                              다시 올리면 새 제안이 돼요.
                            </span>
                            <Button
                              variant="outline"
                              onClick={() => setWithdrawing(true)}
                            >
                              제안 거두기
                            </Button>
                          </>
                        )}
                      </div>
                    )}
                    {isLeader && editable && (
                      <div className="flex flex-wrap items-center justify-end gap-2 border-t pt-4">
                        {current.requires_refresh && (
                          <span className="mr-auto text-xs text-muted-foreground">
                            제안자가 최신 기준으로 맞춘 뒤 승인할 수 있어요.
                          </span>
                        )}
                        {/* 확인 필요(백엔드 메모 9): 검토 시작을 따로 누를지, 팀장이 열면 자동으로 할지 */}
                        {current.status === 'PROPOSED' && (
                          <Button
                            variant="ghost"
                            disabled={action.busy}
                            onClick={() =>
                              void perform(
                                () =>
                                  changesApi.review(projectId, current.id, {
                                    expected_revision: current.revision,
                                    expected_snapshot_hash:
                                      current.snapshot_hash,
                                  }),
                                '검토를 시작했어요.',
                              )
                            }
                          >
                            검토 시작
                          </Button>
                        )}
                        <Button
                          variant="destructive"
                          disabled={action.busy}
                          onClick={() => setMode({ kind: 'reject' })}
                        >
                          {current.requires_refresh
                            ? '기준이 바뀌어 반려'
                            : '반려'}
                        </Button>
                        <Button
                          disabled={action.busy || current.requires_refresh}
                          onClick={openApprove}
                        >
                          승인하고 기준 v{nextVersion} 발행
                        </Button>
                      </div>
                    )}
                    {/*
                      '회의로 논의' 다이얼로그(주석 처리 중). 회의 생성(MTG-A01)은 폐지 예정이고 BE 에도 라우트가 없어, 열린 질문 Q5 가 정해질 때까지 '회의로 논의'를 주석으로 보존한다.
                      {discussing && (
                      <FormDialog
                      title="변경 제안을 함께 논의하기"
                      description="관련 팀원을 골라 회의를 만들어요. 전체 팀원을 자동으로 부르지 않아요."
                      fields={[
                      {
                      name: 'title',
                      label: '회의 제목',
                      value: current.title,
                      required: true,
                      },
                      {
                      name: 'member_id',
                      label: '함께 논의할 팀원',
                      type: 'select',
                      required: true,
                      options: people.map((member) => ({
                      value: member.id,
                      label: member.name,
                      })),
                      },
                      ]}
                      submitLabel="회의 만들기"
                      onClose={() => setDiscussing(false)}
                      onSubmit={async (values) => {
                      await action.run(async () => {
                      const meeting = await meetingsApi.create(
                      projectId,
                      {
                      title: values.title,
                      change_proposal_id: current.id,
                      participant_member_ids: [values.member_id],
                      },
                      )
                      setDiscussing(false)
                      openPane('meetings', meeting.id)
                      }, '관련 팀원과 논의할 회의를 만들었어요.')
                      }}
                      />
                      )}
                    */}
                  </div>
                )
              }}
            </AsyncState>
          )}
        </div>
      </div>
    </section>
  )
}

function Back({
  children,
  onClick,
}: {
  children: ReactNode
  onClick: () => void
}) {
  return (
    <Button
      variant="ghost"
      size="sm"
      className="justify-self-start"
      onClick={onClick}
    >
      <ArrowLeft />
      {children}
    </Button>
  )
}

// 개요 카드(캔버스 PNL-04-v2 통일감 개선: 모서리 14px, 안쪽 16/18px, 머리 15px·700, 이동 버튼 13px·600 노란 글자)
const overviewCard = 'rounded-[14px] border px-[18px] py-4'
const overviewHead =
  'mb-2.5 flex items-center justify-between gap-2 text-[15px] leading-[22px] font-bold'
const overviewLink =
  'text-[13px] font-semibold whitespace-nowrap text-[#8a6a00] hover:underline'
// 바뀌는 내용 · 항목마다 한 줄로 접어 두고, 펼치면 기준 문장과 제안 문장을 −/+로 보여 준다.

function Diff({
  comparison,
  operation,
  label,
  kind,
  open,
}: {
  comparison: Comparison
  operation: keyof typeof operationLabel
  label: string
  kind?: string
  open?: boolean
}) {
  const [whole, setWhole] = useState(false)
  const baseText = comparison.current_content ?? comparison.base_content
  const before = describeContent(baseText)
  const after = describeContent(comparison.proposed_content)
  // 문서 원문은 줄 단위로 비교하고 바뀐 줄 앞뒤만 보여 준다(PNL-04 수정안 회의 제안 · 바뀌는 내용).
  const lines =
    comparison.target_type === 'DOCUMENT' &&
    typeof baseText === 'string' &&
    typeof comparison.proposed_content === 'string'
      ? lineDiff(baseText, comparison.proposed_content)
      : undefined
  // 설명 밖의 구조화 필드(method·path·fields 등)도 바뀐 것만 줄로 보인다(PR #72 전수 리뷰 CHG-2).
  const fields = changedFields(baseText, comparison.proposed_content)
  // 설명은 그대로고 필드만 바뀐 수정이면 설명은 −/+ 대신 그대로 한 번만 보인다.
  const sameDescription = operation === 'UPDATE' && before === after
  return (
    <li>
      <details
        open={open}
        className="group overflow-hidden rounded-xl border text-[13px]"
      >
        <summary className="flex cursor-pointer items-center gap-2 px-3 py-2.5 [&::-webkit-details-marker]:hidden">
          <Tag tone={operationTone[operation]}>{operationLabel[operation]}</Tag>
          {label && <strong className="font-semibold">{label}</strong>}
          {kind && <span className="text-muted-foreground">{kind}</span>}
          <span className="ml-auto text-xs text-muted-foreground group-open:hidden">
            펼치기
          </span>
          <span className="ml-auto hidden text-xs text-muted-foreground group-open:inline">
            접기
          </span>
          <ChevronDown className="size-3.5 shrink-0 group-open:rotate-180" />
        </summary>
        <div className="px-3 pb-3">
          {comparison.format === 'FILE' ? (
            <p className="text-muted-foreground">
              파일 버전 변경이에요. 공용 문서함에서 두 원본을 내려받아
              확인하세요.
            </p>
          ) : lines ? (
            <div className="grid gap-1">
              <div className="grid gap-0.5 font-mono text-xs leading-6 whitespace-pre-wrap">
                {(whole ? lines : foldDiff(lines)).map((line, index) =>
                  line.kind === 'folded' ? (
                    <p key={index} className="px-2 text-muted-foreground">
                      … 같은 줄 {line.count}개
                    </p>
                  ) : (
                    <p
                      key={index}
                      className={cn(
                        'px-2.5',
                        line.kind === 'removed' &&
                          'bg-[#fdecec] text-[#8f1d17]',
                        line.kind === 'added' && 'bg-[#e8f3ec] text-[#00532a]',
                      )}
                    >
                      {line.kind === 'removed'
                        ? '− '
                        : line.kind === 'added'
                          ? '+ '
                          : '  '}
                      {line.text}
                    </p>
                  ),
                )}
              </div>
              <Button
                type="button"
                size="xs"
                variant="ghost"
                className="justify-self-start"
                onClick={() => setWhole((value) => !value)}
              >
                {whole ? '바뀐 줄만 보기' : '원문 전체 보기'}
              </Button>
            </div>
          ) : (
            <div className="grid gap-0.5 leading-5 whitespace-pre-wrap">
              {sameDescription ? (
                <p className="px-2.5 py-0.75 text-muted-foreground">{after}</p>
              ) : (
                <>
                  {operation !== 'CREATE' && (
                    <p className="bg-[#fdecec] px-2.5 py-0.75 text-[#8f1d17]">
                      − {before}
                    </p>
                  )}
                  {operation !== 'RETIRE' && (
                    <p className="bg-[#e8f3ec] px-2.5 py-0.75 text-[#00532a]">
                      + {after}
                    </p>
                  )}
                </>
              )}
              {fields.map((field) => (
                <div key={field.key} className="mt-1.5 grid gap-0.5">
                  <p className="px-2.5 text-xs font-semibold text-muted-foreground">
                    {field.key}
                  </p>
                  {operation !== 'CREATE' && field.before !== undefined && (
                    <p className="bg-[#fdecec] px-2.5 py-0.75 font-mono text-xs text-[#8f1d17]">
                      − {field.before}
                    </p>
                  )}
                  {operation !== 'RETIRE' && field.after !== undefined && (
                    <p className="bg-[#e8f3ec] px-2.5 py-0.75 font-mono text-xs text-[#00532a]">
                      + {field.after}
                    </p>
                  )}
                </div>
              ))}
            </div>
          )}
        </div>
      </details>
    </li>
  )
}
