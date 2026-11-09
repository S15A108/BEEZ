package com.beez.backend.bee.service;

import com.beez.backend.ai.AiClient;
import com.beez.backend.ai.AiErrorBodies;
import com.beez.backend.ai.AiUsageRecorder;
import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.api.Ids;
import com.beez.backend.common.auth.CallerContext;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** BEE-A02. 입력과 응답의 소속을 검증하며, 작업 상태는 바꾸지 않는다. */
@Service
public class AssignmentSuggestionService {
    private static final String FEATURE = "TASK_ASSIGNMENT";
    private final ProjectAccessChecker access;
    private final AssignmentSnapshotService snapshots;
    private final AiClient ai;
    private final AiUsageRecorder usage;

    public AssignmentSuggestionService(ProjectAccessChecker access, AssignmentSnapshotService snapshots, AiClient ai, AiUsageRecorder usage) {
        this.access = access;
        this.snapshots = snapshots;
        this.ai = ai;
        this.usage = usage;
    }

    public Map<String, Object> suggest(CallerContext caller, long projectId, List<String> rawIds) {
        access.requireMember(caller, projectId).requireHuman();
        if (rawIds == null || rawIds.isEmpty() || rawIds.size() > AssignmentSnapshotService.MAX_TASKS) {
            throw AssignmentSnapshotService.invalid("추천할 작업은 1~20개여야 합니다.");
        }
        List<Long> ids = Ids.parseAll(rawIds, "task_ids");
        if (new HashSet<>(ids).size() != ids.size()) throw AssignmentSnapshotService.invalid("작업 번호가 중복되었습니다.");
        AssignmentSnapshotService.Snapshot before = snapshots.capture(caller, projectId, ids, true);
        String key = "assignment-" + UUID.randomUUID();
        Map<String, Object> input = new LinkedHashMap<>(before.input());
        input.put("request_key", key);
        long start = System.nanoTime();
        Map<String, Object> response;
        try {
            response = ai.assignmentRecommendations(input);
        } catch (RestClientResponseException e) {
            List<Map<String, Object>> entries = AiErrorBodies.usageIn(e);
            long elapsed = elapsed(start);
            if (entries == null || usage.recordLines(FEATURE, projectId, null, key, entries, false, null, elapsed) == 0) {
                usage.recordFailure(FEATURE, projectId, null, key, elapsed);
            }
            if (e.getStatusCode().value() == 504) throw timeout();
            throw unavailable();
        } catch (ResourceAccessException e) {
            usage.recordFailure(FEATURE, projectId, null, key, elapsed(start));
            if (e.getCause() instanceof java.net.http.HttpConnectTimeoutException) throw unavailable();
            if (e.getCause() instanceof HttpTimeoutException || e.getCause() instanceof SocketTimeoutException) throw timeout();
            throw unavailable();
        } catch (RestClientException e) {
            usage.recordFailure(FEATURE, projectId, null, key, elapsed(start));
            throw unavailable();
        }

        Map<String, Object> output;
        try {
            output = validatedResponse(response, before);
        } catch (IllegalArgumentException e) {
            if (!recordInvalidUsage(projectId, key, response, elapsed(start))) {
                usage.recordFailure(FEATURE, projectId, null, key, elapsed(start));
            }
            throw unavailable();
        }
        if (usage.record(FEATURE, projectId, null, key, response, elapsed(start)) == 0
                && !"TEMPLATE".equals(response.get("origin"))) {
            usage.recordFailure(FEATURE, projectId, null, key, elapsed(start));
        }
        AssignmentSnapshotService.Snapshot after = snapshots.capture(caller, projectId, ids, false);
        if (!before.input().equals(after.input())) {
            throw new ApiException(HttpStatus.CONFLICT, "recommendation_stale", "작업이나 팀원 정보가 바뀌었어요. 다시 추천해 주세요.");
        }
        return output;
    }

    private static Map<String, Object> validatedResponse(Map<String, Object> response, AssignmentSnapshotService.Snapshot snapshot) {
        if (response == null || !(response.get("result") instanceof Map<?, ?> result)
                || !(result.get("suggestions") instanceof List<?> suggestions)
                || !(result.get("unresolved") instanceof List<?> unresolved)
                || unresolved.stream().anyMatch(value -> !(value instanceof String))) throw new IllegalArgumentException();
        String origin = response.get("origin") instanceof String value ? value : "";
        if (!Set.of("MODEL", "TEMPLATE").contains(origin)) throw new IllegalArgumentException();
        if (origin.equals("MODEL") && (!(response.get("model") instanceof String model) || model.isBlank())) {
            throw new IllegalArgumentException();
        }
        Set<String> seenTasks = new HashSet<>();
        List<Map<String, Object>> cleanSuggestions = new ArrayList<>();
        for (Object raw : suggestions) {
            if (!(raw instanceof Map<?, ?> item)) throw new IllegalArgumentException();
            String taskId = validText(item.get("task_id"));
            String memberId = validText(item.get("member_id"));
            String reason = validText(item.get("reason"));
            if (!snapshot.taskIds().contains(taskId) || !seenTasks.add(taskId) || !snapshot.memberIds().contains(memberId)
                    || !(item.get("alternatives") instanceof List<?> alternatives)) throw new IllegalArgumentException();
            if (snapshot.memberIds().size() > 1 && alternatives.isEmpty()) throw new IllegalArgumentException();
            Set<String> seenMembers = new HashSet<>();
            seenMembers.add(memberId);
            List<Map<String, String>> cleanAlternatives = new ArrayList<>();
            for (Object rawAlternative : alternatives) {
                if (!(rawAlternative instanceof Map<?, ?> alternative)) throw new IllegalArgumentException();
                String alternativeId = validText(alternative.get("member_id"));
                if (!snapshot.memberIds().contains(alternativeId) || !seenMembers.add(alternativeId)) throw new IllegalArgumentException();
                cleanAlternatives.add(Map.of("member_id", alternativeId, "reason", validText(alternative.get("reason"))));
            }
            cleanSuggestions.add(Map.of("task_id", taskId, "member_id", memberId, "reason", reason, "alternatives", cleanAlternatives));
        }
        if (!seenTasks.equals(snapshot.taskIds())) throw new IllegalArgumentException();
        Map<String, Object> generation = new LinkedHashMap<>();
        generation.put("origin", origin);
        if (response.get("model") instanceof String model && !model.isBlank()) generation.put("model", model);
        generation.put("text", "입력된 담당 영역과 업무량, 선행 작업을 참고한 제안입니다. 사람이 확인해 주세요.");
        generation.put("sources", List.of());
        generation.put("unresolved", unresolved);
        if (response.get("usage") instanceof List<?> lines) generation.put("usage", lines);
        return Map.of("suggestions", cleanSuggestions, "generation", generation);
    }

    private static String validText(Object value) {
        if (!(value instanceof String text) || text.isBlank() || text.length() > 4000) throw new IllegalArgumentException();
        return text;
    }

    /** 실제 토큰 정보는 보존하되, BE 계약 검증에 실패한 결과를 성공으로 집계하지 않는다. */
    private boolean recordInvalidUsage(long projectId, String key, Map<String, Object> response, long elapsed) {
        if (response == null || !(response.get("usage") instanceof List<?> entries)) return false;
        List<Map<String, Object>> failed = new ArrayList<>();
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> raw)) continue;
            Map<String, Object> line = new LinkedHashMap<>();
            raw.forEach((name, value) -> { if (name instanceof String text) line.put(text, value); });
            line.put("failed", true);
            failed.add(line);
        }
        return usage.recordLines(FEATURE, projectId, null, key, failed, false, response.get("model"), elapsed) > 0;
    }

    private static long elapsed(long start) { return (System.nanoTime() - start) / 1_000_000; }
    private static ApiException unavailable() {
        return new ApiException(HttpStatus.BAD_GATEWAY, "ai_unavailable", "추천을 만들지 못했어요. 잠시 뒤 다시 시도해 주세요.");
    }
    private static ApiException timeout() {
        return new ApiException(HttpStatus.GATEWAY_TIMEOUT, "ai_timeout", "추천 응답이 늦어지고 있어요. 다시 시도해 주세요.");
    }
}
