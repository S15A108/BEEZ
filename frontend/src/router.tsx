import { createBrowserRouter } from 'react-router'
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
      {
        path: 'account',
        lazy: async () => ({
          Component: (await import('@/pages/AccountPage')).default,
        }),
      },
      {
        path: 'account/password',
        lazy: async () => ({
          Component: (await import('@/pages/AccountPasswordPage')).default,
        }),
      },
      {
        path: 'account/withdraw',
        lazy: async () => ({
          Component: (await import('@/pages/AccountWithdrawPage')).default,
        }),
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
