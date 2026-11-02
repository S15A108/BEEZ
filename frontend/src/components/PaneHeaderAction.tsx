import { use } from 'react'
import type { ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { PaneHeaderSlot } from './paneHeaderSlot'

/**
 * 업무 창 머리 줄(제목 오른쪽)에 둘 창별 버튼. 캔버스 PNL-05-v2-tasks처럼 '+ 작업 계획'을 머리에 둔다.
 * 머리 자리가 없으면(창 밖에서 그릴 때) 이 자리에 그대로 그린다.
 */
export function PaneHeaderAction({ children }: { children: ReactNode }) {
  const slot = use(PaneHeaderSlot)
  return slot ? createPortal(children, slot) : children
}
