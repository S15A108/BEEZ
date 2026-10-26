import axios from 'axios'

/** 웹 인증은 세션 쿠키다. CSRF의 헤더명·쿠키명은 계약 Q04 확정 후 설정한다. */
export const api = axios.create({
  baseURL: '/api',
  withCredentials: true,
})
