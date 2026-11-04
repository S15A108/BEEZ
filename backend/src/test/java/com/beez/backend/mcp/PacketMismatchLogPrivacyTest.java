package com.beez.backend.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.beez.backend.common.access.ActorType;
import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.access.ProjectActor;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.api.InputValidator;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.context.dto.SavePacketArgs;
import com.beez.backend.context.service.ContextPacketService;
import com.beez.backend.mcp.dto.GetDevelopmentContextArgs;
import com.beez.backend.mcp.service.ContextMcpService;
import com.beez.backend.project.ProjectRole;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class PacketMismatchLogPrivacyTest {
    @Test
    void mismatchedProjectIdsAreNotLoggedByEitherPacketEntryPoint() {
        var logger = (Logger) LoggerFactory.getLogger("com.beez.access");
        var captured = new ListAppender<ILoggingEvent>();
        captured.start();
        logger.addAppender(captured);
        try {
            var access = mock(ProjectAccessChecker.class);
            var caller = CallerContext.agent(31L, 33L);
            when(access.requireMember(caller, 987654L))
                    .thenReturn(new ProjectActor(31, 32, 987654, ProjectRole.MEMBER, ActorType.AGENT, 33L));
            var packets = new ContextPacketService(access, mock(InputValidator.class), null, null, null, null, null);
            var mcp = new ContextMcpService(access, packets, null, null, null);
            var packet = new SavePacketArgs("request", "987655", null, "1", List.of(), List.of(), List.of(), List.of(), List.of());

            assertThatThrownBy(() -> mcp.getDevelopmentContext(caller, 987654L,
                    new GetDevelopmentContextArgs("request", "987655", null))).isInstanceOf(ApiException.class);
            assertThatThrownBy(() -> packets.save(caller, 987654L, packet)).isInstanceOf(ApiException.class);
            assertThat(captured.list).extracting(ILoggingEvent::getFormattedMessage)
                    .containsExactly("access denied status=404 reason=packet_project_mismatch",
                            "access denied status=404 reason=packet_project_mismatch");
        } finally {
            logger.detachAppender(captured);
            captured.stop();
        }
    }
}
