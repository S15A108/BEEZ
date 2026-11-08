import type { Member, RequiredDocument } from '@/api/contracts/common'

export const NO_DESCRIPTION = '비비와 프로젝트를 준비해 보세요.'

/** 시작 준비 중 프로젝트의 다음 할 일. 문서는 팀원도 올리고, 승인과 시작은 팀장만 한다. */
export function nextStepText(
  documents: readonly RequiredDocument[],
  isLeader: boolean,
) {
  if (documents.some((document) => !document.uploaded))
    return '다음: 문서 초안 올리기'
  if (documents.some((document) => !document.approved))
    return isLeader ? '다음: 비비 검토 후 승인하기' : '다음: 팀장 승인 기다리기'
  return isLeader ? '다음: 프로젝트 시작하기' : '다음: 팀장이 프로젝트 시작'
}

export function peopleText(
  members: readonly Pick<Member, 'agent_connected'>[],
) {
  const agents = members.filter((member) => member.agent_connected).length
  if (members.length > 1) return `${members.length}명 · 에이전트 ${agents}`
  return agents ? `혼자 사용 · 에이전트 ${agents}` : '혼자 사용'
}
