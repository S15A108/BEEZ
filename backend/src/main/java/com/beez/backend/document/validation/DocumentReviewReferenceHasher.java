package com.beez.backend.document.validation;

import static com.beez.backend.designitem.support.DesignJson.hash;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Review hashes describe transmitted text, never uploaded file bytes or an indexing manifest. */
public final class DocumentReviewReferenceHasher {
    public static String textHash(String content) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static List<Map<String, Object>> snapshots(List<Map<String, Object>> rows) {
        return rows.stream().map(row -> Map.<String, Object>of(
                "reference_id", "document-version-" + row.get("document_version_id"),
                "document_id", row.get("document_id").toString(),
                "document_version_id", row.get("document_version_id").toString(),
                "kind", row.get("type"), "version_no", row.get("version_no"),
                "status", "APPROVED", "content", row.get("content_text"),
                "content_hash", textHash(row.get("content_text").toString())))
                .sorted(Comparator.comparing(row -> row.get("reference_id").toString())).toList();
    }

    public static String referenceHash(List<Map<String, Object>> snapshots) {
        // ASCII reference IDs sort lexicographically; canonical() recursively orders object keys.
        // No null/omitted keys, no whitespace, UTF-8, content itself is represented by its text hash.
        return hash(snapshots.stream().sorted(Comparator.comparing(row -> row.get("reference_id").toString()))
                .map(row -> Map.of("reference_id", row.get("reference_id"),
                        "document_id", row.get("document_id"), "document_version_id", row.get("document_version_id"),
                        "kind", row.get("kind"), "version_no", row.get("version_no"),
                        "status", row.get("status"), "content_hash", row.get("content_hash"))).toList());
    }

    private DocumentReviewReferenceHasher() { }
}
