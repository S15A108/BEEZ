import { expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AxiosError, AxiosHeaders } from 'axios'
import { DraftRequest } from './DraftRequest'

const generation = {
  origin: 'MODEL' as const,
  text: '초안',
  sources: [],
  unresolved: [] as string[],
}

function failure(status: number, code: string) {
  return new AxiosError('failed', 'ERR_BAD_RESPONSE', undefined, undefined, {
    status,
    statusText: '',
    headers: {},
    config: { headers: new AxiosHeaders() },
    data: { error: { code, message: 'server text' } },
  })
}

function setup(
  options: {
    ready?: boolean
    untouched?: boolean
    request?: (instruction: string) => Promise<never> | Promise<unknown>
  } = {},
) {
  const request = vi.fn(
    options.request ??
      (() => Promise.resolve({ after_content: '새 초안', generation })),
  )
  const onApply = vi.fn()
  render(
    <DraftRequest
      ready={options.ready ?? true}
      notReadyReason="기준 원문을 받은 뒤에 쓸 수 있어요."
      request={request as never}
      isUntouched={() => options.untouched ?? true}
      onApply={onApply}
      titleOf={() => '요구사항 명세서'}
    />,
  )
  return { request, onApply, user: userEvent.setup() }
}

async function ask(
  user: ReturnType<typeof userEvent.setup>,
  text = '오류 응답 설명을 보완해 주세요.',
) {
  await user.click(screen.getByRole('button', { name: '비비에게 초안 받기' }))
  await user.type(screen.getByLabelText('어떻게 고칠지 알려 주세요'), text)
  await user.click(screen.getByRole('button', { name: '초안 만들기' }))
}

it('기준 원문을 받기 전에는 버튼을 막고 이유를 알려 준다', () => {
  setup({ ready: false })
  expect(
    screen.getByRole('button', { name: '비비에게 초안 받기' }),
  ).toBeDisabled()
  expect(
    screen.getByText('기준 원문을 받은 뒤에 쓸 수 있어요.'),
  ).toBeInTheDocument()
})

it('수정 요청을 비워 두면 만들기를 막는다', async () => {
  const { user, request } = setup()
  await user.click(screen.getByRole('button', { name: '비비에게 초안 받기' }))
  expect(screen.getByRole('button', { name: '초안 만들기' })).toBeDisabled()
  expect(request).not.toHaveBeenCalled()
})

it('손대지 않은 글은 초안으로 채우고 승인 설계가 아니라고 알린다', async () => {
  const { user, request, onApply } = setup()
  await ask(user)
  await waitFor(() => expect(onApply).toHaveBeenCalledWith('새 초안'))
  expect(request).toHaveBeenCalledWith('오류 응답 설명을 보완해 주세요.')
  expect(
    screen.getByText('초안을 고친 글에 넣었어요. 꼭 읽어 보고 고쳐 주세요.'),
  ).toBeInTheDocument()
  expect(screen.getByText(/승인된 설계가 아니에요/)).toBeInTheDocument()
})

it('손댄 글은 덮어쓰지 않고 묻고, 바꾸기를 누를 때만 초안을 넣는다', async () => {
  const { user, onApply } = setup({ untouched: false })
  await ask(user)
  expect(
    await screen.findByText(/지금 쓰고 있는 글이 있어서 바꾸지 않았어요/),
  ).toBeInTheDocument()
  expect(onApply).not.toHaveBeenCalled()
  await user.click(screen.getByRole('button', { name: '초안으로 바꾸기' }))
  expect(onApply).toHaveBeenCalledWith('새 초안')
  expect(
    screen.getByText('초안을 고친 글에 넣었어요. 꼭 읽어 보고 고쳐 주세요.'),
  ).toBeInTheDocument()
})

it('손댄 글을 지키려고 버리기를 누르면 아무것도 넣지 않는다', async () => {
  const { user, onApply } = setup({ untouched: false })
  await ask(user)
  await user.click(await screen.findByRole('button', { name: '버리기' }))
  expect(onApply).not.toHaveBeenCalled()
  expect(screen.queryByText(/바꾸지 않았어요/)).not.toBeInTheDocument()
})

it('일부만 반영했으면 이유와 참고한 곳을 보여 준다', async () => {
  const { user } = setup({
    request: () =>
      Promise.resolve({
        after_content: '새 초안',
        generation: {
          ...generation,
          unresolved: ['관련 설계 항목 63개 중 50개만 전달했어요.'],
          sources: [
            {
              document_id: '203',
              document_version_id: '2031',
              version_no: 2,
              location: 'L3-L8',
            },
          ],
        },
      }),
  })
  await ask(user)
  expect(
    await screen.findByText('관련 설계 항목 63개 중 50개만 전달했어요.'),
  ).toBeInTheDocument()
  expect(screen.getByText(/요구사항 명세서 v2 · L3-L8/)).toBeInTheDocument()
})

it('요청하는 동안 진행 중임을 알리고 버튼을 막는다', async () => {
  let resolve: (value: unknown) => void = () => undefined
  const { user } = setup({
    request: () => new Promise((done) => (resolve = done)),
  })
  await ask(user)
  expect(await screen.findByRole('status')).toHaveTextContent(
    '비비가 초안을 쓰는 중이에요',
  )
  expect(screen.getByRole('button', { name: '초안 만들기' })).toBeDisabled()
  resolve({ after_content: '새 초안', generation })
  await waitFor(() =>
    expect(
      screen.queryByText(/비비가 초안을 쓰는 중이에요/),
    ).not.toBeInTheDocument(),
  )
})

it.each([
  [502, 'ai_unavailable', '비비가 지금 초안을 만들 수 없어요'],
  [504, 'ai_timeout', '문서가 길면 더 걸릴 수 있어요'],
  [409, 'version_conflict', '그사이 문서나 관련 설계 항목이 바뀌었어요'],
  [409, 'baseline_missing', '기준 설계가 아직 없어서'],
  [409, 'analysis_not_completed', '원문 준비가 끝나지 않았어요'],
])(
  '%s %s 오류는 서버 문구가 아니라 할 일을 알려 준다',
  async (status, code, text) => {
    const { user, onApply } = setup({
      request: () => Promise.reject(failure(status, code)),
    })
    await ask(user)
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(text)
    expect(alert).not.toHaveTextContent('server text')
    expect(onApply).not.toHaveBeenCalled()
    // 실패해도 입력은 남아 같은 요청을 다시 보낼 수 있다.
    expect(screen.getByLabelText('어떻게 고칠지 알려 주세요')).toHaveValue(
      '오류 응답 설명을 보완해 주세요.',
    )
    expect(screen.getByRole('button', { name: '초안 만들기' })).toBeEnabled()
  },
)
