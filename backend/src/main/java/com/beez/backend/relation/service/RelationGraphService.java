package com.beez.backend.relation.service;

import com.beez.backend.common.access.AccessErrors;
import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.api.Ids;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.relation.dto.RelationEdgeResponse;
import com.beez.backend.relation.dto.RelationGraphResponse;
import com.beez.backend.relation.dto.RelationNodeResponse;
import com.beez.backend.relation.repository.DependencyRow;
import com.beez.backend.relation.repository.RelationGraphRepository;
import com.beez.backend.relation.repository.RelationRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * MON-A03 작업·설계·검증 관계 (S15P31A108-167). 기준 객체에서 한 단계 관계를 모아 노드·간선으로 준다.
 *
 * <p>기준이 작업이면
 * <ul>
 *   <li>작업 → 설계 항목: 현재 계획 리비전의 참조 (READS·IMPLEMENTS·CHANGES, 계획이 본 버전)</li>
 *   <li>작업 ↔ 작업: 선후 관계 (BLOCKS·RELATES, 앞 작업이 뒤 작업에 의존)</li>
 *   <li>작업 → 검증 (VALIDATED_BY): 현재 계획 리비전의 가장 최근 검증. 그 검증 → 설계 항목(검증이 본 버전), 검증 → 대조한 작업(COMPARED_WITH)</li>
 *   <li>변경 제안 → 작업 (PROPOSED_FROM): 이 작업에서 나온 변경 제안</li>
 * </ul>
 * <p>기준이 설계 항목이면
 * <ul>
 *   <li>설계 항목 → 설계 항목 (DEPENDS_ON): 확정·재확인 필요·후보. confirmed 는 확정일 때만 true, status 로 구분</li>
 *   <li>작업 → 설계 항목: 현재 계획이 이 항목을 참조하는 작업</li>
 *   <li>변경 제안 → 설계 항목 (UPDATE·RETIRE): 진행 중 변경 제안. 새 항목을 만드는 CREATE 는 아직 항목 번호가 없어 나오지 않는다</li>
 * </ul>
 * 둘 다 주면 두 결과를 합친다. 같은 노드·간선은 한 번만 넣는다.
 * 과거 관계는 당시 계획·검증이 본 항목 버전(design_item_version_id)으로 연결한다. 관계 변경 이력은 TRC-A02 에서 본다.
 */
@Service
public class RelationGraphService {

    static final String TASK = "TASK";
    static final String DESIGN_ITEM = "DESIGN_ITEM";
    static final String VALIDATION = "VALIDATION";
    static final String CHANGE_PROPOSAL = "CHANGE_PROPOSAL";

    private final ProjectAccessChecker access;
    private final RelationGraphRepository graph;
    private final RelationRepository relations;

    public RelationGraphService(ProjectAccessChecker access, RelationGraphRepository graph, RelationRepository relations) {
        this.access = access;
        this.graph = graph;
        this.relations = relations;
    }

    @Transactional(readOnly = true)
    public RelationGraphResponse relations(CallerContext caller, long projectId, Long taskId, Long designItemId) {
        access.requireMember(caller, projectId);
        if (taskId == null && designItemId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", "task_id 또는 design_item_id 중 하나는 필요합니다");
        }
        Graph g = new Graph();
        if (taskId != null) {
            aroundTask(projectId, taskId, g);
        }
        if (designItemId != null) {
            aroundItem(projectId, designItemId, g);
        }
        return g.toResponse(graph, projectId);
    }

    private void aroundTask(long projectId, long taskId, Graph g) {
        Map<String, Object> task = graph.task(projectId, taskId)
                .orElseThrow(() -> AccessErrors.notFound("relation_task_missing project=" + projectId + " task=" + taskId));
        g.node(TASK, taskId);

        Long revision = id(task.get("current_plan_revision_id"));
        if (revision != null) {
            for (Map<String, Object> ref : graph.planRefs(projectId, revision)) {
                long item = id(ref.get("design_item_id"));
                g.node(DESIGN_ITEM, item);
                g.edge(TASK, taskId, DESIGN_ITEM, item, (String) ref.get("relation"), true, null,
                        id(ref.get("design_item_version_id")));
            }
            Optional<Map<String, Object>> validation = graph.latestValidation(projectId, taskId, revision);
            if (validation.isPresent()) {
                long validationId = id(validation.get().get("id"));
                g.validationNode(validationId, (String) validation.get().get("status"));
                g.edge(TASK, taskId, VALIDATION, validationId, "VALIDATED_BY", true, null, null);
                for (Map<String, Object> ref : graph.validationDesignRefs(projectId, validationId)) {
                    long item = id(ref.get("design_item_id"));
                    g.node(DESIGN_ITEM, item);
                    g.edge(VALIDATION, validationId, DESIGN_ITEM, item, (String) ref.get("relation"), true, null,
                            id(ref.get("design_item_version_id")));
                }
                for (Map<String, Object> ref : graph.validationTaskRefs(projectId, validationId)) {
                    long related = id(ref.get("related_task_id"));
                    g.node(TASK, related);
                    g.edge(VALIDATION, validationId, TASK, related, "COMPARED_WITH", true, null, null);
                }
            }
        }
        for (Map<String, Object> dep : graph.taskDependencies(projectId, taskId)) {
            long from = id(dep.get("task_id"));
            long to = id(dep.get("depends_on_task_id"));
            g.node(TASK, from);
            g.node(TASK, to);
            g.edge(TASK, from, TASK, to, (String) dep.get("kind"), true, null, null);
        }
        for (Map<String, Object> proposal : graph.proposalsOfTask(projectId, taskId)) {
            long proposalId = id(proposal.get("id"));
            g.proposalNode(proposalId, (String) proposal.get("key"));
            g.edge(CHANGE_PROPOSAL, proposalId, TASK, taskId, "PROPOSED_FROM", true, null, null);
        }
    }

    private void aroundItem(long projectId, long itemId, Graph g) {
        if (!relations.itemInProject(itemId, projectId)) {
            throw AccessErrors.notFound("relation_item_missing project=" + projectId + " item=" + itemId);
        }
        g.node(DESIGN_ITEM, itemId);
        for (DependencyRow row : relations.dependenciesOf(projectId, itemId, List.of(RelationStorageService.CONFIRMED,
                RelationStorageService.NEEDS_RECHECK, RelationStorageService.CANDIDATE))) {
            g.node(DESIGN_ITEM, row.fromItemId());
            g.node(DESIGN_ITEM, row.toItemId());
            boolean confirmed = RelationStorageService.CONFIRMED.equals(row.status());
            g.edge(DESIGN_ITEM, row.fromItemId(), DESIGN_ITEM, row.toItemId(), DesignDependencyQueryService.DEPENDS_ON,
                    confirmed, row.status(), null);
        }
        for (Map<String, Object> ref : graph.tasksReferencing(projectId, itemId)) {
            long task = id(ref.get("id"));
            g.node(TASK, task);
            g.edge(TASK, task, DESIGN_ITEM, itemId, (String) ref.get("relation"), true, null,
                    id(ref.get("design_item_version_id")));
        }
        for (Map<String, Object> proposal : graph.openProposalsOfItem(projectId, itemId)) {
            long proposalId = id(proposal.get("id"));
            g.proposalNode(proposalId, (String) proposal.get("key"));
            g.edge(CHANGE_PROPOSAL, proposalId, DESIGN_ITEM, itemId, (String) proposal.get("op"), true, null, null);
        }
    }

    /** DB 숫자 칸(Long·Integer 등) → Long. NULL 은 null */
    private static Long id(Object value) {
        if (value == null) {
            return null;
        }
        return ((Number) value).longValue();
    }

    /** 노드·간선을 중복 없이 모은다. 라벨은 마지막에 종류별로 한 번에 읽는다 */
    private static final class Graph {
        private final Map<String, RelationNodeResponse> nodes = new LinkedHashMap<>();
        private final Set<Long> taskIds = new LinkedHashSet<>();
        private final Set<Long> itemIds = new LinkedHashSet<>();
        private final Map<String, RelationEdgeResponse> edges = new LinkedHashMap<>();

        void node(String type, long id) {
            String key = type + ":" + id;
            if (!nodes.containsKey(key)) {
                nodes.put(key, null);
                if (TASK.equals(type)) {
                    taskIds.add(id);
                } else {
                    itemIds.add(id);
                }
            }
        }

        void validationNode(long id, String status) {
            nodes.put(VALIDATION + ":" + id, new RelationNodeResponse(Ids.str(id), VALIDATION, "검증 " + id + " (" + status + ")"));
        }

        void proposalNode(long id, String key) {
            nodes.put(CHANGE_PROPOSAL + ":" + id, new RelationNodeResponse(Ids.str(id), CHANGE_PROPOSAL, key));
        }

        void edge(String fromType, long fromId, String toType, long toId, String relation, boolean confirmed,
                  String status, Long versionId) {
            String key = fromType + ":" + fromId + ">" + toType + ":" + toId + ":" + relation;
            if (!edges.containsKey(key)) {
                edges.put(key, new RelationEdgeResponse(Ids.str(fromId), fromType, Ids.str(toId), toType, relation,
                        confirmed, status, Ids.str(versionId)));
            }
        }

        /**
         * 라벨을 읽어 노드를 완성한다. 이 프로젝트에서 찾지 못한 작업·항목(다른 프로젝트 번호가 섞인 경우)은
         * 노드와 그 노드에 닿는 간선을 모두 뺀다 — 다른 프로젝트 객체를 번호로도 노출하지 않는다.
         */
        RelationGraphResponse toResponse(RelationGraphRepository graph, long projectId) {
            Map<Long, String> taskLabels = graph.taskLabels(projectId, taskIds);
            Map<Long, String> itemLabels = graph.itemLabels(projectId, itemIds);
            List<RelationNodeResponse> outNodes = new ArrayList<>();
            Set<String> dropped = new LinkedHashSet<>();
            for (Map.Entry<String, RelationNodeResponse> e : nodes.entrySet()) {
                if (e.getValue() != null) {
                    outNodes.add(e.getValue());
                    continue;
                }
                String[] parts = e.getKey().split(":");
                long id = Long.parseLong(parts[1]);
                Map<Long, String> labels = itemLabels;
                if (TASK.equals(parts[0])) {
                    labels = taskLabels;
                }
                if (labels.containsKey(id)) {
                    outNodes.add(new RelationNodeResponse(Ids.str(id), parts[0], labels.get(id)));
                } else {
                    dropped.add(e.getKey());
                }
            }
            List<RelationEdgeResponse> outEdges = new ArrayList<>();
            for (RelationEdgeResponse edge : edges.values()) {
                boolean fromDropped = dropped.contains(edge.fromType() + ":" + edge.fromId());
                boolean toDropped = dropped.contains(edge.toType() + ":" + edge.toId());
                if (!fromDropped && !toDropped) {
                    outEdges.add(edge);
                }
            }
            return new RelationGraphResponse(outNodes, outEdges);
        }
    }
}
