package com.beez.backend.changeproposal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.beez.backend.changeproposal.repository.ProposalRepository;
import com.beez.backend.common.api.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;

class PublishedAnalysisRepositoryFailureTest {
    @Test
    void databaseFailureIsUnavailableWithoutLeakingDetailsOrBecomingUnknown() {
        var jdbc = mock(JdbcClient.class);
        when(jdbc.sql(anyString())).thenThrow(new DataAccessResourceFailureException("secret project=987654"));
        var repository = new ProposalRepository(jdbc);

        assertThatThrownBy(() -> repository.publishedAnalysis(7, 22, 12))
                .isInstanceOfSatisfying(ApiException.class, failure -> {
                    assertThat(failure.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(failure.getCode()).isEqualTo("published_analysis_unavailable");
                    assertThat(failure.getMessage()).doesNotContain("secret", "987654");
                    assertThat(failure.getCause()).isNull();
                });
    }
}
