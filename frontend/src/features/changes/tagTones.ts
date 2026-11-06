// 변경 제안 화면의 칩(캔버스 PNL-04-v2 통일감 개선: 높이 22px, 12px·600, 상태마다 색). 작업 칩과 상태 칩이 같은 모양을 쓴다.
export const operationTone = {
  UPDATE: 'bg-[#fdf0c8] text-[#6e5200]',
  CREATE: 'bg-[#e8f3ec] text-[#00753a]',
  RETIRE: 'bg-[#fdecec] text-[#b42318]',
} as const
export const statusTone: Record<string, string> = {
  PROPOSED: operationTone.UPDATE,
  IN_REVIEW: 'bg-[#e6effa] text-[#1d4f91]',
  APPROVED: operationTone.CREATE,
  PUBLISHED: operationTone.CREATE,
  REJECTED: operationTone.RETIRE,
}
