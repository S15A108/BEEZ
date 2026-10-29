package com.beez.backend.designitem;

import static org.assertj.core.api.Assertions.*;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.designitem.dto.DesignItemResponse;
import com.beez.backend.designitem.service.DesignItemQueryService;
import com.beez.backend.designitem.support.DesignJson;
import com.beez.backend.support.AccessFixtures;
import com.beez.backend.support.IntegrationTestSupport;
import java.util.Map;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class DesignItemQueryIntegrationTest extends IntegrationTestSupport {
    @Autowired DesignItemQueryService queries;
    @Autowired JdbcTemplate jdbc;
    AccessFixtures.ProjectWithLeader project;
    CallerContext leader;

    @BeforeEach void setup() {
        project = fixtures().projectWithLeader();
        leader = CallerContext.human(project.leaderAccountId());
    }

    @Test void draftSnapshotCoversAllItemsEvenWhenPageAndKindFilterHideThem() {
        long first = item("REQ-001", "REQUIREMENT");
        long hidden = item("POL-001", "POLICY");
        var filtered = queries.list(leader, project.projectId(), "DRAFT", "REQUIREMENT", null, null, 1);
        var all = queries.list(leader, project.projectId(), "DRAFT", null, null, null, 1);
        assertThat(filtered.items()).hasSize(1);
        assertThat(filtered.items().getFirst().id()).isEqualTo(Long.toString(first));
        assertThat(filtered.draftSnapshotHash()).isEqualTo(all.draftSnapshotHash());
        assertThat(all.hasMore()).isTrue();
        var second = queries.list(leader, project.projectId(), "DRAFT", null, null, all.nextCursor(), 1);
        assertThat(second.items().getFirst().id()).isEqualTo(Long.toString(hidden));
        jdbc.update("UPDATE design_item_version SET content_hash='changed' WHERE id=?", hidden);
        assertThat(queries.list(leader, project.projectId(), "DRAFT", "REQUIREMENT", null, null, 1).draftSnapshotHash())
                .isNotEqualTo(filtered.draftSnapshotHash());
        assertThatThrownBy(() -> queries.list(leader, project.projectId(), "DRAFT", null, null, all.nextCursor(), 1))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("draft_changed"));
    }

    @Test void cursorCannotBeReusedForAnotherProjectOrFilterAndMissingSourceReturnsNoRows() {
        item("REQ-001", "REQUIREMENT"); item("POL-001", "POLICY");
        String cursor = queries.list(leader, project.projectId(), "DRAFT", null, null, null, 1).nextCursor();
        assertThatThrownBy(() -> queries.list(leader, project.projectId(), "DRAFT", "POLICY", null, cursor, 1))
                .isInstanceOf(ApiException.class);
        assertThat(queries.list(leader, project.projectId(), "DRAFT", null, Long.MAX_VALUE, null, 1).items()).isEmpty();
        var foreign = fixtures().projectWithLeader();
        assertThatThrownBy(() -> queries.list(CallerContext.human(foreign.leaderAccountId()),
                project.projectId(), "DRAFT", null, null, null, 20)).isInstanceOf(ApiException.class);
    }

    @Test
    void foreignDocumentVersionFilterReturnsEmptyButInvalidIdRemainsBadRequest() {
        item("REQ-001", "REQUIREMENT");
        var foreign = fixtures().projectWithLeader();
        long document = jdbc.queryForObject("INSERT INTO document(project_id,type,title,created_by) VALUES(?,'PLAN','foreign',?) RETURNING id",
                Long.class, foreign.projectId(), foreign.leaderAccountId());
        long version = jdbc.queryForObject("""
                INSERT INTO document_version(document_id,version_no,status,storage_key,content_hash,content_text)
                VALUES(?,1,'DRAFT','foreign.md','hash','foreign') RETURNING id
                """, Long.class, document);
        var page = queries.list(leader, project.projectId(), "DRAFT", null, version, null, 20);
        assertThat(page.items()).isEmpty();
        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextCursor()).isNull();
        var http = client().login(project.leaderEmail(), AccessFixtures.PASSWORD);
        var response = http.get("/api/projects/" + project.projectId() + "/design-items?status=DRAFT&document_version_id=" + version);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(DesignJson.JSON.readTree(response.body()).path("items").isEmpty()).isTrue();
        assertThatThrownBy(() -> queries.list(leader, project.projectId(), "DRAFT", null, -1L, null, 20))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(400));
    }

    @Test void approvedListUsesCurrentBaselineRatherThanEveryApprovedVersion() {
        long version = item("REQ-001", "REQUIREMENT");
        jdbc.update("UPDATE design_item_version SET status='APPROVED',approved_at=now() WHERE id=?", version);
        assertThat(queries.list(leader, project.projectId(), "APPROVED", null, null, null, 20).items()).isEmpty();
        long baseline = jdbc.queryForObject("""
                INSERT INTO baseline(project_id,version_no,published_by) VALUES(?,1,?) RETURNING id
                """, Long.class, project.projectId(), project.leaderAccountId());
        jdbc.update("""
                INSERT INTO baseline_item(baseline_id,project_id,design_item_id,design_item_version_id)
                SELECT ?,?,design_item_id,id FROM design_item_version WHERE id=?
                """, baseline, project.projectId(), version);
        jdbc.update("UPDATE project SET current_baseline_id=? WHERE id=?", baseline, project.projectId());
        assertThat(queries.list(leader, project.projectId(), "APPROVED", null, null, null, 20).items())
                .extracting(DesignItemResponse::id).containsExactly(Long.toString(version));
    }

    @Test
    void approvedCursorKeepsFirstBaselineWhenDraftsAndCurrentBaselineChange() {
        long first = item("REQ-001", "REQUIREMENT");
        long second = item("POL-001", "POLICY");
        long baseline = publishFixture(1, first, second);
        var page = queries.list(leader, project.projectId(), "APPROVED", null, null, null, 1);
        assertThat(page.items()).extracting(DesignItemResponse::id).containsExactly(Long.toString(first));
        assertThat(page.hasMore()).isTrue();
        assertThat(page.draftSnapshotHash()).isNull();

        long document = jdbc.queryForObject("INSERT INTO document(project_id,type,title,created_by) VALUES(?,'PLAN','draft',?) RETURNING id",
                Long.class, project.projectId(), project.leaderAccountId());
        jdbc.update("""
                INSERT INTO document_version(document_id,version_no,status,storage_key,content_hash,content_text)
                VALUES(?,1,'DRAFT','draft.md','hash','draft')
                """, document);
        assertThat(queries.list(leader, project.projectId(), "APPROVED", null, null, page.nextCursor(), 1).items())
                .extracting(DesignItemResponse::id).containsExactly(Long.toString(second));

        jdbc.update("UPDATE design_item_version SET status='SUPERSEDED' WHERE id IN (?,?)", first, second);
        jdbc.update("UPDATE design_item SET retired_at=now() WHERE id=(SELECT design_item_id FROM design_item_version WHERE id=?)", second);
        long latest = item("REQ-002", "REQUIREMENT");
        long nextBaseline = publishFixture(2, latest);
        assertThat(nextBaseline).isNotEqualTo(baseline);
        assertThat(queries.list(leader, project.projectId(), "APPROVED", null, null, page.nextCursor(), 1).items())
                .extracting(DesignItemResponse::id).containsExactly(Long.toString(second));
        assertThat(queries.list(leader, project.projectId(), "APPROVED", null, null, null, 20).items())
                .extracting(DesignItemResponse::id).containsExactly(Long.toString(latest));
    }

    @Test
    void approvedCursorCannotSelectAnotherProjectsBaseline() {
        long first = item("REQ-001", "REQUIREMENT");
        long second = item("REQ-002", "REQUIREMENT");
        publishFixture(1, first, second);
        String cursor = queries.list(leader, project.projectId(), "APPROVED", null, null, null, 1).nextCursor();
        var foreign = fixtures().projectWithLeader();
        long foreignBaseline = jdbc.queryForObject("INSERT INTO baseline(project_id,version_no,published_by) VALUES(?,1,?) RETURNING id",
                Long.class, foreign.projectId(), foreign.leaderAccountId());
        String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|", -1);
        parts[1] = "baseline:" + foreignBaseline;
        String forged = Base64.getUrlEncoder().withoutPadding().encodeToString(String.join("|", parts).getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> queries.list(leader, project.projectId(), "APPROVED", null, null, forged, 1))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(400));
    }

    private long publishFixture(int versionNo, long... versions) {
        long baseline = jdbc.queryForObject("INSERT INTO baseline(project_id,version_no,published_by) VALUES(?,?,?) RETURNING id",
                Long.class, project.projectId(), versionNo, project.leaderAccountId());
        for (long version : versions) {
            jdbc.update("UPDATE design_item_version SET status='APPROVED',approved_at=now() WHERE id=?", version);
            jdbc.update("""
                    INSERT INTO baseline_item(baseline_id,project_id,design_item_id,design_item_version_id)
                    SELECT ?,?,design_item_id,id FROM design_item_version WHERE id=?
                    """, baseline, project.projectId(), version);
        }
        jdbc.update("UPDATE project SET current_baseline_id=? WHERE id=?", baseline, project.projectId());
        return baseline;
    }

    private long item(String key, String kind) {
        long item = jdbc.queryForObject("INSERT INTO design_item(project_id,key,kind) VALUES(?,?,?) RETURNING id",
                Long.class, project.projectId(), key, kind);
        return jdbc.queryForObject("""
                INSERT INTO design_item_version(design_item_id,version_no,status,title,schema_version,content,content_hash,origin,created_by)
                VALUES(?,1,'DRAFT','조회',1,cast(? AS jsonb),?,'HUMAN',?) RETURNING id
                """, Long.class, item, "{\"description\":\"조회\"}", DesignJson.hash(Map.of("description", "조회")), project.leaderAccountId());
    }
}
