package com.beez.backend.validation.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PolicyCitedVersionsTest {
    @Test void includesApprovedQuotesAndCitedSourceEvidenceButNotTheWholeSearchScope() {
        var request = Map.<String, Object>of("evidence", List.of(
                Map.of("evidence_id", "quoted-source", "design_item_version_id", "312"),
                Map.of("evidence_id", "unused-source", "design_item_version_id", "313")));
        var result = Map.<String, Object>of("policy_assessments", List.of(Map.of(
                "design_item_version_ids", List.of("311", "312", "313"),
                "approved_content_refs", List.of(Map.of("design_item_version_id", "311")),
                "evidence_ids", List.of("quoted-source"))));
        assertThat(AnalysisResultApplier.citedPolicyVersions(request, result)).containsExactlyInAnyOrder("311", "312");
    }
}
