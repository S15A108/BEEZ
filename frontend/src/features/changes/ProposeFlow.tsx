import { useEffect, useMemo, useRef, useState } from 'react'
import type { ComponentProps, ReactNode } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, Check } from 'lucide-react'
import { changesApi } from '@/api/services'
import { getApiErrorCode, getApiErrorStatus, isMissingApi } from '@/api/error'
import type {
  ChangeProposal,
  ChangeProposalInput,
} from '@/api/contracts/common'
import { BibiFace } from '@/components/BibiFace'
import { Button } from '@/components/ui/button'
import { formatDate } from '@/lib/formatDate'
import { cn } from '@/lib/utils'
import { DraftReview } from './DraftReview'
import { acknowledgementReasons, hasCurrentReview } from './reviewState'
import { ProposalForm } from './ProposalForm'
import { operationLabel } from './proposalItems'
import type { ProposalFormValues } from './proposalItems'
import { proposeChanges } from './proposeDiff'
import type { ProposedChange } from './proposeDiff'
import type { Draft, DraftSuggestion } from './schemas'
import { Tag } from './Tag'
import { operationTone } from './tagTones'

// 문서 수정 제안(캔버스 PNL-04-v2-propose-doc·changes·running·review·confirm): ① 고친 문서 등록 → ② 변경점 확인 →
// ③ 비비 검토 → ④ 올리기 전 확인. ①과 다시 고치기는 팀장에게 가지 않는 작성 중 제안으로 저장하고(CHG-A16·A18),
// ③은 권장 단계라 건너뛰거나 실패해도 ④에서 상태를 확인하고 올린다(CHG-A01 review_acknowledged, 사용자 결정 11-06).
// 반려된 제안을 고쳐 다시 올릴 때도 같은 흐름이고, 마지막에 CHG-A04로 같은 제안 번호에 올린다(S15P31A108-325).
const STEPS = ['고친 문서 등록', '변경점 확인', '비비 검토', '올리기 전 확인']
const changeLabel = { UPDATE: '수정', CREATE: '추가', RETIRE: '삭제' } as const

// 작성 중 제안 목록 API가 없어 창을 닫았다 열 때 이어 볼 초안 ID를 이 브라우저에 둔다(프로젝트·반려 제안별).
const storageKey = (projectId: string, sourceId?: string) =>
  `beez.changeDraft.${projectId}${sourceId ? `.${sourceId}` : ''}`
function readStored(key: string) {
  try {
    return localStorage.getItem(key) ?? undefined
  } catch {
    return undefined
  }
}
function writeStored(key: string, value?: string) {
  try {
    if (value) localStorage.setItem(key, value)
    else localStorage.removeItem(key)
  } catch {
    // 저장소를 못 쓰면 이어 보기만 못 한다.
  }
}

const errorText: Record<string, string> = {
  version_conflict:
    '작성 중 제안이 다른 곳에서 바뀌었어요. 최신 상태를 불러왔으니 확인해 주세요.',
  review_in_progress:
    '비비가 검토하는 중에는 고칠 수 없어요. 검토가 끝난 뒤 다시 고쳐 주세요.',
  baseline_conflict:
    '기준이 바뀌었어요. 고친 글을 최신 기준과 다시 비교해 저장한 뒤 올려 주세요.',
  draft_already_submitted:
    '이미 팀장에게 올린 제안이에요. 변경 제안 목록에서 확인해 주세요.',
  review_stale:
    '고친 글이 바뀌어 이 수정안을 반영할 수 없어요. 다시 검토를 받아 주세요.',
  invalid_transition:
    '이 수정안은 지금 고를 수 없어요. 최신 검토를 다시 불러왔어요.',
  review_acknowledgement_required:
    '확인할 검토 상태가 생겼어요. 아래 상태를 확인한 뒤 올려 주세요.',
  not_found: '작성 중 제안을 찾을 수 없어요. 고친 문서를 다시 등록해 주세요.',
}

type FormProps = Pick<
  ComponentProps<typeof ProposalForm>,
  | 'tasks'
  | 'fetchDocumentContent'
  | 'requestDraft'
  | 'onDraftConflict'
  | 'defaultValues'
>
interface Props extends FormProps {
  projectId: string
  documents: {
    id: string
    label: string
    versionId?: string
    title: string
    versionNo: number
  }[]
  /** 지금 기준 버전 */
  baselineVersion?: number
  /** 지금 기준에 승인된 설계 항목의 키 → 종류 이름 */
  itemKinds: Record<string, string>
  /** 폼 값을 작성 중 제안 입력으로 바꾼다. 지금 기준·원래 제안의 나머지 변경을 묶는 일은 부모가 안다. */
  toInput: (values: ProposalFormValues) => ChangeProposalInput
  /** 반려된 제안을 고쳐 다시 올릴 때 그 제안. 초안은 이 제안을 보완하고(source_proposal_id) CHG-A04로 올린다. */
  source?: ChangeProposal
  /** ① 폼에 그대로 넘길 것(반려 사유, 받은 의견, 함께 올라가는 변경 등) */
  form?: Partial<ComponentProps<typeof ProposalForm>>
  onCancel: () => void
  /** CHG-A01·A04로 올린 뒤. ④ '팀장에게 올리기'에서만 부른다. */
  onSubmitted: (proposal: ChangeProposal) => void
}

export function ProposeFlow({
  projectId,
  documents,
  baselineVersion,
  itemKinds,
  tasks,
  fetchDocumentContent,
  requestDraft,
  onDraftConflict,
  defaultValues,
  toInput,
  source,
  form,
  onCancel,
  onSubmitted,
}: Props) {
  const queryClient = useQueryClient()
  const key = storageKey(projectId, source?.id)
  const [step, setStep] = useState(0)
  const [values, setValues] = useState<ProposalFormValues>()
  const [formKey, setFormKey] = useState(0)
  const [draftId, setDraftId] = useState<string>()
  const [stored, setStored] = useState(() => readStored(key))
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  // 올리기 전 확인은 그때 본 초안 리비전·검토 상태 해시에만 유효하다. 자동 갱신 등으로 바뀌면 체크가 풀리고 다시 받는다(PR #227 리뷰).
  const [acknowledgedAt, setAcknowledgedAt] = useState<{
    revision: number
    hash?: string
  }>()
  // 기준이 바뀌어 저장·검토·올리기가 409 baseline_conflict로 막혔다. 최신 기준과 다시 비교해 저장해야 풀린다.
  const [rebase, setRebase] = useState(false)
  // ①에서 등록할 때 비교한 기준 버전 번호. 그 뒤 승인본이 바뀌어도 비교·제출은 이 버전이므로 화면도 이 번호를 보인다.
  const [comparedNo, setComparedNo] = useState<number>()
  const draftQuery = useQuery({
    queryKey: ['projects', projectId, 'change-draft', draftId],
    queryFn: () => changesApi.getDraft(projectId, draftId!),
    enabled: !!draftId,
    // 검토가 도는 동안 몇 초마다 다시 읽는다(CHG-A17 처리 규칙).
    refetchInterval: (query) =>
      query.state.data?.latest_review?.status === 'RUNNING' ? 2000 : false,
  })
  const draft = draftQuery.data
  // 창을 닫았다 다시 열었을 때 이어 볼 초안. 없거나 이미 올렸으면 보이지 않는다.
  const resumeQuery = useQuery({
    queryKey: ['projects', projectId, 'change-draft', stored],
    queryFn: () => changesApi.getDraft(projectId, stored!),
    enabled: !!stored && !draftId,
    retry: false,
  })
  const resumable =
    !draftId && resumeQuery.data && !resumeQuery.data.submitted_proposal_id
      ? resumeQuery.data
      : undefined
  const document = documents.find(
    (item) => item.id === (values ?? defaultValues)?.document_id,
  )
  // 단계 중간에 승인본이 바뀌었는지. 그대로 올리면 서버가 baseline_conflict로 거절하므로 다시 비교하게 한다(PR #135 리뷰).
  const outdated =
    !!values?.base_version_id &&
    !!document?.versionId &&
    document.versionId !== values.base_version_id
  const shownNo = comparedNo ?? document?.versionNo
  // 수정안을 반영하면 서버의 고친 글이 바뀐다. 초안이 있으면 그 글을 보인다.
  const afterContent =
    draft?.documents.find((item) => item.document_id === values?.document_id)
      ?.after_content ??
    values?.after_content ??
    ''
  // 부모가 영향 분석을 1.5초마다 다시 받아 다시 그려도, 등록한 글이 바뀔 때만 줄 비교를 다시 한다(PR #135 리뷰).
  const found = useMemo(
    () =>
      values &&
      proposeChanges(
        values.document_id ? (values.base_content ?? '') : '',
        values.document_id ? afterContent : '',
      ),
    [values, afterContent],
  )
  // 반려된 제안의 설계 항목·다른 문서 변경은 고치지 않아도 함께 올라간다. 올리는 것은 초안이므로 초안으로 센다.
  // 이어서 열면 폼이 채우는 kept_* 값이 없어 설계 항목만 있는 초안이 변경 0건이 됐다(PR #227 리뷰).
  const kept = draft
    ? draft.items.length +
      draft.documents.filter((item) => item.document_id !== values?.document_id)
        .length
    : (values?.kept_items ?? 0) + (values?.kept_documents ?? 0)
  const task = tasks.find((item) => item.id === values?.task_id)
  const taskLabel =
    task && `${task.key} ${task.current_plan_revision.plan.title}`
  const reasons = draft ? acknowledgementReasons(draft) : []
  const acknowledged =
    !!draft &&
    acknowledgedAt?.revision === draft.revision &&
    acknowledgedAt.hash === draft.review_state_hash
  const acknowledgedStale = !!acknowledgedAt && !acknowledged
  const reviewRunning = draft?.latest_review?.status === 'RUNNING'
  const back = [
    document ? `${document.title} v${document.versionNo}` : '변경 제안',
    '고친 문서',
    '변경점',
    '비비 검토',
  ][step]
  // 단계가 바뀌면 창 안을 맨 위(단계 표시)로 올린다. 긴 고친 글을 쓰다 넘어가면 다음 단계가 중간부터 보인다.
  const top = useRef<HTMLDivElement>(null)
  useEffect(() => {
    top.current?.scrollIntoView?.({ block: 'nearest' })
  }, [step])

  const remember = (next: Draft) => {
    queryClient.setQueryData(
      ['projects', projectId, 'change-draft', next.id],
      next,
    )
    setDraftId(next.id)
    writeStored(key, next.id)
  }
  const fail = (cause: unknown, conflictText?: string) => {
    const code = getApiErrorCode(cause) ?? ''
    setError(
      isMissingApi(cause)
        ? '서버에 아직 작성 중 제안·비비 검토 API가 없어 진행할 수 없어요.'
        : (code === 'version_conflict' && conflictText) ||
            errorText[code] ||
            '요청을 처리하지 못했어요. 최신 상태와 권한을 확인해 주세요.',
    )
    if (code === 'baseline_conflict') setRebase(true)
    if (code === 'draft_already_submitted' || code === 'not_found')
      writeStored(key)
    // 충돌이면 초안·기준·문서를 다시 읽어 달라진 상태를 보인다.
    if (getApiErrorStatus(cause) === 409)
      void queryClient.invalidateQueries({ queryKey: ['projects', projectId] })
  }
  const run = async (action: () => Promise<void>, conflictText?: string) => {
    setBusy(true)
    setError('')
    try {
      await action()
    } catch (cause) {
      fail(cause, conflictText)
    } finally {
      setBusy(false)
    }
  }
  // 고친 글은 두고 기준 원문만 비워, 폼이 지금 승인본을 다시 받아 비교하게 한다.
  const recompare = () => {
    setValues(
      (current) =>
        current && {
          ...current,
          after_content: afterContent,
          base_content: undefined,
          base_version_id: undefined,
        },
    )
    setComparedNo(undefined)
    setRebase(false)
    setFormKey((value) => value + 1)
    setStep(0)
  }
  // 다시 고치기: 수정안을 반영한 글에서 이어 고친다.
  const edit = () => {
    setValues(
      (current) => current && { ...current, after_content: afterContent },
    )
    setStep(0)
  }
  const resume = (target: Draft) =>
    run(async () => {
      const change = target.documents[0]
      const base =
        change && fetchDocumentContent
          ? await fetchDocumentContent(change.document_id)
          : undefined
      const current =
        !change || base?.versionId === change.base_document_version_id
      setValues({
        title: target.title,
        reason: target.reason,
        task_id: target.task_id ?? '',
        document_id: change?.document_id ?? '',
        after_content: change?.after_content ?? '',
        ...(current && base
          ? { base_content: base.content, base_version_id: base.versionId }
          : {}),
      })
      setComparedNo(
        current
          ? documents.find((item) => item.id === change?.document_id)?.versionNo
          : undefined,
      )
      queryClient.setQueryData(
        ['projects', projectId, 'change-draft', target.id],
        target,
      )
      setDraftId(target.id)
      setFormKey((value) => value + 1)
      if (!current) {
        setRebase(true)
        setStep(0)
      } else setStep(target.latest_review ? 2 : 1)
    })
  const requestReview = () =>
    run(async () => {
      if (!draft) return
      const review = await changesApi.requestDraftReview(projectId, draft.id, {
        expected_revision: draft.revision,
      })
      queryClient.setQueryData<Draft>(
        ['projects', projectId, 'change-draft', draft.id],
        (current) => current && { ...current, latest_review: review },
      )
      // 검토 상태 해시(review_state_hash)는 초안을 다시 읽어야 온다.
      void draftQuery.refetch()
      setAcknowledgedAt(undefined)
      setStep(2)
    })
  const decide = (
    suggestion: DraftSuggestion,
    decision: 'APPLIED' | 'SKIPPED' | 'PENDING',
  ) =>
    run(async () => {
      if (!draft?.latest_review) return
      remember(
        await changesApi.decideDraftSuggestion(
          projectId,
          draft.id,
          draft.latest_review.id,
          suggestion.id,
          { expected_revision: draft.revision, decision },
        ),
      )
      setAcknowledgedAt(undefined)
    })
  const submit = () =>
    run(async () => {
      if (!draft) return
      // 확인할 상태가 있으면 사용자가 확인한 그 상태(리비전·review_state_hash)로만 올린다.
      const confirm = reasons.length > 0
      if (confirm && !acknowledged) return
      const body = {
        draft_id: draft.id,
        expected_draft_revision: draft.revision,
        review_acknowledged: confirm,
        ...(confirm && draft.latest_review && acknowledgedAt?.hash
          ? { expected_review_state_hash: acknowledgedAt.hash }
          : {}),
      }
      try {
        const proposal = source
          ? await changesApi.resubmitDraft(projectId, source.id, {
              ...body,
              expected_revision: source.revision,
              expected_snapshot_hash: source.snapshot_hash,
            })
          : await changesApi.submitDraft(projectId, body)
        writeStored(key)
        onSubmitted(proposal)
      } catch (cause) {
        setAcknowledgedAt(undefined)
        throw cause
      }
    }, '확인한 뒤 작성 중 제안이나 검토 상태가 바뀌었어요. 바뀐 상태를 다시 확인해 주세요.')

  return (
    <div className="grid content-start gap-4">
      <div
        ref={top}
        className="flex scroll-mt-24 flex-wrap items-center justify-between gap-x-4 gap-y-2"
      >
        <Button
          variant="ghost"
          size="sm"
          onClick={() =>
            step === 1 ? edit() : step ? setStep(step - 1) : onCancel()
          }
        >
          <ArrowLeft />
          {back}
        </Button>
        <Steps current={step} />
      </div>
      {step === 0 && resumable && (
        <div
          role="status"
          className="flex flex-wrap items-center gap-3 rounded-xl border border-primary/40 bg-primary/10 px-4 py-3 text-sm"
        >
          <p className="mr-auto">
            작성 중인 제안이 있어요: <strong>{resumable.title}</strong> ·{' '}
            {formatDate(resumable.updated_at)} 저장
            {resumable.latest_review && ' · 비비 검토 있음'}
          </p>
          <Button
            size="sm"
            variant="outline"
            onClick={() => {
              writeStored(key)
              setStored(undefined)
            }}
          >
            새로 쓰기
          </Button>
          <Button
            size="sm"
            disabled={busy}
            onClick={() => void resume(resumable)}
          >
            이어서 하기
          </Button>
        </div>
      )}
      {error && (
        <p
          role="alert"
          className="rounded-xl bg-destructive/5 px-4 py-3 text-sm text-destructive"
        >
          {error}
        </p>
      )}
      {step === 0 && (
        <ProposalForm
          key={formKey}
          heading="문서 수정 제안"
          description={`고친 문서를 등록하면 기준${baselineVersion ? ` v${baselineVersion}` : ''}과 비교해 바뀐 곳을 보여 줘요. 비비 검토를 받은 뒤 팀장에게 올려요.`}
          submitLabel="등록하고 변경점 찾기"
          footnote="등록해도 아직 팀장에게 가지 않아요."
          contentHint="고친 글을 쓰거나 .md 파일 한 개를 올려요. 바뀐 곳은 다음 단계에서 찾아요."
          projectId={projectId}
          documents={documents}
          tasks={tasks}
          fetchDocumentContent={fetchDocumentContent}
          requestDraft={requestDraft}
          onDraftConflict={onDraftConflict}
          {...form}
          defaultValues={values ?? defaultValues}
          onCancel={onCancel}
          onSubmit={(entered) =>
            run(async () => {
              const input = {
                ...toInput(entered),
                ...(source ? { source_proposal_id: source.id } : {}),
              }
              remember(
                draft && !draft.submitted_proposal_id
                  ? await changesApi.updateDraft(projectId, draft.id, {
                      ...input,
                      expected_revision: draft.revision,
                    })
                  : await changesApi.createDraft(projectId, input),
              )
              setValues(entered)
              setComparedNo(
                documents.find(
                  (item) =>
                    item.id === entered.document_id &&
                    item.versionId === entered.base_version_id,
                )?.versionNo,
              )
              setRebase(false)
              setAcknowledgedAt(undefined)
              setStep(1)
            })
          }
        />
      )}
      {step > 0 && (outdated || rebase) && (
        <div
          role="alert"
          className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-destructive/30 bg-destructive/5 px-4 py-3 text-sm"
        >
          <p>
            {outdated
              ? `승인본이 v${document?.versionNo}로 바뀌었어요. 고친 글은 v${shownNo}과 비교한 것이라 그대로 올리면 거절돼요.`
              : '기준이 바뀌었어요. 고친 글을 최신 기준과 다시 비교해 저장한 뒤 올려요. 이전 검토는 최신 검토로 인정되지 않아요.'}
          </p>
          <Button size="sm" variant="outline" onClick={recompare}>
            {outdated ? '새 승인본과 다시 비교' : '최신 기준과 다시 비교'}
          </Button>
        </div>
      )}
      {step === 1 && values && found && (
        <>
          <div>
            <h3 className="text-lg font-bold">
              바뀐 곳 {found.changes.length}개를 찾았어요
            </h3>
            <p className="mt-1 text-sm text-muted-foreground">
              {document?.title ?? '문서'} 기준 v{shownNo}과 고친 문서를 줄마다
              비교했어요. 고를 것은 없어요. 확인하고 비비 검토로 넘어가요.
            </p>
          </div>
          <div className="grid items-start gap-5 lg:grid-cols-[minmax(0,1fr)_300px]">
            <section aria-label="바뀐 곳" className="grid gap-2.5">
              <div className="flex flex-wrap items-center gap-1.5">
                {(['UPDATE', 'CREATE', 'RETIRE'] as const).map((kind) => (
                  <Tag key={kind} tone={operationTone[kind]}>
                    {changeLabel[kind]} {found.count(kind)}
                  </Tag>
                ))}
                <span className="ml-auto text-xs text-muted-foreground">
                  줄 +{found.addedLines} · -{found.removedLines}
                </span>
              </div>
              {found.changes.length ? (
                <ul className="grid gap-2">
                  {found.changes.map((change, index) => (
                    <ChangeCard key={index} change={change} />
                  ))}
                </ul>
              ) : kept ? (
                <p className="rounded-xl border px-3 py-2.5 text-[13px]">
                  고친 문서에서 바뀐 곳은 없어요. 원래 제안의 변경 {kept}건이
                  그대로 함께 올라가요.
                </p>
              ) : (
                // 빈 줄·끝 줄바꿈만 다르면 서버는 받지만 내용 없는 제안이 되므로 다음 단계로 보내지 않는다(PR #135 리뷰).
                <p className="rounded-xl border px-3 py-2.5 text-[13px]">
                  빈 줄 말고는 바뀐 곳이 없어요. 다시 고치기에서 내용을 고쳐
                  주세요.
                </p>
              )}
            </section>
            <aside className="grid gap-3">
              <Card title="비교한 문서">
                <p className="flex items-center gap-3 text-[13px]">
                  <span>
                    <strong className="block">기준 v{shownNo}</strong>
                    <span className="text-xs text-muted-foreground">
                      승인본
                    </span>
                  </span>
                  <span aria-hidden="true">→</span>
                  <span>
                    <strong className="block">고친 문서</strong>
                    <span className="text-xs text-muted-foreground">
                      방금 등록
                    </span>
                  </span>
                </p>
                <p className="text-xs text-muted-foreground">
                  {document?.title} · 바뀌지 않은 줄은 빼고 보여 줘요.
                </p>
                <WholeText text={afterContent} />
              </Card>
              <Card title="바뀐 줄의 설계 항목">
                {/* API 없음: 바뀐 곳에서 이어진 설계 항목을 찾아 CHG-A01 items에 채우는 기능(수정안 요청). 지금은 바뀐 줄에
                    적힌 키를 지금 기준의 승인 항목과 맞춰 보여 주기만 하고 제안에는 넣지 않는다. */}
                <ItemKeys changes={found.changes} itemKinds={itemKinds} />
              </Card>
            </aside>
          </div>
          <Footer
            note="다음 단계에서 비비가 영향받는 작업과 빠진 부분을 찾아요. 검토는 권장이라 건너뛸 수 있어요."
            back={['다시 고치기', edit]}
          >
            <Button
              variant="ghost"
              disabled={busy || (!found.changes.length && !kept)}
              onClick={() => setStep(3)}
            >
              검토 없이 다음
            </Button>
            <Button
              disabled={busy || !draft || (!found.changes.length && !kept)}
              onClick={() =>
                draft && hasCurrentReview(draft)
                  ? setStep(2)
                  : void requestReview()
              }
            >
              <BibiFace className="size-5" />
              {draft && hasCurrentReview(draft)
                ? '검토 결과 보기'
                : '비비 검토 받기'}
            </Button>
          </Footer>
        </>
      )}
      {step === 2 && (
        <>
          {draft ? (
            <DraftReview
              draft={draft}
              busy={busy}
              onRequest={() => void requestReview()}
              onDecide={(suggestion, decision) =>
                void decide(suggestion, decision)
              }
            />
          ) : (
            <p className="text-sm text-muted-foreground">
              작성 중인 제안을 불러오는 중이에요.
            </p>
          )}
          <Footer
            note={
              reviewRunning
                ? '창을 닫아도 검토는 계속돼요. 기다리지 않고 다음으로 가도 돼요.'
                : '수정안을 모두 고르지 않아도 다음으로 갈 수 있어요. 반영하면 고친 글이 바뀌어요.'
            }
            back={['다시 고치기', edit]}
            backDisabled={busy || reviewRunning}
          >
            <Button disabled={busy || !draft} onClick={() => setStep(3)}>
              다음
            </Button>
          </Footer>
        </>
      )}
      {step === 3 && values && found && (
        <>
          {draft && !reasons.length && (
            <p className="flex items-center gap-2.5 rounded-xl border border-primary/50 bg-[#fdf3d3] px-4 py-3 text-sm">
              <BibiFace className="size-7" />
              비비 의견을 확인한 최종본이에요. 팀장이 보게 될 내용과 같아요.
            </p>
          )}
          <div>
            <h3 className="text-lg font-bold">올리기 전에 확인해요</h3>
            <p className="mt-1 text-sm text-muted-foreground">
              {source
                ? '올리면 같은 제안 번호로 다시 검토 대기가 돼요. 이전 내용과 반려 사유는 이력에 남아요.'
                : '올리면 검토 대기로 팀장에게 가요. 올린 뒤에는 고칠 수 없고 거두기만 할 수 있어요.'}
            </p>
          </div>
          {reasons.length > 0 && (
            <div
              role="group"
              aria-label="올리기 전 확인할 검토 상태"
              className="grid gap-2 rounded-xl border border-primary/50 bg-primary/10 px-4 py-3 text-sm"
            >
              <ul className="grid list-disc gap-0.5 pl-5">
                {reasons.map((reason) => (
                  <li key={reason}>{reason}</li>
                ))}
              </ul>
              <p className="text-xs text-muted-foreground">
                비비 검토는 권장이에요. 이 상태 그대로 올려도 팀장 상세에 함께
                남아요.
              </p>
              {acknowledgedStale && (
                <p className="text-[13px] font-semibold text-destructive">
                  확인한 뒤 검토 상태가 바뀌었어요. 바뀐 상태를 보고 다시 확인해
                  주세요.
                </p>
              )}
              <label className="flex items-center gap-2 font-semibold">
                <input
                  type="checkbox"
                  className="size-4 accent-primary"
                  checked={acknowledged}
                  onChange={(event) =>
                    setAcknowledgedAt(
                      event.target.checked && draft
                        ? {
                            revision: draft.revision,
                            hash: draft.review_state_hash,
                          }
                        : undefined,
                    )
                  }
                />
                이 상태 그대로 올릴게요
              </label>
            </div>
          )}
          <div className="grid items-start gap-5 lg:grid-cols-2">
            <dl className="grid gap-3 text-sm">
              <Field label="제목">{values.title}</Field>
              <Field label="바꾸려는 이유">{values.reason}</Field>
              {values.document_id && (
                <Field label="고친 문서">
                  {document?.title ?? '문서'} · 기준 v{shownNo}에서 고친 글
                  <WholeText text={afterContent} />
                </Field>
              )}
              {taskLabel && <Field label="관련 작업">{taskLabel}</Field>}
            </dl>
            {/* 줄이는(truncate) 긴 줄이 칸을 넓히지 않게 열 폭을 칸에 묶는다. */}
            <div className="grid min-w-0 grid-cols-1 gap-3">
              <section
                aria-label="바뀌는 곳"
                className="grid grid-cols-1 gap-1"
              >
                <h4 className="text-sm font-bold">
                  바뀌는 곳 {found.changes.length}
                </h4>
                <ul>
                  {found.changes.map((change, index) => (
                    <li
                      key={index}
                      className="flex items-center gap-2 border-b py-2 text-[13px] last:border-b-0"
                    >
                      <Tag tone={operationTone[change.kind]}>
                        {changeLabel[change.kind]}
                      </Tag>
                      {change.key && (
                        <strong className="shrink-0">{change.key}</strong>
                      )}
                      <span className="truncate text-muted-foreground">
                        {(change.after ?? change.before)!
                          .replace(change.key ?? '', '')
                          .trim()}
                      </span>
                    </li>
                  ))}
                </ul>
                {kept > 0 && (
                  <p className="text-xs text-muted-foreground">
                    원래 제안의 변경 {kept}건이 함께 올라가요.
                  </p>
                )}
              </section>
              {draft && <ReviewSummary draft={draft} />}
            </div>
          </div>
          <Footer
            note="올리면 팀장에게 알림이 가요."
            back={
              draft?.latest_review
                ? ['검토로 돌아가기', () => setStep(2)]
                : ['변경점으로 돌아가기', () => setStep(1)]
            }
          >
            <Button
              disabled={
                busy ||
                outdated ||
                rebase ||
                !draft ||
                (reasons.length > 0 && !acknowledged)
              }
              onClick={() => void submit()}
            >
              {busy ? '올리는 중…' : '팀장에게 올리기'}
            </Button>
          </Footer>
        </>
      )}
    </div>
  )
}

function ReviewSummary({ draft }: { draft: Draft }) {
  const review = draft.latest_review
  const related =
    review?.steps.find((step) => step.key === 'FIND_RELATED')?.status === 'DONE'
  const skipped =
    review?.suggestions.filter(
      (item) => item.type === 'COMMENT' || item.decision === 'SKIPPED',
    ) ?? []
  return (
    <section
      aria-label="비비 검토"
      className="grid gap-1.5 rounded-xl bg-[#faf7ee] px-4 py-3 text-[13px]"
    >
      <h4 className="font-bold">비비 검토</h4>
      <p>
        {!review
          ? '받지 않았어요.'
          : review.status === 'RUNNING'
            ? '아직 검토하고 있어요.'
            : review.status !== 'COMPLETED'
              ? '끝나지 못했어요.'
              : related
                ? `영향 작업 ${review.tasks_to_wait.length} · 검증 ${review.validations_to_stale.length} · AI 후보 ${review.ai_candidates == null ? '확인 못함' : review.ai_candidates.length}`
                : `영향 확인 못함 · AI 후보 ${review.ai_candidates == null ? '확인 못함' : review.ai_candidates.length}`}
      </p>
      {skipped.length > 0 && (
        <ul className="grid gap-0.5">
          {skipped.map((item) => (
            <li key={item.id}>
              넘긴 의견
              {item.design_item_key ? ` ${item.design_item_key}` : ''}:{' '}
              {item.explanation}
            </li>
          ))}
        </ul>
      )}
      <p className="text-xs text-muted-foreground">
        올리면 이 검토 상태가 팀장 상세에 함께 남아요.
      </p>
    </section>
  )
}

function Steps({ current }: { current: number }) {
  return (
    <ol
      aria-label="문서 수정 제안 단계"
      className="flex flex-wrap items-center gap-x-1 gap-y-2"
    >
      {STEPS.map((label, index) => (
        <li
          key={label}
          aria-current={index === current ? 'step' : undefined}
          className="flex items-center gap-2"
        >
          <span
            className={cn(
              'grid size-6 place-items-center rounded-full text-xs font-bold',
              index < current && 'bg-primary text-[#322710]',
              index === current &&
                'border-2 border-primary bg-white text-[#322710] shadow-[0_0_0_3px_#fdf3d3]',
              index > current && 'border border-[#d4d0c4] text-[#8a877e]',
            )}
          >
            {index < current ? (
              <Check aria-hidden="true" className="size-3.5" />
            ) : (
              index + 1
            )}
            {index < current && <span className="sr-only">완료</span>}
          </span>
          <span
            className={cn(
              'text-[13px]',
              index === current ? 'font-bold' : 'text-[#8a877e]',
              index < current && 'text-foreground',
            )}
          >
            {label}
          </span>
          {index < STEPS.length - 1 && (
            <span
              aria-hidden="true"
              className={cn(
                'mx-1 h-0.5 w-7 rounded-full',
                index < current ? 'bg-primary' : 'bg-[#e5e2d9]',
              )}
            />
          )}
        </li>
      ))}
    </ol>
  )
}

function ChangeCard({ change }: { change: ProposedChange }) {
  return (
    <li className="grid gap-2 rounded-xl border px-3 py-2.5 text-[13px]">
      <p className="flex items-center gap-2">
        <Tag tone={operationTone[change.kind]}>{changeLabel[change.kind]}</Tag>
        {change.key && <strong>{change.key}</strong>}
        {change.section && (
          <span className="text-xs text-muted-foreground">
            {change.section}
          </span>
        )}
      </p>
      {change.before && (
        <p className="rounded-md bg-[#fdecec] px-2.5 py-1 text-[#b42318]">
          <span aria-label="뺀 줄">- </span>
          {change.before}
        </p>
      )}
      {change.after && (
        <p className="rounded-md bg-[#e8f3ec] px-2.5 py-1">
          <span aria-label="더한 줄">+ </span>
          {change.after}
        </p>
      )}
    </li>
  )
}

function ItemKeys({
  changes,
  itemKinds,
}: {
  changes: ProposedChange[]
  itemKinds: Record<string, string>
}) {
  const keys = [...new Set(changes.flatMap((change) => change.key ?? []))]
  // 그 키가 지운 줄에만 있으면 없애기, 아니면 지금 기준에 승인된 키는 고치기, 처음 보는 키는 새 항목이다(PR #135 리뷰).
  const operationOf = (key: string) =>
    changes.every((change) => change.key !== key || change.kind === 'RETIRE')
      ? 'RETIRE'
      : itemKinds[key]
        ? 'UPDATE'
        : 'CREATE'
  return (
    <>
      <p className="text-xs text-muted-foreground">
        바뀐 줄에 적힌 항목 키예요. 이어진 설계 항목을 찾아 제안에 넣는 기능은
        아직 없어 보여 주기만 해요.
      </p>
      {keys.length ? (
        <ul className="grid">
          {keys.map((key) => (
            <li
              key={key}
              className="flex items-center gap-2 border-b py-1.5 text-[13px] last:border-b-0"
            >
              <strong>{key}</strong>
              <span className="text-xs text-muted-foreground">
                {itemKinds[key]}
              </span>
              <span className="ml-auto text-xs text-muted-foreground">
                {operationLabel[operationOf(key)]}
              </span>
            </li>
          ))}
        </ul>
      ) : (
        <p className="text-[13px]">바뀐 줄에 항목 키가 없어요.</p>
      )}
    </>
  )
}

function WholeText({ text }: { text: string }) {
  return (
    <details className="text-[13px]">
      <summary className="cursor-pointer font-semibold text-[#8a6a00]">
        원문 전체 보기
      </summary>
      <pre className="mt-2 max-h-64 overflow-auto rounded-lg bg-muted/40 p-2.5 font-mono text-xs whitespace-pre-wrap">
        {text}
      </pre>
    </details>
  )
}

function Card({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section
      aria-label={title}
      className="grid gap-2 rounded-[14px] border px-4 py-3.5"
    >
      <h4 className="text-[15px] font-bold">{title}</h4>
      {children}
    </section>
  )
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="grid gap-0.5">
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className="whitespace-pre-wrap">{children}</dd>
    </div>
  )
}

function Footer({
  note,
  back,
  backDisabled,
  children,
}: {
  note: string
  back: [string, () => void]
  backDisabled?: boolean
  children: ReactNode
}) {
  return (
    <div className="flex flex-wrap items-center justify-end gap-2 border-t pt-4">
      <p className="mr-auto text-xs text-muted-foreground">{note}</p>
      <Button variant="outline" disabled={backDisabled} onClick={back[1]}>
        {back[0]}
      </Button>
      {children}
    </div>
  )
}
