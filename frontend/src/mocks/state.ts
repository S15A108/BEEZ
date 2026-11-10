import type {
  Account,
  Activity,
  AgentConnection,
  AgentToken,
  Approval,
  Baseline,
  ChangeComment,
  ChangeProposal,
  ContextPacket,
  ContextRecord,
  DesignItemVersion,
  Document,
  DocumentBeeReview,
  DocumentReviewSubmission,
  DocumentWorkingCopy,
  DocumentVersion,
  Invitation,
  Meeting,
  MeetingEntry,
  Member,
  Notification,
  PlanningQuestion,
  Project,
  TaskDetail,
  TaskPlanRevision,
  Validation,
} from '../api/contracts/common'
import type { DraftJob } from '../features/planning/schemas'
import type { Draft, DraftReview } from '../features/changes/schemas'
import { contractExamples } from './contract-examples'

/** 응답의 latest_review·review_state_hash는 읽을 때 만든다. 검토는 오래된 것부터 쌓는다. */
export type StoredChangeDraft = Omit<
  Draft,
  'latest_review' | 'review_state_hash'
> & { owner_account_id: string; reviews: DraftReview[] }

// 예시 데이터를 바꾸면 버전을 올려 브라우저에 남은 예전 데이터 대신 새 예시를 불러온다.
// v10: 사무실 에이전트 상태 예시(D37) 시드. 키를 올려 이전 브라우저 저장값을 새 시드로 바꾼다.
// v11: 보완 질문 유형(선택·단답·서술)과 초안 후보(S15P31A108-202) 시드.
// v13: 변경 제안 71 활동 이력(S15P31A108-303) 시드와 활동의 actor_type 필수화. dev 의 v12 를 이미 연 브라우저에는 이 시드가 없어 키를 올린다.
export const MOCK_STORAGE_KEY = 'beez.mock.api.v13'
export const DEMO_EMAIL = 'demo@beez.local'
export const DEMO_PASSWORD = 'beez-demo-2026'
export const MOCK_NOTICE =
  '브라우저에 저장한 예시입니다. 실제 서버·AI·에이전트에 연결하지 않습니다.'

export function copy<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T
}

export function example<T>(id: keyof typeof contractExamples): T {
  return copy(contractExamples[id]) as T
}

/** Avoid keeping user-entered passwords in browser storage, including in mock mode. */
export async function passwordFingerprint(value: string): Promise<string> {
  const digest = await crypto.subtle.digest(
    'SHA-256',
    new TextEncoder().encode(value),
  )
  return `sha256-${Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, '0')).join('')}`
}

export interface StoredAccount {
  profile: Account
  password_fingerprint: string
  /** 임시 비밀번호로 로그인해 비밀번호를 바꿔야 한다(ACC-A02 must_change_password) */
  must_change_password?: boolean
  /** 받아 두고 아직 쓰지 않은 임시 비밀번호(ACC-A07). 계정마다 하나이고 원문은 남기지 않는다. */
  temporary_password?: { fingerprint: string; expires_at: string }
}

export interface StoredInvitation extends Invitation {
  token: string
}

// 문서별 검토·승인(DOC-A13~A21) 상태. 제출 이력은 응답을 만들 때 붙인다.
export type StoredWorkingCopy = Omit<DocumentWorkingCopy, 'submissions'>
export interface StoredSubmission extends DocumentReviewSubmission {
  content: string
  review: DocumentBeeReview
}

export interface MockProject {
  project: Project
  members: Member[]
  documents: Document[]
  versions: DocumentVersion[]
  working_copies: StoredWorkingCopy[]
  submissions: StoredSubmission[]
  items: DesignItemVersion[]
  questions: PlanningQuestion[]
  /** 설계 초안 생성 작업과 그 후보(PLN-A05). 후보를 채택해야 설계 항목이 된다. */
  draft_jobs?: DraftJob[]
  baselines: Baseline[]
  proposals: ChangeProposal[]
  /** 팀장에게 가지 않는 작성 중 변경 제안과 그 비비 검토(CHG-A16~A20). 없으면 아직 만든 적이 없다. */
  change_drafts?: StoredChangeDraft[]
  comments: ChangeComment[]
  approvals: Approval[]
  tasks: TaskDetail[]
  revisions: TaskPlanRevision[]
  validations: Validation[]
  packets: ContextPacket[]
  records: ContextRecord[]
  meetings: Meeting[]
  entries: MeetingEntry[]
  agents: AgentConnection[]
  activities: Activity[]
  /** 스스로 나간 계정. 로그인만으로는 되살리지 않고 메일 링크(MEM-A05)로만 다시 참여한다(Q05 4). */
  left_account_ids?: string[]
  /**
   * 실행 중인 비비 문서 검토(서버 document_bee_review_request RUNNING). BEE-A04가 읽는다.
   * 예시 DOC-A16은 한 요청 안에서 바로 끝나서 평소에는 비어 있다. 검토 중 표시를 보려면 여기에 넣는다.
   */
  running_bee_reviews?: { document_id: string; started_at: string }[]
}

export interface MockDatabase {
  // 활동에 행위자 종류가 생겨 2로 올렸다. 옛 형식으로 저장된 예시 데이터는 새로 만든다.
  schema_version: 2
  next_id: number
  session_account_id: string | null
  accounts: StoredAccount[]
  projects: MockProject[]
  invitations: StoredInvitation[]
  tokens: (AgentToken & { account_id: string })[]
  notifications: (Notification & { account_id: string })[]
  /** 비밀번호 찾기(ACC-A07) 요청 기록. 서버처럼 맞지 않은 요청도 남겨 다시 요청 간격과 이메일당 한도를 센다. */
  temporary_password_requests?: { email: string; created_at: string }[]
  pending_validations: Record<string, number>
}

export const now = () => new Date().toISOString()

export function nextId(database: MockDatabase): string {
  return String(database.next_id++)
}

type TrailRow = [
  type: string,
  actor: Pick<Activity, 'actor_type' | 'actor_account_id' | 'actor_name'>,
  at: string,
]
const trailPeople = {
  proposer: {
    actor_type: 'HUMAN',
    actor_account_id: '12',
    actor_name: '김동료',
  },
  leader: { actor_type: 'HUMAN', actor_account_id: '11', actor_name: '이팀장' },
  agent: {
    actor_type: 'AGENT',
    actor_account_id: '13',
    actor_name: '민수 에이전트',
  },
  bee: { actor_type: 'BEE' },
  system: { actor_type: 'SYSTEM' },
} as const
function proposalTrail(): Activity[] {
  const rows: TrailRow[] = [
    ['CHANGE_PROPOSAL_SUBMITTED', trailPeople.proposer, '2026-10-30T03:00:00Z'],
    ['CHANGE_PROPOSAL_COMMENTED', trailPeople.agent, '2026-10-30T03:05:00Z'],
    [
      'CHANGE_PROPOSAL_REVIEW_STARTED',
      trailPeople.leader,
      '2026-10-30T03:10:00Z',
    ],
    ['CHANGE_PROPOSAL_COMMENTED', trailPeople.leader, '2026-10-30T03:12:00Z'],
    [
      'CHANGE_PROPOSAL_REVIEW_REQUESTED',
      trailPeople.bee,
      '2026-10-30T03:15:00Z',
    ],
    ['CHANGE_PROPOSAL_COMMENTED', trailPeople.proposer, '2026-10-30T03:20:00Z'],
    ['CHANGE_PROPOSAL_SUBMITTED', trailPeople.proposer, '2026-10-30T03:30:00Z'],
    [
      'CHANGE_PROPOSAL_REVIEW_STARTED',
      trailPeople.leader,
      '2026-10-30T03:40:00Z',
    ],
    ['CHANGE_PROPOSAL_COMMENTED', trailPeople.agent, '2026-10-30T03:45:00Z'],
    [
      'CHANGE_PROPOSAL_REVIEW_REQUESTED',
      trailPeople.system,
      '2026-10-30T03:50:00Z',
    ],
    ['CHANGE_PROPOSAL_COMMENTED', trailPeople.leader, '2026-10-30T03:55:00Z'],
    [
      'CHANGE_PROPOSAL_REVIEW_STARTED',
      trailPeople.leader,
      '2026-10-30T04:00:00Z',
    ],
  ]
  return rows.map(([type, actor, created_at], index) => ({
    id: String(1030 + index),
    type,
    ...actor,
    change_proposal_id: '71',
    target_type: 'CHANGE_PROPOSAL',
    target_id: '71',
    result: 'SUCCESS',
    created_at,
  }))
}

export function blankProject(
  project: Project,
  account: Account,
  memberId: string,
): MockProject {
  return {
    project,
    members: [
      {
        id: memberId,
        account_id: account.id,
        name: account.name,
        role: 'LEADER',
        role_label: '팀장',
        color: '#e5b743',
        agent_connected: false,
      },
    ],
    documents: [],
    versions: [],
    working_copies: [],
    submissions: [],
    items: [],
    questions: [],
    baselines: [],
    proposals: [],
    comments: [],
    approvals: [],
    tasks: [],
    revisions: [],
    validations: [],
    packets: [],
    records: [],
    meetings: [],
    entries: [],
    agents: [],
    activities: [],
  }
}

// 활동 타임라인의 보기와 '이전 활동 더 보기'를 볼 수 있게 사람·에이전트·비비 활동을 한 쪽(20개)보다 많이 둔다.
// 유형은 공통 계약 6.2의 작업·검증·문서·기준·변경 제안·회의·프로젝트·멤버·에이전트 활동을 섞는다.
function seededActivities(account: Account, created: string): Activity[] {
  const actors: Partial<Activity>[] = [
    { actor_type: 'HUMAN', actor_account_id: '12', actor_name: '김동료' },
    {
      actor_type: 'AGENT',
      actor_account_id: '12',
      agent_session_id: '53',
      actor_name: '김동료 노트북',
    },
    {
      actor_type: 'AGENT',
      actor_account_id: '14',
      agent_session_id: '52',
      actor_name: '박팀원 노트북',
    },
    {
      actor_type: 'HUMAN',
      actor_account_id: account.id,
      actor_name: account.name,
    },
    { actor_type: 'BEE' },
  ]
  // 이 시드의 변경 제안 활동은 제안 71 링크를 달지 않는다. 제안 71 의 이력은 아래 proposalTrail()(303)이 따로 둔다.
  // 둘 다 대시보드 타임라인에 나오므로 시각은 이 시드 마지막(2026-10-29T03:00Z)보다 뒤로 두었다.
  const rows: [string, Partial<Activity>][] = [
    ['MEMBER_JOINED', { target_type: 'MEMBER', target_id: '22' }],
    ['AGENT_CONNECTED', { target_type: 'AGENT_SESSION', target_id: '53' }],
    ['AGENT_CONNECTED', { target_type: 'AGENT_SESSION', target_id: '52' }],
    ['TASK_CREATED', { task_id: '502' }],
    ['CHANGE_PROPOSAL_SUBMITTED', {}],
    ['TASK_PLAN_REVISED', { task_id: '502' }],
    ['VALIDATION_FINISHED', { task_id: '502' }],
    ['VALIDATION_FINISHED', { task_id: '503', result: 'FAILURE' }],
    ['DOCUMENT_APPROVED', {}],
    ['CHANGE_PROPOSAL_COMMENTED', {}],
    ['TASK_ASSIGNEE_CHANGED', { task_id: '502' }],
    ['TASK_STARTED', { task_id: '502' }],
    ['TASK_WAITING', { task_id: '503' }],
    ['BASELINE_CREATED', { baseline_id: '401' }],
    ['CHANGE_PROPOSAL_REVIEW_REQUESTED', {}],
    ['DOCUMENT_REGISTERED', {}],
    ['TASK_COMPLETION_REPORTED', { task_id: '502' }],
    ['TASK_RESUMED', { task_id: '503' }],
    // 제외된 멤버(29)는 팀원 목록에 없다
    ['MEMBER_REMOVED', { target_type: 'MEMBER', target_id: '29' }],
    ['CHANGE_PROPOSAL_COMMENTED', {}],
    ['LEADER_TRANSFERRED', { target_type: 'MEMBER', target_id: '21' }],
    ['TASK_COMPLETED', { task_id: '502' }],
    ['VALIDATION_FINISHED', { task_id: '503' }],
    ['MEETING_CLOSED', {}],
  ]
  return rows.map(([type, extra], index) => ({
    id: String(1002 + index),
    type,
    actor_type: 'SYSTEM',
    ...actors[index % actors.length],
    result: 'SUCCESS',
    ...extra,
    created_at: new Date(
      Date.parse(created) + (index + 1) * 6 * 3_600_000,
    ).toISOString(),
  }))
}

function seededProject(
  id: string,
  approved: boolean,
  account: Account,
): MockProject {
  const first = id === '101'
  const created = '2026-10-23T03:00:00Z'
  const state = blankProject(
    {
      id,
      name: first ? '우리 팀 협업 서비스' : '동네 책방 예약',
      description: first
        ? '승인 설계를 함께 관리하는 예시 프로젝트'
        : '새로운 기획과 문서를 준비하는 예시 프로젝트',
      my_role: 'LEADER',
      created_at: created,
      updated_at: created,
    },
    account,
    first ? '21' : '23',
  )
  state.members.push({
    id: first ? '22' : '24',
    account_id: '12',
    name: '김동료',
    role: 'MEMBER',
    domain: '화면',
    role_label: '프론트엔드',
    color: '#79a48a',
    agent_connected: false,
  })
  if (first)
    state.members.push(
      {
        id: '25',
        account_id: '13',
        name: '최팀원',
        role: 'MEMBER',
        domain: '디자인',
        role_label: '디자인',
        color: '#8f7ac8',
        agent_connected: false,
      },
      // 회의실 '관련자 추가' 예시: 회의에 아직 없는 팀원
      {
        id: '26',
        account_id: '14',
        name: '박팀원',
        role: 'MEMBER',
        domain: 'API',
        role_label: '백엔드',
        color: '#5f9b7a',
        agent_connected: false,
      },
    )
  const labels = [
    ['PLAN', '기획안'],
    ['ERD', 'ERD'],
    ['REQUIREMENTS', '요구사항 명세서'],
    ['API_SPEC', 'API 명세서'],
  ] as const
  labels.forEach(([kind, title], index) => {
    const documentId = String((first ? 201 : 221) + index)
    const versionId = String((first ? 211 : 231) + index)
    const content = `# ${title}\n\n${state.project.name}\n\n${kind === 'API_SPEC' ? 'POST /api/auth/login\n요청: email, password\n응답: id, name' : kind === 'ERD' ? 'Account(id, email, name)\nProject(id, name)\nMember(account_id, project_id, role)' : kind === 'REQUIREMENTS' ? 'REQ-001 로그인한 사용자는 참여 프로젝트를 조회한다.\nREQ-002 팀장만 설계 변경을 승인한다.\nREQ-005 초대받은 사용자는 링크로 프로젝트에 참여한다.' : '목표: 팀이 같은 승인 설계를 기준으로 작업한다.\n사용자: 인간 팀장, 팀원\n범위: 문서·계획·변경 검토'}\n`
    const version: DocumentVersion = {
      id: versionId,
      document_id: documentId,
      version_no: 1,
      status: approved ? 'APPROVED' : 'DRAFT',
      file_name: `${kind.toLowerCase()}.md`,
      media_type: 'text/markdown',
      content_hash: `sha256:mock-${versionId}`,
      content,
      created_by: account.id,
      is_current_baseline: approved,
      created_at: created,
    }
    state.versions.push(version)
    state.documents.push({
      id: documentId,
      project_id: id,
      kind,
      title,
      domain: '공통',
      ...(approved ? { approved_version: version } : { latest_draft: version }),
    })
    // 작성 건: 기준이 있는 프로젝트는 승인 완료, 준비 중 프로젝트는 비비 검토 전
    state.working_copies.push({
      document_id: documentId,
      owner_account_id: account.id,
      revision: 1,
      status: approved ? 'APPROVED' : 'EDITING',
      content,
      file_name: `${kind.toLowerCase()}.md`,
    })
  })
  state.items.push({
    id: first ? '311' : '331',
    design_item_id: first ? '301' : '321',
    key: 'REQ-001',
    kind: 'REQUIREMENT',
    version_no: 1,
    status: approved ? 'APPROVED' : 'DRAFT',
    schema_version: 1,
    content: { description: '로그인한 사용자는 참여 프로젝트를 조회한다.' },
    source: {
      document_id: state.documents[2].id,
      document_version_id: state.versions[2].id,
      version_no: 1,
      location: 'REQ-001',
    },
    content_hash: `sha256:mock-item-${id}`,
  })
  state.questions.push({
    id: first ? '61' : '62',
    question: '프로젝트 삭제 권한은 인간 팀장만 가지나요?',
    kind: 'CLARIFICATION',
    question_type: 'SHORT_ANSWER',
    answer_format: { type: 'SHORT_ANSWER', max_length: 200 },
    status: approved ? 'ANSWERED' : 'OPEN',
    evidence_status: 'REVIEW_CANDIDATE',
    summary: '삭제할 수 있는 사람이 문서마다 달라요.',
    source: state.items[0].source,
    ...(approved
      ? {
          answer: '인간 팀장만 삭제합니다.',
          answer_payload: {
            type: 'SHORT_ANSWER' as const,
            text: '인간 팀장만 삭제합니다.',
          },
          answered_by: account.id,
          answered_at: created,
        }
      : {}),
  })
  // 기준 발행 전 프로젝트에는 질문 유형별 예시(선택 단일·다중, 서술, 근거 미확인, 닫은 질문)를 더 둔다.
  if (!approved)
    state.questions.push(
      {
        id: '63',
        question: '프로젝트 삭제는 누가 할 수 있나요?',
        kind: 'MISSING_CANDIDATE',
        question_type: 'CHOICE',
        answer_format: {
          type: 'CHOICE',
          options: ['팀장만', '팀장과 팀원 모두'],
          multiple: false,
          allow_other: true,
        },
        status: 'OPEN',
        evidence_status: 'REVIEW_CANDIDATE',
        summary: '요구사항에는 삭제 기능만 있고 권한이 비어 있어요.',
        source: state.items[0].source,
      },
      {
        id: '64',
        question: '알림은 어떤 방식으로 보낼까요?',
        kind: 'CLARIFICATION',
        question_type: 'CHOICE',
        answer_format: {
          type: 'CHOICE',
          options: ['앱 안 알림', '이메일', '메신저'],
          multiple: true,
          allow_other: false,
        },
        status: 'OPEN',
        evidence_status: 'REVIEW_CANDIDATE',
        summary: '알림 수단이 문서마다 다르게 적혀 있어요.',
        source: state.items[0].source,
      },
      {
        id: '65',
        question:
          '기획서의 "팀원은 문서를 직접 승인한다"와 요구사항의 "승인은 팀장만 한다"가 달라요. 어느 쪽이 맞나요?',
        kind: 'CONFLICT',
        question_type: 'ESSAY',
        answer_format: { type: 'ESSAY', max_length: 500 },
        status: 'OPEN',
        evidence_status: 'UNVERIFIED',
        summary: '두 문서의 승인 권한이 서로 달라요.',
        source: state.items[0].source,
      },
      {
        id: '66',
        question: '다국어를 지원할 계획이 있나요?',
        kind: 'MISSING_CANDIDATE',
        question_type: 'SHORT_ANSWER',
        answer_format: { type: 'SHORT_ANSWER', max_length: 100 },
        status: 'DISMISSED',
        evidence_status: 'REVIEW_CANDIDATE',
        source: state.items[0].source,
      },
    )
  if (!approved) return state
  const baseline: Baseline = {
    id: '401',
    project_id: id,
    version_no: 1,
    documents: state.versions.map((version) => ({
      document_id: version.document_id,
      document_version_id: version.id,
    })),
    items: [{ design_item_id: '301', design_item_version_id: '311' }],
    approved_by: account.id,
    created_at: created,
  }
  state.baselines.push(baseline)
  state.project.current_baseline = {
    id: baseline.id,
    version_no: baseline.version_no,
    created_at: created,
  }
  const draft = {
    ...copy(state.versions[0]),
    id: '215',
    version_no: 2,
    status: 'DRAFT' as const,
    content: `${state.versions[0].content}\n미정: 초대 만료 안내 문구`,
    created_by: '12',
    is_current_baseline: false,
    change_proposal_id: '71',
  }
  state.versions.push(draft)
  state.documents[0].latest_draft = draft
  const task = example<TaskDetail>('TSK-A01')
  task.assignee_member_id = '21'
  task.current_plan_revision.plan = {
    ...task.current_plan_revision.plan,
    title: '프로젝트 목록 화면 구현',
    goal: '승인 요구사항에 맞춰 참여 프로젝트를 표시한다.',
    included_scope: ['참여 프로젝트 조회와 빈 화면'],
    approach: '승인 요구사항 REQ-001을 따른다.',
    design_refs: [
      {
        design_item_id: '301',
        design_item_version_id: '311',
        relation: 'IMPLEMENTS',
      },
    ],
    context_packet_id: '701',
  }
  task.validation_status = 'PASS'
  const validation = {
    ...example<Validation>('VAL-A02'),
    trigger: 'AGENT' as const,
    related_task_refs: [],
  }
  task.validation = validation
  state.tasks.push(task)
  state.revisions.push(copy(task.current_plan_revision))
  state.validations.push(validation)
  // 회의실 예시: TASK-001과 만료 초대 안내가 부딪히는 김동료의 작업
  const other = example<TaskDetail>('TSK-A01')
  other.id = '502'
  other.key = 'TASK-002'
  other.assignee_member_id = '22'
  other.current_plan_revision = {
    ...other.current_plan_revision,
    id: '512',
    task_id: '502',
    created_by: '12',
    plan: {
      ...other.current_plan_revision.plan,
      title: '초대 화면 정보 바꾸기',
      goal: '초대 화면에 프로젝트와 만료 정보를 보여 준다.',
      included_scope: ['초대 카드와 만료 안내'],
      excluded_scope: [],
      approach: '만료된 초대에는 새 링크를 요청하라고 따로 안내한다.',
      completion_criteria: ['만료된 초대에 새 링크 요청 안내를 보여 준다'],
    },
  }
  delete other.current_plan_revision.plan.context_packet_id
  // 사무실 에이전트 예시(D37): 김동료의 작업은 팀장 승인을 기다린다.
  other.status = 'WAITING'
  other.waiting_reason = 'APPROVAL'
  state.tasks.push(other)
  state.revisions.push(copy(other.current_plan_revision))
  // 수정안 예시 데이터: TASK-003은 박팀원의 백엔드 작업
  const third = example<TaskDetail>('TSK-A01')
  third.id = '503'
  third.key = 'TASK-003'
  third.assignee_member_id = '26'
  third.current_plan_revision = {
    ...third.current_plan_revision,
    id: '513',
    task_id: '503',
    created_by: '14',
    plan: {
      ...third.current_plan_revision.plan,
      title: '참여 프로젝트 목록 API 구현',
      goal: '로그인한 사용자가 참여한 프로젝트를 돌려준다.',
      included_scope: ['참여 프로젝트 조회 API'],
      excluded_scope: [],
      approach: '승인 요구사항 REQ-001을 따른다.',
      completion_criteria: ['빈 목록과 여러 프로젝트를 모두 돌려준다'],
    },
  }
  delete third.current_plan_revision.plan.context_packet_id
  // 사무실 에이전트 예시(D37): 박팀원의 에이전트가 진행 중인 작업이다.
  third.status = 'IN_PROGRESS'
  third.validation_status = 'PASS'
  state.tasks.push(third)
  state.revisions.push(copy(third.current_plan_revision))
  const packet = example<ContextPacket>('CTX-A01')
  packet.design_items = copy(state.items)
  packet.related_tasks = []
  packet.sources = [state.items[0].source!]
  state.packets.push(packet)
  state.records.push({
    ...example<ContextRecord>('CTX-A03'),
    summary: '프로젝트 목록의 승인 근거를 확인했어요.',
    detail: 'REQ-001을 기준으로 목록과 빈 상태를 준비합니다.',
  })
  // 변경 제안 예시(수정안 PNL-04): 김동료가 올린 설계 항목 제안. 문서 변경은 공용 문서함에서 다룬다.
  state.proposals.push({
    ...example<ChangeProposal>('CHG-A01'),
    title: '프로젝트 목록 빈 화면 안내',
    reason: '참여한 프로젝트가 없을 때 무엇을 하면 되는지 알려 줘야 해요.',
    status: 'IN_REVIEW',
    proposer_account_id: '12',
    items: [
      {
        operation: 'UPDATE',
        design_item_id: '301',
        base_design_item_version_id: '311',
        schema_version: 1,
        proposed_content: {
          description:
            '로그인한 사용자는 참여 프로젝트를 조회하고, 없으면 새 프로젝트를 만들거나 초대를 기다리라는 안내를 본다.',
        },
      },
    ],
    documents: [],
  })
  // 회의실 예시(수정안 PNL-06): 작업이 부딪혀 비비가 연 회의.
  // 비비 메시지의 작성자 구분이 계약에 없어 비비가 쓴 기록 종류(BEE_ANALYSIS)로 둔다.
  const meeting = example<Meeting>('MTG-A01')
  delete meeting.change_proposal_id
  state.meetings.push({
    ...meeting,
    title: '초대 안내 문구 검토',
    origin: 'BEE_CONFLICT',
    task_ids: ['501', '502'],
    conflict_summary: '만료된 초대를 어떻게 안내할지 정해 주세요',
    status: 'OPEN',
    participants: ['21', '22', '25'].map((member_id) => ({
      member_id,
      status: 'JOINED' as const,
    })),
    created_at: '2026-10-28T01:40:00Z',
  })
  // 끝난 회의 예시(PNL-06 수정안 10-29 '진행 중 / 끝난 회의')
  state.meetings.push({
    ...meeting,
    id: '902',
    title: 'API 오류 응답 형식 통일',
    origin: 'BEE_CONFLICT',
    task_ids: ['503'],
    conflict_summary: 'API 오류 응답 형식을 하나로 맞춰 주세요',
    status: 'CLOSED',
    closed_at: '2026-10-26T06:20:00Z',
    closed_by: '11',
    participants: ['21', '26'].map((member_id) => ({
      member_id,
      status: 'JOINED' as const,
    })),
    created_at: '2026-10-26T05:40:00Z',
  })
  const opened = {
    origin: 'TEMPLATE' as const,
    text: "작업이 부딪혀서 회의를 열었어요.\n- TASK-002 초대 화면 정보 바꾸기(김동료)는 만료된 초대에 '새 링크를 요청하세요' 안내를 따로 보여 줘요.\n- TASK-001 프로젝트 목록 화면 구현(이팀장)은 쓸 수 없는 초대를 원인 없이 '찾을 수 없어요'로 보여 줘요.\n- 요구사항 명세서 v1 REQ-005에는 어느 쪽인지 없어요. 어떻게 맞출지 정해 주세요.",
    sources: [
      {
        document_id: '203',
        document_version_id: '213',
        version_no: 1,
        location: 'REQ-005',
      },
    ],
    unresolved: [],
    usage: [],
  }
  const said = (
    id: string,
    author: string,
    minute: string,
    text: string,
  ): MeetingEntry => ({
    id,
    meeting_id: '901',
    kind: 'TEXT',
    text,
    author_account_id: author,
    created_at: `2026-10-28T01:${minute}:00Z`,
  })
  state.entries.push(
    {
      id: '911',
      meeting_id: '901',
      kind: 'BEE_ANALYSIS',
      text: opened.text,
      generation: opened,
      created_at: '2026-10-28T01:40:00Z',
    },
    said(
      '912',
      '13',
      '46',
      "원인은 숨기고 '팀장에게 새 초대 링크를 요청하세요' 한 줄만 두면 두 화면이 같아져요.",
    ),
    said('913', '12', '48', 'TASK-002에서 만료 전용 안내는 뺄게요.'),
    said(
      '914',
      account.id,
      '52',
      '그렇게 가요. 요구사항에 이 문장을 넣어 주세요.',
    ),
  )
  state.agents.push({
    id: '51',
    member_id: '21',
    connected: false,
    last_report_at: created,
    status_check_required: false,
  })
  // 팀원 예시 에이전트는 사무실 화면 확인용으로 연결됨을 두되(사용자 결정 2026-11-03, D37), 사용자가 mock에서 새로
  // 발급한 토큰은 연결을 만들지 않는다. 상태는 AGT-A05(-75) 정의를 따른다(S15P31A108-341). 김동료(TASK-002 승인
  // 대기)는 연결됨, 박팀원은 연결됐지만 진행 중 작업(TASK-003)을 맡은 채 30분 넘게 통신이 없어 연결 미확인이다.
  // 최팀원과 내 에이전트(51)는 연결이 끊겨(connected=false) 작업 현황에 세션이 없고 사무실 충전 자리에 보인다.
  // 서버는 끊긴 줄에 status_check_required를 주지 않는다.
  state.agents.push(
    {
      id: '52',
      member_id: '26',
      connected: true,
      last_report_at: '2026-10-28T05:20:00Z',
      status_check_required: true,
    },
    {
      id: '53',
      member_id: '22',
      connected: true,
      last_report_at: '2026-10-28T05:10:00Z',
      status_check_required: false,
    },
    {
      id: '54',
      member_id: '25',
      connected: false,
      last_report_at: '2026-10-27T09:00:00Z',
      status_check_required: false,
    },
  )
  for (const member of state.members)
    if (member.id === '22' || member.id === '26') member.agent_connected = true
  state.activities = [
    {
      id: '1001',
      type: 'MOCK_PROJECT_PREPARED',
      actor_type: 'HUMAN',
      actor_account_id: account.id,
      actor_name: account.name,
      result: 'SUCCESS',
      created_at: created,
    },
    // 변경 제안 71 의 지나온 길(303). 행위자 4종과 2페이지 이상이 보이게 한다.
    ...proposalTrail(),
    ...(first ? seededActivities(account, created) : []),
  ]
  return state
}

export function createMockDatabase(): MockDatabase {
  const account: Account = {
    id: '11',
    email: DEMO_EMAIL,
    name: '이팀장',
    avatar: 'bee-yellow',
    created_at: '2026-10-23T03:00:00Z',
  }
  return {
    schema_version: 2,
    next_id: 2000,
    session_account_id: null,
    accounts: [
      {
        profile: account,
        password_fingerprint:
          'sha256-ac434a1b7e6298f11544d43e5a52307bc5ca5f553050be5f59e47796e110928b',
      },
      {
        profile: {
          id: '12',
          email: 'member@beez.local',
          name: '김동료',
          avatar: 'bee-green',
          created_at: now(),
        },
        password_fingerprint:
          'sha256-ac434a1b7e6298f11544d43e5a52307bc5ca5f553050be5f59e47796e110928b',
      },
      {
        profile: {
          id: '13',
          email: 'designer@beez.local',
          name: '최팀원',
          avatar: 'bee-purple',
          created_at: now(),
        },
        password_fingerprint:
          'sha256-ac434a1b7e6298f11544d43e5a52307bc5ca5f553050be5f59e47796e110928b',
      },
      {
        profile: {
          id: '14',
          email: 'backend@beez.local',
          name: '박팀원',
          avatar: 'bee-green',
          created_at: now(),
        },
        password_fingerprint:
          'sha256-ac434a1b7e6298f11544d43e5a52307bc5ca5f553050be5f59e47796e110928b',
      },
    ],
    projects: [
      seededProject('101', true, account),
      seededProject('102', false, account),
    ],
    invitations: [],
    tokens: [],
    notifications: [
      {
        id: '1101',
        account_id: '11',
        project_id: '101',
        type: 'CHANGE_REVIEW_REQUESTED',
        title: '프로젝트 목록 빈 화면 안내 검토 요청',
        body: '김동료가 REQ-001을 고치자고 제안했어요.',
        target_type: 'CHANGE_PROPOSAL',
        target_id: '71',
        created_at: now(),
      },
      ...['11', '12', '13'].map((accountId, index) => ({
        id: String(1102 + index),
        account_id: accountId,
        project_id: '101',
        type: 'MEETING_OPENED',
        title: '비비가 회의를 열었어요',
        body: '초대 안내 문구 검토 · TASK-001과 TASK-002의 만료 초대 안내가 달라요.',
        target_type: 'MEETING' as const,
        target_id: '901',
        created_at: now(),
      })),
    ],
    pending_validations: {},
  }
}

let memory: MockDatabase | undefined

export function loadMockDatabase(): MockDatabase {
  if (memory) return memory
  try {
    const stored = localStorage.getItem(MOCK_STORAGE_KEY)
    if (stored) {
      const parsed = JSON.parse(stored) as MockDatabase
      if (
        parsed.schema_version === 2 &&
        Array.isArray(parsed.accounts) &&
        Array.isArray(parsed.projects)
      )
        memory = parsed
    }
  } catch {
    /* Storage is optional; the mock remains usable in memory. */
  }
  memory ??= createMockDatabase()
  return memory
}

export function saveMockDatabase(database: MockDatabase): void {
  memory = database
  try {
    localStorage.setItem(MOCK_STORAGE_KEY, JSON.stringify(database))
  } catch {
    /* Keep this browser session usable when storage is full or disabled. */
  }
}

/** Tests and the explicit mock reset action can restore deterministic fixtures. */
export function resetMockDatabase(): MockDatabase {
  const database = createMockDatabase()
  saveMockDatabase(database)
  return database
}
