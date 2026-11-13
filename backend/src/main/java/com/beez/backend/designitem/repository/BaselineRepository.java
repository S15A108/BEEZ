package com.beez.backend.designitem.repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class BaselineRepository {
    private final JdbcClient jdbc;

    public BaselineRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean exists(long project) {
        return jdbc.sql("SELECT id FROM baseline WHERE project_id=:project LIMIT 1")
                .param("project", project).query(Long.class).optional().isPresent();
    }

    public List<Map<String, Object>> findLatestDocuments(long project) {
        return jdbc.sql("""
                SELECT DISTINCT ON (d.id) d.id AS document_id,d.type,v.id,v.version_no,v.status,
                       v.content_hash,v.content_text,v.parent_version_id
                  FROM document d JOIN document_version v ON v.document_id=d.id
                 WHERE d.project_id=:project AND d.discarded_at IS NULL AND v.status IN ('DRAFT','APPROVED')
                   AND (NOT EXISTS (SELECT 1 FROM document_working_copy w WHERE w.document_id=d.id)
                        OR EXISTS (SELECT 1 FROM document_working_copy w WHERE w.document_id=d.id AND w.initial_version_id=v.id)
                        OR EXISTS (SELECT 1 FROM document_review_submission s WHERE s.document_version_id=v.id))
                 ORDER BY d.id,v.version_no DESC
                """).param("project", project).query().listOfRows();
    }

    public Map<String, Object> insertInitial(long project, long actor) {
        return jdbc.sql("INSERT INTO baseline(project_id,version_no,published_by) VALUES(:project,1,:actor) RETURNING id,published_at")
                .params(Map.of("project", project, "actor", actor)).query().singleRow();
    }

    public List<Map<String, Object>> workingCopyStates(long project) {
        return jdbc.sql("SELECT w.document_id,w.revision,w.status FROM document_working_copy w JOIN document d ON d.id=w.document_id WHERE d.project_id=:project AND d.discarded_at IS NULL ORDER BY w.document_id")
                .param("project", project).query().listOfRows();
    }

    public boolean hasUnapprovedWorkingCopies(long project) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM document_working_copy w JOIN document d ON d.id=w.document_id WHERE d.project_id=:project AND d.discarded_at IS NULL AND w.status<>'APPROVED')")
                .param("project", project).query(Boolean.class).single();
    }

    public int approveItem(long version) {
        return jdbc.sql("UPDATE design_item_version SET status='APPROVED',approved_at=now(),updated_at=now() WHERE id=:id AND status='DRAFT'")
                .param("id", version).update();
    }

    public void insertDocumentRef(long baseline, long project, long document, long version) {
        jdbc.sql("INSERT INTO baseline_document(baseline_id,project_id,document_id,document_version_id) VALUES(:baseline,:project,:document,:version)")
                .params(Map.of("baseline", baseline, "project", project, "document", document, "version", version)).update();
    }

    public void insertItemRef(long baseline, long project, long item, long version) {
        jdbc.sql("INSERT INTO baseline_item(baseline_id,project_id,design_item_id,design_item_version_id) VALUES(:baseline,:project,:item,:version)")
                .params(Map.of("baseline", baseline, "project", project, "item", item, "version", version)).update();
    }

    /**
     * 이 기준에서의 설계 항목 유효 출처를 기록한다(S15P31A108-406). 행이 없으면 항목 버전의 출처가 그대로 유효 출처다.
     * baseline_item 행이 먼저 있어야 한다.
     */
    public void insertItemSource(long baseline, long project, long item, long documentVersion, String locatorJson,
                                 String status, long remappedFrom) {
        insertItemSource(baseline, project, item, documentVersion, locatorJson, status, remappedFrom, null, null);
    }

    /** 사람이 확정한 행(CONFIRMED)을 다음 기준으로 이어받을 때는 확정한 사람·때도 그대로 옮긴다(S15P31A108-422). */
    public void insertItemSource(long baseline, long project, long item, long documentVersion, String locatorJson,
                                 String status, long remappedFrom, Long confirmedBy, Object confirmedAt) {
        var params = new java.util.HashMap<String, Object>();
        params.put("baseline", baseline); params.put("project", project); params.put("item", item);
        params.put("version", documentVersion); params.put("locator", locatorJson); params.put("status", status);
        params.put("from", remappedFrom); params.put("by", confirmedBy); params.put("at", confirmedAt);
        jdbc.sql("""
                INSERT INTO baseline_item_source(baseline_id,project_id,design_item_id,source_document_version_id,
                  source_locator,source_status,remapped_from_document_version_id,confirmed_by,confirmed_at)
                VALUES(:baseline,:project,:item,:version,cast(:locator AS jsonb),:status,:from,:by,cast(:at AS timestamptz))
                """).params(params).update();
    }

    /**
     * 현재 기준에서 항목의 유효 출처와 그 문서 원문(S15P31A108-422). 항목이 현재 기준에 없으면 빈 값이다.
     * 행: baseline_id, design_item_version_id, source_document_version_id(출처 없으면 null), document_version_id(현재 기준 문서에
     * 든 그 버전, 아니면 null), content_text, text_hash.
     */
    public Optional<Map<String, Object>> currentItemSource(long project, long item) {
        return jdbc.sql("""
                SELECT bi.baseline_id,bi.design_item_version_id,es.source_document_version_id,
                       bd.document_version_id,dv.content_text,dp.text_hash
                  FROM project p
                  JOIN baseline_item bi ON bi.baseline_id=p.current_baseline_id AND bi.project_id=p.id
                  JOIN baseline_item_effective_source es ON es.baseline_id=bi.baseline_id AND es.design_item_id=bi.design_item_id
                  LEFT JOIN baseline_document bd ON bd.baseline_id=bi.baseline_id AND bd.project_id=p.id
                       AND bd.document_version_id=es.source_document_version_id
                  LEFT JOIN document_version dv ON dv.id=bd.document_version_id
                  LEFT JOIN document_processing dp ON dp.document_version_id=dv.id
                 WHERE p.id=:project AND bi.design_item_id=:item
                """).param("project", project).param("item", item).query().listOfRows().stream().findFirst();
    }

    /**
     * 사람이 확정한 출처 위치를 이 기준의 유효 출처로 기록한다(S15P31A108-422). 이미 행이 있으면 같은 문서 버전에서 위치만 바꾼다
     * (V53 트리거가 CONFIRMED 로의 갱신만 허용).
     */
    public void confirmItemSource(long baseline, long project, long item, long documentVersion, String locatorJson, long actor) {
        jdbc.sql("""
                INSERT INTO baseline_item_source(baseline_id,project_id,design_item_id,source_document_version_id,
                  source_locator,source_status,remapped_from_document_version_id,confirmed_by,confirmed_at)
                VALUES(:baseline,:project,:item,:version,cast(:locator AS jsonb),'CONFIRMED',:version,:actor,now())
                ON CONFLICT (baseline_id,design_item_id) DO UPDATE
                   SET source_locator=EXCLUDED.source_locator,source_status='CONFIRMED',
                       remapped_from_document_version_id=EXCLUDED.remapped_from_document_version_id,
                       confirmed_by=EXCLUDED.confirmed_by,confirmed_at=EXCLUDED.confirmed_at
                """).param("baseline", baseline).param("project", project).param("item", item)
                .param("version", documentVersion).param("locator", locatorJson).param("actor", actor).update();
    }

    /** A version's index is ready when its current attempt published every chunk with an embedding for the profile. */
    private static final String READY_INDEX = """
            SELECT 1 FROM document_processing p JOIN document_index_manifest m USING(document_version_id)
            WHERE p.document_version_id=%s AND p.status='COMPLETED' AND p.source_status='READY'
              AND p.index_status='READY' AND m.attempt=p.attempt AND m.text_hash=p.text_hash
              AND m.embedding_profile_id=:profile AND m.chunk_count>0
              AND m.chunk_count=(SELECT count(*) FROM document_chunk c WHERE c.document_version_id=p.document_version_id AND c.embedding IS NOT NULL)
              AND NOT EXISTS (SELECT 1 FROM document_chunk c WHERE c.document_version_id=p.document_version_id AND c.embedding IS NULL)
            """;

    public boolean isIndexReady(long version, String profile) {
        return jdbc.sql("SELECT EXISTS (" + READY_INDEX.formatted(":version") + ")")
                .params(Map.of("version", version, "profile", profile)).query(Boolean.class).single();
    }

    /** Baseline documents whose index is not ready; semantic search over the baseline requires zero. */
    public int countDocumentsWithoutReadyIndex(long baseline, String profile) {
        return jdbc.sql("SELECT count(*) FROM baseline_document b WHERE b.baseline_id=:baseline AND NOT EXISTS (" + READY_INDEX.formatted("b.document_version_id") + ")")
                .params(Map.of("baseline", baseline, "profile", profile)).query(Integer.class).single();
    }
}
