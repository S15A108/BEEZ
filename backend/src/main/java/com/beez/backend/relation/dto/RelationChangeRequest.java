package com.beez.backend.relation.dto;

import java.util.List;

/**
 * 관계 저장 서비스 한 번 호출의 입력. 발행·변경 승인 트랜잭션 안에서 한 번 부르는 것을 기준으로 한다.
 * 처리 순서: ① recheckItemIds 에 걸린 확정 관계를 재확인 필요로 표시 → ② commands 를 순서대로 적용.
 * 그래서 같은 호출에서 재확인 필요로 바뀐 관계를 바로 KEEP·RELEASE 할 수 있다.
 *
 * @param baselineId       유효 기준. 확정·유지·재확인 표시가 있으면 필수. 그 기준의 항목 버전을 이력에 남긴다
 *                         (발행 중이면 아직 현재 기준이 아니어도 된다 — 같은 프로젝트 기준이면 된다)
 * @param changeProposalId 관련 변경 제안 (없으면 null, 최초 발행 등)
 * @param recheckItemIds   이번 변경으로 내용이 바뀌거나 빠진 항목. 이 항목이 끝에 걸린 확정 관계를 재확인 필요로 표시한다
 * @param recheckReason    재확인 표시 사유 (선택)
 * @param commands         쌍 단위 변경
 */
public record RelationChangeRequest(Long baselineId, Long changeProposalId, List<Long> recheckItemIds,
                                    String recheckReason, List<RelationCommand> commands) {

    /** null 목록은 빈 목록으로 받는다 */
    public RelationChangeRequest {
        if (recheckItemIds == null) {
            recheckItemIds = List.of();
        }
        if (commands == null) {
            commands = List.of();
        }
    }
}
