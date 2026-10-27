package com.beez.backend.document;

import static org.assertj.core.api.Assertions.*;

import com.beez.backend.document.repository.DocumentRepository;
import com.beez.backend.document.repository.DocumentVersionRepository;
import com.beez.backend.document.service.DocumentRegistrationService;
import com.beez.backend.document.service.DocumentRegistrationService.KindPolicy;
import com.beez.backend.document.support.InMemoryDocumentStorage;
import com.beez.backend.document.validation.DocumentInputValidator;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class DocumentRegistrationIntegrationTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse(
            "pgvector/pgvector@sha256:ac08538c6f8b9904c33c8224c5e5706dbe760aca29db1d096972b4052c22a75d")
            .asCompatibleSubstituteFor("postgres"));

    @Autowired DocumentRepository documents;
    @Autowired DocumentVersionRepository versions;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;

    @Test
    void concurrentSingleKindRegistrationsCommitOnlyOneDraft() throws Exception {
        long actor = jdbc.queryForObject("INSERT INTO account(display_name) VALUES ('race-test') RETURNING id", Long.class);
        long project = jdbc.queryForObject("INSERT INTO project(name, owner_account_id) VALUES ('race-test', ?) RETURNING id", Long.class, actor);
        var storage = new InMemoryDocumentStorage();
        var service = new DocumentRegistrationService(new DocumentInputValidator(4096, 1000), storage,
                id -> actor, documents, versions, transactions, KindPolicy.SINGLE_PER_KIND);
        var start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Callable<String> register = () -> {
            start.await();
            try {
                service.register(new DocumentRegistrationService.Input(project, DocumentKind.PLAN,
                        "Plan", null, "plan.md", new byte[] {65}));
                return "saved";
            } catch (com.beez.backend.common.api.ApiException e) {
                return e.getCode();
            }
        };
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(register);
            var second = executor.submit(register);
            start.countDown();
            assertThat(java.util.List.of(first.get(10, java.util.concurrent.TimeUnit.SECONDS),
                    second.get(10, java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("saved", "document_kind_exists");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document WHERE project_id = ?", Long.class, project)).isEqualTo(1L);
        assertThat(storage.size()).isEqualTo(1);
    }

    @Test
    void persistsV2DraftAndOriginalAndCleansFailedDocumentInsert() throws Exception {
        long actor = jdbc.queryForObject("INSERT INTO account(display_name) VALUES ('doc-test') RETURNING id", Long.class);
        long project = jdbc.queryForObject("INSERT INTO project(name, owner_account_id) VALUES ('doc-test', ?) RETURNING id", Long.class, actor);
        var storage = new InMemoryDocumentStorage();
        var service = new DocumentRegistrationService(new DocumentInputValidator(4096, 1000), storage,
                id -> actor, documents, versions, transactions, KindPolicy.SINGLE_PER_KIND);
        byte[] bytes = "\uFEFF# 요구사항\r\n조회 권한 😀\r\n".getBytes(StandardCharsets.UTF_8);
        var result = service.register(new DocumentRegistrationService.Input(project, DocumentKind.REQUIREMENTS,
                "Requirements", "account", "requirements.md", bytes));
        var version = versions.findById(result.documentVersionId()).orElseThrow();
        assertThat(version.getStatus()).isEqualTo("DRAFT");
        assertThat(version.getParseStatus()).isEqualTo("PENDING");
        assertThat(version.getContentText()).isEqualTo("# 요구사항\n조회 권한 😀\n");
        assertThat(version.getApprovedAt()).isNull();
        assertThat(storage.read(version.getStorageKey())).containsExactly(bytes);
        assertThat(jdbc.queryForObject("SELECT type FROM document WHERE id = ?", String.class, result.documentId())).isEqualTo("REQ_SPEC");
        assertThat(documents.findById(result.documentId()).orElseThrow().getKind()).isEqualTo(DocumentKind.REQUIREMENTS);
        long before = documents.count();
        // PostgreSQL rejects NUL in text: failure after original storage must rollback document too.
        assertThatThrownBy(() -> service.register(new DocumentRegistrationService.Input(project, DocumentKind.PLAN,
                "bad\0title", null, "plan.txt", new byte[] {65}))).isInstanceOf(RuntimeException.class);
        assertThat(documents.count()).isEqualTo(before);
        assertThat(storage.size()).isEqualTo(1);
    }
}
