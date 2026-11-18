package com.beez.backend.changeproposal.service;

import static com.beez.backend.designitem.support.DesignJson.JSON;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.beez.backend.changeproposal.dto.ProposalDtos.Input;
import com.beez.backend.changeproposal.dto.ProposalDtos.Snapshot;
import com.beez.backend.changeproposal.repository.ProposalRepository;
import com.beez.backend.common.access.AccessErrors;
import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PublishedAnalysisDetailTest {
    private final ProjectAccessChecker access = mock(ProjectAccessChecker.class);
    private final ProposalRepository repository = mock(ProposalRepository.class);
    private final ProposalQueryService service = new ProposalQueryService(access, repository);
    private final CallerContext caller = CallerContext.human(9L);
    private final Map<String, Object> proposal = new HashMap<>();

    @BeforeEach
    void setup() {
        var input = new Input("11", "title", "reason", null, null, List.of(), List.of(), null, null, null);
        proposal.put("id", 22L); proposal.put("key", "CP-1"); proposal.put("project_id", 7L);
        proposal.put("status", "PUBLISHED"); proposal.put("proposer_account_id", 9L);
        proposal.put("current_baseline_id", 12L); proposal.put("published_baseline_id", 12L);
        proposal.put("snapshot_hash", "hash"); proposal.put("snapshot", JSON.writeValueAsString(new Snapshot(input, List.of(), false)));
        proposal.put("revision_no", 1); proposal.put("created_at", Timestamp.from(Instant.parse("2026-11-04T00:00:00Z")));
        proposal.put("updated_at", proposal.get("created_at"));
        when(repository.proposal(7, 22)).thenReturn(proposal);
        when(repository.candidates(eq(7L), eq(11L), anySet(), anySet(), eq(501))).thenReturn(List.of());
    }

    @Test
    void detailUsesTheAuthorizedProposalAndPublishedBaseline() {
        var intake = new HashMap<String, Object>();
        intake.put("status", "COMPLETED"); intake.put("evaluation_status", "INSUFFICIENT_EVIDENCE");
        intake.put("attempt", 2); intake.put("requested_at", proposal.get("created_at"));
        when(repository.publishedAnalysis(7, 22, 12)).thenReturn(Optional.of(intake));

        var detail = service.detail(caller, 7, 22, null);
        assertThat(detail.publishedAnalysis().status()).isEqualTo("COMPLETED");
        assertThat(detail.publishedAnalysis().evaluationStatus()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(detail.publishedAnalysis().guidance()).isEqualTo("REVIEW_EVIDENCE");
        verify(access).requireMember(caller, 7);
        verify(repository).publishedAnalysis(7, 22, 12);
    }

    @Test
    void memberDenialHappensBeforeAnyProposalOrIntakeRead() {
        when(access.requireMember(caller, 7)).thenThrow(AccessErrors.notFound("not_member"));
        assertThatThrownBy(() -> service.detail(caller, 7, 22, null)).isInstanceOf(ApiException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void unpublishedAndMissingIntakeAreDifferent() {
        proposal.put("published_baseline_id", null);
        proposal.put("status", "IN_REVIEW");
        assertThat(service.detail(caller, 7, 22, null).publishedAnalysis()).isNull();
        verify(repository, never()).publishedAnalysis(anyLong(), anyLong(), anyLong());
        proposal.put("status", "PUBLISHED");
        assertThat(service.detail(caller, 7, 22, null).publishedAnalysis().status()).isEqualTo("UNKNOWN");
        assertThat(service.detail(caller, 7, 22, null).publishedAnalysis().currentBaseline()).isNull();
        proposal.put("published_baseline_id", 12L);
        when(repository.publishedAnalysis(7, 22, 12)).thenReturn(Optional.empty());
        assertThat(service.detail(caller, 7, 22, null).publishedAnalysis().status()).isEqualTo("UNKNOWN");
    }
}
