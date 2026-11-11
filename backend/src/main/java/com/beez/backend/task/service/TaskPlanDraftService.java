package com.beez.backend.task.service;

import com.beez.backend.ai.AiCallException;
import com.beez.backend.ai.AiClient;
import com.beez.backend.ai.AiUsageRecorder;
import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.designitem.validation.DesignContentValidator;
import com.beez.backend.document.service.DocumentIndexWorker;
import com.beez.backend.document.service.DocumentIndexWorker.ChunkEvidence;
import com.beez.backend.planning.service.AiPlanningAdapter;
import com.beez.backend.task.dto.TaskPlanDraftRequest;
import com.beez.backend.task.service.ProjectDesignLookup.ApprovedItem;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * 작업 계획 문서 초안 (S15P31A108-385). 사람이 누를 때만 한 번 부르고, 결과는 저장하지 않고 응답으로만 돌려준다.
 *
 * <p>흐름: 권한·기준 확인 → 호출 가드(동시 1건, 시간당 횟수) → 현재 baseline 의 승인 문서에서 제목·메모와 가까운 발췌를 고름 →
 * 승인 설계 항목 목록과 함께 AI 호출 → 응답 검증(연결 후보는 승인 항목만, 신규 항목 후보는 내용 검증·중복 제거) → 사용량 기록.
 * AI 는 판단을 내리지 않는다. 연결 여부와 {@code no_contract_change} 는 화면에서 사람이 정한다.
 */
@Service
public class TaskPlanDraftService {
    private static final Logger log = LoggerFactory.getLogger(TaskPlanDraftService.class);
    static final String FEATURE = "BEE_DRAFT";
    private static final Set<String> DOCUMENT_TYPES = Set.of("PLAN", "REQUIREMENTS", "API_SPEC", "ERD");

    private final ProjectAccessChecker access;
    private final ProjectDesignLookup design;
    private final DocumentIndexWorker documents;
    private final DesignContentValidator contents;
    private final AiClient ai;
    private final AiUsageRecorder usage;
    private final JdbcClient jdbc;
    private final int maxCallsPerHour;
    private final TaskPlanDraftLimits limits;
    /** 같은 사람이 같은 프로젝트에서 동시에 두 번 부르지 못하게 한다(중복 클릭). */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    /** 모델을 부르기 전에 거절된 요청. 임베딩 비용은 나갔지만 llm_usage 에는 남지 않아 따로 센다. */
    private final TaskPlanDraftWindow rejectedBeforeModel = new TaskPlanDraftWindow();

    public TaskPlanDraftService(ProjectAccessChecker access, ProjectDesignLookup design, DocumentIndexWorker documents,
                                DesignContentValidator contents, AiClient ai, AiUsageRecorder usage, JdbcClient jdbc,
                                @Value("${beez.task-plan-draft.max-calls-per-hour:20}") int maxCallsPerHour,
                                TaskPlanDraftLimits limits) {
        this.access = access;
        this.design = design;
        this.documents = documents;
        this.contents = contents;
        this.ai = ai;
        this.usage = usage;
        this.jdbc = jdbc;
        this.maxCallsPerHour = maxCallsPerHour;
        this.limits = limits;
    }

    public Map<String, Object> draft(CallerContext caller, long projectId, TaskPlanDraftRequest body) {
        access.requireMember(caller, projectId).requireHuman();
        Long baseline = design.currentBaselineId(projectId).orElse(null);
        if (baseline == null) {
            throw new ApiException(HttpStatus.CONFLICT, "baseline_missing", "기준 설계가 만들어지기 전에는 문서로 초안을 받을 수 없어요.");
        }
        long account = caller.accountId();
        String guard = guardKey(projectId, account);
        if (!inFlight.add(guard)) {
            throw new ApiException(HttpStatus.CONFLICT, "draft_in_progress", "이미 초안을 만드는 중이에요. 끝날 때까지 기다려 주세요.");
        }
        try {
            if (recentCalls(projectId, account) >= maxCallsPerHour) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "draft_limit_exceeded",
                        "한 시간에 받을 수 있는 초안 횟수를 모두 썼어요. 잠시 뒤 다시 시도해 주세요.");
            }
            return run(projectId, account, baseline, body);
        } finally {
            inFlight.remove(guard);
        }
    }

    private Map<String, Object> run(long projectId, long account, long baseline, TaskPlanDraftRequest body) {
        long retrievalStart = System.nanoTime();
        var retrieval = documents.retrieveEvidence(projectId, query(body), limits.retrievalLimit());
        long retrievalMillis = elapsedMillis(retrievalStart);
        if (retrieval.baselineId() != baseline) {
            rejectedBeforeModel.add(guardKey(projectId, account), System.currentTimeMillis());
            throw new ApiException(HttpStatus.CONFLICT, "baseline_changed", "기준이 바뀌었어요. 다시 시도해 주세요.");
        }
        long selectionStart = System.nanoTime();
        var selected = TaskPlanDraftSelector.excerpts(query(body), retrieval.evidence(), limits);
        List<Map<String, Object>> excerpts = excerpts(projectId, selected);
        if (excerpts.isEmpty()) {
            rejectedBeforeModel.add(guardKey(projectId, account), System.currentTimeMillis());
            throw new ApiException(HttpStatus.CONFLICT, "insufficient_documents",
                    "초안을 만들 승인된 문서 내용을 찾지 못했어요. 문서함에서 문서 승인과 검색 준비 상태를 확인해 주세요.");
        }
        List<ApprovedItem> approved = design.allApprovedItems(projectId, baseline);
        // 확정 의존 항목이 들어갈 자리를 먼저 남긴다. 어휘로 고른 항목이 한도를 다 채우면 의존 항목이 통째로 빠졌다.
        int maxItems = limits.maxDesignItems();
        int dependencyRoom = Math.min(maxItems / 5, 20);
        List<ApprovedItem> modelItems = new ArrayList<>(TaskPlanDraftSelector.designItems(
                query(body), selected, approved, maxItems - dependencyRoom));
        Set<Long> selectedIds = new LinkedHashSet<>(modelItems.stream().map(ApprovedItem::id).toList());
        Set<Long> dependencies = new HashSet<>(design.confirmedDependencyTargets(projectId, new ArrayList<>(selectedIds)));
        for (ApprovedItem item : approved) {
            if (modelItems.size() >= maxItems) break;
            if (dependencies.contains(item.id()) && selectedIds.add(item.id())) modelItems.add(item);
        }
        long selectionMillis = elapsedMillis(selectionStart);

        String key = "task-plan-draft-" + account + "-" + UUID.randomUUID();
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("title", body.title().strip());
        if (body.memo() != null && !body.memo().isBlank()) task.put("memo", body.memo().strip());
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("project_id", Long.toString(projectId));
        input.put("request_key", key);
        input.put("task", task);
        input.put("excerpts", excerpts);
        input.put("design_items", modelItems.stream().map(this::aiItem).toList());
        log.info("task-plan-draft-input project={} candidates={} excerpts={} approved_items={} selected_items={} retrieval_ms={} selection_ms={}",
                projectId, retrieval.evidence().size(), excerpts.size(), approved.size(), modelItems.size(), retrievalMillis, selectionMillis);

        long start = System.nanoTime();
        Map<String, Object> response;
        try {
            response = AiPlanningAdapter.call("task-plan-drafts", ai::taskPlanDrafts, input);
        } catch (AiCallException e) {
            recordFailedCall(projectId, key, e.usage(), elapsedMillis(start));
            log.info("task-plan-draft-failed project={} retrieval_ms={} selection_ms={} generation_ms={}",
                    projectId, retrievalMillis, selectionMillis, elapsedMillis(start));
            throw e;
        }
        long elapsed = elapsedMillis(start);
        if (response == null || !(response.get("result") instanceof Map<?, ?> result)) {
            usage.recordFailure(FEATURE, projectId, null, key, elapsed);
            throw new AiCallException(HttpStatus.BAD_GATEWAY, "ai_unavailable",
                    "AI 서버가 요청을 처리하지 못했어요. 잠시 뒤 다시 시도해 주세요.", null, true);
        }
        usage.record(FEATURE, projectId, null, key, response, elapsed);
        logMetrics(projectId, account, excerpts.size(), modelItems.size(), response, elapsed);
        if (!design.currentBaselineId(projectId).filter(id -> id == baseline).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "baseline_changed", "기준이 바뀌었어요. 다시 시도해 주세요.");
        }
        return view(baseline, result, approved, retrieval.evidence());
    }

    // ---- 입력 만들기 ----

    private static String query(TaskPlanDraftRequest body) {
        String text = body.title().strip() + (body.memo() == null || body.memo().isBlank() ? "" : " " + body.memo().strip());
        return text.length() > 1000 ? text.substring(0, 1000) : text;
    }

    private List<Map<String, Object>> excerpts(long projectId, List<ChunkEvidence> evidence) {
        if (evidence.isEmpty()) return List.of();
        Map<Long, String> types = new HashMap<>();
        jdbc.sql("select id, type from document where project_id = :project and id in (:ids)")
                .param("project", projectId)
                .param("ids", evidence.stream().map(ChunkEvidence::documentId).distinct().toList())
                .query((rs, n) -> Map.entry(rs.getLong("id"), rs.getString("type")))
                .list().forEach(entry -> types.put(entry.getKey(), "REQ_SPEC".equals(entry.getValue()) ? "REQUIREMENTS" : entry.getValue()));
        List<Map<String, Object>> chosen = new ArrayList<>();
        int total = 0;
        for (ChunkEvidence chunk : evidence) {
            String type = types.get(chunk.documentId());
            int length = chunk.text().length();
            if (type == null || !DOCUMENT_TYPES.contains(type) || length == 0 || length > limits.maxExcerptChars()) continue;
            if (total + length > limits.totalExcerptChars()) continue;
            total += length;
            Map<String, Object> excerpt = new LinkedHashMap<>();
            excerpt.put("excerpt_id", "c" + chunk.chunkId());
            excerpt.put("document_id", Long.toString(chunk.documentId()));
            excerpt.put("document_type", type);
            excerpt.put("document_version_id", Long.toString(chunk.documentVersionId()));
            excerpt.put("version_no", chunk.versionNo());
            excerpt.put("location", location(chunk));
            excerpt.put("text", chunk.text());
            chosen.add(excerpt);
        }
        return chosen;
    }

    static String location(ChunkEvidence chunk) {
        Object start = chunk.locator().get("start_line");
        Object end = chunk.locator().get("end_line");
        String title = chunk.title() == null || chunk.title().isBlank() ? "문서" : chunk.title();
        return start != null && end != null ? title + " · " + start + "~" + end + "행" : title;
    }

    private Map<String, Object> aiItem(ApprovedItem item) {
        String summary = item.description().isBlank() || item.title().equals(item.description())
                ? item.title() : item.title() + " — " + item.description();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("design_item_id", Long.toString(item.id()));
        view.put("kind", item.kind());
        view.put("key", item.key());
        view.put("summary", summary.length() > limits.designSummaryChars()
                ? summary.substring(0, limits.designSummaryChars()) : summary);
        return view;
    }

    // ---- 응답 검증 ----

    private Map<String, Object> view(long baseline, Map<?, ?> result, List<ApprovedItem> approved, List<ChunkEvidence> originals) {
        Map<String, ApprovedItem> byId = new HashMap<>();
        for (ApprovedItem item : approved) byId.put(Long.toString(item.id()), item);

        // 연결 후보는 현재 baseline 의 승인 항목만 남긴다. AI 가 낸 id 를 믿지 않는다.
        List<Map<String, Object>> links = new ArrayList<>();
        Set<String> linked = new LinkedHashSet<>();
        for (Object entry : list(result.get("design_ref_candidates"))) {
            if (!(entry instanceof Map<?, ?> candidate)) continue;
            String id = Objects.toString(candidate.get("design_item_id"), "");
            ApprovedItem item = byId.get(id);
            if (item == null || !linked.add(id)) continue;
            links.add(linkView(item, Objects.toString(candidate.get("reason"), "")));
        }

        List<Map<String, Object>> newItems = new ArrayList<>();
        for (Object entry : list(result.get("new_design_item_candidates"))) {
            if (!(entry instanceof Map<?, ?> candidate)) continue;
            String kind = Objects.toString(candidate.get("kind"), "");
            @SuppressWarnings("unchecked") Map<String, Object> content = candidate.get("content") instanceof Map<?, ?> m
                    ? (Map<String, Object>) m : null;
            if (content == null || !validContent(kind, content)) continue;
            var evidence = verifiedEvidence(list(candidate.get("evidence")), originals);
            if (evidence.isEmpty()) continue;
            ApprovedItem same = sameDescription(approved, kind, content);
            if (same != null) {
                // 문서의 같은 내용이 이미 설계에 있으면 새 항목이 아니라 연결 후보로 돌린다.
                if (linked.add(Long.toString(same.id()))) links.add(linkView(same, "문서의 같은 내용이 이미 설계 항목에 있어요."));
                continue;
            }
            var verified = new LinkedHashMap<>(castMap(candidate));
            verified.put("evidence", evidence);
            newItems.add(verified);
        }

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("status", result.get("status"));
        view.put("plan", result.get("plan"));
        var evidence = verifiedEvidence(list(result.get("evidence")), originals);
        view.put("evidence", evidence);
        Set<String> supported = new LinkedHashSet<>();
        for (var entry : evidence) supported.add(Objects.toString(entry.get("field"), ""));
        List<String> unsupported = new ArrayList<>();
        if (result.get("plan") instanceof Map<?, ?> plan) {
            for (String field : List.of("goal", "included_scope", "excluded_scope", "approach", "completion_criteria")) {
                Object value = plan.get(field);
                boolean filled = value instanceof String text ? !text.isBlank() : value instanceof List<?> items && !items.isEmpty();
                if (filled && !supported.contains(field)) unsupported.add(field);
            }
        }
        view.put("unsupported_fields", unsupported);
        view.put("design_ref_candidates", links);
        view.put("new_design_item_candidates", newItems);
        view.put("unlinked", list(result.get("unlinked")));
        view.put("baseline_id", Long.toString(baseline));
        return view;
    }

    /** Compression may join nonadjacent rows. Quotes must still exist in the original stored chunk. */
    private static List<Map<String, Object>> verifiedEvidence(List<?> entries, List<ChunkEvidence> originals) {
        List<Map<String, Object>> verified = new ArrayList<>();
        for (Object value : entries) {
            if (!(value instanceof Map<?, ?> entry) || !(entry.get("quote") instanceof String quote) || quote.isBlank()) continue;
            boolean matches = originals.stream().anyMatch(chunk ->
                    Long.toString(chunk.documentId()).equals(Objects.toString(entry.get("document_id"), ""))
                    && Long.toString(chunk.documentVersionId()).equals(Objects.toString(entry.get("document_version_id"), ""))
                    && entry.get("version_no") instanceof Number version && version.doubleValue() == chunk.versionNo()
                    && chunk.text().contains(quote));
            if (matches) verified.add(new LinkedHashMap<>(castMap(entry)));
        }
        return verified;
    }

    private boolean validContent(String kind, Map<String, Object> content) {
        try {
            contents.validate(kind, 1, content);
            return true;
        } catch (ApiException e) {
            return false;
        }
    }

    private static ApprovedItem sameDescription(List<ApprovedItem> approved, String kind, Map<String, Object> content) {
        String wanted = normalize(content.get("description"));
        if (wanted.isEmpty()) return null;
        for (ApprovedItem item : approved) {
            if (item.kind().equals(kind) && normalize(item.description()).equals(wanted)) return item;
        }
        return null;
    }

    private static String normalize(Object text) {
        return text == null ? "" : text.toString().replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
    }

    private static Map<String, Object> linkView(ApprovedItem item, String reason) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("design_item_id", Long.toString(item.id()));
        view.put("design_item_version_id", Long.toString(item.versionId()));
        view.put("key", item.key());
        view.put("kind", item.kind());
        view.put("title", item.title());
        view.put("reason", reason);
        return view;
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    // ---- 가드·기록 ----

    private static String guardKey(long projectId, long account) {
        return projectId + ":" + account;
    }

    /**
     * 이 사람이 이 프로젝트에서 최근 한 시간 안에 한 초안 요청 수. 한 번 누른 것을 한 번으로 센다.
     * AI 가 사용량의 request_key 에 BE 요청 키(task-plan-draft-{계정}-{uuid})를 실어 보내므로, 그 키가 같은 행은 한 번이다
     * (한 번의 호출이 사용량 줄을 여러 개 남겨도 한 번). 키를 알아볼 수 없는 행은 그 행 자체를 한 번으로 본다.
     * 모델을 부르기 전에 거절된 요청은 사용량에 남지 않아 메모리 카운터로 더한다.
     */
    private long recentCalls(long projectId, long account) {
        long recorded = jdbc.sql("""
                        select count(distinct coalesce(
                                 substring(request_key from 'task-plan-draft-[0-9]+-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}'),
                                 request_key))
                          from llm_usage
                         where project_id = :project and feature = :feature
                           and request_key like :pattern
                           and created_at > now() - interval '1 hour'
                        """)
                .param("project", projectId).param("feature", FEATURE)
                .param("pattern", "%task-plan-draft-" + account + "-%")
                .query(Long.class).single();
        return recorded + rejectedBeforeModel.count(guardKey(projectId, account), System.currentTimeMillis());
    }

    private void recordFailedCall(long projectId, String key, List<Map<String, Object>> entries, long elapsed) {
        if (entries == null || usage.recordLines(FEATURE, projectId, null, key, entries, false, null, elapsed) == 0) {
            usage.recordFailure(FEATURE, projectId, null, key, elapsed);
        }
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static void logMetrics(long projectId, long account, int excerpts, int items, Map<String, Object> response, long elapsed) {
        Object input = null;
        Object output = null;
        if (response.get("usage") instanceof List<?> lines && !lines.isEmpty() && lines.get(0) instanceof Map<?, ?> first) {
            input = first.get("input_tokens");
            output = first.get("output_tokens");
        }
        log.info("task-plan-draft project={} account={} excerpts={} design_items={} input_tokens={} output_tokens={} elapsed_ms={}",
                projectId, account, excerpts, items, input, output, elapsed);
    }
}
