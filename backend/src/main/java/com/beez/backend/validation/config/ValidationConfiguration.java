package com.beez.backend.validation.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 검증 워커(@Scheduled)를 쓰므로 스케줄링을 켠다. 문서 설정에도 있지만 그쪽에 기대지 않는다 (여러 번 켜도 하나로 동작) */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(ValidationProperties.class)
public class ValidationConfiguration {
}
