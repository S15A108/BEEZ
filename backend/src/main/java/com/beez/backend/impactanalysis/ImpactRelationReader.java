package com.beez.backend.impactanalysis;

import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.relation.dto.DesignDependencyResponse;
import com.beez.backend.relation.service.DesignDependencyQueryService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Reads #167 confirmed relations and baseline item versions, never creating relation events. */
@Service
public class ImpactRelationReader {
    private final ProjectAccessChecker access;
    private final DesignDependencyQueryService relations;
    private final JdbcClient jdbc;

    public ImpactRelationReader(ProjectAccessChecker access, DesignDependencyQueryService relations, JdbcClient jdbc) {
        this.access = access;
        this.relations = relations;
        this.jdbc = jdbc;
    }

    public record ConfirmedRelation(long baselineId, long fromItemId, long fromVersionId,
                                    long toItemId, long toVersionId, String relation, String origin,
                                    String reason, String confirmedBy, Instant confirmedAt) {}

    public record Result(long relationSetRevision, List<ConfirmedRelation> relations) {
        public Result { relations = List.copyOf(relations); }
    }

    /**
     * Read only reverse, one-edge CONFIRMED dependencies of changed items. A missing endpoint
     * on the current baseline is an inconsistent snapshot, never an empty/no-impact result.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Result load(CallerContext caller, long projectId, long baselineId, Set<Long> changedItems) {
        access.requireMember(caller, projectId);
        long matching = jdbc.sql("""
                select count(*) from project
                 where id=:project and archived_at is null and current_baseline_id=:baseline
                """).param("project", projectId).param("baseline", baselineId).query(Long.class).single();
        if (matching != 1) throw stale("baseline_changed");

        long revision = relations.currentRevision(projectId);
        Map<String, DesignDependencyResponse> selected = new LinkedHashMap<>();
        for (Long changed : new java.util.TreeSet<>(changedItems)) {
            var response = relations.dependencies(caller, projectId, changed);
            if (response.relationSetRevision() != revision) throw stale("analysis_input_changed");
            for (var dependency : response.items()) {
                if (!"CONFIRMED".equals(dependency.status())
                        || !Long.toString(changed).equals(dependency.toDesignItemId())) continue;
                String key = dependency.fromDesignItemId() + ":" + dependency.toDesignItemId();
                selected.putIfAbsent(key, dependency);
            }
        }
        if (selected.isEmpty()) return new Result(revision, List.of());

        Set<Long> endpoints = new LinkedHashSet<>();
        for (var relation : selected.values()) {
            endpoints.add(Long.parseLong(relation.fromDesignItemId()));
            endpoints.add(Long.parseLong(relation.toDesignItemId()));
        }
        var rows = jdbc.sql("""
                select design_item_id,design_item_version_id from baseline_item
                 where project_id=:project and baseline_id=:baseline and design_item_id in (:ids)
                """).param("project", projectId).param("baseline", baselineId)
                .param("ids", endpoints).query().listOfRows();
        Map<Long, Long> versions = new LinkedHashMap<>();
        for (var row : rows) {
            versions.put(((Number) row.get("design_item_id")).longValue(),
                    ((Number) row.get("design_item_version_id")).longValue());
        }
        if (!versions.keySet().containsAll(endpoints)) throw stale("baseline_inconsistent");
        List<ConfirmedRelation> output = new ArrayList<>();
        for (var relation : selected.values()) {
            long from = Long.parseLong(relation.fromDesignItemId());
            long to = Long.parseLong(relation.toDesignItemId());
            output.add(new ConfirmedRelation(baselineId, from, versions.get(from), to, versions.get(to),
                    relation.relation(), relation.origin(), relation.reason(),
                    relation.confirmedBy(), relation.confirmedAt()));
        }
        output.sort(Comparator.comparingLong(ConfirmedRelation::fromItemId)
                .thenComparingLong(ConfirmedRelation::toItemId));
        return new Result(revision, output);
    }

    private static ApiException stale(String code) {
        return new ApiException(HttpStatus.CONFLICT, code, "분석 기준과 관계를 다시 확인해야 합니다");
    }
}
