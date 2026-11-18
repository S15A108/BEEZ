// 변경 제안 문서 본문 정규화(PR #46, DOC-A12와 같은 규칙): 맨 앞 BOM을 빼고 CRLF를 LF로 바꾼다.
export const normalizeDocument = (text: string) =>
  text.replace(/^\uFEFF/, '').replace(/\r\n/g, '\n')

// 서버 DocumentInputValidator(CHG-A01 stage)와 같은 본문 규칙: 정규화한 본문이 100,000자(코드 포인트)를 넘으면
// 413 file_too_large, 줄바꿈·탭 말고 ISO 제어 문자(U+0000~001F, U+007F~009F)가 있으면 400으로 거절한다.
// 서버는 홀로 있는 CR도 LF로 바꾼 뒤 검사하므로 CR은 허용한다. 1 MiB 바이트 한도는 100,000자 안에서 넘을 수 없어 따로 보지 않는다.
// eslint-disable-next-line no-control-regex -- 제어 문자를 찾는 것이 목적이다.
const CONTROL = /[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F-\u009F]/
const MAX_CHARACTERS = 100_000

export function documentTextError(text: string) {
  const normalized = normalizeDocument(text)
  if (CONTROL.test(normalized))
    return '글에 쓸 수 없는 제어 문자가 들어 있어요.'
  // UTF-16 길이가 한도 안이면 코드 포인트도 한도 안이라 긴 글만 센다.
  if (
    normalized.length > MAX_CHARACTERS &&
    [...normalized].length > MAX_CHARACTERS
  )
    return '고친 글은 100,000자까지 쓸 수 있어요.'
}
