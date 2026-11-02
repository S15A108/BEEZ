import { useState } from 'react'
import type { ReactNode } from 'react'
import { useInfiniteQuery, useQueries, useQuery } from '@tanstack/react-query'
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
  meetingsApi,
  planningApi,
  tasksApi,
} from '@/api/services'
import type { ChangeProposal, Comparison } from '@/api/contracts/common'
import { readAllPages } from '@/api/readAllPages'
import { ActionForm } from '@/components/ActionForm'
import { AsyncState } from '@/components/AsyncState'
import { EmptyState } from '@/components/EmptyState'
import { FormDialog } from '@/components/FormDialog'
import { StatusPill } from '@/components/StatusPill'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { NativeSelect, NativeSelectOption } from '@/components/ui/native-select'
import { useSession } from '@/features/auth/useSession'
import { useProjectAction } from '@/features/workspace/useProjectAction'
import { formatDate } from '@/lib/formatDate'
import { describeContent, LIST_SELECTED } from '@/lib/display'
import { cn } from '@/lib/utils'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import { changedFields, foldDiff, lineDiff } from './lineDiff'
import { ProposalForm } from './ProposalForm'
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
  const [discussing, setDiscussing] = useState(false)
  const [withdrawing, setWithdrawing] = useState(false)
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
  const impactCount = impact.data
    ? impact.data.explicit.length + impact.data.candidates.length
    : 0
  const commentList = comments.data?.pages.flatMap((page) => page.items) ?? []
  const visible = entries
    .filter((entry) => {
      const query = search.trim()
      if (!query) return true
      // 목록은 본문 없는 요약(PR #46)이라 제목과 제안 키로 거른다. 서버 검색(q)은 195 미구현.
      return entry.title.includes(query) || entry.key.includes(query)
    })
    .toSorted((a, b) =>
      sort === 'status'
        ? statusOrder[a.status] - statusOrder[b.status]
        : b.updated_at.localeCompare(a.updated_at),
    )
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
      <div className="flex flex-wrap items-start justify-between gap-3">
        <p className="max-w-2xl text-sm leading-6 text-muted-foreground">
          팀원이 올린 문서 수정 제안을 모아 보고 검토해요. 팀장이 승인하면 새
          기준을 발행해요. 제안은 공용 문서함에서 문서를 열고
          &apos;고치기&apos;로 올려요.
        </p>
        <Button variant="outline" onClick={() => openPane('documents')}>
          <FolderOpen />
          공용 문서함에서 제안하기
        </Button>
      </div>
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
          mode.kind === 'detail' && 'lg:grid-cols-[280px_minmax(0,1fr)]',
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
          <div role="tablist" aria-label="제안 상태" className="flex gap-1">
            {statusTabs.map(([value, label]) => (
              <button
                key={value}
                type="button"
                role="tab"
                aria-selected={filter === value}
                onClick={() => setFilter(value)}
                className={cn(
                  'rounded-full px-2.5 py-1 text-xs',
                  filter === value
                    ? 'bg-foreground text-background'
                    : 'bg-muted',
                )}
              >
                {label}
              </button>
            ))}
          </div>
          <AsyncState query={changes}>
            {() =>
              visible.length ? (
                <ul className="grid gap-2">
                  {visible.map((entry) => (
                    <li key={entry.id}>
                      <button
                        type="button"
                        aria-current={selectedId === entry.id}
                        onClick={() => select(entry.id)}
                        className={cn(
                          'grid w-full gap-1 rounded-xl border px-3.5 py-3 text-left hover:bg-muted/40',
                          selectedId === entry.id && LIST_SELECTED,
                        )}
                      >
                        <strong className="text-sm font-semibold">
                          {entry.title}
                        </strong>
                        <span className="flex flex-wrap items-center gap-1.5 text-xs text-muted-foreground">
                          <StatusPill value={entry.status} />
                          {entry.key} ·{' '}
                          {nameOfAccount(entry.proposer_account_id)}
                          {entry.proposer_account_id === session.data?.id &&
                            '(나)'}
                          {baseline.data &&
                            entry.base_baseline_id !== baseline.data.id &&
                            ['PROPOSED', 'IN_REVIEW', 'APPROVED'].includes(
                              entry.status,
                            ) && (
                              <span className="rounded-full bg-[#fdecec] px-2 py-0.5 font-semibold text-[#b42318]">
                                기준 바뀜
                              </span>
                            )}
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
          {mode.kind === 'form' ? (
            <ProposalForm
              key={documents.isSuccess ? 'documents' : 'loading'}
              documents={documentOptions}
              // 문서 목록 응답에는 본문이 없어(백엔드 DOC-A02) 고른 문서의 기준 원문을 따로 받는다.
              fetchDocumentContent={(documentId) =>
                documentsApi.get(projectId, documentId).then((result) => ({
                  content: result.version.content ?? '',
                  versionId: result.version.id,
                }))
              }
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
              status={mode.source && <StatusPill value={mode.source.status} />}
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
                          <p className="text-sm">
                            {nameOfAccount(result.approved_by)} 승인 ·{' '}
                            {formatDate(result.approved_at)}
                          </p>
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
                      <p className="rounded-lg bg-muted/50 p-3 text-sm leading-6">
                        승인하면 이 항목을 쓰는 검증은 &apos;다시 검증
                        필요&apos;, 진행 중 작업은 &apos;대기 · 재검증&apos;으로
                        바뀌어요. 문서 원문도 함께 새 기준이 돼요.
                        {/* API 없음: 승인 전에 바뀔 검증·작업 목록을 미리 받는 응답(백엔드 메모 4). 지금은 영향 탭의 확인된 관계로 대신한다. */}
                        {impact.data?.explicit.length
                          ? ` 확인된 관련 작업: ${impact.data.explicit.map((entry) => taskKey(entry.task_id)).join(', ')}`
                          : ''}
                      </p>
                      <div className="flex justify-end gap-2">
                        <Button
                          variant="outline"
                          onClick={() => setMode({ kind: 'detail' })}
                        >
                          취소
                        </Button>
                        <Button
                          disabled={action.busy}
                          onClick={() =>
                            void perform(
                              () =>
                                changesApi.approve(projectId, current.id, {
                                  expected_base_baseline_id:
                                    current.base_baseline_id,
                                  expected_revision: current.revision,
                                  expected_snapshot_hash: current.snapshot_hash,
                                }),
                              `기준 v${nextVersion} 발행을 마쳤어요.`,
                            ).then(() => setMode({ kind: 'detail' }))
                          }
                        >
                          승인하고 기준 v{nextVersion} 발행
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
                      <h3 className="flex flex-wrap items-center gap-2 text-lg font-bold">
                        {current.title}
                        <StatusPill value={current.status} />
                        {current.requires_refresh && editable && (
                          <span className="rounded-full bg-[#fdecec] px-2 py-0.5 text-xs font-semibold text-[#b42318]">
                            기준 바뀜
                          </span>
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
                      <div className="grid gap-1 rounded-xl border border-primary/40 bg-primary/10 p-4 text-sm">
                        <strong>
                          {current.status === 'IN_REVIEW'
                            ? `${leaderName}님이 검토하고 있어요`
                            : `${leaderName}님의 검토를 기다리고 있어요`}
                        </strong>
                        <span className="text-muted-foreground">
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
                              ? 'border-foreground font-semibold'
                              : 'border-transparent text-muted-foreground',
                          )}
                        >
                          {label}
                          {value === 'impact' && ` ${impactCount}`}
                          {value === 'comments' && ` ${commentList.length}`}
                        </button>
                      ))}
                    </div>
                    <div role="tabpanel" className="min-h-40">
                      {tab === 'overview' && (
                        <div className="grid gap-3 text-sm">
                          <p className="leading-6">{current.reason}</p>
                          <h4 className="flex flex-wrap items-center gap-2 font-semibold">
                            바뀌는 내용 {current.items.length}
                            <span className="text-xs font-normal text-muted-foreground">
                              {(['UPDATE', 'CREATE', 'RETIRE'] as const)
                                .map(
                                  (operation) =>
                                    [
                                      operation,
                                      current.items.filter(
                                        (item) => item.operation === operation,
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
                              className="ml-auto text-xs font-normal underline"
                              onClick={() => setTab('diff')}
                            >
                              자세히
                            </button>
                          </h4>
                          <ul className="grid gap-1.5">
                            {current.items.map((item, index) => (
                              <li
                                key={index}
                                className="rounded-lg border px-3 py-2"
                              >
                                <strong className="font-semibold">
                                  {operationLabel[item.operation]}
                                </strong>{' '}
                                {item.operation === 'CREATE'
                                  ? '새 항목'
                                  : itemKey(item.design_item_id)}
                                {item.proposed_content && (
                                  <span className="text-muted-foreground">
                                    {' · '}
                                    {describeContent(item.proposed_content)}
                                  </span>
                                )}
                              </li>
                            ))}
                            {current.documents.length > 0 && (
                              <li className="rounded-lg border border-dashed px-3 py-2 text-muted-foreground">
                                문서 변경 {current.documents.length}건은 공용
                                문서함 승인 흐름에서 다뤄요.
                              </li>
                            )}
                          </ul>
                          <div className="grid gap-3 sm:grid-cols-2">
                            <button
                              type="button"
                              className="grid gap-1 rounded-xl border p-3 text-left hover:bg-muted/40"
                              onClick={() => setTab('impact')}
                            >
                              <strong>영향</strong>
                              <span className="text-xs text-muted-foreground">
                                확인된 관계 {impact.data?.explicit.length ?? 0}{' '}
                                · AI 후보 {impact.data?.candidates.length ?? 0}
                              </span>
                            </button>
                            <button
                              type="button"
                              className="grid gap-1 rounded-xl border p-3 text-left hover:bg-muted/40"
                              onClick={() => setTab('comments')}
                            >
                              <strong>의견</strong>
                              <span className="text-xs text-muted-foreground">
                                의견 {commentList.length}
                                {commentList[0] &&
                                  ` · ${nameOfAccount(commentList[0].author_account_id)}: ${commentList[0].body}`}
                              </span>
                            </button>
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
                            const running = ['PENDING', 'RUNNING'].includes(
                              result.analysis_status,
                            )
                            return (
                              <div className="grid gap-3 text-sm">
                                <div className="flex flex-wrap items-center justify-between gap-2">
                                  <p className="text-muted-foreground">
                                    {running
                                      ? '다시 분석하는 중이에요. 이전 결과는 흐리게 보여요.'
                                      : result.analysis_status === 'FAILED'
                                        ? '분석하지 못했어요.'
                                        : '확인된 관계와 AI 후보를 나눠 보여요.'}
                                  </p>
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
                                        '다시 분석을 요청했어요.',
                                      )
                                    }
                                  >
                                    다시 분석
                                  </Button>
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
                                        {nameOfMember(entry.member_id)} ·{' '}
                                        {itemKey(entry.design_item_id)}
                                        {entry.validation_id &&
                                          ' · 관련 검증 있음'}
                                      </p>
                                    ))
                                  ) : (
                                    <p className="text-muted-foreground">
                                      확인된 관계가 없어요.
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
                            {/* 열린 질문 Q5: 사람이 회의를 만들지 않는 결정과 부딪혀 '회의로 논의'는 그대로 둔다. */}
                            <Button
                              size="sm"
                              variant="ghost"
                              onClick={() => setDiscussing(true)}
                            >
                              회의로 논의
                            </Button>
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
                            <span className="mr-auto text-xs text-muted-foreground">
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
                          variant="outline"
                          disabled={action.busy}
                          onClick={() => setMode({ kind: 'reject' })}
                        >
                          {current.requires_refresh
                            ? '기준이 바뀌어 반려'
                            : '반려'}
                        </Button>
                        <Button
                          disabled={action.busy || current.requires_refresh}
                          onClick={() => setMode({ kind: 'approve' })}
                        >
                          승인하고 기준 v{nextVersion} 발행
                        </Button>
                      </div>
                    )}
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

// 바뀌는 내용 · 항목마다 한 줄로 접어 두고, 펼치면 기준 문장과 제안 문장을 −/+로 보여 준다.
const operationTone = {
  UPDATE: 'bg-[#fdf0c8] text-[#6e5200]',
  CREATE: 'bg-[#e8f3ec] text-[#00753a]',
  RETIRE: 'bg-[#fdecec] text-[#b42318]',
} as const

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
          <span
            className={cn(
              'rounded-full px-2 py-0.5 text-xs font-semibold',
              operationTone[operation],
            )}
          >
            {operationLabel[operation]}
          </span>
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
