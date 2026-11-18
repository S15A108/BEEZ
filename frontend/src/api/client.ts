import axios from 'axios'

/** 웹 인증은 공통 로그인 API가 발급하는 세션 쿠키를 사용한다. */
export const api = axios.create({
  baseURL: '/api',
  withCredentials: true,
})
