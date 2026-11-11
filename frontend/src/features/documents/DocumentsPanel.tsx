import { useState } from 'react'
import type { ReactNode } from 'react'
import { useInfiniteQuery, useQueries, useQuery } from '@tanstack/react-query'
import { Controller, useForm, useWatch } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import {
  ChevronLeft,
  Code,
  Database,
  Download,
  FileText,
  ListChecks,
  Lock,
  Play,
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
import { Toast } from '@/components/Toast'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { NativeSelect, NativeSelectOption } from '@/components/ui/native-select'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import { statusLabel } from '@/lib/display'
import { cn } from '@/lib/utils'
import { parseCitationFocus, type CitationTarget } from '@/lib/citationTarget'
import { useProjectStart } from '@/features/projects/useProjectStart'
import { useProjectAction } from '@/features/workspace/useProjectAction'
import { useSession } from '@/features/auth/useSession'
import { membersQuery } from '@/features/members/membersQuery'
import type { DocumentVersion } from '@/api/contracts/common'
import { DocumentReview } from './DocumentReview'
import { reviewStage } from './reviewStage'
import { VerifiedCitation } from './VerifiedCitation'

// 공용 문서함 수정안(PNL-02 모달안 + 10-29 프로젝트 시작 보드): 목록 · 상세 · 등록 · 검토를 같은 창 안에서 바꿔 보여 준다.
// 시작 전에는 필수 문서 4종 칸과 '프로젝트 시작'(PLN-A10), 시작 뒤에는 현재 문서만 두고 문서 수정은 변경 제안으로 보낸다.
const KINDS = ['PLAN', 'REQUIREMENTS', 'ERD', 'API_SPEC'] as const
type Kind = (typeof KINDS)[number]
const kindIcon = {
  PLAN: [FileText, 'bg-amber-100 text-amber-800'],
  REQUIREMENTS: [ListChecks, 'bg-blue-100 text-blue-800'],
  ERD: [Database, 'bg-teal-100 text-teal-800'],
  API_SPEC: [Code, 'bg-violet-100 text-violet-800'],
} as const
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

// 시작 준비 카드: 마지막 비비 검토가 실패한 문서의 다음 할 일. 실패를 '비비가 검토하고 있어요'로 보여
// 끝없이 기다리게 했다(S15P31A108-383). 다시 검토는 올린 사람만, 등록 취소는 올린 사람과 팀장이 한다(DOC-A25).
function failedReviewHint(rejected: boolean, owner: boolean, leader: boolean) {
  const what = rejected
    ? '반려 뒤 비비 검토를 마치지 못했어요.'
    : '비비 검토를 마치지 못했어요.'
  if (owner) return `${what} 열어서 다시 검토해 주세요`
  if (leader)
    return `${what} 올린 사람이 다시 검토하거나, 열어서 등록을 취소할 수 있어요`
  return `${what} 올린 사람이 다시 검토해요`
}

function KindTile({ kind }: { kind: string }) {
  const [Icon, color] = kindIcon[kind as Kind] ?? kindIcon.PLAN
  return (
    <span
      aria-hidden="true"
      className={cn(
        'grid size-9 shrink-0 place-items-center rounded-lg',
        color,
      )}
    >
      <Icon className="size-4" />
    </span>
  )
}

function Chip({ tone, children }: { tone: string; children: ReactNode }) {
  return (
    <span
      className={cn(
        'inline-flex h-6 shrink-0 items-center rounded-full px-2.5 text-xs font-semibold whitespace-nowrap',
        {
          muted: 'bg-muted text-muted-foreground',
          blue: 'bg-blue-100 text-blue-800',
          amber: 'bg-amber-100 text-amber-800',
          orange: 'bg-orange-100 text-orange-800',
          red: 'bg-red-100 text-red-800',
          green: 'bg-green-100 text-green-800',
        }[tone],
      )}
    >
      {children}
    </span>
  )
}

// 버전 이력의 상태 칩 색. 승인 = 현재 기준 후보, 초안 = 아직 쓸 수 있는 후보, 이전 버전 = 대체된 승인본,
// 미채택(REJECTED) = 채택되지 않았고 다시 쓸 수 없는 것(제안을 거뒀거나, 다시 제출했거나, 다른 제안이 먼저 발행됨,
// 문서함 반려). 잘못이 아니라 결과라 이전 버전과 같은 회색 계열로 낮춘다.
const versionTone: Record<DocumentVersion['status'], string> = {
  APPROVED: 'green',
  DRAFT: 'orange',
  SUPERSEDED: 'muted',
  REJECTED: 'muted',
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

export function DocumentsPanel({
  projectId,
  isLeader,
}: {
  projectId: string
  isLeader: boolean
}) {
  const openPane = useWorkspaceStore((state) => state.openPane)
  const session = useSession()
  // 알림·근거·가이드에서 들어오면 그 문서(또는 'upload:종류' 등록 화면)를 바로 연다.
  const [view, setView] = useState<View>(() => {
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
    if (focus?.startsWith('upload:') && KINDS.includes(focus.slice(7) as Kind))
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
  })
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
  const start = useProjectStart(
    projectId,
    isLeader && !started && approvedCount === 4,
  )
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

  const back = (
    <Button
      variant="ghost"
      size="sm"
      className="justify-self-start"
      onClick={() => setView({ name: 'list' })}
    >
      <ChevronLeft />
      {started ? '현재 문서' : '공용 문서함'}
    </Button>
  )

  if (view.name === 'invalidCitation')
    return (
      <div className="grid gap-4">
        {back}
        <p role="alert">인용 위치가 올바르지 않아 문서를 열지 않았어요.</p>
      </div>
    )

  if (view.name === 'reupload')
    return (
      <UploadDocument
        projectId={projectId}
        kind={view.kind}
        replace={view}
        onCancel={() => setView({ name: 'review', id: view.id })}
        onDone={(id, title) => {
          // 파일을 바꿔 올리면 리비전이 올라 비비 검토가 처음부터 다시 시작한다.
          setView({ name: 'review', id })
          setNotice({
            title: '파일을 다시 올렸어요',
            text: `${title} · 비비가 다시 검토하고 있어요. 창을 닫아도 검토는 이어져요.`,
          })
        }}
      />
    )
  if (view.name === 'upload')
    return (
      <UploadDocument
        projectId={projectId}
        kind={view.kind}
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
    )
  if (view.name === 'review') {
    const document = docs.find((item) => item.id === view.id)
    return (
      <div className="grid gap-4">
        {toast}
        {back}
        <DocumentReview
          key={view.id}
          projectId={projectId}
          documentId={view.id}
          title={document?.title ?? ''}
          kind={document?.kind ?? 'PLAN'}
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
          onReupload={(current) =>
            setView({
              name: 'reupload',
              id: view.id,
              kind: (document?.kind ?? 'PLAN') as Kind,
              title: document?.title ?? '',
              domain: document?.domain,
              ...current,
            })
          }
        />
      </div>
    )
  }
  if (view.name === 'baseline')
    return (
      <div className="grid gap-4">
        {back}
        <BaselineDocuments
          projectId={projectId}
          versionNo={view.versionNo}
          titleOf={(id) => docs.find((item) => item.id === id)?.title ?? '문서'}
          onOpen={(id, versionId) => setView({ name: 'detail', id, versionId })}
        />
      </div>
    )
  if (view.name === 'detail')
    return (
      <div className="grid gap-4">
        {back}
        <DocumentDetail
          key={view.id}
          projectId={projectId}
          documentId={view.id}
          versionId={view.versionId}
          citation={view.citation}
          started={started}
          onEdit={() => openPane('changes', `doc:${view.id}`)}
        />
      </div>
    )

  return (
    <div className="grid gap-4">
      {/* 머리 줄(상태 칩·설명·'변경 제안 쓰기')은 두지 않는다. 시작 전에는 필수 문서 빈 칸으로 등록하고,
          시작 뒤에는 문서를 열어 '수정 제안하기'로 간다(PNL-02-v2 10-29). 검색칸은 사용자 결정으로 둔다. */}
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
          {started ? null : (
            <section
              aria-labelledby="start-title"
              className="grid gap-3.5 rounded-2xl border p-5"
            >
              <div className="grid gap-2">
                <div className="flex flex-wrap items-baseline justify-between gap-2">
                  <h3 id="start-title" className="font-semibold">
                    프로젝트 시작 준비 · 필수 문서 4종
                  </h3>
                  <span className="text-sm font-semibold text-green-800">
                    팀장 승인 {approvedCount} / 4
                  </span>
                </div>
              </div>
              <ul className="grid gap-2.5 sm:grid-cols-2 lg:grid-cols-4">
                {slots.map(({ kind, document, copy, approved }) => {
                  const stage = copy && !approved ? reviewStage(copy) : null
                  const status = !document
                    ? (['muted', '아직 없음'] as const)
                    : approved
                      ? ([
                          'green',
                          `승인 v${document.approved_version!.version_no}`,
                        ] as const)
                      : stage?.stale
                        ? (['amber', stage.label] as const)
                        : stage?.failed
                          ? (['red', stage.label] as const)
                          : stage?.label === '팀장 승인 대기'
                            ? (['orange', stage.label] as const)
                            : stage?.label === '반려됨'
                              ? (['red', stage.label] as const)
                              : stage?.step === 2
                                ? (['amber', stage.label] as const)
                                : ([
                                    'blue',
                                    stage?.label ?? '비비 검토 중',
                                  ] as const)
                  const next = approved
                    ? '현재 문서로 들어갔어요'
                    : stage?.stale
                      ? '참고 문서가 바뀌었어요. 비비 검토를 다시 받아 주세요'
                      : stage?.failed && copy
                        ? failedReviewHint(
                            copy.status === 'REJECTED',
                            copy.owner_account_id === session.data?.id,
                            isLeader,
                          )
                        : stage?.label === '팀장 승인 대기'
                          ? isLeader
                            ? '승인 검토하기'
                            : '팀장 승인을 기다리고 있어요'
                          : stage?.label === '반려됨'
                            ? '반려 사유를 보고 다시 검토를 받아요'
                            : stage?.step === 2
                              ? '비비 제안을 확인하고 반영해 주세요'
                              : stage?.step === 3
                                ? '최종본을 확인하고 검토를 요청해요'
                                : '비비가 검토하고 있어요'
                  return (
                    <li
                      key={kind}
                      className={cn(
                        'relative grid gap-3 rounded-xl p-3.5',
                        document
                          ? 'border'
                          : 'border-[1.5px] border-dashed bg-muted/30 hover:bg-muted/60',
                      )}
                    >
                      <div className="flex min-w-0 items-center gap-2.5">
                        <KindTile kind={kind} />
                        <div className="min-w-0">
                          <p className="truncate font-semibold">
                            {statusLabel(kind)}
                          </p>
                          <p className="truncate text-xs text-muted-foreground">
                            {document
                              ? `${document.title} · 담당 ${document.domain || '공통'}`
                              : '아직 올리지 않았어요'}
                          </p>
                        </div>
                      </div>
                      <div className="flex items-center justify-between gap-2">
                        <Chip tone={status[0]}>{status[1]}</Chip>
                        {document ? (
                          <button
                            type="button"
                            className="text-right text-xs text-muted-foreground hover:underline"
                            onClick={() =>
                              setView(
                                approved
                                  ? { name: 'detail', id: document.id }
                                  : { name: 'review', id: document.id },
                              )
                            }
                          >
                            {next}
                          </button>
                        ) : (
                          <button
                            type="button"
                            aria-label={`${statusLabel(kind)} 올리기`}
                            className="text-sm font-semibold text-amber-800 after:absolute after:inset-0 after:content-[''] hover:underline"
                            onClick={() => setView({ name: 'upload', kind })}
                          >
                            올리기 ›
                          </button>
                        )}
                      </div>
                    </li>
                  )
                })}
              </ul>
              {approvedCount === 4 ? (
                <div className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-primary/50 bg-primary/10 p-4">
                  <div className="grid min-w-0 flex-1 gap-1">
                    <strong>
                      필수 문서 4종이 모두 승인됐어요. 이제 프로젝트를 시작할 수
                      있어요
                    </strong>
                    <span className="text-sm text-muted-foreground">
                      {isLeader
                        ? start.indexed
                          ? '시작하면 4종으로 기준 v1이 발행되고 변경 제안과 작업 현황이 열려요. 그 뒤 문서 수정은 모두 변경 제안으로 가요.'
                          : start.needsDrafts
                            ? '기준 v1에 넣을 설계 항목 초안이 아직 없어요. 기획 분석에서 초안을 만들거나 직접 추가하면 시작할 수 있어요.'
                            : '설계 항목 초안을 확인하는 중이에요. 끝나면 버튼이 켜져요.'
                        : '팀장이 프로젝트를 시작하면 알려 드려요.'}
                    </span>
                    {isLeader && start.needsDrafts && (
                      <Button
                        variant="outline"
                        size="sm"
                        className="justify-self-start"
                        onClick={() => openPane('planning')}
                      >
                        <Sparkles />
                        기획 분석에서 초안 만들기
                      </Button>
                    )}
                    {start.error && (
                      <span role="alert" className="text-sm text-destructive">
                        {start.error}
                      </span>
                    )}
                  </div>
                  {isLeader && (
                    <Button
                      disabled={!start.ready || start.busy}
                      onClick={() =>
                        void start
                          .start()
                          .then(() =>
                            setNotice({
                              title: '프로젝트를 시작했어요',
                              text: '기준 v1이 발행됐어요. 이제 문서 수정은 변경 제안으로 해요.',
                            }),
                          )
                          .catch(() => undefined)
                      }
                    >
                      <Play />
                      프로젝트 시작
                    </Button>
                  )}
                </div>
              ) : (
                <div className="flex flex-wrap items-center justify-end gap-3 border-t pt-3.5">
                  <Button variant="secondary" disabled>
                    <Lock />
                    프로젝트 시작
                  </Button>
                </div>
              )}
            </section>
          )}
          <section
            aria-labelledby="current-title"
            className="rounded-2xl border"
          >
            <div className="flex flex-wrap items-baseline gap-x-2 px-5 pt-4 pb-2.5">
              <h3 id="current-title" className="font-semibold">
                현재 문서
              </h3>
              <span className="text-sm text-muted-foreground">
                {current.length}개
              </span>
              {current.length > 0 && (
                <p className="w-full text-sm text-muted-foreground">
                  팀장이 승인해 지금 기준으로 쓰는 문서예요.
                </p>
              )}
            </div>
            <AsyncState query={documents}>
              {() =>
                current.length ? (
                  <ul>
                    {current.map((document) => (
                      <li key={document.id} className="border-t">
                        <button
                          type="button"
                          className="flex w-full flex-wrap items-center gap-x-4 gap-y-1 px-5 py-3 text-left hover:bg-muted/40"
                          onClick={() =>
                            setView({ name: 'detail', id: document.id })
                          }
                        >
                          <span className="flex min-w-0 flex-[999_1_240px] items-center gap-3">
                            <KindTile kind={document.kind} />
                            <span className="min-w-0">
                              <span className="block font-medium">
                                {document.title} v
                                {document.approved_version!.version_no}
                              </span>
                              <span className="block truncate text-xs text-muted-foreground">
                                담당 {document.domain || '공통'}
                              </span>
                            </span>
                          </span>
                          <span className="w-28 text-sm text-muted-foreground">
                            {statusLabel(document.kind)}
                          </span>
                          <Chip tone="green">승인 완료</Chip>
                          <span className="w-24 text-xs text-muted-foreground">
                            {day(document.approved_version!.created_at)}
                          </span>
                        </button>
                      </li>
                    ))}
                  </ul>
                ) : (
                  <div className="grid justify-items-center gap-1.5 border-t px-5 py-8 text-center">
                    <strong>아직 승인된 문서가 없어요</strong>
                    <span className="text-sm text-muted-foreground">
                      위의 필수 문서 4종부터 올려 주세요. 승인되면 여기에
                      들어와요.
                    </span>
                  </div>
                )
              }
            </AsyncState>
          </section>
        </>
      )}
      {toast}
    </div>
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
          placeholder="문서 이름이나 내용으로 검색"
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
      <div className="flex items-center justify-between gap-2">
        <h3 id="search-title" className="font-semibold">
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
                      <h4 className="text-sm font-medium text-muted-foreground">
                        {title}
                      </h4>
                      <ul className="grid gap-2">
                        {hits.map((hit) => (
                          <li key={`${hit.type}:${hit.id}`}>
                            <button
                              type="button"
                              disabled={!hit.source}
                              className="grid w-full gap-1 rounded-xl border p-3 text-left text-sm hover:bg-muted/40"
                              onClick={() =>
                                hit.source && onOpen(hit.source.document_id)
                              }
                            >
                              <strong>{hit.title}</strong>
                              <span className="leading-6 whitespace-pre-wrap">
                                {hit.snippet}
                              </span>
                              <small className="text-muted-foreground">
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
              <p className="text-xs text-muted-foreground">
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
    }),
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
            <span className="flex items-center gap-2 font-semibold">
              <KindTile kind={kind} />
              {label}
            </span>
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
            <Label htmlFor="document-domain">담당 영역 (선택)</Label>
            <Input
              id="document-domain"
              placeholder="예: 인증"
              {...register('domain')}
              disabled={isSubmitting || !!replace}
            />
            <p className="text-xs text-muted-foreground">
              목록과 검색에서 담당 영역별로 모아 볼 수 있어요.
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
  const [tab, setTab] = useState<'content' | 'history'>('content')
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
      '-mb-px h-11 border-b-2 px-0.5 text-sm',
      on
        ? 'border-primary font-semibold'
        : 'border-transparent text-muted-foreground',
    )
  if (unresolved)
    // 이력을 읽는 중이면 불러오는 중, 실패하면 오류와 다시 불러오기를 AsyncState가 보인다.
    return versions.isSuccess ? (
      <p
        role="alert"
        className="rounded-xl border border-destructive/20 bg-destructive/5 p-5 text-sm"
      >
        열려던 버전을 버전 이력에서 찾을 수 없어요. 지워졌거나 볼 수 없는
        버전이에요. 위의 &lsquo;현재 문서&rsquo;로 돌아가 문서를 다시 열어
        주세요.
      </p>
    ) : (
      <AsyncState query={versions}>{() => null}</AsyncState>
    )
  return (
    <AsyncState query={document}>
      {(data) => {
        const base = data.document.approved_version
        return (
          <div className="grid min-w-0 gap-4">
            <div className="flex flex-wrap items-center justify-between gap-3">
              <div className="flex min-w-0 items-center gap-3">
                <KindTile kind={data.document.kind} />
                <div className="min-w-0">
                  <h3 className="text-lg font-semibold">
                    {data.document.title} v{data.version.version_no}
                  </h3>
                  <p className="text-xs text-muted-foreground">
                    담당 {data.document.domain || '공통'} ·{' '}
                    {base ? `현재 기준 v${base.version_no}` : '승인 전'}
                  </p>
                </div>
              </div>
              <div className="flex flex-wrap gap-2">
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
                {author && (
                  <Button
                    variant="ghost"
                    size="sm"
                    disabled={locked}
                    title={
                      locked
                        ? '팀장 검토를 요청한 뒤에는 고칠 수 없어요.'
                        : undefined
                    }
                    onClick={() => setClassification(true)}
                  >
                    분류 수정
                  </Button>
                )}
                {started && <Button onClick={onEdit}>수정 제안하기</Button>}
              </div>
            </div>
            {!started && (
              <p className="rounded-xl bg-muted/50 p-3 text-xs leading-5 text-muted-foreground">
                승인된 버전은 바뀌지 않아요. 고칠 내용은 새 .md를 올려 비비
                검토와 팀장 승인을 거쳐요.
              </p>
            )}
            {base && base.id !== data.version.id && (
              <div className="flex flex-wrap items-center justify-between gap-2 rounded-xl bg-orange-50 p-3 text-sm">
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
            <div
              role="tablist"
              aria-label="문서 보기"
              className="flex gap-5 border-b"
            >
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
            {tab === 'content' ? (
              <div className="grid gap-3">
                <div className="flex flex-wrap items-center gap-3">
                  <Label htmlFor="document-version" className="sr-only">
                    열람할 버전
                  </Label>
                  <NativeSelect
                    id="document-version"
                    value={String(data.version.version_no)}
                    onChange={(event) =>
                      setVersionNo(Number(event.target.value))
                    }
                  >
                    {(versions.data?.items ?? [data.version]).map((version) => (
                      <NativeSelectOption
                        key={version.id}
                        value={String(version.version_no)}
                      >
                        v{version.version_no} ·{' '}
                        {versionStatusLabel(version.status)}
                        {version.id === base?.id ? ' (기준)' : ''}
                      </NativeSelectOption>
                    ))}
                  </NativeSelect>
                  <span className="text-xs text-muted-foreground">
                    {stamp(data.version.created_at)}
                  </span>
                </div>
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
                        className="max-h-[32rem]"
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
                  <ul className="rounded-xl border">
                    {history.items.map((version) => (
                      <li
                        key={version.id}
                        className="flex flex-wrap items-center justify-between gap-3 border-b px-4 py-3 text-sm last:border-b-0"
                      >
                        <span className="flex flex-wrap items-center gap-2">
                          <strong>v{version.version_no}</strong>
                          <Chip tone={versionTone[version.status]}>
                            {versionStatusLabel(version.status)}
                          </Chip>
                          {(version.is_current_baseline === true ||
                            (!history.items.some(
                              (item) => item.is_current_baseline,
                            ) &&
                              version.id === base?.id)) && (
                            <Chip tone="muted">현재 기준</Chip>
                          )}
                          <span className="text-xs text-muted-foreground">
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
                                openPane('changes', version.change_proposal_id)
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
          </div>
        )
      }}
    </AsyncState>
  )
}
