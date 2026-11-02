import { useEffect, useState } from 'react'
import type { ReactNode } from 'react'
import { useNavigate } from 'react-router'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import {
  Check,
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
import type { GetResponse as Project } from '@/features/projects/schemas'
import type { OnboardingResponse } from '@/features/projects/schemas'
import { useProjectStart } from '@/features/projects/useProjectStart'
import { InboxPopover } from '@/features/inbox/InboxPopover'
import {
  openMeetingCount,
  useMeetingList,
} from '@/features/meetings/useMeetingList'
import { targetLabel, targetPane } from '@/features/inbox/notificationTarget'
import { Toast } from '@/components/Toast'
import { Hotbar } from './Hotbar'
import { TourGuide } from './TourGuide'
import type { TourMode } from './TourGuide'
import { Button } from '@/components/ui/button'
import { countText, describeContent, statusLabel } from '@/lib/display'
import { formatAgo } from '@/lib/formatDate'
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
    text: "색인까지 끝났어요. 가이드의 '프로젝트 시작'을 누르면 기준 v1이 생기고 프로젝트가 시작돼요.",
    member: '팀장이 프로젝트를 시작하면 알려 드려요.',
  },
  3: {
    title: '기준 v1이 생겼어요 · 프로젝트가 시작됐어요',
    text: '팀장이 프로젝트를 시작해 기준 v1이 생겼어요. 이제 변경 제안, 회의실, 작업 현황, 설계 관계를 쓸 수 있어요. 문서 수정도 이제 변경 제안으로 해요.',
  },
}

type Step = {
  title: string
  detail: string
  done: boolean
  /** 필수 표시(문서 초안 4종) */
  required?: boolean
  /** 건너뛴 선택 단계: 완료로 세고 '건너뜀'과 다시 하기 링크를 둔다 */
  skipped?: boolean
  locked?: boolean
  optional?: boolean
  action?: {
    label: string
    pane?: WorkspacePane
    focus?: string
    run?: () => void
    disabled?: boolean
  }
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
    assignee_member_id: string
  }[]
  myTasks: TaskDetail[]
}) {
  const navigate = useNavigate()
  const openPane = useWorkspaceStore((state) => state.openPane)
  const pane = useWorkspaceStore((state) => state.pane)
  const setOutdoor = useWorkspaceStore((state) => state.setOutdoor)
  const isLeader = project.my_role === 'LEADER'
  const started = onboarding.baseline_published
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
  // 팀장 시작 가이드: 문서별 승인 요청(DOC-A19)과 프로젝트 시작(PLN-A10)
  const submitted = useQuery({
    queryKey: ['projects', project.id, 'document-submissions', 'SUBMITTED'],
    queryFn: () =>
      documentsApi.reviewSubmissions(project.id, { status: 'SUBMITTED' }),
    enabled: isLeader && !started,
  })
  const start = useProjectStart(project.id, isLeader && !started)
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
  // API 없음: 가이드 상태 저장 필드(시작 가이드 백엔드 메모 1). 건너뛴 초대는 브라우저에 둔다.
  const inviteSkipKey = guideKey(project.id, 'invite-skipped')
  const invited = onboarding.member_count > 1
  const inviteSkipped = !invited && readFlag(inviteSkipKey)
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
  // 10-29: 팀원 초대(선택)를 1순위로, 건너뛸 수 있게. 시작 조건은 그대로 4종 승인.
  const leaderSteps: Step[] = [
    {
      title: '팀원 초대하기',
      detail: invited
        ? '팀원이 들어왔어요.'
        : inviteSkipped
          ? '나중에 초대해도 돼요.'
          : '선택 · 같이 일할 사람을 먼저 불러요. 건너뛰어도 문서는 올릴 수 있어요.',
      done: invited || inviteSkipped,
      skipped: inviteSkipped,
      optional: true,
      action: { label: '팀원 초대하기', pane: 'settings', focus: 'team' },
    },
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
          ? `문서 4종 승인 4 / 4 · ${start.indexed ? '설계 초안 준비됨' : start.needsDrafts ? '설계 항목 초안을 만들어야 시작할 수 있어요' : '설계 초안 확인 중'}`
          : `팀장 승인 ${approvedKinds} / 4${pendingApprovals ? ` · 승인 요청 ${pendingApprovals}건` : ''} · 팀장이 4종을 승인하고 색인이 끝나면 '프로젝트 시작'이 켜져요. 누르면 기준 v1이 생기고 프로젝트가 시작돼요.`,
      done: started,
      locked: !allUploaded,
      // 서버는 문서 승인·색인 뒤 설계 초안을 만들지 않아, 초안이 없으면 기획 분석 창(초안 만들기·직접 추가)으로 보낸다.
      action: pendingApprovals
        ? { label: '승인 요청 보기', pane: 'documents' }
        : allApproved && start.needsDrafts
          ? { label: '설계 초안 만들기', pane: 'planning' }
          : allApproved
            ? {
                label: '프로젝트 시작',
                run: () => void start.start().catch(() => undefined),
                disabled: !start.ready || start.busy,
              }
            : { label: '문서함 보기', pane: 'documents' },
      note: start.error
        ? start.error
        : allApproved
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
      title: '내 담당 영역 확인',
      detail: me?.domain
        ? `${me.domain} (팀장이 정해요)`
        : '담당 영역은 팀장이 정해요. 아직 정해지지 않았어요.',
      done: Boolean(me?.domain),
    },
    {
      title: '내 에이전트 연결하기',
      detail:
        '설정 › 에이전트에서 토큰을 발급하고 Claude Code에 BEEZ MCP를 추가해요.',
      done: onboarding.my_agent_connected,
      action: { label: '설정에서 연결하기', pane: 'settings', focus: 'agent' },
    },
    {
      title: '기준 문서 v1 읽기',
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
  const current = steps.findIndex((step) => !step.done && !step.locked)
  const agentStepNow =
    !isLeader && guideOpen && steps[current]?.title === '내 에이전트 연결하기'
  // 비비 대화 창이 열린 동안은 단축 바만 남기고 다른 위젯은 숨긴다(PNL-12 B안).
  const chatOpen = pane === 'chat'
  // 팀 목록(SCR-06 수정안 · 팀 현황): 사람마다 지금 하는 일과 연결된 에이전트를 한 줄씩.
  // 비비는 목록에 넣지 않고 단축 바 가운데 버튼으로만 연다(사용자 요청 10-30).
  // API 없음: 접속 점·위치·마지막 접속(MON-A07 presence, PR #42 요청)과 에이전트가 하는 일(MON-A05 보고)은 계약이 생기면 줄에 붙인다.
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
  const teamRows = [
    ...members
      .toSorted((a, b) => Number(b.id === me?.id) - Number(a.id === me?.id))
      .map((member) => {
        const task = taskOf(member.id)
        return {
          key: member.id,
          initial: member.name.slice(0, 1),
          color: member.color,
          name: `${member.name}${member.id === me?.id ? ' · 나' : ''}`,
          // API 없음: 접속·위치(presence)가 없어 나만 '사무실 내 자리'로 두고, 다른 팀원은 하는 일만 보여 준다.
          place: member.id === me?.id ? '사무실 내 자리' : undefined,
          detail: task
            ? `${task.key} ${taskText[task.status]}`
            : [member.role === 'LEADER' ? '인간 팀장' : '팀원', member.domain]
                .filter(Boolean)
                .join(' · '),
          open: () =>
            task ? openPane('tasks', task.id) : openPane('settings', 'team'),
        }
      }),
    // 에이전트 줄: 연결된 에이전트, 그리고 아직 연결하지 않은 내 에이전트(설정에서 연결)
    ...members
      .filter((member) => member.agent_connected || member.id === me?.id)
      .map((member) => ({
        key: `agent:${member.id}`,
        initial: 'AI',
        color: undefined,
        name: `${member.name}의 에이전트`,
        place: member.agent_connected ? '연결됨' : '연결 전',
        detail: member.agent_connected ? '' : '설정에서 연결해요',
        open: () =>
          member.id === me?.id && !member.agent_connected
            ? openPane('settings', 'agent')
            : openPane('dashboard'),
      })),
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
  const todos = (
    [
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
          ? `영향 작업 ${reviewImpact.data.explicit.length} · 승인하면 기준 v${(project.current_baseline?.version_no ?? 0) + 1}`
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
          openPane(targetPane[firstUnread.target_type], firstUnread.target_id)
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
        className="pointer-events-auto absolute top-4 left-4 z-10 grid w-80 gap-3 rounded-2xl border bg-card/95 p-3.5 shadow-lg backdrop-blur"
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
            <h2 className="truncate text-base font-bold">{project.name}</h2>
            <p className="text-xs text-muted-foreground">
              {isLeader
                ? '인간 팀장'
                : ['팀원', me?.domain].filter(Boolean).join(' · ')}{' '}
              ·{' '}
              {project.current_baseline
                ? `기준 v${project.current_baseline.version_no}`
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
          <div className="flex items-center justify-between gap-2 px-0.5">
            <span className="text-xs font-semibold">
              팀{' '}
              <span className="font-normal text-muted-foreground">
                {teamRows.length}
              </span>
              {members.length <= 1 && (
                <span className="ml-1.5 font-normal text-muted-foreground">
                  아직 혼자예요
                </span>
              )}
            </span>
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
          <ul
            aria-label="팀 목록"
            className="grid max-h-64 gap-0.5 overflow-y-auto"
          >
            {teamRows.map((row) => (
              <li key={row.key}>
                <button
                  type="button"
                  onClick={row.open}
                  className="flex w-full items-center gap-2.5 rounded-lg px-1.5 py-1 text-left hover:bg-muted"
                >
                  <span
                    aria-hidden="true"
                    className="grid size-7 shrink-0 place-items-center rounded-full text-[11px] font-bold"
                    style={{
                      backgroundColor: `${row.color ?? '#8a8378'}26`,
                      color: row.color ?? '#57534e',
                    }}
                  >
                    {row.initial}
                  </span>
                  <span className="min-w-0">
                    <strong className="block truncate text-xs">
                      {row.name}
                    </strong>
                    <span className="block truncate text-[11px] text-muted-foreground">
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
          </ul>
        </div>
      </section>
      <Hotbar
        shortcutsOff={tour !== null}
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
        className="pointer-events-auto absolute top-17 right-4 z-10 w-85 rounded-2xl border bg-card/95 p-3 shadow-lg backdrop-blur"
      >
        {guideOpen ? (
          <>
            <h2
              id="todo-title"
              className="flex items-center justify-between px-1 text-sm font-bold"
            >
              {isLeader ? '프로젝트 시작 가이드' : '팀원 시작 가이드'}
              <span className="text-xs font-semibold text-muted-foreground">
                {steps.filter((step) => step.done).length} / {steps.length}{' '}
                {isLeader ? '단계' : '완료'}
              </span>
            </h2>
            <ol className="mt-2 grid gap-1.5">
              {steps.map((step, index) => {
                const locked = Boolean(step.locked) && !step.done
                return (
                  <li
                    key={step.title}
                    className={cn(
                      'rounded-xl border p-2.5',
                      (index === current || (step.skipped && started)) &&
                        'border-primary bg-primary/5',
                      locked && 'opacity-55',
                    )}
                  >
                    <div className="flex items-start gap-2">
                      <span
                        className={cn(
                          'grid size-5 shrink-0 place-items-center rounded-full text-[11px] font-bold',
                          step.done
                            ? 'bg-green-600 text-white'
                            : 'bg-muted text-muted-foreground',
                        )}
                      >
                        {step.done ? (
                          <Check className="size-3" />
                        ) : locked ? (
                          <Lock className="size-3" />
                        ) : (
                          index + 1
                        )}
                      </span>
                      <div className="grid flex-1 gap-0.5">
                        <strong className="text-sm">
                          {step.title}
                          {step.required && (
                            <span className="ml-1.5 rounded bg-destructive/10 px-1 text-[10px] text-destructive">
                              필수
                            </span>
                          )}
                          {step.done && !step.skipped && (
                            <span className="ml-1.5 text-[11px] font-semibold text-green-700">
                              완료
                            </span>
                          )}
                          {step.skipped && (
                            <span className="ml-1.5 text-[11px] font-semibold text-muted-foreground">
                              건너뜀
                            </span>
                          )}
                        </strong>
                        <span className="text-xs text-muted-foreground">
                          {step.detail}
                        </span>
                        {!step.done && !locked && step.children}
                        {/* 건너뛴 초대: 시작 전에는 링크, 시작 뒤에는 노란 칸에 버튼을 다시 보여 준다(10-29). */}
                        {step.skipped && step.action && (
                          <span className="mt-1">
                            <Button
                              size="xs"
                              variant={started ? 'default' : 'link'}
                              className={started ? undefined : 'h-auto p-0'}
                              onClick={() =>
                                openPane(step.action!.pane!, step.action!.focus)
                              }
                            >
                              {started ? step.action.label : '지금 초대하기'}
                            </Button>
                          </span>
                        )}
                        {!step.done && !locked && (
                          <span className="mt-1 flex flex-wrap items-center gap-2">
                            {step.action && (
                              <Button
                                size="xs"
                                variant={
                                  index === current ? 'default' : 'outline'
                                }
                                disabled={step.action.disabled}
                                onClick={() =>
                                  step.action!.run
                                    ? step.action!.run()
                                    : openPane(
                                        step.action!.pane!,
                                        step.action!.focus,
                                      )
                                }
                              >
                                {step.action.label}
                              </Button>
                            )}
                            {!isLeader &&
                              step.title === '기준 문서 v1 읽기' && (
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
                            {step.optional && index === current && (
                              <Button
                                size="xs"
                                variant="ghost"
                                onClick={() => {
                                  writeFlag(inviteSkipKey)
                                  rerender((value) => value + 1)
                                }}
                              >
                                나중에 할게요
                              </Button>
                            )}
                            {step.note && (
                              <span className="text-[11px] text-muted-foreground">
                                {step.note}
                              </span>
                            )}
                          </span>
                        )}
                      </div>
                    </div>
                  </li>
                )
              })}
            </ol>
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
            <h2
              id="todo-title"
              className="flex items-center justify-between px-1 text-sm font-bold"
            >
              지금 할 일
              <span className="text-xs text-muted-foreground">
                {todos.length}
              </span>
            </h2>
            <ul className="mt-2 grid grid-cols-[minmax(0,1fr)] gap-1">
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
