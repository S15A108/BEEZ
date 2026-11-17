import { afterEach, expect, it, vi } from 'vitest'
import { rememberedScrollTop, restoreScrollWhenReady } from './restoreScroll'

// jsdom에는 배치가 없어 본문 높이와 ResizeObserver를 흉내 낸다. scrollTop은 브라우저처럼 갈 수 있는 끝에서 잘린다.
function fakeBody(height: number) {
  const body = document.createElement('div')
  body.append(document.createElement('div'))
  let top = 0
  Object.defineProperties(body, {
    clientHeight: { get: () => 800 },
    scrollHeight: { get: () => height },
    scrollTop: {
      get: () => top,
      set: (value: number) => {
        top = Math.max(0, Math.min(value, height - 800))
      },
    },
  })
  return {
    body,
    grow: (next: number) => {
      height = next
    },
  }
}

let resized: (() => void) | undefined
vi.stubGlobal(
  'ResizeObserver',
  class {
    constructor(callback: () => void) {
      resized = callback
    }
    observe() {}
    disconnect() {
      resized = undefined
    }
  },
)
afterEach(() => {
  resized = undefined
})

// PR #321 리뷰: 긴 문서를 500px 내려 읽고 닫은 뒤, 문서를 다시 받는 동안 열면 로딩 안내만 있어 0으로 돌아갔다.
it('본문을 늦게 받아도 길어진 뒤 저장한 위치로 돌아간다', () => {
  const { body, grow } = fakeBody(820)
  restoreScrollWhenReady(body, 500)
  expect(body.scrollTop).toBe(0)
  grow(5798)
  resized?.()
  expect(body.scrollTop).toBe(500)
  expect(resized).toBeUndefined()
})

it('기다리는 동안 먼저 스크롤했으면 그 자리를 둔다', () => {
  const { body, grow } = fakeBody(1000)
  restoreScrollWhenReady(body, 500)
  body.scrollTop = 120
  grow(5798)
  resized?.()
  expect(body.scrollTop).toBe(120)
})

it('본문이 이미 길면 바로 적용한다', () => {
  const { body } = fakeBody(5798)
  restoreScrollWhenReady(body, 500)
  expect(body.scrollTop).toBe(500)
})

// PR #321 리뷰: 문서 A를 내려 읽다 닫고 알림으로 문서 B를 열면 B가 A의 위치로 내려가 열렸다.
it('대상을 정해 열면 옛 스크롤 대신 맨 위에서 연다', () => {
  expect(rememberedScrollTop(2000, null)).toBe(2000)
  expect(rememberedScrollTop(2000, '202')).toBe(0)
  expect(rememberedScrollTop(undefined, null)).toBe(0)
  const { body, grow } = fakeBody(820)
  restoreScrollWhenReady(body, rememberedScrollTop(2000, '202'))
  grow(5798)
  resized?.()
  expect(body.scrollTop).toBe(0)
})
