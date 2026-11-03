package com.beez.backend.impactanalysis;

import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.config.BeezProperties;
import com.beez.backend.impactanalysis.intake.PublishedAnalysisRepository;
import com.beez.backend.impactanalysis.intake.PublishedAnalysisRepository.Claim;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Executes a publication intake without using or overwriting approval-time analysis runs. */
@Service
public class PublishedAnalysisProcessor {
    private final PublishedAnalysisRepository repository;
    private final ImpactAnalysisCoordinator coordinator;
    private final ImpactContextQuery contexts;
    private final ExplicitImpactCalculator rules;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final long leaseSeconds;

    public PublishedAnalysisProcessor(PublishedAnalysisRepository repository, ImpactAnalysisCoordinator coordinator,
            ImpactContextQuery contexts, ExplicitImpactCalculator rules, JdbcClient jdbc,
            PlatformTransactionManager transactions, BeezProperties properties) {
        this.repository = repository;
        this.coordinator = coordinator;
        this.contexts = contexts;
        this.rules = rules;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.leaseSeconds = Math.max(60, properties.ai().readTimeout().plus(properties.ai().connectTimeout()).toSeconds() + 60);
    }

    public void process(long id) {
        var claimed = repository.claimNow(id, leaseSeconds);
        if (claimed.isEmpty()) return;
        Claim claim = claimed.get();
        List<?> receivedUsage = List.of();
        String receivedUsageStatus = "UNKNOWN";
        try {
            var publication = repository.publication(id);
            // The publisher is revalidated as an active member on every input/freshness read;
            // no synthetic administrator or revoked credential is used.
            var actor = CallerContext.human(publication.publishedBy());
            var outcome = coordinator.analyzePublished(actor, publication.projectId(), publication.proposalId(),
                    publication.baselineId(), "published-impact-" + id + "-" + claim.attempt());
            receivedUsage = outcome.usage();
            receivedUsageStatus = outcome.usageStatus().name();
            if (outcome.status() == ImpactAnalysisCoordinator.Status.AI_FAILED) {
                String code = outcome.errorCode() == null ? "model_unavailable" : outcome.errorCode();
                fail(claim, code, Set.of("model_timeout", "model_unavailable").contains(code), outcome.usage(), outcome.usageStatus().name());
                return;
            }
            if (outcome.status() == ImpactAnalysisCoordinator.Status.STALE) {
                fail(claim, "analysis_input_changed", true, outcome.usage(), outcome.usageStatus().name());
                return;
            }
            String evaluation = outcome.status() == ImpactAnalysisCoordinator.Status.ANALYZED ? "COMPLETED"
                    : outcome.usageStatus() == ImpactAnalysisCoordinator.UsageStatus.NOT_STARTED ? "NOT_EVALUATED"
                    : "INSUFFICIENT_EVIDENCE";
            var envelope = outcome.aiEnvelope();
            Object ai = envelope == null ? null : envelope.get("result");
            Object generation = envelope == null ? null : ImpactRunService.generation(envelope, outcome.usage());
            Object explicit = rules.calculate(outcome.input());
            tx.executeWithoutResult(ignored -> {
                lockProject(publication.projectId());
                // Freshness and the fenced result write share the publication's project lock.
                contexts.requirePublishedUnchanged(actor, outcome.input(), publication.baselineId());
                repository.recordResult(claim, evaluation, outcome.errorCode(), ImpactFingerprint.of(outcome.input()),
                        explicit, ai, generation, outcome.usage(), outcome.usageStatus().name());
            });
        } catch (ApiException e) {
            if (e instanceof ImpactAnalysisCoordinator.PublishedRecheckException recheckFailure) {
                receivedUsage = recheckFailure.usage();
                receivedUsageStatus = recheckFailure.usageStatus().name();
            }
            boolean changed = e.getStatus().value() == 409;
            String code = "baseline_changed".equals(e.getCode()) ? "baseline_changed"
                    : changed ? "analysis_input_changed" : "analysis_access_unavailable";
            fail(claim, code, changed && !"baseline_changed".equals(code), receivedUsage, receivedUsageStatus);
        } catch (RuntimeException e) {
            // Never log or store provider payloads, database errors, or document text.
            fail(claim, "analysis_interrupted", true, receivedUsage, receivedUsageStatus);
        }
    }

    private void lockProject(long project) {
        jdbc.sql("SELECT id FROM project WHERE id=:id FOR UPDATE").param("id", project).query(Long.class).single();
    }

    private void fail(Claim claim, String code, boolean retryable, Object usage, String usageStatus) {
        Object safeUsage = usage;
        String safeStatus = usageStatus;
        try { com.beez.backend.designitem.support.DesignJson.canonical(usage); }
        catch (RuntimeException invalid) { safeUsage = List.of(); safeStatus = "UNKNOWN"; }
        final Object recordedUsage = safeUsage;
        final String recordedStatus = safeStatus;
        tx.executeWithoutResult(ignored -> repository.recordFailure(claim, code, retryable, recordedUsage, recordedStatus));
    }
}
