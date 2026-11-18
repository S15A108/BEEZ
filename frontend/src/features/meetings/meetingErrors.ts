import { getApiErrorCode, getApiErrorMessage } from '@/api/error'

// 회의 정리·수정 요청·끝내기(MTG-A11~A13)의 오류 안내. 공용 오류 문구(api/error.ts)에 없는 회의 전용 문구만 여기 둔다.
// 회의는 열린 채로 남는 오류가 대부분이라 어떻게 이어 가면 되는지를 함께 알려 준다.
const messages: Record<string, string> = {
  summary_outdated:
    '정리한 뒤에 새 기록이 생겼거나 기준이 바뀌었어요. 비비에게 정리를 다시 요청해 주세요.',
  meeting_proposal_changed:
    '이미 올린 변경 제안이 그 사이 거두어졌거나 반려됐어요. 회의 끝내기를 다시 누르면 새 제안을 만들어요.',
  meeting_closed: '이미 끝난 회의예요.',
  baseline_missing: '먼저 첫 기준 설계를 만들어 주세요.',
  forbidden: '팀장만 할 수 있어요. 회의 참여자인지도 확인해 주세요.',
  validation_failed:
    '정리를 적용한 문서가 제출 규칙에 맞지 않아요. 비비에게 고칠 점을 말해 정리를 다시 요청해 주세요.',
  ai_unavailable: '비비가 지금 정리하지 못했어요. 잠시 뒤 다시 요청해 주세요.',
  ai_timeout: '정리가 오래 걸려 멈췄어요. 다시 요청해 주세요.',
}

export function meetingErrorMessage(error: unknown): string {
  const code = getApiErrorCode(error)
  return (code && messages[code]) || getApiErrorMessage(error)
}

/** 오래된 정리로는 끝낼 수 없다. 이 오류면 가지고 있던 정리를 버리고 다시 요청하게 한다. */
export const staleSummaryCode = 'summary_outdated'
export const closedMeetingCode = 'meeting_closed'
