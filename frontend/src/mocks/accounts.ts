import type { Account, Project } from '../api/contracts/common'
import {
  fail,
  findById,
  omit,
  page,
  requireAccount,
  requireLeader,
  requireProject,
  requiredString,
} from './helpers'
import type { MockContext, MockReply } from './helpers'
import {
  confirmVerification,
  sendVerification,
  verifiedTokenFor,
} from './emailVerifications'
import { blankProject, nextId, now, passwordFingerprint } from './state'
import type { MockDatabase, StoredAccount } from './state'
import { reassignTask } from './tasks'

// 비밀번호 찾기(ACC-A07)는 서버 기본값(application.yml beez.password-recovery)과 같다: 유효 30분, 같은 이메일 다시 요청 60초, 1시간 5번.
const TEMPORARY_TTL_MS = 30 * 60_000
const FORGOT_RESEND_MS = 60_000
const FORGOT_LIMIT = 5
const FORGOT_WINDOW_MS = 60 * 60_000
const FORGOT_MESSAGE = '입력한 정보가 맞으면 임시 비밀번호를 메일로 보냅니다.'

// 서버 PasswordRecoveryService.generate처럼 헷갈리는 글자(0·O·1·l·I)를 뺀 영문·숫자 12자. 영문과 숫자가 모두 들어가야 한다.
function temporaryPassword(): string {
  const alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789'
  for (;;) {
    const value = Array.from(
      crypto.getRandomValues(new Uint8Array(12)),
      (byte) => alphabet[byte % alphabet.length],
    ).join('')
    if (/[A-Za-z]/.test(value) && /\d/.test(value)) return value
  }
}

function storedAccount(context: MockContext): StoredAccount {
  const id = requireAccount(context).id
  return context.database.accounts.find((item) => item.profile.id === id)!
}

// 로그인·내 계정 조회·프로필 수정(ACC-A02·A04·A05) 응답에는 must_change_password를 싣는다. 가입(ACC-A01) 응답에는 없다(PR #118).
const withPasswordState = (stored: StoredAccount) => ({
  ...stored.profile,
  must_change_password: !!stored.must_change_password,
})

// 회원 탈퇴를 막는 프로젝트(ACC-A10·A13): 본인 말고 활성 팀원이 있는 프로젝트의 팀장. member_count는 본인을 뺀 수다.
function blockedProjects(database: MockDatabase, accountId: string) {
  return database.projects.flatMap(({ project, members }) => {
    const others = members.filter((item) => item.account_id !== accountId)
    return members.some(
      (item) => item.account_id === accountId && item.role === 'LEADER',
    ) && others.length
      ? [
          {
            project_id: project.id,
            name: project.name,
            my_role: 'LEADER',
            member_count: others.length,
          },
        ]
      : []
  })
}

// 서버 PasswordPolicy와 같은 검사: 8자 이상, UTF-8 72바이트 이하, 영문과 숫자 포함(PR #72 mock/live 전수 대조 LIVE-AMP-02).
async function password(value: unknown): Promise<string> {
  if (typeof value !== 'string' || value.length < 8)
    fail(400, 'validation_failed', 'password: 비밀번호는 8자 이상이어야 합니다')
  if (new TextEncoder().encode(value).length > 72)
    fail(
      400,
      'validation_failed',
      'password: 비밀번호가 너무 깁니다 (UTF-8 72바이트 이하)',
    )
  if (!/[A-Za-z]/.test(value) || !/\d/.test(value))
    fail(
      400,
      'validation_failed',
      'password: 비밀번호는 영문과 숫자를 모두 포함해야 합니다',
    )
  return passwordFingerprint(value)
}

// Q05 1·4: 멤버십은 초대할 때가 아니라 초대받은 이메일로 로그인·가입할 때 만든다.
// 스스로 나간 프로젝트는 로그인만으로 되살리지 않는다(메일 링크를 열 때 MEM-A05로만).
function joinPendingInvitations(context: MockContext, account: Account) {
  for (const invitation of context.database.invitations) {
    if (
      invitation.invited_email !== account.email ||
      invitation.accepted_at ||
      invitation.revoked_at
    )
      continue
    const project = context.database.projects.find(
      (item) => item.project.id === invitation.project_id,
    )
    if (!project || project.left_account_ids?.includes(account.id)) continue
    if (!project.members.some((item) => item.account_id === account.id))
      project.members.push({
        id: nextId(context.database),
        account_id: account.id,
        name: account.name,
        role: 'MEMBER',
        agent_connected: false,
      })
    invitation.accepted_at = now()
  }
}

function projectView(context: MockContext, project: Project): Project {
  const member = context.database.projects
    .find((item) => item.project.id === project.id)
    ?.members.find((item) => item.account_id === context.account?.id)
  return { ...project, my_role: member?.role ?? 'MEMBER' }
}

export async function accountRoutes(
  id: string,
  context: MockContext,
): Promise<MockReply | undefined> {
  const { database, body, params, query } = context
  switch (id) {
    case 'ACC-A01': {
      const email = requiredString(body, 'email').toLowerCase()
      if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email))
        fail(400, 'validation_failed', '이메일 형식을 확인해 주세요.')
      const name = requiredString(body, 'name')
      // 서버 순서대로 비밀번호 규칙 → 인증 토큰 → 중복을 보고, 토큰은 가입이 성공한 뒤에만 소비한다(PR #114·#119).
      const password_fingerprint = await password(body.password)
      const consumeToken = verifiedTokenFor(body.verified_token, email)
      if (database.accounts.some((item) => item.profile.email === email))
        fail(409, 'email_already_exists', '이미 등록된 이메일이에요.')
      const profile: Account = {
        id: nextId(database),
        email,
        name,
        avatar: 'bee-yellow',
        created_at: now(),
      }
      database.accounts.push({ profile, password_fingerprint })
      consumeToken()
      database.session_account_id = profile.id
      joinPendingInvitations(context, profile)
      return { status: 201, data: profile }
    }
    case 'ACC-A11':
      return sendVerification(context)
    case 'ACC-A12':
      return confirmVerification(context)
    case 'ACC-A02': {
      const email = requiredString(body, 'email').toLowerCase()
      const fingerprint = await passwordFingerprint(String(body.password ?? ''))
      const stored = database.accounts.find(
        (item) => item.profile.email === email,
      )
      const temporary = stored?.temporary_password
      // 비밀번호가 틀려도 쓸 수 있는 임시 비밀번호와 맞으면 그것이 비밀번호가 되고 must_change_password가 붙는다
      // (서버 PasswordRecoveryService.redeem). 한 번 쓰면 닫힌다.
      if (
        stored &&
        stored.password_fingerprint !== fingerprint &&
        temporary?.fingerprint === fingerprint &&
        Date.parse(temporary.expires_at) > Date.now()
      ) {
        stored.password_fingerprint = fingerprint
        stored.must_change_password = true
        delete stored.temporary_password
      }
      if (!stored || stored.password_fingerprint !== fingerprint)
        fail(401, 'invalid_credentials', '이메일과 비밀번호를 확인해 주세요.')
      database.session_account_id = stored.profile.id
      joinPendingInvitations(context, stored.profile)
      return { data: withPasswordState(stored) }
    }
    case 'ACC-A03':
      database.session_account_id = null
      return { status: 204 }
    case 'ACC-A04':
      return { data: withPasswordState(storedAccount(context)) }
    case 'ACC-A05': {
      const stored = storedAccount(context)
      const account = stored.profile
      // 서버(AccountService.updateProfile)처럼 null·생략은 그대로 두고, 이름은 비울 수 없으며, 아바타 ""는 지운다.
      if (body.name == null && body.avatar == null)
        fail(
          400,
          'validation_failed',
          '변경할 필드(name, avatar)가 하나 이상 필요합니다',
        )
      if (body.name != null) account.name = requiredString(body, 'name')
      if (typeof body.avatar === 'string') {
        if (body.avatar.trim()) account.avatar = body.avatar.trim()
        else delete account.avatar
      }
      database.projects.forEach((project) =>
        project.members
          .filter((member) => member.account_id === account.id)
          .forEach((member) => {
            member.name = account.name
          }),
      )
      return { data: withPasswordState(stored) }
    }
    case 'ACC-A06': {
      const stored = storedAccount(context)
      // 임시 비밀번호로 로그인했으면 그 임시 비밀번호가 현재 비밀번호다.
      if (
        stored.password_fingerprint !==
        (await passwordFingerprint(String(body.current_password ?? '')))
      )
        fail(400, 'current_password_mismatch', '현재 비밀번호가 맞지 않습니다')
      stored.password_fingerprint = await password(body.new_password)
      // 바꾸면 must_change_password가 풀리고, 받아 두고 쓰지 않은 임시 비밀번호도 닫는다.
      delete stored.must_change_password
      delete stored.temporary_password
      return { status: 204 }
    }
    case 'ACC-A07': {
      const email = requiredString(body, 'email').toLowerCase()
      const name = requiredString(body, 'name')
      const requests = (database.temporary_password_requests ??= [])
      const sent = requests.filter((item) => item.email === email)
      const at = Date.now()
      if (
        sent.length &&
        at < Date.parse(sent.at(-1)!.created_at) + FORGOT_RESEND_MS
      )
        fail(429, 'rate_limited', '다시 요청은 잠시 뒤에 할 수 있습니다')
      if (
        sent.filter(
          (item) => Date.parse(item.created_at) > at - FORGOT_WINDOW_MS,
        ).length >= FORGOT_LIMIT
      )
        fail(
          429,
          'rate_limited',
          '이 이메일로 요청할 수 있는 횟수를 넘었습니다. 잠시 뒤에 다시 시도하세요',
        )
      requests.push({ email, created_at: now() })
      const stored = database.accounts.find(
        (item) =>
          item.profile.email === email && item.profile.name.trim() === name,
      )
      if (!stored) return { status: 202, data: { message: FORGOT_MESSAGE } }
      // 발급해도 기존 비밀번호는 그대로이고, 다시 발급하면 이전 임시 비밀번호는 무효다.
      // 예시 환경은 메일을 보내지 않아 안내 문구에 임시 비밀번호를 붙인다. 저장소에는 지문만 남긴다.
      const temporary = temporaryPassword()
      stored.temporary_password = {
        fingerprint: await passwordFingerprint(temporary),
        expires_at: new Date(at + TEMPORARY_TTL_MS).toISOString(),
      }
      return {
        status: 202,
        data: {
          message: `${FORGOT_MESSAGE} 예시 환경이라 메일을 보내지 않아요. 임시 비밀번호: ${temporary}`,
        },
      }
    }
    case 'ACC-A10': {
      const stored = storedAccount(context)
      const accountId = stored.profile.id
      // 세션은 유효하므로 401이 아니라 400이다(서버 AccountWithdrawalService).
      if (
        stored.password_fingerprint !==
        (await passwordFingerprint(String(body.password ?? '')))
      )
        fail(400, 'invalid_credentials', '비밀번호가 맞지 않습니다')
      const blocked = blockedProjects(database, accountId)
      if (blocked.length)
        fail(
          409,
          'leader_of_team_projects',
          '팀원이 있는 프로젝트의 팀장입니다. 팀장을 위임하거나 프로젝트를 삭제한 뒤에 탈퇴할 수 있습니다',
          { blocked_projects: blocked },
        )
      // 혼자 쓰는 프로젝트는 함께 지우고, 팀원으로 참여한 프로젝트에서는 빠진다. 맡은 작업의 담당자는 그대로 둔다
      // (서버 ProjectWithdrawalService). 에이전트 토큰은 모두 폐기한다.
      database.projects = database.projects.filter(
        (project) =>
          !project.members.every((member) => member.account_id === accountId),
      )
      database.projects.forEach((project) => {
        project.members = project.members.filter(
          (member) => member.account_id !== accountId,
        )
      })
      database.tokens
        .filter((token) => token.account_id === accountId)
        .forEach((token) => {
          token.revoked_at ??= now()
        })
      database.accounts = database.accounts.filter((item) => item !== stored)
      database.session_account_id = null
      return { status: 204 }
    }
    case 'ACC-A13': {
      const blocked = blockedProjects(database, requireAccount(context).id)
      return {
        data: { can_withdraw: !blocked.length, blocked_projects: blocked },
      }
    }
    case 'PRJ-A01': {
      const account = requireAccount(context)
      const created = now()
      const project: Project = {
        id: nextId(database),
        name: requiredString(body, 'name'),
        ...(typeof body.description === 'string'
          ? { description: body.description }
          : {}),
        my_role: 'LEADER',
        created_at: created,
        updated_at: created,
      }
      if (
        Object.keys(body).some((key) => !['name', 'description'].includes(key))
      )
        fail(
          400,
          'validation_failed',
          '프로젝트 API에는 이름과 소개만 전달해 주세요.',
        )
      database.projects.push(blankProject(project, account, nextId(database)))
      return { status: 201, data: project }
    }
    case 'PRJ-A02':
      return {
        data: page(
          database.projects
            .filter((project) =>
              project.members.some(
                (member) => member.account_id === requireAccount(context).id,
              ),
            )
            .map((project) => projectView(context, project.project)),
          query,
        ),
      }
    case 'PRJ-A03':
      return { data: projectView(context, requireProject(context).project) }
    case 'PRJ-A04': {
      requireLeader(context)
      const project = requireProject(context).project
      if (body.name !== undefined) project.name = requiredString(body, 'name')
      if (typeof body.description === 'string')
        project.description = body.description
      project.updated_at = now()
      return { data: projectView(context, project) }
    }
    case 'PRJ-A05': {
      requireLeader(context)
      const project = requireProject(context)
      if (body.confirm_name !== project.project.name)
        fail(
          400,
          'confirmation_mismatch',
          '프로젝트 이름을 정확하게 입력해 주세요.',
        )
      database.projects = database.projects.filter((item) => item !== project)
      database.tokens
        .filter((token) => token.project_id === project.project.id)
        .forEach((token) => {
          token.revoked_at = now()
        })
      return { status: 204 }
    }
    case 'PRJ-A06': {
      const project = requireProject(context)
      return {
        data: {
          required_documents: (
            ['PLAN', 'ERD', 'REQUIREMENTS', 'API_SPEC'] as const
          ).map((kind) => ({
            kind,
            uploaded: project.documents.some(
              (document) => document.kind === kind,
            ),
            approved: project.documents.some(
              (document) => document.kind === kind && document.approved_version,
            ),
          })),
          baseline_published: !!project.project.current_baseline,
          member_count: project.members.length,
          my_agent_connected: false,
        },
      }
    }
    case 'MEM-A01': {
      requireLeader(context)
      const project = requireProject(context)
      const email = requiredString(body, 'email').trim().toLowerCase()
      const invitee = database.accounts.find(
        (item) => item.profile.email === email,
      )
      if (
        invitee &&
        project.members.some((item) => item.account_id === invitee.profile.id)
      )
        fail(409, 'already_member', '이미 팀원이에요.')
      const invitation = {
        id: nextId(database),
        project_id: project.project.id,
        invited_email: email,
        token: `mock-invite-${nextId(database)}`,
      }
      database.invitations.push(invitation)
      const origin =
        typeof location === 'undefined' ? 'http://localhost' : location.origin
      const { token, ...publicInvitation } = invitation
      return {
        status: 201,
        data: {
          invitation: publicInvitation,
          invite_url: `${origin}/invite?token=${token}`,
        },
      }
    }
    case 'MEM-A02':
      return {
        data: page(
          database.invitations
            .filter(
              (invitation) =>
                invitation.project_id === requireProject(context).project.id,
            )
            .map((invitation) => omit(invitation, 'token')),
          query,
        ),
      }
    case 'MEM-A03': {
      requireLeader(context)
      const invitation =
        database.invitations.find(
          (item) =>
            item.id === params.invitation_id &&
            item.project_id === requireProject(context).project.id,
        ) ?? fail(404, 'not_found', '초대를 찾을 수 없어요.')
      invitation.revoked_at = now()
      return { status: 204 }
    }
    case 'MEM-A04':
    case 'MEM-A05': {
      const invitation =
        database.invitations.find((item) => item.token === body.token) ??
        fail(404, 'not_found', '초대를 찾을 수 없어요.')
      if (invitation.revoked_at)
        fail(404, 'not_found', '초대를 찾을 수 없어요.')
      const project =
        database.projects.find(
          (item) => item.project.id === invitation.project_id,
        ) ?? fail(404, 'not_found', '프로젝트를 찾을 수 없어요.')
      if (id === 'MEM-A04') {
        // 수정안 요청 필드(inviter·project_description·invited_email·account_matches)도 명세대로 채운다.
        const leader = database.accounts.find(
          (item) =>
            item.profile.id ===
            project.members.find((member) => member.role === 'LEADER')
              ?.account_id,
        )?.profile
        return {
          data: {
            project_name: project.project.name,
            login_required: !context.account,
            ...(leader
              ? { inviter: { name: leader.name, email: leader.email } }
              : {}),
            ...(project.project.description
              ? { project_description: project.project.description }
              : {}),
            ...(invitation.invited_email
              ? { invited_email: invitation.invited_email }
              : {}),
            ...(context.account && invitation.invited_email
              ? {
                  account_matches:
                    context.account.email === invitation.invited_email,
                }
              : {}),
          },
        }
      }
      const account = requireAccount(context)
      // 실제 서버처럼 다른 이메일 계정의 수락은 무효 초대와 같은 404로 준다(MEM-A05, Q05 -174).
      if (
        invitation.invited_email &&
        invitation.invited_email.toLowerCase() !== account.email.toLowerCase()
      )
        fail(404, 'not_found', '초대를 찾을 수 없어요.')
      let member = project.members.find(
        (item) => item.account_id === account.id,
      )
      if (!member) {
        member = {
          id: nextId(database),
          account_id: account.id,
          name: account.name,
          role: 'MEMBER',
          agent_connected: false,
        }
        project.members.push(member)
      }
      project.left_account_ids = project.left_account_ids?.filter(
        (item) => item !== account.id,
      )
      invitation.accepted_at = now()
      return {
        data: { project: projectView(context, project.project), member },
      }
    }
    case 'MEM-A06':
      return { data: page(requireProject(context).members, query) }
    case 'MEM-A07': {
      // 서버(ProjectMemberService.update)처럼 사람 팀장만 고친다. 본인 것도 팀원은 못 고친다(PR #72 재전수 SECOND-AMP-01).
      requireLeader(context)
      const member = findById(requireProject(context).members, params.member_id)
      // 서버(PR #50)처럼: 생략은 유지, ""는 지우기, 글은 앞뒤 공백을 빼고 50자, 색은 #RRGGBB를 대문자로.
      for (const field of ['domain', 'role_label'] as const) {
        if (typeof body[field] !== 'string') continue
        const value = body[field].trim()
        if (value.length > 50)
          fail(400, 'validation_failed', '50자까지 쓸 수 있어요.')
        if (value) member[field] = value
        else delete member[field]
      }
      if (typeof body.color === 'string') {
        if (body.color && !/^#[0-9a-fA-F]{6}$/.test(body.color))
          fail(400, 'validation_failed', '색은 #RRGGBB 형식이에요.')
        if (body.color) member.color = body.color.toUpperCase()
        else delete member.color
      }
      return { data: member }
    }
    case 'MEM-A08': {
      requireLeader(context)
      const project = requireProject(context)
      const target = findById(
        project.members,
        requiredString(body, 'target_member_id'),
      )
      if (target.id === context.member!.id)
        fail(409, 'invalid_transfer_target', '나 자신에게는 위임할 수 없어요.')
      context.member!.role = 'MEMBER'
      target.role = 'LEADER'
      return { data: { members: [context.member!, target] } }
    }
    case 'MEM-A09':
    case 'MEM-A10': {
      const project = requireProject(context)
      if (id === 'MEM-A09') requireLeader(context)
      const member =
        id === 'MEM-A09'
          ? findById(project.members, params.member_id)
          : context.member!
      if (member.role === 'LEADER')
        fail(409, 'leader_transfer_required', '먼저 인간 팀장을 위임해 주세요.')
      const reassigned = body.reassign_to_member_id
      // 인계 대상이 떠나는 본인이거나 활성 멤버가 아니면 400(PR #50).
      if (
        typeof reassigned === 'string' &&
        (reassigned === member.id ||
          !project.members.some((item) => item.id === reassigned))
      )
        fail(
          400,
          'validation_failed',
          '작업을 넘겨받을 팀원을 다시 골라 주세요.',
        )
      const activeTasks = project.tasks.filter(
        (task) =>
          task.assignee_member_id === member.id &&
          !['COMPLETED', 'CANCELLED'].includes(task.status),
      )
      if (activeTasks.length && typeof reassigned !== 'string')
        fail(
          409,
          'active_tasks_exist',
          '진행 중인 작업을 다른 팀원에게 넘긴 뒤 나가거나 제외해 주세요.',
        )
      project.tasks
        .filter(
          (task) =>
            task.assignee_member_id === member.id &&
            !['COMPLETED', 'CANCELLED'].includes(task.status),
        )
        .forEach((task) =>
          reassignTask(
            context,
            task,
            typeof reassigned === 'string' ? reassigned : context.member!.id,
          ),
        )
      project.members = project.members.filter((item) => item !== member)
      if (id === 'MEM-A10')
        project.left_account_ids = [
          ...(project.left_account_ids ?? []),
          member.account_id,
        ]
      database.tokens
        .filter(
          (token) =>
            token.account_id === member.account_id &&
            token.project_id === project.project.id,
        )
        .forEach((token) => {
          token.revoked_at = now()
        })
      return { status: 204 }
    }
    case 'AGT-A01': {
      const project = requireProject(context)
      const account = requireAccount(context)
      // 서버(PR #38)와 같게: 계정·프로젝트마다 폐기 안 된 토큰은 5개까지, label은 앞뒤 공백을 빼고 비면 뺀다.
      const label = typeof body.label === 'string' ? body.label.trim() : ''
      if (label.length > 100)
        fail(400, 'validation_failed', '토큰 이름은 100자까지예요.')
      if (
        database.tokens.filter(
          (token) =>
            token.account_id === account.id &&
            token.project_id === project.project.id &&
            !token.revoked_at,
        ).length >= 5
      )
        fail(
          409,
          'agent_limit_reached',
          '활성 토큰은 프로젝트당 5개까지입니다. 쓰지 않는 토큰을 폐기한 뒤 다시 발급하세요',
        )
      const token = {
        id: nextId(database),
        project_id: project.project.id,
        account_id: account.id,
        ...(label ? { label } : {}),
        created_at: now(),
      }
      database.tokens.push(token)
      const tokenInfo = omit(token, 'account_id')
      return {
        status: 201,
        data: {
          token_info: tokenInfo,
          token: `mock-only-not-a-real-token-${token.id}`,
          mcp_url: 'https://mock.invalid/mcp',
          claude_md_snippet:
            '예시 환경입니다. 이 토큰은 실제 MCP 연결에 사용할 수 없습니다.',
        },
      }
    }
    case 'AGT-A02':
      return {
        data: page(
          // 폐기한 토큰도 revoked_at과 함께 준다. page()가 id 내림차순으로 자른다.
          database.tokens
            .filter(
              (token) =>
                token.account_id === requireAccount(context).id &&
                (!query.get('project_id') ||
                  token.project_id === query.get('project_id')),
            )
            .map((token) => omit(token, 'account_id')),
          query,
        ),
      }
    case 'AGT-A03': {
      const token =
        database.tokens.find(
          (item) =>
            item.id === params.id &&
            item.account_id === requireAccount(context).id,
        ) ?? fail(404, 'not_found', '토큰을 찾을 수 없어요.')
      token.revoked_at = now()
      return { status: 204 }
    }
    case 'AGT-A04':
      return {
        data: {
          mcp_url: 'https://mock.invalid/mcp',
          claude_md_snippet:
            '브라우저 예시에서는 실제 에이전트 연결을 실행하지 않습니다.',
          steps: [
            '예시 토큰 발급',
            '설정 안내 확인',
            '실제 서버 연결은 API 모드에서 지원',
          ],
        },
      }
    case 'AGT-A05':
      return { data: page(requireProject(context).agents, query) }
    case 'AGT-A06':
      return {
        data: findById(requireProject(context).agents, params.agent_session_id),
      }
    default:
      return undefined
  }
}
