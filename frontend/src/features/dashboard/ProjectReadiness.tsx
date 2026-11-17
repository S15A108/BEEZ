import { useQueries, useQuery } from '@tanstack/react-query'
import { Check } from 'lucide-react'
import { documentsApi } from '@/api/services/documents'
import { planningApi } from '@/api/services/planning'
import { Button } from '@/components/ui/button'
import { reviewStage } from '@/features/documents/reviewStage'
import { statusLabel } from '@/lib/display'
import { cn } from '@/lib/utils'
import { useWorkspaceStore } from '@/stores/workspaceStore'

const KINDS = ['PLAN', 'ERD', 'REQUIREMENTS', 'API_SPEC'] as const
const WAITING = '팀장 승인 대기'

// 시작 전(기준 v1 발행 전) 대시보드의 첫 칸(디자인 검수 F08): 문서 4종이 어디까지 왔는지, 설계 초안이 있는지, 지금 할 일 하나.
// 문서 단계는 공용 문서함의 시작 준비 칸과 같은 작성 건(DOC-A13)과 reviewStage로 읽는다. 키가 같아 한쪽에서 받은 것을 같이 쓴다.
export function ProjectReadiness({
  projectId,
  isLeader,
}: {
  projectId: string
  isLeader: boolean
}) {
  const openPane = useWorkspaceStore((state) => state.openPane)
  const documents = useQuery({
    queryKey: ['projects', projectId, 'planning-documents'],
    queryFn: () => documentsApi.list(projectId, { limit: 100 }),
  })
  const docs = documents.data?.items ?? []
  const copies = useQueries({
    queries: docs.map((document) => ({
      queryKey: ['projects', projectId, 'working-copy', document.id],
      queryFn: () => documentsApi.workingCopy(projectId, document.id),
      retry: false,
    })),
  })
  const drafts = useQuery({
    queryKey: ['projects', projectId, 'dashboard', 'design-drafts'],
    queryFn: () =>
      planningApi.items(projectId, { status: 'DRAFT', limit: 100 }),
  })
  const slots = KINDS.map((kind) => {
    const index = docs.findIndex((document) => document.kind === kind)
    const document = docs[index]
    const copy = copies[index]?.data
    const approved =
      !!document?.approved_version && (!copy || copy.status === 'APPROVED')
    const stage = document && !approved && copy ? reviewStage(copy) : undefined
    return { kind, document, approved, stage }
  })
  const uploaded = slots.filter((slot) => slot.document).length
  const approvedCount = slots.filter((slot) => slot.approved).length
  const draftCount = drafts.data?.items.length
  const draftText = drafts.isError
    ? '확인하지 못했어요'
    : drafts.isPending
      ? '확인 중'
      : draftCount
        ? `${draftCount}개${drafts.data.has_more ? ' 이상' : ''}`
        : '아직 없어요'

  const missing = slots.find((slot) => !slot.document)
  const waiting = slots.find((slot) => slot.stage?.label === WAITING)?.document
  // 비비 검토·수정 제안·검토 요청처럼 올린 사람이 이어 갈 문서. 팀장 승인을 기다리는 문서는 팀장 차례라 뺀다.
  const inReview = slots.find(
    (slot) => slot.document && !slot.approved && slot.stage?.label !== WAITING,
  )?.document
  // 작성 건을 받는 동안에는 승인 대기인지 모르니 다음 할 일을 정하지 않는다(받다 실패하면 단계 없이 정한다).
  const next: { text: string; label?: string; open?: () => void } | undefined =
    !documents.data || copies.some((copy) => copy.isPending)
      ? undefined
      : missing
        ? {
            text: '문서 4종을 올리면 비비가 바로 검토해요. 초안이어도 괜찮아요.',
            label: `${statusLabel(missing.kind)} 올리기`,
            open: () => openPane('documents', `upload:${missing.kind}`),
          }
        : isLeader && waiting
          ? {
              text: `${waiting.title} · 팀장 승인을 기다리고 있어요.`,
              label: '승인 검토하기',
              open: () => openPane('documents', `review:${waiting.id}`),
            }
          : inReview
            ? {
                text: `${inReview.title} · 비비 검토를 확인하고 팀장 검토를 요청해요.`,
                label: `${statusLabel(inReview.kind)} 검토 보기`,
                open: () => openPane('documents', `review:${inReview.id}`),
              }
            : approvedCount < 4
              ? { text: '팀장 승인을 기다리고 있어요.' }
              : !isLeader
                ? {
                    text: "팀장이 기획 분석에서 설계를 검토하고 '프로젝트 시작'을 누르면 시작돼요.",
                  }
                : draftCount === 0
                  ? {
                      text: "문서 4종이 승인됐어요. 기획 분석에서 설계 초안을 만들고 '프로젝트 시작'을 누르면 시작돼요.",
                      label: '설계 초안 만들기',
                      open: () => openPane('planning'),
                    }
                  : {
                      text: "기획 분석에서 설계 항목을 검토하고 '프로젝트 시작'을 누르면 기준 v1이 생기고 시작돼요.",
                      // 배너·시작 가이드와 같은 이름. 초안이 있어도 같은 기획 분석을 연다(발행은 기획 분석에서만).
                      label: '설계 초안 만들기',
                      open: () => openPane('planning'),
                    }

  return (
    <section
      aria-labelledby="dashboard-readiness-title"
      className="grid gap-3 rounded-2xl border bg-card px-4 py-3.5 shadow-xs"
    >
      <div className="flex flex-wrap items-baseline gap-x-2 gap-y-1">
        <h4 id="dashboard-readiness-title" className="text-sm font-bold">
          프로젝트 준비
        </h4>
        <span className="text-xs text-muted-foreground">
          {documents.data
            ? `문서 올림 ${uploaded} / 4 · 승인 ${approvedCount} / 4`
            : documents.isError
              ? '문서 상태를 불러오지 못했어요.'
              : '불러오는 중…'}
        </span>
        <button
          type="button"
          className="ml-auto text-[12.5px] font-semibold text-[#8a6a00]"
          onClick={() => openPane('documents')}
        >
          공용 문서함 ›
        </button>
      </div>
      {next && (
        <div className="flex flex-wrap items-center gap-3 rounded-xl border border-[#f3d77a] bg-[#fffaeb] px-4 py-3">
          <div className="grid min-w-0 flex-1 gap-0.5">
            <span className="text-xs font-bold text-[#8a6a00]">다음 할 일</span>
            <span className="text-sm">{next.text}</span>
          </div>
          {next.label && next.open && (
            <Button size="sm" onClick={next.open}>
              {next.label}
            </Button>
          )}
        </div>
      )}
      {/* 문서 목록을 받기 전에는 '아직 올리지 않았어요'로 보이지 않게 칸을 그리지 않는다. */}
      {documents.data && (
        <ul
          aria-label="준비 상태"
          className="grid gap-2 sm:grid-cols-2 xl:grid-cols-5"
        >
          {slots.map(({ kind, document, approved, stage }) => (
            <li key={kind}>
              <button
                type="button"
                className={cn(
                  'grid h-full w-full content-start gap-1 rounded-xl border px-3 py-2.5 text-left hover:bg-muted/40',
                  !document && 'border-dashed bg-muted/30',
                )}
                onClick={() =>
                  openPane(
                    'documents',
                    !document
                      ? `upload:${kind}`
                      : approved
                        ? document.id
                        : `review:${document.id}`,
                  )
                }
              >
                <span className="text-xs text-muted-foreground">
                  {statusLabel(kind)}
                </span>
                <span className="truncate text-[13px] font-semibold">
                  {document?.title ?? '아직 올리지 않았어요'}
                </span>
                <span
                  className={cn(
                    'inline-flex items-center gap-1 text-xs font-semibold',
                    approved
                      ? 'text-[#00753a]'
                      : stage?.label === WAITING
                        ? 'text-[#8a6a00]'
                        : 'text-muted-foreground',
                  )}
                >
                  {approved && <Check aria-hidden="true" className="size-3" />}
                  {!document
                    ? '올리기 ›'
                    : approved
                      ? `승인 v${document.approved_version!.version_no}`
                      : // 작성 건을 못 받았으면 단계를 모른다. 올렸다는 것만 적는다.
                        (stage?.label ?? '올림')}
                </span>
              </button>
            </li>
          ))}
          <li>
            <button
              type="button"
              className="grid h-full w-full content-start gap-1 rounded-xl border px-3 py-2.5 text-left hover:bg-muted/40"
              onClick={() => openPane('planning')}
            >
              <span className="text-xs text-muted-foreground">설계 초안</span>
              <span className="text-[13px] font-semibold">설계 항목 초안</span>
              <span className="text-xs font-semibold text-muted-foreground">
                {draftText}
              </span>
            </button>
          </li>
        </ul>
      )}
    </section>
  )
}
