package com.beez.backend.validation.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** 공통 사용량 표에 넣기 전 값 거르기 (S15P31A108-168). 틀린 값은 null 로 비워 실행 종결을 막지 않는다 */
class ValidationUsageValuesTest {

    @Test
    void integers_acceptWholeNonNegativeValuesOnly() {
        assertThat(ValidationRepository.nonNegativeInt(10)).isEqualTo(10);
        assertThat(ValidationRepository.nonNegativeInt("25")).isEqualTo(25);
        assertThat(ValidationRepository.nonNegativeInt(7L)).isEqualTo(7);
        assertThat(ValidationRepository.nonNegativeInt(3.0)).isEqualTo(3);        // JSON 숫자가 실수로 읽혀도 정수면 받는다
        assertThat(ValidationRepository.nonNegativeInt(0)).isEqualTo(0);
        assertThat(ValidationRepository.nonNegativeInt(null)).isNull();
        assertThat(ValidationRepository.nonNegativeInt(-1)).isNull();
        assertThat(ValidationRepository.nonNegativeInt(2.5)).isNull();
        assertThat(ValidationRepository.nonNegativeInt("abc")).isNull();
        assertThat(ValidationRepository.nonNegativeInt(1L + Integer.MAX_VALUE)).isNull();   // int 칸 범위 밖
    }

    @Test
    void costAmount_fitsNumeric12_6_orIsDropped() {
        assertThat(ValidationRepository.costAmount("0.001234")).isEqualByComparingTo(new BigDecimal("0.001234"));
        assertThat(ValidationRepository.costAmount("1.23456789")).isEqualByComparingTo(new BigDecimal("1.234568"));   // 소수 6자리로 반올림
        assertThat(ValidationRepository.costAmount(0.5)).isEqualByComparingTo(new BigDecimal("0.5"));
        assertThat(ValidationRepository.costAmount("999999.999999")).isNotNull();
        assertThat(ValidationRepository.costAmount("1000000")).isNull();           // 정수부 7자리 → 칸에 안 들어간다
        assertThat(ValidationRepository.costAmount("-0.1")).isNull();
        assertThat(ValidationRepository.costAmount("free")).isNull();
        assertThat(ValidationRepository.costAmount("1e3")).isEqualByComparingTo(new BigDecimal("1000"));
    }
}
