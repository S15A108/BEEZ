package com.beez.backend.validation.worker;

import com.beez.backend.validation.config.ValidationProperties;
import com.beez.backend.validation.repository.JobLease;
import com.beez.backend.validation.repository.ValidationRepository;
import com.beez.backend.validation.repository.ValidationRow;
import com.beez.backend.validation.service.AnalysisResultApplier;
import com.beez.backend.validation.service.ValidationAnalyzer;
import com.beez.backend.validation.service.ValidationResponses;
import com.beez.backend.validation.service.ValidationInputAssembler;
import com.beez.backend.common.auth.CallerContext;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 검증 작업 큐 워커. 한 작업 = ① 큐에서 꺼내 리스 → ② 권한을 확인하며 검색·요청 조립
 * → ③ AI 호출(트랜잭션 밖) → ④ 결과 반영(트랜잭션, {@link AnalysisResultApplier}).
 * AI 호출 중에는 DB 연결을 쥐지 않는다. 호출 뒤 반영 단계에서 아직 같은 시도·같은 리스인지 다시 확인하므로,
 * 그 사이 검증이 STALE 이 되거나 새 시도가 생겼으면 결과를 버린다.
 * 리스가 만료된 작업(워커가 AI 호출 중 죽었거나 재배포로 내려감)은 주기마다 하나씩 회수해 ERROR(RETRY)로 닫는다.
 * 같은 검증을 다시 돌리는 재시도(VAL-A04)는 -161 에서 붙인다 — 그 전에는 새 검증을 요청한다.
 */
@Component
public class ValidationWorker {

    private static final Logger log = LoggerFactory.getLogger(ValidationWorker.class);

    private final ValidationProperties properties;
    private final ValidationRepository validations;
    private final ValidationResponses responses;
    private final ValidationAnalyzer analyzer;
    private final AnalysisResultApplier applier;
    private final TransactionTemplate tx;
    private final ValidationInputAssembler assembler;

    public ValidationWorker(ValidationProperties properties, ValidationRepository validations,
                            ValidationResponses responses, ValidationAnalyzer analyzer,
                            AnalysisResultApplier applier, PlatformTransactionManager transactions,
                            ValidationInputAssembler assembler) {
        this.properties = properties;
        this.validations = validations;
        this.responses = responses;
        this.analyzer = analyzer;
        this.applier = applier;
        this.tx = new TransactionTemplate(transactions);
        this.assembler = assembler;
    }

    @Scheduled(fixedDelayString = "${beez.validation.poll-millis:2000}")
    public void poll() {
        if (!properties.workerEnabled()) {
            return;
        }
        // 한 번에 하나만 처리한다. 큐가 빌 때까지 돌면 AI 응답을 기다리는 동안 이 스레드를 오래 붙잡는다 (문서 워커와 같은 방식)
        try {
            recoverExpired();
        } catch (RuntimeException e) {
            log.error("검증 리스 회수 중 오류: {}", e.toString());
        }
        try {
            runOnce();
        } catch (RuntimeException e) {
            log.error("검증 작업 처리 중 오류: {}", e.toString());
        }
    }

    /** 리스가 만료된 작업 하나를 회수한다. 회수할 것이 없으면 false */
    public boolean recoverExpired() {
        Optional<JobLease> expired = tx.execute(status -> validations.expiredLease());
        if (expired == null || expired.isEmpty()) {
            return false;
        }
        log.warn("validation {} attempt {} 리스 만료 — ERROR(RETRY) 로 회수", expired.get().validationId(), expired.get().attempt());
        applier.applyLeaseExpired(expired.get());
        return true;
    }

    /** 작업 하나를 처리한다. 처리할 것이 없으면 false */
    public boolean runOnce() {
        Optional<JobLease> leased = tx.execute(status -> validations.lease(properties.leaseSeconds()));
        if (leased == null || leased.isEmpty()) {
            return false;
        }
        JobLease lease = leased.get();
        // 큐에서 기다리는 사이 규칙만으로 판정이 서게 됐으면(관련 작업 시작으로 점유 충돌, 참조 버전 낡음) 모델을 부르지 않는다
        if (applier.closeByRulesIfDecided(lease)) {
            return true;
        }
        long started = System.currentTimeMillis();
        Map<String, Object> request;
        ValidationInputAssembler.Result searchInput;
        CallerContext caller;
        try {
            ValidationRow row = tx.execute(status -> {
                ValidationRow v = validations.lock(lease.validationId()).orElse(null);
                return v == null || !"RUNNING".equals(v.status()) || v.currentAttempt() != lease.attempt()
                        || applier.inputChange(v).isPresent() ? null : v;
            });
            if (row == null) {
                applier.applyFailure(lease, "validation_execution_failed", "실행 전에 검증 상태가 바뀌었습니다", 0);
                return true;
            }
            caller = requestActor(row);
            // Search may call the embedding service, so do not hold a DB transaction around it.
            searchInput = assembler.assemble(caller, row.projectId(), row.taskId(), row.planRevisionId(),
                    row.id(), lease.attempt(), properties.executionBudgetMs());
            if (searchInput.baselineId() != row.baselineId()) throw new IllegalStateException("baseline changed");
            if (!searchInput.searchComplete()) {
                applier.applyInputIncomplete(lease, "검색 또는 원문 근거 범위가 불완전합니다", System.currentTimeMillis() - started);
                return true;
            }
            request = searchInput.request();
        } catch (RuntimeException e) {
            log.warn("validation {} 요청 조립 실패: {}", lease.validationId(), e.toString());
            applier.applyFailure(lease, "evidence_fetch_failed", "분석 입력이나 검색 근거를 확인하지 못했습니다",
                    System.currentTimeMillis() - started);
            return true;
        }
        if (request == null) {
            // 꺼내 보니 이미 RUNNING 이 아님 (그 사이 STALE·취소). 반영 단계가 늦은 결과로 정리한다
            applier.applyFailure(lease, "validation_execution_failed", "실행 전에 검증 상태가 바뀌었습니다", 0);
            return true;
        }

        Map<String, Object> response;
        try {
            response = analyzer.analyze(request);
        } catch (ValidationAnalyzer.AnalyzerException e) {
            // 오류 응답에 사용량이 있으면(모델을 부른 뒤 실패) 실행에 남긴다
            applier.applyFailure(lease, e.errorCode(), e.getMessage(), System.currentTimeMillis() - started, e.usage());
            return true;
        } catch (RuntimeException e) {
            applier.applyFailure(lease, "validation_execution_failed", "분석 호출 중 오류: " + e.getMessage(),
                    System.currentTimeMillis() - started);
            return true;
        }
        try {
            applier.applySearchSuccess(lease, request, response, System.currentTimeMillis() - started, caller, searchInput);
        } catch (RuntimeException e) {
            // 반영 자체가 실패하면(기록 실패) 상태 전이도 함께 롤백됐다. 실패로 종결해 사람이 재시도할 수 있게 한다.
            // 모델은 이미 불렀으므로 응답의 사용량은 실패 기록에 같이 남긴다
            log.error("validation {} 결과 반영 실패: {}", lease.validationId(), e.toString());
            applier.applyFailure(lease, "validation_execution_failed", "결과 반영 중 오류: " + e.getMessage(),
                    System.currentTimeMillis() - started, AnalysisResultApplier.usageIn(response));
        }
        return true;
    }

    private CallerContext requestActor(ValidationRow row) {
        Object raw = responses.summary(row.summary()).get("request_actor");
        if (!(raw instanceof Map<?, ?> actor) || !(actor.get("account_id") instanceof Number account)) {
            throw new IllegalStateException("검증 요청자 정보가 없습니다");
        }
        if ("HUMAN".equals(actor.get("kind"))) return CallerContext.human(account.longValue());
        if ("AGENT".equals(actor.get("kind")) && actor.get("agent_token_id") instanceof Number token) {
            return CallerContext.agent(account.longValue(), token.longValue());
        }
        throw new IllegalStateException("검증 요청자 정보가 올바르지 않습니다");
    }

}
