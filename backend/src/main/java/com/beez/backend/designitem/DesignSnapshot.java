package com.beez.backend.designitem;

import static com.beez.backend.designitem.DesignJson.*;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** The complete project draft, never a filtered page. Read under repeatable-read or a project lock. */
@Component
class DesignSnapshot {
    private final JdbcClient jdbc;
    DesignSnapshot(JdbcClient jdbc) { this.jdbc = jdbc; }

    List<Map<String, Object>> documents(long project) {
        return jdbc.sql("""
                SELECT DISTINCT ON (d.id) d.id AS document_id,d.type,v.id,v.version_no,v.status,
                       v.content_hash,v.content_text,v.parent_version_id
                  FROM document d JOIN document_version v ON v.document_id=d.id
                 WHERE d.project_id=:project AND v.status='DRAFT'
                 ORDER BY d.id,v.version_no DESC
                """).param("project", project).query().listOfRows();
    }
    List<Map<String, Object>> items(long project) {
        return jdbc.sql("""
                SELECT i.id AS design_item_id,i.key,i.kind,v.id,v.version_no,v.schema_version,
                       v.content_hash,v.content,v.source_document_version_id,v.source_locator
                  FROM design_item i JOIN design_item_version v ON v.design_item_id=i.id
                 WHERE i.project_id=:project AND i.retired_at IS NULL AND v.status='DRAFT'
                 ORDER BY i.id,v.id
                """).param("project", project).query().listOfRows();
    }
    String hash(long project) {
        var docs = documents(project).stream().map(r -> List.of(r.get("document_id"), r.get("type"),
                r.get("id"), r.get("content_hash"))).toList();
        var items = items(project).stream().map(r -> {
            var source = r.get("source_document_version_id");
            var locator = r.get("source_locator");
            return List.of(r.get("design_item_id"), r.get("key"), r.get("kind"), r.get("id"),
                    r.get("schema_version"), r.get("content_hash"), source == null ? "" : source,
                    locator == null ? Map.of() : object(locator));
        }).toList();
        Object baseline = jdbc.sql("SELECT current_baseline_id FROM project WHERE id=:project")
                .param("project", project).query().singleRow().get("current_baseline_id");
        return DesignJson.hash(Map.of("project_id", project, "baseline_id", baseline == null ? "" : baseline,
                "documents", docs, "items", items));
    }
}
