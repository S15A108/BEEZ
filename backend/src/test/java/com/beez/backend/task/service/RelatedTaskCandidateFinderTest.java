package com.beez.backend.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RelatedTaskCandidateFinderTest {
    private static Map<String, Object> row(long task, String path, long design) {
        return Map.of("task_id", task, "revision_id", task + 100, "assignee_member_id", 9L,
                "path_kind", path, "design_item_id", design, "design_item_version_id", design + 10,
                "relation", "READS");
    }

    @Test
    void preservesMultiplePathsAndReportsWhenCandidateLimitIsReached() {
        var result = RelatedTaskCandidateFinder.collect(99, 101, List.of(
                row(2, "DESIGN_REFERENCE", 11), row(2, "DESIGN_REFERENCE", 12),
                row(3, "DESIGN_REFERENCE", 11)), 1);
        assertThat(result.baselineId()).isEqualTo(99);
        assertThat(result.targetPlanRevisionId()).isEqualTo(101);
        assertThat(result.truncated()).isTrue();
        assertThat(result.candidates()).hasSize(1);
        assertThat(result.candidates().getFirst().paths()).extracting("designItemId").containsExactly(11L, 12L);
    }

    @Test
    void emptyCandidatesDoNotClaimCompleteCoverage() {
        var result = RelatedTaskCandidateFinder.collect(1, 2, List.of(), 20);
        assertThat(result.candidates()).isEmpty();
        assertThat(result.truncated()).isFalse();
        assertThat(result.coverage()).isEqualTo(RelatedTaskCandidateFinder.Coverage.RECORDED_RELATIONSHIPS_ONLY);
    }
}
