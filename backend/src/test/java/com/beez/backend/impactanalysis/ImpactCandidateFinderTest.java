package com.beez.backend.impactanalysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ImpactCandidateFinderTest {
    @Test
    void findsDirectAndReverseDependencyPathsWithoutTurningCandidatesIntoDecisions() {
        var changes = List.of(new ImpactCandidateFinder.Change(1, 10L, 100L, "UPDATE"));
        var dependencies = List.of(new ImpactCandidateFinder.Dependency(20, 200, 10, 100, 700));
        var references = List.of(
                new ImpactCandidateFinder.PlanReference(31, 41L, 51, 10, 100, "READS"),
                new ImpactCandidateFinder.PlanReference(32, 42L, 52, 20, 200, "IMPLEMENTS"));

        var result = ImpactCandidateFinder.find(changes, dependencies, references);

        assertEquals(2, result.paths().size());
        assertEquals(ImpactCandidateFinder.Kind.DIRECT_REFERENCE, result.paths().getFirst().kind());
        var indirect = result.paths().get(1);
        assertEquals(ImpactCandidateFinder.Kind.INDIRECT_DEPENDENCY, indirect.kind());
        assertEquals(700L, indirect.confirmationEventId());
        assertEquals(52, indirect.planRevisionId());
        assertFalse(indirect.staleReference());
        assertEquals(ImpactCandidateFinder.Coverage.UNKNOWN, result.indirectCoverage());
    }

    @Test
    void doesNotInferReverseTransitivityOrUseRelationshipWithWrongChangedVersion() {
        var changes = List.of(new ImpactCandidateFinder.Change(1, 10L, 100L, "UPDATE"));
        var dependencies = List.of(
                new ImpactCandidateFinder.Dependency(20, 200, 10, 99, 700),
                new ImpactCandidateFinder.Dependency(10, 100, 30, 300, 701),
                new ImpactCandidateFinder.Dependency(40, 400, 20, 200, 702));
        var references = List.of(new ImpactCandidateFinder.PlanReference(31, null, 51, 20, 200, "READS"),
                new ImpactCandidateFinder.PlanReference(32, null, 52, 30, 300, "READS"),
                new ImpactCandidateFinder.PlanReference(33, null, 53, 40, 400, "READS"));

        var result = ImpactCandidateFinder.find(changes, dependencies, references);

        assertTrue(result.paths().isEmpty());
        assertEquals(ImpactCandidateFinder.Coverage.UNKNOWN, result.indirectCoverage());
    }

    @Test
    void flagsStalePlanAndPreservesMultipleEvidencePathsForOneTask() {
        var changes = List.of(new ImpactCandidateFinder.Change(1, 10L, 100L, "UPDATE"));
        var dependencies = List.of(new ImpactCandidateFinder.Dependency(20, 200, 10, 100, 700),
                new ImpactCandidateFinder.Dependency(30, 300, 10, 100, 701));
        var references = List.of(new ImpactCandidateFinder.PlanReference(31, 41L, 51, 20, 199, "READS"),
                new ImpactCandidateFinder.PlanReference(31, 41L, 51, 30, 300, "READS"));

        var result = ImpactCandidateFinder.find(changes, dependencies, references);

        assertEquals(2, result.paths().size());
        assertTrue(result.paths().getFirst().staleReference());
        assertFalse(result.paths().getLast().staleReference());
        assertEquals(200L, result.paths().getFirst().requiredReferenceVersionId());
    }

    @Test
    void createWithNoExistingItemDoesNotMistakeMissingPathsForNoImpact() {
        var result = ImpactCandidateFinder.find(
                List.of(new ImpactCandidateFinder.Change(1, null, null, "CREATE")), List.of(), List.of());

        assertTrue(result.paths().isEmpty());
        assertTrue(result.semanticAnalysisRequired());
        assertEquals(ImpactCandidateFinder.Coverage.UNKNOWN, result.indirectCoverage());
    }
}
