package com.beez.backend.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.beez.backend.task.entity.PlanRelation;
import com.beez.backend.task.service.PlanCommand;
import com.beez.backend.task.service.PlanInputHasher;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 빠른 층(단위). 같은 계획이면 순서가 달라도 같은 해시, 내용이 다르면 다른 해시. 제목만 바꾸면 같은 해시(AC-13). */
class PlanInputHasherTest {

    private final PlanInputHasher hasher = new PlanInputHasher();

    private PlanCommand plan(String title, List<String> scope, List<Long> deps) {
        return new PlanCommand(title, "목표", scope, List.of(), "방법", List.of("완료"), deps, List.of(),
                List.of(Map.of("kind", "NEW", "text", "새 화면")), false, null);
    }

    @Test
    void sameContentDifferentOrder_sameHash() {
        String a = hasher.hash(plan("t", List.of("a", "b"), List.of(1L, 2L)),
                Map.of(10L, 100L, 20L, 200L), Map.of(10L, PlanRelation.READS, 20L, PlanRelation.CHANGES), Map.of(20L, Map.of("text", "바꿈")));
        String b = hasher.hash(plan("t", List.of("b", "a"), List.of(2L, 1L)),
                Map.of(20L, 200L, 10L, 100L), Map.of(20L, PlanRelation.CHANGES, 10L, PlanRelation.READS), Map.of(20L, Map.of("text", "바꿈")));
        assertThat(a).isEqualTo(b).hasSize(64);
    }

    @Test
    void titleOnlyChange_sameHash() {
        String a = hasher.hash(plan("제목 1", List.of("a"), List.of()), Map.of(), Map.of(), Map.of());
        String b = hasher.hash(plan("제목 2", List.of("a"), List.of()), Map.of(), Map.of(), Map.of());
        assertThat(a).isEqualTo(b);
    }

    @Test
    void differentVersionOrRelation_differentHash() {
        String base = hasher.hash(plan("t", List.of("a"), List.of()), Map.of(10L, 100L), Map.of(10L, PlanRelation.READS), Map.of());
        String otherVersion = hasher.hash(plan("t", List.of("a"), List.of()), Map.of(10L, 101L), Map.of(10L, PlanRelation.READS), Map.of());
        String otherRelation = hasher.hash(plan("t", List.of("a"), List.of()), Map.of(10L, 100L), Map.of(10L, PlanRelation.CHANGES), Map.of());
        assertThat(base).isNotEqualTo(otherVersion).isNotEqualTo(otherRelation);
    }

    /** 계약을 어떻게 바꾸는지(CHANGES 의 제안 내용)·완료 기준이 바뀌면 다른 입력. 접근 방식만 바뀌면 같은 입력 (AC-13) */
    @Test
    void proposedContentOrCriteriaChange_differentHash_approachOnly_sameHash() {
        PlanCommand base = new PlanCommand("t", "목표", List.of("a"), List.of(), "방법 1", List.of("완료"), List.of(), List.of(),
                List.of(), false, null);
        PlanCommand otherApproach = new PlanCommand("t", "목표", List.of("a"), List.of(), "방법 2", List.of("완료"), List.of(), List.of(),
                List.of(), false, null);
        PlanCommand otherCriteria = new PlanCommand("t", "목표", List.of("a"), List.of(), "방법 1", List.of("완료", "테스트"), List.of(),
                List.of(), List.of(), false, null);
        Map<Long, Long> versions = Map.of(10L, 100L);
        Map<Long, PlanRelation> changes = Map.of(10L, PlanRelation.CHANGES);
        String a = hasher.hash(base, versions, changes, Map.of(10L, Map.of("text", "v1")));
        String sameApproachDiffers = hasher.hash(otherApproach, versions, changes, Map.of(10L, Map.of("text", "v1")));
        String otherProposed = hasher.hash(base, versions, changes, Map.of(10L, Map.of("text", "v2")));
        String criteria = hasher.hash(otherCriteria, versions, changes, Map.of(10L, Map.of("text", "v1")));
        assertThat(a).isEqualTo(sameApproachDiffers).isNotEqualTo(otherProposed).isNotEqualTo(criteria);
    }
}
