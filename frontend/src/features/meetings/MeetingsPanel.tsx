import { useState } from 'react'
import { useInfiniteQuery, useQuery } from '@tanstack/react-query'
import {
  FilePenLine,
  MessageSquarePlus,
  NotebookPen,
  Plus,
  Sparkles,
  UserPlus,
} from 'lucide-react'
import {
  changesApi,
  documentsApi,
  meetingsApi,
  membersApi,
  projectsApi,
} from '@/api/services'
import { ActionForm } from '@/components/ActionForm'
import { AsyncState } from '@/components/AsyncState'
import { EmptyState } from '@/components/EmptyState'
import { FormDialog } from '@/components/FormDialog'
import { SourceLinks } from '@/components/SourceLinks'
import { Button } from '@/components/ui/button'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { Textarea } from '@/components/ui/textarea'
import { useSession } from '@/features/auth/useSession'
import { useProjectAction } from '@/features/workspace/useProjectAction'
import { formatDate } from '@/lib/formatDate'
import { useWorkspaceStore } from '@/stores/workspaceStore'

interface Props {
  projectId: string
  isLeader: boolean
}
interface Draft {
  document_id: string
  base_document_version_id: string
  content: string
}
const participationLabel = {
  INVITED: '참여 요청',
  ACCEPTED: '참여 중',
  DECLINED: '불참',
}

export function MeetingsPanel({ projectId }: Props) {
  const session = useSession()
  const action = useProjectAction(projectId)
  const focus = useWorkspaceStore((state) => state.focusId)
  const openPane = useWorkspaceStore((state) => state.openPane)
  const [selection, setSelection] = useState<{
    id: string
    focus: string | null
  } | null>(null)
  const [dialog, setDialog] = useState<'create' | 'invite' | 'draft' | null>(
    null,
  )
  const [draft, setDraft] = useState<Draft | null>(null)
  const list = useInfiniteQuery({
    queryKey: ['projects', projectId, 'meetings'],
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) =>
      meetingsApi.list(projectId, { cursor: pageParam }),
    getNextPageParam: (page) => (page.has_more ? page.next_cursor : undefined),
  })
  const meetings = list.data?.pages.flatMap((page) => page.items) ?? []
  const selectedId =
    selection?.focus === focus ? selection.id : (focus ?? meetings[0]?.id)
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
  const documents = useQuery({
    queryKey: ['projects', projectId, 'meeting-documents'],
    queryFn: () => documentsApi.list(projectId, { limit: 100 }),
  })
  const project = useQuery({
    queryKey: ['projects', projectId, 'meeting-project'],
    queryFn: () => projectsApi.get(projectId),
  })
  const people = members.data?.items ?? []
  const me = people.find((member) => member.account_id === session.data?.id)
  const notes = entries.data?.pages.flatMap((page) => page.items) ?? []
  const through = notes[0]
  const latestMinutes = notes.find((entry) => entry.kind === 'MINUTES')
  const approvedDocuments =
    documents.data?.items.filter((document) => document.approved_version) ?? []
  const participant = detail.data?.participants.find(
    (item) => item.member_id === me?.id,
  )
  const nameOf = (id: string) =>
    people.find((member) => member.id === id)?.name ?? '팀원'
  const perform = (operation: () => Promise<unknown>, message: string) => {
    void action.run(operation, message).catch(() => undefined)
  }
  return (
    <section className="space-y-6">
      <header className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <p className="mb-2 text-xs font-semibold tracking-widest text-primary">
            DECISIONS TOGETHER
          </p>
          <h2 className="text-2xl font-semibold">함께 논의하고 기록하기</h2>
          <p className="mt-2 text-sm leading-6 text-muted-foreground">
            팀원과 텍스트로 논의하고 회의록을 설계 변경 제안으로 이어 가세요.
          </p>
        </div>
        <Button onClick={() => setDialog('create')}>
          <Plus />
          회의 만들기
        </Button>
      </header>
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
      <div className="grid gap-5 lg:grid-cols-[250px_minmax(0,1fr)]">
        <Card className="h-fit">
          <CardHeader>
            <CardTitle className="text-base">팀의 회의</CardTitle>
          </CardHeader>
          <CardContent>
            <AsyncState query={list}>
              {(data) => (
                <>
                  {data.pages.flatMap((page) => page.items).length ? (
                    <ul className="space-y-2">
                      {data.pages
                        .flatMap((page) => page.items)
                        .map((meeting) => (
                          <li key={meeting.id}>
                            <Button
                              variant={
                                meeting.id === selectedId
                                  ? 'secondary'
                                  : 'ghost'
                              }
                              className="h-auto w-full flex-col items-start gap-1 px-3 py-3 text-left whitespace-normal"
                              onClick={() => {
                                setSelection({ id: meeting.id, focus })
                                setDraft(null)
                              }}
                            >
                              <span>{meeting.title}</span>
                              <span className="text-[11px] font-normal text-muted-foreground">
                                {meeting.participants.length}명 ·{' '}
                                {formatDate(meeting.created_at)}
                              </span>
                            </Button>
                          </li>
                        ))}
                    </ul>
                  ) : (
                    <EmptyState>
                      아직 회의가 없어요.
                      <br />
                      함께 정할 주제로 회의를 열어 보세요.
                    </EmptyState>
                  )}
                  {list.hasNextPage && (
                    <Button
                      variant="outline"
                      className="mt-3 w-full"
                      onClick={() => void list.fetchNextPage()}
                    >
                      회의 더 보기
                    </Button>
                  )}
                </>
              )}
            </AsyncState>
          </CardContent>
        </Card>
        <div className="min-w-0 space-y-5">
          {selectedId ? (
            <AsyncState query={detail}>
              {(meeting) => (
                <>
                  <Card>
                    <CardHeader>
                      <CardTitle role="heading" aria-level={3}>
                        {meeting.title}
                      </CardTitle>
                      <CardDescription>
                        만든 시각 {formatDate(meeting.created_at)} · 회의 기록은
                        원문을 유지해요.
                      </CardDescription>
                    </CardHeader>
                    <CardContent className="space-y-4">
                      <div className="flex flex-wrap gap-2">
                        {meeting.participants.map((item) => (
                          <span
                            key={item.member_id}
                            className="rounded-full border px-3 py-1.5 text-xs"
                          >
                            {nameOf(item.member_id)} ·{' '}
                            {participationLabel[item.status]}
                          </span>
                        ))}
                      </div>
                      <div className="flex flex-wrap gap-2">
                        <Button
                          size="sm"
                          variant="outline"
                          onClick={() => setDialog('invite')}
                          disabled={
                            action.busy ||
                            !people.some(
                              (member) =>
                                !meeting.participants.some(
                                  (item) => item.member_id === member.id,
                                ),
                            )
                          }
                        >
                          <UserPlus />
                          팀원 초대
                        </Button>
                        {participant?.status === 'INVITED' && (
                          <>
                            <Button
                              size="sm"
                              disabled={action.busy}
                              onClick={() =>
                                perform(
                                  () =>
                                    meetingsApi.respond(projectId, meeting.id, {
                                      status: 'ACCEPTED',
                                    }),
                                  '회의 참여를 수락했어요.',
                                )
                              }
                            >
                              참여 수락
                            </Button>
                            <Button
                              size="sm"
                              variant="ghost"
                              disabled={action.busy}
                              onClick={() =>
                                perform(
                                  () =>
                                    meetingsApi.respond(projectId, meeting.id, {
                                      status: 'DECLINED',
                                    }),
                                  '불참으로 응답했어요.',
                                )
                              }
                            >
                              불참
                            </Button>
                          </>
                        )}
                        {meeting.change_proposal_id && (
                          <Button
                            size="sm"
                            variant="ghost"
                            onClick={() =>
                              openPane('changes', meeting.change_proposal_id)
                            }
                          >
                            관련 변경 제안 보기
                          </Button>
                        )}
                      </div>
                    </CardContent>
                  </Card>
                  <Card>
                    <CardHeader>
                      <CardTitle className="flex items-center gap-2 text-base">
                        <MessageSquarePlus className="size-4" />
                        텍스트 기록
                      </CardTitle>
                      <CardDescription>
                        초대된 팀원이 참여를 수락하면 대화와 검토 의견을 남길 수
                        있어요.
                      </CardDescription>
                    </CardHeader>
                    <CardContent className="space-y-5">
                      {participant?.status === 'ACCEPTED' ? (
                        <ActionForm
                          key={`${meeting.id}:${notes.length}`}
                          fields={[
                            {
                              name: 'text',
                              label: '회의에 남길 내용',
                              type: 'textarea',
                              required: true,
                              max: 10000,
                            },
                          ]}
                          submitLabel="기록 남기기"
                          onSubmit={async (values) => {
                            await action.run(
                              () =>
                                meetingsApi.addEntry(projectId, meeting.id, {
                                  text: values.text,
                                }),
                              '회의 기록을 남겼어요.',
                            )
                          }}
                        />
                      ) : (
                        <p className="text-sm text-muted-foreground">
                          회의 참여를 수락하면 기록을 남길 수 있어요.
                        </p>
                      )}
                      <AsyncState query={entries}>
                        {(data) => (
                          <>
                            {data.pages.flatMap((page) => page.items).length ? (
                              <ol className="space-y-4">
                                {data.pages
                                  .flatMap((page) => page.items)
                                  .map((entry) => (
                                    <li
                                      key={entry.id}
                                      className={`rounded-xl border p-4 ${entry.kind === 'MINUTES' ? 'bg-primary/5' : 'bg-muted/20'}`}
                                    >
                                      <div className="mb-3 flex flex-wrap items-center justify-between gap-2 text-xs text-muted-foreground">
                                        <b className="font-medium text-foreground">
                                          {entry.kind === 'MINUTES'
                                            ? '회의록'
                                            : entry.kind === 'BEE_ANALYSIS'
                                              ? '비비 검토'
                                              : '회의 텍스트'}
                                        </b>
                                        <time>
                                          {formatDate(entry.created_at)}
                                        </time>
                                      </div>
                                      {entry.generation && (
                                        <p className="mb-3 text-xs text-muted-foreground">
                                          {entry.generation.origin ===
                                          'TEMPLATE'
                                            ? '예시 템플릿 · 실제 AI 호출 없음'
                                            : `AI 생성 · ${entry.generation.model ?? '모델 정보 미제공'}`}
                                        </p>
                                      )}
                                      <p className="text-sm leading-7 whitespace-pre-wrap">
                                        {entry.text}
                                      </p>
                                      {entry.generation && (
                                        <SourceLinks
                                          sources={entry.generation.sources}
                                        />
                                      )}
                                    </li>
                                  ))}
                              </ol>
                            ) : (
                              <EmptyState>
                                첫 대화나 검토 의견을 기록해 보세요.
                              </EmptyState>
                            )}
                            {entries.hasNextPage && (
                              <Button
                                variant="outline"
                                onClick={() => void entries.fetchNextPage()}
                              >
                                이전 기록 더 보기
                              </Button>
                            )}
                          </>
                        )}
                      </AsyncState>
                    </CardContent>
                  </Card>
                  <Card>
                    <CardHeader>
                      <CardTitle className="flex items-center gap-2 text-base">
                        <NotebookPen className="size-4" />
                        기록에서 다음 결정으로
                      </CardTitle>
                      <CardDescription>
                        버튼을 누른 시점까지의 기록을 기준으로 검토하고, 변경
                        사항은 제안·승인 절차로 반영해요.
                      </CardDescription>
                    </CardHeader>
                    <CardContent className="flex flex-wrap gap-2">
                      <Button
                        variant="outline"
                        disabled={!through || action.busy}
                        onClick={() =>
                          perform(
                            () =>
                              meetingsApi.analyze(projectId, meeting.id, {
                                through_entry_id: through!.id,
                              }),
                            '비비의 검토를 기록했어요.',
                          )
                        }
                      >
                        <Sparkles />
                        비비에게 검토 요청
                      </Button>
                      <Button
                        variant="outline"
                        disabled={!through || action.busy}
                        onClick={() =>
                          perform(
                            () =>
                              meetingsApi.minutes(projectId, meeting.id, {
                                through_entry_id: through!.id,
                              }),
                            '회의록을 만들었어요.',
                          )
                        }
                      >
                        <NotebookPen />
                        회의록 만들기
                      </Button>
                      <Button
                        disabled={
                          !latestMinutes ||
                          !approvedDocuments.length ||
                          action.busy
                        }
                        onClick={() => setDialog('draft')}
                      >
                        <FilePenLine />
                        문서 수정 초안
                      </Button>
                      {!approvedDocuments.length && (
                        <p className="w-full text-xs text-muted-foreground">
                          승인된 공용 문서가 있으면 회의 결정을 문서 수정
                          초안으로 만들 수 있어요.
                        </p>
                      )}
                    </CardContent>
                  </Card>
                </>
              )}
            </AsyncState>
          ) : (
            <EmptyState>
              회의를 선택하거나 새로운 회의를 만들어 보세요.
            </EmptyState>
          )}
          {draft && (
            <Card>
              <CardHeader>
                <CardTitle className="text-base">
                  회의 기반 문서 수정 초안
                </CardTitle>
                <CardDescription>
                  검토하고 변경 제안으로 제출하세요. 문서에 자동으로 반영하지
                  않아요.
                </CardDescription>
              </CardHeader>
              <CardContent className="space-y-4">
                <Textarea
                  aria-label="회의 기반 수정 초안"
                  rows={12}
                  value={draft.content}
                  disabled={action.busy}
                  onChange={(event) =>
                    setDraft({ ...draft, content: event.target.value })
                  }
                />
                <Button
                  disabled={
                    action.busy ||
                    !draft.content.trim() ||
                    !project.data?.current_baseline
                  }
                  onClick={() =>
                    perform(async () => {
                      const proposal = await changesApi.create(projectId, {
                        base_baseline_id: project.data!.current_baseline!.id,
                        title: `${detail.data?.title ?? '회의'} 문서 수정 제안`,
                        reason: '회의록을 바탕으로 검토한 수정 초안입니다.',
                        items: [],
                        documents: [
                          {
                            document_id: draft.document_id,
                            base_document_version_id:
                              draft.base_document_version_id,
                            after_content: draft.content,
                          },
                        ],
                      })
                      setDraft(null)
                      openPane('changes', proposal.id)
                    }, '회의 기반 변경 제안을 등록했어요.')
                  }
                >
                  변경 제안으로 제출
                </Button>
              </CardContent>
            </Card>
          )}
        </div>
      </div>
      {dialog === 'create' && (
        <FormDialog
          title="회의 만들기"
          description="팀원을 한 명 초대해 시작하거나 혼자 회의를 만들 수 있어요. 만든 뒤 다른 팀원도 초대할 수 있어요."
          fields={[
            { name: 'title', label: '회의 제목', required: true, max: 120 },
            {
              name: 'participant',
              label: '함께할 팀원',
              type: 'select',
              options: [
                { value: '', label: '혼자 시작하고 나중에 초대' },
                ...people
                  .filter((member) => member.id !== me?.id)
                  .map((member) => ({ value: member.id, label: member.name })),
              ],
            },
          ]}
          submitLabel="회의 만들기"
          onClose={() => setDialog(null)}
          onSubmit={async (values) => {
            await action.run(async () => {
              const created = await meetingsApi.create(projectId, {
                title: values.title,
                participant_member_ids: values.participant
                  ? [values.participant]
                  : [],
              })
              setSelection({ id: created.id, focus })
              setDraft(null)
            }, '회의를 만들었어요.')
            setDialog(null)
          }}
        />
      )}
      {dialog === 'invite' && detail.data && (
        <FormDialog
          title="회의에 팀원 초대"
          fields={[
            {
              name: 'member_id',
              label: '초대할 팀원',
              type: 'select',
              required: true,
              options: people
                .filter(
                  (member) =>
                    !detail.data!.participants.some(
                      (item) => item.member_id === member.id,
                    ),
                )
                .map((member) => ({ value: member.id, label: member.name })),
            },
          ]}
          submitLabel="참여 요청 보내기"
          onClose={() => setDialog(null)}
          onSubmit={async (values) => {
            await action.run(
              () =>
                meetingsApi.invite(projectId, selectedId!, {
                  member_ids: [values.member_id],
                }),
              '회의 참여를 요청했어요.',
            )
            setDialog(null)
          }}
        />
      )}
      {dialog === 'draft' && latestMinutes && (
        <FormDialog
          title="회의 기반 문서 수정 초안"
          fields={[
            {
              name: 'document_id',
              label: '수정할 승인 문서',
              type: 'select',
              required: true,
              options: approvedDocuments.map((document) => ({
                value: document.id,
                label: `${document.title} · 승인 v${document.approved_version!.version_no}`,
              })),
            },
          ]}
          submitLabel="수정 초안 만들기"
          onClose={() => setDialog(null)}
          onSubmit={async (values) => {
            const document = approvedDocuments.find(
              (item) => item.id === values.document_id,
            )!
            await action.run(async () => {
              const result = await meetingsApi.changeDraft(
                projectId,
                selectedId!,
                {
                  minutes_entry_id: latestMinutes.id,
                  document_id: document.id,
                  base_document_version_id: document.approved_version!.id,
                },
              )
              setDraft({
                document_id: document.id,
                base_document_version_id: document.approved_version!.id,
                content: result.after_content,
              })
            }, '문서 수정 초안을 만들었어요.')
            setDialog(null)
          }}
        />
      )}
    </section>
  )
}
