package com.beez.backend.document.support;

import java.util.*;

/** Model-free fixtures for BE contract tests; never installed in a production Gateway. */
public final class DocumentReviewFixtures {
    public static Map<String, Object> result(Map<String, Object> input, List<?> suggestions) {
        var output = new HashMap<String, Object>();
        for (String field : List.of("document_id", "revision", "content_hash", "reference_hash", "request_key")) {
            output.put(field, input.get(field));
        }
        var refs = (List<Map<String, Object>>) input.get("references");
        var steps = new ArrayList<Map<String, Object>>();
        var used = new ArrayList<String>();
        boolean partial = false;
        for (String code : List.of("BODY", "API_SPEC", "ERD", "REQ_SPEC")) {
            var ids = refs.stream().filter(r -> code.equals(r.get("kind")))
                    .map(r -> r.get("reference_id").toString()).sorted().toList();
            String state = "COMPLETE", reason = "REVIEWED";
            if ("BODY".equals(code)) ids = List.of();
            else if (code.equals(input.get("kind"))) { state = "SKIPPED"; reason = "SAME_KIND"; ids = List.of(); }
            else if (ids.isEmpty()) { state = "SKIPPED"; reason = "REFERENCE_UNAVAILABLE"; partial = true; }
            used.addAll(ids);
            steps.add(new HashMap<>(Map.of("step", code, "status", state, "reason", reason, "reference_ids", ids)));
        }
        output.put("steps", steps); output.put("references_used", used.stream().sorted().toList());
        output.put("review_status", partial ? "PARTIAL" : "COMPLETE"); output.put("summary", "문서 검토 완료");
        output.put("suggestions", suggestions.stream().map(entry -> {
            var row = new HashMap<>((Map<String, Object>) entry);
            row.putIfAbsent("type", "PATCH"); row.putIfAbsent("step", "BODY"); row.putIfAbsent("sources", List.of());
            return row;
        }).toList());
        return Map.of("origin", "MODEL", "result", output, "usage", List.of(Map.of(
                "request_key", input.get("request_key"), "feature", "BEE_DRAFT", "model", "fixture-model",
                "latency_ms", 1, "failed", false, "tokens_known", false)));
    }

    private DocumentReviewFixtures() { }
}
