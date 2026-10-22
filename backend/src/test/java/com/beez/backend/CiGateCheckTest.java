package com.beez.backend;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * CI 게이트 검증용 — 일부러 실패한다. 룰셋의 필수 검사(ci-gate)가 병합을 막는지 확인하고 나면 이 PR 은 닫고 브랜치를 지운다.
 * 빠른 층(`./gradlew test`)에서 돌므로 backend 잡의 첫 테스트 단계에서 실패해야 한다.
 */
class CiGateCheckTest {

    @Test
    void intentionallyFails_toProveCiGateBlocksMerge() {
        assertThat(1 + 1).as("CI 게이트 검증: 이 실패가 병합을 막아야 한다").isEqualTo(3);
    }
}
