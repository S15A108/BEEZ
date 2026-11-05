import type { Account, Member, Project } from '../api/contracts/common'
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
import { reassignTask } from './tasks'

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

// 제외(MEM-A09)·나가기(MEM-A10)하면 그 사람 이메일로 이 프로젝트에 남은 대기 초대를 함께 취소한다(PR #134).
// 남겨 두면 다음 로그인이나 예전 메일 링크로 바로 돌아온다. 끝난 뒤 새로 초대하면 그 초대로는 돌아온다.
function revokePendingInvitations(
  context: MockContext,
  projectId: string,
  accountId: string,
) {
  const email = context.database.accounts.find(
    (item) => item.profile.id === accountId,
  )?.profile.email
  context.database.invitations
    .filter(
      (item) =>
        item.project_id === projectId &&
        item.invited_email === email &&
        !item.accepted_at &&
        !item.revoked_at,
    )
    .forEach((item) => {
      item.revoked_at = now()
    })
}

// MEM-A04·A05 공통: 없는 토큰·삭제된 프로젝트·취소한 초대는 원인을 나누지 않고 404다(PR #134).
function findInvitation(context: MockContext) {
  const invitation =
    context.database.invitations.find(
      (item) => item.token === context.body.token,
    ) ?? fail(404, 'not_found', '초대를 찾을 수 없어요.')
  const project = context.database.projects.find(
    (item) => item.project.id === invitation.project_id,
  )
  if (!project || invitation.revoked_at)
    fail(404, 'not_found', '초대를 찾을 수 없어요.')
  return { invitation, project }
}

const emailPattern = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

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
      const account = database.accounts.find(
        (item) =>
          item.profile.email === email &&
          item.password_fingerprint === fingerprint,
      )
      if (!account)
        fail(401, 'invalid_credentials', '이메일과 비밀번호를 확인해 주세요.')
      database.session_account_id = account.profile.id
      joinPendingInvitations(context, account.profile)
      return { data: account.profile }
    }
    case 'ACC-A03':
      database.session_account_id = null
      return { status: 204 }
    case 'ACC-A04':
      return { data: requireAccount(context) }
    case 'ACC-A05': {
      const account = requireAccount(context)
      if (body.name !== undefined) account.name = requiredString(body, 'name')
      if (typeof body.avatar === 'string') account.avatar = body.avatar
      database.projects.forEach((project) =>
        project.members
          .filter((member) => member.account_id === account.id)
          .forEach((member) => {
            member.name = account.name
          }),
      )
      return { data: account }
    }
    case 'ACC-A06': {
      const stored = database.accounts.find(
        (item) => item.profile.id === requireAccount(context).id,
      )!
      if (
        stored.password_fingerprint !==
        (await passwordFingerprint(String(body.current_password ?? '')))
      )
        fail(400, 'current_password_mismatch', '현재 비밀번호가 맞지 않아요.')
      stored.password_fingerprint = await password(body.new_password)
      return { status: 204 }
    }
    case 'ACC-A07': {
      const account = database.accounts.find(
        (item) => item.profile.email === body.email,
      )
      if (account)
        database.reset_tokens.push({
          token: `mock-reset-${account.profile.id}`,
          account_id: account.profile.id,
          expires_at: new Date(Date.now() + 1800000).toISOString(),
          used: false,
        })
      return {
        data: {
          message:
            '예시 환경에서는 메일을 보내지 않아요. 재설정 예시 토큰은 mock-reset-계정ID입니다.',
        },
        status: 202,
      }
    }
    case 'ACC-A08':
    case 'ACC-A09': {
      const token = database.reset_tokens.findLast(
        (item) => item.token === body.token && !item.used,
      )
      if (!token || Date.parse(token.expires_at) <= Date.now())
        fail(410, 'reset_token_expired', '재설정 예시 링크가 만료됐어요.')
      if (id === 'ACC-A08')
        return { data: { valid: true, expires_at: token.expires_at } }
      database.accounts.find(
        (item) => item.profile.id === token.account_id,
      )!.password_fingerprint = await password(body.new_password)
      token.used = true
      database.session_account_id = null
      return { status: 204 }
    }
    case 'ACC-A10': {
      const account = requireAccount(context)
      const stored = database.accounts.find(
        (item) => item.profile.id === account.id,
      )!
      if (
        stored.password_fingerprint !==
        (await passwordFingerprint(String(body.password ?? '')))
      )
        fail(400, 'password_mismatch', '비밀번호를 확인해 주세요.')
      if (
        database.projects.some((project) =>
          project.members.some((member) => member.account_id === account.id),
        )
      )
        fail(409, 'active_membership', '참여 프로젝트를 먼저 나가 주세요.')
      database.accounts = database.accounts.filter((item) => item !== stored)
      database.session_account_id = null
      return { status: 204 }
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
      const isMember = (email: string) => {
        const invitee = database.accounts.find(
          (item) => item.profile.email === email,
        )
        return project.members.some(
          (item) => item.account_id === invitee?.profile.id,
        )
      }
      // 이메일 초대(PR #134): 이메일마다 대기 초대를 만들고 메일을 보낸다. 예시 환경은 메일을 보내지 않는다.
      // 대소문자·앞뒤 공백만 다른 주소는 하나로 묶고, 같은 이메일의 대기 초대가 있으면 새로 만들지 않는다.
      if (body.emails !== undefined) {
        const raw = body.emails
        if (
          body.email !== undefined ||
          !Array.isArray(raw) ||
          raw.length < 1 ||
          raw.length > 20 ||
          raw.some(
            (item) =>
              typeof item !== 'string' || !emailPattern.test(item.trim()),
          ) ||
          (typeof body.message === 'string' && body.message.trim().length > 500)
        )
          fail(400, 'validation_failed', '초대할 이메일을 확인해 주세요.')
        const emails = new Set(
          (raw as string[]).map((item) => item.trim().toLowerCase()),
        )
        return {
          status: 201,
          data: {
            results: [...emails].map((email) => {
              if (isMember(email)) return { email, status: 'ALREADY_MEMBER' }
              const pending = database.invitations.find(
                (item) =>
                  item.project_id === project.project.id &&
                  item.invited_email === email &&
                  !item.accepted_at &&
                  !item.revoked_at,
              )
              if (pending)
                return {
                  email,
                  status: 'ALREADY_INVITED',
                  invitation_id: pending.id,
                }
              const id = nextId(database)
              database.invitations.push({
                id,
                project_id: project.project.id,
                invited_email: email,
                token: `mock-invite-${nextId(database)}`,
              })
              return { email, status: 'INVITED', invitation_id: id }
            }),
          },
        }
      }
      // 링크 초대(기존 호환). 메일을 보내지 않아 message를 넣을 곳이 없다.
      if (body.message !== undefined)
        fail(400, 'validation_failed', 'message는 emails와 함께만 보내요.')
      const email =
        typeof body.email === 'string' && body.email.trim()
          ? body.email.trim().toLowerCase()
          : undefined
      if (email && isMember(email))
        fail(409, 'already_member', '이미 팀원이에요.')
      const invitation = {
        id: nextId(database),
        project_id: project.project.id,
        ...(email ? { invited_email: email } : {}),
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
      // 이미 수락한 초대는 바꾸지 않는다. 들어온 팀원을 내보내는 것은 MEM-A09다.
      if (!invitation.accepted_at) invitation.revoked_at ??= now()
      return { status: 204 }
    }
    case 'MEM-A11': {
      requireLeader(context)
      const invitation =
        database.invitations.find(
          (item) =>
            item.id === params.invitation_id &&
            item.project_id === requireProject(context).project.id &&
            // 이메일 없는 링크 초대는 보낼 곳이 없어 없는 초대로 본다(PR #134).
            item.invited_email,
        ) ?? fail(404, 'not_found', '초대를 찾을 수 없어요.')
      if (invitation.accepted_at || invitation.revoked_at)
        fail(409, 'invitation_not_pending', '이미 수락·취소된 초대예요.')
      // 새 토큰으로 바꿔 이전 링크를 막는다. 예시 환경은 메일을 보내지 않는다.
      invitation.token = `mock-invite-${nextId(database)}`
      return { data: { invitation: omit(invitation, 'token') } }
    }
    case 'MEM-A04': {
      const { invitation, project } = findInvitation(context)
      // 이메일 초대는 수락한 뒤에도 그 사람이 지금 팀원이면 정상 응답이다(로그인 자동 참여 뒤 메일 링크로 돌아온 경우).
      // 링크 초대는 한 번 쓰면, 이메일 초대는 그 사람이 제외·나간 뒤면 404다.
      const invitee = invitation.invited_email
        ? database.accounts.find(
            (item) => item.profile.email === invitation.invited_email,
          )
        : undefined
      if (
        invitation.accepted_at &&
        !project.members.some(
          (member) => member.account_id === invitee?.profile.id,
        )
      )
        fail(404, 'not_found', '초대를 찾을 수 없어요.')
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
          // 로그인 상태에서만 준다. 링크 초대는 누구나, 이메일 초대는 그 이메일 계정만 맞다.
          ...(context.account
            ? {
                account_matches:
                  !invitation.invited_email ||
                  context.account.email === invitation.invited_email,
              }
            : {}),
        },
      }
    }
    case 'MEM-A05': {
      const { invitation, project } = findInvitation(context)
      const account = requireAccount(context)
      // 서버 판정 순서(PR #134): 다른 이메일 → 404, 이미 팀원 → 409 already_member, 이미 쓴 초대 → 404.
      // 그래서 로그인 자동 참여로 이미 들어온 사람이 메일 링크로 돌아오면 409다.
      if (
        invitation.invited_email &&
        invitation.invited_email.toLowerCase() !== account.email.toLowerCase()
      )
        fail(404, 'not_found', '초대를 찾을 수 없어요.')
      if (project.members.some((item) => item.account_id === account.id))
        fail(409, 'already_member', '이미 팀원이에요.')
      if (invitation.accepted_at)
        fail(404, 'not_found', '초대를 찾을 수 없어요.')
      const member: Member = {
        id: nextId(database),
        account_id: account.id,
        name: account.name,
        role: 'MEMBER',
        agent_connected: false,
      }
      project.members.push(member)
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
      revokePendingInvitations(context, project.project.id, member.account_id)
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
