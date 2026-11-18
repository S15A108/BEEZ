import { useEffect, useState } from 'react'
import type { ReactNode } from 'react'
import { useNavigate } from 'react-router'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import {
  Check,
  ChevronDown,
  ChevronLeft,
  CircleHelp,
  Lock,
  MapPin,
  Settings,
} from 'lucide-react'
import {
  changesApi,
  dashboardApi,
  documentsApi,
  inboxApi,
} from '@/api/services'
import type { Member, TaskDetail } from '@/api/contracts/common'
import { useAccountDialog } from '@/features/account/useAccountDialog'
import { agentStatusLabels } from '@/features/agents/agentStatus'
import { useAgentStatusOf } from '@/features/agents/useAgentStatusOf'
import type { GetResponse as Project } from '@/features/projects/schemas'
import type { OnboardingResponse } from '@/features/projects/schemas'
import { InboxPopover } from '@/features/inbox/InboxPopover'
import {
  openMeetingCount,
  useMeetingList,
} from '@/features/meetings/useMeetingList'
import {
  targetFocus,
  targetLabel,
  targetPane,
} from '@/features/inbox/notificationTarget'
import { openTaskCount, openTasksQuery } from '@/features/tasks/openTasksQuery'
import { Toast } from '@/components/Toast'
import { BibiStatusRow } from './BibiStatusRow'
import { Hotbar } from './Hotbar'
import { TourGuide } from './TourGuide'
import type { TourMode } from './TourGuide'
import { Button } from '@/components/ui/button'
import { countText, describeContent, statusLabel } from '@/lib/display'
import { presenceQuery } from '@/features/presence/presenceQuery'
import { presenceView } from '@/features/presence/presenceText'
import { formatAgo, formatShort } from '@/lib/formatDate'
import { cn } from '@/lib/utils'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import type { WorkspacePane } from '@/stores/workspaceStore'

// '지금 할 일' 한 줄: 설명 한 줄과 바로 가기. 변경 제안 줄은 −/+ 미리 보기와 영향까지(SCR-06-v2-workspace-hotbar).
type Todo = {
  key: string
  mark: string
  tone: 'review' | 'alert' | 'done'
  title: string
  detail: string
  preview?: { before?: string; after?: string }
  note?: string
  action: string
  open: () => void
}

// 가이드 숨김·'기준 설계 읽기' 체크는 브라우저에만 둔다.
// API 없음: 가이드 상태(봤음·숨김·완료)를 계정·프로젝트별로 서버에 저장하는 필드.
const guideKey = (projectId: string, name: string) =>
  `beez.guide.${projectId}.${name}`
const readFlag = (key: string) => {
  try {
    return localStorage.getItem(key) === '1'
  } catch {
    return false
  }
}
const writeFlag = (key: string) => {
  try {
    localStorage.setItem(key, '1')
  } catch {
    // 저장소를 못 쓰면 이번 화면에서만 기억한다.
  }
}
const readStage = (key: string) => {
  try {
    const value = localStorage.getItem(key)
    return value === null ? null : Number(value)
  } catch {
    return null
  }
}

const REQUIRED = ['PLAN', 'ERD', 'REQUIREMENTS', 'API_SPEC']
// 준비 중(0) → 4종 올림(1) → 4종 승인(2) → 프로젝트 시작(3). 단계가 오르면 위에 한 번 알린다.
const notices: Record<
  number,
  { title: string; text: string; member?: string }
> = {
  1: {
    title: '문서 4종이 모두 올라왔어요',
    text: '비비 검토 → 팀장 승인을 거쳐 4종이 승인되면 프로젝트를 시작할 수 있어요.',
  },
  2: {
    title: '문서 4종이 모두 승인됐어요',
    text: "색인까지 끝났어요. 가이드의 '설계 초안 만들기'로 기획 분석을 열어 설계 항목을 검토하고 '프로젝트 시작'을 누르면 시작돼요.",
    member: '팀장이 프로젝트를 시작하면 알려 드려요.',
  },
  3: {
    title: '설계 기준 v1이 생겼어요 · 프로젝트가 시작됐어요',
    text: '팀장이 프로젝트를 시작해 설계 기준 v1이 생겼어요. 이제 변경 제안, 회의실, 작업 현황, 설계 관계를 쓸 수 있어요. 문서 수정도 이제 변경 제안으로 해요.',
  },
}

type Step = {
  title: string
  detail: string
  done: boolean
  /** 필수 표시(문서 초안 4종) */
  required?: boolean
  locked?: boolean
  action?: { label: string; pane: WorkspacePane; focus?: string }
  note?: string
  children?: ReactNode
}

export function WorkspaceHud({
  project,
  members,
  me,
  onboarding,
  tasks,
  myTasks,
}: {
  project: Project
  members: Member[]
  me?: Member
  onboarding: OnboardingResponse
  tasks: {
    id: string
    key: string
    status: string
    assignee_member_id?: string
  }[]
  myTasks: TaskDetail[]
}) {
  const navigate = useNavigate()
  const openPane = useWorkspaceStore((state) => state.openPane)
  const pane = useWorkspaceStore((state) => state.pane)
  // 사무실 위에 회원 설정 창(D48)이 떠 있으면 단축 바 숫자 키를 끈다.
  const accountOpen = useAccountDialog().view !== null
  const setOutdoor = useWorkspaceStore((state) => state.setOutdoor)
  const isLeader = project.my_role === 'LEADER'
  const started = onboarding.baseline_published
  const statusOf = useAgentStatusOf(project.id)
  // 팀원 접속 상태(MON-A07). 아직 못 받았거나 서버에 없는 API면 undefined/null 이라 접속 점 없이 보인다.
  const presence = useQuery(presenceQuery(project.id)).data
  // 집계는 따로 받는다. 하나가 실패해도(dev 백엔드에 작업 집계 API가 아직 없음) 다른 숫자와 시작 안내는 그대로 보인다
  // (PR #72 전수 리뷰 TC-5). 실패한 칸은 0 대신 '—'.
  const documentCounts = useQuery({
    queryKey: ['projects', project.id, 'hud', 'documents'],
    queryFn: () => dashboardApi.documents(project.id),
  })
  const changeCounts = useQuery({
    queryKey: ['projects', project.id, 'hud', 'changes'],
    queryFn: () => dashboardApi.changes(project.id),
  })
  const taskCounts = useQuery({
    queryKey: ['projects', project.id, 'hud', 'tasks'],
    queryFn: () => dashboardApi.tasks(project.id),
  })
  // 팀장 시작 가이드: 문서별 승인 요청(DOC-A19)
  const submitted = useQuery({
    queryKey: ['projects', project.id, 'document-submissions', 'SUBMITTED'],
    queryFn: () =>
      documentsApi.reviewSubmissions(project.id, { status: 'SUBMITTED' }),
    enabled: isLeader && !started,
  })
  const pendingApprovals = submitted.data?.length ?? 0
  const approvedKinds =
    documentCounts.data?.by_kind.filter(
      (item) => REQUIRED.includes(item.kind) && item.approved_count > 0,
    ).length ?? 0
  const unread = useQuery({
    queryKey: ['notifications', project.id, 'unread'],
    queryFn: () =>
      inboxApi.list({ project_id: project.id, unread: true, limit: 20 }),
  })
  const queryClient = useQueryClient()
  // '지금 할 일' 첫 줄(SCR-06-v2-workspace-hotbar): 검토를 기다리는 첫 변경 제안의 상세·영향. 키는 변경 제안 창과 같다.
  const reviewList = useQuery({
    queryKey: ['projects', project.id, 'hud-review'],
    queryFn: () =>
      changesApi.list(project.id, { group: 'IN_PROGRESS', limit: 20 }),
    // 창이 열린 동안은 '지금 할 일'이 가려지므로 부르지 않는다(목 모드 부하, E2E 시간).
    enabled:
      started && !pane && (changeCounts.data?.review_required_count ?? 0) > 0,
  })
  // '지금 할 일'의 담당자 없는 작업(TSK-A02 unassigned=true). 대시보드와 같은 키라 한 번만 부른다.
  const unassigned = useQuery({
    ...openTasksQuery(project.id, { unassigned: true }),
    enabled: started && !pane,
  })
  const firstReview = reviewList.data?.items.find((item) =>
    ['PROPOSED', 'IN_REVIEW'].includes(item.status),
  )
  const reviewDetail = useQuery({
    queryKey: ['projects', project.id, 'change', firstReview?.id],
    queryFn: () => changesApi.get(project.id, firstReview!.id),
    enabled: !!firstReview && !pane,
  })
  const reviewImpact = useQuery({
    queryKey: ['projects', project.id, 'impact', firstReview?.id],
    queryFn: () => changesApi.impact(project.id, firstReview!.id),
    enabled: !!firstReview && !pane,
  })
  const [, rerender] = useState(0)
  // 워크스페이스에 처음 들어오면 환영 창을 한 번 띄운다. 다시 보기는 ? 키와 도움말 버튼.
  const [tour, setTour] = useState<TourMode>(() => {
    try {
      return me && !localStorage.getItem(`beez.tour.${me.account_id}`)
        ? 'welcome'
        : null
    } catch {
      return null
    }
  })
  const hiddenKey = guideKey(project.id, isLeader ? 'leader' : 'member')
  const readKey = guideKey(project.id, 'baseline-read')
  // 팀장의 선택 단계(팀원 초대 · 에이전트 연결)는 다음 할 일로 고르지 않고 가이드 아래 링크로만 둔다(S15P31A108-430).
  // 끝낸 쪽은 링크가 사라진다.
  const setupLinks = isLeader
    ? [
        ...(onboarding.member_count > 1
          ? []
          : [
              {
                label: started ? '팀원 초대하기' : '지금 초대하기',
                focus: 'team',
              },
            ]),
        ...(onboarding.my_agent_connected
          ? []
          : [{ label: '에이전트 연결하기', focus: 'agent' }]),
      ]
    : []
  const uploaded = onboarding.required_documents.filter(
    (item) => item.uploaded,
  ).length
  const allUploaded = uploaded === onboarding.required_documents.length
  const allApproved = approvedKinds >= 4
  // 단계를 처음 본 브라우저에서는 알리지 않고, 이전에 본 단계보다 올라갔을 때만 알린다.
  const stage = started
    ? 3
    : !allUploaded
      ? 0
      : documentCounts.isSuccess
        ? allApproved
          ? 2
          : 1
        : null
  const stageKey = `beez.stage.${project.id}`
  const [seenStage] = useState(() => readStage(stageKey))
  useEffect(() => {
    if (stage === null) return
    try {
      localStorage.setItem(stageKey, String(stage))
    } catch {
      /* 저장소가 없으면 알림을 한 번 더 띄울 수 있을 뿐이다. */
    }
  }, [stage, stageKey])
  const notice =
    stage !== null && seenStage !== null && stage > seenStage
      ? notices[stage]
      : undefined
  // 시작 조건은 4종 승인이다. 선택인 팀원 초대 · 에이전트 연결은 단계로 세지 않고 setupLinks 로만 둔다.
  const leaderSteps: Step[] = [
    {
      title: '문서 초안 4종 올리기',
      required: true,
      detail: allUploaded
        ? `${uploaded} / 4 올림`
        : '초안이어도 괜찮아요. 올리면 비비가 바로 검토해요.',
      done: allUploaded,
      children: (
        <ul className="mt-2 grid gap-1">
          {onboarding.required_documents.map((item) => (
            <li
              key={item.kind}
              className="flex items-center justify-between rounded-lg bg-muted/50 px-2.5 py-1.5 text-xs"
            >
              <span className="flex items-center gap-1.5">
                {item.uploaded && <Check className="size-3.5 text-green-700" />}
                {statusLabel(item.kind)}
              </span>
              {!item.uploaded && (
                <button
                  type="button"
                  className="font-semibold underline"
                  aria-label={`${statusLabel(item.kind)} 올리기`}
                  onClick={() => openPane('documents', `upload:${item.kind}`)}
                >
                  올리기
                </button>
              )}
            </li>
          ))}
          <li className="flex items-center justify-between gap-2 text-[11px] text-muted-foreground">
            {uploaded} / 4 올림
            <Button
              size="xs"
              variant="outline"
              onClick={() => openPane('documents')}
            >
              공용 문서함 열기
            </Button>
          </li>
          <li className="text-[11px] text-muted-foreground">
            변경 제안 · 회의실 · 작업 현황 · 설계 관계는 프로젝트를 시작하면
            열려요
          </li>
        </ul>
      ),
    },
    {
      title: '비비 검토 반영하기',
      detail: !allUploaded
        ? '4종이 다 올라가면 열려요.'
        : allApproved || started
          ? '4종 모두 검토를 반영했어요.'
          : '비비가 4종을 검토해요. 고칠 곳은 공용 문서함에 표시해 둬요.',
      done: allApproved || started,
      locked: !allUploaded,
      action: { label: '검토 결과 보기', pane: 'documents' },
    },
    {
      title: '4종 승인하고 프로젝트 시작',
      detail: started
        ? '프로젝트를 시작했어요.'
        : allApproved
          ? "문서 4종 승인 4 / 4 · 기획 분석에서 설계 항목을 검토하고 '프로젝트 시작'을 누르면 시작돼요"
          : // 승인 전 버튼은 '승인 요청 보기'·'문서함 보기'라서 '켜진다'고 하지 않고 다음 순서만 알린다(잠긴 줄의 이유 글자로도 쓰인다).
            `팀장 승인 ${approvedKinds} / 4${pendingApprovals ? ` · 승인 요청 ${pendingApprovals}건` : ''} · 팀장이 4종을 승인하면 '설계 초안 만들기'로 기획 분석을 열어요. 설계 항목을 검토하고 '프로젝트 시작'을 누르면 설계 기준 v1이 생기고 시작돼요.`,
      done: started,
      locked: !allUploaded,
      // 기준 v1 발행(PLN-A10)은 기획 분석의 '프로젝트 시작' 한 곳에서 한다. '설계 초안 만들기'는 그 창을 연다.
      action: pendingApprovals
        ? { label: '승인 요청 보기', pane: 'documents' }
        : allApproved
          ? { label: '설계 초안 만들기', pane: 'planning' }
          : { label: '문서함 보기', pane: 'documents' },
      note: allApproved
        ? '시작하면 변경 제안과 작업 계획을 받을 수 있어요'
        : undefined,
    },
  ]
  const memberSteps: Step[] = [
    {
      title: '팀 참여',
      detail: `${project.name}에 참여했어요.`,
      done: true,
    },
    {
      title: '내 담당 도메인 확인',
      detail: me?.domain
        ? `${me.domain} (팀장이 정해요)`
        : '담당 도메인은 팀장이 정해요. 아직 정해지지 않았어요.',
      done: Boolean(me?.domain),
    },
    {
      title: '내 에이전트 연결하기',
      detail:
        '설정 › 내 에이전트 관리에서 토큰을 발급하고 Claude Code에 BEEZ MCP를 추가해요.',
      done: onboarding.my_agent_connected,
      action: { label: '설정에서 연결하기', pane: 'settings', focus: 'agent' },
    },
    {
      title: '현재 승인 문서 읽기',
      detail: started
        ? '팀장이 승인한 문서 4종을 확인해요.'
        : '팀장이 프로젝트를 시작하면 열려요.',
      // API 없음: 읽었는지 기록하는 API가 없어 직접 체크한다.
      done: readFlag(readKey),
      locked: !started,
      action: { label: '공용 문서함에서 보기', pane: 'documents' },
    },
    {
      title: '첫 작업 계획 등록하기',
      detail: started
        ? '기준 설계에 맞춰 내 작업 계획을 올려요.'
        : '프로젝트를 시작한 뒤에 할 수 있어요.',
      done: myTasks.length > 0,
      locked: !started,
      action: { label: '작업 계획 등록', pane: 'tasks' },
    },
  ]
  const steps = isLeader ? leaderSteps : memberSteps
  const guideOpen =
    !readFlag(hiddenKey) &&
    (isLeader
      ? !started || onboarding.member_count <= 1
      : steps.some((step) => !step.done))
  // 가이드는 남은 단계 하나만 크게 보이고(focus) 나머지는 한 줄씩, 끝낸 단계는 접는다(S15P31A108-430).
  // 할 수 있는 버튼이 있는 단계를 먼저 고른다(팀장이 정할 담당 도메인은 기다리기만 하는 단계라 그다음).
  // 팀장에게 승인 요청이 와 있으면 그 단계('승인 요청 보기')를 먼저 연다. 비비 검토 단계는 4종이 다 승인돼야 끝나서
  // 그대로 두면 승인 대기가 접힌 줄에 묻힌다(PR #315 리뷰).
  const isOpen = (step: Step) => !step.done && !step.locked
  const firstOpen = (match: (step: Step) => boolean) =>
    steps.findIndex((step) => isOpen(step) && match(step))
  const current =
    [
      firstOpen((step) => step.action?.label === '승인 요청 보기'),
      firstOpen((step) => Boolean(step.action ?? step.children)),
      firstOpen(() => true),
    ].find((index) => index >= 0) ?? -1
  // 남은 단계 줄을 누르면 그 단계를 크게 연다. 끝났거나 잠긴 단계를 고른 채면 current 로 돌아간다.
  const [picked, setPicked] = useState<string>()
  const [showDone, setShowDone] = useState(false)
  const [teamOpen, setTeamOpen] = useState(false)
  // 1280px 미만에서는 '지금 할 일'을 접은 채 시작해 3D 장면과 이름표를 덜 가린다(S15P31A108-443). matchMedia 가 없는 jsdom 은 펼친다.
  const [todoOpen, setTodoOpen] = useState(
    () =>
      typeof window.matchMedia !== 'function' ||
      window.matchMedia('(min-width: 1280px)').matches,
  )
  const doneSteps = steps.filter((step) => step.done)
  // 남은 단계가 모두 잠겼으면(시작을 기다리는 팀원) 첫 잠긴 단계를 기다림 카드로 크게 보이고 잠긴 이유를 적는다.
  const focus =
    steps.find((step) => step.title === picked && isOpen(step)) ??
    steps[current] ??
    steps.find((step) => !step.done)
  const agentStepNow =
    !isLeader && guideOpen && steps[current]?.title === '내 에이전트 연결하기'
  // 비비 대화 창이 열린 동안은 단축 바만 남기고 다른 위젯은 숨긴다(PNL-12 B안).
  const chatOpen = pane === 'chat'
  // 팀 목록(SCR-06 수정안 · 팀 현황): 사람마다 지금 하는 일과 연결된 에이전트를 한 줄씩.
  // 비비는 목록에 넣지 않고 단축 바 가운데 버튼으로만 연다(사용자 요청 10-30). 검토·회의 중일 때만 BibiStatusRow가 한 줄 붙는다.
  // 접속 점·위치·마지막 접속은 MON-A07 presence. 에이전트가 하는 일(MON-A05 보고)은 API 없음이라 계약이 생기면 줄에 붙인다.
  const taskOrder = ['IN_PROGRESS', 'WAITING', 'DRAFT', 'READY']
  const taskOf = (memberId: string) =>
    tasks
      .filter(
        (task) =>
          task.assignee_member_id === memberId &&
          taskOrder.includes(task.status),
      )
      .toSorted(
        (a, b) => taskOrder.indexOf(a.status) - taskOrder.indexOf(b.status),
      )[0]
  const taskText = {
    IN_PROGRESS: '작업 중',
    WAITING: '대기 중',
    DRAFT: '계획 작성 중',
    READY: '시작 전',
  } as Record<string, string>
  // 연결된 에이전트(연결 미확인 포함, AGT-A05). 끊김·연결 전은 목록에 내 것만 남기고 팀 카드 수에도 세지 않는다.
  const linked = (status: string) =>
    status === 'connected' || status === 'recheck'
  // 팀 카드 인원(디자인 검수 F05): 사람과 에이전트 줄을 합쳐 세지 않는다. 사람 수는 팀 관리 목록과 같은 팀원 목록이다.
  const agentCount = members.filter((member) =>
    linked(statusOf(member).status),
  ).length
  const teamRows = [
    ...members
      .toSorted((a, b) => Number(b.id === me?.id) - Number(a.id === me?.id))
      .map((member) => {
        const task = taskOf(member.id)
        const seen = presenceView(
          presence?.find((item) => item.member_id === member.id),
        )
        // 사람 줄은 접속 상태(MON-A07)를 '접속 중'·'오프라인'으로 쓴다. 에이전트의 연결 상태 이름과 섞지 않는다(디자인 검수 F05).
        const status =
          seen.online === undefined
            ? undefined
            : seen.online
              ? '접속 중'
              : (seen.detail ?? '오프라인')
        return {
          online: seen.online,
          key: member.id,
          initial: member.name.slice(0, 1),
          color: member.color,
          name: `${member.name}${member.id === me?.id ? ' · 나' : ''}`,
          status,
          // 위치는 presence(MON-A07)에서 온다. 아직 못 받았으면 나만 '사무실 내 자리'로 두고, 다른 팀원은 하는 일만 보여 준다.
          // 위치(zone)가 없으면 presenceView가 위치 칸에 '접속 중'을 채우는데, 상태 이름에 이미 있어 한 번만 쓴다.
          place:
            (seen.place !== status && seen.place) ||
            (member.id === me?.id && !presence ? '사무실 내 자리' : undefined),
          detail: task
            ? `${task.key} ${taskText[task.status]}`
            : [member.role === 'LEADER' ? '인간 팀장' : '팀원', member.domain]
                .filter(Boolean)
                .join(' · '),
          open: () =>
            task ? openPane('tasks', task.id) : openPane('settings', 'team'),
        }
      }),
    // 에이전트 줄: 연결된 에이전트(연결 미확인 포함), 그리고 아직 연결하지 않은 내 에이전트(설정에서 연결).
    // 연결은 사무실과 같은 연결 현황(AGT-A05)으로 본다. 첫 조회의 agent_connected는 연 뒤의 연결·끊김을 담지 못한다.
    ...members
      .map((member) => ({ member, ...statusOf(member) }))
      .filter(({ member, status }) => linked(status) || member.id === me?.id)
      .map(({ member, status, lastReportAt }) => {
        const mine = member.id === me?.id
        return {
          key: `agent:${member.id}`,
          online: undefined,
          initial: 'AI',
          color: undefined,
          name: `${member.name}의 에이전트`,
          // 연결 상태는 위치가 아니므로 지도 핀이 붙는 위치 칸에 두지 않는다. 연결 미확인은 그 이름 그대로 보인다(F05).
          status: agentStatusLabels[status],
          place: undefined,
          detail:
            status === 'recheck' && lastReportAt
              ? `마지막 보고 ${formatShort(lastReportAt)}`
              : status === 'disconnected' && lastReportAt
                ? `마지막 통신 ${formatShort(lastReportAt)}`
                : (status === 'none' || status === 'failed') && mine
                  ? '설정에서 연결해요'
                  : '',
          open: () =>
            mine && status !== 'connected'
              ? openPane('settings', 'agent')
              : openPane('dashboard'),
        }
      }),
  ]
  const approved =
    documentCounts.data?.by_kind.reduce(
      (sum, item) => sum + item.approved_count,
      0,
    ) ?? 0
  const reviewCount = changeCounts.data?.review_required_count ?? 0
  // 회의실 배지: 회의실 창과 같은 목록 캐시. 시작 전에는 회의실이 잠겨 부르지 않는다.
  const meetingList = useMeetingList(project.id, started)
  const meetingCount = openMeetingCount(
    meetingList.data?.pages.flatMap((page) => page.items) ?? [],
    me?.id,
  )
  const inProgress =
    taskCounts.data?.by_status.find((item) => item.status === 'IN_PROGRESS')
      ?.count ?? 0
  // 내 작업 중 계획 검증을 통과해 바로 시작할 수 있는 것만 센다.
  const readyTasks = myTasks.filter(
    (task) => task.status === 'READY' && task.validation_status === 'PASS',
  )
  const unreadItems = unread.data?.items ?? []
  const nameOfAccount = (id: string) =>
    members.find((member) => member.account_id === id)?.name ?? '팀원'
  const proposal = reviewDetail.data?.proposal
  // −/+ 미리 보기는 item_texts(195 미구현)를 먼저 쓰고, 없으면 CHG-A03 비교 값을 쓴다. 둘 다 없으면 줄 없이 둔다.
  const firstText = reviewDetail.data?.item_texts?.find(
    (text) => text.before_text || text.after_text,
  )
  const firstComparison = reviewDetail.data?.comparisons.find(
    (comparison) =>
      comparison.target_type === 'DESIGN_ITEM' &&
      comparison.proposed_content !== undefined,
  )
  const preview = firstText
    ? { before: firstText.before_text, after: firstText.after_text }
    : firstComparison
      ? {
          before:
            (firstComparison.current_content ?? firstComparison.base_content)
              ? describeContent(
                  firstComparison.current_content ??
                    firstComparison.base_content,
                )
              : undefined,
          after: describeContent(firstComparison.proposed_content),
        }
      : undefined
  // 줄 글은 보드 길이로 짧게: '김동료 · 10분 전 · 설계 항목 3개'. 잘린 글은 title로 전체를 본다.
  const reviewSummary = proposal
    ? [
        nameOfAccount(proposal.proposer_account_id),
        formatAgo(proposal.created_at),
        proposal.items.length > 0 && `설계 항목 ${proposal.items.length}개`,
        proposal.documents.length > 0 && `문서 ${proposal.documents.length}개`,
      ]
        .filter(Boolean)
        .join(' · ')
    : '승인을 기다리는 제안이 있어요'
  const firstUnread = unreadItems[0]
  const firstReady = readyTasks[0]
  const orphans = unassigned.data?.items ?? []
  const todos = (
    [
      // 담당자가 나가거나 제외돼 빈 열린 작업(SCR-06-v2-workspace-unassigned). 팀장은 정하고, 팀원은 열어서 맡는다.
      // API 없음: 누가 나가서 비었는지('김동료가 오늘 나갔어요')는 작업 응답에 없어 쓰지 않는다.
      unassigned.data &&
        orphans.length > 0 && {
          key: 'unassigned',
          mark: '!',
          tone: 'review',
          title: `담당자 없는 작업 ${openTaskCount(unassigned.data)}`,
          detail: `${orphans
            .slice(0, 3)
            .map((task) => `${task.key} ${taskText[task.status]}`)
            .join(', ')} · 상태와 검증은 그대로예요`,
          action: isLeader ? '담당자 정하기' : '작업 열기',
          open: () => openPane('tasks', orphans[0].id),
        },
      reviewCount > 0 && {
        key: 'review',
        mark: '!',
        tone: 'review',
        title: firstReview
          ? `변경 제안 검토 · ${firstReview.title}`
          : `변경 제안 ${reviewCount}건 검토하기`,
        detail: reviewSummary,
        preview,
        note: reviewImpact.data
          ? `영향 작업 ${reviewImpact.data.explicit.length} · 승인하면 설계 기준 v${(project.current_baseline?.version_no ?? 0) + 1}`
          : undefined,
        action: '검토하러 가기',
        open: () => openPane('changes', firstReview?.id),
      },
      firstUnread && {
        key: 'unread',
        mark: String(unreadItems.length),
        tone: 'alert',
        title: `읽지 않은 알림 ${unreadItems.length} · ${firstUnread.title}`,
        detail: `${firstUnread.body} · ${formatAgo(firstUnread.created_at)}`,
        action: `${targetLabel[firstUnread.target_type]} 열기`,
        // 벨 팝오버처럼 누르면 그 창으로 가고 그 알림만 읽음 처리한다.
        open: () => {
          void inboxApi
            .markRead({ ids: [firstUnread.id] })
            .then(() =>
              queryClient.invalidateQueries({ queryKey: ['notifications'] }),
            )
          openPane(
            targetPane[firstUnread.target_type],
            targetFocus(firstUnread),
          )
        },
      },
      firstReady && {
        key: 'ready',
        mark: '✓',
        tone: 'done',
        title: `${firstReady.key} 시작하기${readyTasks.length > 1 ? ` 외 ${readyTasks.length - 1}건` : ''}`,
        detail: [
          `계획 r${firstReady.current_plan_revision.revision_no} 검증 통과`,
          firstReady.validation?.finished_at &&
            formatAgo(firstReady.validation.finished_at),
        ]
          .filter(Boolean)
          .join(' · '),
        action: '작업 열기',
        open: () => openPane('tasks', firstReady.id),
      },
    ] satisfies (Todo | false | undefined)[]
  ).flatMap((todo) => (todo ? [todo] : []))
  const chips = [
    ...(isLeader
      ? [
          [
            '검토할 변경',
            countText(changeCounts, reviewCount),
            'changes',
          ] as const,
        ]
      : []),
    ['진행 중 작업', countText(taskCounts, inProgress), 'tasks'] as const,
    ['승인 문서', countText(documentCounts, approved), 'documents'] as const,
  ]
  return (
    <>
      <section
        aria-label="프로젝트"
        data-tour="card"
        hidden={chatOpen}
        // 열을 패널 폭에 묶는다. 자동 폭 열이면 긴 프로젝트 이름을 따라 패널 밖으로 늘어나 말줄임이 되지 않는다.
        className="pointer-events-auto absolute top-4 left-4 z-10 grid w-80 grid-cols-[minmax(0,1fr)] gap-3 rounded-2xl border bg-card/95 p-3.5 shadow-lg backdrop-blur"
      >
        <div className="flex items-center gap-2">
          <Button
            variant="ghost"
            size="icon-sm"
            aria-label="나의 도시로"
            onClick={() => {
              openPane(null)
              setOutdoor(false)
              navigate('/')
            }}
          >
            <ChevronLeft />
          </Button>
          <div className="min-w-0">
            <h2 title={project.name} className="truncate text-base font-bold">
              {project.name}
            </h2>
            <p className="text-xs text-muted-foreground">
              {isLeader
                ? '인간 팀장'
                : ['팀원', me?.domain].filter(Boolean).join(' · ')}{' '}
              ·{' '}
              {project.current_baseline
                ? `설계 기준 v${project.current_baseline.version_no}`
                : started
                  ? '시작됨'
                  : !allUploaded
                    ? '준비 중'
                    : allApproved
                      ? '시작 전'
                      : '시작 전 · 비비 검토 중'}
            </p>
          </div>
        </div>
        {/* 시작 전에도 숫자 칸을 두고, 시작해야 열리는 칸은 자물쇠로 잠근다(캔버스 SCR-06-v2-workspace-preparing). */}
        <div
          className={cn(
            'grid gap-1.5',
            chips.length === 3 ? 'grid-cols-3' : 'grid-cols-2',
          )}
        >
          {chips.map(([label, value, pane]) => {
            const locked = !started && pane !== 'documents'
            return (
              <button
                key={label}
                type="button"
                aria-disabled={locked || undefined}
                title={locked ? '프로젝트를 시작하면 열려요' : undefined}
                onClick={() => !locked && openPane(pane)}
                className={cn(
                  'grid rounded-xl border bg-muted/40 px-2.5 py-2 text-left hover:bg-muted',
                  locked &&
                    'cursor-not-allowed border-dashed text-muted-foreground/60 hover:bg-muted/40',
                )}
              >
                {locked ? (
                  <Lock className="my-2 size-4" aria-hidden="true" />
                ) : (
                  <strong className="text-2xl leading-8">{value}</strong>
                )}
                <span className="text-[11px] font-semibold whitespace-nowrap text-muted-foreground">
                  {label}
                </span>
              </button>
            )
          })}
        </div>
        <div className="grid gap-1 border-t pt-2.5">
          <div className="flex items-center justify-between gap-2">
            {/* 팀 목록은 요약 한 줄로 접어 두고 누르면 펼친다(S15P31A108-430). */}
            {/* '팀 2 · 아직 혼자예요'처럼 에이전트 줄까지 합친 수였다(F05). 혼자인지는 '팀원 1명'과 초대 버튼이 말한다. */}
            <button
              type="button"
              aria-expanded={teamOpen}
              aria-controls="hud-team-list"
              onClick={() => setTeamOpen((value) => !value)}
              className="flex min-w-0 items-center gap-1 rounded-lg px-1 py-0.5 hover:bg-muted"
            >
              <span className="text-xs font-semibold">
                팀원 {members.length}명{' '}
                <span className="font-normal text-muted-foreground">
                  · 에이전트 {agentCount}개
                </span>
              </span>
              <ChevronDown
                aria-hidden="true"
                className={cn(
                  'size-3.5 text-muted-foreground transition-transform',
                  teamOpen && 'rotate-180',
                )}
              />
            </button>
            {isLeader && members.length <= 1 ? (
              <Button
                size="xs"
                variant="outline"
                onClick={() => openPane('settings', 'team')}
              >
                + 팀원 초대
              </Button>
            ) : (
              <Button
                size="xs"
                variant="ghost"
                onClick={() => openPane('settings', 'team')}
              >
                {isLeader ? '팀 관리' : '팀 보기'}
              </Button>
            )}
          </div>
          {/* 비비 줄은 검토·회의 중일 때만 생기는 소식이라 접어도 보인다. */}
          <ul
            id="hud-team-list"
            aria-label="팀 목록"
            className="grid max-h-64 gap-0.5 overflow-y-auto"
          >
            {(teamOpen ? teamRows : []).map((row) => (
              <li key={row.key}>
                <button
                  type="button"
                  onClick={row.open}
                  className="flex w-full items-center gap-2.5 rounded-lg px-1.5 py-1 text-left hover:bg-muted"
                >
                  <span className="relative shrink-0">
                    <span
                      aria-hidden="true"
                      className="grid size-7 place-items-center rounded-full text-[11px] font-bold"
                      style={{
                        backgroundColor: `${row.color ?? '#8a8378'}26`,
                        color: row.color ?? '#57534e',
                      }}
                    >
                      {row.initial}
                    </span>
                    {row.online !== undefined && (
                      // 접속 점(MON-A07): 접속 중은 초록, 오프라인은 회색. 상태 이름은 아래 줄에 글로 있다.
                      <span
                        aria-hidden="true"
                        className={cn(
                          'absolute right-0 bottom-0 size-2.5 rounded-full border-2 border-background',
                          row.online ? 'bg-emerald-500' : 'bg-stone-400',
                        )}
                      />
                    )}
                  </span>
                  <span className="min-w-0">
                    {/* 이름 13px·줄높이 20px, 위치 등 12px·18px(사용자 요청 11-02) */}
                    <strong className="block truncate text-[13px] leading-5">
                      {row.name}
                    </strong>
                    <span className="block truncate text-xs leading-[18px] text-muted-foreground">
                      {row.status}
                      {row.status && (row.place || row.detail) && ' · '}
                      {row.place && (
                        <span className="inline-flex items-center gap-0.5 text-[#57534e]">
                          <MapPin className="size-3" aria-hidden="true" />
                          {row.place}
                        </span>
                      )}
                      {row.place && row.detail && ' · '}
                      {row.detail}
                    </span>
                  </span>
                </button>
              </li>
            ))}
            <BibiStatusRow projectId={project.id} />
          </ul>
        </div>
      </section>
      <Hotbar
        shortcutsOff={tour !== null || accountOpen}
        locked={started ? [] : ['changes', 'meetings', 'tasks', 'relations']}
        lockedReason="프로젝트를 시작하면 열려요"
        badges={{ changes: reviewCount, meetings: meetingCount }}
        highlight={isLeader && !allUploaded ? 'documents' : undefined}
      />
      <div
        data-tour="top"
        hidden={chatOpen}
        className="pointer-events-auto absolute top-4 right-4 z-10 flex gap-2"
      >
        <Button
          size="icon"
          variant="secondary"
          aria-label="화면 안내 (?)"
          onClick={() => setTour('map')}
        >
          <CircleHelp />
        </Button>
        <InboxPopover projectId={project.id} />
        <span className="relative">
          <Button
            size="icon"
            variant="secondary"
            aria-label="설정"
            className={cn(
              agentStepNow && 'animate-pulse ring-2 ring-primary ring-offset-2',
            )}
            onClick={() => openPane('settings', isLeader ? 'team' : 'agent')}
          >
            <Settings />
          </Button>
        </span>
      </div>
      <section
        aria-labelledby="todo-title"
        data-tour="todo"
        hidden={chatOpen}
        // 304px: 1024에서 두 패널 사이 3D 장면을 넓힌다(S15P31A108-443, 전에는 340px).
        className="pointer-events-auto absolute top-17 right-4 z-10 w-76 rounded-2xl border bg-card/95 p-3 shadow-lg backdrop-blur"
      >
        {guideOpen ? (
          <>
            <h2
              id="todo-title"
              className="flex items-center justify-between px-1 text-sm font-bold"
            >
              {isLeader ? '프로젝트 시작 가이드' : '팀원 시작 가이드'}
              <span className="text-xs font-semibold text-muted-foreground">
                {doneSteps.length} / {steps.length} {isLeader ? '단계' : '완료'}
              </span>
            </h2>
            {focus && (
              <div
                className={cn(
                  'mt-2 flex items-start gap-2 rounded-xl border p-2.5',
                  focus.locked
                    ? 'border-dashed bg-muted/40'
                    : 'border-primary bg-primary/5',
                )}
              >
                <span
                  className={cn(
                    'grid size-5 shrink-0 place-items-center rounded-full text-[11px] font-bold',
                    focus.locked
                      ? 'bg-muted text-muted-foreground'
                      : 'bg-primary text-primary-foreground',
                  )}
                >
                  {focus.locked ? (
                    <Lock className="size-3" aria-hidden="true" />
                  ) : (
                    steps.indexOf(focus) + 1
                  )}
                </span>
                <div className="grid flex-1 gap-0.5">
                  <h3 className="text-sm font-bold">
                    {focus.title}
                    {focus.required && (
                      <span className="ml-1.5 rounded bg-destructive/10 px-1 text-[10px] text-destructive">
                        필수
                      </span>
                    )}
                  </h3>
                  <span className="text-xs text-muted-foreground">
                    {focus.detail}
                  </span>
                  {!focus.locked && focus.children}
                  {!focus.locked && (
                    <span className="mt-1 flex flex-wrap items-center gap-2 empty:hidden">
                      {focus.action && (
                        <Button
                          size="xs"
                          onClick={() =>
                            openPane(focus.action!.pane, focus.action!.focus)
                          }
                        >
                          {focus.action.label}
                        </Button>
                      )}
                      {!isLeader && focus.title === '현재 승인 문서 읽기' && (
                        <Button
                          size="xs"
                          variant="outline"
                          onClick={() => {
                            writeFlag(readKey)
                            rerender((value) => value + 1)
                          }}
                        >
                          읽었어요
                        </Button>
                      )}
                      {focus.note && (
                        <span className="text-[11px] text-muted-foreground">
                          {focus.note}
                        </span>
                      )}
                    </span>
                  )}
                </div>
              </div>
            )}
            {/* 남은 단계는 한 줄씩. 잠기지 않은 줄을 누르면 그 단계가 위 칸에 크게 열린다.
                잠긴 줄은 이유를 툴팁이 아닌 글로 아래에 둔다(키보드·터치에서도 보이게, PR #315 리뷰). */}
            {steps.some((step) => !step.done && step !== focus) && (
              <ol aria-label="남은 단계" className="mt-1.5 grid gap-0.5">
                {steps.map((step, index) =>
                  step.done || step === focus ? null : (
                    <li key={step.title}>
                      <button
                        type="button"
                        aria-disabled={step.locked || undefined}
                        title={step.locked ? step.detail : undefined}
                        onClick={() => !step.locked && setPicked(step.title)}
                        className="flex w-full items-center gap-2 rounded-lg px-2.5 py-1.5 text-left text-xs hover:bg-muted aria-disabled:cursor-not-allowed aria-disabled:text-muted-foreground aria-disabled:hover:bg-transparent"
                      >
                        <span className="grid size-5 shrink-0 place-items-center rounded-full bg-muted text-[11px] font-bold text-muted-foreground">
                          {step.locked ? (
                            <Lock className="size-3" aria-hidden="true" />
                          ) : (
                            index + 1
                          )}
                        </span>
                        <span className="grid min-w-0">
                          <span className="truncate">{step.title}</span>
                          {step.locked && (
                            <span className="line-clamp-2 text-[11px]">
                              {step.detail}
                            </span>
                          )}
                        </span>
                      </button>
                    </li>
                  ),
                )}
              </ol>
            )}
            {doneSteps.length > 0 && (
              <div className="mt-1.5">
                <button
                  type="button"
                  aria-expanded={showDone}
                  aria-controls="hud-guide-done"
                  onClick={() => setShowDone((value) => !value)}
                  className="flex w-full items-center gap-2 rounded-lg px-2.5 py-1.5 text-left text-xs font-semibold text-green-700 hover:bg-muted"
                >
                  <span
                    aria-hidden="true"
                    className="grid size-5 shrink-0 place-items-center rounded-full bg-green-600 text-white"
                  >
                    <Check className="size-3" />
                  </span>
                  {/* 필수 단계를 다 끝내면 가이드는 이 한 줄과 선택 링크만 남는다. */}
                  {focus
                    ? `완료 ${doneSteps.length}단계`
                    : `필수 ${doneSteps.length}단계를 모두 마쳤어요`}
                  <ChevronDown
                    aria-hidden="true"
                    className={cn(
                      'ml-auto size-3.5 transition-transform',
                      showDone && 'rotate-180',
                    )}
                  />
                </button>
                <ul
                  id="hud-guide-done"
                  aria-label="완료한 단계"
                  hidden={!showDone}
                  className="grid gap-1 pr-2.5 pb-1 pl-9"
                >
                  {doneSteps.map((step) => (
                    <li key={step.title} className="min-w-0 text-xs">
                      <span className="font-semibold">{step.title}</span>
                      <span
                        title={step.detail}
                        className="block truncate text-muted-foreground"
                      >
                        {step.detail}
                      </span>
                    </li>
                  ))}
                </ul>
              </div>
            )}
            {setupLinks.length > 0 && (
              <p className="mt-2 flex flex-wrap items-center gap-x-3 gap-y-1 border-t px-1 pt-2 text-xs">
                <span className="rounded bg-muted px-1 text-[10px] text-muted-foreground">
                  선택
                </span>
                {setupLinks.map((link) => (
                  <button
                    key={link.focus}
                    type="button"
                    className="font-semibold underline"
                    onClick={() => openPane('settings', link.focus)}
                  >
                    {link.label}
                  </button>
                ))}
              </p>
            )}
            <button
              type="button"
              className="mt-2 w-full text-center text-xs text-muted-foreground underline"
              onClick={() => {
                writeFlag(hiddenKey)
                rerender((value) => value + 1)
              }}
            >
              가이드 숨기기
            </button>
          </>
        ) : (
          <>
            <h2 id="todo-title" className="text-sm font-bold">
              <button
                type="button"
                aria-expanded={todoOpen}
                aria-controls="hud-todo-list"
                onClick={() => setTodoOpen((value) => !value)}
                className="flex w-full items-center justify-between rounded-lg px-1 py-0.5 hover:bg-muted"
              >
                지금 할 일
                <span className="flex items-center gap-1 text-xs text-muted-foreground">
                  {todos.length}
                  <ChevronDown
                    aria-hidden="true"
                    className={cn(
                      'size-3.5 transition-transform',
                      todoOpen && 'rotate-180',
                    )}
                  />
                </span>
              </button>
            </h2>
            <ul
              id="hud-todo-list"
              aria-label="지금 할 일 목록"
              hidden={!todoOpen}
              className="mt-2 grid grid-cols-[minmax(0,1fr)] gap-1"
            >
              {todos.map((todo) => (
                <li
                  key={todo.key}
                  className={cn(
                    'flex min-w-0 gap-2.5 rounded-xl p-2.5',
                    todo.tone === 'review' && 'bg-primary/15',
                  )}
                >
                  <span
                    aria-hidden="true"
                    className={cn(
                      'grid size-5 shrink-0 place-items-center rounded-full text-[11px] font-extrabold',
                      todo.tone === 'review' &&
                        'bg-primary text-primary-foreground',
                      todo.tone === 'alert' && 'bg-red-100 text-red-700',
                      todo.tone === 'done' && 'bg-green-100 text-green-700',
                    )}
                  >
                    {todo.mark}
                  </span>
                  <div className="grid min-w-0 flex-1 grid-cols-[minmax(0,1fr)] gap-1">
                    <strong
                      title={todo.title}
                      className="truncate text-[13px] leading-[18px]"
                    >
                      {todo.title}
                    </strong>
                    <span
                      title={todo.detail}
                      className="truncate text-xs text-muted-foreground"
                    >
                      {todo.detail}
                    </span>
                    {todo.preview && (
                      <div className="rounded-lg bg-card px-2 py-1.5 text-xs leading-[17px]">
                        {todo.preview.before && (
                          <span
                            title={todo.preview.before}
                            className="block truncate text-red-700"
                          >
                            − {todo.preview.before}
                          </span>
                        )}
                        {todo.preview.after && (
                          <span
                            title={todo.preview.after}
                            className="block truncate text-green-700"
                          >
                            + {todo.preview.after}
                          </span>
                        )}
                      </div>
                    )}
                    {todo.note && (
                      <span
                        title={todo.note}
                        className="truncate text-xs text-muted-foreground"
                      >
                        {todo.note}
                      </span>
                    )}
                    <Button
                      size="xs"
                      variant="outline"
                      className="justify-self-end"
                      onClick={todo.open}
                    >
                      {todo.action}
                    </Button>
                  </div>
                </li>
              ))}
              {!todos.length && (
                <li className="p-2 text-xs text-muted-foreground">
                  지금 처리할 일이 없어요.
                </li>
              )}
            </ul>
          </>
        )}
      </section>
      <TourGuide accountId={me?.account_id} mode={tour} onChange={setTour} />
      {notice && (
        <Toast key={stage} tone="dark">
          <strong className="text-sm font-bold">{notice.title}</strong>
          <span className="text-xs text-[#d6d3c8]">
            {(!isLeader && notice.member) || notice.text}
          </span>
        </Toast>
      )}
    </>
  )
}
