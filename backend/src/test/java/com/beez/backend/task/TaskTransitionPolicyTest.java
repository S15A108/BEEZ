package com.beez.backend.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.beez.backend.task.entity.TaskEvent;
import com.beez.backend.task.entity.TaskStatus;
import com.beez.backend.task.service.TaskTransitionPolicy;
import com.beez.backend.task.service.TaskTransitionPolicy.Verdict;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 전이 표 전수 검사 — docs/design/04-task-lifecycle.md §2 와 글자 하나까지 같아야 한다. 6 상태 × 5 이벤트 = 30 칸. */
class TaskTransitionPolicyTest {

    private final TaskTransitionPolicy policy = new TaskTransitionPolicy();

    /** 문서의 표를 그대로 옮긴 기대값. A=ALLOWED, I=IDEMPOTENT, F=FORBIDDEN. 열 순서: START RESUME WAIT COMPLETED CANCEL */
    private static final Map<TaskStatus, String> EXPECTED = new EnumMap<>(TaskStatus.class);

    static {
        EXPECTED.put(TaskStatus.DRAFT,       "FFFFA");
        EXPECTED.put(TaskStatus.READY,       "AFFFA");
        EXPECTED.put(TaskStatus.IN_PROGRESS, "IFAAA");
        EXPECTED.put(TaskStatus.WAITING,     "FAFFA");
        EXPECTED.put(TaskStatus.COMPLETED,   "FFFIF");
        EXPECTED.put(TaskStatus.CANCELLED,   "FFFFI");
    }

    private static final TaskEvent[] COLUMNS = {
            TaskEvent.START, TaskEvent.RESUME, TaskEvent.WAIT, TaskEvent.COMPLETED, TaskEvent.CANCEL
    };

    @Test
    void everyCellMatchesTheDocument() {
        for (TaskStatus status : TaskStatus.values()) {
            String row = EXPECTED.get(status);
            for (int i = 0; i < COLUMNS.length; i++) {
                Verdict expected = toVerdict(row.charAt(i));
                Verdict actual = policy.check(status, COLUMNS[i]);
                assertThat(actual)
                        .as("%s + %s", status, COLUMNS[i])
                        .isEqualTo(expected);
            }
        }
    }

    @Test
    void closedStatesNeverAllowAnything() {
        for (TaskEvent event : TaskEvent.values()) {
            assertThat(policy.check(TaskStatus.COMPLETED, event)).isNotEqualTo(Verdict.ALLOWED);
            assertThat(policy.check(TaskStatus.CANCELLED, event)).isNotEqualTo(Verdict.ALLOWED);
        }
    }

    private Verdict toVerdict(char c) {
        if (c == 'A') {
            return Verdict.ALLOWED;
        }
        if (c == 'I') {
            return Verdict.IDEMPOTENT;
        }
        return Verdict.FORBIDDEN;
    }
}
