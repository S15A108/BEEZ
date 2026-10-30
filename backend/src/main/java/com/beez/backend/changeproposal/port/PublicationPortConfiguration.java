package com.beez.backend.changeproposal.port;

import com.beez.backend.changeproposal.dto.PublicationDtos.*;
import com.beez.backend.common.auth.CallerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Fallbacks are registered after application beans so an actual adapter replaces them automatically. */
@AutoConfiguration
public class PublicationPortConfiguration {
    private static final Logger log = LoggerFactory.getLogger(PublicationPortConfiguration.class);

    @Bean @ConditionalOnMissingBean(ValidationImpactPort.class)
    ValidationImpactPort validationImpactPort() { return new UnconnectedValidationImpact(); }

    @Bean @ConditionalOnMissingBean(PostPublishAnalysisIntake.class)
    PostPublishAnalysisIntake postPublishAnalysisIntake() { return new UnconnectedAnalysisIntake(); }

    public static class UnconnectedValidationImpact implements ValidationImpactPort {
        @Override @Transactional(propagation = Propagation.MANDATORY)
        public ImpactResult applyPublication(CallerContext caller, long projectId, ImpactRequest request) {
            // TODO(163 연결): replace with the adapter that marks validations STALE and tasks WAITING(REVALIDATION).
            log.warn("Validation impact is not connected: project={}, baseline={}", projectId, request.baselineId());
            return ImpactResult.empty();
        }
        @Override @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
        public ImpactResult previewPublication(long projectId, ImpactRequest request) {
            log.warn("Validation impact preview is not connected: project={}", projectId);
            return ImpactResult.empty();
        }
    }
    public static class UnconnectedAnalysisIntake implements PostPublishAnalysisIntake {
        @Override @Transactional(propagation = Propagation.MANDATORY)
        public void accept(long projectId, long publishedBaselineId, Long changeProposalId) {
            // TODO(197 연결): insert PENDING in this transaction; never run analysis here.
            log.warn("Post-publish analysis intake is not connected: project={}, baseline={}", projectId, publishedBaselineId);
        }
    }
}
