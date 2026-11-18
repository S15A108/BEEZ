package com.beez.backend.task.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 모델을 부르기 전에 거절된 요청의 시간당 카운터(S15P31A108-385). */
class TaskPlanDraftWindowTest {
    private static final long HOUR = TaskPlanDraftWindow.WINDOW_MILLIS;

    @Test void countsOnlyTheSamePersonAndProject() {
        var window = new TaskPlanDraftWindow();
        window.add("1:7", 1_000);
        window.add("1:7", 2_000);
        window.add("1:8", 2_000);
        assertThat(window.count("1:7", 3_000)).isEqualTo(2);
        assertThat(window.count("1:8", 3_000)).isEqualTo(1);
        assertThat(window.count("2:7", 3_000)).isZero();
    }

    @Test void forgetsRequestsOlderThanAnHour() {
        var window = new TaskPlanDraftWindow();
        window.add("1:7", 0);
        window.add("1:7", 30 * 60 * 1000L);
        assertThat(window.count("1:7", HOUR - 1)).isEqualTo(2);
        assertThat(window.count("1:7", HOUR)).isEqualTo(1);
        assertThat(window.count("1:7", HOUR + 30 * 60 * 1000L)).isZero();
    }

    @Test void staysBoundedForOnePerson() {
        var window = new TaskPlanDraftWindow();
        for (int i = 0; i < 5_000; i++) window.add("1:7", i);
        assertThat(window.count("1:7", 5_000)).isEqualTo(1_000);
    }

    @Test void sweepsIdleKeysWhenManyAccumulate() {
        var window = new TaskPlanDraftWindow();
        for (int i = 0; i < 1_500; i++) window.add("p:" + i, 0);
        window.add("late", 2 * HOUR);
        assertThat(window.count("p:1", 2 * HOUR)).isZero();
        assertThat(window.count("late", 2 * HOUR)).isEqualTo(1);
    }
}
