package com.beez.backend.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.beez.backend.common.access.AccessErrors;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ProjectLogPrivacyTest {
    @Test
    void projectIdIsNotLoggedOnDetailRequestsOrMembershipDenial() throws Exception {
        var logger = (Logger) LoggerFactory.getLogger("com.beez.access");
        var captured = new ListAppender<ILoggingEvent>();
        captured.start();
        logger.addAppender(captured);
        try {
            var request = new MockHttpServletRequest("GET", "/api/projects/987654/change-proposals/73");
            var response = new MockHttpServletResponse();
            FilterChain chain = (req, res) -> AccessErrors.notFound("not_member project=987654 account=31");
            new RequestIdFilter().doFilter(request, response, chain);
            AccessErrors.tokenRevoked("token_project=987654 project=987654 token=2");

            assertThat(captured.list).extracting(ILoggingEvent::getFormattedMessage)
                    .anySatisfy(message -> assertThat(message).contains("/api/projects/{projectId}/change-proposals/73"))
                    .anySatisfy(message -> assertThat(message).contains("project=[redacted]"))
                    .anySatisfy(message -> assertThat(message).contains("token_project=[redacted]"))
                    .allSatisfy(message -> assertThat(message).doesNotContain("987654"));
        } finally {
            logger.detachAppender(captured);
            captured.stop();
        }
    }
}
