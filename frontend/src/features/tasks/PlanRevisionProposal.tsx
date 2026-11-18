import { useEffect, useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { Sparkles } from 'lucide-react'
import type {
  TaskDetail,
  TaskPlanInput,
  Validation,
} from '@/api/contracts/common'
import { getApiErrorCode, getApiErrorMessage } from '@/api/error'
import { tasksApi } from '@/api/services'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { documentTypeLabel, draftFieldLabel } from './planDraft'
import type { PlanRevisionProposal as Proposal } from './schemas'

const planLabels: Record<keyof TaskPlanInput, string> = {
  title: '제목',
  ...draftFieldLabel,
  dependency_task_ids: '선행 작업',
  design_refs: '설계 참조',
  unlinked: '연결되지 않은 내용',
  no_contract_change: '계약 변경 없음',
  context_packet_id: '개발 맥락',
}
const changedFields = (proposal: Proposal) =>
  (Object.keys(planLabels) as (keyof TaskPlanInput)[]).filter(
    (field) =>
      JSON.stringify(proposal.before_plan[field]) !==
      JSON.stringify(proposal.proposed_plan[field]),
  )
const planValue = (value: TaskPlanInput[keyof TaskPlanInput]) => {
  if (
    value === undefined ||
    value === '' ||
    (Array.isArray(value) && !value.length)
  )
    return '없음'
  if (typeof value === 'boolean') return value ? '예' : '아니요'
  if (typeof value === 'string') return value
  return value
    .map((item) =>
      typeof item === 'string' ? `• ${item}` : JSON.stringify(item),
    )
    .join('\n')
}
const staleMessage =
  '계획이나 승인 기준, 검증 결과가 바뀌었어요. 최신 내용으로 수정안을 다시 받아 주세요.'
const proposalMessages: Record<string, string> = {
  proposal_stale: staleMessage,
  proposal_expired: '수정안의 유효 시간이 지났어요. 수정안을 다시 받아 주세요.',
  proposal_not_needed: '현재 검증에서 AI가 수정할 계획 항목을 찾지 못했어요.',
  insufficient_documents:
    '수정안을 만들 문서 근거가 부족해요. 승인된 문서를 확인해 주세요.',
  ai_invalid_output:
    '근거가 확인된 수정안을 완성하지 못했어요. 다시 시도해 주세요.',
}

interface Props {
  projectId: string
  task: TaskDetail
  validation: Validation
  baselineId?: string
  disabled?: boolean
  onOpenDocument: (id: string) => void
}

export function PlanRevisionProposal({
  projectId,
  task,
  validation,
  baselineId,
  disabled,
  onOpenDocument,
}: Props) {
  const client = useQueryClient()
  const [open, setOpen] = useState(false)
  const [proposal, setProposal] = useState<Proposal>()
  const [busy, setBusy] = useState<'generate' | 'apply'>()
  const [error, setError] = useState('')
  const [invalid, setInvalid] = useState(false)
  const [expired, setExpired] = useState(false)
  const [message, setMessage] = useState('')
  // React가 다음 렌더를 하기 전 연속 클릭도 한 번만 요청한다.
  const pending = useRef<'generate' | 'apply' | undefined>(undefined)
  const request = useRef<{ sequence: number; controller?: AbortController }>({
    sequence: 0,
  })
  const alive = useRef(true)
  useEffect(() => {
    alive.current = true
    const activeRequest = request.current
    return () => {
      alive.current = false
      activeRequest.sequence += 1
      activeRequest.controller?.abort()
    }
  }, [])
  useEffect(() => {
    if (!proposal) return
    const timer = setTimeout(
      () => setExpired(Date.parse(proposal.expires_at) <= Date.now()),
      Math.min(
        2_147_483_647,
        Math.max(0, Date.parse(proposal.expires_at) - Date.now()),
      ),
    )
    return () => clearTimeout(timer)
  }, [proposal])

  const isStale = (candidate: Proposal) => {
    return (
      invalid ||
      expired ||
      candidate.expected_revision_no !==
        task.current_plan_revision.revision_no ||
      candidate.expected_lock_version !== task.lock_version ||
      candidate.source_validation_id !== validation.id ||
      !['NEEDS_INPUT', 'NEEDS_REVIEW'].includes(validation.status) ||
      (!!baselineId && candidate.baseline_id !== baselineId)
    )
  }
  const stale = !!proposal && isStale(proposal)

  const generate = async () => {
    if (pending.current || disabled) return
    pending.current = 'generate'
    const sequence = ++request.current.sequence
    const controller = new AbortController()
    request.current.controller = controller
    setOpen(true)
    setBusy('generate')
    setError('')
    setMessage('')
    setInvalid(false)
    setExpired(false)
    setProposal(undefined)
    try {
      const received = await tasksApi.proposePlanRevision(projectId, task.id, {
        signal: controller.signal,
      })
      if (alive.current && sequence === request.current.sequence)
        setProposal(received)
    } catch (cause) {
      if (alive.current && sequence === request.current.sequence)
        setError(
          proposalMessages[getApiErrorCode(cause) ?? ''] ??
            getApiErrorMessage(cause),
        )
    } finally {
      if (alive.current && sequence === request.current.sequence) {
        pending.current = undefined
        setBusy(undefined)
      }
    }
  }

  const close = () => {
    if (pending.current === 'apply') return
    request.current.sequence += 1
    request.current.controller?.abort()
    pending.current = undefined
    setBusy(undefined)
    setOpen(false)
    setProposal(undefined)
    setError('')
  }

  const apply = async () => {
    if (
      !proposal ||
      pending.current ||
      disabled ||
      isStale(proposal) ||
      Date.parse(proposal.expires_at) <= Date.now()
    )
      return
    pending.current = 'apply'
    setBusy('apply')
    setError('')
    try {
      // 서버가 한 번의 승인 요청에서 저장과 검증 접수를 함께 처리한다.
      // 응답을 잃어도 같은 제안 ID로 재시도해 중복 리비전을 만들지 않는다.
      const applied = await tasksApi.applyPlanRevisionProposal(
        projectId,
        task.id,
        proposal.id,
      )
      client.setQueryData(
        ['projects', projectId, 'task', task.id],
        applied.task,
      )
      client.setQueryData(
        ['projects', projectId, 'validation', applied.validation.id],
        applied.validation,
      )
      if (alive.current) {
        setOpen(false)
        setProposal(undefined)
        setMessage('수정안을 새 계획으로 저장하고 재검증을 요청했어요.')
      }
      void client.invalidateQueries({ queryKey: ['projects', projectId] })
    } catch (cause) {
      if (!alive.current) return
      const code = getApiErrorCode(cause) ?? ''
      const outdated = [
        'proposal_stale',
        'proposal_expired',
        'revision_conflict',
        'baseline_conflict',
        'version_conflict',
      ].includes(code)
      setInvalid(outdated)
      setError(
        outdated
          ? staleMessage
          : `${getApiErrorMessage(cause)} 같은 수정안으로 다시 승인하면 적용 결과를 확인해요.`,
      )
      if (outdated)
        void client.invalidateQueries({ queryKey: ['projects', projectId] })
    } finally {
      pending.current = undefined
      if (alive.current) setBusy(undefined)
    }
  }

  return (
    <>
      <Button
        variant="outline"
        disabled={disabled || !!busy}
        onClick={() => void generate()}
      >
        <Sparkles />
        AI 계획 수정안 받기
      </Button>
      {message && (
        <p role="status" className="w-full text-sm text-emerald-700">
          {message}
        </p>
      )}
      <Dialog
        open={open}
        onOpenChange={(next) => {
          if (!next) close()
        }}
      >
        <DialogContent className="max-h-[85dvh] overflow-y-auto sm:max-w-3xl">
          <DialogHeader>
            <DialogTitle>AI 계획 수정안 검토</DialogTitle>
            <DialogDescription>
              변경 전후와 이유를 확인해 주세요. 승인하면 새 계획으로 저장하고
              자동으로 재검증해요.
            </DialogDescription>
          </DialogHeader>
          {busy === 'generate' && (
            <p role="status">현재 계획과 검증 근거로 수정안을 만들고 있어요.</p>
          )}
          {error && (
            <p role="alert" className="text-sm text-destructive">
              {error}
            </p>
          )}
          {stale && (
            <p role="alert" className="text-sm text-amber-800">
              {expired
                ? '수정안의 유효 시간이 지났어요. 수정안을 다시 받아 주세요.'
                : staleMessage}
            </p>
          )}
          {proposal && (
            <div className="space-y-5">
              {changedFields(proposal).map((field) => (
                <section
                  key={field}
                  aria-label={`${planLabels[field]} 변경`}
                  className="space-y-3 rounded-lg border p-4"
                >
                  <h3 className="font-semibold">{planLabels[field]}</h3>
                  <div className="grid gap-3 sm:grid-cols-2">
                    <div className="rounded-lg bg-muted p-3">
                      <h4 className="mb-2 text-xs font-medium">변경 전</h4>
                      <p className="text-sm break-words whitespace-pre-wrap">
                        {planValue(proposal.before_plan[field])}
                      </p>
                    </div>
                    <div className="rounded-lg border border-primary/30 bg-primary/5 p-3">
                      <h4 className="mb-2 text-xs font-medium">변경 후</h4>
                      <p className="text-sm break-words whitespace-pre-wrap">
                        {planValue(proposal.proposed_plan[field])}
                      </p>
                    </div>
                  </div>
                  {proposal.changes
                    .filter((change) => change.field === field)
                    .map((change, index) => (
                      <div key={index} className="space-y-1 text-sm">
                        <p>
                          <strong>수정 이유: </strong>
                          {change.reason}
                        </p>
                        {validation.findings
                          .filter((finding) =>
                            change.finding_ids.includes(finding.id),
                          )
                          .map((finding) => (
                            <p
                              key={finding.id}
                              className="text-muted-foreground"
                            >
                              검증 항목: {finding.message}
                            </p>
                          ))}
                      </div>
                    ))}
                </section>
              ))}
              {proposal.evidence.length > 0 && (
                <section aria-label="수정안 근거" className="space-y-3">
                  <h3 className="font-semibold">근거 문서</h3>
                  {proposal.evidence.map((source, index) => (
                    <div
                      key={index}
                      className="border-l-2 border-primary pl-3 text-sm"
                    >
                      <p>
                        {documentTypeLabel[source.document_type]} v
                        {source.version_no} · {source.location} ·{' '}
                        {draftFieldLabel[source.field]}
                      </p>
                      <p className="mt-1 text-muted-foreground">
                        “{source.quote}”
                      </p>
                      <Button
                        variant="link"
                        size="sm"
                        disabled={busy === 'apply'}
                        onClick={() => {
                          close()
                          onOpenDocument(source.document_id)
                        }}
                      >
                        문서함에서 보기
                      </Button>
                    </div>
                  ))}
                </section>
              )}
            </div>
          )}
          <DialogFooter>
            <Button
              variant="outline"
              onClick={close}
              disabled={busy === 'apply'}
            >
              취소
            </Button>
            {(!proposal || stale) && busy !== 'generate' && (
              <Button
                disabled={disabled || !!busy}
                onClick={() => void generate()}
              >
                수정안 다시 받기
              </Button>
            )}
            {proposal && (
              <Button
                disabled={
                  disabled || !!busy || stale || !changedFields(proposal).length
                }
                onClick={() => void apply()}
              >
                {busy === 'apply'
                  ? '적용하고 재검증하는 중…'
                  : '승인하고 재검증'}
              </Button>
            )}
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  )
}
