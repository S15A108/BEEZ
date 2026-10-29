package com.beez.backend.changeproposal;

import static org.assertj.core.api.Assertions.*;
import com.beez.backend.changeproposal.dto.ProposalDtos.*;
import com.beez.backend.changeproposal.service.ProposalCommandService;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.document.storage.DocumentStorage;
import com.beez.backend.document.support.SeaweedTestContainer;
import com.beez.backend.support.IntegrationTestSupport;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;

/** The text-change path against a real S3-compatible store (SeaweedFS), not the in-memory stand-in. */
class ProposalSeaweedIntegrationTest extends IntegrationTestSupport {
    // Shared across the test JVM and returned once the bucket is ready. The bucket is shared, so object counts
    // are compared against this test's own starting count only.
    static final SeaweedTestContainer seaweed = SeaweedTestContainer.shared();
    @DynamicPropertySource static void storageEndpoint(DynamicPropertyRegistry registry) {
        registry.add("beez.document.object-storage.endpoint", seaweed::endpoint);
    }

    @Autowired ProposalCommandService commands;
    @Autowired JdbcTemplate jdbc;
    @Autowired S3Client s3;
    @Autowired DocumentStorage storage;
    long projectId, baseline, document, version;
    CallerContext author;

    @BeforeEach void setup() {
        var project = fixtures().projectWithLeader();
        projectId = project.projectId();
        author = CallerContext.human(project.leaderAccountId());
        baseline = jdbc.queryForObject("INSERT INTO baseline(project_id,version_no,published_by) VALUES(?,1,?) RETURNING id",
                Long.class, projectId, author.accountId());
        jdbc.update("UPDATE project SET current_baseline_id=? WHERE id=?", baseline, projectId);
        document = jdbc.queryForObject("INSERT INTO document(project_id,type,title,created_by) VALUES(?,'REQ_SPEC','요구사항',?) RETURNING id",
                Long.class, projectId, author.accountId());
        version = jdbc.queryForObject("""
                INSERT INTO document_version(document_id,version_no,status,storage_key,content_hash,content_text,created_by,approved_by,approved_at)
                VALUES(?,1,'APPROVED','fixture.md','hash-before','첫째\n이전\n끝\n',?,?,now()) RETURNING id
                """, Long.class, document, author.accountId(), author.accountId());
        jdbc.update("INSERT INTO baseline_document VALUES(?,?,?,?)", baseline, projectId, document, version);
    }

    Input input(String after) {
        return new Input(""+baseline,"문서 보완","반영 이유",null,null,List.of(),
                List.of(new DocumentChange(""+document,""+version,after,null)),null,null,null);
    }
    int objectCount() { return s3.listObjectsV2(ListObjectsV2Request.builder().bucket(SeaweedTestContainer.BUCKET).build()).contents().size(); }

    @Test void textChangeIsStoredOnceAndReadableAndReplayWritesNothingMore() throws Exception {
        int before = objectCount();
        Proposal p = commands.create(author, projectId, input("첫째\r\n수정\r\n끝\r\n"), "seaweed-ok");
        long created = Long.parseLong(p.documents().getFirst().proposedDocumentVersionId());
        String key = jdbc.queryForObject("SELECT storage_key FROM document_version WHERE id=?", String.class, created);
        assertThat(objectCount()).isEqualTo(before + 1);
        assertThat(storage.read(key)).isEqualTo("첫째\n수정\n끝\n".getBytes(StandardCharsets.UTF_8));
        assertThat(commands.create(author, projectId, input("첫째\r\n수정\r\n끝\r\n"), "seaweed-ok")).isEqualTo(p);
        assertThat(objectCount()).isEqualTo(before + 1);
    }

    @Test void failedTransactionDeletesTheOriginalFromRealStorage() {
        int before = objectCount();
        jdbc.execute("CREATE OR REPLACE FUNCTION fail_revision_seaweed_test() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'failure injection'; END $$");
        jdbc.execute("CREATE TRIGGER trg_fail_revision_seaweed_test BEFORE INSERT ON change_proposal_revision FOR EACH ROW WHEN (NEW.project_id="
                + projectId + ") EXECUTE FUNCTION fail_revision_seaweed_test()");
        try {
            assertThatThrownBy(() -> commands.create(author, projectId, input("첫째\n실패할 수정\n끝\n"), "seaweed-fail"))
                    .isInstanceOf(RuntimeException.class).hasMessageContaining("failure injection");
        } finally {
            jdbc.execute("DROP TRIGGER trg_fail_revision_seaweed_test ON change_proposal_revision");
        }
        assertThat(objectCount()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_version WHERE document_id=? AND id<>?", Integer.class, document, version)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM change_proposal WHERE project_id=?", Integer.class, projectId)).isZero();
    }
}
