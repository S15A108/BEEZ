import { lazy, Suspense, useId, useRef, useState } from 'react'
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
  const titleRef = useRef<HTMLDivElement>(null)
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
  return (
    // 아래쪽 여백: 마지막 줄 카드의 호버 카드가 카드 아래로 내려와도 문서 높이가 그대로라 스크롤바가 생겼다 사라지지 않는다.
    <main className="mx-auto max-w-[1376px] px-4 pt-6 pb-48 sm:px-8">
      <AsyncState query={projects}>
        {() => (
          <>
            <div className="relative">
              {/* 제목·만들기 버튼: 넓은 화면(lg)에서는 캔버스처럼 도시 위에 겹치고, 좁은 화면에서는 줄이 늘어 건물과
                겹치므로 도시 바깥 위에 둔다. */}
              <div
                ref={titleRef}
                className="mb-4 flex flex-wrap items-start justify-between gap-3 lg:pointer-events-none lg:absolute lg:inset-x-6 lg:top-6 lg:z-10 lg:mb-0"
              >
                <h2 className="min-w-0 text-[26px] leading-[34px] font-bold wrap-anywhere">
                  {session.data?.name}님의 프로젝트
                </h2>
                <Button
                  className="h-10 rounded-xl px-4 font-bold lg:pointer-events-auto"
                  disabled={nextLot === undefined}
                  onClick={() => startCreate(nextLot ?? null)}
                >
                  <Plus />
                  프로젝트 만들기
                </Button>
              </div>
              <section
                // 높이는 화면의 54%(최소 384px)지만 폭의 80%를 넘지 않는다. 좁은 세로 화면에서 키가 크면 고리 전체가 보여 원으로
                // 읽히므로, 넓은 화면과 같은 가로세로 비율로 고리 앞쪽 호만 담는다(D33).
                className="relative h-[min(max(54dvh,24rem),80vw)] overflow-hidden rounded-3xl border bg-card shadow-sm"
                aria-label="내 프로젝트 도시"
              >
                {/* 거리 뒤가 위 끝 너머로 이어지는 느낌을 주는 흐린 띠(캔버스 SCR-04-v2-city). 제목이 겹치는 넓은 화면에서만 둔다. */}
                <div
                  aria-hidden="true"
                  className="pointer-events-none absolute inset-x-0 top-0 z-[1] hidden h-[120px] bg-linear-to-b from-[rgba(253,252,248,0.96)] to-transparent lg:block"
                />
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
                      sceneProjects.find(
                        (project) => project.id === lastVisited,
                      )?.lot
                    }
                    backdrop={creatingAt !== null}
                    titleRef={titleRef}
                    onProjectSelect={(id) => navigate(`/projects/${id}`)}
                    onLotSelect={startCreate}
                    onProjectHover={(id) =>
                      setHover(id ? { id, from: 'map' } : null)
                    }
                    describedBy={(id) =>
                      mapHover?.id === id ? hoverCardId : undefined
                    }
                    className={
                      creatingAt === null
                        ? 'h-full w-full'
                        : 'fixed inset-0 z-40'
                    }
                  />
                </Suspense>
                {/* 캔버스 SCR-04-v2-city(v495)처럼 바탕 없이 왼쪽 아래 24·16px에 둔다. */}
                <p className="pointer-events-none absolute right-6 bottom-4 left-6 text-xs break-keep text-muted-foreground">
                  끌거나 휠로 고리 돌리기 · 빈 부지 다음은 꾸밈 칸을 건너
                  1번으로 · 부지를 눌러 선택
                </p>
                {/* 건물을 가리지 않게 제목 아래 고정 자리에 두고, 포인터는 그대로 뒤의 건물로 통과시킨다. */}
                {mapHover && (
                  <ProjectHoverCard
                    id={hoverCardId}
                    project={mapHover}
                    design={designOf(mapHover.id)}
                    unread={unreadOf(mapHover.id)}
                    className="pointer-events-none absolute top-3 left-3 z-30 w-[272px] lg:top-[68px] lg:left-6"
                  />
                )}
              </section>
            </div>
            <section className="mt-7">
              <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
                <h3 className="flex items-center gap-2 text-lg font-bold">
                  내 프로젝트
                  <span className="text-[15px] font-semibold text-muted-foreground">
                    {allProjects.length}
                  </span>
                </h3>
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
                </div>
              </div>
              <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
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
                        unread
                          ? `${project.name}, ${unread.label}`
                          : project.name
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
                <button
                  type="button"
                  className="grid min-h-[104px] place-content-center justify-items-center gap-1 rounded-[18px] border-[1.5px] border-dashed border-[#e0b400] bg-[#fdf6da] p-3 text-[#5c4700] hover:bg-[#fbeec2] disabled:opacity-60"
                  disabled={nextLot === undefined}
                  onClick={() => startCreate(nextLot ?? null)}
                >
                  <span
                    aria-hidden="true"
                    className="grid size-6 place-items-center rounded-full bg-primary text-[#322710]"
                  >
                    <Plus className="size-4" strokeWidth={2.5} />
                  </span>
                  <span className="text-sm font-bold text-[#322710]">
                    {nextLot === undefined
                      ? '모든 부지를 사용 중이에요'
                      : '프로젝트 만들기'}
                  </span>
                  {nextLot !== undefined && (
                    <span className="text-xs leading-4">
                      빈 부지 {nextLot + 1}번 · 만들면 고리의 다음 칸이 빈
                      부지가 돼요
                    </span>
                  )}
                </button>
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
            </section>
          </>
        )}
      </AsyncState>
      {creatingAt !== null && (
        <Dialog
          open
          onOpenChange={(open) => {
            if (!open && !create.isPending) setCreatingAt(null)
          }}
        >
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
                  명세서)을 올리고 팀장이 모두 승인하면 '프로젝트 시작'으로
                  시작해요.
                </p>
              }
              submitLabel="프로젝트 만들기"
              onCancel={() => setCreatingAt(null)}
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
