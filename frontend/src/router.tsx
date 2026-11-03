import { createBrowserRouter } from 'react-router'
import App from './App'

export const router = createBrowserRouter([
  {
    path: '/',
    element: <App />,
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
        path: 'reset-password',
        lazy: async () => ({
          Component: (await import('@/pages/PasswordResetPage')).default,
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
