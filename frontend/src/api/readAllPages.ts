import type { Page } from '@/api/contracts/common'

// 목록을 마지막 페이지까지 읽어 한 페이지처럼 돌려준다. 사람이 한 번에 고르는 짧은 목록(문서 버전·기준 이력)에 쓴다.
export async function readAllPages<T>(
  read: (cursor?: string) => Promise<Page<T>>,
): Promise<Page<T>> {
  const items: T[] = []
  let cursor: string | undefined
  do {
    const page = await read(cursor)
    items.push(...page.items)
    cursor = page.has_more ? page.next_cursor : undefined
  } while (cursor)
  return { items, has_more: false }
}
