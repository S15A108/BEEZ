import { AxiosError, AxiosHeaders } from 'axios'
import { render, screen } from '@testing-library/react'
import { expect, it, vi } from 'vitest'
import { MISSING_API_MESSAGE } from '@/api/error'
import { AsyncState } from './AsyncState'

const failed = (error: Error) => ({
  data: undefined,
  isPending: false,
  error,
  refetch: vi.fn(),
})

it('서버에 아직 없는 API면 오류 대신 그 칸만 준비 중으로 보인다', () => {
  const missing = new AxiosError(
    'Not Found',
    'ERR_BAD_REQUEST',
    undefined,
    undefined,
    {
      status: 404,
      statusText: 'Not Found',
      headers: {},
      config: { headers: new AxiosHeaders() },
      data: { error: { code: 'not_found', message: MISSING_API_MESSAGE } },
    },
  )
  render(<AsyncState query={failed(missing)}>{() => <p>내용</p>}</AsyncState>)
  expect(screen.getByRole('status')).toHaveTextContent(
    '이 기능은 서버에서 아직 준비 중이에요.',
  )
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
})

it('그 밖의 오류는 다시 불러오기를 보여 준다', () => {
  render(
    <AsyncState query={failed(new Error('boom'))}>
      {() => <p>내용</p>}
    </AsyncState>,
  )
  expect(screen.getByRole('alert')).toHaveTextContent(
    '정보를 불러오지 못했어요',
  )
})
