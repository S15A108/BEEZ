/// <reference types="node" />
import { webcrypto } from 'node:crypto'
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import { api } from '../api/client'
import {
  agentsApi,
  authApi,
  changesApi,
  contextsApi,
  dashboardApi,
  documentsApi,
  inboxApi,
  meetingsApi,
  membersApi,
  planningApi,
  projectsApi,
  tasksApi,
  validationsApi,
} from '../api/services'
import {
  DEMO_EMAIL,
  DEMO_PASSWORD,
  MOCK_STORAGE_KEY,
  resetMockApi,
  setupMockApi,
} from './setup'
import { loadMockDatabase } from './state'

let restoreAdapter: () => void

beforeEach(() => {
  Object.defineProperty(globalThis.crypto, 'subtle', {
    configurable: true,
    value: webcrypto.subtle,
  })
  localStorage.clear()
  resetMockApi()
  restoreAdapter = setupMockApi()
})
afterEach(() => {
  restoreAdapter()
  vi.restoreAllMocks()
})

const login = () =>
  authApi.login({ email: DEMO_EMAIL, password: DEMO_PASSWORD })
const plan = (overrides = {}) => ({
  title: '프로젝트 목록 연결',
  goal: '승인 계약대로 프로젝트를 보여준다.',
  included_scope: ['프로젝트 목록'],
  excluded_scope: [],
  approach: '승인 요구사항 사용',
  completion_criteria: ['참여 프로젝트 표시'],
  dependency_task_ids: [],
  design_refs: [
    {
      design_item_id: '301',
      design_item_version_id: '311',
      relation: 'IMPLEMENTS' as const,
    },
  ],
  unlinked: [],
  no_contract_change: true,
  ...overrides,
})

describe('documented REST mock adapter', () => {
  it('never sends requests to the network, including unsupported and legacy paths', async () => {
    const fetch = vi
      .spyOn(globalThis, 'fetch')
      .mockRejectedValue(new Error('network forbidden'))
    const xhr = vi.spyOn(XMLHttpRequest.prototype, 'open')
    await expect(authApi.me()).rejects.toMatchObject({
      response: { status: 401 },
    })
    await login()
    expect((await projectsApi.list()).items).toHaveLength(2)
    for (const path of [
      '/state',
      '/world',
      '/human/spec/dashboard',
      '/mcp',
      '/projects/101/github/prs',
      'https://example.com/api/projects',
    ])
      await expect(api.get(path)).rejects.toMatchObject({
        response: { status: 404 },
      })
    expect(fetch).not.toHaveBeenCalled()
    expect(xhr).not.toHaveBeenCalled()
  })

  it('preserves decimal IDs, four approved document kinds and a separate draft', async () => {
    await login()
    const result = await documentsApi.list('101')
    expect(new Set(result.items.map((document) => document.kind))).toEqual(
      new Set(['PLAN', 'ERD', 'REQUIREMENTS', 'API_SPEC']),
    )
    expect(
      result.items.every(
        (document) => document.approved_version?.status === 'APPROVED',
      ),
    ).toBe(true)
    const detail = await documentsApi.get('101', '201')
    expect(detail.version.id).toBe('211')
    expect(detail.document.latest_draft?.id).toBe('215')
    expect(
      (await documentsApi.get('101', '201', { version_no: 2 })).version.status,
    ).toBe('DRAFT')
    await expect(documentsApi.get('102', '221')).rejects.toMatchObject({
      response: { status: 404 },
    })
    expect((await projectsApi.list({ limit: 1 })).next_cursor).toMatch(
      /^mock-after-/,
    )
  })

  it('registers and changes a password without storing its original value', async () => {
    const password = 'private-test-password-8021'
    const user = await authApi.register({
      email: 'new@beez.local',
      name: '새 팀원',
      password,
    })
    expect(user.id).toMatch(/^\d+$/)
    expect(localStorage.getItem(MOCK_STORAGE_KEY)).not.toContain(password)
    expect((await authApi.me()).id).toBe(user.id)
    await authApi.changePassword({
      current_password: password,
      new_password: 'changed-password-8832',
    })
    await authApi.logout()
    await expect(
      authApi.login({ email: user.email, password }),
    ).rejects.toMatchObject({ response: { status: 401 } })
    await authApi.login({
      email: user.email,
      password: 'changed-password-8832',
    })
    await authApi.forgotPassword({ email: user.email })
    expect(
      (await authApi.verifyResetToken({ token: `mock-reset-${user.id}` }))
        .valid,
    ).toBe(true)
    await authApi.resetPassword({
      token: `mock-reset-${user.id}`,
      new_password: 'reset-password-1921',
    })
    await expect(
      authApi.verifyResetToken({ token: `mock-reset-${user.id}` }),
    ).rejects.toMatchObject({ response: { status: 410 } })
  })

  it('creates, edits and deletes a project and keeps access scoped to membership', async () => {
    await login()
    const created = await projectsApi.create({
      name: '새 프로젝트',
      description: '브라우저 예시',
    })
    expect(created.my_role).toBe('LEADER')
    expect(
      (await projectsApi.update(created.id, { description: '변경한 소개' }))
        .description,
    ).toBe('변경한 소개')
    const invitation = await membersApi.createInvitation(created.id, {
      email: 'member@beez.local',
    })
    const token = new URL(invitation.invite_url).searchParams.get('token')!
    await authApi.logout()
    await authApi.login({ email: 'member@beez.local', password: DEMO_PASSWORD })
    await expect(projectsApi.get(created.id)).rejects.toMatchObject({
      response: { status: 404 },
    })
    const accepted = await membersApi.acceptInvitation({ token })
    expect(accepted.member.role).toBe('MEMBER')
    expect((await projectsApi.get(created.id)).my_role).toBe('MEMBER')
    await membersApi.leave(created.id, {})
    await expect(projectsApi.get(created.id)).rejects.toMatchObject({
      response: { status: 404 },
    })
    await authApi.logout()
    await login()
    await projectsApi.remove(created.id, { confirm_name: '새 프로젝트' })
    await expect(projectsApi.get(created.id)).rejects.toMatchObject({
      response: { status: 404 },
    })
  })

  it('issues only mock tokens and keeps connection and provider status honest', async () => {
    await login()
    const result = await agentsApi.issueToken('101', { label: '예시 PC' })
    expect(result.token).toMatch(/^mock-only-/)
    expect(result.mcp_url).toContain('mock.invalid')
    expect(localStorage.getItem(MOCK_STORAGE_KEY)).not.toContain(result.token)
    expect(
      (await agentsApi.list('101')).items.every((agent) => !agent.connected),
    ).toBe(true)
    await agentsApi.revokeToken(result.token_info.id)
    expect((await agentsApi.myTokens()).items[0].revoked_at).toBeTruthy()
    const usage = await dashboardApi.usage('101', {
      from: '2026-10-22T00:00:00Z',
      to: '2026-10-23T00:00:00Z',
    })
    expect(usage.call_count).toBe(0)
  })

  it('uploads a real multipart text file and exposes its draft analysis', async () => {
    await login()
    const project = await projectsApi.create({ name: '문서 업로드 검증' })
    const result = await documentsApi.upload(project.id, {
      file: new File(
        ['# 새로운 기획\n사용자가 예약을 만든다.'],
        'planning.md',
        { type: 'text/markdown' },
      ),
      title: '예약 기능',
      kind: 'PLAN',
    })
    expect(result.document.latest_draft?.content).toContain('예약')
    const version = result.document.latest_draft!
    expect(
      (
        await planningApi.analysis(project.id, result.document.id, {
          document_version_id: version.id,
        })
      ).status,
    ).toBe('COMPLETED')
    expect(
      (
        await planningApi.questions(project.id, {
          document_version_id: version.id,
        })
      ).items,
    ).toHaveLength(1)
    const file = await documentsApi.download(project.id, result.document.id, 1)
    expect(file).toBeInstanceOf(Blob)
  })

  it('publishes the exact reviewed four-document baseline and answers planning questions', async () => {
    await login()
    const question = (await planningApi.questions('102')).items[0]
    await planningApi.answerQuestion('102', question.id, {
      answer: '팀장만 삭제할 수 있어요.',
    })
    const previousItems = await planningApi.items('102', { status: 'DRAFT' })
    const documents = await documentsApi.list('102')
    await planningApi.generateDrafts('102', {
      document_version_ids: documents.items.map(
        (document) => document.latest_draft!.id,
      ),
    })
    const items = await planningApi.items('102', { status: 'DRAFT' })
    expect(items.draft_snapshot_hash).not.toBe(
      previousItems.draft_snapshot_hash,
    )
    expect(
      items.items.some((item) =>
        String(item.content.clarification ?? '').includes(
          '팀장만 삭제할 수 있어요.',
        ),
      ),
    ).toBe(true)
    const baseline = await planningApi.publishBaseline({
      project_id: '102',
      document_version_ids: documents.items.map(
        (document) => document.latest_draft!.id,
      ),
      design_item_version_ids: items.items.map((item) => item.id),
      draft_snapshot_hash: items.draft_snapshot_hash!,
    })
    expect(baseline.documents).toHaveLength(4)
    expect((await projectsApi.get('102')).current_baseline?.id).toBe(
      baseline.id,
    )
    expect((await documentsApi.get('102', '221')).version.status).toBe(
      'APPROVED',
    )
  })

  it('polls the same validation then gates START on real rule results', async () => {
    await login()
    const task = await tasksApi.create('101', plan())
    const input = {
      plan_revision_id: task.current_plan_revision.id,
      expected_lock_version: task.lock_version,
    }
    const validation = await validationsApi.create('101', task.id, input, {
      idempotencyKey: 'same-validation',
    })
    expect(validation.status).toBe('RUNNING')
    expect(
      (
        await validationsApi.create('101', task.id, input, {
          idempotencyKey: 'same-validation',
        })
      ).id,
    ).toBe(validation.id)
    expect((await validationsApi.get('101', validation.id)).status).toBe(
      'RUNNING',
    )
    expect((await validationsApi.get('101', validation.id)).status).toBe('PASS')
    const started = await tasksApi.state('101', task.id, {
      event: 'START',
      plan_revision_id: task.current_plan_revision.id,
      expected_lock_version: task.lock_version,
      validation_id: validation.id,
    })
    expect(started.status).toBe('IN_PROGRESS')
    const incomplete = await tasksApi.create(
      '101',
      plan({ no_contract_change: false, design_refs: [] }),
    )
    const failing = await validationsApi.create('101', incomplete.id, {
      plan_revision_id: incomplete.current_plan_revision.id,
      expected_lock_version: incomplete.lock_version,
    })
    await validationsApi.get('101', failing.id)
    expect((await validationsApi.get('101', failing.id)).status).toBe(
      'NEEDS_INPUT',
    )
    await expect(
      tasksApi.state('101', incomplete.id, {
        event: 'START',
        plan_revision_id: incomplete.current_plan_revision.id,
        expected_lock_version: incomplete.lock_version,
        validation_id: failing.id,
      }),
    ).rejects.toMatchObject({ response: { status: 409 } })
  })

  it('rolls back a failed publication and atomically marks affected work stale on approval', async () => {
    await login()
    const task = await tasksApi.get('101', '501')
    await tasksApi.state('101', task.id, {
      event: 'START',
      plan_revision_id: task.current_plan_revision.id,
      expected_lock_version: task.lock_version,
      validation_id: '601',
    })
    const { proposal } = await changesApi.get('101', '71')
    await expect(
      changesApi.approve('101', '71', {
        expected_base_baseline_id: '999',
        expected_updated_at: proposal.updated_at,
      }),
    ).rejects.toMatchObject({ response: { status: 409 } })
    expect((await documentsApi.baseline('101')).id).toBe('401')
    expect((await changesApi.get('101', '71')).proposal.status).toBe(
      'IN_REVIEW',
    )
    const published = await changesApi.approve('101', '71', {
      expected_base_baseline_id: '401',
      expected_updated_at: proposal.updated_at,
    })
    expect(published.proposal.status).toBe('PUBLISHED')
    expect(published.baseline.version_no).toBe(2)
    const updated = await tasksApi.get('101', task.id)
    expect(updated.status).toBe('WAITING')
    expect(updated.waiting_reason).toBe('REVALIDATION')
    expect(updated.validation_status).toBe('STALE')
    expect((await validationsApi.get('101', '601')).status).toBe('STALE')
    expect((await documentsApi.baselineVersion('101', 1)).id).toBe('401')
  })

  it('never applies a previous running validation to a newer plan revision', async () => {
    await login()
    const task = await tasksApi.create('101', plan())
    const validation = await validationsApi.create('101', task.id, {
      plan_revision_id: task.current_plan_revision.id,
      expected_lock_version: task.lock_version,
    })
    const revised = await tasksApi.savePlan('101', task.id, {
      ...plan(),
      title: '새 작업 계획',
      expected_revision_no: 1,
      expected_lock_version: task.lock_version,
    })
    await validationsApi.get('101', validation.id)
    expect((await validationsApi.get('101', validation.id)).status).toBe(
      'STALE',
    )
    expect((await tasksApi.get('101', task.id)).current_plan_revision.id).toBe(
      revised.current_plan_revision.id,
    )
    expect((await tasksApi.get('101', task.id)).validation_status).toBe(
      'NOT_RUN',
    )
  })

  it('records meeting text, creates explicit template minutes and marks notifications read', async () => {
    await login()
    const meeting = await meetingsApi.create('101', {
      title: '화면 설계 검토',
      participant_member_ids: ['22'],
    })
    const entry = await meetingsApi.addEntry('101', meeting.id, {
      text: '기존 계약을 유지하고 안내 문구를 보완해요.',
    })
    const minutes = await meetingsApi.minutes('101', meeting.id, {
      through_entry_id: entry.id,
    })
    expect(minutes.entry.kind).toBe('MINUTES')
    expect(minutes.summary).toContain('기존 계약')
    expect(minutes.entry.generation?.origin).toBe('TEMPLATE')
    const record = await contextsApi.create('101', {
      kind: 'PROGRESS',
      visibility: 'SHARED',
      summary: '검토 진행',
      detail: '문구를 검토합니다.',
      design_refs: [],
    })
    expect((await contextsApi.get('101', record.id)).summary).toBe('검토 진행')
    const inbox = await inboxApi.list({ project_id: '101', unread: true })
    expect(
      (await inboxApi.markRead({ ids: inbox.items.map((item) => item.id) }))
        .updated_count,
    ).toBe(inbox.items.length)
    expect(
      (await inboxApi.list({ project_id: '101', unread: true })).items,
    ).toHaveLength(0)
    expect(
      loadMockDatabase().projects[0].entries.some(
        (item) => item.id === minutes.entry.id,
      ),
    ).toBe(true)
  })
})
