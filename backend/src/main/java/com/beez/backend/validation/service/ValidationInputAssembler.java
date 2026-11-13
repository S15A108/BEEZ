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
    // A single snapshot avoids repeated embedding calls across pages. The API permits at
    // most 100; more KEYWORD matches still block completion until stable pagination is supported.
    // SEMANTIC search ranks every indexed chunk, so its has_more only says the project has more
    // than 100 chunks, not that a match was cut off (S15P31A108-410).
    private static final int MAX_SEARCH_RESULTS = 100;
    // Bounds the number of search calls (each SEMANTIC call embeds its query); it is a cost
    // budget, not an evidence guarantee. Phrases beyond it are merged into the last query, and the
    // merged query only adds SEMANTIC recall: KEYWORD substring matching will not find it.
    private static final int MAX_QUERIES = 10;
    private static final int MAX_QUERY_LENGTH = 1000;
    private static final int MAX_UNRESOLVED = 100;
    private static final int MAX_UNRESOLVED_TEXT = 2000;
    private static final String PACKET_SELECTION_TRUNCATED_PREFIX = "SELECTION_TRUNCATED: ";
    // AI-A02 allows 200 KB; reserve space for JSON formatting and the snapshot hash.
    private static final int MAX_SEARCH_CONTEXT_REQUEST_BYTES = 180_000;
    // A slash right after Hangul ("소프트/하드") is prose, not an API path. Paths after a host or
    // port ("localhost:8080/api") still count.
    private static final Pattern API_PATH = Pattern.compile("(?<!\\p{IsHangul})/(?=[\\p{L}\\p{N}_{])[\\p{L}\\p{N}_{}./-]+");
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
                         boolean searchComplete, String coverage, List<String> unavailableEvidenceVersionIds,
                         List<String> searchIncompleteReasons) {
        public Result {
            unavailableEvidenceVersionIds = List.copyOf(unavailableEvidenceVersionIds);
            searchIncompleteReasons = List.copyOf(searchIncompleteReasons);
        }
        public Result(Map<String, Object> request, long baselineId, Map<Long, Long> relatedPlanRevisions,
                      boolean searchComplete, String coverage) {
            this(request, baselineId, relatedPlanRevisions, searchComplete, coverage, List.of(), List.of());
        }
        public Result(Map<String, Object> request, long baselineId, Map<Long, Long> relatedPlanRevisions,
                      boolean searchComplete, String coverage, List<String> unavailableEvidenceVersionIds) {
            this(request, baselineId, relatedPlanRevisions, searchComplete, coverage, unavailableEvidenceVersionIds, List.of());
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
        Set<String> incompleteReasons = new LinkedHashSet<>();
        long baselineId = -1;
        boolean searchesComplete = true;
        Set<Long> itemIds = new LinkedHashSet<>();
        Set<Long> documentVersions = new LinkedHashSet<>();
        Map<String, ValidationEvidenceBuilder.SearchHit> documentHits = new LinkedHashMap<>();
        target.plan().designRefs().forEach(ref -> itemIds.add(Long.parseLong(ref.designItemId())));
        if (itemIds.size() > 50) throw incomplete("design_reference_limit");
        for (String query : selection.queries()) {
            var found = search.search(caller, projectId, query, null, null, MAX_SEARCH_RESULTS);
            long foundBaseline = Long.parseLong(found.baselineId());
            if (baselineId != -1 && baselineId != foundBaseline) throw incomplete("baseline_changed");
            baselineId = foundBaseline;
            // Only KEYWORD has_more means matching hits were dropped. SEMANTIC is a ranked top-k over all
            // chunks: counting it failed every validation in a project with more than 100 chunks.
            if (found.keywordHasMore()) {
                searchesComplete = false;
                incompleteReasons.add("search_results_truncated");
            }
            for (var candidate : found.candidates()) {
                if ("DESIGN_ITEM".equals(candidate.hit().get("type"))) {
                    long itemId = Long.parseLong(candidate.hit().get("id").toString());
                    if (itemIds.size() < 50 || itemIds.contains(itemId)) itemIds.add(itemId);
                    else { searchesComplete = false; incompleteReasons.add("design_candidate_limit"); }
                }
                if (Set.of("DOCUMENT", "DOCUMENT_CHUNK").contains(candidate.hit().get("type"))) {
                    if (!(candidate.hit().get("source") instanceof Map<?, ?> source)
                            || source.get("document_version_id") == null) {
                        throw incomplete("invalid_search_source");
                    }
                    long versionId = Long.parseLong(source.get("document_version_id").toString());
                    long hitId = Long.parseLong(candidate.hit().get("id").toString());
                    String type = candidate.hit().get("type").toString();
                    documentVersions.add(versionId);
                    documentHits.putIfAbsent(type + ":" + hitId,
                            new ValidationEvidenceBuilder.SearchHit(type, hitId, versionId, query));
                }
            }
        }
        Set<Long> mappedDocuments = mappedDocuments(projectId, baselineId, documentVersions);
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
        var context = loadContext(projectId, taskId, baselineId, itemIds, target.plan().contextPacketId());
        request.put("unresolved", context.unresolved());
        if (target.plan().contextPacketId() != null) {
            request.put("context_packet_constraints", context.constraints());
        }
        request.put("checks", checks);
        // Search documents without a design mapping are context evidence, never design references.
        var evidence = loadEvidence(projectId, baselineId, designs);
        var allEvidence = new ArrayList<>(evidence.evidence());
        var unmappedHits = documentHits.values().stream()
                .filter(hit -> !mappedDocuments.contains(hit.documentVersionId())).toList();
        allEvidence.addAll(evidenceBuilder.buildSearchEvidence(projectId, baselineId, unmappedHits));
        request.put("evidence", List.copyOf(allEvidence));
        request.put("execution_budget_ms", executionBudgetMs);
        limitSearchContext(request);
        request.put("input_snapshot_hash", snapshotHash(request, baselineId));
        boolean complete = searchesComplete && !relatives.truncated() && evidence.unavailableVersionIds().isEmpty();
        if (relatives.truncated()) incompleteReasons.add("related_task_limit");
        // A moved document whose cited passage changed (NEEDS_CONFIRMATION) is reported separately from a source that is
        // simply not recoverable, so the reader knows the location needs a person's confirmation (S15P31A108-406).
        if (evidence.unavailableVersionIds().stream().anyMatch(id -> !evidence.needsConfirmationVersionIds().contains(id))) {
            incompleteReasons.add("source_evidence_unavailable");
        }
        if (!evidence.needsConfirmationVersionIds().isEmpty()) incompleteReasons.add("source_confirmation_required");
        return new Result(request, baselineId, Map.copyOf(relatedSnapshots), complete, "UNKNOWN",
                evidence.unavailableVersionIds(), List.copyOf(incompleteReasons));
    }

    /** Bind the accepted immutable completion report and its required check into the AI snapshot. */
    public void bindCompletionDelta(Result input, List<Map<String, Object>> delta) {
        if (delta == null || delta.isEmpty()) throw new IllegalArgumentException("missing completion delta");
        var request = input.request();
        @SuppressWarnings("unchecked")
        var checks = new ArrayList<>((List<Map<String, Object>>) request.get("checks"));
        @SuppressWarnings("unchecked")
        var items = (List<Map<String, Object>>) request.get("approved_items");
        List<String> designVersions = items.stream().map(item -> (String) item.get("id")).toList();
        checks.add(check(ValidationRules.COMPLETION_DELTA_CHECK, "PLAN_COMPLETENESS", designVersions, List.of()));
        request.put("checks", checks);
        request.put("completion_delta", delta);
        limitSearchContext(request);
        request.put("input_snapshot_hash", snapshotHash(request, input.baselineId()));
    }

    /** Bind the pinned policy contract after candidate search, without creating design links. */
    public void bindPolicyAnalysis(Result input) {
        var request = input.request();
        @SuppressWarnings("unchecked")
        var checks = new ArrayList<>((List<Map<String, Object>>) request.get("checks"));
        @SuppressWarnings("unchecked")
        var items = (List<Map<String, Object>>) request.get("approved_items");
        checks.add(check(ValidationRules.POLICY_CHECK, "POLICY_ALIGNMENT",
                items.stream().map(item -> (String) item.get("id")).toList(), List.of()));
        request.put("checks", checks);
        limitSearchContext(request);
        request.put("input_snapshot_hash", snapshotHash(request, input.baselineId()));
    }

    /** Revalidate exact source evidence when the final transaction applies a result. */
    public ValidationEvidenceBuilder.Result loadEvidence(long projectId, long baselineId,
                                                         List<Map<String, Object>> approvedItems) {
        return evidenceBuilder.build(projectId, baselineId, approvedItems);
    }

    public boolean searchEvidenceCurrent(long projectId, long baselineId, List<Map<String, Object>> evidence) {
        return evidenceBuilder.searchEvidenceCurrent(projectId, baselineId, evidence);
    }

    /** Optional search context must not crowd required inputs out of the AI request. */
    private void limitSearchContext(Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        var original = (List<Map<String, Object>>) request.get("evidence");
        var retained = new ArrayList<>(original.stream().filter(e -> e.get("design_item_version_id") != null).toList());
        request.put("evidence", retained);
        int bytes = json.writeValueAsString(request).getBytes(StandardCharsets.UTF_8).length;
        for (var entry : original) {
            if (entry.get("design_item_version_id") != null) continue;
            // Include allowance for separators added by downstream JSON encoders.
            int extra = json.writeValueAsString(entry).getBytes(StandardCharsets.UTF_8).length + 64;
            if (bytes + extra > MAX_SEARCH_CONTEXT_REQUEST_BYTES) continue;
            retained.add(entry);
            bytes += extra;
        }
        request.put("evidence", List.copyOf(retained));
    }

    record QuerySelection(List<String> queries) {}

    /** Bounded phrases and API paths: text is data and search recall remains unknown. */
    static QuerySelection queries(TaskPlanRevisionResponse plan) {
        List<String> fields = new ArrayList<>();
        fields.add(plan.plan().title());
        fields.add(plan.plan().goal());
        fields.addAll(plan.plan().includedScope());
        fields.add(plan.plan().approach());
        Set<String> queries = new LinkedHashSet<>();
        String title = plan.plan().title().strip();
        if (title.isEmpty()) throw incomplete("invalid_search_title");
        // Every query, including the title and extracted paths, must fit the search API limit (S15P31A108-410).
        queries.add(cut(title));
        for (String field : fields) {
            if (field == null || field.isBlank()) continue;
            var paths = API_PATH.matcher(field);
            while (paths.find()) queries.add(cut(paths.group()));
        }
        for (String field : fields) {
            if (field == null || field.isBlank()) continue;
            // Plan fields have no length cap; a long approach is searched by its leading text.
            queries.add(cut(field.strip()));
        }
        return new QuerySelection(withinBudget(List.copyOf(queries)));
    }

    /**
     * A plan with many or long scope items must not become unverifiable (S15P31A108-410). Keep the
     * first queries as they are and search the rest together in one merged query, cut to the search limit.
     */
    private static List<String> withinBudget(List<String> queries) {
        if (queries.size() <= MAX_QUERIES) return queries;
        List<String> kept = new ArrayList<>(queries.subList(0, MAX_QUERIES - 1));
        kept.add(cut(String.join("\n", queries.subList(MAX_QUERIES - 1, queries.size()))));
        return List.copyOf(kept);
    }

    private static String cut(String text) {
        if (text.length() <= MAX_QUERY_LENGTH) return text;
        int end = Character.isHighSurrogate(text.charAt(MAX_QUERY_LENGTH - 1)) ? MAX_QUERY_LENGTH - 1 : MAX_QUERY_LENGTH;
        return text.substring(0, end).strip();
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
                       es.source_locator::text as source_locator
                  from baseline_item bi
                  join design_item i on i.id=bi.design_item_id and i.project_id=bi.project_id
                  join design_item_version v on v.id=bi.design_item_version_id and v.design_item_id=i.id
                  join baseline_item_effective_source es on es.baseline_id=bi.baseline_id and es.design_item_id=bi.design_item_id
                  left join document_version dv on dv.id=es.source_document_version_id
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

    /** A search hit can be used as document evidence without asserting a design relationship. */
    private Set<Long> mappedDocuments(long projectId, long baselineId, Set<Long> versionIds) {
        if (versionIds.isEmpty()) return Set.of();
        var rows = jdbc.sql("""
                select distinct bd.document_version_id
                  from baseline_document bd
                  join baseline_item bi on bi.project_id=bd.project_id and bi.baseline_id=bd.baseline_id
                  join baseline_item_effective_source es on es.baseline_id=bi.baseline_id
                       and es.design_item_id=bi.design_item_id
                       and es.source_document_version_id=bd.document_version_id
                  join design_item_version v on v.id=bi.design_item_version_id
                       and v.design_item_id=bi.design_item_id
                 where bd.project_id=:project and bd.baseline_id=:baseline
                   and bd.document_version_id in (:ids) and v.status='APPROVED'
                """).param("project", projectId).param("baseline", baselineId).param("ids", versionIds)
                .query().listOfRows();
        Set<Long> mapped = new LinkedHashSet<>();
        for (var row : rows) mapped.add(((Number) row.get("document_version_id")).longValue());
        return mapped;
    }

    /**
     * Conservatively include unresolved project questions, selected design questions and
     * questions on current-baseline documents. These are open questions, not proof that a
     * policy is absent. Read again before applying a result because statuses may change.
     */
    public record ContextInputs(List<String> unresolved, List<String> constraints) {}

    public List<String> loadUnresolved(long projectId, long taskId, long baselineId,
                                       Set<Long> itemIds, String contextPacketId) {
        return loadContext(projectId, taskId, baselineId, itemIds, contextPacketId).unresolved();
    }

    public ContextInputs loadContext(long projectId, long taskId, long baselineId,
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
                        (select es.source_document_version_id from baseline_item bi
                          join baseline_item_effective_source es on es.baseline_id=bi.baseline_id
                            and es.design_item_id=bi.design_item_id
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
        List<String> constraints = new ArrayList<>();
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
            if (packetBaseline.longValue() != baselineId) throw incomplete("stale_context_packet");
            if (!(packet.get("unresolved") instanceof List<?> entries)
                    || !(packet.get("constraints") instanceof List<?> packetConstraints)) throw incomplete("invalid_context_packet");
            if (entries.size() > MAX_UNRESOLVED || packetConstraints.size() > MAX_UNRESOLVED) {
                throw incomplete("unresolved_limit");
            }
            for (Object entry : packetConstraints) {
                if (!(entry instanceof String constraint) || constraint.isBlank() || constraint.length() > MAX_UNRESOLVED_TEXT) {
                    throw incomplete("invalid_context_packet");
                }
                constraints.add(constraint);
            }
            for (Object entry : entries) {
                if (!(entry instanceof String question) || question.isBlank() || question.length() > MAX_UNRESOLVED_TEXT) {
                    throw incomplete("invalid_context_packet");
                }
                // The stored packet records its selection cap. Validation collects evidence independently.
                if (!question.startsWith(PACKET_SELECTION_TRUNCATED_PREFIX)) {
                    unresolved.add("context_packet:" + packetId + " " + question);
                }
            }
            if (unresolved.size() > MAX_UNRESOLVED) throw incomplete("unresolved_limit");
        }
        return new ContextInputs(List.copyOf(unresolved), List.copyOf(constraints));
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
                    "rule_findings", "unresolved", "context_packet_constraints", "checks", "evidence")) snapshot.put(key, value.get(key));
            if (value.containsKey("completion_delta")) snapshot.put("completion_delta", value.get("completion_delta"));
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
