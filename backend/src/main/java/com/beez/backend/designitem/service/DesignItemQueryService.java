package com.beez.backend.designitem.service;

import static com.beez.backend.designitem.support.DesignJson.canonical;
import static com.beez.backend.designitem.support.DesignJson.conflict;
import static com.beez.backend.designitem.support.DesignJson.hash;
import static com.beez.backend.designitem.support.DesignJson.invalid;
import static com.beez.backend.designitem.support.DesignJson.object;

import com.beez.backend.common.access.AccessErrors;
import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.designitem.dto.DesignItemPageResponse;
import com.beez.backend.designitem.dto.DesignItemResponse;
import com.beez.backend.designitem.dto.DesignItemSourceResponse;
import com.beez.backend.designitem.repository.DesignItemRepository;
import com.beez.backend.designitem.repository.DesignProjectRepository;
import com.beez.backend.designitem.validation.DesignContentValidator;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DesignItemQueryService {
    private final DesignItemRepository items;
    private final ProjectAccessChecker access;
    private final DesignSnapshotService snapshots;
    private final DesignProjectRepository projects;

    public DesignItemQueryService(DesignItemRepository items, ProjectAccessChecker access, DesignSnapshotService snapshots,
                                 DesignProjectRepository projects) {
        this.items = items;
        this.access = access;
        this.snapshots = snapshots;
        this.projects = projects;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public DesignItemPageResponse list(CallerContext caller, long project, String status, String kind,
                                    Long source, String cursor, int limit) {
        access.requireMember(caller, project);
        if (!Set.of("DRAFT", "APPROVED", "SUPERSEDED", "REJECTED").contains(status)
                || (kind != null && !DesignContentValidator.KINDS.contains(kind)) || limit < 1 || limit > 100) {
            throw invalid("목록 필터가 올바르지 않습니다.");
        }
        // A list filter does not establish ownership: the project-scoped query returns no foreign rows.
        // Creation still uses requireSource to reject an invalid source reference.
        if (source != null && source < 1) {
            throw invalid("document_version_id는 양의 정수 ID여야 합니다.");
        }
        boolean approved = "APPROVED".equals(status);
        Long baseline = approved && cursor == null ? projects.currentBaselineId(project) : null;
        String snapshot = approved ? null : snapshots.hash(project);
        String selection = hash(Arrays.asList(project, status, kind, source));
        long after = 0;
        if (cursor != null) {
            String[] parts;
            try {
                if (cursor.length() > 512) {
                    throw new IllegalArgumentException();
                }
                parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|", -1);
                if (parts.length != 3 || (after = Long.parseLong(parts[0])) < 1 || !parts[2].equals(selection)) {
                    throw new IllegalArgumentException();
                }
                if (approved) {
                    if (!parts[1].startsWith("baseline:")) {
                        throw new IllegalArgumentException();
                    }
                    baseline = Long.parseLong(parts[1].substring("baseline:".length()));
                    if (baseline < 1) {
                        throw new IllegalArgumentException();
                    }
                }
            } catch (IllegalArgumentException e) {
                throw invalid("목록 cursor가 올바르지 않습니다.");
            }
            if (approved && !projects.baselineBelongsToProject(project, baseline)) {
                throw invalid("목록 cursor가 올바르지 않습니다.");
            }
            if (!approved && !parts[1].equals(snapshot)) {
                throw conflict("draft_changed");
            }
        }
        var rows = items.findPage(project, status, kind, source, after, limit + 1, baseline);
        boolean more = rows.size() > limit;
        var page = more ? rows.subList(0, limit) : rows;
        String pageToken = approved ? "baseline:" + baseline : snapshot;
        String next = more ? Base64.getUrlEncoder().withoutPadding().encodeToString(
                (page.getLast().get("id") + "|" + pageToken + "|" + selection).getBytes(StandardCharsets.UTF_8)) : null;
        return new DesignItemPageResponse(page.stream().map(this::view).toList(), more, next,
                "DRAFT".equals(status) ? snapshot : null);
    }

    void requireSource(long project, long source) {
        if (source < 1 || !items.sourceBelongsToProject(project, source)) {
            throw AccessErrors.notFound("design_source_not_in_project");
        }
    }

    DesignItemResponse get(long project, long item, long version) {
        var row = items.findVersion(project, item, version)
                .orElseThrow(() -> AccessErrors.notFound("design_item_not_in_project"));
        return view(row);
    }

    private DesignItemResponse view(Map<String, Object> row) {
        DesignItemSourceResponse source = null;
        if (row.get("source_document_version_id") != null) {
            var locator = row.get("source_locator") == null ? Map.<String, Object>of() : object(row.get("source_locator"));
            source = new DesignItemSourceResponse(row.get("source_document_id").toString(),
                    row.get("source_document_version_id").toString(), ((Number) row.get("source_version_no")).intValue(),
                    Objects.toString(locator.get("location"), locator.isEmpty() ? "" : canonical(locator)));
        }
        return new DesignItemResponse(row.get("id").toString(), row.get("design_item_id").toString(),
                row.get("key").toString(), row.get("kind").toString(), ((Number) row.get("version_no")).intValue(),
                row.get("status").toString(), ((Number) row.get("schema_version")).intValue(),
                object(row.get("content")), source, row.get("content_hash").toString());
    }
}
