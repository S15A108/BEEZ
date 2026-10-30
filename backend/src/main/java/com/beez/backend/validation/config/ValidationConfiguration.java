package com.beez.backend.validation.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ValidationProperties.class)
public class ValidationConfiguration {
}
