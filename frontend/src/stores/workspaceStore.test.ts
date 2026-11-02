import { renderHook } from '@testing-library/react'
import { expect, it } from 'vitest'
import { useClearPaneOnLeave, useWorkspaceStore } from './workspaceStore'

// PR #72 4차 조사 FOURTH-UI-01: A 프로젝트의 작업 상세를 연 채 떠나면 다음 프로젝트에 그 작업 id가 남지 않는다.
it('워크스페이스를 떠나면 열려 있던 창과 대상 id를 비운다', () => {
  const view = renderHook(() => useClearPaneOnLeave())
  useWorkspaceStore.getState().openPane('tasks', '501')
  view.unmount()
  expect(useWorkspaceStore.getState()).toMatchObject({
    pane: null,
    focusId: null,
  })
})
