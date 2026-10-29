package com.beez.backend.validation.service;

import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.document.service.HybridDocumentCandidateSearch;
import com.beez.backend.task.dto.TaskPlanRevisionResponse;
import com.beez.backend.task.service.RelatedTaskCandidateFinder;
import com.beez.backend.task.service.TaskQueryService;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Builds AI-A02 from authorized, current snapshots. Search hits are candidates, not confirmed links. */
@Service
public class ValidationInputAssembler {
    private static final int MAX_CANDIDATES = 20;
    private static final int MAX_QUERIES = 5;
    private static final int MAX_UNRESOLVED = 100;
    private static final int MAX_UNRESOLVED_TEXT = 2000;
    private static final Pattern API_PATH = Pattern.compile("/(?=[\\p{L}\\p{N}_{])[\\p{L}\\p{N}_{}./-]+");
    private final TaskQueryService tasks;
    private final HybridDocumentCandidateSearch search;
    private final RelatedTaskCandidateFinder related;
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final ValidationEvidenceBuilder evidenceBuilder;

    public ValidationInputAssembler(TaskQueryService tasks, HybridDocumentCandidateSearch search,
                                    RelatedTaskCandidateFinder related, JdbcClient jdbc, ObjectMapper json,
                                    ValidationEvidenceBuilder evidenceBuilder) {
        this.tasks = tasks;
        this.search = search;
        this.related = related;
        this.jdbc = jdbc;
        this.json = json;
        this.evidenceBuilder = evidenceBuilder;
    }

    /** searchComplete means the selected searches ran without known truncation, not exhaustive recall. */
    public record Result(Map<String, Object> request, long baselineId, Map<Long, Long> relatedPlanRevisions,
                         boolean searchComplete, String coverage, List<String> unavailableEvidenceVersionIds) {
        public Result { unavailableEvidenceVersionIds = List.copyOf(unavailableEvidenceVersionIds); }
        public Result(Map<String, Object> request, long baselineId, Map<Long, Long> relatedPlanRevisions,
                      boolean searchComplete, String coverage) {
            this(request, baselineId, relatedPlanRevisions, searchComplete, coverage, List.of());
        }
    }

    public Result assemble(CallerContext caller, long projectId, long taskId, long planRevisionId,
                           long validationId, int attempt, int executionBudgetMs) {
        if (projectId <= 0 || taskId <= 0 || planRevisionId <= 0 || validationId <= 0 || attempt <= 0
                || executionBudgetMs <= 0 || executionBudgetMs > 60_000) {
            throw new IllegalArgumentException("invalid validation execution context");
        }
        var target = tasks.getTask(caller, projectId, taskId).currentPlanRevision();
        requireRevision(target, planRevisionId);
        var selection = queries(target);
        long baselineId = -1;
        boolean searchesComplete = selection.complete();
        Set<Long> itemIds = new LinkedHashSet<>();
        Set<Long> semanticDocumentVersions = new LinkedHashSet<>();
        target.plan().designRefs().forEach(ref -> itemIds.add(Long.parseLong(ref.designItemId())));
        if (itemIds.size() > 50) throw incomplete("design_reference_limit");
        for (String query : selection.queries()) {
            var found = search.search(caller, projectId, query, null, null, MAX_CANDIDATES);
            long foundBaseline = Long.parseLong(found.baselineId());
            if (baselineId != -1 && baselineId != foundBaseline) throw incomplete("baseline_changed");
            baselineId = foundBaseline;
            searchesComplete &= !found.keywordHasMore() && !found.semanticHasMore();
            for (var candidate : found.candidates()) {
                if ("DESIGN_ITEM".equals(candidate.hit().get("type"))) {
                    long itemId = Long.parseLong(candidate.hit().get("id").toString());
                    if (itemIds.size() < 50 || itemIds.contains(itemId)) itemIds.add(itemId);
                    else searchesComplete = false;
                }
                if ("DOCUMENT_CHUNK".equals(candidate.hit().get("type"))) {
                    if (!(candidate.hit().get("source") instanceof Map<?, ?> source)
                            || source.get("document_version_id") == null) {
                        throw incomplete("invalid_search_source");
                    }
                    semanticDocumentVersions.add(Long.parseLong(source.get("document_version_id").toString()));
                }
            }
        }
        var sourceItems = designsFromSourceDocuments(projectId, baselineId, semanticDocumentVersions);
        if (sourceItems.size() > 50) searchesComplete = false;
        for (long itemId : sourceItems) {
            if (itemIds.size() < 50 || itemIds.contains(itemId)) itemIds.add(itemId);
            else searchesComplete = false;
        }
        var designs = approvedItems(projectId, baselineId, itemIds);
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (var design : designs) byId.put(design.get("design_item_id").toString(), design);
        for (var ref : target.plan().designRefs()) {
            var design = byId.get(ref.designItemId());
            if (design == null || !Objects.equals(design.get("id"), ref.designItemVersionId())) {
                throw incomplete("stale_design_reference");
            }
        }
        if (byId.size() != itemIds.size()) throw incomplete("design_candidate_changed");
        var relatives = related.find(caller, projectId, taskId, planRevisionId, new ArrayList<>(itemIds), MAX_CANDIDATES);
        if (relatives.baselineId() != baselineId) throw incomplete("baseline_changed");
        List<Map<String, Object>> relatedPlans = new ArrayList<>();
        Map<Long, Long> relatedSnapshots = new LinkedHashMap<>();
        for (var candidate : relatives.candidates()) {
            var task = tasks.getTask(caller, projectId, candidate.taskId());
            requireRevision(task.currentPlanRevision(), candidate.planRevisionId());
            if ("COMPLETED".equals(task.status().name()) || "CANCELLED".equals(task.status().name())) {
                throw incomplete("related_task_changed");
            }
            relatedSnapshots.put(candidate.taskId(), candidate.planRevisionId());
            relatedPlans.add(asMap(task.currentPlanRevision()));
        }
        requireRevision(tasks.getTask(caller, projectId, taskId).currentPlanRevision(), planRevisionId);
        Set<String> referencedVersions = new LinkedHashSet<>();
        target.plan().designRefs().forEach(ref -> referencedVersions.add(ref.designItemVersionId()));
        List<Map<String, Object>> checks = checks(designs, relatedPlans, referencedVersions);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("project_id", Long.toString(projectId));
        request.put("request_key", "validate-" + validationId + "-attempt-" + attempt);
        request.put("validation_id", Long.toString(validationId));
        request.put("attempt", attempt);
        request.put("plan_revision", asMap(target));
        request.put("approved_items", designs);
        request.put("related_plans", relatedPlans);
        request.put("rule_findings", List.of());
        request.put("unresolved", loadUnresolved(projectId, taskId, baselineId, itemIds, target.plan().contextPacketId()));
        request.put("checks", checks);
        // Only explicit source line locators can be restored as evidence. Search chunks
        // remain candidates and cannot establish a design-to-excerpt relationship.
        var evidence = loadEvidence(projectId, baselineId, designs);
        request.put("evidence", evidence.evidence());
        request.put("execution_budget_ms", executionBudgetMs);
        request.put("input_snapshot_hash", snapshotHash(request, baselineId));
        boolean complete = searchesComplete && !relatives.truncated() && evidence.unavailableVersionIds().isEmpty();
        return new Result(request, baselineId, Map.copyOf(relatedSnapshots), complete, "UNKNOWN",
                evidence.unavailableVersionIds());
    }

    /** Revalidate exact source evidence when the final transaction applies a result. */
    public ValidationEvidenceBuilder.Result loadEvidence(long projectId, long baselineId,
                                                         List<Map<String, Object>> approvedItems) {
        return evidenceBuilder.build(projectId, baselineId, approvedItems);
    }

    record QuerySelection(List<String> queries, boolean complete) {}

    /** Bounded phrases and API paths: text is data and search recall remains unknown. */
    static QuerySelection queries(TaskPlanRevisionResponse plan) {
        List<String> fields = new ArrayList<>();
        fields.add(plan.plan().title());
        fields.add(plan.plan().goal());
        fields.addAll(plan.plan().includedScope());
        fields.add(plan.plan().approach());
        Set<String> queries = new LinkedHashSet<>();
        boolean complete = true;
        String title = plan.plan().title().strip();
        if (title.isEmpty() || title.length() > 1000) throw incomplete("invalid_search_title");
        queries.add(title);
        for (String field : fields) {
            if (field == null || field.isBlank()) continue;
            var paths = API_PATH.matcher(field);
            while (paths.find()) queries.add(paths.group());
        }
        for (String field : fields) {
            if (field == null || field.isBlank()) continue;
            String phrase = field.strip();
            if (phrase.length() > 1000) {
                complete = false;
                continue;
            }
            queries.add(phrase);
        }
        if (queries.size() > MAX_QUERIES) complete = false;
        return new QuerySelection(queries.stream().limit(MAX_QUERIES).toList(), complete);
    }

    private static void requireRevision(TaskPlanRevisionResponse plan, long expected) {
        if (plan == null || !Objects.equals(plan.id(), Long.toString(expected))) throw incomplete("stale_plan");
    }

    private List<Map<String, Object>> approvedItems(long projectId, long baselineId, Set<Long> ids) {
        if (ids.isEmpty()) return List.of();
        var rows = jdbc.sql("""
                select i.id as item_id,i.key,i.kind,v.id as version_id,v.version_no,v.status,
                       v.schema_version,v.content::text as content,v.content_hash,
                       dv.id as source_version_id,d.id as source_document_id,dv.version_no as source_version_no,
                       v.source_locator::text as source_locator
                  from baseline_item bi
                  join design_item i on i.id=bi.design_item_id and i.project_id=bi.project_id
                  join design_item_version v on v.id=bi.design_item_version_id and v.design_item_id=i.id
                  left join document_version dv on dv.id=v.source_document_version_id
                  left join document d on d.id=dv.document_id and d.project_id=bi.project_id
                 where bi.project_id=:project and bi.baseline_id=:baseline and i.id in (:ids)
                 order by i.id
                """).param("project", projectId).param("baseline", baselineId).param("ids", ids)
                .query().listOfRows();
        List<Map<String, Object>> items = new ArrayList<>();
        for (var row : rows) {
            if (!"APPROVED".equals(row.get("status"))) throw incomplete("design_not_approved");
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.get("version_id").toString());
            item.put("design_item_id", row.get("item_id").toString());
            item.put("key", row.get("key"));
            item.put("kind", row.get("kind"));
            item.put("version_no", row.get("version_no"));
            item.put("status", row.get("status"));
            item.put("schema_version", row.get("schema_version"));
            item.put("content", parseObject(row.get("content").toString()));
            item.put("content_hash", row.get("content_hash"));
            if (row.get("source_document_id") != null && row.get("source_locator") != null) {
                var locator = parseObject(row.get("source_locator").toString());
                String location = Objects.toString(locator.get("location"), "");
                if (!location.isBlank()) item.put("source", Map.of(
                        "document_id", row.get("source_document_id").toString(),
                        "document_version_id", row.get("source_version_id").toString(),
                        "version_no", row.get("source_version_no"), "location", location));
            }
            items.add(item);
        }
        return items;
    }

    /** Same approved source document means a search candidate, not a confirmed semantic relation. */
    private List<Long> designsFromSourceDocuments(long projectId, long baselineId, Set<Long> versionIds) {
        if (versionIds.isEmpty()) return List.of();
        var rows = jdbc.sql("""
                select distinct bi.design_item_id
                  from baseline_item bi
                  join design_item_version v on v.id=bi.design_item_version_id and v.design_item_id=bi.design_item_id
                  join baseline_document bd on bd.baseline_id=bi.baseline_id and bd.project_id=bi.project_id
                       and bd.document_version_id=v.source_document_version_id
                 where bi.project_id=:project and bi.baseline_id=:baseline and v.status='APPROVED'
                   and bd.document_version_id in (:ids)
                 order by bi.design_item_id limit 51
                """).param("project", projectId).param("baseline", baselineId).param("ids", versionIds)
                .query().listOfRows();
        return rows.stream().map(row -> ((Number) row.get("design_item_id")).longValue()).toList();
    }

    /**
     * Conservatively include unresolved project questions, selected design questions and
     * questions on current-baseline documents. These are open questions, not proof that a
     * policy is absent. Read again before applying a result because statuses may change.
     */
    public List<String> loadUnresolved(long projectId, long taskId, long baselineId,
                                       Set<Long> itemIds, String contextPacketId) {
        if (projectId <= 0 || taskId <= 0 || baselineId <= 0 || itemIds == null
                || itemIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("invalid unresolved scope");
        }
        var ids = itemIds.isEmpty() ? List.of(-1L) : new ArrayList<>(itemIds);
        var rows = jdbc.sql("""
                select q.id,q.question from planning_question q
                 where q.project_id=:project and q.status='OPEN'
                   and ((q.design_item_id is null and q.document_version_id is null)
                     or q.design_item_id in (:ids)
                     or q.document_version_id in
                        (select bd.document_version_id from baseline_document bd
                          where bd.project_id=:project and bd.baseline_id=:baseline)
                     or q.document_version_id in
                        (select v.source_document_version_id from baseline_item bi
                          join design_item_version v on v.id=bi.design_item_version_id
                            and v.design_item_id=bi.design_item_id
                          where bi.project_id=:project and bi.baseline_id=:baseline
                            and bi.design_item_id in (:ids)))
                 order by q.id limit 101
                """).param("project", projectId).param("ids", ids).param("baseline", baselineId)
                .query().listOfRows();
        if (rows.size() > MAX_UNRESOLVED) throw incomplete("unresolved_limit");
        List<String> unresolved = new ArrayList<>();
        for (var row : rows) {
            if (!(row.get("id") instanceof Number id) || !(row.get("question") instanceof String question)
                    || question.isBlank() || question.length() > MAX_UNRESOLVED_TEXT) {
                throw incomplete("invalid_unresolved_question");
            }
            unresolved.add("planning_question:" + id.longValue() + " " + question);
        }
        if (contextPacketId != null) {
            final long packetId;
            try { packetId = Long.parseLong(contextPacketId); }
            catch (RuntimeException invalid) { throw incomplete("invalid_context_packet"); }
            if (packetId <= 0) throw incomplete("invalid_context_packet");
            var packets = jdbc.sql("""
                    select baseline_id,content::text as content from dev_context_packet
                     where id=:packet and project_id=:project and (task_id is null or task_id=:task)
                    """).param("packet", packetId).param("project", projectId).param("task", taskId)
                    .query().listOfRows();
            if (packets.size() != 1 || !(packets.getFirst().get("baseline_id") instanceof Number packetBaseline)
                    || packets.getFirst().get("content") == null) throw incomplete("invalid_context_packet");
            Map<String, Object> packet = parseObject(packets.getFirst().get("content").toString());
            if (!(packet.get("unresolved") instanceof List<?> entries)) throw incomplete("invalid_context_packet");
            if (entries.size() > MAX_UNRESOLVED || unresolved.size() + entries.size() > MAX_UNRESOLVED) {
                throw incomplete("unresolved_limit");
            }
            if (packetBaseline.longValue() != baselineId) {
                unresolved.add("context_packet:" + packetId + "의 기준 설계가 현재 기준과 달라 재확인이 필요합니다.");
            }
            for (Object entry : entries) {
                if (!(entry instanceof String question) || question.isBlank() || question.length() > MAX_UNRESOLVED_TEXT) {
                    throw incomplete("invalid_context_packet");
                }
                unresolved.add("context_packet:" + packetId + " " + question);
            }
            if (unresolved.size() > MAX_UNRESOLVED) throw incomplete("unresolved_limit");
        }
        return List.copyOf(unresolved);
    }

    static List<Map<String, Object>> checks(List<Map<String, Object>> designs, List<Map<String, Object>> plans,
                                            Set<String> referencedVersions) {
        List<Map<String, Object>> result = new ArrayList<>();
        result.add(check("plan-completeness", "PLAN_COMPLETENESS", List.of(), List.of()));
        for (var design : designs) {
            String id = design.get("id").toString();
            String prefix = referencedVersions.contains(id) ? "referenced-design-" : "candidate-design-";
            result.add(check(prefix + id, "DESIGN_ALIGNMENT", List.of(id), List.of()));
        }
        for (var plan : plans) {
            String id = plan.get("id").toString();
            result.add(check("related-" + id, "RELATED_PLAN_CONFLICT", List.of(), List.of(id)));
        }
        return result;
    }

    private static Map<String, Object> check(String key, String kind, List<String> itemIds, List<String> planIds) {
        return Map.of("key", key, "kind", kind, "design_item_version_ids", itemIds,
                "related_plan_revision_ids", planIds);
    }

    private Map<String, Object> asMap(Object obj) {
        return json.convertValue(obj, new TypeReference<Map<String, Object>>() {});
    }

    private Map<String, Object> parseObject(String value) {
        try { return json.readValue(value, new TypeReference<Map<String, Object>>() {}); }
        catch (Exception e) { throw new IllegalStateException("invalid stored JSON", e); }
    }

    private String snapshotHash(Map<String, Object> value, long baselineId) {
        try {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("baseline_id", Long.toString(baselineId));
            for (String key : List.of("project_id", "plan_revision", "approved_items", "related_plans",
                    "rule_findings", "unresolved", "checks", "evidence")) snapshot.put(key, value.get(key));
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(stable(snapshot)).getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        catch (Exception e) { throw new IllegalStateException("cannot hash validation input", e); }
    }

    private static Object stable(Object value) {
        if (value instanceof Map<?, ?> map) {
            var sorted = new TreeMap<String, Object>();
            map.forEach((key, item) -> sorted.put(Objects.toString(key), stable(item)));
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(ValidationInputAssembler::stable).toList();
        return value;
    }

    private static ApiException incomplete(String code) {
        return new ApiException(HttpStatus.CONFLICT, code, "검증 입력이 변경되었거나 검색 범위를 초과했습니다");
    }
}
