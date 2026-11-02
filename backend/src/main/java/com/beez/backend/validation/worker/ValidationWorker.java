package com.beez.backend.validation.worker;

import com.beez.backend.context.service.ContextLookup;
import com.beez.backend.task.dto.TaskPlanRevisionResponse;
import com.beez.backend.task.entity.TaskPlanDesignRef;
import com.beez.backend.task.entity.TaskPlanRevision;
import com.beez.backend.task.repository.TaskPlanDesignRefRepository;
import com.beez.backend.task.repository.TaskPlanRevisionRepository;
import com.beez.backend.validation.config.ValidationProperties;
import com.beez.backend.validation.dto.FindingResponse;
import com.beez.backend.validation.repository.JobLease;
import com.beez.backend.validation.repository.ValidationRepository;
import com.beez.backend.validation.repository.ValidationRow;
import com.beez.backend.validation.service.AnalysisResultApplier;
import com.beez.backend.validation.service.ValidationAnalyzer;
import com.beez.backend.validation.service.ValidationResponses;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * 검증 작업 큐 워커 (S15P31A108-159). 한 작업 = ① 큐에서 꺼내 리스(트랜잭션) → ② 요청 조립(읽기 트랜잭션)
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
    private final TaskPlanRevisionRepository revisions;
    private final TaskPlanDesignRefRepository designRefs;
    private final ContextLookup designLookup;
    private final ValidationResponses responses;
    private final ValidationAnalyzer analyzer;
    private final AnalysisResultApplier applier;
    private final TransactionTemplate tx;
    private final ObjectMapper json;

    public ValidationWorker(ValidationProperties properties, ValidationRepository validations,
                            TaskPlanRevisionRepository revisions, TaskPlanDesignRefRepository designRefs,
                            ContextLookup designLookup, ValidationResponses responses, ValidationAnalyzer analyzer,
                            AnalysisResultApplier applier, PlatformTransactionManager transactions, ObjectMapper json) {
        this.properties = properties;
        this.validations = validations;
        this.revisions = revisions;
        this.designRefs = designRefs;
        this.designLookup = designLookup;
        this.responses = responses;
        this.analyzer = analyzer;
        this.applier = applier;
        this.tx = new TransactionTemplate(transactions);
        this.json = json;
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
        try {
            request = tx.execute(status -> buildRequest(lease));
        } catch (RuntimeException e) {
            // 요청 조립은 DB 의 계획·승인 항목·출처만 읽는다. 원문 근거 검색(evidence)은 아직 붙지 않아(요청 evidence=[])
            // evidence_fetch_failed 가 아니라 BE 실행 오류로 분류한다. 원문 검색을 붙일 때 그 단계만 따로 나눈다
            log.warn("validation {} 요청 조립 실패: {}", lease.validationId(), e.toString());
            applier.applyFailure(lease, "validation_execution_failed", "분석 요청을 만들지 못했습니다: " + e.getMessage(),
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
            applier.applySuccess(lease, request, response, System.currentTimeMillis() - started);
        } catch (RuntimeException e) {
            // 반영 자체가 실패하면(기록 실패) 상태 전이도 함께 롤백됐다. 실패로 종결해 사람이 재시도할 수 있게 한다.
            // 모델은 이미 불렀으므로 응답의 사용량은 실패 기록에 같이 남긴다
            log.error("validation {} 결과 반영 실패: {}", lease.validationId(), e.toString());
            applier.applyFailure(lease, "validation_execution_failed", "결과 반영 중 오류: " + e.getMessage(),
                    System.currentTimeMillis() - started, AnalysisResultApplier.usageIn(response));
        }
        return true;
    }

    /**
     * AI-A02 요청 본문. 검증이 고정해 둔 입력(계획 리비전·참조 버전·관련 계획·검사 목록)만 쓴다.
     * 검증이 이미 RUNNING 이 아니면 null.
     */
    Map<String, Object> buildRequest(JobLease lease) {
        ValidationRow v = validations.lock(lease.validationId()).orElse(null);
        if (v == null || !"RUNNING".equals(v.status()) || v.currentAttempt() != lease.attempt()) {
            return null;
        }
        // 큐에서 기다리는 사이 분석 입력(이 작업·관련 작업의 계획)이 바뀌었으면 AI 를 부르지 않는다 (버릴 결과다).
        // null 을 돌려주면 반영 단계가 같은 확인으로 STALE 로 닫는다
        if (applier.inputChange(v).isPresent()) {
            return null;
        }
        TaskPlanRevision plan = revisions.findByIdAndTaskId(v.planRevisionId(), v.taskId()).orElseThrow();
        List<TaskPlanDesignRef> refs = designRefs.findByPlanRevisionId(v.planRevisionId());

        List<Long> versionIds = new ArrayList<>();
        for (Map<String, Object> r : validations.designRefs(v.id())) {
            versionIds.add(((Number) r.get("design_item_version_id")).longValue());
        }
        List<Long> relatedRevisionIds = new ArrayList<>();
        for (Map<String, Object> r : validations.taskRefs(v.id())) {
            relatedRevisionIds.add(((Number) r.get("related_plan_revision_id")).longValue());
        }
        List<Map<String, Object>> relatedPlans = new ArrayList<>();
        if (!relatedRevisionIds.isEmpty()) {
            Map<Long, List<TaskPlanDesignRef>> refsByRevision = new LinkedHashMap<>();
            for (TaskPlanDesignRef ref : designRefs.findByPlanRevisionIdIn(relatedRevisionIds)) {
                refsByRevision.computeIfAbsent(ref.getPlanRevisionId(), k -> new ArrayList<>()).add(ref);
            }
            for (TaskPlanRevision related : revisions.findByIdIn(relatedRevisionIds)) {
                List<TaskPlanDesignRef> relatedRefs = new ArrayList<>();
                if (refsByRevision.containsKey(related.getId())) {
                    relatedRefs = refsByRevision.get(related.getId());
                }
                relatedPlans.add(toMap(TaskPlanRevisionResponse.from(related, relatedRefs)));
            }
        }
        // 규칙 발견 항목은 명세 Finding 모양 그대로 (응답 DTO 와 같은 변환)
        List<Map<String, Object>> ruleFindings = new ArrayList<>();
        for (FindingResponse f : responses.toResponse(v).findings()) {
            if ("RULE".equals(f.source())) {
                ruleFindings.add(toMap(f));
            }
        }
        Map<String, Object> summary = responses.summary(v.summary());

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("project_id", String.valueOf(v.projectId()));
        request.put("request_key", "validate-" + v.id() + "-attempt-" + lease.attempt());
        request.put("validation_id", String.valueOf(v.id()));
        request.put("attempt", lease.attempt());
        request.put("plan_revision", toMap(TaskPlanRevisionResponse.from(plan, refs)));
        request.put("approved_items", withSources(toList(designLookup.designItemVersions(versionIds)), versionIds));
        request.put("related_plans", relatedPlans);
        request.put("rule_findings", ruleFindings);
        request.put("unresolved", List.of());          // 미확정 정책 목록은 원문 검색 연결(후속) 전까지 비어 있다
        request.put("input_snapshot_hash", v.inputSnapshotHash());
        request.put("checks", summary.get("checks"));
        request.put("evidence", List.of());            // 원문 근거 검색 연결(후속) 전까지 비어 있다 — 명세가 허용
        request.put("execution_budget_ms", properties.executionBudgetMs());
        return request;
    }

    /**
     * 승인 항목마다 원문 출처(source)를 붙인다. AI 후보의 sources 는 요청에 넣은 출처 안에서만 고를 수 있어서,
     * 출처를 넣어야 AI 가 근거 위치를 댈 수 있다. 출처가 없는 항목은 source 를 넣지 않는다(명세상 선택).
     */
    private List<Map<String, Object>> withSources(List<Map<String, Object>> items, List<Long> versionIds) {
        Map<String, Map<String, Object>> sourceByVersion = new LinkedHashMap<>();
        for (Map<String, Object> row : validations.designSources(versionIds)) {
            // 위치를 만들 수 없는 출처(문서 버전만 지정 — 설계 항목 등록에서 허용)는 넣지 않는다. 명세 Source.location 은 필수이고
            // AI 쪽 입력 검사도 빈 문자열을 거부해(422) 검증 전체가 ERROR 가 된다. 승인 항목의 source 는 선택이다
            String location = location(row.get("locator"));
            if (location == null) {
                continue;
            }
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("document_id", String.valueOf(row.get("document_id")));
            source.put("document_version_id", String.valueOf(row.get("document_version_id")));
            source.put("version_no", ((Number) row.get("version_no")).intValue());
            source.put("location", location);
            sourceByVersion.put(String.valueOf(row.get("id")), source);
        }
        for (Map<String, Object> item : items) {
            String versionId = String.valueOf(item.get("id"));
            if (sourceByVersion.containsKey(versionId)) {
                item.put("source", sourceByVersion.get(versionId));
            }
        }
        return items;
    }

    /**
     * 원문 위치 JSON → 표시용 위치. 변경 영향 분석(ImpactRequestAssembler)과 같은 규칙:
     * locator.location 이 있으면 그 값(앞뒤 공백 제거), 없고 줄 범위(type=text_lines)면 "lines {시작}-{끝}", 둘 다 아니면 null.
     */
    @SuppressWarnings("unchecked")
    private String location(Object locatorJson) {
        if (locatorJson == null) {
            return null;
        }
        Object parsed = json.readValue(String.valueOf(locatorJson), Object.class);
        if (!(parsed instanceof Map<?, ?> locator)) {
            return null;
        }
        if (locator.get("location") instanceof String text && !text.isBlank()) {
            return text.strip();
        }
        if ("text_lines".equals(locator.get("type")) && locator.get("start_line") instanceof Number start
                && locator.get("end_line") instanceof Number end && start.longValue() > 0
                && end.longValue() >= start.longValue()) {
            return "lines " + start.longValue() + "-" + end.longValue();
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(Object dto) {
        // convertValue: 응답 객체를 API 와 같은 이름(snake_case)의 Map 으로 바꾼다
        return json.convertValue(dto, Map.class);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> toList(Object dtos) {
        return json.convertValue(dtos, List.class);
    }
}
