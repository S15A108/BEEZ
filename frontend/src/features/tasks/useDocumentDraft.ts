import { useEffect, useRef, useState } from 'react'
import { getApiErrorCode, getApiErrorMessage } from '@/api/error'
import {
  draftKey,
  draftFieldLabel,
  fillEmptyFields,
  undoPatch,
  type DraftFill,
  type PlanFormSnapshot,
} from './planDraft'
import type { PlanDraft, PlanDraftInput } from './schemas'

// 작업 계획 문서 초안(TSK-A08, S15P31A108-385). 받기 상태와 폼에 채우는 규칙을 한곳에 둔다.
// 저장하지 않는다. 폼에 채우는 것까지만 하고, 등록은 사람이 확인한 뒤 기존 흐름으로 한다.

const draftMessages: Record<string, string> = {
  ai_timeout:
    '문서에서 초안을 만드는 데 시간이 너무 오래 걸렸어요. 잠시 뒤 다시 시도해 주세요.',
  ai_unavailable:
    '지금은 비비를 부를 수 없어요. 직접 작성하거나 잠시 뒤 다시 시도해 주세요.',
  ai_output_too_long:
    '비비가 응답 생성 한도에 도달해 초안을 완성하지 못했어요. 잠시 뒤 다시 시도해 주세요.',
}
// 다시 받으면 달라질 수 있는 실패. 안내 상자 오른쪽에 '다시 받기'를 둔다.
const retryable = new Set([
  'ai_timeout',
  'ai_unavailable',
  'ai_output_too_long',
])
// 이 프로젝트에서 더 받을 수 없는 실패. 창을 다시 열 때까지 버튼을 비활성으로 둔다.
const blocking = new Set(['draft_limit_exceeded', 'insufficient_documents'])

interface Options {
  /** 없으면 초안 기능을 쓰지 않는다(계획 수정 등) */
  request?: (input: PlanDraftInput) => Promise<PlanDraft>
  title: string
  /** 메모 칸이 없어 이미 적은 목표를 보낸다 */
  memo: string
  getSnapshot: () => PlanFormSnapshot
  apply: (patch: Partial<PlanFormSnapshot>) => void
}

export function useDocumentDraft({
  request,
  title,
  memo,
  getSnapshot,
  apply,
}: Options) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<{ message: string; retry: boolean }>()
  const [draft, setDraft] = useState<PlanDraft | null>(null)
  const [fill, setFill] = useState<DraftFill | null>(null)
  const [doneKey, setDoneKey] = useState<string | null>(null)
  const [blocked, setBlocked] = useState(false)
  // 되돌린 뒤 직접 고친 칸을 그대로 둔 경우의 안내
  const [undoNote, setUndoNote] = useState<string>()
  const alive = useRef(true)
  useEffect(() => {
    alive.current = true
    return () => {
      alive.current = false
    }
  }, [])

  const key = draftKey(title, memo)
  const sameInput = doneKey === key
  const canRequest =
    !!request && !busy && !blocked && !!title.trim() && !sameInput

  const requestDraft = async () => {
    if (!request || !canRequest) return
    setBusy(true)
    setError(undefined)
    setUndoNote(undefined)
    try {
      const received = await request({
        title: title.trim(),
        memo: memo.trim() || undefined,
      })
      if (!alive.current) return
      const snapshot = getSnapshot()
      const next = fillEmptyFields(snapshot, received)
      apply(next.patch)
      setDraft(received)
      setFill(next)
      // 메모로 보낸 목표를 초안이 채우면 입력이 달라 보인다. 채운 뒤의 값을 '같은 입력'의 기준으로 삼는다.
      setDoneKey(draftKey(title, next.patch.goal ?? snapshot.goal))
    } catch (cause) {
      if (!alive.current) return
      const code = getApiErrorCode(cause) ?? ''
      setError({
        message: draftMessages[code] ?? getApiErrorMessage(cause),
        retry: retryable.has(code),
      })
      if (blocking.has(code)) setBlocked(true)
    } finally {
      if (alive.current) setBusy(false)
    }
  }

  const undo = () => {
    if (!fill) return
    const { patch, kept } = undoPatch(fill, getSnapshot())
    apply(patch)
    if (kept.length)
      setUndoNote(
        `직접 고친 칸 ${kept.length}개(${kept.map((field) => draftFieldLabel[field]).join(' · ')})는 그대로 두었어요.`,
      )
    // 되돌린 뒤에는 노란 표시도 안내의 되돌리기도 없앤다.
    setFill({ ...fill, fields: [], patch: {} })
  }

  return {
    enabled: !!request,
    busy,
    error,
    draft,
    fill,
    canRequest,
    sameInput,
    requestDraft,
    undo,
    undoNote,
  }
}
