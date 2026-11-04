package com.beez.backend.changeproposal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static com.beez.backend.designitem.support.DesignJson.JSON;

import com.beez.backend.changeproposal.dto.ProposalDtos.Detail;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PublishedAnalysisStatusMapperTest {
    private static Map<String, Object> row(String status) {
        var row = new HashMap<String, Object>();
        row.put("status", status);
        row.put("attempt", 1);
        row.put("requested_at", Timestamp.from(Instant.parse("2026-11-04T00:00:00Z")));
        return row;
    }

    @Test
    void retryAndExpiredLeaseAreDistinguishedFromOrdinaryWaiting() {
        var pending = row("PENDING");
        assertThat(PublishedAnalysisStatusMapper.from(pending, true).guidance()).isEqualTo("WAIT");
        pending.put("next_attempt_at", Timestamp.from(Instant.parse("2026-11-04T00:00:30Z")));
        var retry = PublishedAnalysisStatusMapper.from(pending, true);
        assertThat(retry.guidance()).isEqualTo("RETRY_SCHEDULED");
        assertThat(retry.nextAttemptAt()).isEqualTo("2026-11-04T00:00:30Z");
        assertThat(retry.evaluationStatus()).isNull();

        var running = row("RUNNING");
        running.put("lease_expired", true);
        var expired = PublishedAnalysisStatusMapper.from(running, false);
        assertThat(expired.guidance()).isEqualTo("RECOVERY_PENDING");
        assertThat(expired.leaseExpired()).isTrue();
        assertThat(expired.currentBaseline()).isFalse();
    }

    @Test
    void completedIntakeDoesNotImplySufficientEvidenceAndMissingIsUnknown() {
        var completed = row("COMPLETED");
        assertThat(PublishedAnalysisStatusMapper.from(completed, true).guidance()).isEqualTo("CHECK_RESULT");
        completed.put("evaluation_status", "INSUFFICIENT_EVIDENCE");
        var insufficient = PublishedAnalysisStatusMapper.from(completed, true);
        assertThat(insufficient.status()).isEqualTo("COMPLETED");
        assertThat(insufficient.guidance()).isEqualTo("REVIEW_EVIDENCE");
        completed.put("evaluation_status", "COMPLETED");
        assertThat(PublishedAnalysisStatusMapper.from(completed, true).guidance()).isEqualTo("NONE");
        assertThat(PublishedAnalysisStatusMapper.from(row("NOT_EVALUATED"), true).guidance()).isEqualTo("REVIEW_REASON");
        assertThat(PublishedAnalysisStatusMapper.from(row("FAILED"), true).guidance()).isEqualTo("CONTACT_OPERATOR");
        assertThat(PublishedAnalysisStatusMapper.missing(true).status()).isEqualTo("UNKNOWN");
    }

    @Test
    void existingDetailContractIncludesOptionalSnakeCasePublicationStatus() {
        var pending = row("PENDING");
        pending.put("next_attempt_at", Timestamp.from(Instant.parse("2026-11-04T00:00:30Z")));
        var response = new Detail(null, List.of(), List.of(), false, PublishedAnalysisStatusMapper.from(pending, true));
        var json = JSON.readTree(JSON.writeValueAsString(response));
        assertThat(json.get("published_analysis").get("status").asText()).isEqualTo("PENDING");
        assertThat(json.get("published_analysis").get("next_attempt_at").asText()).isEqualTo("2026-11-04T00:00:30Z");
        assertThat(json.get("published_analysis").hasNonNull("evaluation_status")).isFalse();
    }
}
