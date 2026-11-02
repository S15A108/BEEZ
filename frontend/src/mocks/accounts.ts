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
      if (database.accounts.some((item) => item.profile.email === email))
        fail(409, 'email_already_exists', '이미 등록된 이메일이에요.')
      const profile: Account = {
        id: nextId(database),
        email,
        name: requiredString(body, 'name'),
        avatar: 'bee-yellow',
        created_at: now(),
      }
      database.accounts.push({
        profile,
        password_fingerprint: await password(body.password),
      })
      database.session_account_id = profile.id
      joinPendingInvitations(context, profile)
      return { status: 201, data: profile }
    }
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
