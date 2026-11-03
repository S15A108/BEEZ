package com.beez.backend.changeproposal.repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 문서 수정 초안(CHG-A07)이 AI 에 보낼 입력을 읽는다. 읽기 전용 — 아무것도 쓰지 않는다. */
@Repository
public class DraftContextRepository {
    private final JdbcClient jdbc;
    public DraftContextRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public boolean projectActive(long project) {
        return jdbc.sql("SELECT id FROM project WHERE id=:project AND archived_at IS NULL")
                .param("project", project).query(Long.class).optional().isPresent();
    }

    public boolean documentInProject(long project, long document) {
        return jdbc.sql("SELECT id FROM document WHERE id=:document AND project_id=:project")
                .param("document", document).param("project", project).query(Long.class).optional().isPresent();
    }

    public boolean taskInProject(long project, long task) {
        return jdbc.sql("SELECT id FROM task WHERE id=:task AND project_id=:project")
                .param("task", task).param("project", project).query(Long.class).optional().isPresent();
    }

    public Long currentBaseline(long project) {
        return jdbc.sql("SELECT current_baseline_id FROM project WHERE id=:project")
                .param("project", project).query(Long.class).optional().orElse(null);
    }

    /** 그 기준에 들어 있는 문서의 승인본. 기준에 없는 문서면 비어 있다. */
    public Optional<Map<String, Object>> baselineVersion(long baseline, long document) {
        return jdbc.sql("""
                SELECT v.id,v.document_id,v.version_no,v.status,v.storage_key,v.content_hash,v.content_text,v.created_by,v.created_at
                FROM baseline_document bd JOIN document_version v ON v.id=bd.document_version_id AND v.document_id=bd.document_id
                WHERE bd.baseline_id=:baseline AND bd.document_id=:document
                """).param("baseline", baseline).param("document", document).query().listOfRows().stream().findFirst();
    }

    /** 현재 기준의 항목 중 출처가 이 문서 버전인 것의 수. 퇴역한 항목은 세지 않는다. */
    public int relatedItemCount(long baseline, long documentVersion) {
        return jdbc.sql("""
                SELECT count(*) FROM baseline_item bi JOIN design_item i ON i.id=bi.design_item_id AND i.project_id=bi.project_id
                JOIN design_item_version v ON v.id=bi.design_item_version_id
                WHERE bi.baseline_id=:baseline AND i.retired_at IS NULL AND v.source_document_version_id=:version
                """).param("baseline", baseline).param("version", documentVersion).query(Integer.class).single();
    }

    /** 키 순서로 고정해 같은 입력이면 같은 목록이 나가게 한다. */
    public List<Map<String, Object>> relatedItems(long baseline, long documentVersion, int limit) {
        return jdbc.sql("""
                SELECT v.id,i.id AS design_item_id,i.key,i.kind,v.version_no,v.status,v.schema_version,
                       v.content::text AS content,v.source_document_version_id,v.source_locator::text AS source_locator
                FROM baseline_item bi JOIN design_item i ON i.id=bi.design_item_id AND i.project_id=bi.project_id
                JOIN design_item_version v ON v.id=bi.design_item_version_id
                WHERE bi.baseline_id=:baseline AND i.retired_at IS NULL AND v.source_document_version_id=:version
                ORDER BY i.key LIMIT :limit
                """).param("baseline", baseline).param("version", documentVersion).param("limit", limit).query().listOfRows();
    }
}
