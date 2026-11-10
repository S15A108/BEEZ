package com.beez.backend.validation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.beez.backend.task.entity.TaskPlanRevision;
import com.beez.backend.task.repository.TaskCompletionReportRepository;
import com.beez.backend.task.repository.TaskPlanDesignRefRepository;
import com.beez.backend.task.repository.TaskPlanRevisionRepository;
import com.beez.backend.task.service.DeltaCompletion;
import com.beez.backend.task.service.ProjectDesignLookup;
import com.beez.backend.task.service.RelatedTaskCandidateFinder;
import com.beez.backend.task.service.ValidationStatusLookup;
import com.beez.backend.validation.event.ConflictFindingsSaved;
import com.beez.backend.validation.repository.JobLease;
import com.beez.backend.validation.repository.ValidationRepository;
import com.beez.backend.validation.repository.ValidationRow;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 충돌 회의 개설 이벤트(S15P31A108-248)가 저장된 결과일 때만, 조건에 맞는 발견 항목에 대해서만 나가는지.
 * 통합 시험은 이벤트를 직접 발행하므로 발행 조건은 여기서 지킨다.
 */
class AnalysisResultApplierConflictEventTest {

    private final UUID token = UUID.randomUUID();
    private final JobLease lease = new JobLease(5L, 42L, 1, token);
    private final ValidationRow row = new ValidationRow(42L, 1L, 10L, 100L, 1L, "RUNNING", "AGENT", null, 1, "h", null,
            Instant.now(), null, 0);

    private ValidationRepository validations;
    private ValidationRules rules;
    private ApplicationEventPublisher publisher;
    private AnalysisResultApplier applier;

    @BeforeEach
    void setUp() {
        validations = mock(ValidationRepository.class);
        ValidationResponses responses = mock(ValidationResponses.class);
        ProjectDesignLookup designLookup = mock(ProjectDesignLookup.class);
        rules = mock(ValidationRules.class);
        TaskPlanRevisionRepository revisions = mock(TaskPlanRevisionRepository.class);
        TaskPlanDesignRefRepository designRefs = mock(TaskPlanDesignRefRepository.class);
        publisher = mock(ApplicationEventPublisher.class);
        applier = spy(new AnalysisResultApplier(validations, responses, designLookup, rules, revisions, designRefs,
                mock(ValidationInputAssembler.class), mock(RelatedTaskCandidateFinder.class), mock(JdbcClient.class), false,
                mock(DeltaCompletion.class), mock(TaskCompletionReportRepository.class), mock(ValidationStatusLookup.class),
                publisher, mock(ValidationActivities.class)));
        doReturn(null).when(applier).contractViolation(any(), any());   // 계약 검사는 AnalysisResultApplier*Test 가 본다

        TaskPlanRevision plan = mock(TaskPlanRevision.class);
        when(plan.getInputHash()).thenReturn("same");
        when(revisions.findByIdAndTaskId(100L, 10L)).thenReturn(Optional.of(plan));
        when(responses.summary(any())).thenAnswer(i -> new LinkedHashMap<String, Object>());
        when(designLookup.requireCurrentBaseline(anyLong())).thenReturn(1L);
        when(rules.evaluate(anyLong(), any(), any(), any(), any(), anyBoolean(), anyBoolean()))
                .thenReturn(new RuleOutcome("PASS", List.of(), List.of()));
        when(validations.projectIdOf(42L)).thenReturn(Optional.of(1L));
        when(validations.lock(42L)).thenReturn(Optional.of(row));
        when(validations.run(42L, 1)).thenReturn(Optional.of(Map.of("id", 7L, "lease_token", token.toString(), "status", "RUNNING")));
        when(validations.currentPlanRevision(10L)).thenReturn(Optional.of(100L));
        when(validations.taskRefs(42L)).thenReturn(List.of());
        when(validations.openTasksSharingItems(anyLong(), anyLong(), anyLong())).thenReturn(List.of());
        liveJob(false);
    }

    private void liveJob(boolean expired) {
        when(validations.jobLease(5L)).thenReturn(Optional.of(
                Map.of("status", "LEASED", "lease_token", token.toString(), "expired", expired)));
    }

    private static Map<String, Object> finding(String kind, String severity, Object relatedTaskId) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("kind", kind);
        f.put("severity", severity);
        f.put("message", "충돌 메시지");
        f.put("related_task_id", relatedTaskId);
        return f;
    }

    @SafeVarargs
    private void apply(Map<String, Object>... findings) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("analysis_status", "COMPLETE");
        result.put("findings", List.of(findings));
        result.put("checked_keys", List.of());
        result.put("unassessed", List.of());
        applier.applySuccess(lease, Map.of("checks", List.of()), Map.of("result", result), 10L);
    }

    @Test void blockingConflictWithRelatedTaskIsPublishedOnceAfterFinish() {
        apply(finding("CONFLICT", "BLOCKING", 5), finding("CONFLICT", "BLOCKING", 3), finding("CONFLICT", "BLOCKING", 5));

        var captor = ArgumentCaptor.forClass(ConflictFindingsSaved.class);
        verify(publisher).publishEvent(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new ConflictFindingsSaved(1L, 42L, 10L, List.of(3L, 5L)));   // 중복 제거·정렬
        var order = inOrder(validations, publisher);
        order.verify(validations).finish(anyLong(), any(), any());
        order.verify(publisher).publishEvent(any(ConflictFindingsSaved.class));
    }

    @Test void warningConflictIsNotPublished() {
        apply(finding("CONFLICT", "WARNING", 5), finding("CONFLICT", "INFO", 5));
        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test void conflictWithoutRelatedTaskIsNotPublished() {
        apply(finding("CONFLICT", "BLOCKING", null));
        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test void otherKindsAreNotPublished() {
        apply(finding("MISMATCH", "BLOCKING", 5));
        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test void lateResultIsNotPublished() {
        when(validations.run(42L, 1)).thenReturn(Optional.empty());
        apply(finding("CONFLICT", "BLOCKING", 5));
        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test void resultAfterInputChangeIsNotPublished() {
        when(validations.currentPlanRevision(10L)).thenReturn(Optional.empty());   // PLAN_CHANGED
        apply(finding("CONFLICT", "BLOCKING", 5));
        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test void resultAfterLeaseExpiryIsNotPublished() {
        liveJob(true);
        apply(finding("CONFLICT", "BLOCKING", 5));
        verify(publisher, never()).publishEvent(any(Object.class));
    }
}
