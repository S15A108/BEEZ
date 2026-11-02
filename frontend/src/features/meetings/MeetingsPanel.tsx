import { useEffect, useRef, useState } from 'react'
import { useInfiniteQuery, useQuery } from '@tanstack/react-query'
import { ArrowLeft, Settings, Sparkles, X } from 'lucide-react'
import { BibiFace } from '@/components/BibiFace'
import { meetingsApi, membersApi, tasksApi } from '@/api/services'
import type { MeetingEntry, Member } from '@/api/contracts/common'
import { ActionForm } from '@/components/ActionForm'
import { AsyncState } from '@/components/AsyncState'
import { EmptyState } from '@/components/EmptyState'
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
import { formatDate } from '@/lib/formatDate'
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

// 회의는 사람이 만들지 않는다. 작업이 부딪히면 비비가 팀장과 관련 작업자를 불러 연다.
// 10-29 수정안: 진행 중/끝난 회의로 나누고, 안건(왜 열렸는지)을 대화 위에 고정하고, 단계(대화 → 비비 정리 → 회의 끝내기 → 변경 제안 승인)를 보인다.
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
  const perform = (operation: () => Promise<unknown>, message: string) => {
    void action.run(operation, message).catch(() => undefined)
  }
  const requestReview = (throughId: string) =>
    meetingsApi.analyze(projectId, selectedId!, { through_entry_id: throughId })
  return (
    <section className="space-y-4">
      <p className="text-sm text-muted-foreground">
        작업이 부딪히면 비비가 관련된 사람을 불러 회의를 열어요. 기록은 원문
        그대로 남아요.
      </p>
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
      <AsyncState query={list}>
        {() =>
          meetings.length ? (
            <div className="grid gap-5 lg:grid-cols-[250px_minmax(0,1fr)]">
              <div className="grid content-start gap-4">
                {(
                  [
                    ['진행 중', open, '진행 중인 회의가 없어요.'],
                    ['끝난 회의', closed, '끝난 회의가 없어요.'],
                  ] as const
                ).map(([heading, group, empty]) => (
                  <div key={heading}>
                    <h3 className="mb-2.5 flex justify-between text-sm font-bold">
                      {heading}
                      <span className="font-medium">{group.length}</span>
                    </h3>
                    {group.length ? (
                      <ul className="grid gap-2">
                        {group.map((meeting) => (
                          <li key={meeting.id}>
                            <button
                              type="button"
                              aria-current={meeting.id === selectedId}
                              className={cn(
                                'grid w-full gap-1 rounded-xl border px-3.5 py-3 text-left hover:bg-muted/40',
                                meeting.id === selectedId &&
                                  'border-2 border-foreground',
                              )}
                              onClick={() => {
                                setSelection({ id: meeting.id, focus })
                                setEnding(false)
                                setAgendaOpen(false)
                              }}
                            >
                              <MeetingChip
                                status={meeting.status}
                                origin={meeting.origin}
                              />
                              <strong className="text-sm font-semibold">
                                {meeting.title}
                              </strong>
                              <span className="text-xs text-muted-foreground">
                                {[
                                  ...(meeting.task_ids ?? []).map(
                                    (id) => taskOf(id)?.key ?? '작업',
                                  ),
                                  `참여 ${meeting.participants.length}명`,
                                ].join(' · ')}
                              </span>
                              <span className="text-xs text-muted-foreground">
                                {formatDate(meeting.created_at)}
                                {meeting.origin === 'BEE_CONFLICT' &&
                                  ' · 비비가 엶'}
                              </span>
                              {meeting.status === 'CLOSED' &&
                                meeting.change_proposal_id && (
                                  <span className="text-xs font-semibold text-amber-800">
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
                    className="mt-3 w-full"
                    onClick={() => void list.fetchNextPage()}
                  >
                    회의 더 보기
                  </Button>
                )}
              </div>
              {selectedId && (
                <AsyncState query={detail}>
                  {(meeting) =>
                    ending && review ? (
                      <div className="grid content-start gap-4">
                        <Button
                          variant="ghost"
                          size="sm"
                          className="justify-self-start"
                          onClick={() => setEnding(false)}
                        >
                          <ArrowLeft />
                          {meeting.title}
                        </Button>
                        <div>
                          <h3 className="text-lg font-bold">
                            회의를 끝낼까요?
                          </h3>
                          <p className="mt-1 text-sm text-muted-foreground">
                            회의를 끝내면 이 정리로 회의 결과 변경 제안을
                            만들어요. 제안자는 회의를 끝낸 팀장이에요. 대화
                            기록은 그대로 남아요.
                          </p>
                          <MeetingSteps current={2} />
                        </div>
                        <article className="rounded-xl border border-primary/40 bg-primary/10 p-4 text-sm">
                          <h4 className="mb-2 font-semibold">비비 정리</h4>
                          <p className="leading-6 whitespace-pre-wrap">
                            {review.text}
                          </p>
                          {/* API 없음: 변경 제안에 들어갈 설계 항목과 문장, 근거 발언은 회의 종료 API가 생기면 여기에 그린다. */}
                        </article>
                        <ul className="list-inside list-disc text-sm text-muted-foreground">
                          <li>
                            문서는 바로 바뀌지 않아요. 팀장이 변경 제안을
                            승인하고 발행하면 기준에 반영돼요.
                          </li>
                          <li>끝난 회의에는 더 쓸 수 없어요.</li>
                        </ul>
                        <div className="flex flex-wrap items-center justify-between gap-3 border-t pt-4">
                          <p className="text-xs text-muted-foreground">
                            팀장만 끝낼 수 있어요. 기준 v1 뒤의 문서 수정은 모두
                            변경 제안을 거쳐요.
                          </p>
                          <div className="flex gap-2">
                            <Button
                              variant="outline"
                              onClick={() => setEnding(false)}
                            >
                              취소
                            </Button>
                            {/* API 없음: 회의 종료(MTG-A13 요청 중)와 회의 결과 변경 제안(CHG-A01, 제안자 = 회의를 끝낸 팀장, meeting_id는 PR #42 요청 중)으로 잇기. 끝낸 뒤에는 그 제안을 변경 제안 창에서 연다. */}
                            <Button
                              disabled
                              title="서버 기능이 준비되면 열려요"
                            >
                              회의 끝내고 변경 제안 만들기
                            </Button>
                          </div>
                        </div>
                      </div>
                    ) : (
                      <div className="grid min-w-0 content-start gap-3">
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
                              {formatDate(meeting.created_at)}
                              {meeting.status === 'CLOSED'
                                ? ` · ${nameOfAccount(meeting.closed_by)}이 끝냄`
                                : meeting.origin === 'BEE_CONFLICT'
                                  ? ' · 비비가 열었어요'
                                  : ''}
                              {' · '}기록은 원문 그대로 남아요
                            </p>
                            <MeetingSteps
                              current={
                                meeting.status === 'CLOSED' ? 3 : review ? 1 : 0
                              }
                            />
                          </div>
                          {meeting.status === 'CLOSED' ? (
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
                                <Sparkles />
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
                        <div className="flex flex-wrap items-center gap-x-3.5 gap-y-1.5 text-sm">
                          <span className="text-muted-foreground">
                            {meeting.origin === 'BEE_CONFLICT'
                              ? '비비가 부른 사람'
                              : '참여자'}
                          </span>
                          {/* API 없음: 사람마다 관련 작업(TASK-001 등)과 누가 추가했는지('이팀장이 추가')를 붙인다. */}
                          {/* 참여자 이름 칩(PNL-06 수정안 10-29). 비비와 팀장 칩에는 × 없음, 빼기는 팀장만. */}
                          <span className="inline-flex items-center gap-1.5 rounded-full border bg-card py-0.5 pr-2.5 pl-0.5">
                            <BibiFace className="size-6" />
                            <strong className="font-semibold">비비</strong>
                          </span>
                          {meeting.participants.map((item) => {
                            const member = people.find(
                              (person) => person.id === item.member_id,
                            )
                            const removable =
                              isLeader &&
                              meeting.status !== 'CLOSED' &&
                              member?.role !== 'LEADER'
                            return (
                              <span
                                key={item.member_id}
                                className={cn(
                                  'inline-flex items-center gap-1.5 rounded-full border bg-card py-0.5 pl-0.5',
                                  removable ? 'pr-1' : 'pr-2.5',
                                )}
                              >
                                <Avatar name={member?.name} />
                                <strong className="font-semibold">
                                  {member?.name ?? '팀원'}
                                </strong>
                                <span className="text-muted-foreground">
                                  {[
                                    member?.role === 'LEADER' && '팀장',
                                    participationLabel[item.status],
                                  ]
                                    .filter(Boolean)
                                    .join(' · ')}
                                </span>
                                {removable && (
                                  <button
                                    type="button"
                                    aria-label={`${member?.name ?? '팀원'} 빼기`}
                                    className="grid size-5 place-items-center rounded-full text-muted-foreground hover:bg-muted"
                                    onClick={() =>
                                      void action
                                        .run(
                                          () =>
                                            meetingsApi.removeParticipant(
                                              projectId,
                                              meeting.id,
                                              item.member_id,
                                            ),
                                          `${member?.name ?? '팀원'}님을 회의에서 뺐어요.`,
                                        )
                                        .catch(() => undefined)
                                    }
                                  >
                                    <X className="size-3" />
                                  </button>
                                )}
                              </span>
                            )
                          })}
                          {(isLeader || !!participant) &&
                            meeting.status !== 'CLOSED' && (
                              <ParticipantPicker
                                members={people}
                                joinedIds={meeting.participants.map(
                                  (item) => item.member_id,
                                )}
                                isLeader={isLeader}
                                onAdd={(memberId) =>
                                  void action
                                    .run(
                                      () =>
                                        meetingsApi.invite(
                                          projectId,
                                          meeting.id,
                                          { member_ids: [memberId] },
                                        ),
                                      '관련자를 추가했어요.',
                                    )
                                    .catch(() => undefined)
                                }
                                onRemove={(memberId) =>
                                  void action
                                    .run(
                                      () =>
                                        meetingsApi.removeParticipant(
                                          projectId,
                                          meeting.id,
                                          memberId,
                                        ),
                                      '회의에서 뺐어요.',
                                    )
                                    .catch(() => undefined)
                                }
                              />
                            )}
                        </div>
                        {meeting.conflict_summary && (
                          <section
                            aria-label="안건"
                            className="grid gap-2 rounded-xl border border-primary/40 bg-primary/5 p-3 text-sm"
                          >
                            {review && !agendaOpen ? (
                              <p className="flex flex-wrap items-center justify-between gap-2">
                                <span>
                                  <strong>안건</strong>{' '}
                                  {meeting.conflict_summary}
                                  {meeting.task_ids?.length
                                    ? ` · ${meeting.task_ids.map((id) => taskOf(id)?.key ?? '작업').join('과 ')}가 달라요`
                                    : ''}
                                </span>
                                <button
                                  type="button"
                                  className="text-xs underline"
                                  onClick={() => setAgendaOpen(true)}
                                >
                                  펼치기
                                </button>
                              </p>
                            ) : (
                              <>
                                <p className="flex flex-wrap items-baseline justify-between gap-2">
                                  <strong>{meeting.conflict_summary}</strong>
                                  <span className="text-xs text-muted-foreground">
                                    비비가 찾은 충돌 ·{' '}
                                    {formatDate(meeting.created_at)}
                                  </span>
                                </p>
                                {/* API 없음: 작업마다 어떻게 다른지와 기준 문단(REQ-005 보기)은 충돌 상세 필드가 생기면 붙인다. 지금은 비비 첫 말풍선에 있다. */}
                                <ul className="flex flex-wrap gap-2">
                                  {(meeting.task_ids ?? []).map((id) => {
                                    const task = taskOf(id)
                                    return (
                                      <li key={id}>
                                        <button
                                          type="button"
                                          className="rounded-lg border bg-card px-2.5 py-1 text-xs hover:bg-muted"
                                          onClick={() => openPane('tasks', id)}
                                        >
                                          <strong>{task?.key ?? '작업'}</strong>{' '}
                                          {
                                            task?.current_plan_revision.plan
                                              .title
                                          }
                                          {task &&
                                            ` · ${nameOfMember(task.assignee_member_id)}`}
                                        </button>
                                      </li>
                                    )
                                  })}
                                </ul>
                              </>
                            )}
                          </section>
                        )}
                        <AsyncState query={entries}>
                          {() => (
                            <div
                              ref={log}
                              role="log"
                              aria-label="회의 대화"
                              className="grid max-h-[50vh] min-h-48 content-start gap-3.5 overflow-auto rounded-2xl bg-muted/30 p-4"
                            >
                              {entries.hasNextPage && (
                                <Button
                                  variant="outline"
                                  size="sm"
                                  className="justify-self-center"
                                  onClick={() => void entries.fetchNextPage()}
                                >
                                  이전 기록 더 보기
                                </Button>
                              )}
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
                                      author={
                                        people.find(
                                          (member) =>
                                            member.account_id ===
                                            entry.author_account_id,
                                        )?.name
                                      }
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
                        {/* API 없음: 정리 수정 요청 전용 필드. 지금은 고칠 점을 메시지로 남기고 그 시점까지 다시 정리한다. */}
                        {meeting.status === 'CLOSED' ? (
                          <p className="rounded-lg bg-muted/50 px-3 py-2.5 text-sm text-muted-foreground">
                            끝난 회의라 메시지를 보낼 수 없어요.
                          </p>
                        ) : isLeader && review ? (
                          <div className="grid gap-3">
                            <ActionForm
                              key={`fix:${review.id}`}
                              fields={[
                                {
                                  name: 'text',
                                  label: '비비에게 고칠 점',
                                  required: true,
                                  max: 10000,
                                  placeholder:
                                    '정리에서 고칠 점을 비비에게 말해 주세요',
                                },
                              ]}
                              submitLabel="수정 요청"
                              onSubmit={async (values) => {
                                await action.run(async () => {
                                  const entry = await meetingsApi.addEntry(
                                    projectId,
                                    meeting.id,
                                    { text: values.text },
                                  )
                                  await requestReview(entry.id)
                                }, '비비가 다시 정리했어요.')
                              }}
                            />
                            <Button
                              variant="outline"
                              className="justify-self-start"
                              onClick={() => setEnding(true)}
                            >
                              회의 끝내기
                            </Button>
                          </div>
                        ) : participant?.status === 'JOINED' ? (
                          <ActionForm
                            key={`${meeting.id}:${notes.length}`}
                            fields={[
                              {
                                name: 'text',
                                label: '메시지',
                                required: true,
                                max: 10000,
                                placeholder: '메시지 보내기',
                              },
                            ]}
                            submitLabel="보내기"
                            onSubmit={async (values) => {
                              await action.run(
                                () =>
                                  meetingsApi.addEntry(projectId, meeting.id, {
                                    text: values.text,
                                  }),
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
                                    meetingsApi.respond(projectId, meeting.id, {
                                      status: 'JOINED',
                                    }),
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
                    )
                  }
                </AsyncState>
              )}
            </div>
          ) : (
            <EmptyState>
              <strong className="text-base text-foreground">
                회의가 없어요
              </strong>
              작업이 서로 부딪히면 비비가 팀장과 관련 작업자를 불러 회의를
              열어요.
            </EmptyState>
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
        'w-fit rounded-full px-2 py-0.5 text-xs font-semibold',
        status === 'CLOSED'
          ? 'bg-muted text-muted-foreground'
          : 'bg-amber-100 text-amber-800',
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
      className="flex flex-wrap items-center gap-1 text-xs"
    >
      {meetingSteps.map((step, index) => (
        <li
          key={step}
          aria-current={index === current ? 'step' : undefined}
          className={cn(
            'flex items-center gap-1 rounded-full px-2 py-0.5',
            index < current && 'font-semibold text-muted-foreground',
            index === current && 'bg-foreground font-bold text-background',
            index > current && 'text-muted-foreground/70',
          )}
        >
          <span aria-hidden="true">{index < current ? '✓' : index + 1}</span>
          {step}
        </li>
      ))}
    </ol>
  )
}

function Avatar({ name }: { name?: string }) {
  return (
    <span
      aria-hidden="true"
      className="grid size-6 shrink-0 place-items-center rounded-full bg-muted text-[10px] font-bold"
    >
      {name?.[0] ?? '?'}
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
  author?: string
}) {
  const time = formatDate(entry.created_at)
  if (entry.kind !== 'TEXT')
    return (
      <div className="flex items-start gap-2.5">
        <BibiFace className="size-8 shrink-0" />
        <div className="max-w-[80%] min-w-0">
          <p className="text-xs text-muted-foreground">
            <strong className="text-foreground">비비</strong> · {time}
          </p>
          <article className="mt-1 rounded-2xl rounded-tl-sm border border-primary/40 bg-primary/10 px-3 py-2.5 text-sm">
            {entry.kind === 'MINUTES' && (
              <strong className="block text-[13px]">회의록</strong>
            )}
            {entry.generation && (
              <p className="mt-1 text-xs text-muted-foreground">
                {entry.generation.origin === 'TEMPLATE'
                  ? '예시 템플릿 · 실제 AI 호출 없음'
                  : `AI 생성 · ${entry.generation.model ?? '모델 정보 미제공'}`}
              </p>
            )}
            <p className="mt-1 leading-6 whitespace-pre-wrap">{entry.text}</p>
            {entry.generation && (
              <SourceLinks sources={entry.generation.sources} />
            )}
          </article>
        </div>
      </div>
    )
  if (mine)
    return (
      <div className="grid max-w-[80%] justify-items-end justify-self-end">
        <p className="text-xs text-muted-foreground">
          {time} · <strong className="text-foreground">나</strong>
        </p>
        <p className="mt-1 rounded-2xl rounded-tr-sm bg-foreground px-3 py-2.5 text-sm leading-6 whitespace-pre-wrap text-background">
          {entry.text}
        </p>
      </div>
    )
  return (
    <div className="flex items-start gap-2.5">
      <Avatar name={author} />
      <div className="max-w-[80%] min-w-0">
        <p className="text-xs text-muted-foreground">
          <strong className="text-foreground">{author ?? '팀원'}</strong> ·{' '}
          {time}
        </p>
        <p className="mt-1 rounded-2xl rounded-tl-sm border bg-background px-3 py-2.5 text-sm leading-6 whitespace-pre-wrap">
          {entry.text}
        </p>
      </div>
    </div>
  )
}

// 관련자 · GitHub 리뷰어처럼 이름 찾기와 체크(PNL-06 수정안 10-29). 누르면 바로 더하거나(MTG-A04) 뺀다(MTG-A14).
// 빼기는 팀장만이고 팀장 줄은 뺄 수 없다. 팀원이 열면 체크를 더할 수만 있다.
function ParticipantPicker({
  members,
  joinedIds,
  isLeader,
  onAdd,
  onRemove,
}: {
  members: Member[]
  joinedIds: string[]
  isLeader: boolean
  onAdd: (memberId: string) => void
  onRemove: (memberId: string) => void
}) {
  const [query, setQuery] = useState('')
  const shown = members.filter((member) => member.name.includes(query.trim()))
  return (
    <Popover onOpenChange={() => setQuery('')}>
      <PopoverTrigger asChild>
        <button
          type="button"
          className="inline-flex h-7 items-center gap-1 rounded-full border border-dashed px-2.5 text-xs font-semibold text-muted-foreground hover:bg-muted"
        >
          <Settings className="size-3.5" />
          관련자
        </button>
      </PopoverTrigger>
      <PopoverContent align="end" className="w-72 p-2">
        <Input
          aria-label="이름 찾기"
          placeholder="이름 찾기"
          className="mb-2 h-8"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
        />
        <ul aria-label="팀원" className="grid max-h-64 gap-0.5 overflow-y-auto">
          {shown.map((member) => {
            const joined = joinedIds.includes(member.id)
            const locked = joined && (!isLeader || member.role === 'LEADER')
            return (
              <li key={member.id}>
                <label
                  className={cn(
                    'flex cursor-pointer items-center gap-2 rounded-md px-2 py-1.5 text-sm hover:bg-muted',
                    locked && 'cursor-default opacity-60',
                  )}
                >
                  <input
                    type="checkbox"
                    className="size-4 accent-primary"
                    checked={joined}
                    disabled={locked}
                    onChange={() =>
                      joined ? onRemove(member.id) : onAdd(member.id)
                    }
                  />
                  <Avatar name={member.name} />
                  {member.name}
                  {member.role === 'LEADER' && (
                    <span className="text-xs text-muted-foreground">팀장</span>
                  )}
                </label>
              </li>
            )
          })}
          {!shown.length && (
            <li className="px-2 py-1.5 text-xs text-muted-foreground">
              찾는 팀원이 없어요.
            </li>
          )}
        </ul>
        {!isLeader && (
          <p className="px-2 pt-2 text-xs text-muted-foreground">
            빼기는 팀장만 할 수 있어요.
          </p>
        )}
      </PopoverContent>
    </Popover>
  )
}
