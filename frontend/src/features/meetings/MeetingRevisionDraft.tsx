import { useState } from 'react'
import { isAxiosError } from 'axios'
import { useInfiniteQuery, useQuery } from '@tanstack/react-query'
import { changesApi, documentsApi, meetingsApi } from '@/api/services'
import type { MeetingEntry } from '@/api/contracts/common'
import { getApiErrorMessage } from '@/api/error'
import { Button } from '@/components/ui/button'
import { Textarea } from '@/components/ui/textarea'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import type { ScopedBlocksResponse } from '@/features/changes/schemas'
import type { RevisionDraftResponse, SummaryResponse } from './schemas'

interface Props {
  projectId: string
  meetingId: string
  entries: MeetingEntry[]
  hasMore: boolean
  loadingMore: boolean
  loadMore: () => void
}

/** 사용자가 결정과 근거 발언을 직접 확인한 뒤 별도의 미제출 초안을 저장한다. */
export function MeetingRevisionDraft(props: Props) {
  return <Editor key={`${props.projectId}:${props.meetingId}`} {...props} />
}

function Editor({
  projectId,
  meetingId,
  entries,
  hasMore,
  loadingMore,
  loadMore,
}: Props) {
  const openPane = useWorkspaceStore((state) => state.openPane)
  const savedSummary = useQuery({
    queryKey: [
      'projects',
      projectId,
      'meeting',
      meetingId,
      'summary',
      'latest',
    ],
    queryFn: () => meetingsApi.latestSummary(projectId, meetingId),
    retry: false,
    refetchOnWindowFocus: false,
  })
  const savedDraft = useQuery({
    queryKey: [
      'projects',
      projectId,
      'meeting',
      meetingId,
      'revision-drafts',
      'latest',
    ],
    queryFn: () => meetingsApi.latestRevisionDraft(projectId, meetingId),
    retry: false,
  })
  const documents = useInfiniteQuery({
    queryKey: ['projects', projectId, 'meeting-revision-documents'],
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) =>
      documentsApi.list(projectId, { limit: 100, cursor: pageParam }),
    getNextPageParam: (page) => (page.has_more ? page.next_cursor : undefined),
  })
  const [newSummary, setNewSummary] = useState<SummaryResponse>()
  const [newDraft, setNewDraft] = useState<RevisionDraftResponse>()
  const [documentId, setDocumentId] = useState('')
  const [blocks, setBlocks] = useState<ScopedBlocksResponse>()
  const [selectedBlocks, setSelectedBlocks] = useState<string[]>([])
  const [selectedDecisions, setSelectedDecisions] = useState<
    Record<number, string[]>
  >({})
  const [confirmed, setConfirmed] = useState(false)
  const [instruction, setInstruction] = useState('')
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string>()
  const [copied, setCopied] = useState(false)
  const summary = newSummary ?? savedSummary.data
  const draft = newDraft ?? savedDraft.data
  const documentList = documents.data?.pages.flatMap((page) => page.items) ?? []
  const document = documentList.find((item) => item.id === documentId)
  const versionId = document?.approved_version?.id
  const textEntries = entries.filter((entry) => entry.kind === 'TEXT')
  // 회의 기록 API는 오래된 순서로 페이지를 반환한다. 마지막 페이지까지 받아야 최신 시점을 알 수 있다.
  const latestEntry = entries
    .filter((entry) => entry.kind === 'TEXT' || entry.kind === 'CONFLICT_BRIEF')
    .at(-1)
  const summaryOutdated =
    !!summary &&
    !hasMore &&
    !!latestEntry &&
    latestEntry.id !== summary.through_entry_id
  const draftDocument = documentList.find(
    (item) => item.id === draft?.document_id,
  )
  const draftOutdated =
    !!draft &&
    ((!!summary && draft.summary_id !== summary.id) ||
      summaryOutdated ||
      (!!draftDocument &&
        draftDocument.approved_version?.id !== draft.base_document_version_id))
  const unavailable = (cause: unknown) =>
    !!cause && !(isAxiosError(cause) && cause.response?.status === 404)
  const decisions = Object.entries(selectedDecisions).map(([index, ids]) => ({
    decision_index: Number(index),
    evidence_entry_ids: ids,
  }))
  const valid =
    !!summary &&
    !!latestEntry &&
    !hasMore &&
    !summaryOutdated &&
    !!versionId &&
    !!blocks &&
    selectedBlocks.length > 0 &&
    decisions.length > 0 &&
    decisions.every((decision) => decision.evidence_entry_ids.length > 0) &&
    confirmed &&
    instruction.trim().length > 0

  const run = async (operation: () => Promise<void>) => {
    setPending(true)
    setError(undefined)
    try {
      await operation()
    } catch (cause) {
      setError(getApiErrorMessage(cause))
    } finally {
      setPending(false)
    }
  }
  const createSummary = () =>
    run(async () => {
      if (!latestEntry || hasMore) return
      const value = await meetingsApi.summary(
        projectId,
        meetingId,
        latestEntry.id,
      )
      setNewSummary(value)
      setSelectedDecisions({})
      setConfirmed(false)
      setNewDraft(undefined)
    })
  const loadBlocks = () =>
    run(async () => {
      if (!versionId) return
      const value = await changesApi.scopedBlocks(projectId, {
        document_id: documentId,
        base_document_version_id: versionId,
      })
      if (
        value.document_id !== documentId ||
        value.base_document_version_id !== versionId
      )
        throw new Error('기준 문서가 바뀌었어요. 다시 불러와 주세요.')
      setBlocks(value)
      setSelectedBlocks([])
    })
  const generate = () =>
    run(async () => {
      if (!valid || !summary || !versionId) return
      const value = await meetingsApi.revisionDraft(projectId, meetingId, {
        summary_id: summary.id,
        document_id: documentId,
        base_document_version_id: versionId,
        selected_block_ids: selectedBlocks,
        instruction: instruction.trim(),
        decisions,
        confirmed: true,
      })
      setNewDraft(value)
    })

  return (
    <section
      aria-label="회의 수정 초안"
      className="grid gap-4 rounded-2xl border bg-white p-4 text-sm"
    >
      <div>
        <h3 className="font-bold">회의 결정으로 문서 수정 초안 만들기</h3>
        <p className="mt-1 text-muted-foreground">
          결정과 발언 근거를 직접 확인해 주세요. 만든 초안은 제출하거나 승인하지
          않아요.
        </p>
      </div>
      {unavailable(savedSummary.error) && !newSummary && (
        <p role="alert">저장된 회의 정리를 불러오지 못했어요.</p>
      )}
      {unavailable(savedDraft.error) && !newDraft && (
        <p role="alert">저장된 초안을 불러오지 못했어요.</p>
      )}
      {hasMore && (
        <div className="grid gap-1">
          <p>최신 대화까지 확인하려면 나머지 기록을 불러와 주세요.</p>
          <Button
            type="button"
            variant="outline"
            disabled={loadingMore}
            onClick={loadMore}
          >
            회의 기록 더 보기
          </Button>
        </div>
      )}
      <Button
        type="button"
        variant="outline"
        disabled={pending || !latestEntry || hasMore}
        onClick={() => void createSummary()}
      >
        {summary ? '최신 대화로 다시 정리' : '회의 정리 만들기'}
      </Button>
      {!latestEntry && !hasMore && <p>먼저 회의 대화를 남겨 주세요.</p>}
      {summaryOutdated && (
        <p role="status">
          회의 정리 이후 새 기록이 있어요. 최신 대화로 다시 정리해 주세요.
        </p>
      )}
      {summary && (
        <>
          <fieldset className="grid gap-3">
            <legend className="mb-2 font-semibold">
              확인한 결정과 근거 발언 선택
            </legend>
            {summary.decisions.length === 0 && (
              <p>정리된 결정이 없어요. 대화를 보완한 뒤 다시 정리해 주세요.</p>
            )}
            {summary.decisions.map((decision, index) => {
              const selected = Object.hasOwn(selectedDecisions, index)
              const evidence = selectedDecisions[index] ?? []
              return (
                <div
                  key={`${index}:${decision.text}`}
                  className="grid gap-2 rounded border p-3"
                >
                  <label className="flex gap-2">
                    <input
                      type="checkbox"
                      checked={selected}
                      disabled={pending}
                      onChange={(event) => {
                        setSelectedDecisions((old) => {
                          const next = { ...old }
                          if (event.target.checked) next[index] = []
                          else delete next[index]
                          return next
                        })
                        setConfirmed(false)
                      }}
                    />
                    {decision.text}
                  </label>
                  {selected && (
                    <fieldset className="grid max-h-48 gap-2 overflow-auto pl-5">
                      <legend>이 결정의 근거 발언 (1개 이상)</legend>
                      {textEntries
                        .filter(
                          (entry) =>
                            BigInt(entry.id) <=
                            BigInt(summary.through_entry_id),
                        )
                        .map((entry) => (
                          <label key={entry.id} className="flex gap-2">
                            <input
                              type="checkbox"
                              checked={evidence.includes(entry.id)}
                              disabled={pending}
                              onChange={(event) => {
                                setSelectedDecisions((old) => ({
                                  ...old,
                                  [index]: event.target.checked
                                    ? [...evidence, entry.id]
                                    : evidence.filter((id) => id !== entry.id),
                                }))
                                setConfirmed(false)
                              }}
                            />
                            <span className="whitespace-pre-wrap">
                              {entry.text}
                            </span>
                          </label>
                        ))}
                    </fieldset>
                  )}
                </div>
              )
            })}
          </fieldset>
          <label className="flex gap-2">
            <input
              type="checkbox"
              checked={confirmed}
              disabled={pending}
              onChange={(event) => setConfirmed(event.target.checked)}
            />
            선택한 내용이 실제 회의에서 확인한 결정이고 각 발언이 그 근거임을
            확인했어요.
          </label>
          <label className="grid gap-1">
            수정할 승인 문서
            <select
              className="rounded border p-2"
              value={documentId}
              disabled={pending}
              onChange={(event) => {
                setDocumentId(event.target.value)
                setBlocks(undefined)
                setSelectedBlocks([])
              }}
            >
              <option value="">문서 선택</option>
              {documentList
                .filter((item) => item.approved_version)
                .map((item) => (
                  <option key={item.id} value={item.id}>
                    {item.title}
                  </option>
                ))}
            </select>
          </label>
          {documents.isError && (
            <p role="alert">문서 목록을 불러오지 못했어요.</p>
          )}
          {documents.hasNextPage && (
            <Button
              type="button"
              variant="outline"
              disabled={documents.isFetchingNextPage}
              onClick={() => void documents.fetchNextPage()}
            >
              문서 더 보기
            </Button>
          )}
          <Button
            type="button"
            variant="outline"
            disabled={pending || !versionId}
            onClick={() => void loadBlocks()}
          >
            수정 범위 불러오기
          </Button>
          {blocks && (
            <>
              <fieldset className="grid max-h-56 gap-2 overflow-auto">
                <legend>수정할 부분 (최대 30개)</legend>
                {blocks.blocks.map((block) => (
                  <label
                    key={block.block_id}
                    className="flex gap-2 rounded border p-2"
                  >
                    <input
                      type="checkbox"
                      checked={selectedBlocks.includes(block.block_id)}
                      disabled={
                        pending ||
                        (selectedBlocks.length >= 30 &&
                          !selectedBlocks.includes(block.block_id))
                      }
                      onChange={(event) =>
                        setSelectedBlocks((old) =>
                          event.target.checked
                            ? [...old, block.block_id]
                            : old.filter((id) => id !== block.block_id),
                        )
                      }
                    />
                    <span className="whitespace-pre-wrap">{block.text}</span>
                  </label>
                ))}
              </fieldset>
              <label htmlFor="meeting-revision-instruction">수정 요청</label>
              <Textarea
                id="meeting-revision-instruction"
                maxLength={4000}
                value={instruction}
                disabled={pending}
                onChange={(event) => setInstruction(event.target.value)}
              />
              <Button
                type="button"
                disabled={pending || !valid}
                onClick={() => void generate()}
              >
                {pending ? '처리 중…' : '수정 초안 저장'}
              </Button>
            </>
          )}
        </>
      )}
      {error && (
        <p role="alert" className="text-destructive">
          {error}
        </p>
      )}
      {draft && (
        <div className="grid gap-2 rounded border p-3">
          <strong>저장된 초안 · 제출 전</strong>
          {draftOutdated && (
            <p role="status">
              이 초안은 이전 회의 정리 또는 문서 버전을 기준으로 만들었어요.
              현재 내용과 다시 비교해 주세요.
            </p>
          )}
          <div>
            <strong>확인한 회의 결정과 근거</strong>
            {draft.meeting.decisions.map((decision) => (
              <div key={decision.id} className="mt-2">
                <p>{decision.text}</p>
                <ul>
                  {decision.evidence_entry_ids.map((id) => (
                    <li key={id}>
                      근거 발언:{' '}
                      {entries.find((entry) => entry.id === id)?.text ??
                        `기록 ${id}`}
                    </li>
                  ))}
                </ul>
              </div>
            ))}
          </div>
          {draft.draft.changes.map((change) => (
            <div
              key={change.block_id}
              className="grid gap-1 rounded border p-2"
            >
              <span>수정 전</span>
              <pre className="whitespace-pre-wrap">{change.before}</pre>
              <span>수정 후</span>
              <pre className="whitespace-pre-wrap">{change.after}</pre>
              <span>이유: {change.reason}</span>
            </div>
          ))}
          {draft.draft.unresolved.map((item, index) => (
            <p key={index}>확인 필요: {item}</p>
          ))}
          <details className="rounded border p-2">
            <summary>전체 수정 초안 보기</summary>
            <p className="my-2 text-muted-foreground">
              내용을 복사해 변경 제안에서 직접 검토하고 제출할 수 있어요.
            </p>
            <pre className="max-h-80 overflow-auto whitespace-pre-wrap">
              {draft.draft.after_content}
            </pre>
            <Button
              type="button"
              variant="outline"
              onClick={() => {
                setCopied(false)
                if (!navigator.clipboard) {
                  setError(
                    '복사할 수 없어요. 내용을 직접 선택해 복사해 주세요.',
                  )
                  return
                }
                void navigator.clipboard
                  .writeText(draft.draft.after_content)
                  .then(() => setCopied(true))
                  .catch(() =>
                    setError(
                      '복사할 수 없어요. 내용을 직접 선택해 복사해 주세요.',
                    ),
                  )
              }}
            >
              전체 초안 복사
            </Button>
            {copied && <p role="status">전체 초안을 복사했어요.</p>}
            <Button
              type="button"
              variant="outline"
              onClick={() => openPane('changes', `doc:${draft.document_id}`)}
            >
              변경 제안 작성으로 이동
            </Button>
          </details>
        </div>
      )}
    </section>
  )
}
