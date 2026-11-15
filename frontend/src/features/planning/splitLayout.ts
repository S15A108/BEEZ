import { cn } from '@/lib/utils'

// 기획 분석 패널 높이. 업무 창(100dvh-160px)에서 창 머리(120px)와 아래 여백(32px)을 뺀 만큼이다.
export const PANEL_HEIGHT = 'h-[calc(100dvh-19.5rem)]'
// 탭 안의 목록·상세는 위쪽 안내 줄을 뺀 남은 높이를 채우고 두 칸이 각자 스크롤한다.
// 안내가 많아도 목록이 몇 줄은 보이게 최소 높이를 둔다(넘치면 창이 스크롤한다).
export const SPLIT_HEIGHT = 'min-h-[22rem] flex-1'
// 탭 내용의 바깥 틀: 안내 줄들 + 목록·상세
export const TAB_BODY = 'flex min-h-0 flex-1 flex-col gap-3'

// 왼쪽 목록의 한 줄. 지금 오른쪽에 펼친 줄은 꿀색으로 칠한다.
export const listRowClass = (active: boolean) =>
  cn(
    'flex w-full gap-3 border-b px-4 py-3 text-left transition-colors focus-visible:bg-muted focus-visible:outline-none',
    active ? 'bg-primary/15' : 'hover:bg-muted/60',
  )

// 오른쪽 상세의 아래 버튼 줄. 상세가 길어도 스크롤 끝에 붙어 있다.
export const DETAIL_FOOTER =
  'sticky bottom-0 mt-auto flex flex-wrap items-center gap-2 border-t bg-card px-8 py-3'
