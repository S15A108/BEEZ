import axios from 'axios'
import { apiMode } from '@/lib/apiMode'

/** 웹 인증은 세션 쿠키다. CSRF의 헤더명·쿠키명은 계약 Q04 확정 후 설정한다. */
export const api = axios.create({
  baseURL: '/api',
  withCredentials: true,
})

// Document mutations use the authenticated session's synchronizer token.
api.interceptors.request.use(async (config) => {
  const method = config.method?.toUpperCase() ?? 'GET'
  if (
    apiMode === 'live' &&
    !['GET', 'HEAD', 'OPTIONS'].includes(method) &&
    /\/projects\/[^/]+\/documents(?:\/|$)/.test(config.url ?? '')
  ) {
    const response = await api.get<{ csrf_token: string }>('/session/csrf')
    config.headers.set('X-CSRF-Token', response.data.csrf_token)
  }
  return config
})
