import { lazy, Suspense, useState } from 'react'
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
import { projectPlacements } from '@/features/projects/placements'
import {
  BuildingArt,
  BuildingDesignPicker,
} from '@/features/projects/BuildingDesignPicker'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import type { BuildingDesign } from '@/stores/workspaceStore'
import { formatAgo } from '@/lib/formatDate'
import { cn } from '@/lib/utils'
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
  const placeProject = useWorkspaceStore((state) => state.placeProject)
  const [creatingAt, setCreatingAt] = useState<number | null>(null)
  const [design, setDesign] = useState<BuildingDesign>('modern')
  const [search, setSearch] = useState('')
  const [sort, setSort] = useState<'recent' | 'name' | 'leader'>('recent')
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
  const sceneProjects = allProjects.flatMap((project) => {
    const placement = assigned.find((item) => item.id === project.id)
    return placement?.lot !== null && placement?.lot !== undefined
      ? [
          {
            id: project.id,
            name: project.name,
            lot: placement.lot,
            design: placement.design,
            status: project.current_baseline
              ? ('ready' as const)
              : ('constructing' as const),
          },
        ]
      : []
  })
  const nextLot = Array.from({ length: 8 }, (_, index) => index).find(
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
    <main className="mx-auto max-w-[1376px] px-4 pt-6 pb-10 sm:px-8">
      <AsyncState query={projects}>
        {() => (
          <>
            <section
              className="relative h-[54dvh] min-h-96 overflow-hidden rounded-3xl border bg-card shadow-sm"
              aria-label="내 프로젝트 도시"
            >
              <div className="pointer-events-none absolute top-6 left-6 z-10">
                <h2 className="text-[26px] leading-[34px] font-bold">
                  {session.data?.name}님의 도시
                </h2>
                <p className="mt-1 text-sm text-muted-foreground">
                  부지 8칸 중{' '}
                  {assigned.filter((item) => item.lot !== null).length}칸 사용 ·
                  빈 부지를 누르면 프로젝트를 만들 수 있어요.
                </p>
              </div>
              <div className="absolute top-6 right-6 z-10">
                <Button
                  className="h-10 rounded-xl px-4 font-bold"
                  disabled={nextLot === undefined}
                  onClick={() => startCreate(nextLot ?? null)}
                >
                  <Plus />
                  프로젝트 만들기
                </Button>
              </div>
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
                <CityScene
                  projects={sceneProjects}
                  onProjectSelect={(id) => navigate(`/projects/${id}`)}
                  onLotSelect={startCreate}
                  className="h-full w-full"
                />
              </Suspense>
              <p className="pointer-events-none absolute bottom-5 left-6 text-xs text-muted-foreground">
                부지를 눌러 선택 · 휠로 확대/축소
              </p>
            </section>
            <section className="mt-7">
              <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
                <h3 className="flex items-center gap-2 text-lg font-bold">
                  내 프로젝트
                  <span className="text-[15px] font-semibold text-muted-foreground">
                    {allProjects.length}
                  </span>
                </h3>
                <div className="flex items-center gap-2">
                  <div className="relative">
                    <Search className="pointer-events-none absolute top-2 left-2.5 size-4 text-muted-foreground" />
                    <Input
                      type="search"
                      aria-label="프로젝트 검색"
                      placeholder="프로젝트 이름 검색"
                      className="w-[260px] pl-8"
                      value={search}
                      onChange={(event) => setSearch(event.target.value)}
                    />
                  </div>
                  <NativeSelect
                    aria-label="정렬"
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
                      내가 팀장인 프로젝트 먼저
                    </NativeSelectOption>
                  </NativeSelect>
                </div>
              </div>
              <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
                {shownProjects.map((project) => (
                  <Link
                    className="flex flex-col gap-3.5 rounded-[18px] border bg-card p-4 transition hover:-translate-y-1 hover:shadow-md"
                    key={project.id}
                    to={`/projects/${project.id}`}
                  >
                    {/* SCR-04-v2-city 카드. 왼쪽 위 건물 그림으로 목록과 3D 건물을 짝짓는다. 검토할 변경·진행 중 작업·승인 문서 수,
                        문서 초안 진행, 팀 얼굴·인원·에이전트 수, 마지막 활동은 목록 API에 없어(PRJ-A02, 나의 도시 백엔드 메모 7) 상태 줄만 둔다. */}
                    <span className="flex items-start gap-3">
                      <span className="flex size-14 shrink-0 items-end justify-center overflow-hidden rounded-[14px] bg-[#f4f2ec]">
                        <BuildingArt
                          design={
                            assigned.find((item) => item.id === project.id)
                              ?.design ?? 'modern'
                          }
                          className="h-12"
                        />
                      </span>
                      <span className="grid min-w-0 flex-1 gap-0.5">
                        <strong className="truncate text-base font-bold">
                          {project.name}
                        </strong>
                        <span className="line-clamp-2 text-[13px] leading-[19px] break-keep text-[#57534e]">
                          {project.description ||
                            '비비와 프로젝트를 준비해 보세요.'}
                        </span>
                      </span>
                      <span
                        className={cn(
                          'inline-flex h-[22px] shrink-0 items-center rounded-full px-2 text-xs font-semibold whitespace-nowrap',
                          project.my_role === 'LEADER'
                            ? 'bg-foreground text-white'
                            : 'bg-[#f0eee8] text-[#57534e]',
                        )}
                      >
                        {project.my_role === 'LEADER' ? '팀장' : '팀원'}
                      </span>
                    </span>
                    <span className="flex items-center justify-between gap-2 rounded-xl bg-[#fbfaf6] px-3 py-2.5 text-[13px] leading-[18px] font-semibold">
                      <span className="inline-flex items-center gap-1.5">
                        <span
                          aria-hidden="true"
                          className={cn(
                            'size-[7px] rounded-full',
                            project.current_baseline
                              ? 'bg-[#22a35a]'
                              : 'bg-[#e0b400]',
                          )}
                        />
                        {project.current_baseline
                          ? `진행 중 · 기준 v${project.current_baseline.version_no}`
                          : '시작 준비 중'}
                      </span>
                      <span className="text-xs leading-4 font-medium text-muted-foreground">
                        {formatAgo(project.created_at)} 만듦
                      </span>
                    </span>
                  </Link>
                ))}
                <button
                  type="button"
                  className="grid min-h-[132px] place-content-center justify-items-center gap-1.5 rounded-[18px] border border-dashed border-[#d4d0c6] text-[#57534e] hover:bg-muted/50"
                  disabled={nextLot === undefined}
                  onClick={() => startCreate(nextLot ?? null)}
                >
                  <Plus className="size-6" />
                  <span className="text-sm font-semibold">
                    {nextLot === undefined
                      ? '모든 부지를 사용 중이에요'
                      : '프로젝트 만들기'}
                  </span>
                  {nextLot !== undefined && (
                    <span className="text-xs leading-4 text-muted-foreground">
                      빈 부지{' '}
                      {8 - assigned.filter((item) => item.lot !== null).length}
                      칸 남음
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
                placeProject(session.data!.id, project.id, {
                  lot: creatingAt,
                  design,
                })
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
