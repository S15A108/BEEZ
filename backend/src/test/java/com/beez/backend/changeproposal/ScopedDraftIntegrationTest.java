package com.beez.backend.changeproposal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.beez.backend.ai.AiClient;
import com.beez.backend.support.AccessFixtures;
import com.beez.backend.support.IntegrationTestSupport;
import com.beez.backend.task.TaskApiSupport;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** HTTP, authorization, snapshot, replay, and persistence against a real database. */
class ScopedDraftIntegrationTest extends IntegrationTestSupport {
    private static final String ORIGIN = "http://localhost:5173";
    private static final String ORIGINAL = "첫 문단\n\n기존 권한\n";
    private static final String BLOCK_ID = "blk_" + "a".repeat(64);

    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean AiClient ai;
    TaskApiSupport api;
    long project, member, document, version, baseline;

    @BeforeEach void setUp() {
        api = new TaskApiSupport(this::client, dataSource);
        api.resetAndLoadFixture();
        project = api.projectId();
        member = api.accountId("member@test");
        long leader = api.accountId("leader@test");
        baseline = jdbc.queryForObject("SELECT current_baseline_id FROM project WHERE id=?", Long.class, project);
        document = jdbc.queryForObject("INSERT INTO document(project_id,type,title,created_by) VALUES(?,'REQ_SPEC','요구사항',?) RETURNING id", Long.class, project, leader);
        version = jdbc.queryForObject("""
                INSERT INTO document_version(document_id,version_no,status,storage_key,content_hash,content_text,created_by,approved_by,approved_at)
                VALUES(?,1,'APPROVED','fixture.txt',?,?,?, ?,now()) RETURNING id
                """, Long.class, document, "sha256:" + "b".repeat(64), ORIGINAL, leader, leader);
        jdbc.update("INSERT INTO baseline_document VALUES(?,?,?,?)", baseline, project, document, version);
        when(ai.revisionBlocks(any())).thenAnswer(call -> {
            Map<?, ?> source = call.getArgument(0);
            return Map.of("document_version_id", Long.toString(version), "text_hash", source.get("text_hash"),
                    "normalization_version", "utf8-lf-v1", "parser_profile", "plain-v1", "parser_version", "v1",
                    "blocks", List.of(Map.of("block_id", BLOCK_ID, "block_type", "paragraph", "text", "기존 권한\n",
                            "locator", Map.of("type", "text_lines", "start_line", 3, "end_line", 3))));
        });
        when(ai.documentRevision(any())).thenAnswer(call -> Map.of("origin", "MODEL", "model", "test-model",
                "usage", List.of(Map.of("request_key", "scoped-" + UUID.randomUUID(), "feature", "BEE_DRAFT",
                        "model", "test-model", "input_tokens", 10, "output_tokens", 5, "latency_ms", 10,
                        "failed", false, "tokens_known", true)),
                "result", Map.of("document_version_id", Long.toString(version), "after_content", "첫 문단\n\n팀장 권한\n",
                        "changes", List.of(Map.of("block_id", BLOCK_ID, "before", "기존 권한\n", "after", "팀장 권한\n",
                                "reason", "요청 반영", "evidence", List.of("팀장 권한"))), "unresolved", List.of())));
    }

    private String path() { return "/api/projects/" + project + "/change-drafts/scoped"; }
    private String base() { return "\"document_id\":\"" + document + "\",\"base_document_version_id\":\"" + version + "\""; }
    private String body(String instruction) {
        return "{" + base() + ",\"selected_block_ids\":[\"" + BLOCK_ID + "\"],\"instruction\":\"" + instruction
                + "\",\"agreements\":[\"팀장 권한\"]}";
    }
    private int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class); }

    @Test void memberCanReadAndGenerateWithoutPublishingAndIdempotencyReplays() {
        int versions = count("document_version"), proposals = count("change_proposal"), baselines = count("baseline");
        var blocks = api.postWithHeaders(path() + "/blocks", "{" + base() + "}", member, "Origin", ORIGIN);
        assertThat(blocks.statusCode()).as(blocks.body()).isEqualTo(200);
        assertThat(blocks.body()).contains(BLOCK_ID, "기존 권한");
        var first = api.postWithHeaders(path(), body("권한 변경"), member, "Origin", ORIGIN, "Idempotency-Key", "scoped-http");
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        assertThat(first.body()).contains("팀장 권한", "\"origin\":\"MODEL\"");
        var second = api.postWithHeaders(path(), body("권한 변경"), member, "Origin", ORIGIN, "Idempotency-Key", "scoped-http");
        assertThat(second.statusCode()).as(second.body()).isEqualTo(200);
        assertThat(com.beez.backend.designitem.support.DesignJson.JSON.readTree(second.body()))
                .isEqualTo(com.beez.backend.designitem.support.DesignJson.JSON.readTree(first.body()));
        var changed = api.postWithHeaders(path(), body("다른 요청"), member, "Origin", ORIGIN, "Idempotency-Key", "scoped-http");
        assertThat(changed.statusCode()).as(changed.body()).isEqualTo(409);
        verify(ai, times(1)).documentRevision(any());
        assertThat(count("document_version")).isEqualTo(versions);
        assertThat(count("change_proposal")).isEqualTo(proposals);
        assertThat(count("baseline")).isEqualTo(baselines);
        assertThat(jdbc.queryForObject("SELECT content_text FROM document_version WHERE id=?", String.class, version)).isEqualTo(ORIGINAL);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM llm_usage WHERE project_id=? AND feature='BEE_DRAFT'", Integer.class, project)).isEqualTo(1);
    }

    @Test void unauthorizedOrStaleRequestsNeverCallAi() {
        assertThat(client().post(path() + "/blocks", "{" + base() + "}", "Origin", ORIGIN).statusCode()).isEqualTo(401);
        long outsider = fixtures().account();
        var other = client().login(fixtures().emailOf(outsider), AccessFixtures.PASSWORD);
        assertThat(other.post(path() + "/blocks", "{" + base() + "}", "Origin", ORIGIN).statusCode()).isEqualTo(404);
        var stale = api.postWithHeaders(path() + "/blocks", "{\"document_id\":\"" + document + "\",\"base_document_version_id\":\"999999\"}", member, "Origin", ORIGIN);
        assertThat(stale.statusCode()).as(stale.body()).isEqualTo(409);
        verifyNoInteractions(ai);
    }

    @Test void discardsDraftIfBaselineChangesDuringModelCall() {
        long leader = api.accountId("leader@test");
        long newerVersion = jdbc.queryForObject("""
                INSERT INTO document_version(document_id,version_no,status,storage_key,content_hash,content_text,created_by,approved_by,approved_at)
                VALUES(?,2,'APPROVED','fixture.txt',?,?,?, ?,now()) RETURNING id
                """, Long.class, document, "sha256:" + "c".repeat(64), "새 기준\n", leader, leader);
        long newerBaseline = jdbc.queryForObject("INSERT INTO baseline(project_id,version_no,published_by) VALUES(?,2,?) RETURNING id",
                Long.class, project, leader);
        jdbc.update("INSERT INTO baseline_document VALUES(?,?,?,?)", newerBaseline, project, document, newerVersion);
        when(ai.documentRevision(any())).thenAnswer(call -> {
            jdbc.update("UPDATE project SET current_baseline_id=? WHERE id=?", newerBaseline, project);
            return Map.of("origin", "MODEL", "model", "test-model", "usage", List.of(),
                    "result", Map.of("document_version_id", Long.toString(version), "after_content", "첫 문단\n\n팀장 권한\n",
                            "changes", List.of(Map.of("block_id", BLOCK_ID, "before", "기존 권한\n", "after", "팀장 권한\n",
                                    "reason", "요청 반영", "evidence", List.of())), "unresolved", List.of()));
        });
        var result = api.postWithHeaders(path(), body("권한 변경"), member, "Origin", ORIGIN, "Idempotency-Key", "baseline-race");
        assertThat(result.statusCode()).as(result.body()).isEqualTo(409);
        assertThat(result.body()).contains("version_conflict");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM design_http_command WHERE project_id=?", Integer.class, project)).isZero();
        assertThat(jdbc.queryForObject("SELECT content_text FROM document_version WHERE id=?", String.class, version)).isEqualTo(ORIGINAL);
        verify(ai, times(1)).documentRevision(any());
    }
}
