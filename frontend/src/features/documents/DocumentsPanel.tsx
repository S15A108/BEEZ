import { useState } from 'react'
import { useInfiniteQuery, useQueries, useQuery } from '@tanstack/react-query'
import { Controller, useForm, useWatch } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import {
  ChevronLeft,
  Download,
  Ellipsis,
  FileText,
  Lock,
  Plus,
  Search,
  Sparkles,
  Upload,
  X,
} from 'lucide-react'
import { documentsApi } from '@/api/services/documents'
import { readAllPages } from '@/api/readAllPages'
import { getApiErrorCode, getApiErrorMessage } from '@/api/error'
import { AsyncState } from '@/components/AsyncState'
import { MarkdownView } from '@/components/MarkdownView'
import { EmptyState } from '@/components/EmptyState'
import { FormDialog } from '@/components/FormDialog'
import { PaneHeaderAction } from '@/components/PaneHeaderAction'
import { Toast } from '@/components/Toast'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { NativeSelect, NativeSelectOption } from '@/components/ui/native-select'
import { useRemembered, useWorkspaceStore } from '@/stores/workspaceStore'
import {
  LIST_ROW,
  LIST_SELECTED,
  SPLIT_COLUMNS,
  SPLIT_DETAIL,
  statusLabel,
} from '@/lib/display'
import { cn } from '@/lib/utils'
import { parseCitationFocus, type CitationTarget } from '@/lib/citationTarget'
import { useProjectAction } from '@/features/workspace/useProjectAction'
import { useSession } from '@/features/auth/useSession'
import { membersQuery } from '@/features/members/membersQuery'
import type { DocumentVersion } from '@/api/contracts/common'
import { DocumentReview } from './DocumentReview'
import { reviewStage } from './reviewStage'
import { VerifiedCitation } from './VerifiedCitation'

// 공용 문서함 수정안(PNL-02 모달안 + 10-29 프로젝트 시작 보드): 목록 · 상세 · 등록 · 검토를 같은 창 안에서 바꿔 보여 준다.
// 시작 전에는 필수 문서 4종 칸과 기획 분석을 여는 '설계 초안 만들기'(4종 승인 전에는 잠김), 시작 뒤에는 현재 문서만 두고 문서 수정은 변경 제안으로 보낸다.
const KINDS = ['PLAN', 'REQUIREMENTS', 'ERD', 'API_SPEC'] as const
type Kind = (typeof KINDS)[number]
const day = (value: string) =>
  new Intl.DateTimeFormat('ko-KR', {
    dateStyle: 'medium',
    timeZone: 'Asia/Seoul',
  }).format(new Date(value))
const stamp = (value: string) =>
  new Intl.DateTimeFormat('ko-KR', {
    dateStyle: 'medium',
    timeStyle: 'short',
    timeZone: 'Asia/Seoul',
  }).format(new Date(value))

// 버전 이력의 상태 이름 모양. 승인 = 현재 기준 후보, 초안 = 아직 쓸 수 있는 후보는 테두리로, 이전 버전 = 대체된 승인본과
// 미채택(REJECTED) = 채택되지 않았고 다시 쓸 수 없는 것(제안을 거뒀거나, 다시 제출했거나, 다른 제안이 먼저 발행됨,
// 문서함 반려)은 잘못이 아니라 결과라 회색 바탕으로 낮춘다. 노란색은 주 행동과 선택에만 쓴다(D49).
const versionTone: Record<DocumentVersion['status'], 'outline' | 'secondary'> =
  {
    APPROVED: 'outline',
    DRAFT: 'outline',
    SUPERSEDED: 'secondary',
    REJECTED: 'secondary',
  }
// 버전 상태 이름은 이력과 버전 드롭다운이 같게 보인다. 채택되지 않은 버전은 '반려'가 아니라 '미채택'이다.
const versionStatusLabel = (status: DocumentVersion['status']) =>
  status === 'REJECTED' ? '미채택' : statusLabel(status)

type View =
  | { name: 'list' }
  // versionId: 기준 vN 문서 보기에서 연 그 기준의 문서 버전
  | {
      name: 'detail'
      id: string
      versionId?: string
      citation?: CitationTarget
    }
  | { name: 'invalidCitation' }
  | { name: 'baseline'; versionNo: number }
  | { name: 'upload'; kind: Kind }
  | { name: 'review'; id: string }
  // 비비 검토가 실패해 '파일 등록' 단계로 돌아온 화면. 지금 등록된 파일을 선택된 채로 보이고 새 파일로 바꿔 올린다(DOC-A15).
  | {
      name: 'reupload'
      id: string
      kind: Kind
      title: string
      domain?: string
      fileName: string
      content: string
      revision: number
    }

// 문서함은 목록과 상세만 기억한다. 등록·다시 올리기·검토·잘못된 인용·기준 보기처럼 잠깐 거치는 화면과 파일 본문,
// 근거 인용 위치는 남기지 않는다. 다시 열면 목록이나 보던 문서(인용 표시 없이)로 열린다(PR #321 리뷰).
const rememberedView = (view: View): View =>
  view.name === 'detail'
    ? {
        name: 'detail',
        id: view.id,
        ...(view.versionId ? { versionId: view.versionId } : {}),
      }
    : { name: 'list' }

export function DocumentsPanel({
  projectId,
  isLeader,
}: {
  projectId: string
  isLeader: boolean
}) {
  const openPane = useWorkspaceStore((state) => state.openPane)
  // 알림·근거·가이드에서 들어오면 그 문서(또는 'upload:종류' 등록 화면)를 바로 연다.
  // 그냥 열면 이 프로젝트에서 마지막으로 보던 화면(연 문서 등)을 다시 연다(S15P31A108-431).
  const [view, setView] = useRemembered<View>(
    'documents.view',
    () => {
      const focus = useWorkspaceStore.getState().focusId
      if (focus?.startsWith('citation:')) {
        const citation = parseCitationFocus(focus)
        return citation
          ? {
              name: 'detail',
              id: citation.document_id,
              versionId: citation.document_version_id,
              citation,
            }
          : { name: 'invalidCitation' }
      }
      // 시작 가이드의 '올리기'는 'upload:종류'로 들어온다. 종류는 그 칸으로 고정한다.
      if (
        focus?.startsWith('upload:') &&
        KINDS.includes(focus.slice(7) as Kind)
      )
        return { name: 'upload', kind: focus.slice(7) as Kind }
      // 발행한 변경 제안의 '기준 vN 보기'는 'baseline:N'으로 들어와 그 기준의 문서를 보인다(ROUND2-DOC-02).
      if (focus?.startsWith('baseline:'))
        return { name: 'baseline', versionNo: Number(focus.slice(9)) }
      // HUD 비비 줄의 '문서 검토 중'은 'review:문서 ID'로 들어와 검토 화면을 연다. 승인 전 문서는 상세(DOC-A03)로 열 수 없다.
      if (focus?.startsWith('review:'))
        return { name: 'review', id: focus.slice(7) }
      // 비비·회의·기획 답의 근거는 'version:문서 ID:버전 ID'로 들어와 인용한 그 버전(초안 포함)을 연다.
      if (focus?.startsWith('version:')) {
        const [id, versionId] = focus.slice(8).split(':')
        return { name: 'detail', id, versionId }
      }
      return focus ? { name: 'detail', id: focus } : { name: 'list' }
    },
    false,
    rememberedView,
  )
  const [query, setQuery] = useState('')
  const [notice, setNotice] = useState<{ title?: string; text: string }>()
  const documents = useQuery({
    queryKey: ['projects', projectId, 'planning-documents'],
    queryFn: () => documentsApi.list(projectId, { limit: 100 }),
  })
  const baseline = useQuery({
    queryKey: ['projects', projectId, 'baseline'],
    queryFn: () => documentsApi.baseline(projectId),
    retry: false,
  })
  const started = !!baseline.data
  const docs = documents.data?.items ?? []
  // 문서마다 작성 건(DOC-A13)을 불러 시작 준비 칸의 단계를 보여 준다.
  const copies = useQueries({
    queries: docs.map((document) => ({
      queryKey: ['projects', projectId, 'working-copy', document.id],
      queryFn: () => documentsApi.workingCopy(projectId, document.id),
      retry: false,
    })),
  })
  const slots = KINDS.map((kind) => {
    const index = docs.findIndex((document) => document.kind === kind)
    const document = docs[index]
    const copy = copies[index]?.data
    const approved =
      !!document?.approved_version && (!copy || copy.status === 'APPROVED')
    return { kind, document, copy, approved }
  })
  const approvedCount = slots.filter((slot) => slot.approved).length
  const current = docs
    .filter((document) => document.approved_version)
    .toSorted((a, b) => KINDS.indexOf(a.kind) - KINDS.indexOf(b.kind))

  const toast = notice && (
    <Toast key={`${notice.title}${notice.text}`} tone="success">
      {notice.title ? (
        <>
          <strong className="block">{notice.title}</strong>
          <span className="text-muted-foreground">{notice.text}</span>
        </>
      ) : (
        notice.text
      )}
    </Toast>
  )

  // 목록·상세 2단(S15P31A108-445): 왼쪽 목록은 늘 보이고, 고른 문서의 상세·검토·등록을 오른쪽에 연다.
  // 시작 뒤 목록은 승인본이 있는 문서 전부(같은 종류가 여럿일 수 있다), 시작 전 목록은 필수 문서 4종 칸이다.
  type Slot = (typeof slots)[number]
  const readable = (slot: Slot) =>
    started ? !!slot.document?.approved_version : slot.approved
  const open = (slot: Slot): View =>
    !slot.document
      ? { name: 'upload', kind: slot.kind }
      : readable(slot)
        ? { name: 'detail', id: slot.document.id }
        : { name: 'review', id: slot.document.id }
  const rows: Slot[] = started
    ? current.map((document) => ({
        kind: document.kind,
        document,
        copy: undefined,
        approved: true,
      }))
    : slots
  // 그냥 열면 올라온 첫 문서를 오른쪽에 펼친다. 문서를 하나씩 열고 돌아오지 않아도 차례로 읽는다.
  const first = rows.find((slot) => slot.document)
  const shown: View = view.name === 'list' && first ? open(first) : view
  const selectedId = 'id' in shown ? shown.id : undefined
  const selected = (slot: Slot) =>
    slot.document
      ? slot.document.id === selectedId
      : shown.name === 'upload' && shown.kind === slot.kind
  // 행에는 이름과 버전·날짜만 둔다. 승인 전 문서는 그 자리에 지금 단계를 쓴다.
  const rowStatus = (slot: Slot): [text: string, tone?: 'todo' | 'failed'] => {
    const { document, copy } = slot
    if (!document) return ['올리기']
    if (readable(slot)) {
      const { version_no, created_at } = document.approved_version!
      return [`${started ? '' : '승인 '}v${version_no} · ${day(created_at)}`]
    }
    const stage = copy ? reviewStage(copy) : undefined
    if (!stage) return ['비비 검토 중']
    if (stage.failed || stage.label === '반려됨') return [stage.label, 'failed']
    return [stage.label, stage.stale || stage.step > 1 ? 'todo' : undefined]
  }
  // 걸린 변경 제안은 그 제안이 만든 미승인 초안(latest_draft)으로 안다. 있을 때만 한 줄 더한다.
  const proposing = (slot: Slot) =>
    started &&
    slot.document?.latest_draft?.status === 'DRAFT' &&
    !!slot.document.latest_draft.change_proposal_id
  const kindRows = (
    <ul aria-labelledby="documents-list-title" className="grid gap-1.5">
      {rows.map((slot) => {
        const [status, tone] = rowStatus(slot)
        const name = slot.document?.title ?? statusLabel(slot.kind)
        return (
          <li key={slot.document?.id ?? slot.kind}>
            <button
              type="button"
              aria-current={selected(slot)}
              onClick={() => setView(open(slot))}
              className={cn(
                'grid gap-[3px]',
                LIST_ROW,
                selected(slot) ? LIST_SELECTED : 'hover:bg-muted/40',
              )}
            >
              <strong className="truncate text-sm leading-5 font-semibold">
                {name}
              </strong>{' '}
              {/* 줄 사이 공백은 화면 읽기 프로그램이 읽는 이름('기획안 올리기')을 띄어 쓰게 한다. */}
              <span
                className={cn(
                  'flex items-center gap-1 text-caption',
                  tone === 'failed'
                    ? 'text-destructive'
                    : tone === 'todo'
                      ? 'font-medium text-foreground'
                      : 'text-muted-foreground',
                )}
              >
                {!slot.document && (
                  <Plus aria-hidden="true" className="size-3.5" />
                )}
                {/* 제목을 종류 이름과 다르게 지었을 때만 종류를 앞에 붙인다. */}
                {slot.document && name !== statusLabel(slot.kind)
                  ? `${statusLabel(slot.kind)} · `
                  : ''}
                {status}
              </span>{' '}
              {proposing(slot) && (
                <span className="text-caption font-medium text-foreground">
                  변경 제안 진행 중
                </span>
              )}
            </button>
          </li>
        )
      })}
    </ul>
  )

  const right =
    shown.name === 'invalidCitation' ? (
      <p role="alert">인용 위치가 올바르지 않아 문서를 열지 않았어요.</p>
    ) : shown.name === 'reupload' ? (
      <UploadDocument
        projectId={projectId}
        kind={shown.kind}
        replace={shown}
        onCancel={() => setView({ name: 'review', id: shown.id })}
        onDone={(id, title) => {
          // 파일을 바꿔 올리면 리비전이 올라 비비 검토가 처음부터 다시 시작한다.
          setView({ name: 'review', id })
          setNotice({
            title: '파일을 다시 올렸어요',
            text: `${title} · 비비가 다시 검토하고 있어요. 창을 닫아도 검토는 이어져요.`,
          })
        }}
      />
    ) : shown.name === 'upload' ? (
      <UploadDocument
        key={shown.kind}
        projectId={projectId}
        kind={shown.kind}
        onCancel={() => setView({ name: 'list' })}
        onDone={(id, title) => {
          openPane('documents', id)
          // 등록하면 바로 ① 비비 검토 단계로 잇는다.
          setView({ name: 'review', id })
          setNotice({
            title: '문서를 등록했어요',
            text: `${title} · 비비가 검토하고 있어요. 창을 닫아도 검토는 이어져요.`,
          })
        }}
      />
    ) : shown.name === 'review' ? (
      <DocumentReview
        key={shown.id}
        projectId={projectId}
        documentId={shown.id}
        title={docs.find((item) => item.id === shown.id)?.title ?? ''}
        kind={docs.find((item) => item.id === shown.id)?.kind ?? 'PLAN'}
        isLeader={isLeader}
        locked={started}
        onClose={() => setView({ name: 'list' })}
        // 승인하면 검토 화면에 머물지 않고 목록으로 돌아가 같은 알림을 띄운다(실패하면 검토 화면에 남는다).
        onApproved={(message) => {
          setView({ name: 'list' })
          setNotice({ text: message })
        }}
        // 등록을 취소하면 목록으로 돌아가 알린다. 같은 종류를 새로 올릴 수 있다(DOC-A25).
        onDiscarded={(message) => {
          setView({ name: 'list' })
          setNotice({ text: message })
        }}
        // 검토가 실패하면 처음 등록 화면으로 돌아가 파일을 다시 올린다(S15P31A108-382).
        onReupload={(current) => {
          const document = docs.find((item) => item.id === shown.id)
          setView({
            name: 'reupload',
            id: shown.id,
            kind: (document?.kind ?? 'PLAN') as Kind,
            title: document?.title ?? '',
            domain: document?.domain,
            ...current,
          })
        }}
      />
    ) : shown.name === 'baseline' ? (
      <BaselineDocuments
        projectId={projectId}
        versionNo={shown.versionNo}
        titleOf={(id) => docs.find((item) => item.id === id)?.title ?? '문서'}
        onOpen={(id, versionId) => setView({ name: 'detail', id, versionId })}
      />
    ) : shown.name === 'detail' ? (
      <DocumentDetail
        key={`${shown.id}:${shown.versionId ?? ''}`}
        projectId={projectId}
        documentId={shown.id}
        versionId={shown.versionId}
        citation={shown.citation}
        started={started}
        onEdit={() => openPane('changes', `doc:${shown.id}`)}
      />
    ) : (
      <EmptyState>
        {started
          ? '아직 승인된 문서가 없어요.'
          : '필수 문서 4종을 올리면 비비가 검토하고 팀장이 승인해요. 왼쪽에서 올릴 문서를 골라 주세요.'}
      </EmptyState>
    )

  return (
    <section
      aria-label="공용 문서"
      className="space-y-4 lg:flex lg:h-0 lg:min-h-full lg:flex-col"
    >
      {started && isLeader && (
        <PaneHeaderAction>
          <Button variant="outline" onClick={() => openPane('planning')}>
            <Sparkles />
            작업 분해·배정
          </Button>
        </PaneHeaderAction>
      )}
      <div
        className={cn(
          'grid gap-5 lg:min-h-0 lg:flex-1 lg:grid-rows-[minmax(0,1fr)] lg:items-start',
          SPLIT_COLUMNS,
        )}
      >
        {/* 검색칸은 사용자 결정으로 둔다. 검색하면 목록 자리에 결과가 나오고, 결과를 눌러도 결과는 남는다. */}
        <div className="grid content-start gap-3 rounded-xl bg-card p-4 ring-1 ring-foreground/10 lg:max-h-full lg:overflow-y-auto">
          <SearchBar value={query} onSearch={setQuery} />
          {query ? (
            <SearchResults
              projectId={projectId}
              query={query}
              onClear={() => setQuery('')}
              onOpen={(id) => setView({ name: 'detail', id })}
            />
          ) : (
            <>
              <h3
                id="documents-list-title"
                className="flex items-baseline gap-1.5 text-sm font-semibold"
              >
                {started ? '현재 문서' : '필수 문서 4종'}
                {started && ' '}
                {started && (
                  <span className="text-caption font-normal text-muted-foreground">
                    {rows.length}개
                  </span>
                )}
              </h3>
              <AsyncState query={documents}>{() => kindRows}</AsyncState>
              {!started && (
                <div className="grid gap-2 border-t pt-3">
                  <p className="text-sub text-muted-foreground">
                    팀장 승인 {approvedCount} / 4
                  </p>
                  {approvedCount === 4 ? (
                    <>
                      <strong className="text-sm">
                        {isLeader
                          ? '문서 승인을 마쳤어요. 설계 초안을 만들면 시작할 수 있어요'
                          : '문서 승인을 마쳤어요. 팀장이 설계 초안을 만들면 시작돼요'}
                      </strong>
                      <p className="text-sub text-muted-foreground">
                        {isLeader
                          ? "기획 분석에서 설계 항목을 검토하고 '프로젝트 시작'을 누르면 설계 기준 v1이 생기고 시작돼요. 그 뒤 문서 수정은 변경 제안으로 해요."
                          : '팀장이 프로젝트를 시작하면 알려 드려요.'}
                      </p>
                      {/* 기준 v1 발행(PLN-A10)은 기획 분석의 '프로젝트 시작' 한 곳에서 한다. '설계 초안 만들기'는 그 창을 연다. */}
                      {isLeader && (
                        <Button onClick={() => openPane('planning')}>
                          <Sparkles />
                          설계 초안 만들기
                        </Button>
                      )}
                    </>
                  ) : (
                    // 팀원은 설계 초안을 만들 수 없어 끝까지 풀리지 않는 버튼이라 팀장에게만 보인다.
                    isLeader && (
                      <Button variant="secondary" disabled>
                        <Lock />
                        설계 초안 만들기
                      </Button>
                    )
                  )}
                </div>
              )}
            </>
          )}
        </div>
        {/* 상세가 길면 이 칸만 스크롤한다. 문서 머리 줄은 이 칸 위에 붙는다. */}
        <div
          className={cn(
            'min-w-0 rounded-xl bg-card p-5 ring-1 ring-foreground/10 lg:max-h-full lg:overflow-y-auto',
            SPLIT_DETAIL,
          )}
        >
          {right}
        </div>
      </div>
      {toast}
    </section>
  )
}

const searchSchema = z.object({
  q: z.string().trim().min(1, '검색어를 입력해 주세요.'),
})

function SearchBar({
  value,
  onSearch,
}: {
  value: string
  onSearch: (q: string) => void
}) {
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm({ resolver: zodResolver(searchSchema), values: { q: value } })
  return (
    <form
      role="search"
      noValidate
      className="grid gap-1"
      onSubmit={handleSubmit((form) => onSearch(form.q))}
    >
      <div className="flex gap-2">
        <Input
          aria-label="검색어"
          placeholder="이름·내용으로 검색"
          aria-invalid={!!errors.q}
          {...register('q')}
        />
        <Button type="submit" variant="outline" size="icon" aria-label="검색">
          <Search />
        </Button>
      </div>
      {errors.q && (
        <p role="alert" className="text-xs text-destructive">
          {errors.q.message}
        </p>
      )}
    </form>
  )
}

// 검색 방식을 고르지 않고 키워드·의미 검색을 함께 불러 두 묶음으로 보여 준다(F-24 수정안).
function SearchResults({
  projectId,
  query,
  onClear,
  onOpen,
}: {
  projectId: string
  query: string
  onClear: () => void
  onOpen: (documentId: string) => void
}) {
  // 두 검색 모두 페이지로 온다. has_more면 그 묶음 아래 '더 보기'로 다음 페이지를 잇는다(PR #72 3차 조사 THIRD-DOC-02).
  const search = (mode: 'KEYWORD' | 'SEMANTIC') => ({
    queryKey: ['projects', projectId, 'document-search', query, mode],
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }: { pageParam: string | undefined }) =>
      documentsApi.search(projectId, { q: query, mode, cursor: pageParam }),
    getNextPageParam: (page: { has_more: boolean; next_cursor?: string }) =>
      page.has_more ? page.next_cursor : undefined,
  })
  const keyword = useInfiniteQuery(search('KEYWORD'))
  const semantic = useInfiniteQuery(search('SEMANTIC'))
  const words = keyword.data?.pages.flatMap((page) => page.items) ?? []
  const similar = (
    semantic.data?.pages.flatMap((page) => page.items) ?? []
  ).filter(
    (hit) =>
      !words.some((word) => word.id === hit.id && word.type === hit.type),
  )
  const groups = [
    ['단어가 들어간 문서', words, keyword],
    ['뜻이 비슷한 문서', similar, semantic],
  ] as const
  return (
    <section aria-labelledby="search-title" className="grid gap-3">
      <div className="flex flex-wrap items-center justify-between gap-x-2">
        <h3 id="search-title" className="text-sm font-semibold">
          ‘{query}’ 검색 결과 {words.length + similar.length}건
          {keyword.hasNextPage || semantic.hasNextPage ? ' 이상' : ''}
        </h3>
        <Button variant="ghost" size="sm" onClick={onClear}>
          검색 지우기
        </Button>
      </div>
      <AsyncState query={keyword}>
        {() =>
          words.length + similar.length || semantic.hasNextPage ? (
            <div className="grid gap-4">
              {groups.map(
                ([title, hits, pages]) =>
                  (hits.length > 0 || pages.hasNextPage) && (
                    <div key={title} className="grid gap-2">
                      <h4 className="text-sub font-medium text-muted-foreground">
                        {title}
                      </h4>
                      <ul className="grid gap-1.5">
                        {hits.map((hit) => (
                          <li key={`${hit.type}:${hit.id}`}>
                            <button
                              type="button"
                              disabled={!hit.source}
                              className={cn(
                                'grid gap-1 text-sm',
                                LIST_ROW,
                                'hover:bg-muted/40',
                              )}
                              onClick={() =>
                                hit.source && onOpen(hit.source.document_id)
                              }
                            >
                              <strong className="font-semibold">
                                {hit.title}
                              </strong>
                              <span className="line-clamp-3 text-sub whitespace-pre-wrap">
                                {hit.snippet}
                              </span>
                              <small className="text-caption text-muted-foreground">
                                {hit.source
                                  ? `v${hit.source.version_no} · ${hit.source.location}`
                                  : '문서 출처 없이 승인된 설계 항목'}
                              </small>
                            </button>
                          </li>
                        ))}
                      </ul>
                      {pages.hasNextPage && (
                        <Button
                          variant="outline"
                          size="sm"
                          className="justify-self-start"
                          aria-label={`${title} 더 보기`}
                          disabled={pages.isFetchingNextPage}
                          onClick={() => void pages.fetchNextPage()}
                        >
                          더 보기
                        </Button>
                      )}
                    </div>
                  ),
              )}
              <p className="text-caption text-muted-foreground">
                단어가 들어간 문서를 먼저, 뜻이 비슷한 문서를 그 아래에 함께
                보여 줘요.
              </p>
            </div>
          ) : (
            <EmptyState>
              조건에 맞는 문서가 없어요. 다른 검색어로 찾아 보세요.
            </EmptyState>
          )
        }
      </AsyncState>
    </section>
  )
}

const MAX_FILE_BYTES = 1024 * 1024
const uploadSchema = z.object({
  file: z
    .instanceof(File, { error: '등록할 파일을 선택해 주세요.' })
    .refine((file) => /\.md$/i.test(file.name), {
      error: '지원하지 않는 형식이에요. Markdown(.md) 파일만 올릴 수 있어요.',
    })
    .refine((file) => file.size <= MAX_FILE_BYTES, {
      error: '파일이 너무 커요. 1 MiB 이하 Markdown 파일만 올릴 수 있어요.',
    })
    // 서버는 0바이트이거나 공백(BOM 포함)뿐인 파일을 400으로 거절한다. 너무 큰 파일은 읽지 않는다.
    .refine(
      async (file) =>
        file.size > MAX_FILE_BYTES || (await file.text()).trim() !== '',
      {
        error:
          '빈 파일은 올릴 수 없어요. 내용이 있는 Markdown(.md) 파일을 골라 주세요.',
      },
    ),
  title: z.string().trim().min(1, '문서 제목을 입력해 주세요.'),
  domain: z.string(),
})
type UploadValues = z.infer<typeof uploadSchema>
const reviewSteps = ['파일 등록', '비비 검토', '수정 제안 반영', '팀장 승인']
// 서버가 거절한 이유를 등록 화면 문구로(DOC-A01 오류). 파일 문제는 파일 줄 아래, 나머지는 아래 줄에 보인다.
const uploadFailure: Record<string, { file?: true; text: string }> = {
  unsupported_media_type: {
    file: true,
    text: '지원하지 않는 형식이에요. Markdown(.md) 파일만 올릴 수 있어요.',
  },
  file_too_large: {
    file: true,
    text: '파일이 너무 커요. 1 MiB 이하 Markdown 파일만 올릴 수 있어요.',
  },
  // 서버는 UTF-8이 아니거나 비어 있는 파일을 400으로 거절한다.
  validation_failed: {
    file: true,
    text: '파일을 읽지 못했어요. 비어 있지 않은 UTF-8 Markdown(.md) 파일인지 확인해 주세요.',
  },
  document_kind_exists: {
    text: '이 종류의 문서는 이미 있어요. 공용 문서함에서 그 칸을 눌러 열어 주세요.',
  },
  change_proposal_required: {
    text: '기준 v1이 생긴 뒤에는 문서를 새로 올리지 않아요. 변경 제안으로 요청해 주세요.',
  },
}

// 문서 등록 (F-19, PNL-02 모달안) · 같은 창이 등록 화면으로 바뀐다.
// 공용 문서함 시작 칸이나 시작 가이드의 '올리기'로만 열고, 누른 칸의 종류로 유형을 고정한다(10-29 사용자 코멘트).
// 등록하면 바로 ① 비비 검토로 잇는다.
function UploadDocument({
  projectId,
  kind,
  replace,
  onCancel,
  onDone,
}: {
  projectId: string
  kind: Kind
  /** 이미 등록된 문서의 파일을 바꿔 올리는 모드. 지금 등록된 파일을 선택된 채로 보인다(S15P31A108-382). */
  replace?: {
    id: string
    title: string
    domain?: string
    fileName: string
    content: string
    revision: number
  }
  onCancel: () => void
  onDone: (id: string, title: string) => void
}) {
  const openPane = useWorkspaceStore((state) => state.openPane)
  const action = useProjectAction(projectId)
  const [failure, setFailure] = useState<string>()
  const [proposalRequired, setProposalRequired] = useState(false)
  const label = statusLabel(kind)
  // 지금 등록된 파일. 같은 파일 그대로는 다시 올릴 이유가 없어 새 파일을 고르기 전에는 올릴 수 없다.
  const [registered] = useState(() =>
    replace
      ? new File([replace.content], replace.fileName || `${label}.md`, {
          type: 'text/markdown',
        })
      : undefined,
  )
  const {
    register,
    handleSubmit,
    control,
    setError,
    trigger,
    formState: { errors, isSubmitting },
  } = useForm<UploadValues>({
    resolver: zodResolver(uploadSchema),
    defaultValues: {
      title: replace?.title ?? label,
      domain: replace?.domain ?? '',
      file: registered,
    },
  })
  const pickedFile = useWatch({ control, name: 'file' })
  const unchanged = !!registered && pickedFile === registered
  const submit = handleSubmit(async (values) => {
    setFailure(undefined)
    setProposalRequired(false)
    try {
      let documentId: string | undefined
      await action.run(
        async () => {
          if (replace) {
            await documentsApi.reuploadWorkingCopy(projectId, replace.id, {
              expected_revision: replace.revision,
              file: values.file,
            })
            documentId = replace.id
            return
          }
          const result = await documentsApi.upload(projectId, {
            ...values,
            kind,
            domain: values.domain.trim() || undefined,
          })
          documentId = result.document.id
        },
        replace ? '파일을 다시 올렸어요.' : '문서를 등록했어요.',
      )
      if (documentId) onDone(documentId, values.title.trim())
    } catch (cause) {
      // 고른 파일과 입력값을 그대로 두고 원인을 알린다.
      const code = getApiErrorCode(cause)
      const known = code ? uploadFailure[code] : undefined
      if (known?.file) setError('file', { message: known.text })
      else
        setFailure(
          `등록하지 못했어요 · ${known?.text ?? getApiErrorMessage(cause)}`,
        )
      setProposalRequired(code === 'change_proposal_required')
    }
  })
  return (
    <form
      className="grid gap-5"
      noValidate
      onSubmit={(event) => void submit(event)}
    >
      <div className="flex items-center gap-2">
        <Button
          type="button"
          variant="ghost"
          size="icon-sm"
          aria-label="이전 화면으로 돌아가기"
          disabled={isSubmitting}
          onClick={onCancel}
        >
          <ChevronLeft />
        </Button>
        <div>
          <h3 className="font-semibold">
            {label} {replace ? '파일 다시 올리기' : '등록'}
          </h3>
          <p className="text-xs text-muted-foreground">
            {replace
              ? `유형 ${label} · 새 파일을 올리면 비비가 처음부터 다시 검토해요`
              : `필수 문서 · 유형 ${label} · 등록 → 비비 검토 → 팀장 승인`}
          </p>
        </div>
      </div>
      <ol aria-label="문서 등록 단계" className="flex flex-wrap gap-2 text-xs">
        {reviewSteps.map((step, index) => (
          <li
            key={step}
            aria-current={index === 0 ? 'step' : undefined}
            className={cn(
              'flex items-center gap-1.5 rounded-full px-2.5 py-1',
              index === 0
                ? 'bg-primary font-semibold text-primary-foreground'
                : 'bg-muted text-muted-foreground',
            )}
          >
            {index + 1} {step}
          </li>
        ))}
      </ol>
      {/* 입력칸은 창 폭을 따라 늘어나고, 넓은 화면(lg 이상)에서는 두 칸으로 놓는다: 왼쪽 문서 유형·원본 파일,
          오른쪽 제목·담당 영역·안내. 좁은 화면은 한 칸이다(사용자 요청 11-02, 등록 화면 보드는 없음). */}
      <div className="grid w-full gap-4 lg:grid-cols-2 lg:items-start lg:gap-6">
        <div className="grid gap-4">
          <div className="flex flex-wrap items-center gap-x-3 gap-y-1 rounded-xl bg-muted/50 px-4 py-3 text-sm">
            <span className="text-muted-foreground">문서 유형</span>
            <span className="font-semibold">{label}</span>
            <span className="basis-full text-xs text-muted-foreground">
              공용 문서함에서 누른 칸의 종류라 바꿀 수 없어요
            </span>
          </div>
          <Controller
            name="file"
            control={control}
            render={({ field }) => {
              const pick = (file?: File) => {
                field.onChange(file)
                void trigger('file')
                setFailure(undefined)
              }
              return (
                <div className="grid gap-2">
                  <Label htmlFor="document-file">원본 파일 *</Label>
                  <div
                    className={cn(
                      'grid justify-items-center gap-1 rounded-xl border-[1.5px] border-dashed px-4 py-6 text-center text-sm',
                      errors.file && 'border-destructive',
                    )}
                    onDragOver={(event) => event.preventDefault()}
                    onDrop={(event) => {
                      event.preventDefault()
                      if (!isSubmitting) pick(event.dataTransfer.files[0])
                    }}
                  >
                    <Upload className="size-5 text-muted-foreground" />
                    <p>
                      파일을 여기로 끌어 놓거나{' '}
                      <label
                        htmlFor="document-file"
                        className="cursor-pointer font-semibold underline"
                      >
                        파일 선택
                      </label>
                    </p>
                    <input
                      id="document-file"
                      type="file"
                      accept=".md"
                      className="sr-only"
                      name={field.name}
                      ref={field.ref}
                      onBlur={field.onBlur}
                      disabled={isSubmitting}
                      aria-invalid={!!errors.file}
                      onChange={(event) => {
                        pick(event.target.files?.[0])
                        event.target.value = ''
                      }}
                    />
                    <p className="text-xs text-muted-foreground">
                      지원 형식: Markdown (.md) · 1 MiB 이하
                    </p>
                  </div>
                  {field.value && (
                    <div
                      className={cn(
                        'flex items-center gap-2 rounded-lg border px-3 py-2 text-sm',
                        errors.file && 'border-destructive',
                      )}
                    >
                      <FileText className="size-4 shrink-0 text-muted-foreground" />
                      <span className="min-w-0 flex-1 truncate">
                        {field.value.name}
                      </span>
                      {!errors.file && (
                        <span className="text-xs text-green-700">
                          {field.value === registered
                            ? '지금 등록된 파일'
                            : '선택됨'}
                        </span>
                      )}
                      <Button
                        type="button"
                        variant="ghost"
                        size="icon-sm"
                        aria-label="선택한 파일 빼기"
                        disabled={isSubmitting}
                        onClick={() => pick(undefined)}
                      >
                        <X />
                      </Button>
                    </div>
                  )}
                  {errors.file && (
                    <p className="text-sm text-destructive" role="alert">
                      {errors.file.message}
                    </p>
                  )}
                </div>
              )
            }}
          />
        </div>
        <div className="grid gap-4">
          <div className="grid gap-2">
            <Label htmlFor="document-title">문서 제목 *</Label>
            <Input
              id="document-title"
              {...register('title')}
              disabled={isSubmitting || !!replace}
              aria-invalid={!!errors.title}
            />
            {errors.title && (
              <p className="text-sm text-destructive" role="alert">
                {errors.title.message}
              </p>
            )}
          </div>
          <div className="grid gap-2">
            <Label htmlFor="document-domain">담당 도메인 (선택)</Label>
            <Input
              id="document-domain"
              placeholder="예: 인증"
              {...register('domain')}
              disabled={isSubmitting || !!replace}
            />
            <p className="text-xs text-muted-foreground">
              목록과 검색에서 담당 도메인별로 모아 볼 수 있어요.
            </p>
          </div>
          <p className="rounded-xl bg-muted/50 px-4 py-3 text-xs text-muted-foreground">
            {replace
              ? '제목과 담당 영역은 그대로예요. 파일만 바꿔 올리면 비비가 처음부터 다시 검토해요.'
              : '등록하면 비비가 먼저 검토하고, 팀장이 승인해요.'}
          </p>
        </div>
      </div>
      {failure && (
        <div
          role="alert"
          className="flex flex-wrap items-center gap-2 rounded-lg bg-destructive/5 p-3 text-sm text-destructive"
        >
          {failure}
          {proposalRequired && (
            <Button
              type="button"
              size="sm"
              variant="outline"
              onClick={() => openPane('changes', 'doc:')}
            >
              변경 제안 쓰기
            </Button>
          )}
        </div>
      )}
      <div className="flex flex-wrap items-center justify-end gap-2 border-t pt-4">
        <Button
          type="button"
          variant="outline"
          disabled={isSubmitting}
          onClick={onCancel}
        >
          취소
        </Button>
        <Button
          type="submit"
          disabled={isSubmitting || !!errors.file || unchanged}
        >
          {isSubmitting
            ? replace
              ? '올리는 중…'
              : '등록 중…'
            : replace
              ? '다시 올리고 비비 검토 받기'
              : '등록하고 비비 검토 받기'}
        </Button>
      </div>
    </form>
  )
}

// 기준 vN 문서(DOC-A10): 그 기준에 든 문서와 버전. 누르면 그 버전으로 문서를 연다.
function BaselineDocuments({
  projectId,
  versionNo,
  titleOf,
  onOpen,
}: {
  projectId: string
  versionNo: number
  titleOf: (documentId: string) => string
  onOpen: (documentId: string, versionId: string) => void
}) {
  const baseline = useQuery({
    queryKey: ['projects', projectId, 'baseline-version', versionNo],
    queryFn: () => documentsApi.baselineVersion(projectId, versionNo),
  })
  return (
    <section aria-labelledby="baseline-title" className="grid gap-3">
      <h3 id="baseline-title" className="text-lg font-bold">
        기준 v{versionNo} 문서
      </h3>
      <p className="text-sm text-muted-foreground">
        이 기준을 발행할 때의 문서예요. 지금 승인본과 다를 수 있어요.
      </p>
      <AsyncState query={baseline}>
        {(data) => (
          <ul className="grid gap-2">
            {data.documents.map((document) => (
              <li key={document.document_id}>
                <button
                  type="button"
                  className="flex w-full items-center justify-between rounded-xl border px-4 py-3 text-left text-sm hover:bg-muted/50"
                  onClick={() =>
                    onOpen(document.document_id, document.document_version_id)
                  }
                >
                  <strong className="font-semibold">
                    {titleOf(document.document_id)}
                  </strong>
                  <span className="text-xs text-muted-foreground">
                    기준 v{versionNo}의 버전 보기 ›
                  </span>
                </button>
              </li>
            ))}
          </ul>
        )}
      </AsyncState>
    </section>
  )
}

// 문서 상세 (F-20) · 보기 전용. 탭 두 개: 문서 내용 · 버전 이력. 버전 비교는 팀장 승인 화면의 '변경 내용'에서 본다.
// versionId를 주면(기준 vN 문서나 근거에서 열면) 그 버전부터 보인다.
function DocumentDetail({
  projectId,
  documentId,
  versionId,
  citation,
  started,
  onEdit,
}: {
  projectId: string
  documentId: string
  versionId?: string
  citation?: CitationTarget
  started: boolean
  onEdit: () => void
}) {
  const action = useProjectAction(projectId)
  const openPane = useWorkspaceStore((state) => state.openPane)
  const members = useQuery(membersQuery(projectId))
  // 올린 계정 이름. 구성원 목록이 온 뒤에도 못 찾으면(나간 사람 등) 다른 패널처럼 '팀원'으로 보인다.
  const uploaderName = (version: DocumentVersion) =>
    version.created_by && members.data
      ? (members.data.items.find(
          (item) => item.account_id === version.created_by,
        )?.name ?? '팀원')
      : undefined
  const [picked, setVersionNo] = useState<number>()
  const [tab, setTab] = useRemembered<'content' | 'history'>(
    `documents.${documentId}.tab`,
    'content',
  )
  const [classification, setClassification] = useState(false)
  // 분류 수정(DOC-A07)은 서버가 문서를 등록한 사람만 허용하고, 팀장 검토를 요청한 동안(SUBMITTED)은 409 submission_locked다.
  // 작성 건의 owner_account_id로 등록한 사람에게만 버튼을 보인다(PR #72 4차 조사 FOURTH-DOC-01).
  const session = useSession()
  const copy = useQuery({
    queryKey: ['projects', projectId, 'working-copy', documentId],
    queryFn: () => documentsApi.workingCopy(projectId, documentId),
    retry: false,
  })
  const author =
    !!session.data && copy.data?.owner_account_id === session.data.id
  const locked = copy.data?.status === 'SUBMITTED'
  // 버전 이력은 마지막 페이지까지 읽어 v1까지 고르고 내려받게 한다(PR #72 재전수 ROUND2-DOC-03).
  const versions = useQuery({
    queryKey: ['projects', projectId, 'document-versions', documentId],
    queryFn: () =>
      readAllPages((cursor) =>
        documentsApi.versions(projectId, documentId, { cursor, limit: 100 }),
      ),
  })
  const pinned = versionId
    ? versions.data?.items.find((item) => item.id === versionId)?.version_no
    : undefined
  const versionNo = picked ?? pinned
  // 버전을 지정해 열 때는 이력에서 그 버전 번호를 찾은 뒤에만 부른다.
  // 이력 조회가 실패하거나 그 버전이 없을 때 번호 없이 부르면 승인본이 열린다(PR #218 리뷰).
  const unresolved = !!versionId && versionNo === undefined
  const document = useQuery({
    queryKey: ['projects', projectId, 'document', documentId, versionNo],
    queryFn: () =>
      documentsApi.get(projectId, documentId, { version_no: versionNo }),
    enabled: !unresolved,
    // 다른 버전을 고르는 동안 머리 줄(탭·버전 고르기)이 사라졌다 나타나지 않게 보던 버전을 둔다.
    placeholderData: (previous) => previous,
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
  const tabClass = (on: boolean) =>
    cn(
      '-mb-px h-10 border-b-2 px-0.5 text-sm',
      on
        ? 'border-primary font-semibold'
        : 'border-transparent text-muted-foreground hover:text-foreground',
    )
  // 문서 머리(제목·버튼 줄과 탭·버전 줄)는 본문 위에 붙여 두고 본문은 상세 칸의 스크롤 하나로 읽는다(디자인 검수 F04).
  // 상세 칸의 안쪽 여백(p-5)만큼 올려 붙여, 문서를 내려도 칸 맨 위에 제목이 남는다.
  // 원본 다운로드·분류 수정은 자주 쓰지 않아 ⋯ 메뉴에 둔다(S15P31A108-445).
  const shown = unresolved ? undefined : document.data
  const header = (
    <div className="sticky -top-5 z-10 -mx-5 -mt-5 grid gap-2 border-b bg-card px-5 pt-5">
      {shown && (
        <div className="flex flex-wrap items-center gap-x-3 gap-y-2">
          <div
            className="min-w-0 flex-[1_1_12rem]"
            title={`${shown.document.title} v${shown.version.version_no}`}
          >
            <h3 className="truncate text-base leading-6 font-semibold">
              {shown.document.title} v{shown.version.version_no}
            </h3>
            <p className="truncate text-caption text-muted-foreground">
              {shown.document.title !== statusLabel(shown.document.kind) &&
                `${statusLabel(shown.document.kind)} · `}
              담당 {shown.document.domain || '공통'} ·{' '}
              {shown.document.approved_version
                ? `현재 기준 v${shown.document.approved_version.version_no}`
                : '승인 전'}{' '}
              · {stamp(shown.version.created_at)}
            </p>
          </div>
          <div className="ml-auto flex shrink-0 gap-2">
            {started && (
              <Button size="sm" onClick={onEdit}>
                수정 제안하기
              </Button>
            )}
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button
                  variant="outline"
                  size="icon-sm"
                  aria-label="문서 메뉴"
                  disabled={action.busy}
                >
                  <Ellipsis />
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end">
                <DropdownMenuItem
                  onSelect={() =>
                    void download(shown.version).catch(() => undefined)
                  }
                >
                  <Download />
                  원본 다운로드
                </DropdownMenuItem>
                {author && (
                  <DropdownMenuItem
                    disabled={locked}
                    onSelect={() => setClassification(true)}
                  >
                    분류 수정
                    {locked && (
                      <span className="text-caption text-muted-foreground">
                        검토 요청 중에는 못 고쳐요
                      </span>
                    )}
                  </DropdownMenuItem>
                )}
              </DropdownMenuContent>
            </DropdownMenu>
          </div>
        </div>
      )}
      {shown && (
        <div className="flex flex-wrap items-end justify-between gap-x-4 gap-y-1">
          <div role="tablist" aria-label="문서 보기" className="flex gap-5">
            <button
              type="button"
              role="tab"
              aria-selected={tab === 'content'}
              className={tabClass(tab === 'content')}
              onClick={() => setTab('content')}
            >
              문서 내용
            </button>
            <button
              type="button"
              role="tab"
              aria-selected={tab === 'history'}
              className={tabClass(tab === 'history')}
              onClick={() => setTab('history')}
            >
              버전 이력 {versions.data?.items.length ?? ''}
            </button>
          </div>
          {tab === 'content' && (
            <div className="pb-1.5">
              <NativeSelect
                size="sm"
                aria-label="열람할 버전"
                value={String(shown.version.version_no)}
                onChange={(event) => setVersionNo(Number(event.target.value))}
              >
                {(versions.data?.items ?? [shown.version]).map((version) => (
                  <NativeSelectOption
                    key={version.id}
                    value={String(version.version_no)}
                  >
                    v{version.version_no} · {versionStatusLabel(version.status)}
                    {version.id === shown.document.approved_version?.id
                      ? ' (기준)'
                      : ''}
                  </NativeSelectOption>
                ))}
              </NativeSelect>
            </div>
          )}
        </div>
      )}
    </div>
  )
  // 문서 본문은 한 줄 40자 안팎(640px, MarkdownView)으로 읽고, 머리 줄은 상세 칸 폭을 다 쓴다.
  if (unresolved)
    // 이력을 읽는 중이면 불러오는 중, 실패하면 오류와 다시 불러오기를 AsyncState가 보인다.
    return (
      <div className="grid min-w-0 gap-4">
        {header}
        {versions.isSuccess ? (
          <p
            role="alert"
            className="rounded-xl border border-destructive/20 bg-destructive/5 p-5 text-sm"
          >
            열려던 버전을 버전 이력에서 찾을 수 없어요. 지워졌거나 볼 수 없는
            버전이에요. 왼쪽 목록에서 문서를 다시 열어 주세요.
          </p>
        ) : (
          <AsyncState query={versions}>{() => null}</AsyncState>
        )}
      </div>
    )
  return (
    <div className="grid min-w-0 gap-4">
      {header}
      <AsyncState query={document}>
        {(data) => {
          const base = data.document.approved_version
          return (
            <div className="grid min-w-0 gap-4">
              {!started && (
                <p className="text-sub text-muted-foreground">
                  승인된 버전은 바뀌지 않아요. 고칠 내용은 새 .md를 올려 비비
                  검토와 팀장 승인을 거쳐요.
                </p>
              )}
              {base && base.id !== data.version.id && (
                <div className="flex flex-wrap items-center justify-between gap-2 rounded-xl bg-muted p-3 text-sm">
                  v{data.version.version_no}를 보고 있어요. 팀의 기준은 v
                  {base.version_no}예요.
                  <Button
                    size="sm"
                    variant="outline"
                    onClick={() => setVersionNo(base.version_no)}
                  >
                    기준 v{base.version_no}로 돌아가기
                  </Button>
                </div>
              )}
              {tab === 'content' ? (
                <div className="grid gap-3">
                  {citation &&
                  data.version.id === citation.document_version_id ? (
                    <VerifiedCitation
                      content={data.version.content}
                      target={citation}
                    />
                  ) : (
                    <>
                      {citation && (
                        <p className="text-xs text-muted-foreground">
                          인용한 버전이 아니므로 인용 위치를 표시하지 않아요.
                        </p>
                      )}
                      {data.version.content !== undefined ? (
                        <MarkdownView
                          source={data.version.content}
                          className="overflow-visible rounded-none bg-transparent px-0 pt-1 pb-0"
                        />
                      ) : (
                        <EmptyState>
                          이 파일은 본문 미리보기를 제공하지 않아요. 원본 파일을
                          내려받아 확인해 주세요.
                        </EmptyState>
                      )}
                    </>
                  )}
                </div>
              ) : (
                <AsyncState query={versions}>
                  {(history) => (
                    <ul aria-label="버전 이력" className="divide-y">
                      {history.items.map((version) => (
                        <li
                          key={version.id}
                          className="flex flex-wrap items-center justify-between gap-3 py-3 text-sm"
                        >
                          <span className="flex flex-wrap items-center gap-2">
                            <strong>v{version.version_no}</strong>
                            <Badge variant={versionTone[version.status]}>
                              {versionStatusLabel(version.status)}
                            </Badge>
                            {(version.is_current_baseline === true ||
                              (!history.items.some(
                                (item) => item.is_current_baseline,
                              ) &&
                                version.id === base?.id)) && (
                              <Badge variant="outline">현재 기준</Badge>
                            )}
                            <span className="text-caption text-muted-foreground">
                              {stamp(version.created_at)}
                              {uploaderName(version) &&
                                ` · ${uploaderName(version)}`}
                            </span>
                          </span>
                          <span className="flex flex-wrap gap-2">
                            {version.change_proposal_id && (
                              <Button
                                size="sm"
                                variant="outline"
                                onClick={() =>
                                  openPane(
                                    'changes',
                                    version.change_proposal_id,
                                  )
                                }
                              >
                                변경 제안 보기
                              </Button>
                            )}
                            <Button
                              size="sm"
                              variant="outline"
                              onClick={() => {
                                setVersionNo(version.version_no)
                                setTab('content')
                              }}
                            >
                              보기
                            </Button>
                          </span>
                        </li>
                      ))}
                    </ul>
                  )}
                </AsyncState>
              )}
              {action.error && (
                <p className="text-sm text-destructive" role="alert">
                  {action.error}
                </p>
              )}
              {classification && (
                <FormDialog
                  title="문서 분류 수정"
                  fields={[
                    {
                      name: 'domain',
                      label: '담당 도메인',
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
                      '담당 도메인을 저장했어요.',
                    )
                    setClassification(false)
                  }}
                />
              )}
            </div>
          )
        }}
      </AsyncState>
    </div>
  )
}
