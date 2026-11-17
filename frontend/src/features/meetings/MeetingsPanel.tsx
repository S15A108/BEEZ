import { useEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import {
  useInfiniteQuery,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query'
import { zodResolver } from '@hookform/resolvers/zod'
import { useForm } from 'react-hook-form'
import { z } from 'zod'
import {
  Check,
  ChevronLeft,
  ChevronRight,
  Ellipsis,
  Settings,
  Users,
  X,
} from 'lucide-react'
import { BibiFace } from '@/components/BibiFace'
import {
  changesApi,
  documentsApi,
  meetingsApi,
  membersApi,
  tasksApi,
} from '@/api/services'
import type {
  MeetingEntry,
  MeetingSummary,
  Member,
} from '@/api/contracts/common'
import { getApiErrorCode } from '@/api/error'
import { AsyncState } from '@/components/AsyncState'
import { SourceLinks } from '@/components/SourceLinks'
import { Toast } from '@/components/Toast'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from '@/components/ui/popover'
import { Textarea } from '@/components/ui/textarea'
import {
  Tooltip,
  TooltipContent,
  TooltipTrigger,
} from '@/components/ui/tooltip'
import { useSession } from '@/features/auth/useSession'
import { useProjectAction } from '@/features/workspace/useProjectAction'
import { formatDate, formatShort, formatTime } from '@/lib/formatDate'
import { cn } from '@/lib/utils'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import {
  closedMeetingCode,
  meetingErrorMessage,
  staleSummaryCode,
} from './meetingErrors'
import { FEEDBACK_MAX } from './schemas'
import { latestSummaryKey, rememberSummary } from './summaryCache'
import { SummaryCard } from './SummaryCard'
import { DocumentChanges } from './DocumentChanges'
import { useMeetingList } from './useMeetingList'
import { MeetingRevisionDraft } from './MeetingRevisionDraft'
import { MeetingConflictGuidance } from './MeetingConflictGuidance'

interface Props {
  projectId: string
  isLeader: boolean
}
const participationLabel = {
  INVITED: '참여 요청',
  JOINED: '',
  DECLINED: '불참',
}

// 회의 단계(회의실 흐름 목업, 사용자 결정 11-17 · S15P31A108-454). 한 단계에 한 화면이고 노란 버튼은 다음 단계 하나다.
const meetingSteps = [
  { name: '대화', todo: '정할 것을 맞춰요' },
  { name: '비비 정리', todo: '결정을 정리해요' },
  { name: '수정 초안', todo: '바뀌는 문장 확인' },
  { name: '변경 제안', todo: '팀장 승인으로' },
]
// 캔버스 PNL-06-v2 회의실의 칸 테두리
const line = 'border-[#e4e0d4]'

// 회의는 사람이 만들지 않는다. 작업이 부딪히면 비비가 팀장과 관련 작업자를 불러 연다.
// 왼쪽은 회의 목록(진행 중·끝남 필터), 오른쪽은 회의 머리·참여자, 단계 네 칸, 흰 단계 카드와 아래 버튼 줄이다(S15P31A108-454·457).
// 단계는 서버 상태로 정한다: 끝난 회의 → 변경 제안, 지금 정리로 만든 수정 초안 → 수정 초안, 정리 뒤 새 대화가 없는 최신 정리 →
// 비비 정리, 그 밖에는 대화. 사람은 지난 단계로 돌아가 볼 수 있다(단계 칸, '‹ 돌아가기').
// API 없음: origin·task_ids·conflict_summary·status·closed_*는 dev 명세의 '수정안 요청'이라 서버가 주면 그린다.
export function MeetingsPanel({ projectId, isLeader }: Props) {
  const session = useSession()
  const action = useProjectAction(projectId)
  const focus = useWorkspaceStore((state) => state.focusId)
  const openPane = useWorkspaceStore((state) => state.openPane)
  const [selection, setSelection] = useState<{
    id: string
    focus: string | null
  } | null>(null)
  // 사람이 고른 단계(회의마다). 서버 상태로 갈 수 없는 단계가 되면(정리 뒤 새 대화 등) 서버 상태의 단계를 보인다.
  const [stepView, setStepView] = useState<{
    meetingId: string
    step: number
  } | null>(null)
  // 목록 필터. 고르지 않으면 선택한 회의가 있는 쪽을 보인다.
  const [listFilter, setListFilter] = useState<'open' | 'closed' | null>(null)
  const [showRevision, setShowRevision] = useState(false)
  // 회의 정리·끝내기 오류는 회의가 열린 채 어떻게 이어 가면 되는지를 알려 준다(meetingErrors.ts).
  const [notice, setNotice] = useState<{ text: string; id: number } | null>(
    null,
  )
  const client = useQueryClient()
  // 방금 끝낸 회의의 결과(MTG-A13): 변경 제안을 만들었는지와 알림 받은 사람
  const [closeResult, setClosedResult] = useState<{
    meetingId: string
    proposalId?: string
    notified: string[]
  } | null>(null)
  const list = useMeetingList(projectId)
  const meetings = list.data?.pages.flatMap((page) => page.items) ?? []
  const open = meetings.filter((meeting) => meeting.status !== 'CLOSED')
  const closed = meetings.filter((meeting) => meeting.status === 'CLOSED')
  // 처음에는 진행 중인 회의를 연다.
  const selectedId =
    selection?.focus === focus
      ? selection.id
      : (focus ?? open[0]?.id ?? meetings[0]?.id)
  const filter =
    listFilter ??
    (meetings.find((meeting) => meeting.id === selectedId)?.status === 'CLOSED'
      ? 'closed'
      : 'open')
  const detail = useQuery({
    queryKey: ['projects', projectId, 'meeting', selectedId],
    queryFn: () => meetingsApi.get(projectId, selectedId!),
    enabled: !!selectedId,
  })
  const entries = useInfiniteQuery({
    queryKey: ['projects', projectId, 'meeting-entries', selectedId],
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) =>
      meetingsApi.entries(projectId, selectedId!, { cursor: pageParam }),
    getNextPageParam: (page) => (page.has_more ? page.next_cursor : undefined),
    enabled: !!selectedId,
  })
  const members = useQuery({
    queryKey: ['projects', projectId, 'meeting-members'],
    queryFn: () => membersApi.list(projectId, { limit: 100 }),
  })
  const people = members.data?.items ?? []
  const tasks = useQuery({
    queryKey: ['projects', projectId, 'meeting-tasks'],
    queryFn: () => tasksApi.list(projectId, { limit: 100 }),
  })
  const documents = useQuery({
    queryKey: ['projects', projectId, 'meeting-documents'],
    queryFn: () => documentsApi.list(projectId, { limit: 100 }),
  })
  const documentTitleOf = (id: string) =>
    documents.data?.items.find((document) => document.id === id)?.title ??
    '문서'
  const taskOf = (id: string) =>
    tasks.data?.items.find((task) => task.id === id)
  const nameOfMember = (id: string) =>
    people.find((member) => member.id === id)?.name ?? '팀원'
  const nameOfAccount = (id?: string) =>
    people.find((member) => member.account_id === id)?.name ?? '팀장'
  const me = people.find((member) => member.account_id === session.data?.id)
  // 기록 목록(MTG-A10)은 작성 순서(오래된 순)이고 cursor 는 이전 페이지 마지막 기록 ID 다. 최신 기록과 최신 정리를 놓치지
  // 않도록 남은 쪽을 모두 읽고, 화면은 항상 최신순(notes[0] 이 가장 최근)으로 다룬다. 대화는 오래된 것부터 보여 준다.
  const notes = (entries.data?.pages.flatMap((page) => page.items) ?? []).sort(
    (a, b) => (BigInt(a.id) < BigInt(b.id) ? 1 : -1),
  )
  const { hasNextPage, isFetchingNextPage, isError, fetchNextPage } = entries
  // 조회가 실패하면 자동 호출을 멈춘다(계속 두드리지 않는다). 아래 AsyncState 의 '다시 불러오기'로 사람이 다시 시작한다.
  useEffect(() => {
    if (hasNextPage && !isFetchingNextPage && !isError) void fetchNextPage()
  }, [hasNextPage, isFetchingNextPage, isError, fetchNextPage])
  const latest = notes[0]
  const log = useRef<HTMLDivElement>(null)
  // 비비 정리(MTG-A11): 가장 최근 SUMMARY 기록. 구조화한 정리(summary_id·바뀔 문서 등)는 최신 정리 조회(MTG-A15)로 받아서
  // 새로 고치거나 다른 기기에서 열어도 끝내기·수정 요청을 이어 갈 수 있다.
  const review = notes.find((entry) => entry.kind === 'SUMMARY')
  const summaryQuery = useQuery({
    queryKey: latestSummaryKey(projectId, selectedId ?? ''),
    queryFn: () => meetingsApi.latestSummary(projectId, selectedId!),
    enabled: !!selectedId && !!review,
    retry: false,
  })
  const summary =
    review && summaryQuery.data?.entry.id === review.id
      ? summaryQuery.data
      : undefined
  const hasDocumentChanges = !!summary?.document_changes.length
  // 정리에 쓴 마지막 기록(through_entry_id) 뒤에 사람이 쓴 기록이 있으면 그 정리로는 끝낼 수 없다(서버가 409
  // summary_outdated 로 막는다). 결과 기록의 ID 가 아니라 이 값과 비교해야 비비가 정리하는 동안 올라온 대화도 잡는다.
  const summaryThroughId = review?.through_entry_id ?? summary?.through_entry_id
  const talkedAfterSummary =
    !!summaryThroughId &&
    notes.some(
      (entry) =>
        entry.kind === 'TEXT' && BigInt(entry.id) > BigInt(summaryThroughId),
    )
  // 정리에 쓸 마지막 기록: 사람 기록(TEXT)이나 비비가 연 첫 기록(CONFLICT_BRIEF)
  const throughEntry = notes.find(
    (entry) => entry.kind === 'TEXT' || entry.kind === 'CONFLICT_BRIEF',
  )
  // 기록을 모두 읽기 전(남은 페이지가 있거나 조회가 실패한 상태)에는 정리를 요청할 수 없다. 읽힌 곳까지만 정리하게 되기 때문이다.
  const allLoaded = !!entries.data && !hasNextPage && !isError
  const participant = detail.data?.participants.find(
    (item) => item.member_id === me?.id,
  )
  const closedMeeting = detail.data?.status === 'CLOSED'
  // 끝낼 수 있는 정리: 최신 정리이고 그 뒤에 새 대화가 없다.
  const summaryReady = !!summary && !talkedAfterSummary
  // 지금 정리로 만든 회의 수정 초안이 있으면 수정 초안 단계까지 왔다고 본다(MeetingRevisionDraft와 같은 캐시).
  const savedDraft = useQuery({
    queryKey: [
      'projects',
      projectId,
      'meeting',
      selectedId,
      'revision-drafts',
      'latest',
    ],
    queryFn: () => meetingsApi.latestRevisionDraft(projectId, selectedId!),
    enabled: !!selectedId && isLeader && summaryReady && !closedMeeting,
    retry: false,
  })
  const progress = closedMeeting
    ? 3
    : summaryReady
      ? savedDraft.data?.summary_id === summary?.id
        ? 2
        : 1
      : 0
  // 볼 수 있는 단계. 끝난 회의는 기록·정리·바뀐 문서를 읽기만 하고, 수정 초안은 끝내기를 맡은 팀장만 연다.
  const canView = (index: number) =>
    index === 0 ||
    (closedMeeting
      ? index === 3 || !!summary
      : (index === 1 && summaryReady) ||
        (index === 2 && summaryReady && isLeader))
  const step =
    stepView && stepView.meetingId === selectedId && canView(stepView.step)
      ? stepView.step
      : progress
  const goTo = (next: number) => {
    if (selectedId) setStepView({ meetingId: selectedId, step: next })
  }
  // 새 메시지가 오거나 대화 단계로 돌아오면 대화 맨 아래로 내린다. 이전 기록을 더 불러올 때는 그대로 둔다.
  useEffect(() => {
    if (log.current) log.current.scrollTop = log.current.scrollHeight
  }, [latest?.id, step])
  const proposalId =
    detail.data?.change_proposal_id ??
    (closeResult && closeResult.meetingId === selectedId
      ? closeResult.proposalId
      : undefined)
  // 끝낸 결과에 올린 변경 제안의 제목을 보인다(변경 제안 창과 같은 캐시).
  const proposal = useQuery({
    queryKey: ['projects', projectId, 'change', proposalId],
    queryFn: () => changesApi.get(projectId, proposalId!),
    enabled: !!proposalId && step === 3,
  })
  // 사람마다 이 회의에서 맡은 작업(TASK-001 등). 없으면 팀원 역할 이름(화면 문구 등)
  const roleOf = (member: Member | undefined, taskIds: string[] = []) => {
    const keys = taskIds.flatMap((id) => {
      const task = taskOf(id)
      return task && task.assignee_member_id === member?.id ? [task.key] : []
    })
    return [
      member?.role === 'LEADER' && '팀장',
      ...(keys.length
        ? keys
        : [
            member?.role !== 'LEADER' && (member?.role_label ?? member?.domain),
          ]),
    ]
      .filter(Boolean)
      .join(' · ')
  }
  // 실패하면 회의 전용 안내를 보이고 오류를 다시 던진다(입력줄이 입력을 지우지 않게).
  // 정리·수정 요청·끝내기는 회의 정리 전용 문구를, 메시지·관련자 추가·빼기는 공용 문구(action.error)를 쓴다.
  const runWithNotice = async (
    operation: () => Promise<unknown>,
    message: string,
  ) => {
    setNotice(null)
    try {
      return await action.run(operation, message)
    } catch (error) {
      setNotice({ text: meetingErrorMessage(error), id: 1 })
      throw error
    }
  }
  const runPlain = async (
    operation: () => Promise<unknown>,
    message: string,
  ) => {
    setNotice(null)
    return action.run(operation, message)
  }
  const perform = (operation: () => Promise<unknown>, message: string) => {
    void runPlain(operation, message).catch(() => undefined)
  }
  // 새 정리는 정리 캐시와 기록 목록 캐시에 함께 넣는다(회의 수정 초안도 같은 정리 캐시를 읽는다).
  const remember = (meetingId: string, next: MeetingSummary) =>
    rememberSummary(client, projectId, meetingId, next)
  // MTG-A11: 가장 최근 기록까지의 대화를 비비가 정리한다. 정리가 오면 비비 정리 단계로 넘어간다.
  const requestSummary = (meetingId: string, throughId: string) =>
    runWithNotice(async () => {
      remember(
        meetingId,
        await meetingsApi.summarize(projectId, meetingId, {
          through_entry_id: throughId,
        }),
      )
      setStepView({ meetingId, step: 1 })
    }, '비비가 회의 내용을 정리했어요.')
  // 근거 발언을 누르면 대화 단계로 가서 그 말을 가운데에 둔다.
  const showEvidence = (entryId: string) => {
    goTo(0)
    requestAnimationFrame(() =>
      document
        .getElementById(`entry-${entryId}`)
        ?.scrollIntoView({ block: 'center' }),
    )
  }
  // MTG-A13: 팀장이 확인한 최신 정리로 회의를 끝낸다. 정리에 바뀔 문서가 있으면 변경 제안이 만들어진다.
  const closeMeeting = (meetingId: string, summaryId: string) =>
    void runWithNotice(async () => {
      try {
        const result = await meetingsApi.close(projectId, meetingId, {
          summary_id: summaryId,
        })
        setClosedResult({
          meetingId,
          proposalId: result.change_proposal_id,
          notified: result.notified_member_ids.map(nameOfMember),
        })
        // 진행 중 목록에서 빠져도 방금 끝낸 회의를 계속 보여 준다(목록은 '끝남'으로).
        setSelection({ id: meetingId, focus })
        setStepView(null)
        setListFilter(null)
      } catch (error) {
        const code = getApiErrorCode(error)
        // 오래된 정리로는 끝낼 수 없다. 대화로 돌아가 정리를 다시 요청하게 한다. 이미 끝난 회의면 결과로 간다.
        if (code === staleSummaryCode) setStepView({ meetingId, step: 0 })
        if (code === closedMeetingCode) setStepView(null)
        throw error
      }
    }, '').catch(() => undefined)
  const select = (id: string) => {
    setSelection({ id, focus })
    setStepView(null)
    setShowRevision(false)
    setNotice(null)
  }
  const shown = filter === 'open' ? open : closed
  return (
    // 풀스크린 업무 창(S15P31A108-431)과 같은 바깥 틀이다. 창 바탕 위에 흰 목록 카드와 흰 단계 카드를 둔다.
    // 넓은 화면에서는 창 높이에 맞추고 단계 카드 안만 스크롤한다. 창의 1fr 줄이 내용 높이로 늘지 않게 높이 0에 min-h로 채운다.
    <section
      className={cn(
        '-mb-5 flex min-h-[calc(100%+1.25rem)] flex-col gap-3.5 pb-5 sm:-mb-8 sm:min-h-[calc(100%+2rem)] lg:h-0 lg:overflow-y-auto',
        line,
      )}
    >
      {/* 결과·오류 안내는 토스트로 잠깐 보였다 사라진다(회의는 열린 채 어떻게 이어 가면 되는지를 알려 준다). */}
      {action.message && (
        <Toast key={action.message} tone="success">
          {action.message}
        </Toast>
      )}
      {(notice || action.error) && (
        <Toast key={notice?.id ?? 'error'} tone="danger">
          {notice?.text ?? action.error}
        </Toast>
      )}
      <AsyncState query={list}>
        {() =>
          meetings.length ? (
            <div className="grid flex-1 gap-5 lg:min-h-0 lg:grid-cols-[280px_minmax(0,1fr)] lg:grid-rows-[minmax(0,1fr)]">
              <div
                className={cn(
                  'grid content-start gap-3 self-start rounded-2xl border bg-white p-3.5 lg:max-h-full lg:overflow-y-auto',
                  line,
                )}
              >
                {/* 진행 중·끝남 필터(S15P31A108-457). 숫자는 그쪽 회의 수다. */}
                <div
                  role="group"
                  aria-label="회의 목록 보기"
                  className="grid grid-cols-2 gap-1 rounded-xl bg-[#f1efe7] p-1"
                >
                  {(
                    [
                      ['open', '진행 중', open.length],
                      ['closed', '끝남', closed.length],
                    ] as const
                  ).map(([key, label, count]) => (
                    <button
                      key={key}
                      type="button"
                      aria-pressed={filter === key}
                      className={cn(
                        'flex h-8 items-center justify-center gap-1.5 rounded-lg text-[13px] font-semibold outline-none focus-visible:ring-2 focus-visible:ring-ring',
                        filter === key
                          ? 'bg-white text-foreground shadow-sm'
                          : 'text-foreground/75 hover:bg-white/60',
                      )}
                      onClick={() => setListFilter(key)}
                    >
                      {label}
                      <span className="text-xs font-medium text-muted-foreground">
                        {count}
                      </span>
                    </button>
                  ))}
                </div>
                {shown.length ? (
                  <ul
                    aria-label={
                      filter === 'open' ? '진행 중 회의' : '끝난 회의'
                    }
                    className="grid gap-2"
                  >
                    {shown.map((meeting) => (
                      <li key={meeting.id}>
                        <button
                          type="button"
                          aria-current={meeting.id === selectedId}
                          className={cn(
                            'grid w-full gap-1 rounded-xl border border-[#ece9e0] bg-[#f7f5ef] px-3.5 py-3 text-left hover:bg-[#fbfaf6]',
                            meeting.id === selectedId &&
                              'border-2 border-[#2c291f] bg-[#fff8e0] px-[13px] py-[11px] hover:bg-[#fff8e0]',
                          )}
                          onClick={() => select(meeting.id)}
                        >
                          <MeetingChip
                            status={meeting.status}
                            origin={meeting.origin}
                            alert
                          />
                          <strong className="text-sm font-semibold">
                            {meeting.title}
                          </strong>
                          {meeting.status === 'CLOSED' ? (
                            <span className="text-xs text-muted-foreground">
                              {[
                                formatShort(meeting.created_at),
                                meeting.origin === 'BEE_CONFLICT' &&
                                  '비비가 엶',
                                `참여 ${meeting.participants.length}명`,
                              ]
                                .filter(Boolean)
                                .join(' · ')}
                            </span>
                          ) : (
                            <>
                              <span className="text-xs text-muted-foreground">
                                {[
                                  ...(meeting.task_ids ?? []).map(
                                    (id) => taskOf(id)?.key ?? '작업',
                                  ),
                                  `참여 ${meeting.participants.length}명`,
                                ].join(' · ')}
                              </span>
                              <span className="text-xs text-muted-foreground">
                                {formatShort(meeting.created_at)}
                                {meeting.origin === 'BEE_CONFLICT' &&
                                  ' · 비비가 엶'}
                              </span>
                            </>
                          )}
                          {meeting.status === 'CLOSED' &&
                            meeting.change_proposal_id && (
                              <span className="text-xs font-semibold text-[#8a6a00]">
                                변경 제안 올라감 ›
                              </span>
                            )}
                        </button>
                      </li>
                    ))}
                  </ul>
                ) : (
                  <p className="px-1 py-2 text-xs text-muted-foreground">
                    {filter === 'open'
                      ? '진행 중인 회의가 없어요.'
                      : '끝난 회의가 없어요.'}
                  </p>
                )}
                {list.hasNextPage && (
                  <Button
                    variant="outline"
                    className="w-full"
                    onClick={() => void list.fetchNextPage()}
                  >
                    회의 더 보기
                  </Button>
                )}
              </div>
              {selectedId && (
                <AsyncState query={detail}>
                  {(meeting) => {
                    const joined = participant?.status === 'JOINED'
                    // 정리 수정·끝내기·회의 중단은 이 회의에 참여한 팀장만 한다.
                    const leading = isLeader && joined && !closedMeeting
                    // 회의 결과 변경 제안을 알릴 관련 작업 담당자(나 말고). 담당자 없는 작업은 알릴 사람이 없다.
                    const notify = (meeting.task_ids ?? []).flatMap((id) => {
                      const task = taskOf(id)
                      return task?.assignee_member_id &&
                        task.assignee_member_id !== me?.id
                        ? [
                            `${task.key} 담당 ${nameOfMember(task.assignee_member_id)}`,
                          ]
                        : []
                    })
                    const talkCount = notes.filter(
                      (entry) => entry.kind === 'TEXT',
                    ).length
                    const captions = [
                      progress > 0
                        ? `메시지 ${talkCount}개`
                        : meetingSteps[0].todo,
                      progress > 1 && summary
                        ? `결정 ${summary.decisions.length}개`
                        : meetingSteps[1].todo,
                      progress > 2 && summary
                        ? `문서 ${summary.document_changes.length}개`
                        : meetingSteps[2].todo,
                      closedMeeting
                        ? proposalId
                          ? '제안을 올렸어요'
                          : '변경 없이 끝남'
                        : meetingSteps[3].todo,
                    ]
                    // 회의 중단(변경 없이 끝내기, 사용자 결정 11-17): 서버의 회의 끝내기(MTG-A13)는 정리가 있어야 하고
                    // 정리에 바뀔 문서가 있으면 늘 변경 제안을 만든다. 그래서 바뀔 문서 없는 최신 정리가 있을 때만 끝낸다.
                    const stopBlocked = !summary
                      ? '비비 정리가 있어야 회의를 끝낼 수 있어요. 대화에서 비비에게 정리를 먼저 요청해 주세요.'
                      : talkedAfterSummary
                        ? '정리한 뒤에 새 대화가 있어요. 비비에게 정리를 다시 요청해 주세요.'
                        : hasDocumentChanges
                          ? '정리에 바뀔 문서가 있어 끝내면 변경 제안이 올라가요. 바꾸지 않으려면 비비 정리 수정에서 바뀔 문서를 빼 달라고 말해 주세요.'
                          : ''
                    const summaryMissing = (
                      <p className="text-[13px] text-muted-foreground">
                        {summaryQuery.isError
                          ? '비비 정리를 불러오지 못했어요.'
                          : '비비 정리를 불러오는 중이에요.'}
                      </p>
                    )
                    const resultButton = (
                      <Button
                        variant="outline"
                        size="lg"
                        onClick={() => goTo(3)}
                      >
                        결과 보기
                        <ChevronRight />
                      </Button>
                    )
                    return (
                      <div className="flex min-h-0 min-w-0 flex-col gap-3.5">
                        <div className="grid gap-1">
                          {/* 제목 줄: 제목 오른쪽에 참여자와 더보기를 둔다(화면 검수 11-16). 좁으면 참여자가 제목 아래로 내려간다. */}
                          <div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-2">
                            <div className="flex flex-wrap items-center gap-2">
                              <h3 className="text-lg font-bold">
                                {meeting.title}
                              </h3>
                              <MeetingChip
                                status={meeting.status}
                                origin={meeting.origin}
                              />
                            </div>
                            <div className="flex items-start gap-1">
                              <div
                                role="group"
                                aria-label="참여자"
                                className="flex flex-wrap items-center gap-2 text-[13px]"
                              >
                                <span className="mr-0.5 text-muted-foreground">
                                  참여자 {meeting.participants.length + 1}
                                </span>
                                {/* 참여자 칩(캔버스 PNL-06-v2): 이니셜 원, 이름(역할·이 회의의 작업은 마우스를 올리면). 비비와 팀장 칩에는 × 없음, 빼기는 팀장만. */}
                                <PersonChip
                                  avatar={<Initial name="비" color="#f3c530" />}
                                  name="비비"
                                  role="AI 팀장"
                                />
                                {meeting.participants.map((item) => {
                                  const member = people.find(
                                    (person) => person.id === item.member_id,
                                  )
                                  const removable =
                                    isLeader &&
                                    !closedMeeting &&
                                    member?.role !== 'LEADER'
                                  return (
                                    <PersonChip
                                      key={item.member_id}
                                      avatar={
                                        <Initial
                                          name={member?.name}
                                          color={member?.color}
                                          dark={member?.role === 'LEADER'}
                                        />
                                      }
                                      name={member?.name ?? '팀원'}
                                      role={[
                                        roleOf(member, meeting.task_ids),
                                        item.added_by_member_id &&
                                          `${nameOfMember(item.added_by_member_id)}이 추가`,
                                      ]
                                        .filter(Boolean)
                                        .join(' · ')}
                                      status={participationLabel[item.status]}
                                    >
                                      {removable && (
                                        <button
                                          type="button"
                                          aria-label={`${member?.name ?? '팀원'} 빼기`}
                                          className="grid size-5 place-items-center rounded-full text-muted-foreground hover:bg-muted"
                                          onClick={() =>
                                            perform(
                                              () =>
                                                meetingsApi.removeParticipant(
                                                  projectId,
                                                  meeting.id,
                                                  item.member_id,
                                                ),
                                              `${member?.name ?? '팀원'}님을 회의에서 뺐어요.`,
                                            )
                                          }
                                        >
                                          <X className="size-3" />
                                        </button>
                                      )}
                                    </PersonChip>
                                  )
                                })}
                                {(isLeader || !!participant) &&
                                  !closedMeeting && (
                                    <ParticipantPicker
                                      members={people}
                                      joinedIds={meeting.participants.map(
                                        (item) => item.member_id,
                                      )}
                                      isLeader={isLeader}
                                      roleOf={(member) =>
                                        roleOf(member, meeting.task_ids)
                                      }
                                      onAdd={(memberId) =>
                                        perform(
                                          () =>
                                            meetingsApi.invite(
                                              projectId,
                                              meeting.id,
                                              {
                                                member_ids: [memberId],
                                              },
                                            ),
                                          '관련자를 추가했어요.',
                                        )
                                      }
                                      onRemove={(memberId) =>
                                        perform(
                                          () =>
                                            meetingsApi.removeParticipant(
                                              projectId,
                                              meeting.id,
                                              memberId,
                                            ),
                                          '회의에서 뺐어요.',
                                        )
                                      }
                                    />
                                  )}
                              </div>
                              {leading && (
                                <Popover>
                                  <PopoverTrigger asChild>
                                    <Button
                                      variant="ghost"
                                      size="icon"
                                      aria-label="회의 더보기"
                                    >
                                      <Ellipsis />
                                    </Button>
                                  </PopoverTrigger>
                                  <PopoverContent
                                    align="end"
                                    className="grid w-72 gap-2 p-3.5 text-[13px]"
                                  >
                                    <p className="font-bold">회의 중단</p>
                                    <p className="text-xs leading-5 text-muted-foreground">
                                      변경 제안 없이 회의를 끝내요. 끝난
                                      회의에는 더 쓸 수 없어요.
                                    </p>
                                    {stopBlocked && (
                                      <p className="text-xs leading-5 text-[#8a6a00]">
                                        {stopBlocked}
                                      </p>
                                    )}
                                    <Button
                                      variant="destructive"
                                      disabled={!!stopBlocked || action.busy}
                                      onClick={() =>
                                        summary &&
                                        closeMeeting(meeting.id, summary.id)
                                      }
                                    >
                                      변경 없이 끝내기
                                    </Button>
                                  </PopoverContent>
                                </Popover>
                              )}
                            </div>
                          </div>
                          <p className="text-xs text-muted-foreground">
                            {[
                              formatDate(meeting.created_at),
                              closedMeeting
                                ? `${nameOfAccount(meeting.closed_by)}이 끝냄`
                                : meeting.origin === 'BEE_CONFLICT' &&
                                  '비비가 열었어요',
                              '기록은 원문 그대로 남아요',
                            ]
                              .filter(Boolean)
                              .join(' · ')}
                          </p>
                        </div>
                        <MeetingSteps
                          current={step}
                          done={(index) =>
                            index < Math.max(progress, step) && canView(index)
                          }
                          canView={canView}
                          captions={captions}
                          onSelect={goTo}
                        />
                        {/* 단계 카드: 위는 그 단계의 내용, 아래는 버튼 줄. 넓은 화면에서는 남는 높이를 모두 쓰고 안에서 스크롤한다.
                            대화·정리 단계는 펼친 안건(128px)·대화 칸(112px) 또는 정리(192px)와 아래 입력칸·버튼 줄이 겹치지 않을 높이(416px)를 지키고,
                            창이 그보다 낮으면 회의실 창이 스크롤한다(PR #306 재검토와 같은 방식). */}
                        <div
                          className={cn(
                            'flex flex-col rounded-2xl border bg-white lg:min-h-0 lg:flex-1',
                            step <= 1 && 'lg:min-h-[26rem]',
                            line,
                          )}
                        >
                          <div
                            className={cn(
                              'flex flex-col gap-3.5 p-4 sm:px-6 sm:py-5 lg:min-h-0 lg:flex-1',
                              step >= 2 && 'lg:overflow-y-auto',
                            )}
                          >
                            {step === 0 && (
                              <>
                                {(meeting.conflict_summary ||
                                  meeting.conflict_guidance) && (
                                  <MeetingConflictGuidance
                                    key={meeting.id}
                                    summary={meeting.conflict_summary}
                                    taskIds={meeting.task_ids ?? []}
                                    guidance={meeting.conflict_guidance}
                                    brief={
                                      notes.find(
                                        (entry) =>
                                          entry.kind === 'CONFLICT_BRIEF',
                                      )?.text
                                    }
                                    closed={closedMeeting}
                                    taskKeyOf={(id) => taskOf(id)?.key}
                                    documentTitleOf={documentTitleOf}
                                  />
                                )}
                                {review &&
                                  !summary &&
                                  summaryQuery.isError &&
                                  !closedMeeting &&
                                  isLeader && (
                                    <p className="rounded-xl border border-dashed border-[#d6d1c4] bg-white px-3.5 py-2.5 text-[13px] text-muted-foreground">
                                      비비 정리를 불러오지 못했어요. 비비에게
                                      정리를 다시 요청하면 새로 받아요.
                                    </p>
                                  )}
                                {/* lg 미만은 창이 내용 높이로 늘어나므로 대화 칸 높이를 묶어 안에서 스크롤한다(새 메시지로 내리기).
                                    lg부터는 단계 카드에서 안건·입력칸을 뺀 남은 높이를 모두 쓰고, 이 칸만 스크롤한다. */}
                                <AsyncState query={entries}>
                                  {() => (
                                    <div
                                      ref={log}
                                      role="log"
                                      aria-label="회의 대화"
                                      className="-mx-1 grid max-h-[50vh] min-h-48 flex-1 content-start gap-3.5 overflow-auto px-1 lg:max-h-none lg:min-h-28"
                                    >
                                      {notes.length ? (
                                        notes
                                          .toReversed()
                                          .map((entry) => (
                                            <Bubble
                                              key={entry.id}
                                              entry={entry}
                                              mine={
                                                entry.author_account_id ===
                                                session.data?.id
                                              }
                                              author={people.find(
                                                (member) =>
                                                  member.account_id ===
                                                  entry.author_account_id,
                                              )}
                                              latestSummary={
                                                entry.id === review?.id
                                              }
                                              onOpenSummary={
                                                entry.id === review?.id &&
                                                canView(1)
                                                  ? () => goTo(1)
                                                  : undefined
                                              }
                                              documentTitleOf={documentTitleOf}
                                            />
                                          ))
                                      ) : (
                                        <p className="text-sm text-muted-foreground">
                                          아직 대화가 없어요.
                                        </p>
                                      )}
                                    </div>
                                  )}
                                </AsyncState>
                                {closedMeeting ? (
                                  <p className="rounded-[10px] border border-dashed border-[#d6d1c4] bg-white px-3 py-2.5 text-[13px] text-muted-foreground">
                                    끝난 회의라 메시지를 보낼 수 없어요.
                                  </p>
                                ) : joined ? (
                                  <InlineMessage
                                    key={`message-${meeting.id}`}
                                    label="메시지"
                                    placeholder="메시지 보내기"
                                    submitLabel="보내기"
                                    max={ENTRY_MAX}
                                    onSubmit={async (text) => {
                                      await runPlain(
                                        () =>
                                          meetingsApi.addEntry(
                                            projectId,
                                            meeting.id,
                                            { text },
                                          ),
                                        '메시지를 보냈어요.',
                                      )
                                    }}
                                  />
                                ) : (
                                  <p className="text-sm text-muted-foreground">
                                    비비가 부른 사람만 메시지를 보낼 수 있어요.
                                  </p>
                                )}
                              </>
                            )}
                            {step === 1 &&
                              (summary ? (
                                <>
                                  <div className="-mx-1 grid content-start gap-3.5 px-1 lg:min-h-48 lg:flex-1 lg:overflow-y-auto">
                                    <div className="flex flex-wrap items-center gap-x-2.5 gap-y-1">
                                      <BibiFace className="size-7 shrink-0" />
                                      <h4 className="font-bold">비비 정리</h4>
                                      <span className="text-xs text-muted-foreground">
                                        {formatTime(summary.created_at)} · 대화{' '}
                                        {
                                          notes.filter(
                                            (entry) =>
                                              entry.kind === 'TEXT' &&
                                              BigInt(entry.id) <=
                                                BigInt(
                                                  summary.through_entry_id,
                                                ),
                                          ).length
                                        }
                                        개까지 정리
                                      </span>
                                      {summary.generation.origin ===
                                        'TEMPLATE' && (
                                        <span className="text-xs text-muted-foreground">
                                          · 예시 템플릿 · 실제 AI 호출 없음
                                        </span>
                                      )}
                                    </div>
                                    <SummaryCard
                                      summary={summary}
                                      documentTitleOf={documentTitleOf}
                                      nameOfMember={nameOfMember}
                                      onShowEvidence={showEvidence}
                                      compactChanges
                                    />
                                  </div>
                                  {/* 정리 단계의 아래 입력칸은 메시지 대신 '비비 정리 수정'이다(S15P31A108-457). 새 합의는 대화로 돌아가 남긴다. */}
                                  {leading && (
                                    <section
                                      aria-label="비비 정리 수정"
                                      className="grid gap-1.5"
                                    >
                                      <p className="text-xs text-muted-foreground">
                                        정리에서 빠지거나 틀린 점을 비비에게
                                        말하면 정리를 다시 받아요. 새로 합의할
                                        게 있으면 &apos;대화로 돌아가기&apos;.
                                      </p>
                                      <InlineMessage
                                        key={summary.id}
                                        label="비비에게 고칠 점"
                                        placeholder="정리에서 고칠 점을 비비에게 말해 주세요"
                                        submitLabel="수정 요청"
                                        busy={action.busy}
                                        max={FEEDBACK_MAX}
                                        onSubmit={async (text) => {
                                          await runWithNotice(async () => {
                                            remember(
                                              meeting.id,
                                              await meetingsApi.reviseSummary(
                                                projectId,
                                                meeting.id,
                                                summary.id,
                                                { feedback: text },
                                              ),
                                            )
                                          }, '비비가 다시 정리했어요.')
                                        }}
                                      />
                                    </section>
                                  )}
                                </>
                              ) : (
                                summaryMissing
                              ))}
                            {step === 2 &&
                              (summary ? (
                                <>
                                  <div>
                                    <h4 className="font-bold">
                                      변경 제안에 담을 것
                                    </h4>
                                    <p className="mt-0.5 text-[13px] leading-5 text-muted-foreground">
                                      {closedMeeting
                                        ? '회의를 끝낼 때 확인한 비비 정리의 바뀔 문서예요.'
                                        : hasDocumentChanges
                                          ? '회의를 끝내면 아래 문서 변경이 그대로 변경 제안으로 올라가요. 빼거나 고칠 게 있으면 정리로 돌아가 비비 정리 수정에서 말해 주세요.'
                                          : '확인한 정리를 기록에 남기고 회의를 닫아요.'}
                                    </p>
                                  </div>
                                  <DocumentChanges
                                    changes={summary.document_changes}
                                    documentTitleOf={documentTitleOf}
                                    onShowEvidence={showEvidence}
                                  />
                                  {!closedMeeting && (
                                    <ul className="list-disc pl-[18px] text-[13px] leading-[21px] text-[#57534e]">
                                      {hasDocumentChanges &&
                                        notify.length > 0 && (
                                          <li>
                                            {notify.join(', ')}에게 변경 제안을
                                            알려요.
                                          </li>
                                        )}
                                      {hasDocumentChanges && (
                                        <li>
                                          공용 문서는 변경 제안을 승인·발행하면
                                          바뀌어요.
                                        </li>
                                      )}
                                      <li>끝난 회의에는 더 쓸 수 없어요.</li>
                                    </ul>
                                  )}
                                  {/* 결정·근거·문서 범위를 직접 골라 미제출 초안을 저장하는 도구(S15P31A108-191)는 접어 둔다. */}
                                  {leading && (
                                    <div
                                      className={cn(
                                        'grid gap-2 border-t pt-3.5',
                                        line,
                                      )}
                                    >
                                      <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
                                        <Button
                                          type="button"
                                          variant="outline"
                                          aria-expanded={showRevision}
                                          onClick={() =>
                                            setShowRevision((value) => !value)
                                          }
                                        >
                                          {showRevision
                                            ? '직접 수정 초안 접기'
                                            : '직접 수정 초안 만들기'}
                                        </Button>
                                        <span className="text-xs text-muted-foreground">
                                          결정·근거·문서 범위를 직접 골라
                                          제출하지 않는 초안을 저장해요.
                                        </span>
                                      </div>
                                      {showRevision && (
                                        <MeetingRevisionDraft
                                          projectId={projectId}
                                          meetingId={meeting.id}
                                          entries={notes}
                                          hasMore={!!entries.hasNextPage}
                                          loadingMore={
                                            entries.isFetchingNextPage
                                          }
                                          loadMore={() =>
                                            void entries.fetchNextPage()
                                          }
                                        />
                                      )}
                                    </div>
                                  )}
                                </>
                              ) : (
                                summaryMissing
                              ))}
                            {step === 3 && (
                              <section
                                aria-label="회의 끝낸 결과"
                                className="m-auto grid max-w-[520px] justify-items-center gap-2.5 py-6 text-center"
                              >
                                <span className="grid size-12 place-items-center rounded-full bg-green-700/10 text-green-700">
                                  <Check
                                    aria-hidden="true"
                                    className="size-6"
                                  />
                                </span>
                                <h4 className="text-lg font-bold">
                                  {proposalId
                                    ? '회의를 끝내고 변경 제안을 올렸어요.'
                                    : '회의를 끝냈어요. 바뀔 문서가 없어 변경 제안은 만들지 않았어요.'}
                                </h4>
                                {proposal.data && (
                                  <p className="text-sm font-semibold">
                                    {proposal.data.proposal.title}
                                  </p>
                                )}
                                {proposalId && (
                                  <p className="text-[13px] leading-5 text-muted-foreground">
                                    {closeResult?.meetingId === meeting.id &&
                                    closeResult.notified.length
                                      ? `${closeResult.notified.join(', ')}에게 알렸어요. `
                                      : ''}
                                    공용 문서는 변경 제안을 승인·발행하면
                                    바뀌어요.
                                  </p>
                                )}
                                <div className="mt-1 flex flex-wrap justify-center gap-2">
                                  <Button
                                    variant="outline"
                                    size="lg"
                                    onClick={() => goTo(0)}
                                  >
                                    회의 기록 보기
                                  </Button>
                                  {proposalId && (
                                    <Button
                                      size="lg"
                                      onClick={() =>
                                        openPane('changes', proposalId)
                                      }
                                    >
                                      변경 제안에서 보기
                                    </Button>
                                  )}
                                </div>
                              </section>
                            )}
                          </div>
                          <div
                            className={cn(
                              'flex flex-wrap items-center gap-2.5 rounded-b-2xl border-t bg-[#fbfaf6] px-4 py-3 sm:px-6',
                              line,
                            )}
                          >
                            {step === 0 && (
                              <>
                                <p
                                  className={cn(
                                    'mr-auto min-w-0 flex-1 basis-60 text-xs leading-5',
                                    isLeader &&
                                      !closedMeeting &&
                                      talkedAfterSummary
                                      ? 'text-[#8a6a00]'
                                      : 'text-muted-foreground',
                                  )}
                                >
                                  {closedMeeting
                                    ? '끝난 회의에는 더 쓸 수 없어요.'
                                    : !isLeader
                                      ? summaryReady
                                        ? '팀장이 비비 정리를 확인하고 회의를 끝내요.'
                                        : '팀장만 요청할 수 있어요'
                                      : talkedAfterSummary
                                        ? '정리한 뒤에 새 대화가 있어요. 끝내기 전에 비비에게 정리를 다시 요청해 주세요.'
                                        : summaryReady
                                          ? '정리한 뒤에 새로 말하면 비비에게 정리를 다시 요청해야 해요.'
                                          : '합의가 끝나면 비비에게 정리를 맡기세요. 정리하면 정리 화면으로 넘어가요.'}
                                </p>
                                {closedMeeting ? (
                                  resultButton
                                ) : summaryReady ? (
                                  <Button size="lg" onClick={() => goTo(1)}>
                                    비비 정리 보기
                                    <ChevronRight />
                                  </Button>
                                ) : (
                                  <Button
                                    size="lg"
                                    disabled={
                                      !isLeader ||
                                      !participant ||
                                      !throughEntry ||
                                      !allLoaded ||
                                      action.busy
                                    }
                                    onClick={() =>
                                      void requestSummary(
                                        meeting.id,
                                        throughEntry!.id,
                                      ).catch(() => undefined)
                                    }
                                  >
                                    비비에게 정리 요청
                                  </Button>
                                )}
                              </>
                            )}
                            {step === 1 && (
                              <>
                                <Button
                                  variant="ghost"
                                  size="lg"
                                  className="mr-auto"
                                  onClick={() => goTo(0)}
                                >
                                  <ChevronLeft />
                                  대화로 돌아가기
                                </Button>
                                {closedMeeting ? (
                                  resultButton
                                ) : isLeader ? (
                                  <Button
                                    size="lg"
                                    disabled={!participant}
                                    onClick={() => goTo(2)}
                                  >
                                    수정 초안 만들기
                                  </Button>
                                ) : (
                                  <p className="text-xs text-muted-foreground">
                                    팀장이 비비 정리를 확인하고 회의를 끝내요.
                                  </p>
                                )}
                              </>
                            )}
                            {step === 2 && (
                              <>
                                <Button
                                  variant="ghost"
                                  size="lg"
                                  className="mr-auto"
                                  onClick={() => goTo(1)}
                                >
                                  <ChevronLeft />
                                  정리로 돌아가기
                                </Button>
                                {closedMeeting
                                  ? resultButton
                                  : summary && (
                                      <Button
                                        size="lg"
                                        disabled={
                                          action.busy ||
                                          !isLeader ||
                                          !participant ||
                                          talkedAfterSummary
                                        }
                                        onClick={() =>
                                          closeMeeting(meeting.id, summary.id)
                                        }
                                      >
                                        {hasDocumentChanges
                                          ? '변경 제안 올리고 회의 끝내기'
                                          : '회의 끝내기'}
                                      </Button>
                                    )}
                              </>
                            )}
                            {step === 3 && (
                              <p className="text-xs leading-5 text-muted-foreground">
                                끝난 회의에는 더 쓸 수 없어요. 다시 맞출 게
                                생기면 비비가 새 회의를 열어요.
                              </p>
                            )}
                          </div>
                        </div>
                      </div>
                    )
                  }}
                </AsyncState>
              )}
            </div>
          ) : (
            // 캔버스 PNL-06-v2-empty: 가운데 아이콘 칸과 안내
            <div className="grid flex-1 place-items-center py-16">
              <div className="grid max-w-[440px] justify-items-center gap-2.5 text-center">
                <span
                  className={cn(
                    'grid size-14 place-items-center rounded-2xl border bg-white',
                    line,
                  )}
                >
                  <Users aria-hidden="true" className="size-6" />
                </span>
                <strong className="text-base">회의가 없어요</strong>
                <span className="text-[13px] leading-5 text-muted-foreground">
                  작업이 서로 부딪히면 비비가 팀장과 관련 작업자를 불러 회의를
                  열어요.
                </span>
              </div>
            </div>
          )
        }
      </AsyncState>
    </section>
  )
}

// 회의 칩. 목록에서는 '작업 충돌'을 빨간색으로 보인다(alert, S15P31A108-457).
function MeetingChip({
  status,
  origin,
  alert,
}: {
  status?: 'OPEN' | 'CLOSED'
  origin?: 'BEE_CONFLICT' | 'HUMAN'
  alert?: boolean
}) {
  if (status !== 'CLOSED' && origin !== 'BEE_CONFLICT') return null
  return (
    <span
      className={cn(
        'inline-flex h-[22px] w-fit items-center rounded-full px-2 text-xs font-semibold',
        status === 'CLOSED'
          ? 'bg-[#f0eee8] text-[#57534e]'
          : alert
            ? 'bg-destructive/10 text-destructive-ink'
            : 'bg-[#fdf0c8] text-[#6e5200]',
      )}
    >
      {status === 'CLOSED' ? '완료' : '작업 충돌'}
    </span>
  )
}

// 회의 단계 네 칸(회의실 흐름 목업). 지난 단계는 초록 체크, 지금 단계는 노란 테두리, 볼 수 있는 단계는 눌러 옮겨 간다.
// 글자 대비는 S15P31A108-439 기준(지난·다음 단계 text-foreground/75)을 따른다.
function MeetingSteps({
  current,
  done,
  canView,
  captions,
  onSelect,
}: {
  current: number
  done: (index: number) => boolean
  canView: (index: number) => boolean
  captions: string[]
  onSelect: (index: number) => void
}) {
  return (
    <ol
      aria-label="회의 단계"
      className="grid grid-cols-2 gap-2 sm:grid-cols-4"
    >
      {meetingSteps.map((item, index) => {
        const now = index === current
        const finished = !now && done(index)
        const box = cn(
          'flex w-full items-center gap-2.5 rounded-xl px-3 py-2 text-left text-[13px] leading-tight',
          now
            ? 'bg-white font-semibold text-foreground ring-2 ring-primary ring-inset'
            : 'bg-[#f1efe7] text-foreground/75',
        )
        const body = (
          <>
            <span
              aria-hidden="true"
              className={cn(
                'grid size-[22px] shrink-0 place-items-center rounded-full text-xs font-bold',
                now
                  ? 'bg-primary text-primary-foreground'
                  : finished
                    ? 'bg-green-700 text-white'
                    : 'bg-white ring-1 ring-[#d6d1c4]',
              )}
            >
              {finished ? <Check className="size-3.5" /> : index + 1}
            </span>
            <span className="grid min-w-0 gap-0.5">
              <span className={cn(finished && 'text-foreground')}>
                {item.name}
              </span>
              <span className="truncate text-[11px] font-normal text-muted-foreground [@media(max-height:820px)]:hidden">
                {captions[index]}
              </span>
            </span>
          </>
        )
        return (
          <li key={item.name} aria-current={now ? 'step' : undefined}>
            {!now && canView(index) ? (
              <button
                type="button"
                className={cn(
                  box,
                  'outline-none hover:bg-[#e9e6dc] focus-visible:ring-2 focus-visible:ring-ring',
                )}
                onClick={() => onSelect(index)}
              >
                {body}
              </button>
            ) : (
              <div className={box}>{body}</div>
            )}
          </li>
        )
      })}
    </ol>
  )
}

// 이니셜 원. 팀원 색이 있으면 그 색을 옅게 깔고, 팀장은 진한 원이다(캔버스 PNL-06-v2).
function Initial({
  name,
  color,
  dark,
  large,
}: {
  name?: string
  color?: string
  dark?: boolean
  large?: boolean
}) {
  return (
    <span
      aria-hidden="true"
      style={dark || !color ? undefined : { background: `${color}33` }}
      className={cn(
        'grid shrink-0 place-items-center rounded-full font-bold',
        large ? 'size-8 text-xs' : 'size-[22px] text-[10px]',
        dark ? 'bg-[#2c291f] text-white' : 'bg-[#efeae0] text-[#3f3a2e]',
      )}
    >
      {name?.[0] ?? '?'}
    </span>
  )
}

function PersonChip({
  avatar,
  name,
  role,
  status,
  children,
}: {
  avatar: ReactNode
  name: string
  role?: string
  /** 참여 요청·불참처럼 아직 대화에 없는 사람의 상태. 칩에 글자로 보인다. */
  status?: string
  children?: ReactNode
}) {
  // 칩이 제목 줄에 들어가도록 역할은 글자로 펼치지 않고, 마우스를 올리거나 키보드로 칩에 초점을 두면 툴팁으로 보인다.
  // 화면 읽기 프로그램은 sr-only 글자로 읽는다. 참여 상태는 다른 곳에 없어 칩에 글자로 남긴다(리뷰 11-16).
  const detail = [role, status].filter(Boolean).join(' · ')
  const chip = (
    <span
      tabIndex={detail ? 0 : undefined}
      className={cn(
        'inline-flex h-8 items-center gap-1.5 rounded-full border bg-white pl-1 outline-none focus-visible:ring-2 focus-visible:ring-ring',
        children ? 'pr-1.5' : 'pr-3',
        line,
      )}
    >
      {avatar}
      <strong className="font-semibold">{name}</strong>
      {role && <span className="sr-only">{role}</span>}
      {status && (
        <span className="text-xs text-muted-foreground">{status}</span>
      )}
      {children}
    </span>
  )
  if (!detail) return chip
  return (
    <Tooltip>
      <TooltipTrigger asChild>{chip}</TooltipTrigger>
      <TooltipContent>
        {name} · {detail}
      </TooltipContent>
    </Tooltip>
  )
}

// 비비 말풍선은 노란 테두리로 눈에 띄게, 내 말은 노란 바탕으로 보인다(S15P31A108-457).
const beeBubble =
  'mt-1 rounded-2xl rounded-tl-sm border-2 border-primary bg-[#fff8e0] px-3 py-2.5 text-sm'

function Bubble({
  entry,
  mine,
  author,
  latestSummary,
  onOpenSummary,
  documentTitleOf,
}: {
  entry: MeetingEntry
  mine: boolean
  author?: Member
  latestSummary: boolean
  /** 최신 정리를 비비 정리 단계에서 연다. 정리는 대화 칸이 아니라 그 단계에서 읽는다. */
  onOpenSummary?: () => void
  documentTitleOf: (documentId: string) => string
}) {
  const time = formatTime(entry.created_at)
  if (entry.kind === 'SUMMARY') {
    // 비비 정리(MTG-A11)는 대화에 첫 줄만 남기고, 최신 정리는 '정리 화면에서 보기'로 비비 정리 단계를 연다.
    const [title, ...rest] = entry.text.split('\n')
    return (
      <div id={`entry-${entry.id}`} className="flex items-start gap-2.5">
        <BibiFace className="size-8 shrink-0" />
        <div className="max-w-[80%] min-w-0">
          <p className="text-xs text-muted-foreground">
            <strong className="text-foreground">비비</strong> · {time}
            {!latestSummary && ' · 이전 정리'}
          </p>
          <article className={beeBubble}>
            <p className="font-semibold">{title}</p>
            {entry.generation?.origin === 'TEMPLATE' && (
              <p className="text-xs text-muted-foreground">
                예시 템플릿 · 실제 AI 호출 없음
              </p>
            )}
            {onOpenSummary ? (
              <Button
                variant="outline"
                size="sm"
                className="mt-2 bg-white"
                onClick={onOpenSummary}
              >
                정리 화면에서 보기
                <ChevronRight />
              </Button>
            ) : (
              rest.length > 0 && (
                <p className="mt-1 leading-6 whitespace-pre-wrap">
                  {rest.join('\n')}
                </p>
              )
            )}
          </article>
        </div>
      </div>
    )
  }
  if (entry.kind !== 'TEXT') {
    // 비비 말은 첫 줄을 제목처럼 굵게 쓴다(캔버스 '작업이 부딪혀서 회의를 열었어요').
    const [title, ...rest] = entry.text.split('\n')
    return (
      <div id={`entry-${entry.id}`} className="flex items-start gap-2.5">
        <BibiFace className="size-8 shrink-0" />
        <div className="max-w-[80%] min-w-0">
          <p className="text-xs text-muted-foreground">
            <strong className="text-foreground">비비</strong> · {time}
          </p>
          <article className={beeBubble}>
            {entry.generation && (
              <p className="text-xs text-muted-foreground">
                {entry.generation.origin === 'TEMPLATE'
                  ? '예시 템플릿 · 실제 AI 호출 없음'
                  : `AI 생성 · ${entry.generation.model ?? '모델 정보 미제공'}`}
              </p>
            )}
            <p className="mt-1 font-semibold">{title}</p>
            {rest.length > 0 && (
              <p className="mt-1 leading-6 whitespace-pre-wrap">
                {rest.join('\n')}
              </p>
            )}
            {entry.generation && (
              <SourceLinks
                sources={entry.generation.sources}
                titleOf={documentTitleOf}
              />
            )}
          </article>
        </div>
      </div>
    )
  }
  if (mine)
    return (
      <div
        id={`entry-${entry.id}`}
        className="grid max-w-[80%] justify-items-end justify-self-end"
      >
        <p className="text-xs text-muted-foreground">
          {time} · <strong className="text-foreground">나</strong>
        </p>
        <p className="mt-1 rounded-2xl rounded-tr-sm bg-primary px-3 py-2.5 text-sm leading-6 whitespace-pre-wrap text-primary-foreground">
          {entry.text}
        </p>
      </div>
    )
  return (
    <div id={`entry-${entry.id}`} className="flex items-start gap-2.5">
      <Initial name={author?.name} color={author?.color} large />
      <div className="max-w-[80%] min-w-0">
        <p className="text-xs text-muted-foreground">
          <strong className="text-foreground">{author?.name ?? '팀원'}</strong>{' '}
          · {time}
        </p>
        <p
          className={cn(
            'mt-1 rounded-2xl rounded-tl-sm border bg-[#f7f5ef] px-3 py-2.5 text-sm leading-6 whitespace-pre-wrap',
            line,
          )}
        >
          {entry.text}
        </p>
      </div>
    </div>
  )
}

// 회의 대화와 정리 수정 요청에 쓰는 입력칸. 서버 한도(4,000자, MTG-A06·A12)에 맞춘다.
// 채팅 앱처럼 여러 줄로 쓰고 내용만큼 커진다(최대 160px 뒤 스크롤). Enter로 보내고 Shift+Enter로 줄을 바꾼다(S15P31A108-457).
const ENTRY_MAX = 4000
const messageSchema = (max: number) =>
  z.object({
    text: z
      .string()
      .trim()
      .min(1, '내용을 입력해 주세요.')
      .max(max, `${max.toLocaleString('ko-KR')}자까지 쓸 수 있어요.`),
  })
function InlineMessage({
  label,
  placeholder,
  submitLabel,
  busy,
  max,
  onSubmit,
  children,
}: {
  label: string
  placeholder: string
  submitLabel: string
  // 다른 요청(비비 정리 등)이 진행 중이면 보내지 못한다.
  busy?: boolean
  max: number
  onSubmit: (text: string) => Promise<void>
  children?: ReactNode
}) {
  const {
    register,
    handleSubmit,
    reset,
    formState: { errors, isSubmitting },
  } = useForm<{ text: string }>({
    resolver: zodResolver(messageSchema(max)),
    defaultValues: { text: '' },
  })
  return (
    <form
      noValidate
      className="grid gap-1"
      // 보내기에 성공했을 때만 입력을 비운다. 실패하면 쓰던 글을 그대로 둔다(기록이 더 붙어도 폼을 다시 만들지 않는다).
      onSubmit={handleSubmit(async (values) => {
        try {
          await onSubmit(values.text)
          reset()
        } catch {
          // 오류 안내는 호출한 쪽이 이미 보였다.
        }
      })}
    >
      <div className="flex items-end gap-2">
        <Textarea
          aria-label={label}
          placeholder={placeholder}
          aria-invalid={!!errors.text}
          rows={1}
          className="max-h-40 min-h-11 flex-1 resize-none bg-white py-2.5"
          {...register('text')}
          onKeyDown={(event) => {
            // 한글 조합 중의 Enter는 글자 확정이라 보내지 않는다.
            if (
              event.key === 'Enter' &&
              !event.shiftKey &&
              !event.nativeEvent.isComposing
            ) {
              event.preventDefault()
              event.currentTarget.form?.requestSubmit()
            }
          }}
        />
        <Button
          type="submit"
          size="lg"
          className="h-11 px-4"
          disabled={isSubmitting || busy}
        >
          {submitLabel}
        </Button>
        {children}
      </div>
      {errors.text && (
        <p role="alert" className="text-xs text-destructive">
          {errors.text.message}
        </p>
      )}
    </form>
  )
}

// 관련자(캔버스 PNL-06-v2-add-participant) · GitHub 리뷰어처럼 이름 찾기와 체크. 누르면 바로 더하거나(MTG-A04) 뺀다(MTG-A14).
// 참여자가 위에 오고, 줄마다 역할·이 회의의 작업을 둘째 줄에 쓴다. 빼기는 팀장만이고 팀장 줄은 뺄 수 없다. 팀원이 열면 체크를
// 더할 수만 있다.
function ParticipantPicker({
  members,
  joinedIds,
  isLeader,
  roleOf,
  onAdd,
  onRemove,
}: {
  members: Member[]
  joinedIds: string[]
  isLeader: boolean
  roleOf: (member: Member) => string
  onAdd: (memberId: string) => void
  onRemove: (memberId: string) => void
}) {
  const [query, setQuery] = useState('')
  // 팀장, 참여자, 나머지 순
  const rank = (member: Member) =>
    member.role === 'LEADER' ? 0 : joinedIds.includes(member.id) ? 1 : 2
  const shown = members
    .filter((member) => member.name.includes(query.trim()))
    .toSorted((a, b) => rank(a) - rank(b))
  return (
    <Popover onOpenChange={() => setQuery('')}>
      <PopoverTrigger asChild>
        <button
          type="button"
          className={cn(
            'inline-flex h-8 items-center gap-1.5 rounded-full border border-dashed bg-white px-3 text-[13px] font-semibold text-[#3f3a2e] hover:bg-[#fbfaf6] data-[state=open]:border-solid data-[state=open]:border-[#2c291f]',
            'border-[#d6d1c4]',
          )}
        >
          <Settings className="size-3.5" />
          관련자
        </button>
      </PopoverTrigger>
      <PopoverContent align="start" className="w-[300px] p-0">
        <p className={cn('border-b px-3 py-2.5 text-[13px] font-bold', line)}>
          관련자
        </p>
        <div className={cn('border-b p-2', line)}>
          <Input
            aria-label="이름 찾기"
            placeholder="이름으로 찾기"
            className="h-8"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
          />
        </div>
        <ul aria-label="팀원" className="grid max-h-64 overflow-y-auto py-1">
          {shown.map((member) => {
            const joined = joinedIds.includes(member.id)
            const locked = joined && (!isLeader || member.role === 'LEADER')
            const role = roleOf(member)
            return (
              <li key={member.id}>
                {/* 체크 상자가 sr-only라 키보드 초점 테두리는 줄 전체에 그린다(ActionForm 칩과 같은 클래스). 목록이 스크롤 영역이라 바깥 테두리가 잘리지 않게 안쪽으로 그린다. */}
                <label
                  className={cn(
                    'flex cursor-pointer items-center gap-2.5 px-3 py-1.5 hover:bg-[#fbfaf6] has-focus-visible:ring-2 has-focus-visible:ring-ring has-focus-visible:ring-inset',
                    locked && 'cursor-default',
                  )}
                >
                  <input
                    type="checkbox"
                    className="sr-only"
                    checked={joined}
                    disabled={locked}
                    onChange={() =>
                      joined ? onRemove(member.id) : onAdd(member.id)
                    }
                  />
                  <Check
                    aria-hidden="true"
                    className={cn(
                      'size-4 shrink-0 text-[#2c291f]',
                      !joined && 'invisible',
                      locked && 'text-[#c9c4b8]',
                    )}
                  />
                  <Initial
                    name={member.name}
                    color={member.color}
                    dark={member.role === 'LEADER'}
                  />
                  <span
                    className={cn(
                      'grid text-[13px] leading-[18px]',
                      locked && 'text-muted-foreground',
                    )}
                  >
                    {member.name}
                    <span className="text-xs text-muted-foreground">
                      {member.role === 'LEADER' && joined
                        ? '팀장 · 뺄 수 없어요'
                        : role}
                    </span>
                  </span>
                </label>
              </li>
            )
          })}
          {!shown.length && (
            <li className="px-3 py-1.5 text-xs text-muted-foreground">
              찾는 팀원이 없어요.
            </li>
          )}
        </ul>
        <p
          className={cn(
            'border-t px-3 py-2 text-xs leading-[18px] text-muted-foreground',
            line,
          )}
        >
          {isLeader
            ? '누르면 바로 추가되거나 빠지고, 그 사람에게 알림이 가요.'
            : '누르면 바로 추가되고 그 사람에게 알림이 가요. 빼기는 팀장만 할 수 있어요.'}
        </p>
      </PopoverContent>
    </Popover>
  )
}
