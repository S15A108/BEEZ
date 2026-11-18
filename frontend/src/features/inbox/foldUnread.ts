import type { Notification } from '@/api/contracts/common'

/** 알림이 가리키는 대상. 할 일 줄도 같은 모양('MEETING:901')으로 자기 대상을 적는다. */
export const targetKey = (
  item: Pick<Notification, 'target_type' | 'target_id'>,
) => `${item.target_type}:${item.target_id}`

// 사무실 '할 일'(S15P31A108-444): 안 읽은 알림 가운데 할 일 줄과 대상이 같은 것은 그 줄에 접고, 나머지는 '새 소식'으로 보낸다.
// 알림 하나는 한 곳에만 보인다. 유형으로 나누지 않으므로 새 유형이 생겨도 어디에도 안 보이는 알림이 없다.
export function foldUnread(
  unread: Notification[],
  rows: { key: string; targets: string[] }[],
) {
  const byRow = new Map<string, Notification[]>()
  const folded = new Set<string>()
  for (const row of rows) {
    const mine = unread.filter(
      (item) => !folded.has(item.id) && row.targets.includes(targetKey(item)),
    )
    byRow.set(row.key, mine)
    for (const item of mine) folded.add(item.id)
  }
  return { byRow, news: unread.filter((item) => !folded.has(item.id)) }
}
