import { lazy, Suspense, useId, useState } from 'react'
import { Link, useNavigate } from 'react-router'
import {
  useInfiniteQuery,
  useMutation,
  useQueryClient,
} from '@tanstack/react-query'
import { Plus, Search } from 'lucide-react'
import { projectsApi } from '@/api/services'
import { RequireAccount } from '@/features/auth/RequireAccount'
import { useSession } from '@/features/auth/useSession'
import { useUnreadByProject } from '@/features/inbox/useUnreadByProject'
import { projectPlacements } from '@/features/projects/placements'
import {
  BuildingArt,
  BuildingDesignPicker,
} from '@/features/projects/BuildingDesignPicker'
import { ProjectHoverCard } from '@/features/projects/ProjectHoverCard'
import { NO_DESCRIPTION } from '@/features/projects/projectHoverText'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import type { BuildingDesign } from '@/stores/workspaceStore'
import { UNREAD_BADGE } from '@/lib/display'
import { cn } from '@/lib/utils'
import { RING_SLOTS } from '@/three/city/ring'
import { AsyncState } from '@/components/AsyncState'
import { ActionForm } from '@/components/ActionForm'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { NativeSelect, NativeSelectOption } from '@/components/ui/native-select'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'

const CityScene = lazy(() => import('@/three/CityScene'))

function City() {
  const session = useSession()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const create = useMutation({
    mutationFn: (input: Parameters<typeof projectsApi.create>[0]) =>
      projectsApi.create(input),
  })
  const placements = useWorkspaceStore((state) => state.placements)
  const lastVisited = useWorkspaceStore(
    (state) => state.lastVisited[session.data?.id ?? ''],
  )
  const placeProjects = useWorkspaceStore((state) => state.placeProjects)
  const [creatingAt, setCreatingAt] = useState<number | null>(null)
  const [design, setDesign] = useState<BuildingDesign>('modern')
  const [search, setSearch] = useState('')
  const [sort, setSort] = useState<'recent' | 'name' | 'leader'>('recent')
  // 호버 카드는 한 번에 하나다. 지도에서 올리면 지도 왼쪽 위, 카드에 올리면 그 카드 위에 뜬다.
  const [hover, setHover] = useState<{
    id: string
    from: 'map' | 'card'
  } | null>(null)
  const hoverCardId = useId()
  const unreadOf = useUnreadByProject()
  const projects = useInfiniteQuery({
    queryKey: ['projects'],
    queryFn: ({ pageParam }) => projectsApi.list({ cursor: pageParam }),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => (last.has_more ? last.next_cursor : undefined),
  })
  const allProjects = projects.data?.pages.flatMap((page) => page.items) ?? []
  const assigned = projectPlacements(
    allProjects.map((project) => project.id),
    session.data?.id ?? '',
    placements,
  )
  const designOf = (id: string) =>
    assigned.find((item) => item.id === id)?.design ?? 'modern'
  const sceneProjects = allProjects.flatMap((project) => {
    const placement = assigned.find((item) => item.id === project.id)
    return placement?.lot !== null && placement?.lot !== undefined
      ? [
          {
            id: project.id,
            name: project.name,
            lot: placement.lot,
            design: placement.design,
            unread: unreadOf(project.id),
          },
        ]
      : []
  })
  const mapHover =
    hover?.from === 'map' && creatingAt === null
      ? allProjects.find((project) => project.id === hover.id)
      : undefined
  // 원형 도시의 빈 부지는 늘 하나다. 고리를 따라 가장 앞의 빈 칸에 생긴다.
  const nextLot = Array.from({ length: RING_SLOTS }, (_, index) => index).find(
    (lot) => !assigned.some((item) => item.lot === lot),
  )
  // ponytail: 불러온 페이지 안에서만 검색·정렬한다. 서버 검색·정렬 파라미터가 생기면 쿼리로 옮긴다.
  const shownProjects = allProjects
    .filter((project) =>
      project.name.toLowerCase().includes(search.trim().toLowerCase()),
    )
    .sort((a, b) =>
      sort === 'name'
        ? a.name.localeCompare(b.name, 'ko')
        : sort === 'leader' && a.my_role !== b.my_role
          ? a.my_role === 'LEADER'
            ? -1
            : 1
          : b.created_at.localeCompare(a.created_at),
    )
  const startCreate = (lot: number | null) => {
    setDesign('modern')
    setCreatingAt(lot)
  }
  // 이름·소개를 쓰던 중이면 Esc·바깥 클릭·닫기·취소로 바로 닫지 않고 한 번 묻는다(S15P31A108-371).
  const [dirty, setDirty] = useState(false)
  const [confirmClose, setConfirmClose] = useState(false)
  const closeCreate = () => {
    if (create.isPending) return
    if (dirty) setConfirmClose(true)
    else setCreatingAt(null)
  }
  return (
    // 아래쪽 여백: 마지막 줄 카드의 호버 카드가 카드 아래로 내려와도 문서 높이가 그대로라 스크롤바가 생겼다 사라지지 않는다.
    <main className="mx-auto max-w-[1376px] px-4 pt-6 pb-48 sm:px-8">
      <AsyncState query={projects}>
        {() => (
          <>
            {/* 제목 줄 하나에 검색·정렬·만들기를 둔다. 도시 위에 겹치지 않아 3D를 가리지 않는다(화면 검수 2026-11-16, S15P31A108-446). */}
            <div className="mb-4 flex flex-wrap items-center gap-3">
              <h2 className="mr-auto flex min-w-0 items-baseline gap-2 text-[26px] leading-[34px] font-bold wrap-anywhere">
                {session.data?.name}님의 프로젝트
                <span className="text-[15px] font-semibold text-muted-foreground">
                  {allProjects.length}
                </span>
              </h2>
              <div className="flex flex-wrap items-center gap-2">
                <div className="relative max-w-full">
                  <Search className="pointer-events-none absolute top-2 left-2.5 size-4 text-muted-foreground" />
                  <Input
                    type="search"
                    aria-label="프로젝트 검색"
                    placeholder="프로젝트 이름 검색"
                    className="w-[220px] max-w-full pl-8 text-[12.5px] md:text-[12.5px]"
                    value={search}
                    onChange={(event) => setSearch(event.target.value)}
                  />
                </div>
                <NativeSelect
                  aria-label="정렬"
                  className="[&_select]:text-[12.5px]"
                  value={sort}
                  onChange={(event) =>
                    setSort(event.target.value as typeof sort)
                  }
                >
                  <NativeSelectOption value="recent">
                    최근 만든 순
                  </NativeSelectOption>
                  <NativeSelectOption value="name">이름순</NativeSelectOption>
                  <NativeSelectOption value="leader">
                    팀장 먼저
                  </NativeSelectOption>
                </NativeSelect>
                <Button
                  className="h-9 rounded-xl px-4 font-bold"
                  disabled={nextLot === undefined}
                  onClick={() => startCreate(nextLot ?? null)}
                >
                  <Plus />
                  {nextLot === undefined
                    ? '모든 부지를 사용 중이에요'
                    : '프로젝트 만들기'}
                </Button>
              </div>
            </div>
            <section
              // 높이는 화면에서 헤더·제목 줄·안내·카드 한 줄·아래 여백(31rem)을 뺀 나머지라, 프로젝트가 한 줄이면 1440×900에서
              // 스크롤 없이 목록까지 들어온다(S15P31A108-446, 사용자 결정 '안 2'). 최소 320px이고 폭의 80%를 넘지 않는다. 좁은 세로
              // 화면에서 키가 크면 고리 전체가 보여 원으로 읽히므로, 넓은 화면과 같은 가로세로 비율로 고리 앞쪽 호만 담는다(D33).
              className="relative h-[min(max(20rem,100dvh_-_31rem),80vw)] overflow-hidden rounded-3xl border bg-card shadow-sm"
              aria-label="내 프로젝트 도시"
            >
              <Suspense
                fallback={
                  <p
                    role="status"
                    className="grid h-full place-items-center text-sm text-muted-foreground"
                  >
                    도시를 준비하는 중…
                  </p>
                }
              >
                {/* 프로젝트 만들기 창이 열리면 도시가 화면 전체로 깔리고 창의 흐린 막 뒤에 보인다(SCR-04-v2-create-project). */}
                <CityScene
                  projects={sceneProjects}
                  nextLot={nextLot ?? null}
                  // 마지막으로 연 프로젝트(이 계정·이 브라우저)가 도시에 있으면 그 건물을 가운데에 둔다. 지웠거나 나간 프로젝트는
                  // 목록에 없어 처음 화면 규칙을 쓴다(D33).
                  visitedLot={
                    sceneProjects.find((project) => project.id === lastVisited)
                      ?.lot
                  }
                  backdrop={creatingAt !== null}
                  onProjectSelect={(id) => navigate(`/projects/${id}`)}
                  onLotSelect={startCreate}
                  onProjectHover={(id) =>
                    setHover(id ? { id, from: 'map' } : null)
                  }
                  describedBy={(id) =>
                    mapHover?.id === id ? hoverCardId : undefined
                  }
                  className={
                    creatingAt === null ? 'h-full w-full' : 'fixed inset-0 z-40'
                  }
                />
              </Suspense>
              {/* 건물을 가리지 않게 도시 왼쪽 위 고정 자리에 두고, 포인터는 그대로 뒤의 건물로 통과시킨다. */}
              {mapHover && (
                <ProjectHoverCard
                  id={hoverCardId}
                  project={mapHover}
                  design={designOf(mapHover.id)}
                  unread={unreadOf(mapHover.id)}
                  className="pointer-events-none absolute top-3 left-3 z-30 w-[272px]"
                />
              )}
            </section>
            {/* 조작 안내는 도시 밖 아래 한 줄. 도시 위에 두면 나무·건물에 가렸다(S15P31A108-446). */}
            <p className="mt-2 text-xs break-keep text-muted-foreground">
              끌거나 휠로 고리 돌리기 · 새 프로젝트 다음은 다시 1번 · 부지를
              눌러 선택
            </p>
            <div className="mt-6 grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
              {shownProjects.map((project) => {
                const open = hover?.from === 'card' && hover.id === project.id
                const unread = unreadOf(project.id)
                return (
                  // 카드는 건물 그림·이름·설명만. 역할·상태·숫자는 호버 카드가 카드 위에 겹쳐 펼쳐 보인다(터치에서는 배지만).
                  <Link
                    className={cn(
                      'relative flex min-h-[104px] min-w-0 items-start gap-3 rounded-[18px] border bg-card p-4',
                      // 닫힌 카드도 z를 둬 제 배지를 카드 안에 가둔다. 없으면 옆 카드의 호버 카드 위로 배지가 비친다.
                      open ? 'z-20' : 'z-[1]',
                    )}
                    key={project.id}
                    to={`/projects/${project.id}`}
                    aria-label={
                      unread ? `${project.name}, ${unread.label}` : project.name
                    }
                    aria-describedby={open ? hoverCardId : undefined}
                    onPointerEnter={(event) => {
                      if (event.pointerType !== 'touch')
                        setHover({ id: project.id, from: 'card' })
                    }}
                    onPointerLeave={() => setHover(null)}
                    onFocus={(event) => {
                      if (event.currentTarget.matches(':focus-visible'))
                        setHover({ id: project.id, from: 'card' })
                    }}
                    onBlur={() => setHover(null)}
                  >
                    <span className="flex size-14 shrink-0 items-end justify-center overflow-hidden rounded-[14px] bg-[#f4f2ec]">
                      <BuildingArt
                        design={designOf(project.id)}
                        className="h-12"
                      />
                    </span>
                    <span className="flex min-w-0 flex-1 flex-col gap-0.5">
                      <strong className="truncate text-base font-bold">
                        {project.name}
                      </strong>
                      <span className="line-clamp-2 text-[13px] leading-[19px] wrap-anywhere break-keep text-[#57534e]">
                        {project.description || NO_DESCRIPTION}
                      </span>
                    </span>
                    {/* 절대 위치의 기준이 링크의 테두리 안쪽이라 테두리 1px만큼 더 뺀다: 호버 카드는 카드 밖 6px, 배지는 9px. */}
                    {open && (
                      <ProjectHoverCard
                        id={hoverCardId}
                        project={project}
                        design={designOf(project.id)}
                        unread={unread}
                        className="absolute -inset-x-[7px] -top-[7px] z-30"
                      />
                    )}
                    {unread && (
                      <span
                        aria-hidden="true"
                        className={cn(
                          UNREAD_BADGE,
                          '-top-2.5 -right-2.5 z-[31] h-6 min-w-6 px-[7px] ring-background',
                        )}
                      >
                        {unread.text}
                      </span>
                    )}
                  </Link>
                )
              })}
              {/* 점선 만들기 카드를 지워 목록이 비면 그 자리가 안내 없이 비었다(PR #318 리뷰). 만들기는 제목 줄 버튼과 빈 부지로 한다. */}
              {shownProjects.length === 0 && (
                <p
                  role="status"
                  className="col-span-full text-sm text-muted-foreground"
                >
                  {allProjects.length === 0
                    ? '아직 프로젝트가 없어요. 프로젝트 만들기로 시작해 보세요.'
                    : `‘${search.trim()}’에 맞는 프로젝트가 없어요.`}
                </p>
              )}
            </div>
            {projects.hasNextPage && (
              <Button
                variant="outline"
                className="mt-4"
                disabled={projects.isFetchingNextPage}
                onClick={() => void projects.fetchNextPage()}
              >
                프로젝트 더 보기
              </Button>
            )}
          </>
        )}
      </AsyncState>
      {creatingAt !== null && (
        <Dialog open onOpenChange={(open) => !open && closeCreate()}>
          <DialogContent className="max-h-[90dvh] gap-5 overflow-y-auto rounded-[22px] p-7 sm:max-w-[760px]">
            <DialogHeader>
              <DialogTitle className="text-xl font-bold">
                프로젝트 만들기
              </DialogTitle>
              {/* 보드는 제목만 보인다. 어느 부지에 짓는지는 화면 읽기 도구에만 알린다. */}
              <DialogDescription className="sr-only">
                부지 {creatingAt + 1}에 지어요. 부지와 건물 디자인은 이
                브라우저에 보관돼요.
              </DialogDescription>
            </DialogHeader>
            {/* API 없음: 건물 디자인은 Project에 design 필드가 생기면 생성 요청에 같이 보낸다. */}
            <BuildingDesignPicker value={design} onChange={setDesign} />
            <hr />
            <ActionForm
              fields={[
                {
                  name: 'name',
                  label: '프로젝트 이름',
                  required: true,
                  max: 40,
                  counter: true,
                },
                {
                  name: 'description',
                  label: '프로젝트 소개',
                  type: 'textarea',
                  max: 300,
                  counter: true,
                  placeholder:
                    '팀원이 이 프로젝트를 한눈에 알 수 있게 한두 문장으로 적어 주세요.',
                },
              ]}
              footer={
                <p className="rounded-[10px] bg-[#fbfaf6] px-3 py-2.5 text-[13px] leading-5 text-[#57534e]">
                  만든 뒤 문서 초안 4종(기획안 · ERD · 요구사항 명세서 · API
                  명세서)을 올리고 팀장이 모두 승인하면 '설계 초안 만들기'로
                  기획 분석에서 설계를 정하고 시작해요.
                </p>
              }
              submitLabel="프로젝트 만들기"
              onCancel={closeCreate}
              onDirtyChange={setDirty}
              onSubmit={async (values) => {
                const project = await create.mutateAsync({
                  name: values.name.trim(),
                  description: values.description.trim(),
                })
                // 지금 보이는 칸(빈 칸을 당긴 번호)을 같이 저장해, 새 칸 번호가 지운 칸 뒤에 남은 저장 번호와 겹치지 않는다.
                placeProjects(session.data!.id, [
                  ...assigned,
                  { id: project.id, lot: creatingAt, design },
                ])
                await queryClient.invalidateQueries({ queryKey: ['projects'] })
                setCreatingAt(null)
                navigate(`/projects/${project.id}`)
              }}
            />
            {confirmClose && (
              <Dialog open onOpenChange={(open) => setConfirmClose(open)}>
                <DialogContent className="sm:max-w-[420px]">
                  <DialogHeader>
                    <DialogTitle>만들던 프로젝트를 닫을까요?</DialogTitle>
                    <DialogDescription>
                      쓴 이름과 소개는 저장되지 않아요.
                    </DialogDescription>
                  </DialogHeader>
                  <DialogFooter>
                    <Button
                      variant="outline"
                      onClick={() => setConfirmClose(false)}
                    >
                      계속 쓰기
                    </Button>
                    <Button
                      variant="destructive"
                      onClick={() => {
                        setConfirmClose(false)
                        setCreatingAt(null)
                      }}
                    >
                      버리고 닫기
                    </Button>
                  </DialogFooter>
                </DialogContent>
              </Dialog>
            )}
          </DialogContent>
        </Dialog>
      )}
    </main>
  )
}

export default function CityPage() {
  return (
    <RequireAccount>
      <City />
    </RequireAccount>
  )
}
