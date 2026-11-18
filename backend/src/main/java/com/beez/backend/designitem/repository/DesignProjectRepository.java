package com.beez.backend.designitem.repository;

import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class DesignProjectRepository {
    private final JdbcClient jdbc;

    public DesignProjectRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean lockActiveProject(long project) {
        return jdbc.sql("SELECT id FROM project WHERE id=:project AND archived_at IS NULL FOR UPDATE")
                .param("project", project).query(Long.class).optional().isPresent();
    }

    public Optional<String> findCurrentRole(long project, long account) {
        return jdbc.sql("""
                SELECT m.role FROM project_member m JOIN account a ON a.id=m.account_id
                 WHERE m.project_id=:project AND m.account_id=:account AND m.active=true AND a.deleted_at IS NULL
                """).param("project", project).param("account", account).query(String.class).optional();
    }

    public Long currentBaselineId(long project) {
        Object value = jdbc.sql("SELECT current_baseline_id FROM project WHERE id=:project")
                .param("project", project).query().singleRow().get("current_baseline_id");
        return value == null ? null : ((Number) value).longValue();
    }

    public int setInitialBaseline(long project, long baseline) {
        return jdbc.sql("UPDATE project SET current_baseline_id=:baseline,updated_at=now() WHERE id=:project AND current_baseline_id IS NULL")
                .params(Map.of("baseline", baseline, "project", project)).update();
    }

    public boolean baselineBelongsToProject(long project, long baseline) {
        return jdbc.sql("SELECT id FROM baseline WHERE id=:baseline AND project_id=:project")
                .params(Map.of("baseline", baseline, "project", project)).query(Long.class).optional().isPresent();
    }
}
