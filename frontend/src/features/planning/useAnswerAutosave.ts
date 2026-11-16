import { useEffect, useMemo, useRef, useState } from 'react'
import { getApiErrorMessage } from '@/api/error'
import type { AnswerQuestionInput } from './schemas'

// 글 답변은 입력을 멈추고 이만큼 지나면 저장한다.
export const AUTOSAVE_DELAY_MS = 1000

export type SaveState =
  | { state: 'saving' }
  | { state: 'saved' }
  | { state: 'empty' }
  | { state: 'failed'; message: string }

interface Entry {
  timer?: ReturnType<typeof setTimeout>
  /** 기다리는 중인 값(타이머가 끝나면 보낸다) */
  next?: AnswerQuestionInput
  inFlight: boolean
  /** 보내는 중에 들어온 마지막 값. 끝나면 이것만 보낸다 */
  queued?: AnswerQuestionInput
  /** 지금 보내는 저장. 끝날 때까지 기다릴 수 있게 둔다 */
  sending?: Promise<void>
  /** 보내는 중에 답이 비었다. 끝나도 '저장됨'으로 덮지 않는다(서버에는 앞 값이 남아 있다) */
  empty?: boolean
}

// 보완 질문 답변 자동 저장(PLN-A04). 질문마다 따로 센다.
// 같은 질문에 요청이 겹치지 않게, 보내는 중에 바뀐 값은 마지막 것만 모아 두었다가 끝나면 보낸다.
// 빈 답(undefined)은 서버가 받지 않으므로 보내지 않는다.
export function useAnswerAutosave(
  save: (id: string, input: AnswerQuestionInput) => Promise<unknown>,
  delay = AUTOSAVE_DELAY_MS,
) {
  const saveRef = useRef(save)
  useEffect(() => {
    saveRef.current = save
  })
  const [states, setStates] = useState<Record<string, SaveState>>({})
  const engine = useMemo(() => {
    const entries = new Map<string, Entry>()
    const setState = (id: string, value: SaveState) =>
      setStates((all) => ({ ...all, [id]: value }))
    const clearState = (id: string) =>
      setStates((all) =>
        Object.fromEntries(Object.entries(all).filter(([key]) => key !== id)),
      )
    const entryOf = (id: string) => {
      let entry = entries.get(id)
      if (!entry) {
        entry = { inFlight: false }
        entries.set(id, entry)
      }
      return entry
    }
    const stop = (entry: Entry) => {
      clearTimeout(entry.timer)
      entry.timer = undefined
      entry.next = undefined
    }
    const send = (id: string, input: AnswerQuestionInput) => {
      const entry = entryOf(id)
      if (entry.inFlight) {
        entry.queued = input
        return
      }
      entry.inFlight = true
      setState(id, { state: 'saving' })
      entry.sending = (async () => {
        // 끝났을 때 상태를 바꿔도 되는지: 뒤에 보낼 값이 없고, 그사이 답이 비지 않았을 때만
        const last = () => !entry.queued && !entry.timer && !entry.empty
        try {
          await saveRef.current(id, input)
          if (last()) setState(id, { state: 'saved' })
        } catch (cause) {
          if (last())
            setState(id, {
              state: 'failed',
              message: getApiErrorMessage(cause),
            })
        } finally {
          entry.inFlight = false
          entry.sending = undefined
          const queued = entry.queued
          entry.queued = undefined
          if (queued) send(id, queued)
        }
      })()
    }
    return {
      /** 기다리지 않고 바로 저장한다(선택형, 다시 시도) */
      saveNow(id: string, input: AnswerQuestionInput | undefined) {
        const entry = entryOf(id)
        stop(entry)
        entry.empty = !input
        if (!input) {
          entry.queued = undefined
          setState(id, { state: 'empty' })
          return
        }
        send(id, input)
      },
      /** 입력을 멈추고 delay 뒤에 저장한다(글 입력) */
      schedule(id: string, input: AnswerQuestionInput | undefined) {
        const entry = entryOf(id)
        stop(entry)
        entry.empty = !input
        if (!input) {
          entry.queued = undefined
          setState(id, { state: 'empty' })
          return
        }
        entry.next = input
        setState(id, { state: 'saving' })
        entry.timer = setTimeout(() => {
          const next = entry.next
          entry.timer = undefined
          entry.next = undefined
          if (next) send(id, next)
        }, delay)
      },
      /** 기다리는 저장이 있으면 바로 보낸다(칸을 벗어날 때, 다른 질문으로 옮길 때) */
      flush(id: string) {
        const entry = entries.get(id)
        if (!entry?.timer) return
        const next = entry.next
        stop(entry)
        if (next) send(id, next)
      },
      /** 기다리는 저장을 버리고, 이미 보내는 저장이 끝날 때까지 기다린다(답하지 않고 닫기가 그 뒤에 서버에 닿게).
       *  그 질문의 저장 상태도 지운다. 닫은 질문에 '저장 중…'이나 '저장됨'이 남지 않게 한다 */
      async cancel(id: string) {
        const entry = entries.get(id)
        if (entry) {
          stop(entry)
          entry.queued = undefined
          await entry.sending
          entry.empty = false
        }
        clearState(id)
      },
    }
  }, [delay])
  return { states, ...engine }
}
