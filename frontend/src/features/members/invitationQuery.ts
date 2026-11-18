// 초대 흐름의 쿼리(?invite=토큰&project=프로젝트 ID). 로그인·가입을 오가도 그대로 넘겨, 돌아온 초대 화면이
// 이미 팀원(409 already_member)일 때 그 프로젝트로 보낼 수 있게 한다. 옛 링크(/invite?token=)는 project가 없다.
export function invitationQuery(params: URLSearchParams): string {
  const invite = params.get('invite') ?? params.get('token')
  if (!invite) return ''
  const project = params.get('project')
  return `?${new URLSearchParams(project ? { invite, project } : { invite })}`
}
