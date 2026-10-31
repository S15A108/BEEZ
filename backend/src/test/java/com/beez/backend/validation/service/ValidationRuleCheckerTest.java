package com.beez.backend.validation.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ValidationRuleCheckerTest {
    @Test
    void ordinaryPlanCanCompleteWithinTheExplicitMvpScope() {
        var facts = new ValidationRuleChecker.Facts("제목", "목표", "방법", true, true,
                List.of(new ValidationRuleChecker.Ref(10, 11, "READS", 11L)), List.of(50L), Set.of(50L), Set.of(), List.of(), 1);
        var result = ValidationRuleChecker.evaluate(facts);
        assertThat(result.findings()).isEmpty();
        assertThat(result.checkedRules()).contains("DESIGN_REFERENCE_VERSION", "EXPLICIT_DEPENDENCY");
        assertThat(result.checkedRules()).contains("DEPENDENCY_TRANSITIVE_CYCLE");
        assertThat(result.unsupportedRules()).isEmpty();
        // Scoped validation does not claim that unrelated, unlinked documents were found.
        assertThat(result.allRulesVerified()).isTrue();
    }

    @Test
    void detectsContradictionOldVersionAndOtherWorkChangingSameDesign() {
        var facts = new ValidationRuleChecker.Facts("제목", "목표", "방법", true, true,
                List.of(new ValidationRuleChecker.Ref(10, 11, "CHANGES", 12L)), List.of(), Set.of(), Set.of(),
                List.of(new ValidationRuleChecker.Conflict(10, 11, 20)), 1);
        var findings = ValidationRuleChecker.evaluate(facts).findings();
        assertThat(findings).extracting(ValidationRuleChecker.Finding::kind)
                .containsExactly("MISSING_INPUT", "MISSING_INPUT", "CONFLICT");
        assertThat(findings.get(1).designItemVersionId()).isEqualTo(11L);
        assertThat(findings.get(2).relatedTaskId()).isEqualTo(20L);
        assertThat(findings.get(2).resolution()).isEqualTo("HUMAN_REVIEW");
    }

    @Test
    void missingDeclarationAndInvalidDependenciesDoNotGetSilentPass() {
        var facts = new ValidationRuleChecker.Facts("제목", "", "방법", false, true, List.of(),
                List.of(1L, 9L, 3L, 3L), Set.of(1L, 3L), Set.of(3L), List.of(), 1);
        var findings = ValidationRuleChecker.evaluate(facts).findings();
        assertThat(findings).hasSize(6);
        assertThat(findings).allMatch(f -> "MISSING_INPUT".equals(f.kind()) && "BLOCKING".equals(f.severity()));
        // Out-of-project/missing task IDs must not become cross-project foreign-key references.
        assertThat(findings.get(3).relatedTaskId()).isNull();
    }

    @Test
    void findsLongCyclesInTheTaskDependencyGraphWithoutLooping() {
        var graph = Map.of(2L, List.of(3L), 3L, List.of(4L), 4L, List.of(1L),
                5L, List.of(6L), 6L, List.of(5L));
        assertThat(ValidationRuleChecker.cyclicDependencies(1L, List.of(2L, 5L, 7L), graph))
                .containsExactly(2L);
        assertThat(ValidationRuleChecker.cyclicDependencies(1L, List.of(1L), graph))
                .containsExactly(1L);
    }

    @Test
    void foundLongCycleIsBlockingForThePlan() {
        var facts = new ValidationRuleChecker.Facts("제목", "목표", "방법", true, true,
                List.of(new ValidationRuleChecker.Ref(10, 11, "READS", 11L)),
                List.of(2L), Set.of(2L), Set.of(2L), List.of(), 1);
        var findings = ValidationRuleChecker.evaluate(facts).findings();
        assertThat(findings).hasSize(1);
        assertThat(findings.getFirst().kind()).isEqualTo("MISSING_INPUT");
        assertThat(findings.getFirst().message()).contains("순환");
    }

    @Test
    void missingOrUnsupportedMvpScopeIsNeverMarkedVerified() {
        var noRefs = new ValidationRuleChecker.Facts("제목", "목표", "방법", true, true,
                List.of(), List.of(), Set.of(), Set.of(), List.of(), 1);
        assertThat(ValidationRuleChecker.evaluate(noRefs).unsupportedRules()).contains("NO_EXPLICIT_DESIGN");
        var unlinked = new ValidationRuleChecker.Facts("제목", "목표", "방법", true, false,
                List.of(new ValidationRuleChecker.Ref(10, 11, "READS", 11L)),
                List.of(), Set.of(), Set.of(), List.of(), 1);
        assertThat(ValidationRuleChecker.evaluate(unlinked).unsupportedRules()).contains("UNRESOLVED_Q02_RULES");
        var changes = new ValidationRuleChecker.Facts("제목", "목표", "방법", false, true,
                List.of(new ValidationRuleChecker.Ref(10, 11, "CHANGES", 11L)),
                List.of(), Set.of(), Set.of(), List.of(), 1);
        assertThat(ValidationRuleChecker.evaluate(changes).unsupportedRules()).contains("STRUCTURED_CONTRACT_MISMATCH");
    }
}
