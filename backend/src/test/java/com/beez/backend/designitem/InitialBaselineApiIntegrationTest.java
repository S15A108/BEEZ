package com.beez.backend.designitem;

import static org.assertj.core.api.Assertions.*;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.designitem.dto.CreateDesignItemRequest;
import com.beez.backend.designitem.dto.DesignItemResponse;
import com.beez.backend.designitem.dto.PublishInitialBaselineRequest;
import com.beez.backend.designitem.dto.UpdateDesignItemRequest;
import com.beez.backend.designitem.service.BaselinePublishService;
import com.beez.backend.designitem.service.DesignItemCommandService;
import com.beez.backend.designitem.service.DesignItemQueryService;
import com.beez.backend.support.AccessFixtures;
import com.beez.backend.support.ApiClient;
import com.beez.backend.support.IntegrationTestSupport;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.JsonNode;

@TestPropertySource(properties = {"beez.cors.allowed-origins=https://app.beez.test", "beez.document.embedding-profile=design-test"})
class InitialBaselineApiIntegrationTest extends IntegrationTestSupport {
    private static final JsonMapper JSON = JsonMapper.builder().propertyNamingStrategy(tools.jackson.databind.PropertyNamingStrategies.SNAKE_CASE).build();
    private static final String ORIGIN = "https://app.beez.test";
    @Autowired JdbcTemplate jdbc;
    @Autowired DesignItemCommandService commands;
    @Autowired DesignItemQueryService queries;
    @Autowired BaselinePublishService baselines;
    @Autowired PlatformTransactionManager transactions;
    AccessFixtures.ProjectWithLeader p;
    CallerContext actor;
    ApiClient leader;

    @BeforeEach void setup() {
        p = fixtures().projectWithLeader();
        actor = CallerContext.human(p.leaderAccountId());
        leader = client().login(p.leaderEmail(), AccessFixtures.PASSWORD);
    }
    String path() { return "/api/projects/" + p.projectId() + "/design-items"; }
    JsonNode json(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(status);
        return JSON.readTree(response.body());
    }
    DesignItemResponse create(String description) {
        return commands.create(actor, p.projectId(), new CreateDesignItemRequest("REQUIREMENT", 1,
                Map.of("description", description), null, null), UUID.randomUUID().toString());
    }
    PublishInitialBaselineRequest input(List<Long> docs, List<Long> ids) {
        return new PublishInitialBaselineRequest(Long.toString(p.projectId()), docs.stream().map(String::valueOf).toList(), ids.stream().map(String::valueOf).toList(),
                queries.list(actor, p.projectId(), "DRAFT", null, null, null, 100).draftSnapshotHash());
    }
    HttpResponse<String> publish(PublishInitialBaselineRequest input, String key) {
        return leader.post("/api/human/baselines", JSON.writeValueAsString(input), "Origin", ORIGIN, "Idempotency-Key", key);
    }

    @Test void publishBlocksUntilAllFourIndexesAreReadyThenReplaysExactBaselineAndFreezesEdits() throws Exception {
        var docs = documents(false);
        var item = create("조회 허용");
        var input = input(docs, List.of(Long.parseLong(item.id())));
        String key = "publish";
        assertThat(json(publish(input, key), 409).path("error").path("code").asText()).isEqualTo("analysis_not_completed");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM baseline WHERE project_id=?", Integer.class, p.projectId())).isZero();
        for (long doc : docs) ready(doc);
        var result = json(publish(input, key), 201);
        assertThat(result.path("documents").size()).isEqualTo(4);
        assertThat(result.path("items").size()).isEqualTo(1);
        assertThat(json(publish(input, key), 201)).isEqualTo(result);
        assertThat(json(publish(input, "new-key"), 409).path("error").path("code").asText()).isEqualTo("baseline_exists");
        assertThat(json(leader.get("/api/projects/" + p.projectId() + "/baselines/current"), 200)).isEqualTo(result);
        assertThat(json(leader.get(path()), 200).path("items").get(0).path("status").asText()).isEqualTo("APPROVED");
        assertThatThrownBy(() -> commands.update(actor, Long.parseLong(item.designItemId()),
                new UpdateDesignItemRequest(Long.toString(p.projectId()), item.id(), Map.of("description", "변경"), item.contentHash())))
                .isInstanceOf(ApiException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM baseline_document WHERE baseline_id=?", Integer.class, result.path("id").asLong())).isEqualTo(4);
    }

    @Test void baselineButtonCannotApproveUnapprovedDocumentsAndDoesNotChangeDocumentApprovalHistory() throws Exception {
        var docs = documents(true);
        var item = create("별도 생성");
        long version = docs.getFirst();
        jdbc.update("UPDATE document_version SET status='DRAFT',approved_at=null WHERE id=?", version);
        var request = input(docs, List.of(Long.parseLong(item.id())));
        assertThat(json(publish(request, "not-approved"), 409).path("error").path("code").asText()).isEqualTo("document_not_approved");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM baseline WHERE project_id=?", Integer.class, p.projectId())).isZero();
        jdbc.update("UPDATE document_version SET status='APPROVED',approved_by=?,approved_at=now() WHERE id=?", p.leaderAccountId(), version);
        String approvedAt = jdbc.queryForObject("SELECT approved_at::text FROM document_version WHERE id=?", String.class, version);
        json(publish(input(docs, List.of(Long.parseLong(item.id()))), "separate-button"), 201);
        assertThat(jdbc.queryForObject("SELECT approved_at::text FROM document_version WHERE id=?", String.class, version)).isEqualTo(approvedAt);
    }

    @Test void completeItemSetHashAndSourceVersionAreValidatedBeforeApproval() throws Exception {
        var docs = documents(true);
        var first = create("첫 항목");
        var second = create("둘째 항목");
        var partial = input(docs, List.of(Long.parseLong(first.id())));
        assertThat(json(publish(partial, "partial"), 409).path("error").path("code").asText()).isEqualTo("draft_changed");
        var full = input(docs, List.of(Long.parseLong(first.id()), Long.parseLong(second.id())));
        commands.update(actor, Long.parseLong(first.designItemId()), new UpdateDesignItemRequest(Long.toString(p.projectId()), first.id(), Map.of("description", "수정"), first.contentHash()));
        assertThat(json(publish(full, "stale"), 409).path("error").path("code").asText()).isEqualTo("draft_changed");
        commands.exclude(actor, p.projectId(), Long.parseLong(second.designItemId()), Long.parseLong(second.id()), second.contentHash());
        var current = input(docs, List.of(Long.parseLong(first.id())));
        var baseline = json(publish(current, "current"), 201);
        assertThat(baseline.path("items").size()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM design_item_version WHERE id=?", String.class, Long.parseLong(second.id()))).isEqualTo("REJECTED");
    }

    @Test void midPublicationDatabaseFailureRollsBackStatesRefsPointerAndCommand() throws Exception {
        var docs = documents(true);
        var item = create("트랜잭션");
        var input = input(docs, List.of(Long.parseLong(item.id())));
        String constraint = "test_fail_baseline_" + p.projectId();
        jdbc.execute("ALTER TABLE project ADD CONSTRAINT " + constraint + " CHECK (id<>" + p.projectId() + " OR current_baseline_id IS NULL)");
        try {
            assertThatThrownBy(() -> baselines.publish(actor, input, "rollback")).isInstanceOf(RuntimeException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM baseline WHERE project_id=?", Integer.class, p.projectId())).isZero();
            assertThat(jdbc.queryForObject("SELECT status FROM design_item_version WHERE id=?", String.class, Long.parseLong(item.id()))).isEqualTo("DRAFT");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM document_version v JOIN document d ON d.id=v.document_id WHERE d.project_id=? AND v.status='APPROVED'", Integer.class, p.projectId())).isEqualTo(4);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM design_http_command WHERE project_id=? AND operation='initial-baseline'", Integer.class, p.projectId())).isZero();
        } finally { jdbc.execute("ALTER TABLE project DROP CONSTRAINT " + constraint); }
        assertThat(baselines.publish(actor, input, "rollback").versionNo()).isEqualTo(1);
    }

    @Test void concurrentPublishCreatesOneBaselineAndTwoEditorsCannotOverwriteEachOther() throws Exception {
        var docs = documents(true);
        var item = create("동시 편집");
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            var calls = new ArrayList<Future<String>>();
            for (String description : List.of("수정 A", "수정 B")) calls.add(pool.submit(() -> {
                gate.await();
                try {
                    commands.update(actor, Long.parseLong(item.designItemId()), new UpdateDesignItemRequest(Long.toString(p.projectId()),
                            item.id(), Map.of("description", description), item.contentHash()));
                    return "OK";
                } catch (ApiException e) { return e.getCode(); }
            }));
            gate.countDown();
            assertThat(List.of(calls.get(0).get(20, TimeUnit.SECONDS), calls.get(1).get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("OK", "version_conflict");
            var input = input(docs, List.of(Long.parseLong(item.id())));
            var start = new CountDownLatch(1);
            var a = pool.submit(() -> { start.await(); return baselines.publish(actor, input, "same-publish"); });
            var b = pool.submit(() -> { start.await(); return baselines.publish(actor, input, "same-publish"); });
            start.countDown();
            assertThat(a.get(20, TimeUnit.SECONDS)).isEqualTo(b.get(20, TimeUnit.SECONDS));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM baseline WHERE project_id=?", Integer.class, p.projectId())).isEqualTo(1);
        }
    }

    @Test void duplicateIdsMissingDocumentsOrChangedIdempotentInputCannotPublish() throws Exception {
        var item = create("초안");
        var missing = new PublishInitialBaselineRequest(Long.toString(p.projectId()), List.of("1"), List.of(item.id()), "hash");
        assertThat(json(publish(missing, "missing"), 400).path("error").path("code").asText()).isEqualTo("required_documents_missing");
        var docs = documents(true);
        var duplicates = input(docs, List.of(Long.parseLong(item.id()), Long.parseLong(item.id())));
        json(publish(duplicates, "duplicate"), 409);
        var body = new CreateDesignItemRequest("REQUIREMENT", 1, Map.of("description", "최초"), null, null);
        commands.create(actor, p.projectId(), body, "conflict");
        assertThatThrownBy(() -> commands.create(actor, p.projectId(), new CreateDesignItemRequest("REQUIREMENT", 1,
                Map.of("description", "다른 입력"), null, null), "conflict"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("idempotency_conflict"));
    }

    @Test void readinessRequiresCurrentManifestProfileAttemptAndActualVectors() throws Exception {
        var docs = documents(true);
        var item = create("색인 검증");
        var input = input(docs, List.of(Long.parseLong(item.id())));
        long doc = docs.getFirst();
        jdbc.update("UPDATE document_index_manifest SET embedding_profile_id='old' WHERE document_version_id=?", doc);
        json(publish(input, "not-ready"), 409);
        jdbc.update("UPDATE document_index_manifest SET embedding_profile_id='design-test',attempt=99 WHERE document_version_id=?", doc);
        json(publish(input, "not-ready"), 409);
        jdbc.update("UPDATE document_index_manifest SET attempt=1,chunk_count=2 WHERE document_version_id=?", doc);
        json(publish(input, "not-ready"), 409);
        jdbc.update("UPDATE document_index_manifest SET chunk_count=1 WHERE document_version_id=?", doc);
        jdbc.update("UPDATE document_chunk SET embedding=NULL WHERE document_version_id=?", doc);
        json(publish(input, "not-ready"), 409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM baseline WHERE project_id=?", Integer.class, p.projectId())).isZero();
    }

    @Test void sourceMapsToExactDocumentAndAnOldSourceCannotEnterNewDocumentSnapshot() throws Exception {
        var docs = documents(true);
        long source = docs.getFirst();
        var item = commands.create(actor, p.projectId(), new CreateDesignItemRequest("POLICY", 1,
                Map.of("description", "출처 항목"), Long.toString(source), "1절"), "source");
        assertThat(item.source().documentVersionId()).isEqualTo(Long.toString(source));
        assertThat(item.source().location()).isEqualTo("1절");
        long document = Long.parseLong(item.source().documentId());
        long next = jdbc.queryForObject("""
                INSERT INTO document_version(document_id,version_no,status,storage_key,content_hash,content_text,created_by,parent_version_id)
                SELECT document_id,2,'DRAFT','replacement.md',content_hash,content_text,created_by,id FROM document_version WHERE id=? RETURNING id
                """, Long.class, source);
        jdbc.update("INSERT INTO document_processing(document_version_id,status,text_hash) SELECT ?, 'PENDING',text_hash FROM document_processing WHERE document_version_id=?", next, source);
        ready(next);
        jdbc.update("UPDATE document_version SET status='APPROVED',approved_at=now() WHERE id=?", next);
        var currentDocs = new ArrayList<>(docs); currentDocs.set(0, next);
        var input = input(currentDocs, List.of(Long.parseLong(item.id())));
        assertThat(json(publish(input, "old-source"), 400).path("error").path("code").asText()).isEqualTo("validation_failed");
        assertThat(jdbc.queryForObject("SELECT id FROM document WHERE id=?", Long.class, document)).isEqualTo(document);
        var changed = commands.update(actor, Long.parseLong(item.designItemId()), new UpdateDesignItemRequest(
                Long.toString(p.projectId()), item.id(), item.content(), item.contentHash(), true,
                Long.toString(next), "2절", queries.list(actor, p.projectId(), "DRAFT", null, null, null, 100).draftSnapshotHash()));
        assertThat(changed.key()).isEqualTo(item.key());
        assertThat(changed.source().documentVersionId()).isEqualTo(Long.toString(next));
        codeStaleSourceEdit(item, next);
        json(publish(input(currentDocs, List.of(Long.parseLong(item.id()))), "fixed-source"), 201);
    }

    private void codeStaleSourceEdit(DesignItemResponse item, long next) {
        assertThatThrownBy(() -> commands.update(actor, Long.parseLong(item.designItemId()), new UpdateDesignItemRequest(
                Long.toString(p.projectId()), item.id(), item.content(), item.contentHash(), true,
                Long.toString(next), "이전 화면", "stale-snapshot")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("draft_changed"));
    }

    private List<Long> documents(boolean completed) throws Exception {
        var result = new ArrayList<Long>();
        for (String type : List.of("PLAN", "REQ_SPEC", "ERD", "API_SPEC")) {
            long document = jdbc.queryForObject("INSERT INTO document(project_id,type,title,created_by) VALUES(?,?,?,?) RETURNING id",
                    Long.class, p.projectId(), type, type, p.leaderAccountId());
            String text = "# " + type + "\n내용";
            String hash = "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
            long version = jdbc.queryForObject("""
                    INSERT INTO document_version(document_id,version_no,status,storage_key,content_hash,content_text,created_by,approved_at)
                    VALUES(?,1,'APPROVED',?,?,?,?,now()) RETURNING id
                    """, Long.class, document, UUID.randomUUID() + ".md", hash, text, p.leaderAccountId());
            jdbc.update("INSERT INTO document_processing(document_version_id,status,text_hash) VALUES(?,'PENDING',?)", version, hash);
            result.add(version);
            if (completed) ready(version);
        }
        return result;
    }
    private void ready(long version) {
        jdbc.update("UPDATE document_processing SET status='COMPLETED',source_status='READY',index_status='READY' WHERE document_version_id=?", version);
        jdbc.update("""
                INSERT INTO document_index_manifest(document_version_id,attempt,text_hash,embedding_profile_id,chunking_profile_id,parser_version,chunk_count)
                SELECT document_version_id,attempt,text_hash,'design-test','test-chunk','test-parser',1 FROM document_processing WHERE document_version_id=?
                """, version);
        String vector = "[1," + "0,".repeat(1022) + "0]";
        jdbc.update("INSERT INTO document_chunk(document_version_id,chunk_no,text,embedding) VALUES(?,0,'내용',cast(? AS vector))", version, vector);
    }
}
