import { expect, it } from 'vitest'
import { checkDesignContent, parseDesignContent } from './parseDesignContent'

it('description이 있는 JSON 객체는 통과한다', () => {
  expect(checkDesignContent('{"description":"로그인"}')).toBeUndefined()
  expect(parseDesignContent('{"description":"로그인"}')).toEqual({
    description: '로그인',
  })
})

it('JSON이 아닌 글은 JSON 형식 안내를 돌려준다', () => {
  expect(checkDesignContent('로그인할 수 있어야 한다')).toMatch(/JSON 형식/)
})

it('객체가 아닌 JSON은 중괄호 안내를 돌려준다', () => {
  expect(checkDesignContent('[]')).toMatch(/JSON 객체/)
  expect(checkDesignContent('"글"')).toMatch(/JSON 객체/)
  expect(checkDesignContent('null')).toMatch(/JSON 객체/)
})

it('description이 없거나 비어 있으면 description 안내를 돌려준다', () => {
  expect(checkDesignContent('{}')).toMatch(/description/)
  expect(checkDesignContent('{"description":"  "}')).toMatch(/description/)
  expect(checkDesignContent('{"description":3}')).toMatch(/description/)
})
