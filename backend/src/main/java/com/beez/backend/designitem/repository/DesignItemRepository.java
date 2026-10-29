package com.beez.backend.designitem.repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class DesignItemRepository {
    private static final String SELECT = """
            SELECT v.*,i.key,i.kind,s.document_id AS source_document_id,s.version_no AS source_version_no
              FROM design_item i JOIN design_item_version v ON v.design_item_id=i.id
              LEFT JOIN document_version s ON s.id=v.source_document_version_id
            """;

    private final JdbcClient jdbc;

    public DesignItemRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> findPage(long project, String status, String kind, Long source, long after, int size,
                                             Long baseline) {
        var parameters = new HashMap<String, Object>();
        parameters.put("project", project);
        parameters.put("status", status);
        parameters.put("after", after);
        parameters.put("limit", size);
        String filter = " WHERE i.project_id=:project AND v.id>:after";
        if ("APPROVED".equals(status)) {
            parameters.put("baseline", baseline);
            filter += """
                     AND EXISTS (SELECT 1 FROM baseline b JOIN baseline_item bi ON bi.baseline_id=b.id
                                  WHERE b.project_id=:project AND b.id=:baseline AND bi.design_item_version_id=v.id)
                    """;
        } else {
            filter += " AND i.retired_at IS NULL AND v.status=:status";
        }
        if (kind != null) {
            filter += " AND i.kind=:kind";
            parameters.put("kind", kind);
        }
        if (source != null) {
            filter += " AND v.source_document_version_id=:source";
            parameters.put("source", source);
        }
        return jdbc.sql(SELECT + filter + " ORDER BY v.id LIMIT :limit").params(parameters).query().listOfRows();
    }

    public Optional<Map<String, Object>> findVersion(long project, long item, long version) {
        return jdbc.sql(SELECT + " WHERE i.project_id=:project AND i.id=:item AND v.id=:version")
                .params(Map.of("project", project, "item", item, "version", version))
                .query().listOfRows().stream().findFirst();
    }

    public List<Map<String, Object>> findDrafts(long project) {
        return jdbc.sql("""
                SELECT i.id AS design_item_id,i.key,i.kind,v.id,v.version_no,v.schema_version,
                       v.content_hash,v.content,v.source_document_version_id,v.source_locator
                  FROM design_item i JOIN design_item_version v ON v.design_item_id=i.id
                 WHERE i.project_id=:project AND i.retired_at IS NULL AND v.status='DRAFT'
                 ORDER BY i.id,v.id
                """).param("project", project).query().listOfRows();
    }

    public boolean sourceBelongsToProject(long project, long source) {
        return jdbc.sql("""
                SELECT v.id FROM document_version v JOIN document d ON d.id=v.document_id
                 WHERE v.id=:source AND d.project_id=:project
                """).param("source", source).param("project", project).query(Long.class).optional().isPresent();
    }

    public long nextKeySequence(long project, String prefix) {
        return jdbc.sql("SELECT coalesce(max(substring(key from :pattern)::bigint),0)+1 FROM design_item WHERE project_id=:project")
                .param("pattern", "^" + prefix + "-([0-9]+)$").param("project", project).query(Long.class).single();
    }

    public long insertItem(long project, String key, String kind) {
        return jdbc.sql("INSERT INTO design_item(project_id,key,kind) VALUES(:project,:key,:kind) RETURNING id")
                .params(Map.of("project", project, "key", key, "kind", kind)).query(Long.class).single();
    }

    public long insertDraft(long item, String title, String content, String hash, Long source, String locator, long actor) {
        var parameters = new HashMap<String, Object>();
        parameters.put("item", item);
        parameters.put("title", title);
        parameters.put("content", content);
        parameters.put("hash", hash);
        parameters.put("source", source);
        parameters.put("locator", locator);
        parameters.put("actor", actor);
        return jdbc.sql("""
                INSERT INTO design_item_version(design_item_id,version_no,status,title,schema_version,content,
                                                content_hash,source_document_version_id,source_locator,origin,created_by)
                VALUES(:item,1,'DRAFT',:title,1,cast(:content AS jsonb),:hash,:source,cast(:locator AS jsonb),'HUMAN',:actor)
                RETURNING id
                """).params(parameters).query(Long.class).single();
    }

    public int updateDraft(long item, long version, String title, String content, String hash, String expectedHash) {
        return jdbc.sql("""
                UPDATE design_item_version SET title=:title,content=cast(:content AS jsonb),content_hash=:hash,updated_at=now()
                 WHERE id=:version AND design_item_id=:item AND status='DRAFT' AND content_hash=:expected
                """).params(Map.of("title", title, "content", content, "hash", hash,
                        "version", version, "item", item, "expected", expectedHash)).update();
    }

    public int excludeDraft(long item, long version, String expectedHash) {
        return jdbc.sql("""
                UPDATE design_item_version SET status='REJECTED',updated_at=now()
                 WHERE id=:version AND design_item_id=:item AND status='DRAFT' AND content_hash=:hash
                """).params(Map.of("version", version, "item", item, "hash", expectedHash)).update();
    }

    public void updateDraftSource(long item, long version, Long source, String locator) {
        var params = new HashMap<String, Object>();
        params.put("item", item); params.put("version", version); params.put("source", source); params.put("locator", locator);
        jdbc.sql("UPDATE design_item_version SET source_document_version_id=:source,source_locator=cast(:locator AS jsonb),updated_at=now() WHERE design_item_id=:item AND id=:version AND status='DRAFT'")
                .params(params).update();
    }

    public boolean isActive(long item) {
        return jdbc.sql("SELECT id FROM design_item WHERE id=:item AND retired_at IS NULL")
                .param("item", item).query(Long.class).optional().isPresent();
    }
}
