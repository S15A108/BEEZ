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

// 나의 도시는 마지막으로 연 프로젝트 건물을 가운데에 두고 시작한다(D33). 계정마다 따로 남긴다.
it('마지막으로 연 프로젝트를 계정마다 따로 기억하고, 새로 열면 바꾼다', () => {
  const { visitProject } = useWorkspaceStore.getState()
  visitProject('11', '101')
  visitProject('12', '201')
  visitProject('11', '102')
  expect(useWorkspaceStore.getState().lastVisited).toMatchObject({
    '11': '102',
    '12': '201',
  })
  // 브라우저 설정으로 저장한다(부지 배치와 같은 저장소)
  expect(localStorage.getItem('beez.scene.preferences.v1')).toContain(
    '"lastVisited":{"11":"102","12":"201"',
  )
})
