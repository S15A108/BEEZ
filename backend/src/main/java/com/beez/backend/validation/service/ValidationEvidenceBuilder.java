package com.beez.backend.validation.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Restores only explicit design-to-source line ranges; search hits do not prove relationships. */
@Service
public class ValidationEvidenceBuilder {
    // The existing design-item API accepts a display location. Only this exact,
    // explicit 1-based line notation can be restored without guessing.
    private static final Pattern DISPLAY_LINES = Pattern.compile("^([1-9][0-9]*)\\s*(?:~\\s*([1-9][0-9]*))?\\s*행$");
    private static final int MAX_EXCERPT_BYTES = 16_000;
    private static final int MAX_TOTAL_BYTES = 100_000;
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public ValidationEvidenceBuilder(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc; this.json = json;
    }

    /** unavailableVersionIds means a known source was not safely recoverable, never PASS evidence. */
    public record Result(List<Map<String, Object>> evidence, List<String> unavailableVersionIds) {
        public Result { evidence = List.copyOf(evidence); unavailableVersionIds = List.copyOf(unavailableVersionIds); }
    }

    /** Caller must already have checked project permissions. Revalidate baseline after use. */
    public Result build(long projectId, long baselineId, List<Map<String, Object>> approvedItems) {
        if (projectId <= 0 || baselineId <= 0 || approvedItems == null) throw new IllegalArgumentException("invalid evidence scope");
        List<Map<String, Object>> result = new ArrayList<>();
        List<String> unavailable = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        int totalBytes = 0;
        for (var item : approvedItems) {
            long itemId = id(item.get("design_item_id"));
            long versionId = id(item.get("id"));
            if (!seen.add(versionId)) throw new IllegalArgumentException("duplicate design version");
            var rows = jdbc.sql("""
                    select v.source_document_version_id as item_source_id,v.source_locator::text as locator,
                           dv.content_text,dp.text_hash,bd.document_version_id as baseline_source_id,
                           d.id as document_id,dv.id as document_version_id,dv.version_no,dv.status as document_status
                    from project p
                    join baseline_item bi on bi.baseline_id=p.current_baseline_id and bi.project_id=p.id
                    join design_item_version v on v.id=bi.design_item_version_id and v.design_item_id=bi.design_item_id
                    left join document_version dv on dv.id=v.source_document_version_id
                    left join document d on d.id=dv.document_id and d.project_id=p.id
                    left join baseline_document bd on bd.baseline_id=p.current_baseline_id and bd.project_id=p.id
                      and bd.document_id=d.id and bd.document_version_id=dv.id
                    left join document_processing dp on dp.document_version_id=dv.id
                    where p.id=:project and p.current_baseline_id=:baseline
                      and bi.design_item_id=:item and bi.design_item_version_id=:version
                      and v.status='APPROVED'
                    """).param("project", projectId).param("baseline", baselineId)
                    .param("item", itemId).param("version", versionId).query().listOfRows();
            if (rows.size() != 1) throw new IllegalStateException("approved design not in current project baseline");
            var row = rows.getFirst();
            if (row.get("item_source_id") == null) {
                if (item.get("source") != null) throw new IllegalStateException("unexpected source identity");
                continue; // A manual design genuinely has no source document.
            }
            if (item.get("source") == null || row.get("baseline_source_id") == null
                    || row.get("document_id") == null || !"APPROVED".equals(row.get("document_status"))) {
                unavailable.add(Long.toString(versionId)); continue;
            }
            var source = object(item.get("source"));
            long sourceDoc = id(source.get("document_id")), sourceVersion = id(source.get("document_version_id"));
            int sourceVersionNo = positiveInt(source.get("version_no"));
            String location = text(source.get("location"));
            if (id(row.get("document_id")) != sourceDoc || id(row.get("document_version_id")) != sourceVersion
                    || positiveInt(row.get("version_no")) != sourceVersionNo) {
                throw new IllegalStateException("approved source identity changed");
            }
            if (row.get("locator") == null) { unavailable.add(Long.toString(versionId)); continue; }
            var locator = json.readValue(row.get("locator").toString(), new TypeReference<Map<String, Object>>() {});
            if (!location.equals(locator.get("location"))) throw new IllegalStateException("source display location changed");
            int start;
            int end;
            if (locator.containsKey("start_line") && locator.containsKey("end_line")) {
                if (locator.containsKey("type") && !"text_lines".equals(locator.get("type"))) {
                    throw new IllegalStateException("unsupported source locator");
                }
                start = positiveInt(locator.get("start_line"));
                end = positiveInt(locator.get("end_line"));
            } else {
                // Legacy free-form values such as headings or partial ranges do
                // not establish a source range and must remain unavailable.
                if (locator.containsKey("start_line") || locator.containsKey("end_line")
                        || locator.containsKey("type")) {
                    unavailable.add(Long.toString(versionId)); continue;
                }
                int[] lines = displayLineRange(location);
                if (lines == null) { unavailable.add(Long.toString(versionId)); continue; }
                start = lines[0];
                end = lines[1];
            }
            String content = text(row.get("content_text"));
            if (content.startsWith("\uFEFF") || content.contains("\r")) throw new IllegalStateException("source text is not normalized");
            String digest = sha256(content);
            if (!(row.get("text_hash") instanceof String stored) || !stored.equals("sha256:" + digest)) {
                throw new IllegalStateException("source text hash mismatch");
            }
            final String excerpt;
            try {
                excerpt = excerpt(content, start, end);
            } catch (IllegalArgumentException invalidRange) {
                // A well-formed display locator may no longer fit this source.
                // Preserve hash/identity failures above as errors, but don't retry
                // an invalid source range as an infrastructure failure.
                unavailable.add(Long.toString(versionId));
                continue;
            }
            int bytes = excerpt.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > MAX_EXCERPT_BYTES || totalBytes + bytes > MAX_TOTAL_BYTES) {
                unavailable.add(Long.toString(versionId)); continue;
            }
            totalBytes += bytes;
            var evidence = new LinkedHashMap<String, Object>();
            evidence.put("evidence_id", "source-" + versionId + "-" + start + "-" + end);
            evidence.put("design_item_version_id", Long.toString(versionId));
            evidence.put("source", Map.copyOf(source));
            evidence.put("locator", Map.of("type", "text_lines", "start_line", start, "end_line", end));
            evidence.put("text", excerpt);
            evidence.put("text_hash", digest);
            evidence.put("normalization_version", "utf8-lf-v1");
            result.add(Map.copyOf(evidence));
        }
        return new Result(result, unavailable);
    }

    /** Strict display-location subset; ambiguous or invalid ranges are unavailable. */
    static int[] displayLineRange(String location) {
        if (location == null) return null;
        var matcher = DISPLAY_LINES.matcher(location.strip());
        if (!matcher.matches()) return null;
        try {
            int first = Integer.parseInt(matcher.group(1));
            int last = matcher.group(2) == null ? first : Integer.parseInt(matcher.group(2));
            return last >= first ? new int[] { first, last } : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
    /** Return exact 1-based inclusive lines, retaining each source LF when present. */
    static String excerpt(String content, int first, int last) {
        if (first < 1 || last < first || content == null || content.isEmpty()) throw new IllegalArgumentException("invalid source range");
        int pos = 0, line = 1, from = -1;
        while (pos < content.length()) {
            if (line == first) from = pos;
            int lf = content.indexOf('\n', pos);
            int next = lf < 0 ? content.length() : lf + 1;
            if (line == last) return from < 0 ? invalidRange() : content.substring(from, next);
            pos = next; line++;
        }
        return invalidRange();
    }

    private static String invalidRange() { throw new IllegalArgumentException("source line range exceeds document"); }
    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException("invalid source object");
        return (Map<String, Object>) map;
    }
    private static String text(Object value) {
        if (!(value instanceof String s) || s.isBlank()) throw new IllegalArgumentException("invalid source text");
        return s;
    }
    private static long id(Object value) {
        try { long id = Long.parseLong(value.toString()); if (id > 0) return id; }
        catch (RuntimeException ignored) { }
        throw new IllegalArgumentException("invalid source identifier");
    }
    private static int positiveInt(Object value) {
        if (!(value instanceof Number n) || n.longValue() <= 0 || n.longValue() > Integer.MAX_VALUE
                || n.doubleValue() != n.longValue()) throw new IllegalArgumentException("invalid source number");
        return n.intValue();
    }
}
