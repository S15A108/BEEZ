package com.beez.backend.designitem;

import static org.assertj.core.api.Assertions.*;

import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.support.AccessFixtures;
import com.beez.backend.support.IntegrationTestSupport;
import java.util.Map;
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
        assertThat(filtered.draft_snapshot_hash()).isEqualTo(all.draft_snapshot_hash());
        assertThat(all.has_more()).isTrue();
        var second = queries.list(leader, project.projectId(), "DRAFT", null, null, all.next_cursor(), 1);
        assertThat(second.items().getFirst().id()).isEqualTo(Long.toString(hidden));
        jdbc.update("UPDATE design_item_version SET content_hash='changed' WHERE id=?", hidden);
        assertThat(queries.list(leader, project.projectId(), "DRAFT", "REQUIREMENT", null, null, 1).draft_snapshot_hash())
                .isNotEqualTo(filtered.draft_snapshot_hash());
        assertThatThrownBy(() -> queries.list(leader, project.projectId(), "DRAFT", null, null, all.next_cursor(), 1))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("draft_changed"));
    }

    @Test void cursorCannotBeReusedForAnotherProjectOrFilterAndForeignSourceIsHidden() {
        item("REQ-001", "REQUIREMENT"); item("POL-001", "POLICY");
        String cursor = queries.list(leader, project.projectId(), "DRAFT", null, null, null, 1).next_cursor();
        assertThatThrownBy(() -> queries.list(leader, project.projectId(), "DRAFT", "POLICY", null, cursor, 1))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> queries.list(leader, project.projectId(), "DRAFT", null, Long.MAX_VALUE, null, 1))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(404));
        var foreign = fixtures().projectWithLeader();
        assertThatThrownBy(() -> queries.list(CallerContext.human(foreign.leaderAccountId()),
                project.projectId(), "DRAFT", null, null, null, 20)).isInstanceOf(ApiException.class);
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
                .extracting(DesignItemDtos.Item::id).containsExactly(Long.toString(version));
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
