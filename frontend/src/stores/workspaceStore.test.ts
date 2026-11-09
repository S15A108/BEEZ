import { act, renderHook } from '@testing-library/react'
import { expect, it, vi } from 'vitest'
import {
  useClearPaneOnLeave,
  useOfficeTime,
  useWorkspaceStore,
} from './workspaceStore'

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

// D39: 고정한 동안 시간이 지났어도 자동으로 바꾸면 1분을 기다리지 않고 바로 지금 시각의 빛이 된다. 그 뒤로는 1분마다 다시 본다.
it('사무실 시간대를 자동으로 바꾸면 바로 지금 시각을 따르고 시간이 지나면 바뀐다', () => {
  vi.useFakeTimers({ now: new Date('2026-11-05T07:59:30Z') }) // 한국 시각 16:59:30, 낮
  useWorkspaceStore.getState().setOfficeTime('night')
  const { result } = renderHook(() => useOfficeTime())
  expect(result.current).toBe('night')
  act(() => {
    vi.advanceTimersByTime(2 * 60_000) // 17:01:30, 노을
  })
  expect(result.current).toBe('night')
  act(() => useWorkspaceStore.getState().setOfficeTime('auto'))
  expect(result.current).toBe('sunset')
  act(() => {
    vi.advanceTimersByTime(2 * 60 * 60_000) // 19:01:30, 밤
  })
  expect(result.current).toBe('night')
  vi.useRealTimers()
})
