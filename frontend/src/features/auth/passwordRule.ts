// 회원가입·비밀번호 변경·비밀번호 찾기 공통 규칙: 8자 이상, UTF-8 72바이트 이하(bcrypt가 읽는 길이), 영문·숫자 각 1자 이상.
// 서버 PasswordPolicy와 같다(Q04, D23). 오류는 상황별로 나눈다(사용자 결정 2026-11-02).
export const PASSWORD_MAX_BYTES = 72
export const PASSWORD_HINT = '영문과 숫자를 모두 넣어 8자 이상'

export function passwordRule(value: string) {
  if (value.length < 8) return '8자 이상으로 입력하세요.'
  if (new TextEncoder().encode(value).length > PASSWORD_MAX_BYTES)
    return '비밀번호가 너무 길어요. 더 짧게 입력해 주세요.'
  // 틀리면 규칙과 빠진 것을 함께 알려 준다(캔버스 SCR-01-v2-register, SCR-05-v2-password).
  const missing = [
    !/[A-Za-z]/.test(value) && '영문',
    !/\d/.test(value) && '숫자',
  ].filter(Boolean)
  if (missing.length)
    return `${PASSWORD_HINT}으로 입력하세요. ${missing.join(', ')}${missing.at(-1) === '영문' ? '이' : '가'} 없어요.`
}
