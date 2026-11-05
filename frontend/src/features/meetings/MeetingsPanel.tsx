import { useEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { useInfiniteQuery, useQuery } from '@tanstack/react-query'
import { zodResolver } from '@hookform/resolvers/zod'
import { useForm } from 'react-hook-form'
import { z } from 'zod'
import { Check, ChevronLeft, Settings, Users, X } from 'lucide-react'
import { BibiFace } from '@/components/BibiFace'
import { meetingsApi, membersApi, tasksApi } from '@/api/services'
import type { MeetingEntry, Member } from '@/api/contracts/common'
import { AsyncState } from '@/components/AsyncState'
import { SourceLinks } from '@/components/SourceLinks'
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
import { useMeetingList } from './useMeetingList'

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
  const [ending, setEnding] = useState(false)
  const [agendaOpen, setAgendaOpen] = useState(false)
  const [earlierOpen, setEarlierOpen] = useState(false)
  const list = useMeetingList(projectId)
  const meetings = list.data?.pages.flatMap((page) => page.items) ?? []
  const open = meetings.filter((meeting) => meeting.status !== 'CLOSED')
  const closed = meetings.filter((meeting) => meeting.status === 'CLOSED')
  // 처음에는 진행 중인 회의를 연다.
  const selectedId =
    selection?.focus === focus
      ? selection.id
      : (focus ?? open[0]?.id ?? meetings[0]?.id)
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
  const taskOf = (id: string) =>
    tasks.data?.items.find((task) => task.id === id)
  const nameOfMember = (id: string) =>
    people.find((member) => member.id === id)?.name ?? '팀원'
  const nameOfAccount = (id?: string) =>
    people.find((member) => member.account_id === id)?.name ?? '팀장'
  const me = people.find((member) => member.account_id === session.data?.id)
  // 목록은 최신순으로 온다. 대화는 오래된 것부터 보여 준다.
  const notes = entries.data?.pages.flatMap((page) => page.items) ?? []
  const latest = notes[0]
  const log = useRef<HTMLDivElement>(null)
  // 새 메시지가 오면 대화 맨 아래로 내린다. 이전 기록을 더 불러올 때는 그대로 둔다.
  useEffect(() => {
    if (log.current) log.current.scrollTop = log.current.scrollHeight
  }, [latest?.id])
  const review = latest?.kind === 'BEE_ANALYSIS' ? latest : undefined
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
  const perform = (operation: () => Promise<unknown>, message: string) => {
    void action.run(operation, message).catch(() => undefined)
  }
  const requestReview = (throughId: string) =>
    meetingsApi.analyze(projectId, selectedId!, { through_entry_id: throughId })
  const select = (id: string) => {
    setSelection({ id, focus })
    setEnding(false)
    setAgendaOpen(false)
    setEarlierOpen(false)
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
      {action.message && (
        <p
          role="status"
          className="rounded-xl bg-primary/10 px-3.5 py-2.5 text-sm"
        >
          {action.message}
        </p>
      )}
      {action.error && (
        <p
          role="alert"
          className="rounded-xl bg-destructive/5 px-3.5 py-2.5 text-sm text-destructive"
        >
          {action.error}
        </p>
      )}
      <AsyncState query={list}>
        {() =>
          meetings.length ? (
            // 회의 끝내기 확인은 목록 없이 창 전체를 쓴다(캔버스 PNL-06-v2-draft).
            <div
              className={
                ending && review
                  ? 'flex flex-1 flex-col'
                  : 'grid flex-1 gap-5 lg:min-h-0 lg:grid-cols-[280px_minmax(0,1fr)] lg:grid-rows-[minmax(0,1fr)]'
              }
            >
              <div
                hidden={!!(ending && review)}
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
                    // 회의 결과 변경 제안을 알릴 관련 작업 담당자(나 말고)
                    const notify = (meeting.task_ids ?? []).flatMap((id) => {
                      const task = taskOf(id)
                      return task && task.assignee_member_id !== me?.id
                        ? [
                            `${task.key} 담당 ${nameOfMember(task.assignee_member_id)}`,
                          ]
                        : []
                    })
                    if (ending && review)
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
                              <p className="leading-6 whitespace-pre-wrap">
                                {review.text}
                              </p>
                              {/* API 없음(MTG-A11 수정안 요청): 바뀔 문서·문장(diff)과 근거 발언은 구조화한 비비 정리가 생기면 그린다. */}
                            </article>
                            <ul className="list-disc pl-[18px] text-[13px] leading-[21px] text-[#57534e]">
                              {notify.length > 0 && (
                                <li>
                                  {notify.join(', ')}에게 변경 제안을 알려요.
                                </li>
                              )}
                              <li>끝난 회의에는 더 쓸 수 없어요.</li>
                            </ul>
                          </div>
                          <div
                            className={cn(
                              'flex flex-wrap items-center justify-between gap-2 border-t pt-3.5',
                              line,
                            )}
                          >
                            <p className="text-xs text-muted-foreground">
                              팀장만 끝낼 수 있어요. 기준은 변경 제안에서
                              승인·발행해야 바뀌어요.
                            </p>
                            <div className="flex gap-2">
                              <Button
                                variant="outline"
                                onClick={() => setEnding(false)}
                              >
                                취소
                              </Button>
                              {/* API 없음: 회의 끝내기(MTG-A13 수정안 요청)와 회의 결과 변경 제안(제안자 = 회의를 끝낸 팀장)으로 잇기. 끝낸 뒤에는 그 제안을 변경 제안 창에서 연다. */}
                              <Button
                                disabled
                                title="서버 기능이 준비되면 열려요"
                              >
                                회의 끝내고 변경 제안 만들기
                              </Button>
                            </div>
                          </div>
                        </div>
                      )
                    const conversation = notes.toReversed()
                    // 비비 정리가 나오면 앞의 대화는 접고 마지막 두 메시지와 정리만 보인다(캔버스 PNL-06-v2-bibi-review).
                    const folded =
                      review && !earlierOpen
                        ? Math.max(0, conversation.length - 3)
                        : 0
                    return (
                      <div className="flex min-h-0 min-w-0 flex-col gap-3">
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
                              <Button
                                disabled={
                                  !isLeader ||
                                  latest?.kind !== 'TEXT' ||
                                  action.busy
                                }
                                onClick={() =>
                                  perform(
                                    () => requestReview(latest!.id),
                                    '비비가 회의 내용을 정리했어요.',
                                  )
                                }
                              >
                                비비에게 정리 요청
                              </Button>
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
                                          ` · ${nameOfMember(task.assignee_member_id)}`}
                                      </button>
                                    </li>
                                  )
                                })}
                              </ul>
                            </section>
                          ))}
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
                              ) : (
                                entries.hasNextPage && (
                                  <Button
                                    variant="outline"
                                    size="sm"
                                    className="justify-self-center"
                                    onClick={() => void entries.fetchNextPage()}
                                  >
                                    이전 기록 더 보기
                                  </Button>
                                )
                              )}
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
                        {/* 입력줄은 창 아래 끝에 붙여 둔다(창 전체가 스크롤해도 보이게). 창의 안쪽 여백만큼 내려 아래로 지나가는 대화를 가린다.
                            API 없음(MTG-A12 수정안 요청): 정리 수정 요청 전용 필드. 지금은 고칠 점을 메시지로 남기고 그 시점까지 다시 정리한다. */}
                        <div className="sticky -bottom-5 z-10 -mb-5 bg-[#f3f1ea] pt-1 pb-5 sm:-bottom-8">
                          {closedMeeting ? (
                            <p className="rounded-[10px] border border-dashed border-[#d6d1c4] bg-white px-3 py-2.5 text-[13px] text-muted-foreground">
                              끝난 회의라 메시지를 보낼 수 없어요.
                            </p>
                          ) : isLeader && review ? (
                            <InlineMessage
                              key={`fix:${review.id}`}
                              label="비비에게 고칠 점"
                              placeholder="정리에서 고칠 점을 비비에게 말해 주세요"
                              submitLabel="수정 요청"
                              outline
                              onSubmit={async (text) => {
                                await action.run(async () => {
                                  const entry = await meetingsApi.addEntry(
                                    projectId,
                                    meeting.id,
                                    { text },
                                  )
                                  await requestReview(entry.id)
                                }, '비비가 다시 정리했어요.')
                              }}
                            >
                              {/* 고칠 점 폼 안에 있으므로 제출 버튼이 되지 않게 한다. */}
                              <Button
                                type="button"
                                onClick={() => setEnding(true)}
                              >
                                회의 끝내기
                              </Button>
                            </InlineMessage>
                          ) : participant?.status === 'JOINED' ? (
                            <InlineMessage
                              key={`${meeting.id}:${notes.length}`}
                              label="메시지"
                              placeholder="메시지 보내기"
                              submitLabel="보내기"
                              onSubmit={async (text) => {
                                await action.run(
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
                          ) : participant?.status === 'INVITED' ? (
                            // API 없음: 비비가 부르면 바로 참여하는지 정해지면 이 단계를 뺀다.
                            <p className="flex flex-wrap items-center gap-3 text-sm text-muted-foreground">
                              회의에 참여하면 메시지를 보낼 수 있어요.
                              <Button
                                size="sm"
                                disabled={action.busy}
                                onClick={() =>
                                  perform(
                                    () =>
                                      meetingsApi.respond(
                                        projectId,
                                        meeting.id,
                                        {
                                          status: 'JOINED',
                                        },
                                      ),
                                    '회의에 참여했어요.',
                                  )
                                }
                              >
                                참여하기
                              </Button>
                            </p>
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
      {status === 'CLOSED' ? '끝남' : '작업 충돌'}
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
}: {
  entry: MeetingEntry
  mine: boolean
  author?: Member
}) {
  const time = formatTime(entry.created_at)
  if (entry.kind !== 'TEXT') {
    // 비비 말은 첫 줄을 제목처럼 굵게 쓴다(캔버스 '작업이 부딪혀서 회의를 열었어요').
    const [title, ...rest] = entry.text.split('\n')
    return (
      <div className="flex items-start gap-2.5">
        <BibiFace className="size-8 shrink-0" />
        <div className="max-w-[80%] min-w-0">
          <p className="text-xs text-muted-foreground">
            <strong className="text-foreground">비비</strong> · {time}
          </p>
          <article className="mt-1 rounded-2xl rounded-tl-sm border border-[#f3e2a6] bg-[#fdf5d8] px-3 py-2.5 text-sm">
            {entry.kind === 'MINUTES' && (
              <strong className="block text-[13px]">회의록</strong>
            )}
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
      <div className="grid max-w-[80%] justify-items-end justify-self-end">
        <p className="text-xs text-muted-foreground">
          {time} · <strong className="text-foreground">나</strong>
        </p>
        <p className="mt-1 rounded-2xl rounded-tr-sm bg-[#2c291f] px-3 py-2.5 text-sm leading-6 whitespace-pre-wrap text-white">
          {entry.text}
        </p>
      </div>
    )
  return (
    <div className="flex items-start gap-2.5">
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
const messageSchema = z.object({
  text: z
    .string()
    .trim()
    .min(1, '내용을 입력해 주세요.')
    .max(10000, '10000자까지 쓸 수 있어요.'),
})
function InlineMessage({
  label,
  placeholder,
  submitLabel,
  outline,
  onSubmit,
  children,
}: {
  label: string
  placeholder: string
  submitLabel: string
  outline?: boolean
  onSubmit: (text: string) => Promise<void>
  children?: ReactNode
}) {
  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<z.infer<typeof messageSchema>>({
    resolver: zodResolver(messageSchema),
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
          disabled={isSubmitting}
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
