package com.beez.backend.document;

import static org.assertj.core.api.Assertions.*;
import static com.beez.backend.document.validation.DocumentReviewReferenceHasher.*;

import com.beez.backend.designitem.support.DesignJson;
import java.util.*;
import org.junit.jupiter.api.Test;

class DocumentReviewReferenceHasherTest {
    Map<String, Object> row(long version, String text) {
        return Map.of("document_id", 2L, "document_version_id", version, "type", "ERD", "version_no", 1,
                "content_text", text, "content_hash", "unrelated-upload-byte-hash");
    }

    @Test void rawUtf8VectorsPreserveKoreanAndLineEndingsAndEmptyReferenceSet() throws Exception {
        try (var stream = getClass().getResourceAsStream("/document/review-reference-hash-vectors.json")) {
            var fixture = DesignJson.JSON.readTree(stream);
            for (var vector : fixture.path("text_vectors")) {
                assertThat(textHash(vector.path("text").asText())).isEqualTo(vector.path("hash").asText());
            }
            assertThat(referenceHash(List.of())).isEqualTo(fixture.path("empty_reference_hash").asText());
        }
    }

    @Test void referenceOrderIsIrrelevantButTextVersionKindAndApprovalMetadataMatter() {
        var a = row(10L, "한글\n"); var b = row(2L, "reference");
        var first = snapshots(List.of(a, b));
        assertThat(first.stream().map(r -> r.get("reference_id")).toList()).containsExactly("document-version-10", "document-version-2");
        assertThat(referenceHash(first)).isEqualTo(referenceHash(snapshots(List.of(b, a))));
        assertThat(first.getFirst().get("content_hash")).isEqualTo(textHash("한글\n"));
        assertThat(referenceHash(first)).isNotEqualTo(referenceHash(snapshots(List.of(row(10L, "한글\r\n"), b))));
        assertThat(referenceHash(first)).isNotEqualTo(referenceHash(snapshots(List.of(row(11L, "한글\n"), b))));
        for (String field : List.of("kind", "status", "version_no")) {
            var changed = new HashMap<>(first.getFirst());
            changed.put(field, "changed");
            assertThat(referenceHash(List.of(changed, first.getLast()))).isNotEqualTo(referenceHash(first));
        }
    }

    @Test void nonemptyCanonicalHashMatchesIndependentCompleteFixture() throws Exception {
        try (var stream = getClass().getResourceAsStream("/document/review-contract-complete.json")) {
            var input = (Map<String, Object>) DesignJson.JSON.readValue(stream, Map.class).get("input");
            assertThat(referenceHash((List<Map<String, Object>>) input.get("references"))).isEqualTo(input.get("reference_hash"));
        }
    }
}
