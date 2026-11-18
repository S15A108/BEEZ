package com.beez.backend.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.beez.backend.common.access.ActorType;
import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.access.ProjectActor;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.context.dto.SavePacketArgs;
import com.beez.backend.context.service.ContextLookup;
import com.beez.backend.context.service.ContextPacketService;
import com.beez.backend.context.service.DevelopmentContextComposer;
import com.beez.backend.context.service.DevelopmentContextLimiter;
import com.beez.backend.mcp.dto.GetDevelopmentContextArgs;
import com.beez.backend.mcp.service.ContextMcpService;
import com.beez.backend.project.ProjectRole;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ContextMcpServiceRateLimitTest {

    static final long PROJECT = 987654L;

    /**
     * 깨지면: 잘못된 인수·기준 없음처럼 비용이 들지 않는 실패가 MCP-01 호출 한도를 써서 정상 호출이 429 로 막히거나,
     * 한도를 넘은 호출이 유료 구성(질의 임베딩·AI 선택)까지 간다 (-399 결정: 권한·인수·기준 확인 뒤, 구성 직전에 센다).
     */
    @Test
    void limitCountsOnlyCallsThatReachComposition_andBlocksBeforeComposing() {
        var access = mock(ProjectAccessChecker.class);
        var caller = CallerContext.agent(31L, 33L);
        when(access.requireMember(caller, PROJECT))
                .thenReturn(new ProjectActor(31, 32, PROJECT, ProjectRole.MEMBER, ActorType.AGENT, 33L));
        var lookup = mock(ContextLookup.class);
        var composer = mock(DevelopmentContextComposer.class);
        when(composer.compose(anyLong(), anyLong(), any(), anyString())).thenReturn(
                new SavePacketArgs("r", null, null, "7", List.of(), List.of(), List.of(), List.of(), List.of()));
        var mcp = new ContextMcpService(access, mock(ContextPacketService.class), null, null, lookup, composer,
                new DevelopmentContextLimiter(1, Duration.ofMinutes(1)));

        // 비용 없는 실패: 빈 요청 400, 다른 프로젝트 404, 기준 없음 404 — 한도를 쓰지 않는다
        assertCode(() -> mcp.getDevelopmentContext(caller, PROJECT, new GetDevelopmentContextArgs(" ", null, null)),
                "validation_failed");
        assertCode(() -> mcp.getDevelopmentContext(caller, PROJECT,
                new GetDevelopmentContextArgs("r", String.valueOf(PROJECT + 1), null)), "not_found");
        when(lookup.currentBaselineId(PROJECT)).thenReturn(Optional.empty());
        assertCode(() -> mcp.getDevelopmentContext(caller, PROJECT, new GetDevelopmentContextArgs("r", null, null)),
                "baseline_not_published");

        // 한도 1: 첫 구성은 통과, 다음 호출은 구성 전에 429
        when(lookup.currentBaselineId(PROJECT)).thenReturn(Optional.of(7L));
        mcp.getDevelopmentContext(caller, PROJECT, new GetDevelopmentContextArgs("r", null, null));
        assertCode(() -> mcp.getDevelopmentContext(caller, PROJECT, new GetDevelopmentContextArgs("r", null, null)),
                "rate_limited");
        verify(composer, times(1)).compose(anyLong(), anyLong(), any(), anyString());
    }

    private static void assertCode(Runnable call, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo(code));
    }
}
