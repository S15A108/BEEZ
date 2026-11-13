package com.beez.backend.impactanalysis;

import com.beez.backend.ai.AiClient;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.access.ProjectLogRedaction;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.config.BeezProperties;
import com.beez.backend.impactanalysis.intake.PublishedAnalysisRepository;
import com.beez.backend.impactanalysis.intake.PublishedAnalysisRepository.Claim;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
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
            PlatformTransactionManager transactions, BeezProperties properties,
            @Value(AiClient.CHANGE_IMPACT_READ_TIMEOUT) Duration impactReadTimeout) {
        this.repository = repository;
        this.coordinator = coordinator;
        this.contexts = contexts;
        this.rules = rules;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        // 영향 분석 전용 읽기 제한(AiClient 와 같은 값)으로 잡아, 긴 정상 분석 중에 lease 가 끝나 중단으로 읽히지 않게 한다 (S15P31A108-402)
        this.leaseSeconds = AiClient.changeImpactLeaseSeconds(properties.ai(), impactReadTimeout);
    }

    /**
     * 자동 재시도할 AI 실패. 일시 장애(시간 초과·연결 실패)는 재시도 정책의 횟수(최대 4회)대로 다시 시도한다.
     * 출력 상한 잘림(model_output_truncated)은 첫 시도에서만 한 번 더 시도한다. 추론 길이가 매번 달라 다음 시도는 들어갈 수 있지만,
     * 같은 상한으로 계속 끊기면 시도마다 입력 전체 비용만 나간다(11-11 잘림 4회 반복). 최종 FAILED 는 그 버전을 다시 평가할 수
     * 없게 남기므로(뒤 발행이 나오면 baseline_changed) 첫 잘림에 바로 닫지 않는다 (S15P31A108-402, #286 리뷰, -408).
     */
    static boolean retryableAiFailure(String code, int attempt) {
        if ("model_output_truncated".equals(code)) return attempt == 1;
        return Set.of("model_timeout", "model_unavailable").contains(code);
    }

    public void process(long id) {
        try (var ignored = ProjectLogRedaction.open()) {
            processIntake(id);
        }
    }

    private void processIntake(long id) {
        var claimed = repository.claimNow(id, leaseSeconds);
        if (claimed.isEmpty()) return;
        Claim claim = claimed.get();
        List<?> receivedUsage = List.of();
        String receivedUsageStatus = "UNKNOWN";
        // 응답 최상위 model. 사용량 항목에 model 이 없을 때 공통 사용량 표에 쓴다 (-169)
        String receivedModel = null;
        try {
            var publication = repository.publication(id);
            // The publisher is revalidated as an active member on every input/freshness read;
            // no synthetic administrator or revoked credential is used.
            var actor = CallerContext.human(publication.publishedBy());
            var outcome = coordinator.analyzePublished(actor, publication.projectId(), publication.proposalId(),
                    publication.baselineId(), "published-impact-" + id + "-" + claim.attempt());
            receivedUsage = outcome.usage();
            receivedUsageStatus = outcome.usageStatus().name();
            receivedModel = outcome.model();
            if (outcome.status() == ImpactAnalysisCoordinator.Status.AI_FAILED) {
                String code = outcome.errorCode() == null ? "model_unavailable" : outcome.errorCode();
                fail(claim, code, retryableAiFailure(code, claim.attempt()), outcome.usage(), outcome.usageStatus().name(),
                        outcome.model());
                return;
            }
            if (outcome.status() == ImpactAnalysisCoordinator.Status.STALE) {
                fail(claim, "analysis_input_changed", true, outcome.usage(), outcome.usageStatus().name(), outcome.model());
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
                // 비용 집계(llm_usage)는 결과 저장과 같은 트랜잭션. 되돌려지면 아래 실패 처리가 같은 사용량으로 다시 적는다 (-169)
                repository.recordUsage(claim, outcome.usage(), outcome.model());
                repository.recordResult(claim, evaluation, outcome.errorCode(), ImpactFingerprint.of(outcome.input()),
                        explicit, ai, generation, outcome.usage(), outcome.usageStatus().name());
            });
        } catch (ApiException e) {
            if (e instanceof ImpactAnalysisCoordinator.AccessRecheckException recheckFailure) {
                receivedUsage = recheckFailure.usage();
                receivedUsageStatus = recheckFailure.usageStatus().name();
                receivedModel = ImpactAnalysisCoordinator.receivedModel(e);
            }
            boolean changed = e.getStatus().value() == 409;
            String code = "baseline_changed".equals(e.getCode()) ? "baseline_changed"
                    : changed ? "analysis_input_changed" : "analysis_access_unavailable";
            fail(claim, code, changed && !"baseline_changed".equals(code), receivedUsage, receivedUsageStatus, receivedModel);
        } catch (RuntimeException e) {
            // Never log or store provider payloads, database errors, or document text.
            // 모델 응답 뒤 재확인 조회가 실패한 경우(ReceivedUsageException)에는 받은 사용량이 실려 온다
            List<?> carried = ImpactAnalysisCoordinator.receivedUsage(e);
            if (receivedUsage.isEmpty() && !carried.isEmpty()) {
                receivedUsage = carried;
                receivedUsageStatus = "REPORTED";
                receivedModel = ImpactAnalysisCoordinator.receivedModel(e);
            }
            fail(claim, "analysis_interrupted", true, receivedUsage, receivedUsageStatus, receivedModel);
        }
    }

    private void lockProject(long project) {
        jdbc.sql("SELECT id FROM project WHERE id=:id FOR UPDATE").param("id", project).query(Long.class).single();
    }

    private void fail(Claim claim, String code, boolean retryable, Object usage, String usageStatus, String model) {
        Object safeUsage = usage;
        String safeStatus = usageStatus;
        try { com.beez.backend.designitem.support.DesignJson.canonical(usage); }
        catch (RuntimeException invalid) { safeUsage = List.of(); safeStatus = "UNKNOWN"; }
        final Object recordedUsage = safeUsage;
        final String recordedStatus = safeStatus;
        tx.executeWithoutResult(ignored -> {
            // 프로젝트 행을 맨 먼저 잠근다 — 선점·결과 저장과 같은 잠금 순서. 사용량 기록이 프로젝트 행을 외래 키로 먼저 잡은 뒤
            // 잠금을 올리면 그 사이 프로젝트 행을 기다리는 선점과 엇갈릴 수 있다 (S15P31A108-221 리뷰)
            repository.lockProjectOf(claim.id());
            // 비용 집계에는 시도 기록에 넣기에 너무 커서 비운 경우에도 받은 사용량 그대로 (같은 키는 한 번만)
            repository.recordUsage(claim, usage, model);
            repository.recordFailure(claim, code, retryable, recordedUsage, recordedStatus);
        });
    }
}
