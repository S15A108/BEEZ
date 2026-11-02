import { expect, it } from 'vitest'
import { characterOf, characters } from './characters'

it('저장된 캐릭터 키는 그대로 쓰고, 옛 bee-* 값과 표시 색은 가장 가까운 캐릭터로 바꾼다', () => {
  expect(characterOf('bee-purple').label).toBe('사람')
  expect(characterOf('human', '#3f9a5c').key).toBe('human')
  expect(characterOf(undefined, '#e08040').key).toBe('bee')
})

it('색도 없으면 같은 id는 늘 같은 프리셋을 받는다', () => {
  const first = characterOf(undefined, undefined, '12')
  expect(characterOf(undefined, 'not-a-color', '12')).toBe(first)
  expect(characters).toContain(first)
})
