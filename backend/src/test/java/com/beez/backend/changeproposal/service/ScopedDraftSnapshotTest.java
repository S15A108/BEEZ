package com.beez.backend.changeproposal.service;

import static org.assertj.core.api.Assertions.*;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ScopedDraftSnapshotTest {
    @Test void mapsStoredRequirementsAndDistinctRawAndNormalizedHashes() {
        String rawHash = "a".repeat(64);
        var result = ScopedDraftSnapshot.source(42, Map.of(
                "id", 21L, "type", "REQ_SPEC", "storage_key", "document.md",
                "content_hash", "sha256:" + rawHash, "content_text", "한글\n"));
        assertThat(result).containsEntry("kind", "REQUIREMENTS")
                .containsEntry("project_id", "42").containsEntry("document_version_id", "21")
                .containsEntry("content_hash", rawHash).containsEntry("parser_profile", "markdown-v1")
                .containsEntry("normalization_version", "utf8-lf-v1");
        assertThat(result.get("text_hash")).isNotEqualTo(rawHash).asString().hasSize(64);
    }

    @Test void rejectsAmbiguousFileAndMalformedHash() {
        assertThatThrownBy(() -> ScopedDraftSnapshot.source(1, Map.of("id", 2L, "type", "PLAN",
                "storage_key", "document.unknown", "content_hash", "sha256:" + "a".repeat(64), "content_text", "x")))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> ScopedDraftSnapshot.source(1, Map.of("id", 2L, "type", "PLAN",
                "storage_key", "document.txt", "content_hash", "not-a-hash", "content_text", "x")))
                .isInstanceOf(RuntimeException.class);
    }
}
