import { useState } from 'react'

export const LIST_STEP = 5

// 긴 목록을 한 번에 다 그리지 않고 step 개씩 보여 준다. 목록이 줄어도 보이는 개수는 그대로 두고 slice 가 알아서 자른다.
export function useShowMore<T>(items: T[], step = LIST_STEP) {
  const [visible, setVisible] = useState(step)
  const shown = items.slice(0, visible)
  return {
    shown,
    hidden: Math.max(items.length - shown.length, 0),
    more: () => setVisible((current) => current + step),
    reset: () => setVisible(step),
  }
}
