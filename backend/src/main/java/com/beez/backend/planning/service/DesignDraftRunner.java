package com.beez.backend.planning.service;

import static com.beez.backend.designitem.support.DesignJson.JSON;
import static com.beez.backend.designitem.support.DesignJson.canonical;
import static com.beez.backend.designitem.support.DesignJson.hash;

import com.beez.backend.changeproposal.service.DraftUsageRecorder;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.designitem.repository.DesignProjectRepository;
import com.beez.backend.designitem.validation.DesignContentValidator;
import com.beez.backend.planning.port.AiFailure;
import com.beez.backend.planning.port.DesignDraftGateway;
import com.beez.backend.planning.repository.DesignDraftRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 접수된 설계 초안 생성 작업({@code design_draft_job})을 하나씩 처리한다. 접수 때 보존한 입력 스냅샷을 AI(134)에 그대로 보내고,
 * 응답을 검증해 생성 후보로 보존한다. 설계 항목은 건드리지 않는다.
 *
 * <p>AI 호출 동안에는 트랜잭션·잠금을 잡지 않는다. 결과를 저장할 때 프로젝트 잠금을 잡고 작업이 아직 내 것인지(lease)와 기준 설계가
 * 아직 없는지 다시 확인한다. 실패하면 작업만 FAILED 로 남기고 기존 설계 항목·후보는 그대로 둔다. FAILED 는 새 요청으로 다시 만든다.
 */
@Service
public class DesignDraftRunner {
    private static final Logger log = LoggerFactory.getLogger(DesignDraftRunner.class);
    static final int LEASE_SECONDS = 300;
    private static final Set<String> SUPPORTS = Set.of("DOCUMENT", "ANSWER", "DOCUMENT_AND_ANSWER", "NONE");

    private final DesignDraftRepository drafts;
    private final DesignDraftGateway gateway;
    private final DraftUsageRecorder usage;
    private final DesignProjectRepository projects;
    private final DesignContentValidator content;
    private final TransactionTemplate tx;

    public DesignDraftRunner(DesignDraftRepository drafts, DesignDraftGateway gateway, DraftUsageRecorder usage,
                             DesignProjectRepository projects, DesignContentValidator content, PlatformTransactionManager manager) {
        this.drafts = drafts; this.gateway = gateway; this.usage = usage; this.projects = projects; this.content = content;
        this.tx = new TransactionTemplate(manager);
    }

    /** 처리할 작업 하나를 끝까지 처리한다. 작업이 없으면 false. */
    @SuppressWarnings("unchecked")
    public boolean runOnce() {
        Map<String, Object> job = tx.execute(status -> drafts.claim(LEASE_SECONDS).orElse(null));
        if (job == null) return false;
        long id = ((Number) job.get("id")).longValue(), project = ((Number) job.get("project_id")).longValue();
        Object lease = job.get("lease_until");
        Map<String, Object> snapshot = JSON.readValue(job.get("input_snapshot").toString(), Map.class);
        // 호출마다 새 key: 같은 작업을 다시 잡아도 실제로 일어난 AI 호출은 모두 사용량에 센다.
        String requestKey = "design-draft-job-" + id + "-" + UUID.randomUUID();
        var payload = new LinkedHashMap<String, Object>();
        payload.put("request_key", requestKey);
        payload.putAll(snapshot);
        payload.put("project_id", Long.toString(project));

        long started = System.nanoTime();
        Map<String, Object> response;
        try {
            response = gateway.draft(payload);
        } catch (AiFailure e) {
            recordFailedCall(project, requestKey, e, elapsed(started));
            fail(id, lease, e.getCode(), e.retryable(), e.usage());
            return true;
        } catch (RuntimeException e) {
            log.error("design-drafts call failed unexpectedly: {}", e.toString(), e);
            usage.recordFailure(project, null, requestKey, elapsed(started));
            fail(id, lease, "ai_unavailable", true, null);
            return true;
        }
        int recorded = response == null ? 0 : usage.record(project, null, requestKey, response, elapsed(started));
        Parsed parsed;
        try {
            parsed = parse(response, snapshot, id, project);
        } catch (IllegalArgumentException | ApiException e) {
            log.warn("design-drafts output unusable for job {}: {}", id, e.getMessage());
            if (recorded == 0) usage.recordFailure(project, null, requestKey, elapsed(started));
            fail(id, lease, "invalid_ai_output", true, usageOf(response));
            return true;
        }
        tx.executeWithoutResult(status -> save(id, project, lease, parsed, usageOf(response)));
        return true;
    }

    private void save(long id, long project, Object lease, Parsed parsed, List<Map<String, Object>> usageLines) {
        if (!projects.lockActiveProject(project) || !drafts.jobStillRunning(id, lease)) return;
        if (drafts.hasBaseline(project)) {
            drafts.finishJob(id, lease, "FAILED", "baseline_exists", false, null, usageLines);
            return;
        }
        for (var candidate : parsed.candidates()) drafts.insertCandidate(candidate);
        drafts.finishJob(id, lease, "COMPLETED", null, false, parsed.meta(), usageLines);
    }

    private void fail(long id, Object lease, String code, boolean retryable, List<Map<String, Object>> usageLines) {
        tx.executeWithoutResult(status -> drafts.finishJob(id, lease, "FAILED", code, retryable, null, usageLines));
    }

    record Parsed(List<Map<String, Object>> candidates, Map<String, Object> meta) { }

    /**
     * AI 응답을 후보 행으로 바꾼다. 요청한 문서 버전·텍스트 해시와 다르거나, 항목과 변경 목록이 짝이 맞지 않거나, 기준 항목·답변 키가
     * 접수 때 보낸 입력에 없거나, 변경 전 본문이 보낸 본문과 다르거나, 설계 본문 규칙을 어기면 전체를 거절한다(일부만 저장하지 않는다).
     */
    @SuppressWarnings("unchecked")
    private Parsed parse(Map<String, Object> response, Map<String, Object> snapshot, long job, long project) {
        if (response == null || !(response.get("result") instanceof Map<?, ?> raw)) throw new IllegalArgumentException("result 없음");
        var result = (Map<String, Object>) raw;
        var document = (Map<String, Object>) snapshot.get("document");
        if (!(result.get("document") instanceof Map<?, ?> doc)
                || !document.get("document_id").equals(String.valueOf(doc.get("document_id")))
                || !document.get("document_version_id").equals(String.valueOf(doc.get("document_version_id")))
                || !snapshot.get("text_hash").equals(result.get("text_hash"))) {
            throw new IllegalArgumentException("다른 문서 버전의 결과");
        }
        if (!(result.get("items") instanceof List<?> itemList) || !(result.get("changes") instanceof List<?> changeList)) {
            throw new IllegalArgumentException("items/changes 없음");
        }
        Map<String, Map<String, Object>> existing = new HashMap<>();
        for (Object value : (List<Object>) snapshot.get("existing_items")) {
            var item = (Map<String, Object>) value;
            existing.put(item.get("design_item_version_id").toString(), item);
        }
        Set<String> answered = new java.util.HashSet<>();
        for (Object value : (List<Object>) snapshot.get("questions")) {
            var q = (Map<String, Object>) value;
            if ("ANSWERED".equals(q.get("status"))) answered.add(((Map<String, Object>) q.get("question")).get("question_key").toString());
        }
        Map<String, Map<String, Object>> changes = new HashMap<>();
        for (Object value : changeList) {
            var change = (Map<String, Object>) value;
            if (changes.put(text(change.get("candidate_key")), change) != null) throw new IllegalArgumentException("변경 목록 키 중복");
        }
        if (changes.size() != itemList.size()) throw new IllegalArgumentException("항목과 변경 목록의 수가 다름");

        List<Map<String, Object>> rows = new ArrayList<>();
        Set<String> keys = new java.util.HashSet<>();
        for (Object value : itemList) {
            var item = (Map<String, Object>) value;
            String key = text(item.get("candidate_key"));
            var change = changes.get(key);
            if (change == null || !keys.add(key)) throw new IllegalArgumentException("항목과 변경 목록이 짝이 아님");
            String kind = text(item.get("kind")), kindAfter = kind;
            if (!(item.get("content") instanceof Map<?, ?> after) || !"DRAFT".equals(item.get("status"))) throw new IllegalArgumentException("초안 본문");
            content.validate(kind, 1, (Map<String, Object>) after);
            String support = text(item.get("support"));
            if (!SUPPORTS.contains(support)) throw new IllegalArgumentException("support");
            List<String> refs = strings(item.get("answer_refs"));
            if (!answered.containsAll(refs)) throw new IllegalArgumentException("답변 참조가 보낸 질문에 없음");
            String changeKind = text(change.get("change"));
            Map<String, Object> before = null;
            Long base = null;
            String baseStatus = null;
            if ("UPDATE".equals(changeKind)) {
                String baseId = text(item.get("base_design_item_version_id"));
                var current = existing.get(baseId);
                if (current == null || !kindAfter.equals(current.get("kind")) || !baseId.equals(String.valueOf(change.get("base_design_item_version_id")))) {
                    throw new IllegalArgumentException("기준 항목이 보낸 입력에 없음");
                }
                before = (Map<String, Object>) current.get("content");
                if (!canonical(before).equals(canonical(change.get("before")))) throw new IllegalArgumentException("변경 전 본문이 보낸 본문과 다름");
                if (!canonical(after).equals(canonical(change.get("after")))) throw new IllegalArgumentException("변경 후 본문이 초안과 다름");
                base = Long.parseLong(baseId);
                baseStatus = current.get("status").toString();
            } else if (!"CREATE".equals(changeKind) || item.get("base_design_item_version_id") != null) {
                throw new IllegalArgumentException("변경 종류");
            }
            var row = new HashMap<String, Object>();
            row.put("job", job); row.put("project", project); row.put("key", key); row.put("change", changeKind); row.put("kind", kind);
            row.put("base", base); row.put("baseStatus", baseStatus);
            row.put("before", before == null ? null : canonical(before));
            row.put("beforeHash", before == null ? null : hash(before));
            row.put("after", canonical(after));
            row.put("changed", canonical(change.get("changed_fields") instanceof List<?> l ? l : List.of()));
            row.put("conflicting", canonical(change.get("conflicting_fields") instanceof List<?> l ? l : List.of()));
            row.put("sources", canonical(item.get("sources") instanceof List<?> l ? l : List.of()));
            row.put("refs", canonical(refs));
            row.put("support", support);
            row.put("rationale", item.get("rationale") instanceof String s ? s : null);
            rows.add(row);
        }
        var meta = new LinkedHashMap<String, Object>();
        meta.put("origin", response.get("origin"));
        meta.put("untouched_existing_ids", result.get("untouched_existing_ids") instanceof List<?> l ? l : List.of());
        meta.put("unanswered_question_keys", result.get("unanswered_question_keys") instanceof List<?> l ? l : List.of());
        meta.put("unresolved", result.get("unresolved") instanceof List<?> l ? l : List.of());
        meta.put("omitted_items", result.get("omitted_items") instanceof Number n ? n.intValue() : 0);
        return new Parsed(rows, meta);
    }

    private static List<String> strings(Object value) {
        var list = new ArrayList<String>();
        if (value instanceof List<?> items) for (Object o : items) list.add(text(o));
        return list;
    }

    private static String text(Object value) {
        if (!(value instanceof String s) || s.isBlank()) throw new IllegalArgumentException("필수 문자열 없음");
        return s;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> usageOf(Map<String, Object> response) {
        if (response == null || !(response.get("usage") instanceof List<?> list)) return null;
        var lines = new ArrayList<Map<String, Object>>();
        for (Object o : list) if (o instanceof Map<?, ?> m) lines.add((Map<String, Object>) m);
        return lines;
    }

    private void recordFailedCall(long project, String requestKey, AiFailure error, long elapsed) {
        int recorded = 0;
        if (error.usage() != null) recorded = usage.recordLines(project, null, requestKey, error.usage(), false, null, elapsed);
        if (recorded == 0) usage.recordFailure(project, null, requestKey, elapsed);
    }

    private static long elapsed(long startedNanos) { return (System.nanoTime() - startedNanos) / 1_000_000; }
}
