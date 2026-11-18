package com.beez.backend.document.service;

import static com.beez.backend.document.service.DocumentValues.hash;
import static com.beez.backend.document.service.DocumentValues.invalid;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Validates the AI response against the immutable normalized source before publication. */
final class DocumentChunkValidator {
    // The md-structure-v1 profile is fixed on both sides; the manifest must echo these limits.
    private static final int MAX_TOKENS = 600, MAX_CHUNKS = 1024;

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) throw invalid("invalid_ai_output");
        return (Map<String, Object>) map;
    }
    private static List<?> array(Object value) {
        if (!(value instanceof List<?> list)) throw invalid("invalid_ai_output");
        return list;
    }
    private static String text(Object value) {
        if (!(value instanceof String s) || s.isBlank()) throw invalid("invalid_ai_output");
        return s;
    }
    private static int integer(Object value) {
        if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue() != n.intValue()) throw invalid("invalid_ai_output");
        return n.intValue();
    }
    private static String digest(Object value) {
        String s = text(value);
        return s.startsWith("sha256:") ? s.substring(7) : s;
    }
    private static void same(Object a, Object b) {
        if (!Objects.equals(a, b)) throw invalid("invalid_chunk_manifest");
    }

    static List<Map<String, Object>> validate(String source, Map<String, Object> input, Map<String, Object> result) {
        var manifest = object(result.get("manifest"));
        same(manifest.get("project_id"), input.get("project_id"));
        same(manifest.get("document_version_id"), input.get("document_version_id"));
        same(manifest.get("content_hash"), digest(input.get("content_hash")));
        same(manifest.get("text_hash"), digest(input.get("text_hash")));
        same(manifest.get("normalization_version"), input.get("normalization_profile"));
        same(manifest.get("parser_profile"), "text/plain".equals(input.get("media_type")) ? "plain-v1" : "markdown-v1");
        same(manifest.get("parser_version"), result.get("parser_version"));
        text(manifest.get("chunker_version"));
        var profile = object(manifest.get("chunking_profile"));
        same(profile.get("profile_id"), input.get("chunking_profile_id"));
        text(profile.get("tokenizer_id"));
        if (integer(profile.get("max_tokens")) != MAX_TOKENS || integer(profile.get("max_chunks")) != MAX_CHUNKS
                || !Boolean.FALSE.equals(profile.get("include_headings")) || !Boolean.FALSE.equals(profile.get("include_table_headers"))) {
            throw invalid("invalid_chunk_manifest");
        }

        var raw = array(result.get("chunks"));
        if (raw.isEmpty() || raw.size() > MAX_CHUNKS) throw invalid("invalid_chunk_count");
        var expected = array(manifest.get("expected_chunk_keys"));
        if (expected.size() != raw.size()) throw invalid("invalid_chunk_manifest");
        int[] points = source.codePoints().toArray();
        var starts = new ArrayList<Integer>(); starts.add(0);
        for (int i = 0; i < points.length; i++) if (points[i] == '\n') starts.add(i + 1);
        if (starts.getLast() != points.length) starts.add(points.length);
        // An LF-terminated source has no extra empty line.
        int lines = starts.size() - 1;
        boolean[] covered = new boolean[points.length];
        var chunks = new ArrayList<Map<String, Object>>(); var keys = new HashSet<String>();
        int previousEnd = 0;
        for (int no = 0; no < raw.size(); no++) {
            var chunk = object(raw.get(no));
            String key = text(chunk.get("chunk_key"));
            if (integer(chunk.get("chunk_no")) != no || !keys.add(key) || !Objects.equals(expected.get(no), key)) throw invalid("invalid_chunk_keys");
            same(chunk.get("document_version_id"), input.get("document_version_id"));
            String embeddingText = text(chunk.get("embedding_text"));
            int tokens = integer(chunk.get("token_count"));
            if (tokens <= 0 || tokens > MAX_TOKENS) throw invalid("invalid_chunk_tokens");
            var spans = array(chunk.get("source_spans"));
            if (spans.isEmpty()) throw invalid("invalid_source_span");
            var restored = new StringBuilder(); int firstLine = 0, lastLine = 0;
            for (Object item : spans) {
                var span = object(item); text(span.get("block_id"));
                int start = integer(span.get("start_offset")), end = integer(span.get("end_offset"));
                if (start < previousEnd || start < 0 || end <= start || end > points.length) throw invalid("invalid_source_span");
                int startLine = lineFor(starts, start), endLine = lineFor(starts, end - 1);
                checkLocator(span.get("locator"), startLine, endLine, lines);
                if (firstLine == 0) firstLine = startLine;
                lastLine = endLine;
                mark(covered, start, end);
                restored.append(new String(points, start, end - start));
                previousEnd = end;
            }
            checkLocator(chunk.get("locator"), firstLine, lastLine, lines);
            if (!restored.toString().equals(chunk.get("text")) || !restored.toString().equals(embeddingText)) throw invalid("invalid_chunk_text");
            for (Object item : array(chunk.get("context_refs"))) {
                var ref = object(item); text(ref.get("block_id"));
                if (!(ref.get("role") instanceof String role) || !Set.of("heading", "table_header").contains(role)
                        || !Boolean.FALSE.equals(ref.get("applied"))) throw invalid("invalid_context_reference");
                var locator = object(ref.get("locator")); int start = integer(locator.get("start_line")), end = integer(locator.get("end_line"));
                checkLocator(locator, start, end, lines);
                String referenced = new String(points, starts.get(start - 1), starts.get(end) - starts.get(start - 1));
                if (!digest(hash(referenced.getBytes(StandardCharsets.UTF_8))).equals(ref.get("text_hash"))) throw invalid("invalid_context_reference");
            }
            chunks.add(chunk);
        }
        for (Object item : array(result.get("excluded_ranges"))) {
            var excluded = object(item); var locator = object(excluded.get("locator"));
            int start = integer(locator.get("start_line")), end = integer(locator.get("end_line"));
            checkLocator(locator, start, end, lines);
            String reason = text(excluded.get("reason"));
            if (!Set.of("blank", "thematic_break", "container_marker").contains(reason)) throw invalid("invalid_excluded_range");
            if ("text/plain".equals(input.get("media_type")) && !"blank".equals(reason)) throw invalid("invalid_excluded_range");
            for (int line = start; line <= end; line++) {
                String value = new String(points, starts.get(line - 1), starts.get(line) - starts.get(line - 1));
                if (!validExcludedLine(value, reason)) throw invalid("invalid_excluded_range");
            }
            mark(covered, starts.get(start - 1), starts.get(end));
        }
        for (boolean owned : covered) if (!owned) throw invalid("missing_source_ranges");
        return chunks;
    }

    private static int lineFor(List<Integer> starts, int offset) {
        int index = Collections.binarySearch(starts, offset);
        return index >= 0 ? index + 1 : -index - 1;
    }
    private static void checkLocator(Object value, int start, int end, int lines) {
        var locator = object(value);
        if (!"text_lines".equals(locator.get("type")) || start < 1 || end < start || end > lines
                || integer(locator.get("start_line")) != start || integer(locator.get("end_line")) != end) throw invalid("invalid_chunk_locator");
    }
    private static void mark(boolean[] covered, int start, int end) {
        for (int i = start; i < end; i++) {
            if (covered[i]) throw invalid("overlapping_source_ranges");
            covered[i] = true;
        }
    }
    static boolean validExcludedLine(String line, String reason) {
        String s = line.endsWith("\n") ? line.substring(0, line.length() - 1) : line;
        if (reason.equals("blank")) return skipSpace(s, 0) == s.length();
        if (reason.equals("container_marker")) return containerMarker(s);
        // Scan the final run once, then validate the container prefix once.
        // Retrying a suffix scan at each list marker makes malformed long lines quadratic.
        int end = s.length() - 1;
        while (end >= 0 && (s.charAt(end) == ' ' || s.charAt(end) == '\t')) end--;
        if (end < 0) return false;
        char marker = s.charAt(end);
        if (marker != '*' && marker != '-' && marker != '_') return false;
        int count = 0, cursor = end;
        while (cursor >= 0 && (s.charAt(cursor) == marker || s.charAt(cursor) == ' ' || s.charAt(cursor) == '\t')) {
            if (s.charAt(cursor) == marker) count++;
            cursor--;
        }
        if (count < 3) return false;
        int runStart = skipSpace(s, cursor + 1);
        int at = 0;
        while (at < runStart) {
            at = skipSpace(s, at);
            if (at >= runStart) break;
            char prefix = s.charAt(at);
            if (prefix == '>') { at++; continue; }
            int next = at;
            if (prefix == '-' || prefix == '+' || prefix == '*') next++;
            else {
                while (next < runStart && next - at < 9 && s.charAt(next) >= '0' && s.charAt(next) <= '9') next++;
                if (next == at || next >= runStart || (s.charAt(next) != '.' && s.charAt(next) != ')')) return false;
                next++;
            }
            if (next >= runStart || (s.charAt(next) != ' ' && s.charAt(next) != '\t')) return false;
            at = next + 1;
        }
        return true;
    }
    private static int skipSpace(String value, int at) {
        while (at < value.length() && (value.charAt(at) == ' ' || value.charAt(at) == '\t')) at++;
        return at;
    }
    private static boolean containerMarker(String value) {
        int at = skipSpace(value, 0);
        boolean quote = false;
        while (at < value.length() && value.charAt(at) == '>') {
            quote = true;
            at = skipSpace(value, at + 1);
        }
        if (at == value.length()) return quote;
        char c = value.charAt(at);
        if (c == '-' || c == '+' || c == '*') at++;
        else {
            int start = at;
            while (at < value.length() && at - start < 9 && value.charAt(at) >= '0' && value.charAt(at) <= '9') at++;
            if (at == start || at == value.length() || (value.charAt(at) != '.' && value.charAt(at) != ')')) return false;
            at++;
        }
        return skipSpace(value, at) == value.length();
    }
    private DocumentChunkValidator() { }
}
