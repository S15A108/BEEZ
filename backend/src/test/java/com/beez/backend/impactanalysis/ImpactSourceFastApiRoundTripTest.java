package com.beez.backend.impactanalysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.beez.backend.ai.AiClient;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.config.BeezProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/** Opt-in actual FastAPI HTTP round trip; query and LLM are deterministic test doubles. */
@EnabledIfEnvironmentVariable(named = "BEEZ_RUN_IMPACT_HTTP_TEST", matches = "true")
class ImpactSourceFastApiRoundTripTest {
    @ParameterizedTest
    @ValueSource(strings = {" lines 5-7 ", "\u00a0lines 5-7\u00a0", "\u0085lines 5-7\u0085",
            "\u001clines  5-7\u001c"})
    void sourceSurvivesActualFastApiRoundTrip(String location) {
        String baseUrl = System.getenv("BEEZ_IMPACT_HTTP_URL");
        String token = System.getenv("BEEZ_IMPACT_HTTP_TOKEN");
        assertThat(baseUrl).startsWith("http://127.0.0.1:");
        assertThat(token).isNotBlank();
        var ai = new AiClient(new BeezProperties(null, new BeezProperties.Ai(
                baseUrl, token, Duration.ofSeconds(2), Duration.ofSeconds(10)), null));
        var now = Instant.parse("2026-01-01T00:00:00Z");
        var proposal = new ImpactContextQuery.Proposal(71, 1, 9, 1, "PROPOSED", "fixture", now, 2, "sha256:abc");
        var change = new ImpactContextQuery.Change(0, "RETIRE", 10L, 100L, null, null, null, now);
        var locator = JsonMapper.builder().build().writeValueAsString(Map.of("location", location));
        var approved = new ImpactContextQuery.ApprovedItem(10, 100, "API-1", "API_CONTRACT", 1, 1,
                "fixture", "{\"path\":\"/v1\"}", 7L, 8L, 1, locator);
        var snapshot = new ImpactContextQuery.Snapshot(proposal, List.of(change), List.of(approved),
                List.of(), List.of(), ImpactContextQuery.RuleCandidateStatus.CONNECTED);
        var query = mock(ImpactContextQuery.class);
        var caller = CallerContext.human(4L);
        when(query.load(caller, 1, 71)).thenReturn(snapshot);

        var outcome = new ImpactAnalysisCoordinator(query, ai).analyze(caller, 1, 71, "source-roundtrip");

        assertThat(outcome.status()).isEqualTo(ImpactAnalysisCoordinator.Status.INSUFFICIENT_EVIDENCE);
        assertThat(outcome.errorCode()).isNull();
        var result = (Map<?, ?>) outcome.aiEnvelope().get("result");
        var candidate = (Map<?, ?>) ((List<?>) result.get("candidates")).getFirst();
        var source = (Map<?, ?>) ((List<?>) candidate.get("sources")).getFirst();
        assertThat(source.get("location")).isEqualTo(location.startsWith("\u001c") ? location : "lines 5-7");
    }
}
