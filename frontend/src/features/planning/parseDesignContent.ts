// 설계 항목 내용(JSON 객체)을 입력 칸의 글에서 읽는다. 서버 규칙(DesignContentValidator)은 서버 한 곳에서 지키고,
// 여기서는 서버까지 가지 않아도 알 수 있는 것(JSON 문법, 객체 여부, description)만 미리 알려 준다.
export const DESIGN_ITEM_SCHEMA_VERSION = 1

export const DESIGN_CONTENT_GUIDE =
  '요구사항·정책·제약은 description, API 계약은 description·method·path, 데이터 계약은 description·entity·fields(배열)를 JSON 객체로 적어요.'

export const DESIGN_CONTENT_PLACEHOLDER =
  '{"description": "로그인할 수 있어야 한다"}'

export const DESIGN_CONTENT_EXAMPLES = [
  '요구사항: {"description": "로그인할 수 있어야 한다"}',
  'API 계약: {"description": "프로젝트 목록 조회", "method": "GET", "path": "/api/projects"}',
  '데이터 계약: {"description": "프로젝트", "entity": "Project", "fields": ["id", "name"]}',
].join('\n')

function parseJson(text: string): unknown {
  try {
    return JSON.parse(text)
  } catch {
    return undefined
  }
}

// 틀린 곳이 없으면 undefined, 있으면 칸 아래에 보일 문구.
export function checkDesignContent(text: string): string | undefined {
  const value = parseJson(text)
  if (value === undefined)
    return 'JSON 형식으로 입력해 주세요. 예: {"description": "설명"}'
  if (value === null || typeof value !== 'object' || Array.isArray(value))
    return '중괄호({ })로 감싼 JSON 객체여야 해요.'
  const description = (value as Record<string, unknown>).description
  if (typeof description !== 'string' || !description.trim())
    return 'description에 설명을 문자열로 입력해 주세요.'
  return undefined
}

// 검사를 통과한 글만 넘긴다(checkDesignContent가 undefined일 때).
export function parseDesignContent(text: string): Record<string, unknown> {
  return JSON.parse(text) as Record<string, unknown>
}
