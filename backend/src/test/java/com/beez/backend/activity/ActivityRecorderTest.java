package com.beez.backend.activity;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.common.auth.CallerKind;
import org.junit.jupiter.api.Test;

/** 활동 입구의 행위자 검사. DB 에 닿기 전에 끝나는 것만 — 빠른 층. */
class ActivityRecorderTest {

    @Test
    void agentCallerWithoutToken_isRejectedBeforeSaving() {
        // 토큰 없는 에이전트 행위가 저장되면 -178 에서 세션을 이을 근거가 없어 에이전트 필터에서 영구히 빠진다
        ActivityRecorder recorder = new ActivityRecorder(null, null);   // EntityManager 까지 가면 NPE 로 다른 실패가 된다
        CallerContext tokenless = new CallerContext(1L, null, CallerKind.AGENT);

        assertThatThrownBy(() -> recorder.record(tokenless,
                ActivityEvent.of("task:1:lock:1:START", 1L, "TASK_STARTED", ActivityTargetType.TASK, 1L)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("agentTokenId");
    }
}
