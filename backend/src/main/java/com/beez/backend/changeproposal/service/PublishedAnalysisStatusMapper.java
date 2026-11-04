package com.beez.backend.changeproposal.service;

import static com.beez.backend.designitem.support.DesignJson.time;

import com.beez.backend.changeproposal.dto.ProposalDtos.PublishedAnalysisStatus;
import java.util.Map;

/** Maps a stored publication intake without treating processing completion as a sufficient evaluation. */
final class PublishedAnalysisStatusMapper {
    private PublishedAnalysisStatusMapper() { }

    static PublishedAnalysisStatus from(Map<String, Object> row, Boolean currentBaseline) {
        String status = row.get("status").toString();
        String evaluation = nullable(row.get("evaluation_status"));
        boolean expired = Boolean.TRUE.equals(row.get("lease_expired"));
        String guidance = switch (status) {
            case "PENDING" -> row.get("next_attempt_at") == null ? "WAIT" : "RETRY_SCHEDULED";
            case "RUNNING" -> expired ? "RECOVERY_PENDING" : "WAIT";
            case "FAILED" -> "CONTACT_OPERATOR";
            case "NOT_EVALUATED" -> "REVIEW_REASON";
            case "COMPLETED" -> "COMPLETED".equals(evaluation) ? "NONE"
                    : "INSUFFICIENT_EVIDENCE".equals(evaluation) ? "REVIEW_EVIDENCE" : "CHECK_RESULT";
            default -> "CHECK_RESULT";
        };
        return new PublishedAnalysisStatus(status, evaluation, nullable(row.get("error_code")),
                ((Number) row.get("attempt")).intValue(), timeOrNull(row.get("requested_at")),
                timeOrNull(row.get("started_at")), timeOrNull(row.get("finished_at")), timeOrNull(row.get("next_attempt_at")),
                "RUNNING".equals(status) ? expired : null, currentBaseline, guidance);
    }

    static PublishedAnalysisStatus missing(Boolean currentBaseline) {
        return new PublishedAnalysisStatus("UNKNOWN", null, null, null, null, null, null, null,
                null, currentBaseline, "CHECK_RESULT");
    }

    private static String nullable(Object value) { return value == null ? null : value.toString(); }
    private static String timeOrNull(Object value) { return value == null ? null : time(value); }
}
