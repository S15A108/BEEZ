import { createContext } from 'react'

// 업무 창 머리 줄의 창별 버튼 자리(WorkspacePage가 준다). 없으면 버튼은 본문에 그대로 그린다.
export const PaneHeaderSlot = createContext<HTMLElement | null>(null)
