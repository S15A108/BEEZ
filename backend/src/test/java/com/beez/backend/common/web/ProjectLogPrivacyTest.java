package com.beez.backend.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.beez.backend.common.access.AccessErrors;
import com.beez.backend.common.access.ProjectLogRedaction;
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
            FilterChain chain = (req, res) -> {
                AccessErrors.notFound("not_member project=987654 account=31");
                AccessErrors.tokenRevoked("token_project=987654 project=987654 token=2");
            };
            new RequestIdFilter().doFilter(request, response, chain);

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

    @Test
    void redactionIsLimitedToAnalysisDetailAndDoesNotLeakAcrossRequestsOrExceptions() throws Exception {
        var logger = (Logger) LoggerFactory.getLogger("com.beez.access");
        var captured = new ListAppender<ILoggingEvent>();
        captured.start();
        logger.addAppender(captured);
        try {
            var filter = new RequestIdFilter();
            var detail = new MockHttpServletRequest("GET", "/api/projects/987654/change-proposals/73");
            assertThatThrownBy(() -> filter.doFilter(detail, new MockHttpServletResponse(), (req, res) -> {
                AccessErrors.notFound("not_member project=987654");
                throw new IllegalStateException("expected");
            })).isInstanceOf(IllegalStateException.class);

            var other = new MockHttpServletRequest("GET", "/api/projects/987654/tasks");
            filter.doFilter(other, new MockHttpServletResponse(),
                    (req, res) -> AccessErrors.notFound("not_member project=987654"));
            var update = new MockHttpServletRequest("PUT", "/api/projects/987654/change-proposals/73");
            filter.doFilter(update, new MockHttpServletResponse(), (req, res) -> { });
            var mcp = new MockHttpServletRequest("POST", "/mcp");
            filter.doFilter(mcp, new MockHttpServletResponse(), (req, res) -> { });

            assertThat(captured.list).extracting(ILoggingEvent::getFormattedMessage)
                    .anySatisfy(message -> assertThat(message).contains("not_member project=[redacted]"))
                    .anySatisfy(message -> assertThat(message).contains("GET /api/projects/{projectId}/change-proposals/73"))
                    .anySatisfy(message -> assertThat(message).contains("not_member project=987654"))
                    .anySatisfy(message -> assertThat(message).contains("GET /api/projects/987654/tasks"))
                    .anySatisfy(message -> assertThat(message).contains("PUT /api/projects/987654/change-proposals/73"))
                    .anySatisfy(message -> assertThat(message).contains("POST /mcp -> 200"));
        } finally {
            logger.detachAppender(captured);
            captured.stop();
        }
    }

    @Test
    void nestedRedactionRestoresTheOuterScope() {
        var logger = (Logger) LoggerFactory.getLogger("com.beez.access");
        var captured = new ListAppender<ILoggingEvent>();
        captured.start();
        logger.addAppender(captured);
        try {
            try (var outer = ProjectLogRedaction.open()) {
                try (var inner = ProjectLogRedaction.open()) {
                    AccessErrors.notFound("project=987654");
                }
                AccessErrors.notFound("project=987654");
            }
            AccessErrors.notFound("project=987654");
            assertThat(captured.list).extracting(ILoggingEvent::getFormattedMessage)
                    .containsExactly("access denied status=404 reason=project=[redacted]",
                            "access denied status=404 reason=project=[redacted]",
                            "access denied status=404 reason=project=987654");
        } finally {
            logger.detachAppender(captured);
            captured.stop();
        }
    }
}
