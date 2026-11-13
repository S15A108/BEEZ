package com.beez.backend.designitem;

import static org.assertj.core.api.Assertions.assertThat;

import com.beez.backend.support.IntegrationTestSupport;
import com.beez.backend.task.TaskApiSupport;
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

/**
 * PLN-A11 기준 설계 항목 출처 위치 확정(S15P31A108-422). 프로젝트 30 의 REQ-015·016 처럼 출처 문서는 있는데 위치가 없는 항목은
 * 근거 수집이 쓸 수 없어 그 항목을 참조하는 작업 검증이 영구히 막혔다. 시작된 프로젝트에서 팀장이 위치를 채우면 풀려야 한다.
 */
class DesignItemSourceConfirmIntegrationTest extends IntegrationTestSupport {
    @Autowired DataSource dataSource;
    @Autowired ValidationEvidenceBuilder builder;
    private TaskApiSupport api;
    private long project, leader, member, baseline, document, documentVersion;
    private final String content = "# 요구사항\n## 방\n방장은 준비 취소를 거부할 수 있다.\n카운트다운은 3초다.\n";

    @BeforeEach void setup() throws Exception {
        api = new TaskApiSupport(this::client, dataSource);
        api.resetAndLoadFixture();
        project = api.projectId(); leader = api.accountId("leader@test"); member = api.accountId("member@test");
        baseline = api.jdbc.sql("select current_baseline_id from project where id=:id").param("id", project).query(Long.class).single();
        document = api.jdbc.sql("insert into document(project_id,type,title,created_by) values(:project,'REQ_SPEC','요구사항',:owner) returning id")
                .param("project", project).param("owner", leader).query(Long.class).single();
        documentVersion = api.jdbc.sql("""
                insert into document_version(document_id,version_no,status,parse_status,storage_key,content_hash,content_text,created_by,approved_at)
                values(:document,1,'APPROVED','OK','synthetic.md','source-hash',:text,:owner,now()) returning id
                """).param("document", document).param("text", content).param("owner", leader).query(Long.class).single();
        api.jdbc.sql("insert into baseline_document(baseline_id,project_id,document_id,document_version_id) values(:baseline,:project,:document,:version)")
                .param("baseline", baseline).param("project", project).param("document", document).param("version", documentVersion).update();
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        api.jdbc.sql("insert into document_processing(document_version_id,status,source_status,index_status,text_hash) values(:version,'COMPLETED','READY','READY',:hash)")
                .param("version", documentVersion).param("hash", "sha256:" + hash).update();
    }

    /** 승인된 기준 항목. source 가 false 면 출처 없는 수동 항목이다. */
    private long[] seedItem(String key, boolean source) {
        long item = api.jdbc.sql("insert into design_item(project_id,key,kind) values(:project,:key,'REQUIREMENT') returning id")
                .param("project", project).param("key", key).query(Long.class).single();
        long version = api.jdbc.sql("""
                insert into design_item_version(design_item_id,version_no,status,title,schema_version,content,content_hash,
                  origin,approved_at,source_document_version_id,source_locator)
                values(:item,1,'APPROVED','준비 취소',1,'{"description":"COUNTDOWN 전송 후 준비 취소 거부"}'::jsonb,
                  'design-hash','AI_PROPOSED',now(),:source,null) returning id
                """).param("item", item).param("source", source ? documentVersion : null).query(Long.class).single();
        api.jdbc.sql("insert into baseline_item(baseline_id,project_id,design_item_id,design_item_version_id) values(:baseline,:project,:item,:version)")
                .param("baseline", baseline).param("project", project).param("item", item).param("version", version).update();
        return new long[] { item, version };
    }

    private Map<String, Object> approved(long[] item, String location) {
        if (location == null) return Map.of("id", "" + item[1], "design_item_id", "" + item[0]);
        var source = Map.<String, Object>of("document_id", "" + document, "document_version_id", "" + documentVersion,
                "version_no", 1, "location", location);
        return Map.of("id", "" + item[1], "design_item_id", "" + item[0], "source", source);
    }

    private String body(long version, String rest) {
        return "{\"project_id\":\"" + project + "\",\"expected_version_id\":\"" + version + "\"," + rest + "}";
    }

    @Test void emptyLocationBlocksEvidenceUntilTheLeaderPicksLines() {
        long[] item = seedItem("REQ-015", true);
        // 위치가 없으면 근거를 쓸 수 없는 항목이다(이 상태로 검증이 영구히 막혔다).
        assertThat(builder.build(project, baseline, List.of(approved(item, null))).unavailableVersionIds())
                .containsExactly("" + item[1]);

        var response = api.putFromScreen("/api/human/design-items/" + item[0] + "/source", body(item[1], "\"start_line\":3,\"end_line\":4"), leader);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.body()).contains("\"location\":\"요구사항 / 방 / L3-L4\"");

        var result = builder.build(project, baseline, List.of(approved(item, "요구사항 / 방 / L3-L4")));
        assertThat(result.unavailableVersionIds()).isEmpty();
        assertThat(result.evidence()).singleElement()
                .satisfies(e -> assertThat(e).containsEntry("text", "방장은 준비 취소를 거부할 수 있다.\n카운트다운은 3초다.\n"));
    }

    @Test void noQuoteConfirmationLetsEvidenceSkipTheItem() {
        long[] item = seedItem("REQ-016", true);
        var response = api.putFromScreen("/api/human/design-items/" + item[0] + "/source", body(item[1], "\"no_quote\":true"), leader);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.body()).contains("\"location\":\"원문 인용 없음\"");
        var result = builder.build(project, baseline, List.of(approved(item, null)));
        assertThat(result.unavailableVersionIds()).isEmpty();
        assertThat(result.evidence()).isEmpty();
    }

    @Test void rejectsWhatCannotBeConfirmed() {
        long[] item = seedItem("REQ-017", true);
        String path = "/api/human/design-items/" + item[0] + "/source";
        assertThat(api.putFromScreen(path, body(item[1], "\"start_line\":1,\"end_line\":1"), member).statusCode()).isEqualTo(403);
        assertThat(api.putFromScreen(path, body(item[1], "\"start_line\":1,\"end_line\":1,\"no_quote\":true"), leader).statusCode()).isEqualTo(400);
        assertThat(api.putFromScreen(path, body(item[1], "\"start_line\":4,\"end_line\":5"), leader).statusCode()).isEqualTo(400);
        assertThat(api.putFromScreen(path, body(item[1] + 1000, "\"start_line\":1,\"end_line\":1"), leader).statusCode()).isEqualTo(409);
        long[] manual = seedItem("REQ-018", false);
        var noSource = api.putFromScreen("/api/human/design-items/" + manual[0] + "/source", body(manual[1], "\"no_quote\":true"), leader);
        assertThat(noSource.statusCode()).isEqualTo(409);
        assertThat(noSource.body()).contains("design_source_missing");
        assertThat(api.jdbc.sql("select count(*) from baseline_item_source where baseline_id=:b").param("b", baseline)
                .query(Integer.class).single()).isZero();
    }
}
