import { useState } from 'react'
import { changesApi } from '@/api/services/changes'
import { getApiErrorMessage } from '@/api/error'
import { Button } from '@/components/ui/button'
import { Textarea } from '@/components/ui/textarea'
import type { ScopedBlocksResponse, ScopedDraftResponse } from './schemas'

interface Props {
  projectId: string
  documentId: string
  versionId: string
  taskId?: string
  currentContent: string
  onApply: (content: string) => void
}

export function ScopedDraft(props: Props) {
  return (
    <DraftEditor
      key={`${props.projectId}:${props.documentId}:${props.versionId}:${props.taskId ?? ''}`}
      {...props}
    />
  )
}

function DraftEditor({
  projectId,
  documentId,
  versionId,
  taskId,
  currentContent,
  onApply,
}: Props) {
  const [blocks, setBlocks] = useState<ScopedBlocksResponse>()
  const [selected, setSelected] = useState<string[]>([])
  const [instruction, setInstruction] = useState('')
  const [agreements, setAgreements] = useState('')
  const [proposals, setProposals] = useState('')
  const [draft, setDraft] = useState<ScopedDraftResponse>()
  const [contentAtRequest, setContentAtRequest] = useState('')
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string>()
  const base = { document_id: documentId, base_document_version_id: versionId }
  const lines = (value: string) =>
    value
      .split('\n')
      .map((item) => item.trim())
      .filter(Boolean)
  const agreementItems = lines(agreements)
  const proposalItems = lines(proposals)
  const invalidReferences = [agreementItems, proposalItems].some(
    (items) => items.length > 30 || items.some((item) => item.length > 4000),
  )
  const sameDocument = (result: typeof base) =>
    result.document_id === documentId &&
    result.base_document_version_id === versionId
  const load = async () => {
    setPending(true)
    setError(undefined)
    setDraft(undefined)
    setBlocks(undefined)
    setSelected([])
    try {
      const result = await changesApi.scopedBlocks(projectId, base)
      if (!sameDocument(result))
        throw new Error('기준 문서가 변경됐어요. 다시 불러와 주세요.')
      setBlocks(result)
    } catch (cause) {
      setError(getApiErrorMessage(cause))
    } finally {
      setPending(false)
    }
  }
  const generate = async () => {
    setPending(true)
    setError(undefined)
    setDraft(undefined)
    setContentAtRequest(currentContent)
    try {
      const result = await changesApi.scopedDraft(projectId, {
        ...base,
        selected_block_ids: selected,
        instruction,
        ...(taskId ? { task_id: taskId } : {}),
        ...(agreementItems.length ? { agreements: agreementItems } : {}),
        ...(proposalItems.length ? { related_proposals: proposalItems } : {}),
      })
      if (!sameDocument(result))
        throw new Error('기준 문서가 변경됐어요. 다시 불러와 주세요.')
      setDraft(result)
    } catch (cause) {
      setError(getApiErrorMessage(cause))
    } finally {
      setPending(false)
    }
  }
  return (
    <section
      aria-label="비비 수정 초안"
      className="grid gap-3 rounded-lg border p-3 text-sm"
    >
      <strong>비비와 선택한 부분 고치기</strong>
      <p className="text-xs text-muted-foreground">
        기준 문서에서 고칠 부분을 선택해요. 결과를 확인하고 직접 적용할 수
        있어요.
      </p>
      <Button
        type="button"
        size="sm"
        variant="outline"
        disabled={pending}
        onClick={() => void load()}
      >
        수정 범위 불러오기
      </Button>
      {blocks && (
        <>
          <fieldset className="grid max-h-56 gap-2 overflow-auto">
            <legend>고칠 부분 선택 (최대 30개)</legend>
            {blocks.blocks.map((block) => (
              <label
                key={block.block_id}
                className="flex gap-2 rounded border p-2"
              >
                <input
                  type="checkbox"
                  checked={selected.includes(block.block_id)}
                  disabled={
                    pending ||
                    (selected.length >= 30 &&
                      !selected.includes(block.block_id))
                  }
                  onChange={(event) => {
                    setSelected(
                      event.target.checked
                        ? [...selected, block.block_id]
                        : selected.filter((id) => id !== block.block_id),
                    )
                    setDraft(undefined)
                  }}
                />
                <span className="min-w-0 whitespace-pre-wrap">
                  {block.text}
                </span>
              </label>
            ))}
          </fieldset>
          <label htmlFor="scoped-instruction">수정 요청</label>
          <Textarea
            id="scoped-instruction"
            maxLength={4000}
            value={instruction}
            disabled={pending}
            onChange={(event) => {
              setInstruction(event.target.value)
              setDraft(undefined)
            }}
          />
          <details className="rounded border p-2">
            <summary className="cursor-pointer">
              직접 입력한 참고 내용 (선택)
            </summary>
            <p className="text-xs text-muted-foreground">
              합의나 관련 제안을 한 줄에 하나씩 입력해 주세요. 팀의 확정
              내용인지 직접 확인해 주세요.
            </p>
            <label htmlFor="scoped-agreements">직접 입력한 합의 내용</label>
            <Textarea
              id="scoped-agreements"
              value={agreements}
              disabled={pending}
              onChange={(event) => {
                setAgreements(event.target.value)
                setDraft(undefined)
              }}
            />
            <label htmlFor="scoped-proposals">직접 입력한 관련 변경 제안</label>
            <Textarea
              id="scoped-proposals"
              value={proposals}
              disabled={pending}
              onChange={(event) => {
                setProposals(event.target.value)
                setDraft(undefined)
              }}
            />
            {invalidReferences && (
              <p role="alert">
                각 목록은 30개 이하, 한 줄은 4,000자 이하로 입력해 주세요.
              </p>
            )}
          </details>
          <Button
            type="button"
            size="sm"
            disabled={
              pending ||
              !selected.length ||
              !instruction.trim() ||
              invalidReferences
            }
            onClick={() => void generate()}
          >
            {pending ? '처리 중…' : '선택 범위 초안 만들기'}
          </Button>
        </>
      )}
      {error && (
        <p role="alert" className="text-destructive">
          {error}
        </p>
      )}
      {draft && (
        <div className="grid gap-2">
          <strong>수정 전후 확인</strong>
          {draft.changes.map((change) => (
            <div
              key={change.block_id}
              className="grid gap-1 rounded border p-2"
            >
              <span>수정 전</span>
              <pre className="whitespace-pre-wrap">{change.before}</pre>
              <span>수정 후</span>
              <pre className="whitespace-pre-wrap">{change.after}</pre>
              <span>이유: {change.reason}</span>
              {change.evidence.map((item, index) => (
                <span key={index}>근거: {item}</span>
              ))}
            </div>
          ))}
          {draft.unresolved.map((item, index) => (
            <p key={index}>확인 필요: {item}</p>
          ))}
          {currentContent !== contentAtRequest && (
            <p role="alert">
              초안 생성 후 고친 글이 바뀌었어요. 다시 초안을 만들어 주세요.
            </p>
          )}
          <Button
            type="button"
            size="sm"
            disabled={
              !draft.changes.length || currentContent !== contentAtRequest
            }
            onClick={() => onApply(draft.after_content)}
          >
            초안 적용
          </Button>
        </div>
      )}
    </section>
  )
}
