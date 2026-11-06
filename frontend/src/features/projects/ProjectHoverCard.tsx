import { useQuery } from '@tanstack/react-query'
import { dashboardApi, projectsApi } from '@/api/services'
import type { Project } from '@/api/contracts/common'
import type { UnreadCount } from '@/features/inbox/useUnreadByProject'
import { membersQuery } from '@/features/members/membersQuery'
import { countText } from '@/lib/display'
import { formatAgo } from '@/lib/formatDate'
import { cn } from '@/lib/utils'
import type { BuildingDesign } from '@/stores/workspaceStore'
import { BuildingArt } from './BuildingDesignPicker'
import { NO_DESCRIPTION, nextStepText, peopleText } from './projectHoverText'

// 카드를 옮겨 다닐 때마다 다시 부르지 않게 잠깐 캐시를 그대로 쓴다.
const staleTime = 30_000

/**
 * 나의 도시의 호버 카드. 목록 API(PRJ-A02)에 없는 값을 올린 프로젝트 하나만 기존 API로 불러온다. 키는 사무실 HUD와 같아
 * 도시와 사무실의 숫자가 어긋나지 않는다.
 */
export function ProjectHoverCard({
  project,
  design,
  unread,
  id,
  className,
}: {
  project: Project
  design: BuildingDesign
  unread?: UnreadCount
  id?: string
  className?: string
}) {
  const running = !!project.current_baseline
  const isLeader = project.my_role === 'LEADER'
  const documents = useQuery({
    queryKey: ['projects', project.id, 'hud', 'documents'],
    queryFn: () => dashboardApi.documents(project.id),
    enabled: running,
    staleTime,
  })
  const changes = useQuery({
    queryKey: ['projects', project.id, 'hud', 'changes'],
    queryFn: () => dashboardApi.changes(project.id),
    enabled: running && isLeader,
    staleTime,
  })
  const tasks = useQuery({
    queryKey: ['projects', project.id, 'hud', 'tasks'],
    queryFn: () => dashboardApi.tasks(project.id),
    enabled: running,
    staleTime,
  })
  const onboarding = useQuery({
    queryKey: ['projects', project.id, 'onboarding'],
    queryFn: () => projectsApi.onboarding(project.id),
    enabled: !running,
    staleTime,
  })
  const members = useQuery({ ...membersQuery(project.id), staleTime })
  const activity = useQuery({
    queryKey: ['projects', project.id, 'latest-activity'],
    queryFn: () => dashboardApi.activities(project.id, { limit: 1 }),
    staleTime,
  })

  const chips = [
    ...(isLeader
      ? [
          [
            '검토할 변경',
            countText(changes, changes.data?.review_required_count),
          ],
        ]
      : []),
    [
      '진행 중 작업',
      countText(
        tasks,
        tasks.data?.by_status.find((item) => item.status === 'IN_PROGRESS')
          ?.count,
      ),
    ],
    [
      '승인 문서',
      countText(
        documents,
        documents.data?.by_kind.reduce(
          (sum, item) => sum + item.approved_count,
          0,
        ),
      ),
    ],
  ]
  const required = onboarding.data?.required_documents
  const faces = (members.data?.items ?? [])
    .toSorted(
      (a, b) => Number(b.role === 'LEADER') - Number(a.role === 'LEADER'),
    )
    .slice(0, 4)
  const latest = activity.data?.items[0]

  return (
    <div
      role="tooltip"
      id={id}
      className={cn(
        'flex flex-col gap-[9px] rounded-2xl border bg-card p-3 text-left text-[12.5px] leading-[17px] shadow-[0_18px_40px_-16px_rgba(44,41,31,0.35)]',
        className,
      )}
    >
      <div className="flex items-start gap-2.5">
        <span className="flex size-10 shrink-0 items-end justify-center overflow-hidden rounded-[10px] bg-[#f4f2ec]">
          <BuildingArt design={design} className="h-[34px]" />
        </span>
        <div className="flex min-w-0 flex-1 flex-col gap-0.5">
          <strong className="line-clamp-2 text-sm leading-[19px] font-bold wrap-anywhere break-keep">
            {project.name}
          </strong>
          <span className="line-clamp-2 text-xs leading-[17px] wrap-anywhere break-keep text-[#57534e]">
            {project.description || NO_DESCRIPTION}
          </span>
        </div>
      </div>

      <div className="flex items-center justify-between gap-2 rounded-[10px] bg-[#fbfaf6] px-2 py-[5px] font-semibold">
        <span className="inline-flex min-w-0 items-center gap-1.5">
          <span
            aria-hidden="true"
            className={cn(
              'size-[7px] shrink-0 rounded-full',
              running ? 'bg-[#22a35a]' : 'bg-[#e0b400]',
            )}
          />
          {project.current_baseline
            ? `진행 중 · 기준 v${project.current_baseline.version_no}`
            : '시작 준비 중'}
        </span>
        <span
          className={cn(
            'inline-flex h-5 shrink-0 items-center rounded-full px-[7px] text-[11.5px]',
            isLeader
              ? 'bg-foreground text-white'
              : 'bg-[#f0eee8] text-[#57534e]',
          )}
        >
          {isLeader ? '팀장' : '팀원'}
        </span>
      </div>

      {running ? (
        <div
          className={cn(
            'grid gap-1.5',
            chips.length === 3 ? 'grid-cols-3' : 'grid-cols-2',
          )}
        >
          {chips.map(([label, value]) => (
            <div
              key={label}
              className="flex flex-col rounded-[10px] border px-[7px] py-[5px]"
            >
              <strong className="text-[15px] leading-5 font-bold tabular-nums">
                {value}
              </strong>
              <span className="text-[11px] leading-[14px] break-keep text-[#57534e]">
                {label}
              </span>
            </div>
          ))}
        </div>
      ) : (
        <div className="flex flex-col gap-1.5">
          <div aria-hidden="true" className="grid grid-cols-4 gap-1">
            {(required ?? Array.from({ length: 4 }, () => undefined)).map(
              (item, index) => (
                <span
                  key={item?.kind ?? index}
                  className={cn(
                    'h-1.5 rounded-full',
                    // 어느 문서인지가 아니라 몇 개인지를 보이므로 왼쪽부터 채운다.
                    index < (required?.filter((d) => d.uploaded).length ?? 0)
                      ? 'bg-[#e0b400]'
                      : 'bg-[#e7e3d8]',
                  )}
                />
              ),
            )}
          </div>
          <div className="flex flex-wrap justify-between gap-x-2 gap-y-0.5 text-xs leading-4">
            <span className="text-[#57534e]">
              문서 초안{' '}
              {countText(
                onboarding,
                required?.filter((item) => item.uploaded).length,
              )}{' '}
              / 4 올림
            </span>
            {required && (
              <span className="font-semibold text-[#7a5e00]">
                {nextStepText(required, isLeader)}
              </span>
            )}
          </div>
        </div>
      )}

      <div className="flex min-h-[18px] items-center justify-between gap-2 text-[11.5px] leading-[15px] text-[#57534e]">
        <span className="inline-flex min-w-0 items-center gap-[13px]">
          {faces.length > 0 && (
            <span aria-hidden="true" className="inline-flex">
              {faces.map((member) => (
                <span
                  key={member.id}
                  className="-mr-1 grid size-[18px] place-items-center rounded-full text-[10px] font-bold text-foreground ring-2 ring-card"
                  style={{
                    backgroundColor: `color-mix(in srgb, ${member.color ?? '#8a8378'} 20%, white)`,
                  }}
                >
                  {member.name.slice(0, 1)}
                </span>
              ))}
            </span>
          )}
          {members.data && peopleText(members.data.items)}
        </span>
        <span className="shrink-0">
          {latest
            ? `${formatAgo(latest.created_at)} 활동`
            : !activity.isPending && `${formatAgo(project.created_at)} 만듦`}
        </span>
      </div>

      {unread && (
        <div className="border-t border-[#efece4] pt-[7px] text-[11.5px] leading-[15px] font-semibold text-[#b91c1c]">
          {unread.label}
        </div>
      )}
    </div>
  )
}
