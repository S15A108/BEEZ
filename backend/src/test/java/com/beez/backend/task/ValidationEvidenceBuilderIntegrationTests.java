package com.beez.backend.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.beez.backend.support.IntegrationTestSupport;
import com.beez.backend.validation.service.ValidationEvidenceBuilder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Verifies source scoping and exact excerpt generation against an isolated PostgreSQL. */
class ValidationEvidenceBuilderIntegrationTests extends IntegrationTestSupport {
    @Autowired DataSource dataSource;
    @Autowired ValidationEvidenceBuilder builder;
    private TaskApiSupport api;
    private long project, owner, baseline;
    private final String content = "# 회원 API\n요청: userId\n응답: name\n";

    @BeforeEach void setup() {
        api = new TaskApiSupport(this::client, dataSource);
        api.resetAndLoadFixture();
        project = api.projectId(); owner = api.accountId("member@test");
        baseline = api.jdbc.sql("select current_baseline_id from project where id=:id")
                .param("id", project).query(Long.class).single();
    }

    private Map<String, Object> seed(String locator, boolean includeDoc, boolean matchingHash) throws Exception {
        long document = api.jdbc.sql("insert into document(project_id,type,title,created_by) values(:project,'API_SPEC','회원 API',:owner) returning id")
                .param("project", project).param("owner", owner).query(Long.class).single();
        long documentVersion = api.jdbc.sql("""
                insert into document_version(document_id,version_no,status,parse_status,storage_key,content_hash,content_text,created_by,approved_at)
                values(:document,1,'APPROVED','OK','synthetic.md','source-hash',:text,:owner,now()) returning id
                """).param("document", document).param("text", content).param("owner", owner).query(Long.class).single();
        if (includeDoc) api.jdbc.sql("insert into baseline_document(baseline_id,project_id,document_id,document_version_id) values(:baseline,:project,:document,:version)")
                .param("baseline", baseline).param("project", project).param("document", document).param("version", documentVersion).update();
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        api.jdbc.sql("insert into document_processing(document_version_id,status,source_status,index_status,text_hash) values(:version,'COMPLETED','READY','READY',:hash)")
                .param("version", documentVersion).param("hash", matchingHash ? "sha256:" + hash : "sha256:invalid").update();
        long item = api.jdbc.sql("insert into design_item(project_id,key,kind) values(:project,'API-184E','API_CONTRACT') returning id")
                .param("project", project).query(Long.class).single();
        long version = api.jdbc.sql("""
                insert into design_item_version(design_item_id,version_no,status,title,schema_version,content,content_hash,
                  origin,approved_at,source_document_version_id,source_locator)
                values(:item,1,'APPROVED','회원 API',1,'{"description":"회원 API","method":"POST","path":"/members"}'::jsonb,
                  'design-hash','HUMAN',now(),:source,cast(:locator as jsonb)) returning id
                """).param("item", item).param("source", documentVersion).param("locator", locator).query(Long.class).single();
        api.jdbc.sql("insert into baseline_item(baseline_id,project_id,design_item_id,design_item_version_id) values(:baseline,:project,:item,:version)")
                .param("baseline", baseline).param("project", project).param("item", item).param("version", version).update();
        return Map.of("id", Long.toString(version), "design_item_id", Long.toString(item),
                "source", Map.of("document_id", Long.toString(document), "document_version_id", Long.toString(documentVersion),
                        "version_no", 1, "location", "회원 API"));
    }

    @Test void verifiesRealDocumentAndRestoresExactLines() throws Exception {
        var item = seed("{\"location\":\"회원 API\",\"start_line\":2,\"end_line\":3}", true, true);
        var result = builder.build(project, baseline, List.of(item));
        assertThat(result.unavailableVersionIds()).isEmpty();
        assertThat(result.evidence()).hasSize(1);
        assertThat(result.evidence().getFirst()).containsEntry("text", "요청: userId\n응답: name\n")
                .containsEntry("design_item_version_id", item.get("id"));
        assertThat(((String) result.evidence().getFirst().get("text_hash"))).hasSize(64);
    }

    @Test void noPreciseRangeIsExplicitlyUnavailable() throws Exception {
        var item = seed("{\"location\":\"회원 API\"}", true, true);
        var result = builder.build(project, baseline, List.of(item));
        assertThat(result.evidence()).isEmpty();
        assertThat(result.unavailableVersionIds()).containsExactly(item.get("id").toString());
    }

    @Test void outOfBoundsDisplayLocationIsUnavailableWithoutDiscardingValidHash() throws Exception {
        var item = seed("{\"location\":\"5000행\"}", true, true);
        var source = (Map<String, Object>) item.get("source");
        var request = Map.of("id", item.get("id"), "design_item_id", item.get("design_item_id"),
                "source", Map.of("document_id", source.get("document_id"),
                        "document_version_id", source.get("document_version_id"),
                        "version_no", source.get("version_no"), "location", "5000행"));
        var result = builder.build(project, baseline, List.of(request));
        assertThat(result.evidence()).isEmpty();
        assertThat(result.unavailableVersionIds()).containsExactly(item.get("id").toString());
    }
    @Test void tamperedHashFailsInsteadOfSendingEvidence() throws Exception {
        var item = seed("{\"location\":\"회원 API\",\"start_line\":1,\"end_line\":2}", true, false);
        assertThatThrownBy(() -> builder.build(project, baseline, List.of(item))).isInstanceOf(IllegalStateException.class);
    }

    @Test void nonBaselineDocumentCannotBecomeEvidence() throws Exception {
        var item = seed("{\"location\":\"회원 API\",\"start_line\":1,\"end_line\":2}", false, true);
        var result = builder.build(project, baseline, List.of(item));
        assertThat(result.evidence()).isEmpty();
        assertThat(result.unavailableVersionIds()).containsExactly(item.get("id").toString());
    }
}
