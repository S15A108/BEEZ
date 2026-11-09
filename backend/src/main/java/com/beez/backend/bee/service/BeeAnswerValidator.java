package com.beez.backend.bee.service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 가 돌려준 비비 답변을 BE 가 보낸 집합과 대조한다 (BEE-A01 "권한 밖 문서가 근거로 나오지 않는다").
 * AI 응답은 믿지 않는다 — 인용·출처·설계 항목·작업이 모두 우리가 보낸 허용 집합 안에 있어야 하고, 하나라도 어긋나면
 * 일부만 지우지 않고 응답 전체를 거부한다(지우면 본문 [n]·grounded 가 인용과 어긋난다).
 * 거부 사유({@link Rejected#getMessage})는 서버 로그 전용이며 질문·근거 원문을 담지 않는다.
 */
public final class BeeAnswerValidator {

    private static final Set<String> STATUSES = Set.of("ANSWERED", "INSUFFICIENT_EVIDENCE", "OUT_OF_SCOPE");
    private static final Pattern MARK = Pattern.compile("\\[(\\d{1,9})]");
    static final int MAX_TEXT = 10_000;

    /** 보낸 근거 한 건. 인용은 이 값과 정확히 같아야 한다 */
    public record SentEvidence(String documentId, String documentVersionId, int versionNo, String chunkKey,
                               Map<String, Object> locator, String title, String text) {
    }

    /** 보낸 문서 버전의 문서 ID·버전 번호. 출처(sources)는 이 값과 정확히 같아야 한다 */
    public record SentSource(String documentId, int versionNo) {
    }

    /**
     * BE 가 AI 에 보낸 집합.
     * @param versionIds baseline 의 문서 버전 ID(문자열). 출처는 모두 이 안이어야 한다
     * @param evidence evidence_id → 보낸 근거
     * @param versionSources 버전 ID → 보낸 문서 ID·버전 번호(보낸 근거와 승인 항목의 출처에서 모은다)
     * @param approvedVersions 보낸 승인 항목: 버전 ID → design_item_id
     * @param taskIds 보낸 관련 작업 ID
     */
    public record Sent(String baselineId, String embeddingProfile, Set<String> versionIds,
                       Map<String, SentEvidence> evidence, Map<String, SentSource> versionSources,
                       Map<String, String> approvedVersions, Set<String> taskIds) {
    }

    /** 허용 집합과 어긋나거나 계약에 맞지 않는 답변 */
    public static final class Rejected extends RuntimeException {
        public Rejected(String reason) { super(reason); }
    }

    private BeeAnswerValidator() { }

    /** @return 검증을 통과한 {@code result}. 어긋나면 {@link Rejected} */
    public static Map<String, Object> validate(Map<String, Object> response, Sent sent) {
        if (response == null || !"MODEL".equals(response.get("origin"))) throw new Rejected("origin_not_model");
        Map<String, Object> result = map(response.get("result"), "result");

        String status = string(result.get("answer_status"), "answer_status");
        if (!STATUSES.contains(status)) throw new Rejected("answer_status_unknown");
        String text = string(result.get("text"), "text");
        if (text.isBlank() || text.length() > MAX_TEXT) throw new Rejected("text_length");

        Map<String, Object> scope = map(result.get("scope"), "scope");
        if (!"APPROVED".equals(scope.get("mode")) || !sent.baselineId().equals(String.valueOf(scope.get("baseline_id")))
                || !sent.versionIds().equals(strings(scope.get("document_version_ids"), "scope.document_version_ids"))) {
            throw new Rejected("scope_mismatch");
        }
        if (!sent.embeddingProfile().equals(result.get("embedding_profile_id"))) throw new Rejected("profile_mismatch");

        List<?> citations = list(result.get("citations"), "citations");
        Set<Integer> numbers = new HashSet<>();
        for (Object value : citations) {
            Map<String, Object> citation = map(value, "citation");
            SentEvidence known = sent.evidence().get(string(citation.get("evidence_id"), "citation.evidence_id"));
            if (known == null) throw new Rejected("citation_unknown_evidence");
            if (!known.documentId().equals(String.valueOf(citation.get("document_id")))
                    || !known.documentVersionId().equals(String.valueOf(citation.get("document_version_id")))
                    || !known.chunkKey().equals(citation.get("chunk_key"))
                    || !sameLocator(known.locator(), citation.get("locator"))) {
                throw new Rejected("citation_mismatch");
            }
            String quote = string(citation.get("quote"), "citation.quote").strip();

            if (quote.isEmpty()) throw new Rejected("citation_quote_blank");

            // 인용문은 보낸 청크 원문의 정확한 부분 문자열이어야 한다(계약 #135). 원문에 없는 문장은 거부한다

            if (!known.text().contains(quote)) throw new Rejected("citation_quote_not_in_evidence");

            if (!(citation.get("version_no") instanceof Number v) || v.intValue() != known.versionNo()) throw new Rejected("citation_mismatch");
            if (!(citation.get("n") instanceof Number n) || !numbers.add(n.intValue())) throw new Rejected("citation_number");
        }
        for (int n = 1; n <= citations.size(); n++) if (!numbers.contains(n)) throw new Rejected("citation_number_gap");

        Matcher marks = MARK.matcher(text);
        while (marks.find()) {
            int n = Integer.parseInt(marks.group(1));
            if (n < 1 || n > citations.size()) throw new Rejected("text_mark_out_of_range");
        }

        List<?> sources = list(result.get("sources"), "sources");
        for (Object value : sources) {
            Map<String, Object> source = map(value, "source");
            String versionId = String.valueOf(source.get("document_version_id"));

            if (!sent.versionIds().contains(versionId)) throw new Rejected("source_out_of_scope");

            // 허용된 버전 번호만 맞아서는 부족하다 — 그 버전의 문서 ID·버전 번호도 우리가 보낸 값과 같아야 한다

            SentSource known = sent.versionSources().get(versionId);

            if (known == null || !known.documentId().equals(String.valueOf(source.get("document_id")))

                    || !(source.get("version_no") instanceof Number no) || no.intValue() != known.versionNo()) {

                throw new Rejected("source_mismatch");

            }
            string(source.get("location"), "source.location");
        }

        List<?> designRefs = list(result.get("design_refs"), "design_refs");
        for (Object value : designRefs) {
            Map<String, Object> ref = map(value, "design_ref");
            String item = sent.approvedVersions().get(String.valueOf(ref.get("design_item_version_id")));
            if (item == null || !item.equals(String.valueOf(ref.get("design_item_id")))) throw new Rejected("design_ref_unknown");
        }
        List<?> taskRefs = list(result.get("task_refs"), "task_refs");
        for (Object value : taskRefs) {
            if (!sent.taskIds().contains(String.valueOf(map(value, "task_ref").get("task_id")))) throw new Rejected("task_ref_unknown");
        }

        boolean hasGround = !citations.isEmpty() || !designRefs.isEmpty() || !taskRefs.isEmpty();
        boolean answered = "ANSWERED".equals(status);
        if (!(result.get("grounded") instanceof Boolean grounded) || grounded != (answered && hasGround)) throw new Rejected("grounded_mismatch");
        if (!answered && hasGround) throw new Rejected("ground_without_answer");
        if (answered && !hasGround) throw new Rejected("answered_without_ground");

        strings(result.getOrDefault("unresolved", List.of()), "unresolved");
        strings(result.getOrDefault("notices", List.of()), "notices");
        return result;
    }

    private static boolean sameLocator(Map<String, Object> known, Object given) {
        if (!(given instanceof Map<?, ?> locator)) return false;
        return number(known.get("start_line")) == number(locator.get("start_line"))
                && number(known.get("end_line")) == number(locator.get("end_line"));
    }

    private static long number(Object value) {
        return value instanceof Number n ? n.longValue() : Long.MIN_VALUE;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value, String name) {
        if (!(value instanceof Map<?, ?> m)) throw new Rejected(name + "_not_object");
        return (Map<String, Object>) m;
    }

    private static List<?> list(Object value, String name) {
        if (!(value instanceof List<?> l)) throw new Rejected(name + "_not_array");
        return l;
    }

    private static String string(Object value, String name) {
        if (!(value instanceof String s)) throw new Rejected(name + "_not_string");
        return s;
    }

    private static Set<String> strings(Object value, String name) {
        Set<String> out = new HashSet<>();
        for (Object item : list(value, name)) out.add(string(item, name));
        return out;
    }
}
