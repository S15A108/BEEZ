package com.beez.backend.bee.service;

import com.beez.backend.ai.AiClient;
import com.beez.backend.ai.AiErrorBodies;
import com.beez.backend.ai.AiUsageRecorder;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.document.service.DocumentIndexWorker;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** BEE-A01: the AI receives only authorized, indexed evidence for a fixed baseline. */
@Service
public class BeeQuestionService {
    private final BeeQuestionSnapshot snapshots;
    private final DocumentIndexWorker search;
    private final AiClient ai;
    private final AiUsageRecorder usage;

    public BeeQuestionService(BeeQuestionSnapshot snapshots, DocumentIndexWorker search, AiClient ai, AiUsageRecorder usage) {
        this.snapshots = snapshots; this.search = search; this.ai = ai; this.usage = usage;
    }

    @Transactional(propagation = Propagation.NEVER)
    public Map<String, Object> answer(CallerContext caller, long project, String question, Long taskId, String scope, String key) {
        snapshots.authorize(caller, project);
        if (question == null || question.isBlank() || question.length() > 2000) {
            throw BeeQuestionSnapshot.invalid("질문은 1~2000자여야 합니다.");
        }
        if (scope != null && !"APPROVED".equals(scope)) {
            if ("INCLUDE_DRAFTS".equals(scope)) throw new ApiException(HttpStatus.CONFLICT, "draft_search_not_supported", "초안 포함 검색은 아직 지원하지 않습니다.");
            throw BeeQuestionSnapshot.invalid("검색 범위가 올바르지 않습니다.");
        }
        if (key == null || key.isBlank() || key.length() > 128 || key.chars().anyMatch(Character::isISOControl)) {
            throw BeeQuestionSnapshot.invalid("Idempotency-Key는 제어 문자가 없는 1~128자여야 합니다.");
        }
        var snapshot = snapshots.capture(caller, project, taskId);
        snapshots.reserve(caller, project, keyHash(key));
        List<Long> evidenceIds;
        try {
            var result = search.search(project, question, null, null, null, 12);
            if (result == null || !(result.get("items") instanceof List<?> hits)) throw unavailable();
            evidenceIds = new ArrayList<>();
            for (Object value : hits) {
                if (!(value instanceof Map<?, ?> hit) || !"DOCUMENT_CHUNK".equals(hit.get("type"))
                        || !(hit.get("id") instanceof String id)) throw unavailable();
                try { evidenceIds.add(Long.parseLong(id)); }
                catch (NumberFormatException badId) { throw unavailable(); }
            }
            if (new LinkedHashSet<>(evidenceIds).size() != evidenceIds.size()) throw unavailable();
        } catch (RestClientResponseException failure) {
            throw failure.getStatusCode().value() == 504 ? timeout() : unavailable();
        } catch (ResourceAccessException failure) {
            throw timedOut(failure) ? timeout() : unavailable();
        } catch (ApiException failure) {
            if ("validation_failed".equals(failure.getCode())) throw unavailable();
            throw failure;
        } catch (IllegalStateException failure) {
            throw unavailable();
        }
        var evidence = snapshots.evidence(caller, project, snapshot, evidenceIds);
        String requestKey = "bee-chat-" + UUID.randomUUID();
        var input = new LinkedHashMap<String, Object>();
        input.put("project_id", Long.toString(project)); input.put("request_key", requestKey); input.put("question", question);
        input.put("scope", Map.of("mode", "APPROVED", "baseline_id", Long.toString(snapshot.baseline()),
                "document_version_ids", snapshot.versions()));
        input.put("embedding_profile_id", snapshot.profile()); input.put("retrieved_evidence", evidence);
        input.put("approved_items", snapshot.approvedItems()); input.put("related_tasks", snapshot.tasks());
        input.put("unresolved", snapshot.unresolved());

        Map<String, Object> envelope;
        long start = System.nanoTime();
        try {
            envelope = ai.beeChat(input);
        } catch (RestClientResponseException failure) {
            var lines = AiErrorBodies.usageIn(failure);
            long elapsed = elapsed(start);
            int recorded = lines == null ? 0 : usage.recordLines("BEE_CHAT", project, taskId, requestKey, lines, false, null, elapsed);
            if (recorded == 0 && failure.getStatusCode().is5xxServerError()) {
                usage.recordFailure("BEE_CHAT", project, taskId, requestKey, elapsed);
            }
            throw failure.getStatusCode().value() == 504 ? timeout() : unavailable();
        } catch (ResourceAccessException failure) {
            usage.recordFailure("BEE_CHAT", project, taskId, requestKey, elapsed(start));
            throw timedOut(failure) ? timeout() : unavailable();
        } catch (RestClientException failure) {
            usage.recordFailure("BEE_CHAT", project, taskId, requestKey, elapsed(start));
            throw unavailable();
        }
        Map<String, Object> response;
        try {
            response = BeeAnswerValidator.validate(envelope, input);
            if (!(response.get("unresolved") instanceof List<?> unresolved) || !unresolved.containsAll(snapshot.unresolved())) {
                throw unavailable();
            }
        } catch (RuntimeException failure) {
            recordInvalid(project, taskId, requestKey, envelope, elapsed(start));
            throw unavailable();
        }
        if (usage.record("BEE_CHAT", project, taskId, requestKey, envelope, elapsed(start)) == 0) {
            usage.recordFailure("BEE_CHAT", project, taskId, requestKey, elapsed(start));
        }
        var usedVersions = new LinkedHashSet<String>();
        if (response.get("sources") instanceof List<?> sources) for (Object value : sources) {
            if (value instanceof Map<?, ?> source && source.get("document_version_id") instanceof String id) usedVersions.add(id);
        }
        long answer = snapshots.issue(caller, project, taskId, snapshot, evidenceIds, evidence, List.copyOf(usedVersions));
        var out = new LinkedHashMap<>(response); out.put("answer_id", Long.toString(answer));
        return out;
    }

    private void recordInvalid(long project, Long task, String key, Map<String, Object> envelope, long elapsed) {
        var failed = new ArrayList<Map<String, Object>>();
        if (envelope != null && envelope.get("usage") instanceof List<?> entries) for (Object value : entries) {
            if (value instanceof Map<?, ?> raw) {
                var copy = new LinkedHashMap<String, Object>();
                raw.forEach((k, v) -> { if (k instanceof String name) copy.put(name, v); });
                copy.put("failed", true); failed.add(copy);
            }
        }
        if (usage.recordLines("BEE_CHAT", project, task, key, failed, false, null, elapsed) == 0) {
            usage.recordFailure("BEE_CHAT", project, task, key, elapsed);
        }
    }

    private static long elapsed(long start) { return (System.nanoTime() - start) / 1_000_000; }
    private static String keyHash(String key) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static boolean timedOut(ResourceAccessException failure) {
        return failure.getCause() instanceof HttpTimeoutException || failure.getCause() instanceof SocketTimeoutException;
    }
    private static ApiException unavailable() { return new ApiException(HttpStatus.BAD_GATEWAY, "ai_unavailable", "비비 답변을 만들지 못했습니다."); }
    private static ApiException timeout() { return new ApiException(HttpStatus.GATEWAY_TIMEOUT, "ai_timeout", "비비 답변 시간이 초과되었습니다."); }
}
