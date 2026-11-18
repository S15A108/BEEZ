package com.beez.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BeezBackendApplicationStartTest {
    // 깨지면: 기동이 실패해도 남은 비데몬 스레드(MCP SDK 세션 정리기, 295 리허설 실측) 때문에 JVM 이 끝나지 않아
    // 컨테이너가 running 으로 남고 restart 정책이 돌지 않는다(S15P31A108-298 완료 조건)
    @Test
    void startupFailureExitsWithNonZeroCode() {
        List<Integer> codes = new ArrayList<>();
        BeezBackendApplication.start(() -> { throw new IllegalStateException("boom"); }, codes::add);
        assertThat(codes).containsExactly(1);

        codes.clear();
        BeezBackendApplication.start(() -> { }, codes::add);
        assertThat(codes).isEmpty();
    }
}
