package com.beez.backend.document;

import static org.assertj.core.api.Assertions.*;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Isolated upgrade database: no existing developer database or shared test schema is reset. */
@Tag("integration")
@Testcontainers
class DocumentReviewMigrationIntegrationTest {
    @Container
    static final PostgreSQLContainer database = new PostgreSQLContainer(DockerImageName.parse(
            "pgvector/pgvector@sha256:ac08538c6f8b9904c33c8224c5e5706dbe760aca29db1d096972b4052c22a75d")
            .asCompatibleSubstituteFor("postgres"));

    @Test void upgradesPopulatedV15ToV17WithoutInventingStagesOrChangingSubmissionHistory() {
        Flyway.configure().dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
                .target("15").load().migrate();
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword()));
        long account = jdbc.queryForObject("INSERT INTO account(email,password_hash,display_name) VALUES('migration@example.com','fixture','migration') RETURNING id", Long.class);
        long project = jdbc.queryForObject("INSERT INTO project(name,owner_account_id) VALUES('migration',?) RETURNING id", Long.class, account);
        long document = jdbc.queryForObject("INSERT INTO document(project_id,type,title,created_by) VALUES(?,'PLAN','migration',?) RETURNING id", Long.class, project, account);
        long version = jdbc.queryForObject("""
                INSERT INTO document_version(document_id,version_no,status,storage_key,content_hash,content_text,created_by)
                VALUES(?,1,'DRAFT','original','file-hash','original',?) RETURNING id
                """, Long.class, document, account);
        jdbc.update("""
                INSERT INTO document_working_copy(document_id,owner_account_id,initial_version_id,content_text,file_name)
                VALUES(?,?,?,'original','plan.md')
                """, document, account, version);
        long review = jdbc.queryForObject("""
                INSERT INTO document_bee_review(document_id,input_revision,resolved_revision,reference_hash,input,result,usage)
                VALUES(?,1,1,'old-hash','{"references":[]}','{"summary":"legacy","suggestions":[],"unavailable_references":[]}', '[]') RETURNING id
                """, Long.class, document);
        long suggestion = jdbc.queryForObject("""
                INSERT INTO document_bee_suggestion(review_id,explanation,original_text,replacement_text,decision,decided_at)
                VALUES(?,'legacy','original','updated','APPLIED',now()) RETURNING id
                """, Long.class, review);
        long submission = jdbc.queryForObject("""
                INSERT INTO document_review_submission(document_id,document_version_id,submitted_by,revision,bee_review_id,content_hash)
                VALUES(?,?,?,1,?,'file-hash') RETURNING id
                """, Long.class, document, version, account, review);
        String oldResult = jdbc.queryForObject("SELECT result::text FROM document_bee_review WHERE id=?", String.class, review);

        var upgraded = Flyway.configure().dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword()).load();
        upgraded.migrate();
        assertThat(upgraded.info().current().getVersion().toString()).isEqualTo("17");

        assertThat(jdbc.queryForObject("SELECT type FROM document_bee_suggestion WHERE id=?", String.class, suggestion)).isEqualTo("PATCH");
        assertThat(jdbc.queryForObject("SELECT step FROM document_bee_suggestion WHERE id=?", String.class, suggestion)).isNull();
        assertThat(jdbc.queryForObject("SELECT decision FROM document_bee_suggestion WHERE id=?", String.class, suggestion)).isEqualTo("APPLIED");
        assertThat(jdbc.queryForObject("SELECT result::text FROM document_bee_review WHERE id=?", String.class, review)).isEqualTo(oldResult);
        assertThat(jdbc.queryForObject("SELECT bee_review_id FROM document_review_submission WHERE id=?", Long.class, submission)).isEqualTo(review);
        assertThatThrownBy(() -> jdbc.update("UPDATE document_bee_review SET reference_hash='changed' WHERE id=?", review)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM document_bee_suggestion WHERE id=?", suggestion)).isInstanceOf(RuntimeException.class);
    }
}
