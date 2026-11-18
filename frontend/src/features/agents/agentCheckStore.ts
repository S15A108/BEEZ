import { create } from 'zustand'

// '연결 확인'을 누른 팀원. 연결 실패는 서버 값이 아니라 이 확인의 결과라, 상태를 보이는 화면이 모두 여기를 같이 본다.
// '방금 확인'이 오래 남지 않게 워크스페이스를 나가면 지운다(SettingsDialog).
export const useAgentCheckStore = create<{
  checkedMemberId: string | null
  setCheckedMember: (memberId: string | null) => void
}>((set) => ({
  checkedMemberId: null,
  setCheckedMember: (checkedMemberId) => set({ checkedMemberId }),
}))
