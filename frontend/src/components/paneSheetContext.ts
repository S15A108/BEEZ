import { createContext } from 'react'

/** 창 안 하위 화면의 몸통. 폼은 이걸 보고 아래 고정 줄(취소 + 확인)을 붙인다. */
export const PaneSheetBody = createContext<{ onClose: () => void } | null>(null)

/** 하위 화면을 그릴 자리와 열린 수 */
export const PaneSheetContext = createContext<{
  host: HTMLElement | null
  track: (open: boolean) => void
  /** 하위 화면이 하나라도 열려 있는지. 열려 있으면 창 머리 버튼을 숨긴다(PR #88 리뷰). */
  open: boolean
} | null>(null)
