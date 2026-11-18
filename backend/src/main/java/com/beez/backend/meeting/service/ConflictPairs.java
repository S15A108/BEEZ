package com.beez.backend.meeting.service;

import com.beez.backend.meeting.repository.MeetingRepository.TaskState;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 충돌 감지 결과에서 회의를 열 작업 쌍을 고른다 (S15P31A108-248). 담당자가 서로 다르고(둘 다 담당자가 있어야 한다)
 * 둘 다 COMPLETED·CANCELLED 가 아닌 쌍만 남는다.
 */
final class ConflictPairs {

    private static final Set<String> CLOSED = Set.of("COMPLETED", "CANCELLED");

    record Pair(TaskState task, TaskState related) {
    }

    private ConflictPairs() {
    }

    static List<Pair> select(long taskId, List<Long> relatedTaskIds, Map<Long, TaskState> states) {
        List<Pair> pairs = new ArrayList<>();
        TaskState task = states.get(taskId);
        if (task == null || !open(task) || task.assigneeMemberId() == null) {
            return pairs;
        }
        for (Long relatedId : relatedTaskIds) {
            TaskState related = states.get(relatedId);
            if (related == null || relatedId == taskId || !open(related) || related.assigneeMemberId() == null
                    || Objects.equals(task.assigneeMemberId(), related.assigneeMemberId())) {
                continue;
            }
            pairs.add(new Pair(task, related));
        }
        return pairs;
    }

    private static boolean open(TaskState state) {
        return !CLOSED.contains(state.status());
    }
}
