import { create } from 'zustand'
import { createJSONStorage, persist } from 'zustand/middleware'

// '비비에게 요청하기'를 여러 의견에 동시에 눌러도 요청마다 진행·실패가 남도록, 요청 상태를 카드 밖에 둔다.
// 카드 안에 두면 다른 요청이 먼저 끝나 화면 단계(제안 ↔ 최종본)가 바뀔 때 카드가 다시 만들어지며
// 아직 돌고 있던 요청의 진행 표시와 실패 안내가 사라져 요청이 누락된 것처럼 보였다(S15P31A108-382).
export type RequestState = {
  status: 'pending' | 'failed' | 'no_change'
  message?: string
  /** 보낸 요청 글. 답이 오기 전에 카드가 다시 만들어져도 쓰던 글을 되살린다. */
  draft?: string
}

interface SuggestionRequestState {
  /** `${reviewId}:${의견 id}` → 진행 중·실패·고칠 곳 없음 요청. 새 제안이 생기면 지운다. */
  requests: Record<string, RequestState>
  /** 새 제안 id → 그 제안을 요청한 의견 id. 새 제안에 의견 번호를 이어 붙이려고 브라우저에 기억한다. */
  sources: Record<string, string>
  begin: (key: string, draft: string) => void
  fail: (key: string, message: string) => void
  noChange: (key: string, message: string, draft: string) => void
  succeed: (
    key: string,
    newSuggestionId: string | undefined,
    sourceId: string,
  ) => void
  clear: (key: string) => void
}

// 오래된 검토의 기록이 끝없이 쌓이지 않게 최근 것만 남긴다.
const SOURCES_MAX = 200

export const useSuggestionRequestStore = create<SuggestionRequestState>()(
  persist(
    (set) => ({
      requests: {},
      sources: {},
      begin: (key, draft) =>
        set((state) => ({
          requests: { ...state.requests, [key]: { status: 'pending', draft } },
        })),
      noChange: (key, message, draft) =>
        set((state) => ({
          requests: {
            ...state.requests,
            [key]: { status: 'no_change', message, draft },
          },
        })),
      fail: (key, message) =>
        set((state) => ({
          requests: { ...state.requests, [key]: { status: 'failed', message } },
        })),
      succeed: (key, newSuggestionId, sourceId) =>
        set((state) => {
          const { [key]: _done, ...requests } = state.requests
          void _done
          if (!newSuggestionId) return { requests }
          const sources = { ...state.sources, [newSuggestionId]: sourceId }
          const ids = Object.keys(sources)
          for (const id of ids.slice(0, Math.max(0, ids.length - SOURCES_MAX)))
            delete sources[id]
          return { requests, sources }
        }),
      clear: (key) =>
        set((state) => {
          const { [key]: _gone, ...requests } = state.requests
          void _gone
          return { requests }
        }),
    }),
    {
      name: 'beez-suggestion-request-sources',
      storage: createJSONStorage(() => localStorage),
      // 진행 중·실패 상태는 새로고침하면 의미가 없다. 번호를 잇는 기록만 저장한다.
      partialize: (state) => ({ sources: state.sources }),
    },
  ),
)
