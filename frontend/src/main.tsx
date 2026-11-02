import {
  MutationCache,
  QueryCache,
  QueryClient,
  QueryClientProvider,
} from '@tanstack/react-query'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { RouterProvider } from 'react-router/dom'
import './index.css'
import { router } from './router'
import { getApiErrorStatus, shouldRetryQuery } from '@/api/error'
import { reloadOnStaleChunk } from '@/lib/reloadOnStaleChunk'

reloadOnStaleChunk()

// 어느 API든 401이면 로그인이 만료된 것이라 /me(['account'])를 다시 묻는다. /me도 401이면 RequireAccount가
// 캐시를 지우고 로그인으로 보낸다(PR #72 재전수 SECOND-AMP-03).
const recheckSession = (error: unknown, key?: readonly unknown[]) => {
  if (getApiErrorStatus(error) === 401 && key?.[0] !== 'account')
    void queryClient.invalidateQueries({ queryKey: ['account'] })
}
// 5xx와 네트워크 오류만 재시도한다. 다시 불러도 같은 4xx(없는 API의 '준비 중' 포함)는 바로 보인다.
const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: shouldRetryQuery } },
  queryCache: new QueryCache({
    onError: (error, query) => recheckSession(error, query.queryKey),
  }),
  mutationCache: new MutationCache({
    onError: (error) => recheckSession(error),
  }),
})

async function bootstrap() {
  // Vite가 이 비교를 빌드 때 상수로 바꿔 live 빌드에는 mock 어댑터·예시 데이터 청크가 생기지 않는다.
  if (import.meta.env.VITE_API_MODE !== 'live') {
    const { setupMockApi } = await import('@/mocks/setup')
    setupMockApi()
  }
  // 이미지·링크를 끌어 옮기지 못하게 한다(-webkit-user-drag가 없는 Firefox까지). draggable="true"로 연 곳만 둔다.
  document.addEventListener('dragstart', (event) => {
    if (!(event.target as Element | null)?.closest?.('[draggable="true"]'))
      event.preventDefault()
  })

  createRoot(document.getElementById('root')!).render(
    <StrictMode>
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </StrictMode>,
  )
}

void bootstrap()
