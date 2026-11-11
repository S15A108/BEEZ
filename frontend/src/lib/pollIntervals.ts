// TanStack Query refetchInterval 로 쓰는 다시 읽기 주기를 한곳에 모은다.
// 테스트(vitest, MODE=test)에서는 진행을 따라가는 주기만 수십 ms 로 줄인다. 운영 주기를 실제 시간으로 기다리면
// 전체 시험이 느려지고, 부하가 걸리면 5초 제한을 넘겨 간헐적으로 실패했다(S15P31A108-351).
// import.meta.env.MODE 는 빌드 때 상수로 바뀌므로 운영 번들에는 운영 값만 남는다.
const inTest = import.meta.env.MODE === 'test'

/** 분석·검증·초안 생성·영향 분석처럼 곧 끝날 작업이 도는 동안 따라가는 주기. */
export const ACTIVE_POLL_MS = inTest ? 20 : 1500
/** 서버 워커가 대기 중인 문서를 찾는 주기(beez.planning.poll-millis, 기본 3초)에 맞춘다. */
export const WAITING_POLL_MS = inTest ? 40 : 3000
/** 다른 창이나 다른 사람이 돌린 비비 문서 검토가 끝났는지 보는 주기. */
export const REVIEW_POLL_MS = inTest ? 40 : 3000

// 아래 둘은 작업 완료를 기다리는 주기가 아니라 화면을 늦게나마 맞추는 배경 갱신이다. 시험은 가짜 타이머로
// 이 값을 직접 확인하므로(beeStatusQuery·sceneQuery 시험) 테스트에서도 줄이지 않는다.
/** 발행된 제안의 사후 분석처럼 오래 걸리는 작업을 가끔 확인하는 주기. */
export const SLOW_POLL_MS = 15_000
/** 비비 상태·사무실 장면처럼 계속 띄워 두는 화면을 새로 맞추는 주기. */
export const BACKGROUND_POLL_MS = 30_000
/**
 * 비비 검토가 실패한 문서를 보는 팀원 창이 올린 사람의 재검토를 SLOW_POLL_MS로 따라가는 기간.
 * 서버가 오래 걸린 검토를 실패로 보는 기준(10분)과 같다(S15P31A108-383).
 */
export const FAILED_REVIEW_FOLLOW_MS = 10 * 60_000

/**
 * 보완 질문 생성이 이만큼 이어지면 '평소보다 오래 걸려요'를 덧붙인다. 서버 워커는 문서를 한 번에 하나씩 처리해
 * 시간으로 끊지 않지만(S15P31A108-202), 사용자가 끝없이 기다리지 않게 상태를 직접 새로 확인할 길을 준다.
 */
export const SLOW_GENERATION_MS = inTest ? 150 : 90_000
