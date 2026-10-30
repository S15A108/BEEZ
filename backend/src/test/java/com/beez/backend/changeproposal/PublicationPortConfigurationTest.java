package com.beez.backend.changeproposal;

import static org.assertj.core.api.Assertions.*;
import com.beez.backend.changeproposal.dto.PublicationDtos.*;
import com.beez.backend.changeproposal.port.*;
import com.beez.backend.common.auth.CallerContext;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PublicationPortConfigurationTest {
    final ApplicationContextRunner runner=new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PublicationPortConfiguration.class));
    @Test void missingAdaptersHaveNoOpDefaultsAndEmptyPreview() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(ValidationImpactPort.class).hasSingleBean(PostPublishAnalysisIntake.class);
            var request=new ImpactRequest(null,1,2,1,"hash",List.of(),"reason");
            assertThat(ctx.getBean(ValidationImpactPort.class).previewPublication(1,request)).isEqualTo(ImpactResult.empty());
            assertThat(ctx.getBean(ValidationImpactPort.class).applyPublication(CallerContext.human(1L),1,request)).isEqualTo(ImpactResult.empty());
            ctx.getBean(PostPublishAnalysisIntake.class).accept(1,2,3L);
        });
    }
    @Test void actualAdaptersReplaceDefaultsRatherThanCompetingWithThem() {
        ValidationImpactPort real=new ValidationImpactPort() {
            public ImpactResult applyPublication(CallerContext c,long p,ImpactRequest r) { return ImpactResult.empty(); }
            public ImpactResult previewPublication(long p,ImpactRequest r) { return ImpactResult.empty(); }
        };
        PostPublishAnalysisIntake intake=(p,b,c) -> {};
        runner.withBean("actualImpact",ValidationImpactPort.class,() -> real)
                .withBean("actualIntake",PostPublishAnalysisIntake.class,() -> intake).run(ctx -> {
                    assertThat(ctx).hasSingleBean(ValidationImpactPort.class).hasSingleBean(PostPublishAnalysisIntake.class);
                    assertThat(ctx.getBean(ValidationImpactPort.class)).isSameAs(real);
                    assertThat(ctx.getBean(PostPublishAnalysisIntake.class)).isSameAs(intake);
                });
    }
}
