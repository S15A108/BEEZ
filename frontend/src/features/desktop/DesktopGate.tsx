import { useLocation } from 'react-router'
import App from '@/App'
import { useIsDesktop } from '@/lib/useIsDesktop'
import { DesktopOnlyNotice } from './DesktopOnlyNotice'

// 좁은 화면에서도 여는 주소. 메일 링크(초대 수락)와 로그인·가입(비밀번호 찾기·이메일 인증 포함)이다.
const NARROW_ALLOWED_PATHS = ['/login', '/register', '/invite']

function isAllowedOnNarrow(pathname: string) {
  const path = pathname.length > 1 ? pathname.replace(/\/+$/, '') : pathname
  return NARROW_ALLOWED_PATHS.includes(path)
}

// App 바깥에서 가른다. 안내 화면에서는 App(/me 조회)과 페이지(캔버스·API)가 아예 올라오지 않는다.
export default function DesktopGate() {
  const isDesktop = useIsDesktop()
  const { pathname } = useLocation()
  if (isDesktop || isAllowedOnNarrow(pathname)) return <App />
  return <DesktopOnlyNotice />
}
