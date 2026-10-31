package com.beez.backend.designitem;

import static org.assertj.core.api.Assertions.assertThat;

import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.designitem.dto.CreateDesignItemRequest;
import com.beez.backend.designitem.dto.PublishInitialBaselineRequest;
import com.beez.backend.designitem.service.BaselinePublishService;
import com.beez.backend.designitem.service.DesignItemCommandService;
import com.beez.backend.designitem.service.DesignItemQueryService;
import com.beez.backend.support.IntegrationTestSupport;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "beez.document.embedding-profile=design-test")
class InitialBaselineNoLegacyIntegrationTest extends IntegrationTestSupport {
    @Autowired JdbcTemplate jdbc;
    @Autowired DesignItemCommandService commands;
    @Autowired DesignItemQueryService queries;
    @Autowired BaselinePublishService baselines;

    @Test void defaultPublicationDoesNotCreateLegacyOutbox() throws Exception {
        var project = fixtures().projectWithLeader();
        var actor = CallerContext.human(project.leaderAccountId());
        var versions = new ArrayList<String>();
        for (String type : List.of("PLAN", "REQ_SPEC", "ERD", "API_SPEC")) {
            long document = jdbc.queryForObject("INSERT INTO document(project_id,type,title,created_by) VALUES(?,?,?,?) RETURNING id",
                    Long.class, project.projectId(), type, type, project.leaderAccountId());
            String text = "# " + type;
            String hash = "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
            long version = jdbc.queryForObject("""
                    INSERT INTO document_version(document_id,version_no,status,storage_key,content_hash,content_text,created_by,approved_at)
                    VALUES(?,1,'APPROVED',?,?,?,?,now()) RETURNING id
                    """, Long.class, document, UUID.randomUUID() + ".md", hash, text, project.leaderAccountId());
            jdbc.update("INSERT INTO document_processing(document_version_id,status,text_hash,source_status,index_status) VALUES(?,'COMPLETED',?,'READY','READY')", version, hash);
            jdbc.update("""
                    INSERT INTO document_index_manifest(document_version_id,attempt,text_hash,embedding_profile_id,chunking_profile_id,parser_version,chunk_count)
                    SELECT document_version_id,attempt,text_hash,'design-test','test-chunk','test-parser',1 FROM document_processing WHERE document_version_id=?
                    """, version);
            String vector = "[1," + "0,".repeat(1022) + "0]";
            jdbc.update("INSERT INTO document_chunk(document_version_id,chunk_no,text,embedding) VALUES(?,0,'text',cast(? AS vector))", version, vector);
            versions.add(Long.toString(version));
        }
        var item = commands.create(actor, project.projectId(), new CreateDesignItemRequest("REQUIREMENT", 1,
                Map.of("description", "initial"), null, null), UUID.randomUUID().toString());
        String draftHash = queries.list(actor, project.projectId(), "DRAFT", null, null, null, 100).draftSnapshotHash();
        var request = new PublishInitialBaselineRequest(Long.toString(project.projectId()), versions, List.of(item.id()), draftHash);

        baselines.publish(actor, request, "default-no-legacy");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM baseline WHERE project_id=?", Integer.class, project.projectId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM publication_outbox WHERE project_id=?", Integer.class, project.projectId())).isZero();
    }
}
