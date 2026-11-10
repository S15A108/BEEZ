import { useEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import type { InfiniteData } from '@tanstack/react-query'
import {
  useInfiniteQuery,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query'
import { zodResolver } from '@hookform/resolvers/zod'
import { useForm } from 'react-hook-form'
import { z } from 'zod'
import { Check, ChevronLeft, Settings, Users, X } from 'lucide-react'
import { BibiFace } from '@/components/BibiFace'
import { documentsApi, meetingsApi, membersApi, tasksApi } from '@/api/services'
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
import type { EntriesResponse } from './schemas'
import { SummaryCard } from './SummaryCard'
import { useMeetingList } from './useMeetingList'
import { MeetingRevisionDraft } from './MeetingRevisionDraft'

interface Props {
  projectId: string
  isLeader: boolean
}
const participationLabel = {
  INVITED: '참여 요청',
  JOINED: '',
  DECLINED: '불참',
}

const meetingSteps = ['대화', '비비 정리', '회의 끝내기', '변경 제안 승인']
// 캔버스 PNL-06-v2 회의실의 칸 테두리와 바탕
const line = 'border-[#e4e0d4]'

// 회의는 사람이 만들지 않는다. 작업이 부딪히면 비비가 팀장과 관련 작업자를 불러 연다.
// 캔버스 PNL-06-v2(meetings·meetings-member·bibi-review·draft·draft-result·add-participant·empty): 베이지 바탕에 왼쪽 회의 목록
// 카드, 오른쪽에 회의 머리·참여자·안건과 흰 대화 칸을 두고, 입력줄은 창 아래에 고정한다. 진행 중/끝난 회의로 나누고, 안건(왜
// 열렸는지)을 대화 위에 두고, 단계(대화 → 비비 정리 → 회의 끝내기 → 변경 제안 승인)를 보인다.
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
  // 끝내기 확인 화면을 연 회의. 알림 등으로 다른 회의가 열리면 그 회의에서는 확인 화면이 뜨지 않는다.
  const [endingId, setEndingId] = useState<string | null>(null)
  const [showRevision, setShowRevision] = useState(false)
  const [agendaOpen, setAgendaOpen] = useState(false)
  const [earlierOpen, setEarlierOpen] = useState(false)
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
  const ending = !!selectedId && endingId === selectedId
  const setEnding = (value: boolean) =>
    setEndingId(value ? (selectedId ?? null) : null)
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
  // 새 메시지가 오면 대화 맨 아래로 내린다. 이전 기록을 더 불러올 때는 그대로 둔다.
  useEffect(() => {
    if (log.current) log.current.scrollTop = log.current.scrollHeight
  }, [latest?.id])
  // 비비 정리(MTG-A11): 가장 최근 SUMMARY 기록. 구조화한 정리(summary_id·바뀔 문서 등)는 최신 정리 조회(MTG-A15)로 받아서
  // 새로 고치거나 다른 기기에서 열어도 끝내기·수정 요청을 이어 갈 수 있다.
  const review = notes.find((entry) => entry.kind === 'SUMMARY')
  const summaryKey = ['projects', projectId, 'meeting-summary', selectedId]
  const summaryQuery = useQuery({
    queryKey: summaryKey,
    queryFn: () => meetingsApi.latestSummary(projectId, selectedId!),
    enabled: !!selectedId && !!review,
    retry: false,
  })
  const summary =
    review && summaryQuery.data?.entry.id === review.id
      ? summaryQuery.data
      : undefined
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
  // 새 정리는 정리 캐시와 기록 목록 캐시에 함께 넣는다. 기록 목록만 재조회 전이면 정리 카드가 잠깐 글로 바뀌고 입력줄이 깜빡인다.
  const remember = (meetingId: string, next: MeetingSummary) => {
    client.setQueryData(
      ['projects', projectId, 'meeting-summary', meetingId],
      next,
    )
    client.setQueryData<InfiniteData<EntriesResponse>>(
      ['projects', projectId, 'meeting-entries', meetingId],
      (data) => {
        if (
          !data ||
          data.pages.some((page) =>
            page.items.some((item) => item.id === next.entry.id),
          )
        )
          return data
        const pages = data.pages.slice()
        const last = pages.length - 1
        pages[last] = {
          ...pages[last],
          items: [...pages[last].items, next.entry],
        }
        return { ...data, pages }
      },
    )
  }
  // MTG-A11: 가장 최근 기록까지의 대화를 비비가 정리한다.
  const requestSummary = (meetingId: string, throughId: string) =>
    runWithNotice(async () => {
      remember(
        meetingId,
        await meetingsApi.summarize(projectId, meetingId, {
          through_entry_id: throughId,
        }),
      )
    }, '비비가 회의 내용을 정리했어요.')
  const scrollToEntry = (entryId: string) => {
    setEarlierOpen(true)
    requestAnimationFrame(() =>
      document
        .getElementById(`entry-${entryId}`)
        ?.scrollIntoView({ block: 'center' }),
    )
  }
  const select = (id: string) => {
    setSelection({ id, focus })
    setEnding(false)
    setShowRevision(false)
    setAgendaOpen(false)
    setEarlierOpen(false)
    setNotice(null)
  }
  return (
    // 창 본문을 베이지로 채운다(캔버스 PNL-06-v2: 머리 아래 #f3f1ea). 창의 안쪽 여백만큼 밖으로 넓힌다.
    // 넓은 화면에서는 창 높이에 맞춰 대화 칸만 안에서 스크롤한다. 창의 1fr 줄이 내용 높이로 늘지 않게 높이 0에 min-h로 채운다.
    <section
      className={cn(
        '-mx-5 -mb-5 flex min-h-[calc(100%+1.25rem)] flex-col gap-3.5 border-t bg-[#f3f1ea] px-5 py-5 sm:-mx-8 sm:-mb-8 sm:min-h-[calc(100%+2rem)] sm:px-7 lg:h-0 lg:overflow-y-auto',
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
            // 회의 끝내기 확인은 목록 없이 창 전체를 쓴다(캔버스 PNL-06-v2-draft).
            <div
              className={
                ending && review && summary
                  ? 'flex flex-1 flex-col'
                  : 'grid flex-1 gap-5 lg:min-h-0 lg:grid-cols-[280px_minmax(0,1fr)] lg:grid-rows-[minmax(0,1fr)]'
              }
            >
              <div
                hidden={!!(ending && review && summary)}
                className={cn(
                  'grid content-start gap-4 self-start rounded-2xl border bg-white p-3.5 lg:max-h-full lg:overflow-y-auto',
                  line,
                )}
              >
                {(
                  [
                    ['진행 중', open, '진행 중인 회의가 없어요.'],
                    ['끝난 회의', closed, '끝난 회의가 없어요.'],
                  ] as const
                ).map(([heading, group, empty]) => (
                  <div key={heading}>
                    <h3 className="mb-2.5 flex justify-between text-sm font-bold">
                      {heading}
                      <span className="font-medium text-muted-foreground">
                        {group.length}
                      </span>
                    </h3>
                    {group.length ? (
                      <ul className="grid gap-2">
                        {group.map((meeting) => (
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
                      <p className="text-xs text-muted-foreground">{empty}</p>
                    )}
                  </div>
                ))}
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
                    const closedMeeting = meeting.status === 'CLOSED'
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
                    if (ending && review && summary)
                      return (
                        <div className="flex flex-1 flex-col gap-3.5">
                          <Button
                            variant="ghost"
                            size="sm"
                            className="-ml-2 self-start"
                            onClick={() => setEnding(false)}
                          >
                            <ChevronLeft />
                            {meeting.title}
                          </Button>
                          <div>
                            <h3 className="text-lg font-bold">
                              회의를 끝낼까요?
                            </h3>
                            <p className="mt-0.5 text-[13px] leading-5 text-muted-foreground">
                              비비 정리로 변경 제안을 만들고 회의를 닫아요. 대화
                              기록은 그대로 남아요.
                            </p>
                            <MeetingSteps current={2} />
                          </div>
                          <div className="grid flex-1 content-start gap-3">
                            <article
                              className={cn(
                                'rounded-2xl border bg-white px-[18px] py-4 text-sm',
                                line,
                              )}
                            >
                              <h4 className="mb-2.5 flex items-center justify-between gap-2 text-sm font-bold">
                                비비 정리
                                <span className="rounded-full bg-[#fdf0c8] px-2 py-0.5 text-xs font-semibold text-[#6e5200]">
                                  변경 제안에 담길 내용
                                </span>
                              </h4>
                              <SummaryCard
                                summary={summary}
                                documentTitleOf={documentTitleOf}
                                nameOfMember={nameOfMember}
                                onShowEvidence={(entryId) => {
                                  setEnding(false)
                                  scrollToEntry(entryId)
                                }}
                              />
                            </article>
                            <ul className="list-disc pl-[18px] text-[13px] leading-[21px] text-[#57534e]">
                              {summary.document_changes.length > 0 ? (
                                notify.length > 0 && (
                                  <li>
                                    {notify.join(', ')}에게 변경 제안을 알려요.
                                  </li>
                                )
                              ) : (
                                <li>
                                  바뀔 문서가 없어 변경 제안은 만들어지지
                                  않아요.
                                </li>
                              )}
                              <li>끝난 회의에는 더 쓸 수 없어요.</li>
                            </ul>
                          </div>
                          <div
                            // 카드가 길어도 버튼 줄은 창 아래에 붙어 항상 보인다(대화 입력줄과 같은 방식).
                            className={cn(
                              'sticky -bottom-5 z-10 -mb-5 flex flex-wrap items-center justify-between gap-2 border-t bg-[#f3f1ea] pt-3.5 pb-5 sm:-bottom-8',
                              line,
                            )}
                          >
                            {talkedAfterSummary ? (
                              <p className="text-xs text-[#8a6a00]">
                                정리한 뒤에 새 대화가 있어요. 끝내기 전에
                                비비에게 정리를 다시 요청해 주세요.
                              </p>
                            ) : (
                              <p className="text-xs text-muted-foreground">
                                팀장만 끝낼 수 있어요. 기준은 변경 제안에서
                                승인·발행해야 바뀌어요.
                              </p>
                            )}
                            <div className="flex flex-wrap gap-2">
                              <Button
                                variant="outline"
                                disabled={
                                  action.busy || !throughEntry || !allLoaded
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
                              <Button
                                variant="outline"
                                onClick={() => setEnding(false)}
                              >
                                취소
                              </Button>
                              <Button
                                disabled={
                                  action.busy || !isLeader || talkedAfterSummary
                                }
                                onClick={() =>
                                  void runWithNotice(async () => {
                                    try {
                                      const result = await meetingsApi.close(
                                        projectId,
                                        meeting.id,
                                        { summary_id: summary.id },
                                      )
                                      setClosedResult({
                                        meetingId: meeting.id,
                                        proposalId: result.change_proposal_id,
                                        notified:
                                          result.notified_member_ids.map(
                                            nameOfMember,
                                          ),
                                      })
                                      // 진행 중 목록에서 빠져도 방금 끝낸 회의를 계속 보여 준다.
                                      setSelection({ id: meeting.id, focus })
                                      setEnding(false)
                                    } catch (error) {
                                      const code = getApiErrorCode(error)
                                      // 오래된 정리로는 끝낼 수 없다. 대화로 돌아가 정리를 다시 요청하게 한다.
                                      if (
                                        code === staleSummaryCode ||
                                        code === closedMeetingCode
                                      )
                                        setEnding(false)
                                      throw error
                                    }
                                  }, '').catch(() => undefined)
                                }
                              >
                                회의 끝내고 변경 제안 만들기
                              </Button>
                            </div>
                          </div>
                        </div>
                      )
                    const conversation = notes.toReversed()
                    // 비비 정리가 나오면 앞의 대화는 접고 마지막 두 메시지와 정리만 보인다(캔버스 PNL-06-v2-bibi-review).
                    // 정리 뒤에 대화가 쌓여도 최신 정리와 그 앞 두 메시지는 항상 보이게, 정리 위치까지만 접는다.
                    const reviewAt = review
                      ? conversation.findIndex(
                          (entry) => entry.id === review.id,
                        )
                      : -1
                    const folded =
                      review && !earlierOpen
                        ? Math.max(
                            0,
                            Math.min(conversation.length - 3, reviewAt - 2),
                          )
                        : 0
                    return (
                      <div className="flex min-h-0 min-w-0 flex-col gap-3">
                        {closeResult?.meetingId === meeting.id && (
                          <section
                            aria-label="회의 끝낸 결과"
                            className="flex flex-wrap items-center justify-between gap-2 rounded-2xl border border-[#f3e2a6] bg-[#fff8e0] px-3.5 py-3 text-sm"
                          >
                            <span className="grid gap-0.5">
                              <strong>
                                {closeResult.proposalId
                                  ? '회의를 끝내고 변경 제안을 올렸어요.'
                                  : '회의를 끝냈어요. 바뀔 문서가 없어 변경 제안은 만들지 않았어요.'}
                              </strong>
                              {closeResult.proposalId && (
                                <span className="text-xs text-muted-foreground">
                                  {closeResult.notified.length
                                    ? `${closeResult.notified.join(', ')}에게 알렸어요. `
                                    : ''}
                                  기준은 변경 제안을 승인·발행해야 바뀌어요.
                                </span>
                              )}
                            </span>
                          </section>
                        )}
                        <div className="flex flex-wrap items-start justify-between gap-4">
                          <div className="grid gap-1">
                            <div className="flex flex-wrap items-center gap-2">
                              <h3 className="text-lg font-bold">
                                {meeting.title}
                              </h3>
                              <MeetingChip
                                status={meeting.status}
                                origin={meeting.origin}
                              />
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
                            <MeetingSteps
                              current={closedMeeting ? 3 : review ? 1 : 0}
                            />
                          </div>
                          {closedMeeting ? (
                            meeting.change_proposal_id && (
                              <Button
                                onClick={() =>
                                  openPane(
                                    'changes',
                                    meeting.change_proposal_id,
                                  )
                                }
                              >
                                변경 제안 보기
                              </Button>
                            )
                          ) : (
                            <span className="grid justify-items-end gap-1">
                              <span className="flex flex-wrap justify-end gap-2">
                                <Button
                                  variant={summary ? 'outline' : 'default'}
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
                                {isLeader && !!participant && !!summary && (
                                  <Button onClick={() => setEnding(true)}>
                                    회의 끝내기
                                  </Button>
                                )}
                              </span>
                              {!isLeader && (
                                <span className="text-xs text-muted-foreground">
                                  팀장만 요청할 수 있어요
                                </span>
                              )}
                            </span>
                          )}
                        </div>
                        <div
                          role="group"
                          aria-label="참여자"
                          className="flex flex-wrap items-center gap-2 text-[13px]"
                        >
                          <span className="mr-0.5 text-muted-foreground">
                            참여자 {meeting.participants.length + 1}
                          </span>
                          {/* 참여자 칩(캔버스 PNL-06-v2): 이니셜 원, 이름, 역할·이 회의의 작업. 비비와 팀장 칩에는 × 없음, 빼기는 팀장만. */}
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
                                  participationLabel[item.status],
                                ]
                                  .filter(Boolean)
                                  .join(' · ')}
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
                          {(isLeader || !!participant) && !closedMeeting && (
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
                                    meetingsApi.invite(projectId, meeting.id, {
                                      member_ids: [memberId],
                                    }),
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
                        {review &&
                          !summary &&
                          summaryQuery.isError &&
                          !closedMeeting &&
                          isLeader && (
                            <p className="rounded-xl border border-dashed border-[#d6d1c4] bg-white px-3.5 py-2.5 text-[13px] text-muted-foreground">
                              비비 정리를 불러오지 못했어요. 비비에게 정리를
                              다시 요청하면 새로 받아요.
                            </p>
                          )}
                        {meeting.conflict_summary &&
                          (review && !agendaOpen ? (
                            <section
                              aria-label="안건"
                              className={cn(
                                'flex flex-wrap items-center justify-between gap-2 rounded-xl border bg-white px-3.5 py-2.5 text-[13px]',
                                line,
                              )}
                            >
                              <span>
                                <strong className="mr-2">안건</strong>
                                {meeting.conflict_summary}
                                {meeting.task_ids?.length
                                  ? ` · ${meeting.task_ids.map((id) => taskOf(id)?.key ?? '작업').join('과 ')}가 달라요`
                                  : ''}
                              </span>
                              <button
                                type="button"
                                className="text-xs font-semibold text-[#8a6a00]"
                                onClick={() => setAgendaOpen(true)}
                              >
                                펼치기
                              </button>
                            </section>
                          ) : (
                            <section
                              aria-label="안건"
                              className="grid gap-2.5 rounded-2xl border border-[#f3e2a6] bg-[#fff8e0] px-3.5 py-3 text-sm"
                            >
                              <p className="flex flex-wrap items-baseline justify-between gap-2">
                                <strong>{meeting.conflict_summary}</strong>
                                <span className="text-xs text-muted-foreground">
                                  비비가 찾은 충돌 ·{' '}
                                  {formatTime(meeting.created_at)}
                                </span>
                              </p>
                              {/* API 없음: 작업마다 어떻게 다른지와 기준 문단(REQ-005 보기)은 충돌 상세 필드가 생기면 카드에 붙인다. 지금은 비비 첫 말풍선에 있다. */}
                              <ul className="grid gap-2 sm:grid-cols-2">
                                {(meeting.task_ids ?? []).map((id) => {
                                  const task = taskOf(id)
                                  return (
                                    <li key={id}>
                                      <button
                                        type="button"
                                        className={cn(
                                          'w-full rounded-xl border bg-white px-3 py-2 text-left text-xs text-muted-foreground hover:bg-[#fbfaf6]',
                                          line,
                                        )}
                                        onClick={() => openPane('tasks', id)}
                                      >
                                        <strong className="text-foreground">
                                          {task?.key ?? '작업'}
                                        </strong>{' '}
                                        {task?.current_plan_revision.plan.title}
                                        {task &&
                                          ` · ${task.assignee_member_id ? nameOfMember(task.assignee_member_id) : '담당자 없음'}`}
                                      </button>
                                    </li>
                                  )
                                })}
                              </ul>
                            </section>
                          ))}
                        {isLeader &&
                          participant?.status === 'JOINED' &&
                          !closedMeeting && (
                            <div className="grid gap-2">
                              <Button
                                type="button"
                                variant="outline"
                                onClick={() =>
                                  setShowRevision((value) => !value)
                                }
                              >
                                {showRevision
                                  ? '회의 수정 초안 접기'
                                  : '회의 수정 초안 만들기'}
                              </Button>
                              {showRevision && (
                                <MeetingRevisionDraft
                                  projectId={projectId}
                                  meetingId={meeting.id}
                                  entries={notes}
                                  hasMore={!!entries.hasNextPage}
                                  loadingMore={entries.isFetchingNextPage}
                                  loadMore={() => void entries.fetchNextPage()}
                                />
                              )}
                            </div>
                          )}
                        {/* lg 미만은 창이 내용 높이로 늘어나므로 대화 칸 높이를 묶어 안에서 스크롤한다(새 메시지로 내리기). lg부터는 창 높이를 따른다. */}
                        <AsyncState query={entries}>
                          {() => (
                            <div
                              ref={log}
                              role="log"
                              aria-label="회의 대화"
                              className={cn(
                                'grid max-h-[50vh] min-h-48 flex-1 content-start gap-3.5 overflow-auto rounded-2xl border bg-white p-4 lg:max-h-none',
                                line,
                              )}
                            >
                              {folded > 0 ? (
                                <button
                                  type="button"
                                  className="justify-self-center text-xs text-muted-foreground hover:underline"
                                  onClick={() => setEarlierOpen(true)}
                                >
                                  앞의 대화 {folded}개
                                </button>
                              ) : null}
                              {conversation.length ? (
                                conversation.slice(folded).map((entry) => {
                                  const author = people.find(
                                    (member) =>
                                      member.account_id ===
                                      entry.author_account_id,
                                  )
                                  return (
                                    <Bubble
                                      key={entry.id}
                                      entry={entry}
                                      mine={
                                        entry.author_account_id ===
                                        session.data?.id
                                      }
                                      author={author}
                                      summary={
                                        entry.id === review?.id
                                          ? summary
                                          : undefined
                                      }
                                      latestSummary={entry.id === review?.id}
                                      documentTitleOf={documentTitleOf}
                                      nameOfMember={nameOfMember}
                                      onShowEvidence={scrollToEntry}
                                    />
                                  )
                                })
                              ) : (
                                <p className="text-sm text-muted-foreground">
                                  아직 대화가 없어요.
                                </p>
                              )}
                            </div>
                          )}
                        </AsyncState>
                        {/* 입력줄은 창 아래 끝에 붙여 둔다(창 전체가 스크롤해도 보이게). 창의 안쪽 여백만큼 내려 아래로 지나가는 대화를 가린다. */}
                        <div className="sticky -bottom-5 z-10 -mb-5 bg-[#f3f1ea] pt-1 pb-5 sm:-bottom-8">
                          {closedMeeting ? (
                            <p className="rounded-[10px] border border-dashed border-[#d6d1c4] bg-white px-3 py-2.5 text-[13px] text-muted-foreground">
                              끝난 회의라 메시지를 보낼 수 없어요.
                            </p>
                          ) : isLeader && !!participant && summary ? (
                            <div className="grid gap-1.5">
                              {talkedAfterSummary && (
                                <p className="text-xs text-[#8a6a00]">
                                  정리한 뒤에 새 대화가 있어요. 끝내기 전에
                                  비비에게 정리를 다시 요청해 주세요.
                                </p>
                              )}
                              <InlineMessage
                                key={`fix:${summary.id}`}
                                label="비비에게 고칠 점"
                                placeholder="정리에서 고칠 점을 비비에게 말해 주세요"
                                submitLabel="수정 요청"
                                outline
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
                            </div>
                          ) : participant?.status === 'JOINED' ? (
                            <InlineMessage
                              key={`${meeting.id}:${notes.length}`}
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
                                      {
                                        text,
                                      },
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

function MeetingChip({
  status,
  origin,
}: {
  status?: 'OPEN' | 'CLOSED'
  origin?: 'BEE_CONFLICT' | 'HUMAN'
}) {
  if (status !== 'CLOSED' && origin !== 'BEE_CONFLICT') return null
  return (
    <span
      className={cn(
        'inline-flex h-[22px] w-fit items-center rounded-full px-2 text-xs font-semibold',
        status === 'CLOSED'
          ? 'bg-[#f0eee8] text-[#57534e]'
          : 'bg-[#fdf0c8] text-[#6e5200]',
      )}
    >
      {status === 'CLOSED' ? '완료' : '작업 충돌'}
    </span>
  )
}

// 회의 단계: 대화 → 비비 정리 → 회의 끝내기 → 변경 제안 승인
function MeetingSteps({ current }: { current: number }) {
  return (
    <ol
      aria-label="회의 단계"
      className="mt-1.5 flex flex-wrap items-center gap-0.5 text-xs"
    >
      {meetingSteps.map((step, index) => (
        <li
          key={step}
          aria-current={index === current ? 'step' : undefined}
          className="flex items-center gap-0.5 text-[#c9c4b8]"
        >
          <span
            className={cn(
              'flex h-6 items-center gap-[5px] rounded-full px-[9px] whitespace-nowrap',
              index < current && 'font-semibold text-[#57534e]',
              index === current && 'bg-[#2c291f] font-bold text-white',
              index > current && 'font-medium text-[#a3a3a3]',
            )}
          >
            <span aria-hidden="true">{index < current ? '✓' : index + 1}</span>
            {step}
          </span>
          {index < meetingSteps.length - 1 && <span aria-hidden="true">›</span>}
        </li>
      ))}
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
  children,
}: {
  avatar: ReactNode
  name: string
  role?: string
  children?: ReactNode
}) {
  return (
    <span
      className={cn(
        'inline-flex h-8 items-center gap-1.5 rounded-full border bg-white pl-1',
        children ? 'pr-1.5' : 'pr-3',
        line,
      )}
    >
      {avatar}
      <strong className="font-semibold">{name}</strong>
      {role && <span className="text-xs text-muted-foreground">{role}</span>}
      {children}
    </span>
  )
}

function Bubble({
  entry,
  mine,
  author,
  summary,
  latestSummary,
  documentTitleOf,
  nameOfMember,
  onShowEvidence,
}: {
  entry: MeetingEntry
  mine: boolean
  author?: Member
  /** 이 기록이 최신 정리일 때 구조화된 정리(이 화면에서 요청했을 때만 있다) */
  summary?: MeetingSummary
  latestSummary: boolean
  documentTitleOf: (documentId: string) => string
  nameOfMember: (memberId: string) => string
  onShowEvidence: (entryId: string) => void
}) {
  const time = formatTime(entry.created_at)
  if (entry.kind === 'SUMMARY') {
    // 비비 정리(MTG-A11)는 노란 말풍선이다. 최신 정리는 구조화한 카드로, 이전 정리는 문장으로 보인다.
    const [title, ...rest] = entry.text.split('\n')
    return (
      <div id={`entry-${entry.id}`} className="flex items-start gap-2.5">
        <BibiFace className="size-8 shrink-0" />
        <div className="max-w-[80%] min-w-0 flex-1">
          <p className="text-xs text-muted-foreground">
            <strong className="text-foreground">비비</strong> · {time}
            {!latestSummary && ' · 이전 정리'}
          </p>
          <article className="mt-1 rounded-2xl rounded-tl-sm border border-[#f3e2a6] bg-[#fdf5d8] px-3 py-2.5 text-sm">
            <p className="font-semibold">{title}</p>
            {entry.generation?.origin === 'TEMPLATE' && (
              <p className="text-xs text-muted-foreground">
                예시 템플릿 · 실제 AI 호출 없음
              </p>
            )}
            {summary ? (
              <div className="mt-2">
                <SummaryCard
                  summary={summary}
                  documentTitleOf={documentTitleOf}
                  nameOfMember={nameOfMember}
                  onShowEvidence={onShowEvidence}
                />
              </div>
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
          <article className="mt-1 rounded-2xl rounded-tl-sm border border-[#f3e2a6] bg-[#fdf5d8] px-3 py-2.5 text-sm">
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
              <SourceLinks sources={entry.generation.sources} />
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
        <p className="mt-1 rounded-2xl rounded-tr-sm bg-[#2c291f] px-3 py-2.5 text-sm leading-6 whitespace-pre-wrap text-white">
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

// 창 아래에 고정한 한 줄 입력(캔버스 '메시지 보내기 · 보내기', '정리에서 고칠 점… · 수정 요청 · 회의 끝내기').
// 서버 한도(기록 본문·고칠 점 모두 4,000자, MTG-A06·A12)에 맞춘다.
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
  outline,
  busy,
  max,
  onSubmit,
  children,
}: {
  label: string
  placeholder: string
  submitLabel: string
  outline?: boolean
  // 다른 요청(비비 정리 등)이 진행 중이면 보내지 못한다.
  busy?: boolean
  max: number
  onSubmit: (text: string) => Promise<void>
  children?: ReactNode
}) {
  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<{ text: string }>({
    resolver: zodResolver(messageSchema(max)),
    defaultValues: { text: '' },
  })
  return (
    <form
      noValidate
      className="grid gap-1"
      onSubmit={handleSubmit((values) =>
        onSubmit(values.text).catch(() => undefined),
      )}
    >
      <div className="flex items-center gap-2">
        <Input
          aria-label={label}
          placeholder={placeholder}
          aria-invalid={!!errors.text}
          className="h-10 flex-1 bg-white"
          {...register('text')}
        />
        <Button
          type="submit"
          variant={outline ? 'outline' : 'default'}
          className="h-10"
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
