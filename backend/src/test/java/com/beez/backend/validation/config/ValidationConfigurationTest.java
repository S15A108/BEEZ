package com.beez.backend.validation.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ValidationConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ValidationConfiguration.class)
            .withPropertyValues("beez.validation.poll-millis=1000", "beez.validation.lease-seconds=60",
                    "beez.validation.execution-budget-ms=15000");

    @Test void rejectsPolicyAnalysisWithFakeAnalyzerAtStartup() {
        runner.withPropertyValues("beez.validation.policy-analysis-enabled=true", "beez.validation.fake-analyzer=true")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseInstanceOf(IllegalArgumentException.class)
                        .rootCause().hasMessageContaining("policy-analysis-enabled requires fake-analyzer=false"));
    }

    @Test void allowsTheOtherFlagCombinations() {
        for (String[] flags : new String[][] {{"false", "false"}, {"true", "false"}, {"false", "true"}}) {
            runner.withPropertyValues("beez.validation.policy-analysis-enabled=" + flags[0],
                            "beez.validation.fake-analyzer=" + flags[1])
                    .run(context -> assertThat(context).hasNotFailed());
        }
    }
}
