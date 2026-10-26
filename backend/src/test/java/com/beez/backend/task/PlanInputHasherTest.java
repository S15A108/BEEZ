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
                Map.of(10L, 100L, 20L, 200L), Map.of(10L, PlanRelation.READS, 20L, PlanRelation.CHANGES));
        String b = hasher.hash(plan("t", List.of("b", "a"), List.of(2L, 1L)),
                Map.of(20L, 200L, 10L, 100L), Map.of(20L, PlanRelation.CHANGES, 10L, PlanRelation.READS));
        assertThat(a).isEqualTo(b).hasSize(64);
    }

    @Test
    void titleOnlyChange_sameHash() {
        String a = hasher.hash(plan("제목 1", List.of("a"), List.of()), Map.of(), Map.of());
        String b = hasher.hash(plan("제목 2", List.of("a"), List.of()), Map.of(), Map.of());
        assertThat(a).isEqualTo(b);
    }

    @Test
    void differentVersionOrRelation_differentHash() {
        String base = hasher.hash(plan("t", List.of("a"), List.of()), Map.of(10L, 100L), Map.of(10L, PlanRelation.READS));
        String otherVersion = hasher.hash(plan("t", List.of("a"), List.of()), Map.of(10L, 101L), Map.of(10L, PlanRelation.READS));
        String otherRelation = hasher.hash(plan("t", List.of("a"), List.of()), Map.of(10L, 100L), Map.of(10L, PlanRelation.CHANGES));
        assertThat(base).isNotEqualTo(otherVersion).isNotEqualTo(otherRelation);
    }
}
