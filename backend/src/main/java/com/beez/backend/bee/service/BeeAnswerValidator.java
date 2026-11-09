package com.beez.backend.bee.service;

import com.beez.backend.common.api.ApiException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/** Validates the AI-A05 result against the evidence selected by the backend. */
public final class BeeAnswerValidator {
    private static final Pattern MARKER = Pattern.compile("\\[(\\d+)\\]");
    private static final String INSUFFICIENT = "찾지 못했어요. 지금 승인된 문서와 설계 항목에서는 이 질문에 답할 근거를 찾지 못했습니다. 짐작해서 답하지 않습니다.";
    private static final String OUT_OF_SCOPE = "이 질문은 이 프로젝트의 문서·설계 범위 밖이라 답하지 않습니다. 프로젝트 문서나 승인 설계에 대해 물어봐 주세요.";

    private BeeAnswerValidator() { }

    /** Returns screen fields only. The caller owns authorization, usage storage and answer ID. */
    public static Map<String, Object> validate(Map<String, Object> envelope, Map<String, Object> input) {
        try {
            return checked(envelope, input);
        } catch (RuntimeException failure) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "ai_unavailable", "비비 응답을 확인하지 못했어요. 다시 시도해 주세요.");
        }
    }

    private static Map<String, Object> checked(Map<String, Object> envelope, Map<String, Object> input) {
        if (!"MODEL".equals(envelope.get("origin"))) throw invalid();
        String model = text(envelope.get("model"), 256);
        List<?> usage = list(envelope.get("usage"), 16);
        if (usage.isEmpty()) throw invalid();
        boolean successfulCall = false;
        Set<String> usageKeys = new HashSet<>();
        List<Map<String, Object>> cleanUsage = new ArrayList<>();
        for (Object value : usage) {
            Map<String, Object> line = map(value);
            if (!usageKeys.add(text(line.get("request_key"), 256))) throw invalid();
            text(line.get("model"), 256);
            if (!"BEE_CHAT".equals(line.get("feature")) || !(line.get("failed") instanceof Boolean)
                    || !(line.get("tokens_known") instanceof Boolean)) throw invalid();
            integer(line.get("latency_ms"), 0, Integer.MAX_VALUE);
            if (Boolean.TRUE.equals(line.get("tokens_known"))) {
                integer(line.get("input_tokens"), 0, Integer.MAX_VALUE);
                integer(line.get("output_tokens"), 0, Integer.MAX_VALUE);
            } else if (line.get("input_tokens") != null || line.get("output_tokens") != null) throw invalid();
            Object amount = line.get("estimated_cost_amount"), currency = line.get("currency");
            if ((amount == null) != (currency == null)) throw invalid();
            if (amount != null) {
                if (!(amount instanceof String || amount instanceof Number)
                        || !text(currency, 3).matches("[A-Z]{3}")) throw invalid();
                String amountText = amount.toString();
                if (amountText.length() > 64 || new BigDecimal(amountText).signum() < 0) throw invalid();
            }
            Map<String, Object> clean = new LinkedHashMap<>();
            for (String key : List.of("request_key", "feature", "model", "latency_ms", "failed", "tokens_known",
                    "input_tokens", "output_tokens", "estimated_cost_amount", "currency")) {
                if (line.get(key) != null) clean.put(key, line.get(key));
            }
            cleanUsage.add(clean);
            if (Boolean.FALSE.equals(line.get("failed"))) successfulCall = true;
        }
        if (!successfulCall) throw invalid();

        Map<String, Object> result = map(envelope.get("result"));
        String answer = text(result.get("text"), 10_000);
        String status = text(result.get("answer_status"), 40);
        if (!Set.of("ANSWERED", "INSUFFICIENT_EVIDENCE", "OUT_OF_SCOPE").contains(status)
                || !(result.get("grounded") instanceof Boolean grounded)) throw invalid();
        Map<String, Object> scope = map(input.get("scope"));
        Map<String, Object> echoedScope = map(result.get("scope"));
        if (!Objects.equals(scope, echoedScope)
                || !Objects.equals(input.get("embedding_profile_id"), result.get("embedding_profile_id"))) throw invalid();
        Set<String> allowedVersions = stringSet(scope.get("document_version_ids"), 1000);
        String profile = id(input.get("embedding_profile_id"));

        List<?> rawEvidence = list(input.get("retrieved_evidence"), 50);
        int maxEvidence = input.containsKey("max_evidence") ? integer(input.get("max_evidence"), 1, 50) : 12;
        Map<String, Map<String, Object>> evidence = new HashMap<>();
        for (int i = 0; i < rawEvidence.size(); i++) {
            Map<String, Object> item = map(rawEvidence.get(i));
            String key = id(item.get("evidence_id"));
            if (!allowedVersions.contains(id(item.get("document_version_id")))
                    || !profile.equals(id(item.get("embedding_profile_id")))) throw invalid();
            if (i < maxEvidence && evidence.putIfAbsent(key, item) != null) throw invalid();
        }

        Map<String, Map<String, Object>> items = index(input.get("approved_items"), "id", 500);
        Map<String, Map<String, Object>> tasks = index(input.get("related_tasks"), "id", 100);
        List<?> rawCitations = list(result.get("citations"), 20);
        List<Map<String, Object>> citations = new ArrayList<>();
        Set<Map<String, Object>> allowedSources = new LinkedHashSet<>();
        Set<String> citationKeys = new HashSet<>();
        for (int i = 0; i < rawCitations.size(); i++) {
            Map<String, Object> citation = map(rawCitations.get(i));
            if (integer(citation.get("n"), 1, 20) != i + 1) throw invalid();
            String evidenceId = id(citation.get("evidence_id"));
            Map<String, Object> source = evidence.get(evidenceId);
            if (source == null || !sameId(citation, source, "document_id")
                    || !sameId(citation, source, "document_version_id") || !sameId(citation, source, "chunk_key")
                    || integer(citation.get("version_no"), 1, Integer.MAX_VALUE) != integer(source.get("version_no"), 1, Integer.MAX_VALUE)) throw invalid();
            Map<String, Object> locator = locator(source.get("locator"));
            if (!locator.equals(locator(citation.get("locator")))) throw invalid();
            String quote = text(citation.get("quote"), 1000);
            if (!quote.equals(quote.strip()) || !text(source.get("text"), 20_000).contains(quote)
                    || !citationKeys.add(evidenceId + "\u0000" + quote)) throw invalid();
            String title = text(source.get("title"), 500);
            if (!Objects.equals(source.get("title"), citation.get("title"))
                    || !Objects.equals(source.get("section_title"), citation.get("section_title"))) throw invalid();
            Map<String, Object> clean = new LinkedHashMap<>();
            clean.put("n", i + 1); clean.put("document_id", id(source.get("document_id")));
            clean.put("document_version_id", id(source.get("document_version_id")));
            clean.put("version_no", integer(source.get("version_no"), 1, Integer.MAX_VALUE));
            clean.put("title", title); clean.put("locator", locator); clean.put("quote", quote);
            if (source.get("section_title") != null) clean.put("section_title", text(source.get("section_title"), 1000));
            if (citation.get("claim") != null) clean.put("claim", text(citation.get("claim"), 2_000));
            citations.add(clean);
            String section = source.get("section_title") == null ? null : text(source.get("section_title"), 1000);
            String location = (section == null ? "" : section + " / ") + "L" + locator.get("start_line") + "-L" + locator.get("end_line");
            allowedSources.add(sourceRow(source, truncate(location, 1000)));
        }
        Set<String> seenItems = new HashSet<>();
        List<?> designRefs = list(result.get("design_refs"), 20);
        List<Map<String, Object>> cleanDesignRefs = new ArrayList<>();
        for (Object value : designRefs) {
            Map<String, Object> ref = map(value);
            String version = id(ref.get("design_item_version_id"));
            Map<String, Object> item = items.get(version);
            if (item == null || !seenItems.add(version) || !sameId(ref, item, "design_item_id")
                    || !Objects.equals(ref.get("key"), item.get("key")) || !Objects.equals(ref.get("kind"), item.get("kind"))
                    || integer(ref.get("version_no"), 1, Integer.MAX_VALUE) != integer(item.get("version_no"), 1, Integer.MAX_VALUE)
                    || !Objects.equals(ref.get("source"), item.get("source"))) throw invalid();
            Map<String, Object> clean = new LinkedHashMap<>();
            clean.put("design_item_id", id(item.get("design_item_id"))); clean.put("design_item_version_id", version);
            clean.put("key", text(item.get("key"), 256)); clean.put("kind", text(item.get("kind"), 64));
            clean.put("version_no", integer(item.get("version_no"), 1, Integer.MAX_VALUE));
            if (item.get("source") != null) {
                Map<String, Object> itemSource = map(item.get("source"));
                if (allowedVersions.contains(id(itemSource.get("document_version_id")))) {
                    Map<String, Object> safeSource = sourceRow(itemSource, text(itemSource.get("location"), 1000));
                    allowedSources.add(safeSource); clean.put("source", safeSource);
                }
            }
            cleanDesignRefs.add(clean);
        }
        Set<String> seenTasks = new HashSet<>();
        List<?> taskRefs = list(result.get("task_refs"), 20);
        List<Map<String, Object>> cleanTaskRefs = new ArrayList<>();
        for (Object value : taskRefs) {
            Map<String, Object> ref = map(value);
            String taskId = id(ref.get("task_id"));
            Map<String, Object> task = tasks.get(taskId);
            if (task == null || !seenTasks.add(taskId) || !Objects.equals(ref.get("key"), task.get("key"))
                    || !Objects.equals(ref.get("status"), task.get("status"))) throw invalid();
            cleanTaskRefs.add(Map.of("task_id", taskId, "key", text(task.get("key"), 256),
                    "status", text(task.get("status"), 64)));
        }

        List<?> rawSources = list(result.get("sources"), 40);
        Set<Map<String, Object>> actualSources = new LinkedHashSet<>();
        for (Object value : rawSources) {
            Map<String, Object> source = map(value);
            if (!actualSources.add(sourceRow(source, text(source.get("location"), 1000)))) throw invalid();
        }
        if (!actualSources.equals(allowedSources)) throw invalid();
        boolean supported = !citations.isEmpty() || !designRefs.isEmpty() || !taskRefs.isEmpty();
        if (grounded != supported || grounded != "ANSWERED".equals(status)) throw invalid();
        if ("INSUFFICIENT_EVIDENCE".equals(status) && !INSUFFICIENT.equals(answer)) throw invalid();
        if ("OUT_OF_SCOPE".equals(status) && !OUT_OF_SCOPE.equals(answer)) throw invalid();
        Set<Integer> citedNumbers = new HashSet<>();
        var markers = MARKER.matcher(answer);
        while (markers.find()) {
            try {
                int n = Integer.parseInt(markers.group(1));
                if (n < 1 || n > citations.size()) throw invalid();
                citedNumbers.add(n);
            } catch (NumberFormatException e) { throw invalid(); }
        }
        if (citedNumbers.size() != citations.size()) throw invalid();
        Map<String, Object> verification = map(result.get("verification"));
        String level = text(verification.get("level"), 20);
        int claims = integer(verification.get("claims"), 0, Integer.MAX_VALUE);
        if (!Set.of("NONE", "TOKENS", "ENTAILMENT").contains(level)
                || ("NONE".equals(level) != (claims == 0)) || (grounded == "NONE".equals(level))) throw invalid();
        if ("ENTAILMENT".equals(level)) text(verification.get("model"), 256);
        else if (verification.get("model") != null) throw invalid();
        Map<String, Object> cleanVerification = new LinkedHashMap<>();
        cleanVerification.put("level", level); cleanVerification.put("claims", claims);
        if ("ENTAILMENT".equals(level)) cleanVerification.put("model", verification.get("model"));
        List<String> unresolved = texts(result.get("unresolved"), 50);

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("origin", "MODEL"); output.put("model", model); output.put("text", answer);
        output.put("sources", new ArrayList<>(actualSources)); output.put("unresolved", unresolved);
        output.put("usage", cleanUsage); output.put("answer_status", status); output.put("grounded", grounded);
        output.put("citations", citations); output.put("verification", cleanVerification);
        output.put("design_refs", cleanDesignRefs); output.put("task_refs", cleanTaskRefs);
        return output;
    }

    private static Map<String, Object> sourceRow(Map<String, Object> source, String location) {
        return Map.of("document_id", id(source.get("document_id")), "document_version_id", id(source.get("document_version_id")),
                "version_no", integer(source.get("version_no"), 1, Integer.MAX_VALUE), "location", location);
    }
    private static Map<String, Object> locator(Object raw) {
        Map<String, Object> value = map(raw);
        if (!"text_lines".equals(value.get("type"))) throw invalid();
        int start = integer(value.get("start_line"), 1, Integer.MAX_VALUE);
        int end = integer(value.get("end_line"), start, Integer.MAX_VALUE);
        return Map.of("type", "text_lines", "start_line", start, "end_line", end);
    }
    private static boolean sameId(Map<String, Object> a, Map<String, Object> b, String key) {
        return id(a.get(key)).equals(id(b.get(key)));
    }
    private static Map<String, Map<String, Object>> index(Object raw, String key, int limit) {
        Map<String, Map<String, Object>> out = new HashMap<>();
        for (Object value : list(raw, limit)) {
            var row = map(value);
            if (out.putIfAbsent(id(row.get(key)), row) != null) throw invalid();
        }
        return out;
    }
    private static Set<String> stringSet(Object raw, int limit) {
        Set<String> out = new HashSet<>();
        for (Object value : list(raw, limit)) if (!out.add(id(value))) throw invalid();
        return out;
    }
    private static List<String> texts(Object raw, int limit) {
        List<String> out = new ArrayList<>();
        for (Object value : list(raw, limit)) out.add(text(value, 2_000));
        return out;
    }
    private static String id(Object raw) {
        String value = text(raw, 256);
        if (value.chars().anyMatch(Character::isWhitespace)) throw invalid();
        return value;
    }
    private static String text(Object raw, int max) {
        if (!(raw instanceof String value) || value.isBlank() || value.codePointCount(0, value.length()) > max
                || value.indexOf('\0') >= 0) throw invalid();
        return value;
    }
    private static String truncate(String value, int max) {
        return value.codePointCount(0, value.length()) <= max ? value : value.substring(0, value.offsetByCodePoints(0, max));
    }
    private static int integer(Object raw, int min, int max) {
        if (!(raw instanceof Integer || raw instanceof Long)) throw invalid();
        long value = ((Number) raw).longValue();
        if (value < min || value > max) throw invalid();
        return (int) value;
    }
    private static List<?> list(Object raw, int limit) {
        if (!(raw instanceof List<?> values) || values.size() > limit) throw invalid();
        return values;
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object raw) {
        if (!(raw instanceof Map<?, ?> value) || value.keySet().stream().anyMatch(key -> !(key instanceof String))) throw invalid();
        return (Map<String, Object>) value;
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("invalid bee response"); }
}
