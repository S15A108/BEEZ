package com.beez.backend.relation.api;

import com.beez.backend.common.api.Ids;
import com.beez.backend.common.api.PageResponse;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.relation.dto.DependencyHistoryResponse;
import com.beez.backend.relation.dto.DesignDependenciesResponse;
import com.beez.backend.relation.dto.RelationGraphResponse;
import com.beez.backend.relation.service.DesignDependencyQueryService;
import com.beez.backend.relation.service.RelationGraphService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 관계 조회 API (S15P31A108-167). 조회만 한다 — 관계 변경은 발행·변경 승인 트랜잭션에서 관계 저장 서비스로 한다 */
@RestController
@Tag(name = "Relations", description = "설계 항목 의존관계·이력과 작업·설계·검증 관계 조회")
public class RelationController {

    private final DesignDependencyQueryService dependencies;
    private final RelationGraphService graph;

    public RelationController(DesignDependencyQueryService dependencies, RelationGraphService graph) {
        this.dependencies = dependencies;
        this.graph = graph;
    }

    @GetMapping("/api/projects/{projectId}/design-items/{itemId}/dependencies")
    @Operation(summary = "TRC-A01 설계 항목 의존관계 조회")
    public DesignDependenciesResponse dependencies(CallerContext caller, @PathVariable long projectId,
                                                   @PathVariable long itemId) {
        return dependencies.dependencies(caller, projectId, itemId);
    }

    @GetMapping("/api/projects/{projectId}/design-items/{itemId}/dependencies/history")
    @Operation(summary = "TRC-A02 설계 항목 의존관계 이력 조회")
    public PageResponse<DependencyHistoryResponse> history(CallerContext caller, @PathVariable long projectId,
                                                           @PathVariable long itemId,
                                                           @RequestParam(required = false) String cursor,
                                                           @RequestParam(required = false) Integer limit) {
        return dependencies.history(caller, projectId, itemId, cursor, limit);
    }

    @GetMapping("/api/projects/{projectId}/relations")
    @Operation(summary = "MON-A03 작업·설계·검증 관계")
    public RelationGraphResponse relations(CallerContext caller, @PathVariable long projectId,
                                           @RequestParam(name = "task_id", required = false) String taskId,
                                           @RequestParam(name = "design_item_id", required = false) String designItemId) {
        // Ids.parse: 십진 문자열 ID → 숫자. 형식이 틀리면 400 validation_failed
        return graph.relations(caller, projectId, Ids.parse(taskId, "task_id"), Ids.parse(designItemId, "design_item_id"));
    }
}
