package com.beez.backend.bee;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.beez.backend.ai.AiClient;
import com.beez.backend.document.service.DocumentIndexWorker;
import com.beez.backend.support.AccessFixtures;
import com.beez.backend.support.ApiClient;
import com.beez.backend.support.IntegrationTestSupport;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

/** Real PostgreSQL, mocked search and model; no real question text or response is persisted. */
@TestPropertySource(properties = {"beez.document.embedding-profile=test-profile", "beez.document.embedding-model=test-model"})
class BeeQuestionIntegrationTest extends IntegrationTestSupport {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @Autowired JdbcTemplate db;
    @MockitoBean DocumentIndexWorker search;
    @MockitoBean AiClient ai;
    AccessFixtures.ProjectWithLeader project;
    ApiClient leader;
    long baseline;

    @BeforeEach void setup() {
        project = fixtures().projectWithLeader();
        leader = client().login(project.leaderEmail(), AccessFixtures.PASSWORD);
        baseline = db.queryForObject("INSERT INTO baseline(project_id,version_no,published_by) VALUES(?,1,?) RETURNING id",
                Long.class, project.projectId(), project.leaderAccountId());
        db.update("UPDATE project SET current_baseline_id=? WHERE id=?", baseline, project.projectId());
        when(search.search(eq(project.projectId()), anyString(), isNull(), isNull(), isNull(), eq(12)))
                .thenReturn(Map.of("items", List.of()));
        when(ai.beeChat(anyMap())).thenAnswer(call -> emptyAnswer(call.getArgument(0)));
    }

    private Map<String, Object> emptyAnswer(Map<String, Object> input) {
        var result = new LinkedHashMap<String, Object>();
        result.put("answer_status", "INSUFFICIENT_EVIDENCE"); result.put("grounded", false);
        result.put("text", "찾지 못했어요. 지금 승인된 문서와 설계 항목에서는 이 질문에 답할 근거를 찾지 못했습니다. 짐작해서 답하지 않습니다.");
        result.put("citations", List.of()); result.put("sources", List.of()); result.put("design_refs", List.of()); result.put("task_refs", List.of());
        result.put("unresolved", List.of()); result.put("scope", input.get("scope")); result.put("embedding_profile_id", input.get("embedding_profile_id"));
        result.put("verification", Map.of("level", "NONE", "claims", 0));
        return Map.of("origin", "MODEL", "model", "test", "result", result,
                "usage", List.of(Map.of("feature", "BEE_CHAT", "request_key", "test-" + input.get("request_key"),
                        "model", "test", "latency_ms", 1, "tokens_known", false, "failed", false)));
    }

    private HttpResponse<String> ask(ApiClient client, String key) {
        return client.post("/api/projects/" + project.projectId() + "/bee/questions", "{\"question\":\"설계 질문\"}",
                "Origin", "http://localhost:5173", "Idempotency-Key", key);
    }

    @Test void sameKeyReturnsConflictAndOnlyMetadataIsPersisted() {
        var first = ask(leader, "same-key");
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        long answer = JSON.readTree(first.body()).get("answer_id").asLong();
        var again = ask(leader, "same-key");
        assertThat(again.statusCode()).as(again.body()).isEqualTo(409);
        assertThat(JSON.readTree(again.body()).get("error").get("code").asString()).isEqualTo("idempotency_conflict");
        assertThat(db.queryForObject("SELECT count(*) FROM bee_question_request_key WHERE project_id=?", Integer.class, project.projectId())).isEqualTo(1);
        assertThat(db.queryForMap("SELECT mode,baseline_id,document_version_ids::text AS versions FROM bee_answer WHERE id=?", answer))
                .containsEntry("mode", "APPROVED").containsEntry("baseline_id", baseline).containsEntry("versions", "[]");
        verify(ai, times(1)).beeChat(anyMap());
    }

    @Test void concurrentSameKeyStartsTheModelOnlyOnce() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(ai.beeChat(anyMap())).thenAnswer(call -> {
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("model was not released");
            return emptyAnswer(call.getArgument(0));
        });
        var first = CompletableFuture.supplyAsync(() -> ask(leader, "concurrent-key"));
        try {
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(ask(leader, "concurrent-key").statusCode()).isEqualTo(409);
        } finally { release.countDown(); }
        assertThat(first.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        verify(ai, times(1)).beeChat(anyMap());
    }
}
