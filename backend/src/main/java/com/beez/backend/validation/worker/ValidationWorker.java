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
 * 리스 만료 회수(워커 중단)는 검증 중복 실행·타임아웃·재시도 작업(-161)에서 붙인다.
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
            runOnce();
        } catch (RuntimeException e) {
            log.error("검증 작업 처리 중 오류: {}", e.toString());
        }
    }

    /** 작업 하나를 처리한다. 처리할 것이 없으면 false */
    public boolean runOnce() {
        Optional<JobLease> leased = tx.execute(status -> validations.lease(properties.leaseSeconds()));
        if (leased == null || leased.isEmpty()) {
            return false;
        }
        JobLease lease = leased.get();
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
            applier.applyFailure(lease, e.errorCode(), e.getMessage(), System.currentTimeMillis() - started);
            return true;
        } catch (RuntimeException e) {
            applier.applyFailure(lease, "validation_execution_failed", "분석 호출 중 오류: " + e.getMessage(),
                    System.currentTimeMillis() - started);
            return true;
        }
        try {
            applier.applySuccess(lease, request, response, System.currentTimeMillis() - started);
        } catch (RuntimeException e) {
            // 반영 자체가 실패하면(기록 실패) 상태 전이도 함께 롤백됐다. 실패로 종결해 사람이 재시도할 수 있게 한다
            log.error("validation {} 결과 반영 실패: {}", lease.validationId(), e.toString());
            applier.applyFailure(lease, "validation_execution_failed", "결과 반영 중 오류: " + e.getMessage(),
                    System.currentTimeMillis() - started);
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
        // 큐에서 기다리는 사이 계획이 바뀌었으면 AI 를 부르지 않는다 (지난 계획을 분석해 봐야 버릴 결과다).
        // null 을 돌려주면 반영 단계가 STALE(PLAN_CHANGED) 로 닫는다
        Optional<Long> currentPlan = validations.currentPlanRevision(v.taskId());
        if (currentPlan.isEmpty() || !currentPlan.get().equals(v.planRevisionId())) {
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
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("document_id", String.valueOf(row.get("document_id")));
            source.put("document_version_id", String.valueOf(row.get("document_version_id")));
            source.put("version_no", ((Number) row.get("version_no")).intValue());
            source.put("location", String.valueOf(row.get("location")));
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
