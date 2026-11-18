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

    /**
     * 검토를 접수하고 워커를 돌려 끝까지 처리한 뒤 결과를 돌려준다(S15P31A108-391). 워커가 실패로 끝내면 남긴 오류 코드로 던져,
     * 동기 호출이던 때 시험이 보던 오류 코드를 그대로 확인할 수 있게 한다.
     */
    public static com.beez.backend.document.dto.DocumentBeeReviewResponse reviewNow(
            com.beez.backend.document.service.DocumentReviewService reviews, org.springframework.jdbc.core.JdbcTemplate jdbc,
            com.beez.backend.common.auth.CallerContext caller, long project, long document, long revision, String key) {
        var accepted = reviews.review(caller, project, document, revision, key);
        if (accepted.review() != null) return accepted.review();
        while (reviews.runNextReview()) { /* 접수된 작업을 모두 처리한다 */ }
        var rows = jdbc.queryForList("SELECT status,error_code FROM document_bee_review_request WHERE document_id=? AND account_id=? AND request_key=?",
                document, caller.accountId(), key);
        if (!rows.isEmpty() && "FAILED".equals(rows.getFirst().get("status"))) {
            String code = rows.getFirst().get("error_code").toString();
            var status = switch (code) {
                case "review_timeout" -> org.springframework.http.HttpStatus.GATEWAY_TIMEOUT;
                case "review_unavailable", "invalid_ai_output" -> org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;
                default -> org.springframework.http.HttpStatus.CONFLICT;
            };
            throw new com.beez.backend.common.api.ApiException(status, code, "워커가 검토를 실패로 끝냈습니다.");
        }
        var done = reviews.review(caller, project, document, revision, key);
        if (done.review() == null) throw new IllegalStateException("워커를 돌렸는데도 검토가 끝나지 않았습니다: " + done.request());
        return done.review();
    }

    private DocumentReviewFixtures() { }
}
