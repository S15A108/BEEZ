package com.beez.backend.impactanalysis;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.json.JsonMapper;

/**
 * A bounded, deliberately incomplete lexical search seed for meaning analysis.
 * It produces candidates only, never confirmed graph relationships.
 */
public final class ImpactKeywordSearch {
    public static final int MAX_TERMS = 12;
    public static final int MAX_RESULTS = 20;
    private static final int MAX_JSON_CHARS = 262144;
    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}_-]+");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> COMMON_KEYS = Set.of(
            "method", "path", "description", "title", "endpoint", "name",
            "type", "field", "fields", "response", "request", "access",
            "content", "schema_version", "kind", "status");
    private static final Set<String> COMMON_WORDS = Set.of(
            "get", "post", "put", "patch", "delete", "string", "number",
            "integer", "boolean", "long", "null", "true", "false", "api");

    public enum Status { SEARCHED, NO_USABLE_TERMS, INPUT_TOO_LARGE }

    public record Terms(List<String> values, Status status, boolean termsTruncated) {
        public Terms { values = List.copyOf(values); }
    }

    public record Coverage(Status status, int termCount, boolean termsTruncated,
                           boolean designsTruncated, boolean tasksTruncated, List<Long> candidateTaskIds) {
        public Coverage { candidateTaskIds = List.copyOf(candidateTaskIds); }
        public Coverage(Status status, int termCount, boolean termsTruncated,
                        boolean designsTruncated, boolean tasksTruncated) {
            this(status, termCount, termsTruncated, designsTruncated, tasksTruncated, List.of());
        }
    }

    private ImpactKeywordSearch() {}

    /** Values are extracted from JSON content, not keys, and never become SQL text. */
    public static Terms terms(String reason, List<ImpactContextQuery.Change> changes,
                              List<ImpactContextQuery.ApprovedItem> approved) {
        Set<String> words = new LinkedHashSet<>();
        if (reason != null && reason.length() > MAX_JSON_CHARS) {
            return new Terms(List.of(), Status.INPUT_TOO_LARGE, false);
        }
        for (var change : changes) {
            if (!addJson(change.proposedContent(), words)) {
                return new Terms(List.of(), Status.INPUT_TOO_LARGE, false);
            }
        }
        for (var item : approved) {
            if (item.title() != null && item.title().length() > MAX_JSON_CHARS) {
                return new Terms(List.of(), Status.INPUT_TOO_LARGE, false);
            }
            addWords(item.title(), words);
            if (!addJson(item.content(), words)) {
                return new Terms(List.of(), Status.INPUT_TOO_LARGE, false);
            }
        }
        addWords(reason, words);
        List<String> all = new ArrayList<>(words);
        boolean truncated = all.size() > MAX_TERMS;
        List<String> selected = all.subList(0, Math.min(all.size(), MAX_TERMS));
        return new Terms(selected, selected.isEmpty() ? Status.NO_USABLE_TERMS : Status.SEARCHED, truncated);
    }

    /** SQL uses bound terms via strpos, so '%' and '_' are not wildcards. */
    public static String predicate(String expression, int count) {
        if (count < 1 || count > MAX_TERMS) throw new IllegalArgumentException("invalid term count");
        var parts = new ArrayList<String>();
        for (int i = 0; i < count; i++) {
            parts.add("strpos(lower(" + expression + "), :term" + i + ") > 0");
        }
        return "(" + String.join(" or ", parts) + ")";
    }

    private static boolean addJson(String input, Set<String> words) {
        if (input == null) return true;
        if (input.length() > MAX_JSON_CHARS) return false;
        try {
            addValue(JSON.readValue(input, Object.class), words, 0);
            return true;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Malformed search input", e);
        }
    }

    private static void addValue(Object value, Set<String> words, int depth) {
        if (depth > 32) throw new IllegalArgumentException("Search input too deep");
        if (value instanceof String text) addWords(text, words);
        else if (value instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                if (entry.getKey() instanceof String key
                        && !COMMON_KEYS.contains(key.toLowerCase(Locale.ROOT))) addWords(key, words);
                addValue(entry.getValue(), words, depth + 1);
            }
        } else if (value instanceof List<?> list) {
            for (Object v : list) addValue(v, words, depth + 1);
        }
    }

    private static void addWords(String text, Set<String> words) {
        if (text == null || text.length() > MAX_JSON_CHARS) return;
        var matcher = TOKEN.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String word = matcher.group();
            if (word.codePointCount(0, word.length()) >= 2 && word.length() <= 128
                    && word.codePoints().anyMatch(Character::isLetter)
                    && !COMMON_WORDS.contains(word)) words.add(word);
        }
    }
}
