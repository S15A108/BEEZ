package com.beez.backend.meeting.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.beez.backend.meeting.repository.MeetingRepository.TaskState;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConflictPairsTest {

    private static TaskState task(long id, Long assignee, String status) {
        return new TaskState(id, "T-" + id, assignee, status);
    }

    private static Map<Long, TaskState> states(TaskState... all) {
        var map = new java.util.LinkedHashMap<Long, TaskState>();
        for (TaskState s : all) map.put(s.id(), s);
        return map;
    }

    @Test void differentAssigneesOnActiveTasksMakeAPair() {
        var pairs = ConflictPairs.select(1, List.of(2L, 3L), states(task(1, 10L, "IN_PROGRESS"), task(2, 20L, "READY"), task(3, 30L, "WAITING")));
        assertThat(pairs).extracting(p -> p.related().id()).containsExactly(2L, 3L);
    }

    @Test void sameAssigneeMakesNoPair() {
        assertThat(ConflictPairs.select(1, List.of(2L), states(task(1, 10L, "READY"), task(2, 10L, "READY")))).isEmpty();
    }

    @Test void unassignedTaskIsSkipped() {
        assertThat(ConflictPairs.select(1, List.of(2L), states(task(1, 10L, "READY"), task(2, null, "READY")))).isEmpty();
        assertThat(ConflictPairs.select(1, List.of(2L), states(task(1, null, "READY"), task(2, 20L, "READY")))).isEmpty();
    }

    @Test void completedOrCancelledOnEitherSideMakesNoPair() {
        assertThat(ConflictPairs.select(1, List.of(2L), states(task(1, 10L, "READY"), task(2, 20L, "COMPLETED")))).isEmpty();
        assertThat(ConflictPairs.select(1, List.of(2L), states(task(1, 10L, "CANCELLED"), task(2, 20L, "READY")))).isEmpty();
    }

    @Test void missingTaskAndSelfReferenceAreIgnored() {
        assertThat(ConflictPairs.select(1, List.of(1L, 9L), states(task(1, 10L, "READY")))).isEmpty();
    }
}
