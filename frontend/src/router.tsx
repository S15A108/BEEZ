import { Navigate, createBrowserRouter } from 'react-router'
import DesktopGate from '@/features/desktop/DesktopGate'

export const router = createBrowserRouter([
  {
    path: '/',
    element: <DesktopGate />,
    hydrateFallbackElement: (
      <p role="status" className="p-12 text-center">
        BEEZ를 준비하는 중…
      </p>
    ),
    children: [
      {
        index: true,
        lazy: async () => ({
          Component: (await import('@/pages/CityPage')).default,
        }),
      },
      {
        path: 'login',
        lazy: async () => ({
          Component: (await import('@/pages/AuthPage')).default,
        }),
      },
      {
        path: 'register',
        lazy: async () => ({
          Component: (await import('@/pages/RegisterPage')).default,
        }),
      },
      {
        path: 'projects/:projectId',
        lazy: async () => ({
          Component: (await import('@/pages/WorkspacePage')).default,
        }),
      },
      // 회원 설정은 페이지가 아니라 어디서나 여는 창이다(D48). 메일·북마크의 옛 주소는 나의 도시 위에 그 창을 연다.
      {
        path: 'account',
        element: <Navigate to="/?account=profile" replace />,
      },
      {
        path: 'account/password',
        element: <Navigate to="/?account=password" replace />,
      },
      {
        path: 'account/withdraw',
        element: <Navigate to="/?account=withdraw" replace />,
      },
      {
        path: 'invite',
        lazy: async () => ({
          Component: (await import('@/pages/InvitationPage')).default,
        }),
      },
      {
        // 설정 › 내 에이전트 관리에서 새 탭으로 연다(D43).
        path: 'help/agent-connection',
        lazy: async () => ({
          Component: (await import('@/pages/AgentHelpPage')).default,
        }),
      },
      {
        path: '*',
        lazy: async () => ({
          Component: (await import('@/pages/NotFoundPage')).default,
        }),
      },
    ],
  },
])
