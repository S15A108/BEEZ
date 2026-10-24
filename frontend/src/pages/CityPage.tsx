import { lazy, Suspense, useState } from 'react'
import { Link, useNavigate } from 'react-router'
import {
  useInfiniteQuery,
  useMutation,
  useQueryClient,
} from '@tanstack/react-query'
import { ArrowUpRight, LogOut, Plus, Settings, Building2 } from 'lucide-react'
import { authApi, projectsApi } from '@/api/services'
import { RequireAccount } from '@/features/auth/RequireAccount'
import { useSession } from '@/features/auth/useSession'
import { projectPlacements } from '@/features/projects/placements'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import type { BuildingDesign } from '@/stores/workspaceStore'
import { AsyncState } from '@/components/AsyncState'
import { FormDialog } from '@/components/FormDialog'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { getApiErrorMessage } from '@/api/error'

const CityScene = lazy(() => import('@/three/CityScene'))

function City() {
  const session = useSession()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const logout = useMutation({
    mutationFn: () => authApi.logout(),
    onSuccess: () => {
      queryClient.clear()
      navigate('/login')
    },
  })
  const create = useMutation({
    mutationFn: (input: Parameters<typeof projectsApi.create>[0]) =>
      projectsApi.create(input),
  })
  const placements = useWorkspaceStore((state) => state.placements)
  const placeProject = useWorkspaceStore((state) => state.placeProject)
  const [creatingAt, setCreatingAt] = useState<number | null>(null)
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
  return (
    <main className="mx-auto max-w-[1600px] px-4 py-6 sm:px-8">
      <div className="mb-5 flex flex-wrap items-center justify-between gap-3">
        <div>
          <p className="text-xs tracking-[0.2em] text-muted-foreground">
            MY PROJECT CITY
          </p>
          <h2 className="mt-2 text-2xl font-bold">
            {session.data?.name}님의 도시
          </h2>
        </div>
        <div className="flex gap-2">
          <Button variant="outline" asChild>
            <Link to="/account">
              <Settings />
              회원 설정
            </Link>
          </Button>
          <Button
            variant="ghost"
            disabled={logout.isPending}
            onClick={() => logout.mutate()}
          >
            <LogOut />
            로그아웃
          </Button>
        </div>
      </div>
      {logout.error && (
        <p role="alert" className="mb-4 text-sm text-destructive">
          {getApiErrorMessage(logout.error)}
        </p>
      )}
      <AsyncState query={projects}>
        {() => (
          <>
            <section
              className="relative h-[54dvh] min-h-96 overflow-hidden rounded-3xl border bg-card shadow-sm"
              aria-label="내 프로젝트 도시"
            >
              <div className="pointer-events-none absolute top-6 left-6 z-10 max-w-64">
                <h3 className="text-xl font-semibold">함께 만드는 작은 도시</h3>
                <p className="mt-2 text-xs leading-6 text-muted-foreground">
                  빈 부지를 선택하고, 프로젝트와 함께 넓혀 가세요.
                </p>
              </div>
              <div className="absolute top-6 right-6 z-10">
                <Button
                  disabled={nextLot === undefined}
                  onClick={() => setCreatingAt(nextLot ?? null)}
                >
                  <Plus />
                  건물 짓기
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
                  onLotSelect={setCreatingAt}
                  className="h-full w-full"
                />
              </Suspense>
              <p className="pointer-events-none absolute bottom-5 left-6 text-xs text-muted-foreground">
                부지를 눌러 선택 · 휠로 확대/축소
              </p>
            </section>
            <section className="mt-7">
              <div className="mb-4 flex items-center justify-between">
                <h3 className="font-semibold">
                  내 프로젝트{' '}
                  <span className="ml-2 text-muted-foreground">
                    {allProjects.length}
                  </span>
                </h3>
                <Badge variant="outline">참여 중인 프로젝트</Badge>
              </div>
              <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
                {allProjects.map((project) => (
                  <Link
                    className="group rounded-2xl border bg-card p-5 transition hover:-translate-y-1 hover:shadow-md"
                    key={project.id}
                    to={`/projects/${project.id}`}
                  >
                    <div className="mb-4 flex items-center justify-between">
                      <Building2 className="size-5 text-primary" />
                      <ArrowUpRight className="size-4 text-muted-foreground group-hover:text-foreground" />
                    </div>
                    <h4 className="font-semibold">{project.name}</h4>
                    <p className="mt-2 line-clamp-2 min-h-10 text-xs leading-5 text-muted-foreground">
                      {project.description ||
                        '비비와 프로젝트를 준비해 보세요.'}
                    </p>
                    <div className="mt-5 flex items-center justify-between text-xs">
                      <span>
                        {project.my_role === 'LEADER' ? '인간 팀장' : '팀원'}
                      </span>
                      <Badge variant="secondary">
                        {project.current_baseline
                          ? `기준 v${project.current_baseline.version_no}`
                          : '준비 중'}
                      </Badge>
                    </div>
                  </Link>
                ))}
                <button
                  type="button"
                  className="grid min-h-52 place-content-center gap-3 rounded-2xl border border-dashed text-sm text-muted-foreground hover:bg-muted/50"
                  disabled={nextLot === undefined}
                  onClick={() => setCreatingAt(nextLot ?? null)}
                >
                  <Plus className="mx-auto size-6" />
                  {nextLot === undefined
                    ? '모든 부지를 사용 중이에요'
                    : '새 프로젝트 시작'}
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
        <FormDialog
          title="선택한 부지에 프로젝트 만들기"
          description={`부지 ${creatingAt + 1}에서 팀의 공간을 시작하세요. 부지와 외관은 이 브라우저에 보관됩니다.`}
          submitLabel="건물 짓기"
          onClose={() => setCreatingAt(null)}
          fields={[
            { name: 'name', label: '프로젝트 이름', required: true, max: 40 },
            {
              name: 'description',
              label: '프로젝트 소개',
              type: 'textarea',
              max: 300,
            },
            {
              name: 'design',
              label: '건물 디자인',
              type: 'select',
              value: 'modern',
              options: [
                { value: 'glass', label: '유리 오피스' },
                { value: 'brick', label: '붉은 벽돌' },
                { value: 'modern', label: '모던 화이트' },
                { value: 'tower', label: '다크 타워' },
                { value: 'wood', label: '목조 스튜디오' },
              ],
            },
          ]}
          onSubmit={async (values) => {
            const project = await create.mutateAsync({
              name: values.name.trim(),
              description: values.description.trim(),
            })
            placeProject(session.data!.id, project.id, {
              lot: creatingAt,
              design: values.design as BuildingDesign,
            })
            await queryClient.invalidateQueries({ queryKey: ['projects'] })
            setCreatingAt(null)
            navigate(`/projects/${project.id}`)
          }}
        />
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
