package com.beez.backend.designitem;

import static com.beez.backend.designitem.DesignJson.*;

import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.access.AccessErrors;
import com.beez.backend.common.auth.CallerContext;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DesignItemQueryService {
    private static final String SELECT = """
            SELECT v.*,i.key,i.kind,s.document_id AS source_document_id,s.version_no AS source_version_no
              FROM design_item i JOIN design_item_version v ON v.design_item_id=i.id
              LEFT JOIN document_version s ON s.id=v.source_document_version_id
            """;
    private final JdbcClient jdbc;
    private final ProjectAccessChecker access;
    private final DesignSnapshot snapshots;
    public DesignItemQueryService(JdbcClient jdbc, ProjectAccessChecker access, DesignSnapshot snapshots) {
        this.jdbc = jdbc; this.access = access; this.snapshots = snapshots;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public DesignItemDtos.Page list(CallerContext caller, long project, String status, String kind,
                                    Long source, String cursor, int limit) {
        access.requireMember(caller, project);
        if (!Set.of("DRAFT", "APPROVED", "SUPERSEDED", "REJECTED").contains(status)
                || (kind != null && !KINDS.contains(kind)) || limit < 1 || limit > 100) throw invalid("목록 필터가 올바르지 않습니다.");
        if (source != null) requireSource(project, source);
        String snapshot = snapshots.hash(project);
        String selection = hash(Arrays.asList(project, status, kind, source));
        long after = 0;
        if (cursor != null) {
            String[] parts;
            try {
                if (cursor.length() > 512) throw new IllegalArgumentException();
                parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|", -1);
                if (parts.length != 3 || (after = Long.parseLong(parts[0])) < 1 || !parts[2].equals(selection)) {
                    throw new IllegalArgumentException();
                }
            } catch (IllegalArgumentException e) { throw invalid("목록 cursor가 올바르지 않습니다."); }
            if (!parts[1].equals(snapshot)) throw conflict("draft_changed");
        }
        var params = new HashMap<String, Object>();
        params.put("project", project); params.put("status", status); params.put("after", after); params.put("limit", limit + 1);
        String filter = " WHERE i.project_id=:project AND i.retired_at IS NULL AND v.status=:status AND v.id>:after";
        if ("APPROVED".equals(status)) filter += """
                 AND EXISTS (SELECT 1 FROM project p JOIN baseline_item bi ON bi.baseline_id=p.current_baseline_id
                              WHERE p.id=:project AND bi.design_item_version_id=v.id)
                """;
        if (kind != null) { filter += " AND i.kind=:kind"; params.put("kind", kind); }
        if (source != null) { filter += " AND v.source_document_version_id=:source"; params.put("source", source); }
        var rows = jdbc.sql(SELECT + filter + " ORDER BY v.id LIMIT :limit").params(params).query().listOfRows();
        boolean more = rows.size() > limit;
        var page = more ? rows.subList(0, limit) : rows;
        String next = more ? Base64.getUrlEncoder().withoutPadding().encodeToString(
                (page.getLast().get("id") + "|" + snapshot + "|" + selection).getBytes(StandardCharsets.UTF_8)) : null;
        return new DesignItemDtos.Page(page.stream().map(this::view).toList(), more, next,
                "DRAFT".equals(status) ? snapshot : null);
    }

    static final Set<String> KINDS = Set.of("REQUIREMENT", "API_CONTRACT", "DATA_CONTRACT", "POLICY", "CONSTRAINT");

    void requireSource(long project, long source) {
        if (source < 1 || jdbc.sql("""
                SELECT v.id FROM document_version v JOIN document d ON d.id=v.document_id
                 WHERE v.id=:source AND d.project_id=:project
                """).param("source", source).param("project", project).query(Long.class).optional().isEmpty()) {
            throw AccessErrors.notFound("design_source_not_in_project");
        }
    }
    DesignItemDtos.Item get(long project, long item, long version) {
        var row = jdbc.sql(SELECT + " WHERE i.project_id=:project AND i.id=:item AND v.id=:version")
                .params(Map.of("project", project, "item", item, "version", version)).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> AccessErrors.notFound("design_item_not_in_project"));
        return view(row);
    }
    private DesignItemDtos.Item view(Map<String, Object> row) {
        DesignItemDtos.Source source = null;
        if (row.get("source_document_version_id") != null) {
            var locator = row.get("source_locator") == null ? Map.<String, Object>of() : object(row.get("source_locator"));
            source = new DesignItemDtos.Source(row.get("source_document_id").toString(),
                    row.get("source_document_version_id").toString(), ((Number) row.get("source_version_no")).intValue(),
                    Objects.toString(locator.get("location"), locator.isEmpty() ? "" : canonical(locator)));
        }
        return new DesignItemDtos.Item(row.get("id").toString(), row.get("design_item_id").toString(),
                row.get("key").toString(), row.get("kind").toString(), ((Number) row.get("version_no")).intValue(),
                row.get("status").toString(), ((Number) row.get("schema_version")).intValue(),
                object(row.get("content")), source, row.get("content_hash").toString());
    }
}
