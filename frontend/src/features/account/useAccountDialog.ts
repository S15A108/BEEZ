import { useLocation, useNavigate, useSearchParams } from 'react-router'

export type AccountView = 'profile' | 'password' | 'withdraw'

const PARAM = 'account'
/** 회원 설정 창의 요청(프로필 저장·토큰 폐기·비밀번호 변경·탈퇴). 이 요청이 도는 동안만 창을 닫지 않는다. */
export const ACCOUNT_MUTATION_KEY = ['account-dialog']
const views: readonly string[] = ['profile', 'password', 'withdraw']

// 회원 설정 창(D48)은 지금 주소의 ?account=에 상태를 둔다. 나의 도시든 사무실이든 그 화면과 경로를 두고 위에 뜨며,
// 새로고침해도 남고 뒤로 가기로 닫힌다. 열거나 하위 화면으로 갈 때 쌓은 기록 수를 history state(accountDepth)에 적어,
// 닫을 때 그만큼 되돌린다. 옛 주소(/account…)나 링크로 바로 들어와 쌓은 기록이 없으면 주소만 바꾼다.
export function useAccountDialog() {
  const [params] = useSearchParams()
  const location = useLocation()
  const navigate = useNavigate()
  const raw = params.get(PARAM)
  const view: AccountView | null =
    raw === null ? null : views.includes(raw) ? (raw as AccountView) : 'profile'
  const depth =
    (location.state as { accountDepth?: number } | null)?.accountDepth ?? 0
  const hrefOf = (next: AccountView | null) => {
    const search = new URLSearchParams(location.search)
    if (next) search.set(PARAM, next)
    else search.delete(PARAM)
    const query = search.toString()
    return `${location.pathname}${query ? `?${query}` : ''}`
  }
  return {
    view,
    /** 회원 설정을 여는 링크. 헤더 링크·이름 메뉴·배너·설정 창이 같이 쓴다. */
    linkTo: (next: AccountView) => ({
      to: hrefOf(next),
      state: { accountDepth: depth + 1 },
    }),
    /** 창 안에서 하위 화면으로 간다. 뒤로 가기로 돌아온다. */
    show: (next: AccountView) =>
      navigate(hrefOf(next), { state: { accountDepth: depth + 1 } }),
    /** 하위 화면에서 회원 설정으로 돌아간다. */
    back: () =>
      depth > 1
        ? navigate(-1)
        : navigate(hrefOf('profile'), { replace: true, state: location.state }),
    close: () =>
      depth > 0 ? navigate(-depth) : navigate(hrefOf(null), { replace: true }),
  }
}
