import { cn } from '@/lib/utils'

// 기획 분석 패널 높이. 화면 전체 업무 창(100dvh)에서 창 머리(65px)와 본문 위아래 여백(32px씩)을 뺀 만큼이라 창을 꼭 채운다
// (S15P31A108-431. 예전 가운데 창은 100dvh-19.5rem이었다).
export const PANEL_HEIGHT = 'h-[calc(100dvh-129px)]'
// 발행 뒤 팀장 화면은 탭 위에 작업 만들기(TaskBreakdown)가 들어온다. 그때는 패널 높이를 고정하지 않고
// 탭 영역에만 높이를 준다. 목록·상세가 찌그러지지 않고 넘치는 만큼은 창이 스크롤한다(PR #303 리뷰).
// 창 머리·여백(129px)에 작업 만들기 칸·문서 줄·탭 줄 몫(258px)을 더 뺀다. 1440×900·1024×768에서 창 스크롤 없이 맞는다(예전 100dvh-35rem).
export const TAB_AREA_WITH_BREAKDOWN = 'h-[max(22rem,calc(100dvh-387px))]'
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
