package com.beez.backend.meeting.service;

import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.text.NormalizedText;
import com.beez.backend.meeting.dto.SummaryResult;
import com.beez.backend.meeting.dto.SummaryResult.ActionItem;
import com.beez.backend.meeting.dto.SummaryResult.Decision;
import com.beez.backend.meeting.dto.SummaryResult.DocumentChange;
import com.beez.backend.meeting.dto.SummaryResult.Locator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongPredicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

/**
 * AI-A08 SUMMARY 출력 검증·정규화 (S15P31A108-235). AI 출력은 믿지 않고 모두 입력과 대조한다.
 *
 * <ul>
 *   <li>쓸 수 없는 출력(구조 불량, origin 불량, 한 목록이 통째로 불량)은 502 {@code ai_unavailable} 이다.</li>
 *   <li>일부만 틀린 항목은 그 항목만 버리고 {@code unresolved} 에 개수를 남긴다.</li>
 *   <li>문서 변경은 입력으로 보낸 문서·버전이어야 하고, 줄 범위가 원문 안이며, {@code before} 가 그 범위와 정확히 같아야 한다.
 *       같은 문서 안에서 줄 범위가 겹치면 뒤의 것을 버린다. 근거 기록은 입력에 실은 기록 ID 만 남긴다.</li>
 * </ul>
 * 줄은 {@link NormalizedText#lines} 로 센다 — 이후 줄 편집도 같은 함수를 쓴다.
 */
final class SummaryResultValidator {
    private static final Logger log = LoggerFactory.getLogger(SummaryResultValidator.class);

    static final int MAX_TEXT = 4000;
    static final int MAX_LIST = 100;
    static final int MAX_AFTER = 100_000;

    private SummaryResultValidator() { }

    /** 입력으로 보낸 문서 한 건. 줄은 정규화한 원문의 줄 */
    record DocInfo(long documentId, long versionId, int versionNo, List<String> lines, boolean endsWithLf) { }

    /** 입력에 실제로 실은 기록·문서와 활성 멤버 판정 */
    record Context(Set<Long> entryIds, Map<Long, DocInfo> documents, LongPredicate activeMember) { }

    /** text 는 AI 가 준 요약문(없으면 null). sources·usage 는 AI 가 준 객체 중 쓸 수 있는 것 */
    record Validated(String origin, String model, String text, SummaryResult result, List<Map<String, Object>> sources,
                     List<String> unresolved, List<Map<String, Object>> usage) { }

    static Validated validate(Map<String, Object> response, Context ctx) {
        if (response == null || !(response.get("result") instanceof Map<?, ?> result)) throw invalid("result 가 없습니다");
        String origin = response.get("origin") instanceof String o ? o : null;
        if (!"MODEL".equals(origin) && !"TEMPLATE".equals(origin)) throw invalid("origin 이 MODEL/TEMPLATE 이 아닙니다");
        List<?> rawConflicts = list(result, "conflicts");
        List<?> rawDecisions = list(result, "decisions");
        List<?> rawChanges = list(result, "document_changes");
        List<?> rawActions = list(result, "action_items");

        List<String> unresolved = new ArrayList<>();
        if (result.get("unresolved") instanceof List<?> items) {
            for (Object item : items) {
                if (item instanceof String s && !s.isBlank() && unresolved.size() < MAX_LIST) unresolved.add(clip(s.strip()));
            }
        }

        List<String> conflicts = new ArrayList<>();
        int droppedConflicts = 0;
        for (Object item : rawConflicts) {
            String text = textOf(item);
            if (text == null || conflicts.size() >= MAX_LIST) droppedConflicts++; else conflicts.add(text);
        }
        List<Decision> decisions = new ArrayList<>();
        int droppedDecisions = 0;
        for (Object item : rawDecisions) {
            Decision decision = decision(item, ctx);
            if (decision == null || decisions.size() >= MAX_LIST) droppedDecisions++; else decisions.add(decision);
        }
        List<DocumentChange> changes = new ArrayList<>();
        int droppedChanges = 0;
        for (Object item : rawChanges) {
            DocumentChange change = change(item, ctx);
            if (change == null || changes.size() >= MAX_LIST || overlaps(changes, change)) droppedChanges++; else changes.add(change);
        }
        List<ActionItem> actions = new ArrayList<>();
        int droppedActions = 0;
        int unknownMembers = 0;
        for (Object item : rawActions) {
            ActionItem action = action(item, ctx);
            if (action == null || actions.size() >= MAX_LIST) {
                droppedActions++;
            } else {
                if (item instanceof Map<?, ?> m && m.get("member_id") != null && action.memberId() == null) unknownMembers++;
                actions.add(action);
            }
        }
        // AI 가 있다고 한 목록이 하나도 쓸 수 없으면 일부만 틀린 게 아니라 쓸 수 없는 출력이다
        requireSomeKept("conflicts", rawConflicts.size(), conflicts.size());
        requireSomeKept("decisions", rawDecisions.size(), decisions.size());
        requireSomeKept("document_changes", rawChanges.size(), changes.size());
        requireSomeKept("action_items", rawActions.size(), actions.size());

        addDropped(unresolved, "충돌", droppedConflicts);
        addDropped(unresolved, "결정", droppedDecisions);
        addDropped(unresolved, "문서 변경", droppedChanges);
        addDropped(unresolved, "할 일", droppedActions);
        if (unknownMembers > 0) unresolved.add("할 일 " + unknownMembers + "건의 담당자는 이 프로젝트의 활성 멤버가 아니라 제외했어요.");

        List<Map<String, Object>> sources = new ArrayList<>();
        int rawSources = 0;
        if (result.get("sources") instanceof List<?> items) {
            for (Object item : items) {
                rawSources++;
                Map<String, Object> source = source(item, ctx);
                if (source != null) sources.add(source);
            }
        }
        if (rawSources > sources.size()) {
            unresolved.add("근거 " + (rawSources - sources.size()) + "건이 이 요청의 문서·버전과 맞지 않거나 필수 값이 없어 제외했어요.");
        }

        String text = result.get("text") instanceof String s && !s.isBlank() ? clip(s.strip()) : null;
        String model = response.get("model") instanceof String m && !m.isBlank() ? m : null;
        List<Map<String, Object>> usage = null;
        if (response.get("usage") instanceof List<?> lines) {
            usage = new ArrayList<>();
            for (Object line : lines) {
                if (line instanceof Map<?, ?> m) {
                    @SuppressWarnings("unchecked") Map<String, Object> entry = (Map<String, Object>) m;
                    usage.add(entry);
                }
            }
        }
        return new Validated(origin, model, text, new SummaryResult(conflicts, decisions, changes, actions), sources, unresolved, usage);
    }

    // ---- 항목 ----

    private static Decision decision(Object item, Context ctx) {
        if (item instanceof String) {
            String text = textOf(item);
            return text == null ? null : new Decision(text, null);
        }
        if (item instanceof Map<?, ?> m) {
            String text = textOf(m.get("text"));
            if (text == null) return null;
            Long member = id(m.get("decided_by_member_id"));
            return new Decision(text, member != null && ctx.activeMember().test(member) ? Long.toString(member) : null);
        }
        return null;
    }

    private static DocumentChange change(Object item, Context ctx) {
        if (!(item instanceof Map<?, ?> m)) return null;
        Long documentId = id(m.get("document_id"));
        DocInfo document = documentId == null ? null : ctx.documents().get(documentId);
        if (document == null) return null;
        Long base = id(m.get("base_version_id"));
        if (base == null || base != document.versionId()) return null;
        if (!(m.get("locator") instanceof Map<?, ?> locator) || !"text_lines".equals(locator.get("type"))) return null;
        Integer start = integer(locator.get("start_line"));
        Integer end = integer(locator.get("end_line"));
        if (start == null || end == null || start < 1 || end < start || end > document.lines().size()) return null;
        if (!(m.get("before") instanceof String before) || !before.equals(NormalizedText.range(document.lines(), document.endsWithLf(), start, end))) return null;
        if (!(m.get("after") instanceof String after) || after.length() > MAX_AFTER) return null;
        Set<String> evidence = new LinkedHashSet<>();
        if (m.get("evidence_entry_ids") instanceof List<?> ids) {
            for (Object value : ids) {
                Long entry = id(value);
                if (entry != null && ctx.entryIds().contains(entry)) evidence.add(Long.toString(entry));
            }
        }
        if (evidence.isEmpty()) return null;
        return new DocumentChange(Long.toString(documentId), Long.toString(base), new Locator("text_lines", start, end), before, after,
                List.copyOf(evidence));
    }

    private static boolean overlaps(List<DocumentChange> kept, DocumentChange candidate) {
        for (DocumentChange other : kept) {
            if (!other.documentId().equals(candidate.documentId())) continue;
            if (candidate.locator().startLine() <= other.locator().endLine() && other.locator().startLine() <= candidate.locator().endLine()) return true;
        }
        return false;
    }

    private static ActionItem action(Object item, Context ctx) {
        if (!(item instanceof Map<?, ?> m)) return null;
        String text = textOf(m.get("text"));
        if (text == null) return null;
        Long member = id(m.get("member_id"));
        return new ActionItem(text, member != null && ctx.activeMember().test(member) ? Long.toString(member) : null);
    }

    /** 근거 한 건이 유효하면 정규화한 네 필드만, 아니면 null. 요청에 실은 문서·버전과 같아야 한다 */
    private static Map<String, Object> source(Object item, Context ctx) {
        if (!(item instanceof Map<?, ?> m)) return null;
        Long documentId = id(m.get("document_id"));
        DocInfo document = documentId == null ? null : ctx.documents().get(documentId);
        Long version = id(m.get("document_version_id"));
        if (document == null || version == null || version != document.versionId()) return null;
        if (!(m.get("version_no") instanceof Number number) || number.doubleValue() != document.versionNo()) return null;
        if (!(m.get("location") instanceof String location) || location.isBlank()) return null;
        var source = new LinkedHashMap<String, Object>();
        source.put("document_id", Long.toString(documentId));
        source.put("document_version_id", Long.toString(version));
        source.put("version_no", document.versionNo());
        source.put("location", location);
        return source;
    }

    // ---- 작은 도우미 ----

    private static List<?> list(Map<?, ?> result, String name) {
        if (!(result.get(name) instanceof List<?> list)) throw invalid(name + " 가 배열이 아닙니다");
        return list;
    }

    private static void requireSomeKept(String name, int raw, int kept) {
        if (raw > 0 && kept == 0) throw invalid(name + " 가 모두 쓸 수 없는 값입니다");
    }

    private static void addDropped(List<String> unresolved, String label, int dropped) {
        if (dropped > 0) unresolved.add(label + " " + dropped + "건이 형식이 맞지 않거나 입력과 맞지 않아 제외했어요.");
    }

    /** 비어 있지 않고 4,000자 이하인 문자열이면 앞뒤 공백을 뗀 값, 아니면 null */
    private static String textOf(Object value) {
        if (!(value instanceof String s) || s.isBlank()) return null;
        String stripped = s.strip();
        return stripped.length() > MAX_TEXT ? null : stripped;
    }

    private static String clip(String s) {
        return s.length() > MAX_TEXT ? s.substring(0, MAX_TEXT) : s;
    }

    /** 문자열("123")이든 정수든 양의 ID 로 읽고, 아니면 null */
    private static Long id(Object value) {
        if (value instanceof String s && s.matches("[0-9]{1,18}")) {
            long id = Long.parseLong(s);
            return id > 0 ? id : null;
        }
        if (value instanceof Integer || value instanceof Long) {
            long id = ((Number) value).longValue();
            return id > 0 ? id : null;
        }
        return null;
    }

    private static Integer integer(Object value) {
        if (value instanceof Integer i) return i;
        if (value instanceof Long l && l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) return l.intValue();
        return null;
    }

    private static ApiException invalid(String reason) {
        log.warn("AI-A08 output unusable: {}", reason);
        return new ApiException(HttpStatus.BAD_GATEWAY, "ai_unavailable", AiMeetingSummaryAdapter.UNAVAILABLE);
    }
}
