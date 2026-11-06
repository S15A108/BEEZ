package com.beez.backend.changeproposal.service;

import com.beez.backend.common.api.ApiException;
import com.beez.backend.document.DocumentKindMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** Build precisely the same normalized snapshot for listing and generating. */
final class ScopedDraftSnapshot {
    private ScopedDraftSnapshot() { }

    static Map<String, Object> source(long project, Map<String, Object> row) {
        try {
            String content = (String) row.get("content_text");
            String key = (String) row.get("storage_key");
            String hash = (String) row.get("content_hash");
            if (content == null || content.isBlank() || content.startsWith("\uFEFF") || content.indexOf('\r') >= 0
                    || key == null || !(key.endsWith(".md") || key.endsWith(".txt"))
                    || hash == null || !hash.matches("sha256:[0-9a-f]{64}")) throw invalid();
            String kind = new DocumentKindMapper().convertToEntityAttribute(row.get("type").toString()).name();
            boolean markdown = key.endsWith(".md");
            var source = new LinkedHashMap<String, Object>();
            source.put("project_id", Long.toString(project));
            source.put("document_version_id", row.get("id").toString());
            source.put("kind", kind);
            source.put("media_type", markdown ? "text/markdown" : "text/plain");
            source.put("content", content);
            source.put("content_hash", hash.substring(7));
            source.put("text_hash", digest(content));
            source.put("normalization_version", "utf8-lf-v1");
            source.put("parser_profile", markdown ? "markdown-v1" : "plain-v1");
            return source;
        } catch (ClassCastException | IllegalArgumentException | NullPointerException e) {
            throw invalid();
        }
    }

    private static String digest(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.CONFLICT, "analysis_not_completed", "문서 원문을 확인할 수 없어요.");
    }
}
