import { expect, it } from 'vitest'
import { cn } from './utils'

it('글자 크기 이름(text-sub 등)과 색 클래스를 같이 쓰면 둘 다 남는다', () => {
  expect(cn('text-sub', 'text-muted-foreground')).toBe(
    'text-sub text-muted-foreground',
  )
  expect(cn('text-caption text-primary-foreground')).toBe(
    'text-caption text-primary-foreground',
  )
  expect(cn('text-kbd', 'text-foreground')).toBe('text-kbd text-foreground')
})

it('글자 크기끼리는 뒤에 쓴 것만 남는다', () => {
  expect(cn('text-xs', 'text-sub')).toBe('text-sub')
  expect(cn('text-body', 'text-sm')).toBe('text-sm')
})
