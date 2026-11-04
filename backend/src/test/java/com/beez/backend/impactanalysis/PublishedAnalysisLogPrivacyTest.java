package com.beez.backend.impactanalysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.beez.backend.common.access.AccessErrors;
import com.beez.backend.config.BeezProperties;
import com.beez.backend.impactanalysis.intake.PublishedAnalysisRepository;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

class PublishedAnalysisLogPrivacyTest {
    @Test
    void workerRedactsProjectIdAndRestoresLoggingScopeOnFailure() {
        var logger = (Logger) LoggerFactory.getLogger("com.beez.access");
        var captured = new ListAppender<ILoggingEvent>();
        captured.start();
        logger.addAppender(captured);
        try {
            var repository = mock(PublishedAnalysisRepository.class);
            when(repository.claimNow(anyLong(), anyLong())).thenAnswer(invocation -> {
                AccessErrors.notFound("not_member project=987654 account=31");
                throw new IllegalStateException("claim failed");
            });
            var properties = new BeezProperties(null,
                    new BeezProperties.Ai("http://localhost", "test", Duration.ofSeconds(1), Duration.ofSeconds(1)), null);
            var processor = new PublishedAnalysisProcessor(repository, mock(ImpactAnalysisCoordinator.class),
                    mock(ImpactContextQuery.class), mock(ExplicitImpactCalculator.class), mock(JdbcClient.class),
                    mock(PlatformTransactionManager.class), properties);
            assertThatThrownBy(() -> processor.process(42)).isInstanceOf(IllegalStateException.class);
            AccessErrors.notFound("not_member project=987654 account=31");

            assertThat(captured.list).extracting(ILoggingEvent::getFormattedMessage)
                    .containsExactly("access denied status=404 reason=not_member project=[redacted] account=31",
                            "access denied status=404 reason=not_member project=987654 account=31");
        } finally {
            logger.detachAppender(captured);
            captured.stop();
        }
    }
}
