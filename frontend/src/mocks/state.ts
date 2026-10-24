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
import { contractExamples } from './contract-examples'

export const MOCK_STORAGE_KEY = 'beez.mock.api.v1'
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
}

export interface StoredInvitation extends Invitation {
  token: string
}

export interface MockProject {
  project: Project
  members: Member[]
  documents: Document[]
  versions: DocumentVersion[]
  items: DesignItemVersion[]
  questions: PlanningQuestion[]
  baselines: Baseline[]
  proposals: ChangeProposal[]
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
}

export interface MockDatabase {
  schema_version: 1
  next_id: number
  session_account_id: string | null
  accounts: StoredAccount[]
  projects: MockProject[]
  invitations: StoredInvitation[]
  tokens: (AgentToken & { account_id: string })[]
  notifications: (Notification & { account_id: string })[]
  reset_tokens: {
    token: string
    account_id: string
    expires_at: string
    used: boolean
  }[]
  pending_validations: Record<string, number>
}

export const now = () => new Date().toISOString()

export function nextId(database: MockDatabase): string {
  return String(database.next_id++)
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
  const labels = [
    ['PLAN', '기획안'],
    ['ERD', 'ERD'],
    ['REQUIREMENTS', '요구사항 명세서'],
    ['API_SPEC', 'API 명세서'],
  ] as const
  labels.forEach(([kind, title], index) => {
    const documentId = String((first ? 201 : 221) + index)
    const versionId = String((first ? 211 : 231) + index)
    const content = `# ${title}\n\n${state.project.name}\n\n${kind === 'API_SPEC' ? 'POST /api/auth/login\n요청: email, password\n응답: id, name' : kind === 'ERD' ? 'Account(id, email, name)\nProject(id, name)\nMember(account_id, project_id, role)' : kind === 'REQUIREMENTS' ? 'REQ-001 로그인한 사용자는 참여 프로젝트를 조회한다.\nREQ-002 팀장만 설계 변경을 승인한다.' : '목표: 팀이 같은 승인 설계를 기준으로 작업한다.\n사용자: 인간 팀장, 팀원\n범위: 문서·계획·변경 검토'}\n`
    const version: DocumentVersion = {
      id: versionId,
      document_id: documentId,
      version_no: 1,
      status: approved ? 'APPROVED' : 'DRAFT',
      file_name: `${kind.toLowerCase()}.md`,
      media_type: 'text/markdown',
      content_hash: `sha256:mock-${versionId}`,
      content,
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
    source: state.items[0].source,
    ...(approved
      ? {
          answer: '인간 팀장만 삭제합니다.',
          answered_by: account.id,
          answered_at: created,
        }
      : {}),
  })
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
  state.proposals.push({
    ...example<ChangeProposal>('CHG-A01'),
    title: '초대 안내 문구 보완',
    reason: '팀원이 초대 만료 상태를 이해할 수 있도록 문구를 보완합니다.',
    status: 'IN_REVIEW',
    documents: [
      {
        document_id: '203',
        base_document_version_id: '213',
        after_content: `${state.versions[2].content}\nREQ-003 만료된 초대는 새 초대를 요청하도록 안내한다.`,
      },
    ],
    items: [],
  })
  state.meetings.push({
    ...example<Meeting>('MTG-A01'),
    title: '초대 안내 문구 검토',
  })
  state.entries.push({
    ...example<MeetingEntry>('MTG-A06'),
    text: '예시 회의 기록: 초대가 만료됐을 때 새 링크를 요청하도록 안내해요.',
  })
  state.agents.push({
    id: '51',
    member_id: '21',
    connected: false,
    last_report_at: created,
    status_check_required: true,
  })
  state.activities = [
    {
      id: '1001',
      type: 'MOCK_PROJECT_PREPARED',
      actor_account_id: account.id,
      result: 'SUCCESS',
      created_at: created,
    },
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
    schema_version: 1,
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
        title: '초대 안내 문구 검토 요청',
        body: '브라우저에 저장한 예시 변경 제안입니다.',
        target_type: 'CHANGE_PROPOSAL',
        target_id: '71',
        created_at: now(),
      },
    ],
    reset_tokens: [],
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
        parsed.schema_version === 1 &&
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
