// 변경 제안 문서 본문 정규화(PR #46, DOC-A12와 같은 규칙): 맨 앞 BOM을 빼고 CRLF를 LF로 바꾼다.
export const normalizeDocument = (text: string) =>
  text.replace(/^\uFEFF/, '').replace(/\r\n/g, '\n')
