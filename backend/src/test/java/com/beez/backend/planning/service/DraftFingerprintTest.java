package com.beez.backend.planning.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class DraftFingerprintTest {
    @Test void apiIgnoresPathVariableNamesTrailingSlashAndCase() {
        assertThat(DraftFingerprint.of("API_CONTRACT", Map.of("method", "get", "path", "/api/Projects/{id}/drafts/")))
                .isEqualTo(DraftFingerprint.of("API_CONTRACT", Map.of("method", "GET", "path", "/api/projects/{projectId}/drafts")));
    }

    @Test void differentMethodsAreDifferentApis() {
        assertThat(DraftFingerprint.of("API_CONTRACT", Map.of("method", "GET", "path", "/a")))
                .isNotEqualTo(DraftFingerprint.of("API_CONTRACT", Map.of("method", "POST", "path", "/a")));
    }

    @Test void dataUsesTheEntityName() {
        assertThat(DraftFingerprint.of("DATA_CONTRACT", Map.of("entity", "Draft_Job", "description", "x")))
                .isEqualTo(DraftFingerprint.of("DATA_CONTRACT", Map.of("entity", "draft job", "description", "y")));
    }

    @Test void otherKindsUseTheNormalizedDescription() {
        assertThat(DraftFingerprint.of("POLICY", Map.of("description", "실패한 문서만, 다시 시도한다!")))
                .isEqualTo(DraftFingerprint.of("POLICY", Map.of("description", "실패한 문서만 다시 시도한다")));
        assertThat(DraftFingerprint.of("POLICY", Map.of("description", "가")))
                .isNotEqualTo(DraftFingerprint.of("REQUIREMENT", Map.of("description", "가")));
    }

    @Test void nothingToCompareGivesNoFingerprint() {
        assertThat(DraftFingerprint.of("POLICY", Map.of())).isNull();
    }
}
