package com.beez.backend.designchange.dto;

import java.time.Instant;
import java.util.List;

/**
 * 발행 한 건의 영향 분석 결과가 정해졌다는 알림 (S15P31A108-221, MCP-03 impact_updates).
 *
 * @param resultSeq           프로젝트 안 결과 순번. 같은 순번을 두 번 받으면 같은 결과다
 * @param baseline            분석한 발행
 * @param proposalId          그 발행의 변경 제안. 제안 없이 발행됐으면 없다
 * @param analysisStatus      COMPLETED | NOT_EVALUATED | FAILED. 미평가·실패는 "영향 0건"이 아니다
 * @param evaluationStatus    COMPLETED | INSUFFICIENT_EVIDENCE | NOT_EVALUATED. 분석이 끝났을 때 근거가 충분했는지
 * @param errorCode           실패·미평가 사유
 * @param candidates          영향 후보 (최대 10개, 규칙 후보 먼저)
 * @param candidatesTruncated 10개를 넘어 빠진 후보 수. 0 보다 크면 전체를 본 것이 아니다
 * @param analyzedAt          결과가 정해진 시각
 */
public record ImpactUpdateResponse(
        long resultSeq,
        BaselineSummaryResponse baseline,
        String proposalId,
        String analysisStatus,
        String evaluationStatus,
        String errorCode,
        List<PublishedImpactCandidateResponse> candidates,
        int candidatesTruncated,
        Instant analyzedAt
) {
}
