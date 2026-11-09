import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AxiosError, AxiosHeaders } from 'axios'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { changesApi } from '@/api/services'
import type { ChangeProposal } from '@/api/contracts/common'
import type { ProposalFormValues } from './proposalItems'
import { ProposeFlow } from './ProposeFlow'
import { proposeChanges } from './proposeDiff'
import type { Draft, DraftInput, DraftReview } from './schemas'

// 부모가 영향 분석을 1.5초마다 다시 받아 다시 그려도 줄 비교를 되풀이하지 않는지 세려고 실제 함수를 감싼다.
vi.mock('./proposeDiff', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./proposeDiff')>()
  return { ...actual, proposeChanges: vi.fn(actual.proposeChanges) }
})

const doc = (versionNo: number, versionId: string) => ({
  id: '203',
  label: '요구사항 명세',
  versionId,
  title: '요구사항 명세',
  versionNo,
})

// 작성 중 제안 API(CHG-A16~A20)는 서버 대신 이 초안을 돌려준다. 저장할 때마다 리비전이 오른다.
let current: Draft | undefined
const toDraft = (input: DraftInput, revision: number): Draft => ({
  id: '900',
  project_id: '101',
  revision,
  base_baseline_id: input.base_baseline_id,
  title: input.title,
  reason: input.reason,
  items: input.items,
  documents: input.documents.map((change) => ({
    document_id: change.document_id,
    base_document_version_id: change.base_document_version_id,
    proposed_document_version_id: '901',
    after_content: change.after_content ?? '',
    content_hash: 'hash',
  })),
  created_at: '2026-11-09T01:00:00Z',
  updated_at: '2026-11-09T01:00:00Z',
})

beforeEach(() => {
  localStorage.clear()
  current = undefined
  vi.spyOn(changesApi, 'createDraft').mockImplementation(
    async (_, input) => (current = toDraft(input, 1)),
  )
  vi.spyOn(changesApi, 'updateDraft').mockImplementation(
    async (_, __, input) => (current = toDraft(input, current!.revision + 1)),
  )
  vi.spyOn(changesApi, 'getDraft').mockImplementation(async () => current!)
})
afterEach(() => vi.restoreAllMocks())

function conflict(code: string) {
  return new AxiosError('conflict', 'ERR_BAD_REQUEST', undefined, undefined, {
    status: 409,
    statusText: 'Conflict',
    headers: {},
    config: { headers: new AxiosHeaders() },
    data: { error: { code, message: code } },
  })
}

function renderFlow(
  itemKinds: Record<string, string> = {},
  source?: ChangeProposal,
) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  const onSubmitted = vi.fn()
  const flow = (documents: ReturnType<typeof doc>[]) => (
    <QueryClientProvider client={client}>
      <ProposeFlow
        projectId="101"
        documents={documents}
        itemKinds={itemKinds}
        tasks={[]}
        fetchDocumentContent={async () => ({
          content: 'REQ-001 이메일로 가입한다.',
          versionId: '301',
        })}
        defaultValues={
          {
            title: '',
            reason: '',
            task_id: '',
            document_id: '203',
            after_content: '',
          } as ProposalFormValues
        }
        toInput={(values) => ({
          base_baseline_id: '401',
          title: values.title,
          reason: values.reason,
          items: [],
          documents: [
            {
              document_id: '203',
              base_document_version_id: values.base_version_id!,
              after_content: values.after_content ?? '',
            },
          ],
        })}
        source={source}
        onCancel={vi.fn()}
        onSubmitted={onSubmitted}
      />
    </QueryClientProvider>
  )
  const view = render(flow([doc(1, '301')]))
  return {
    client,
    onSubmitted,
    approveNewVersion: () => view.rerender(flow([doc(2, '302')])),
    rerenderSame: () => view.rerender(flow([doc(1, '301')])),
  }
}

// PR #135 리뷰(오서현): v1과 비교해 ②에 들어간 뒤 승인본이 v2로 바뀌면, 화면은 'v2와 비교'라고 하는데 실제 비교·제출은
// v1이었다(서버는 baseline_conflict로 거절). 비교한 버전을 고정해 보이고, 올리기 전에 승인본이 바뀌었으면 다시 비교하게 한다.
// 비비 검토 단계가 생겨 ②에서 넘어가는 버튼만 '검토 없이 다음'으로 바꾸고 확인 표시를 켠다(사용자 승인 2026-11-09).
it('단계 중간에 승인본이 바뀌면 비교한 버전을 그대로 보이고 올리기 전에 다시 비교하라고 막는다', async () => {
  const user = userEvent.setup()
  const { onSubmitted, approveNewVersion } = renderFlow()
  await waitFor(() =>
    expect(
      (screen.getByLabelText('고친 글') as HTMLTextAreaElement).value,
    ).toContain('REQ-001'),
  )
  await user.type(screen.getByLabelText('제목 *'), '가입 안내 추가')
  await user.type(screen.getByLabelText('바꾸려는 이유 *'), '안내가 필요해요.')
  await user.type(
    screen.getByLabelText('고친 글'),
    '\nREQ-002 가입하면 환영 메일을 보낸다.',
  )
  await user.click(screen.getByRole('button', { name: '등록하고 변경점 찾기' }))
  expect((await screen.findAllByText(/기준 v1/)).length).toBeGreaterThan(0)

  approveNewVersion()

  expect(screen.getAllByText(/기준 v1/).length).toBeGreaterThan(0)
  expect(screen.queryByText(/기준 v2/)).not.toBeInTheDocument()
  expect(screen.getByRole('alert')).toHaveTextContent(
    '승인본이 v2로 바뀌었어요',
  )
  await user.click(screen.getByRole('button', { name: '검토 없이 다음' }))
  await user.click(
    screen.getByRole('checkbox', { name: '이 상태 그대로 올릴게요' }),
  )
  expect(screen.getByRole('button', { name: '팀장에게 올리기' })).toBeDisabled()
  expect(onSubmitted).not.toHaveBeenCalled()

  await user.click(
    screen.getByRole('button', { name: '새 승인본과 다시 비교' }),
  )
  expect(screen.getByLabelText('제목 *')).toHaveValue('가입 안내 추가')
})

async function register(edit: (text: HTMLElement) => Promise<void>) {
  const user = userEvent.setup()
  const text = screen.getByLabelText('고친 글')
  await waitFor(() => expect(text).toHaveValue('REQ-001 이메일로 가입한다.'))
  await user.type(screen.getByLabelText('제목 *'), '가입 규칙 바꾸기')
  await user.type(
    screen.getByLabelText('바꾸려는 이유 *'),
    '규칙이 바뀌었어요.',
  )
  await edit(text)
  await user.click(screen.getByRole('button', { name: '등록하고 변경점 찾기' }))
  await screen.findByRole('heading', { name: /바뀐 곳 \d+개를 찾았어요/ })
  return user
}

// PR #135 리뷰: 승인된 REQ-001 줄을 지우고 그 자리에 REQ-002를 쓰면 REQ-001은 '고치기'가 아니라 '없애기'다.
it('지운 항목 키는 바뀐 줄의 설계 항목에 없애기로, 새 키는 새 항목으로 보인다', async () => {
  renderFlow({ 'REQ-001': '요구사항' })
  await register(async (text) => {
    await userEvent.clear(text)
    await userEvent.type(text, 'REQ-002 이메일 인증 후 가입한다.')
  })
  const items = within(
    screen.getByRole('region', { name: '바뀐 줄의 설계 항목' }),
  ).getAllByRole('listitem')
  expect(items.map((item) => item.textContent)).toEqual([
    'REQ-001요구사항없애기',
    'REQ-002새 항목',
  ])
})

// PR #135 리뷰: 빈 줄이나 끝 줄바꿈만 바뀌면 바뀐 곳이 0개인데도 다음 단계로 넘어가 빈 제안을 올릴 수 있었다.
// 비비 검토 단계가 생겨 ②의 넘어가는 버튼이 '검토 없이 다음'·'비비 검토 받기' 둘이 됐다(사용자 승인 2026-11-09).
it('빈 줄만 바꾸면 다음 단계로 넘어가지 않고 다시 고치게 한다', async () => {
  renderFlow()
  const user = await register((text) => userEvent.type(text, '{Enter}{Enter}'))
  expect(screen.getByText('바뀐 곳 0개를 찾았어요')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: '검토 없이 다음' })).toBeDisabled()
  expect(screen.getByRole('button', { name: '비비 검토 받기' })).toBeDisabled()
  expect(screen.getByText(/빈 줄 말고는 바뀐 곳이 없어요/)).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '다시 고치기' }))
  expect(
    screen.getByRole('button', { name: '등록하고 변경점 찾기' }),
  ).toBeInTheDocument()
})

it('같은 내용으로 다시 그려도 줄 비교를 되풀이하지 않는다', async () => {
  const { rerenderSame } = renderFlow()
  await register((text) =>
    userEvent.type(text, '{Enter}REQ-002 환영 메일을 보낸다.'),
  )
  expect(screen.getByText('바뀐 곳 1개를 찾았어요')).toBeInTheDocument()
  vi.mocked(proposeChanges).mockClear()
  rerenderSame()
  rerenderSame()
  expect(proposeChanges).not.toHaveBeenCalled()
})

// ---- S15P31A108-325 올리기 전 비비 검토 ----

const review = (patch: Partial<DraftReview> = {}): DraftReview => ({
  id: '950',
  status: 'COMPLETED',
  input_revision: current!.revision,
  resolved_revision: current!.revision,
  steps: [
    { key: 'READ_CHANGES', status: 'DONE', count: 1 },
    { key: 'FIND_RELATED', status: 'DONE', count: 1 },
    { key: 'AI_CANDIDATES', status: 'NOT_EVALUATED' },
    { key: 'GAPS_CONFLICTS', status: 'DONE', count: 1 },
  ],
  tasks_to_wait: [{ task_id: '501', title: '가입 화면 구현' }],
  validations_to_stale: [],
  suggestions: [
    {
      id: '960',
      type: 'PATCH',
      category: 'OMISSION',
      design_item_key: 'REQ-002',
      explanation: '실패했을 때 안내가 없어요.',
      document_id: '203',
      base_document_version_id: '301',
      reviewed_content_hash: 'hash',
      original_text: 'REQ-002 환영 메일을 보낸다.',
      replacement_text: 'REQ-002 환영 메일을 보내고 실패하면 다시 보낸다.',
      decision: 'PENDING',
      sources: [],
    },
  ],
  usage: [],
  created_at: '2026-11-09T01:00:00Z',
  ...patch,
})

const withReview = (patch?: Partial<DraftReview>) => {
  current = {
    ...current!,
    latest_review: review(patch),
    review_state_hash: `state-${current!.revision}`,
  }
  return current
}

async function registerAddedLine() {
  return register((text) =>
    userEvent.type(text, '{Enter}REQ-002 환영 메일을 보낸다.'),
  )
}

it('비비 검토를 받으면 진행을 보이다가 결과를 보이고, 수정안을 반영하면 고친 글이 바뀐다', async () => {
  renderFlow()
  const user = await registerAddedLine()
  vi.spyOn(changesApi, 'requestDraftReview').mockImplementation(async () => {
    withReview({
      status: 'RUNNING',
      steps: [
        { key: 'READ_CHANGES', status: 'DONE', count: 1 },
        { key: 'FIND_RELATED', status: 'RUNNING' },
        { key: 'AI_CANDIDATES', status: 'PENDING' },
        { key: 'GAPS_CONFLICTS', status: 'PENDING' },
      ],
      suggestions: [],
    })
    return current!.latest_review!
  })
  await user.click(screen.getByRole('button', { name: '비비 검토 받기' }))
  expect(
    await screen.findByRole('heading', {
      name: '비비가 바뀐 곳을 검토하고 있어요',
    }),
  ).toBeInTheDocument()
  expect(screen.getByText('2단계 / 4단계')).toBeInTheDocument()
  // 검토가 도는 동안은 고칠 수 없다(CHG-A18 409 review_in_progress).
  expect(screen.getByRole('button', { name: '다시 고치기' })).toBeDisabled()

  withReview()
  expect(
    await screen.findByRole(
      'heading',
      { name: '비비가 검토를 마쳤어요' },
      { timeout: 4000 },
    ),
  ).toBeInTheDocument()
  expect(screen.getByText('가입 화면 구현')).toBeInTheDocument()

  const decide = vi
    .spyOn(changesApi, 'decideDraftSuggestion')
    .mockImplementation(async () => {
      current = {
        ...current!,
        revision: 2,
        documents: current!.documents.map((change) => ({
          ...change,
          after_content: change.after_content.replace(
            'REQ-002 환영 메일을 보낸다.',
            'REQ-002 환영 메일을 보내고 실패하면 다시 보낸다.',
          ),
        })),
        latest_review: {
          ...current!.latest_review!,
          resolved_revision: 2,
          suggestions: current!.latest_review!.suggestions.map((item) => ({
            ...item,
            decision: 'APPLIED' as const,
          })),
        },
      }
      return current
    })
  await user.click(screen.getByRole('button', { name: '반영하기' }))
  expect(decide).toHaveBeenCalledWith('101', '900', '950', '960', {
    expected_revision: 1,
    decision: 'APPLIED',
  })
  expect(await screen.findByText(/반영함/)).toBeInTheDocument()
  expect(screen.getByRole('button', { name: '되돌리기' })).toBeInTheDocument()
  // 반영한 글이 ④의 고친 글이 되고, 확인할 상태가 없으면 체크 없이 올린다.
  await user.click(screen.getByRole('button', { name: '다음' }))
  expect(
    screen.getByText(
      '비비 의견을 확인한 최종본이에요. 팀장이 보게 될 내용과 같아요.',
    ),
  ).toBeInTheDocument()
  expect(
    screen.getByText(/환영 메일을 보내고 실패하면 다시 보낸다/, {
      selector: 'span',
    }),
  ).toBeInTheDocument()
  expect(
    screen.queryByRole('checkbox', { name: '이 상태 그대로 올릴게요' }),
  ).not.toBeInTheDocument()
  const submitDraft = vi
    .spyOn(changesApi, 'submitDraft')
    .mockResolvedValue({ id: '71' } as ChangeProposal)
  await user.click(screen.getByRole('button', { name: '팀장에게 올리기' }))
  await waitFor(() =>
    expect(submitDraft).toHaveBeenCalledWith('101', {
      draft_id: '900',
      expected_draft_revision: 2,
      review_acknowledged: false,
    }),
  )
})

it('검토를 받지 않았거나 진행 중이면 상태를 보이고 확인한 뒤에만 올린다', async () => {
  const { onSubmitted } = renderFlow()
  const user = await registerAddedLine()
  await user.click(screen.getByRole('button', { name: '검토 없이 다음' }))
  const group = screen.getByRole('group', {
    name: '올리기 전 확인할 검토 상태',
  })
  expect(group).toHaveTextContent('비비 검토를 받지 않았어요.')
  const button = screen.getByRole('button', { name: '팀장에게 올리기' })
  expect(button).toBeDisabled()
  const submitDraft = vi
    .spyOn(changesApi, 'submitDraft')
    .mockResolvedValue({ id: '72' } as ChangeProposal)
  await user.click(
    within(group).getByRole('checkbox', { name: '이 상태 그대로 올릴게요' }),
  )
  await user.click(button)
  await waitFor(() => expect(onSubmitted).toHaveBeenCalledWith({ id: '72' }))
  expect(submitDraft).toHaveBeenCalledWith('101', {
    draft_id: '900',
    expected_draft_revision: 1,
    review_acknowledged: true,
  })
  // 올리면 이어 볼 초안을 지운다.
  expect(localStorage.getItem('beez.changeDraft.101')).toBeNull()
})

it('진행 중인 검토를 두고 올리면 사용자가 본 검토 상태 해시를 함께 보낸다', async () => {
  renderFlow()
  const user = await registerAddedLine()
  vi.spyOn(changesApi, 'requestDraftReview').mockImplementation(async () => {
    withReview({ status: 'RUNNING', suggestions: [] })
    return current!.latest_review!
  })
  await user.click(screen.getByRole('button', { name: '비비 검토 받기' }))
  await screen.findByRole('heading', {
    name: '비비가 바뀐 곳을 검토하고 있어요',
  })
  await user.click(screen.getByRole('button', { name: '다음' }))
  expect(
    screen.getByRole('group', { name: '올리기 전 확인할 검토 상태' }),
  ).toHaveTextContent('비비가 아직 검토하고 있어요. 올리면 이 검토는 취소돼요.')
  const submitDraft = vi
    .spyOn(changesApi, 'submitDraft')
    .mockResolvedValue({ id: '73' } as ChangeProposal)
  await user.click(
    screen.getByRole('checkbox', { name: '이 상태 그대로 올릴게요' }),
  )
  await user.click(screen.getByRole('button', { name: '팀장에게 올리기' }))
  await waitFor(() =>
    expect(submitDraft).toHaveBeenCalledWith('101', {
      draft_id: '900',
      expected_draft_revision: 1,
      review_acknowledged: true,
      expected_review_state_hash: 'state-1',
    }),
  )
})

// PR #227 리뷰(오서현): '검토 진행 중'을 확인한 뒤 자동 갱신으로 처리하지 않은 수정안이 생겨도 체크가 남아, 올릴 때 새 상태의
// 해시를 보내 새 상태까지 확인한 것이 됐다. 확인은 그때 본 초안 리비전·검토 상태 해시에 묶고, 바뀌면 풀어서 다시 받는다.
it('확인한 뒤 자동 갱신으로 검토 상태가 바뀌면 체크를 풀고 다시 확인한 상태의 해시로 올린다', async () => {
  const { client } = renderFlow()
  const user = await registerAddedLine()
  vi.spyOn(changesApi, 'requestDraftReview').mockImplementation(async () => {
    withReview({ status: 'RUNNING', suggestions: [] })
    return current!.latest_review!
  })
  await user.click(screen.getByRole('button', { name: '비비 검토 받기' }))
  await screen.findByRole('heading', {
    name: '비비가 바뀐 곳을 검토하고 있어요',
  })
  await user.click(screen.getByRole('button', { name: '다음' }))
  const checkbox = () =>
    screen.getByRole('checkbox', { name: '이 상태 그대로 올릴게요' })
  await user.click(checkbox())
  expect(checkbox()).toBeChecked()

  // 그 사이 검토가 끝나 처리하지 않은 수정안이 생겼고, 자동 갱신으로 다시 읽었다.
  current = { ...withReview(), review_state_hash: 'state-1-done' }
  await act(() => client.refetchQueries())
  const group = screen.getByRole('group', {
    name: '올리기 전 확인할 검토 상태',
  })
  await waitFor(() =>
    expect(group).toHaveTextContent('처리하지 않은 수정안이 1개 있어요.'),
  )
  expect(checkbox()).not.toBeChecked()
  expect(group).toHaveTextContent(
    '확인한 뒤 검토 상태가 바뀌었어요. 바뀐 상태를 보고 다시 확인해 주세요.',
  )
  const button = screen.getByRole('button', { name: '팀장에게 올리기' })
  expect(button).toBeDisabled()

  const submitDraft = vi
    .spyOn(changesApi, 'submitDraft')
    .mockResolvedValue({ id: '74' } as ChangeProposal)
  await user.click(checkbox())
  expect(group).not.toHaveTextContent('확인한 뒤 검토 상태가 바뀌었어요.')
  await user.click(button)
  await waitFor(() =>
    expect(submitDraft).toHaveBeenCalledWith('101', {
      draft_id: '900',
      expected_draft_revision: 1,
      review_acknowledged: true,
      expected_review_state_hash: 'state-1-done',
    }),
  )
})

it('확인한 뒤 상태가 바뀌어 409 version_conflict가 나면 바뀐 상태를 다시 보이고 다시 확인받는다', async () => {
  renderFlow()
  const user = await registerAddedLine()
  await user.click(screen.getByRole('button', { name: '검토 없이 다음' }))
  vi.spyOn(changesApi, 'submitDraft').mockRejectedValueOnce(
    conflict('version_conflict'),
  )
  // 그 사이 다른 창에서 검토를 받아 처리하지 않은 수정안이 생겼다.
  withReview()
  await user.click(
    screen.getByRole('checkbox', { name: '이 상태 그대로 올릴게요' }),
  )
  await user.click(screen.getByRole('button', { name: '팀장에게 올리기' }))
  expect(await screen.findByRole('alert')).toHaveTextContent(
    '확인한 뒤 작성 중 제안이나 검토 상태가 바뀌었어요',
  )
  const group = await screen.findByRole('group', {
    name: '올리기 전 확인할 검토 상태',
  })
  await waitFor(() =>
    expect(group).toHaveTextContent('처리하지 않은 수정안이 1개 있어요.'),
  )
  expect(
    within(group).getByRole('checkbox', { name: '이 상태 그대로 올릴게요' }),
  ).not.toBeChecked()
  expect(screen.getByRole('button', { name: '팀장에게 올리기' })).toBeDisabled()
})

it('기준이 바뀌어 409 baseline_conflict가 나면 최신 기준과 다시 비교해 CHG-A18로 저장하게 한다', async () => {
  renderFlow()
  const user = await registerAddedLine()
  await user.click(screen.getByRole('button', { name: '검토 없이 다음' }))
  vi.spyOn(changesApi, 'submitDraft').mockRejectedValueOnce(
    conflict('baseline_conflict'),
  )
  await user.click(
    screen.getByRole('checkbox', { name: '이 상태 그대로 올릴게요' }),
  )
  await user.click(screen.getByRole('button', { name: '팀장에게 올리기' }))
  await user.click(
    await screen.findByRole('button', { name: '최신 기준과 다시 비교' }),
  )
  // 고친 글은 그대로 두고 기준만 다시 받아 비교한다.
  await waitFor(() =>
    expect(screen.getByLabelText('고친 글')).toHaveValue(
      'REQ-001 이메일로 가입한다.\nREQ-002 환영 메일을 보낸다.',
    ),
  )
  await user.click(screen.getByRole('button', { name: '등록하고 변경점 찾기' }))
  await waitFor(() =>
    expect(changesApi.updateDraft).toHaveBeenCalledWith(
      '101',
      '900',
      expect.objectContaining({
        expected_revision: 1,
        base_baseline_id: '401',
      }),
    ),
  )
  expect(
    await screen.findByRole('heading', { name: '바뀐 곳 1개를 찾았어요' }),
  ).toBeInTheDocument()
})

it('창을 닫았다 열면 작성 중 제안과 검토 결과를 이어서 본다', async () => {
  current = toDraft(
    {
      base_baseline_id: '401',
      title: '가입 규칙 바꾸기',
      reason: '규칙이 바뀌었어요.',
      items: [],
      documents: [
        {
          document_id: '203',
          base_document_version_id: '301',
          after_content:
            'REQ-001 이메일로 가입한다.\nREQ-002 환영 메일을 보낸다.',
        },
      ],
    },
    1,
  )
  withReview()
  localStorage.setItem('beez.changeDraft.101', '900')
  const user = userEvent.setup()
  renderFlow()
  const banner = (await screen.findByText(/작성 중인 제안이 있어요/)).closest(
    '[role="status"]',
  ) as HTMLElement
  expect(banner).toHaveTextContent('작성 중인 제안이 있어요: 가입 규칙 바꾸기')
  await user.click(within(banner).getByRole('button', { name: '이어서 하기' }))
  expect(
    await screen.findByRole('heading', { name: '비비가 검토를 마쳤어요' }),
  ).toBeInTheDocument()
  expect(screen.getByText('실패했을 때 안내가 없어요.')).toBeInTheDocument()
})

it('검토 실패·시간 초과와 평가하지 않은 영향은 0건으로 보이지 않고 다시 요청할 수 있다', async () => {
  renderFlow()
  const user = await registerAddedLine()
  const request = vi
    .spyOn(changesApi, 'requestDraftReview')
    .mockImplementation(async () => {
      withReview({
        status: 'FAILED',
        error_code: 'review_timeout',
        steps: [
          { key: 'READ_CHANGES', status: 'DONE', count: 1 },
          { key: 'FIND_RELATED', status: 'DONE', count: 0 },
          { key: 'AI_CANDIDATES', status: 'NOT_EVALUATED' },
          { key: 'GAPS_CONFLICTS', status: 'FAILED' },
        ],
        suggestions: [],
      })
      return current!.latest_review!
    })
  await user.click(screen.getByRole('button', { name: '비비 검토 받기' }))
  expect(
    await screen.findByRole('heading', { name: '비비 검토가 끝나지 못했어요' }),
  ).toBeInTheDocument()
  expect(
    screen.getByText(/10분 안에 끝나지 않아 검토를 멈췄어요/),
  ).toBeInTheDocument()
  const steps = screen.getByRole('list', { name: '비비 검토 단계' })
  expect(steps).toHaveTextContent('AI 영향 후보 찾기확인하지 못함')
  expect(steps).toHaveTextContent('빠진 부분·충돌 정리실패')
  await user.click(screen.getByRole('button', { name: '다시 검토 받기' }))
  expect(request).toHaveBeenCalledTimes(2)

  // 완료여도 관계 찾기를 못 했거나 AI 후보를 평가하지 않았으면 '확인하지 못함'이다.
  request.mockImplementation(async () => {
    withReview({
      steps: [
        { key: 'READ_CHANGES', status: 'DONE', count: 1 },
        { key: 'FIND_RELATED', status: 'FAILED' },
        { key: 'AI_CANDIDATES', status: 'NOT_EVALUATED' },
        { key: 'GAPS_CONFLICTS', status: 'DONE', count: 0 },
      ],
      tasks_to_wait: [],
      suggestions: [],
    })
    return current!.latest_review!
  })
  await user.click(screen.getByRole('button', { name: '다시 검토 받기' }))
  await screen.findByRole('heading', { name: '비비가 검토를 마쳤어요' })
  expect(screen.getByText('영향 확인 못함')).toBeInTheDocument()
  expect(
    screen.getByText('관련 작업·검증을 확인하지 못했어요.'),
  ).toBeInTheDocument()
  expect(
    screen.getByText(
      /확인하지 못했어요. 문서 변경은 아직 AI가 평가하지 않아요./,
    ),
  ).toBeInTheDocument()
  expect(screen.queryByText('영향 작업 0')).not.toBeInTheDocument()
})

it('반려된 제안을 고치면 그 제안을 보완하는 초안을 만들고 CHG-A04로 같은 제안에 올린다', async () => {
  const source = {
    id: '71',
    revision: 2,
    snapshot_hash: `sha256:${'b'.repeat(64)}`,
  } as ChangeProposal
  const { onSubmitted } = renderFlow({}, source)
  const user = await registerAddedLine()
  expect(changesApi.createDraft).toHaveBeenCalledWith(
    '101',
    expect.objectContaining({ source_proposal_id: '71' }),
  )
  expect(localStorage.getItem('beez.changeDraft.101.71')).toBe('900')
  await user.click(screen.getByRole('button', { name: '검토 없이 다음' }))
  const resubmit = vi
    .spyOn(changesApi, 'resubmitDraft')
    .mockResolvedValue({ id: '71', revision: 3 } as ChangeProposal)
  await user.click(
    screen.getByRole('checkbox', { name: '이 상태 그대로 올릴게요' }),
  )
  await user.click(screen.getByRole('button', { name: '팀장에게 올리기' }))
  await waitFor(() =>
    expect(resubmit).toHaveBeenCalledWith('101', '71', {
      draft_id: '900',
      expected_draft_revision: 1,
      review_acknowledged: true,
      expected_revision: 2,
      expected_snapshot_hash: source.snapshot_hash,
    }),
  )
  expect(onSubmitted).toHaveBeenCalledWith({ id: '71', revision: 3 })
})
