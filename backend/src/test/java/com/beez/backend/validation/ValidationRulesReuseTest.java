package com.beez.backend.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.beez.backend.common.access.ActorType;
import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.access.ProjectActor;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.project.ProjectRole;
import com.beez.backend.task.entity.Task;
import com.beez.backend.task.entity.TaskPlanRevision;
import com.beez.backend.task.entity.TaskStatus;
import com.beez.backend.task.repository.TaskPlanDesignRefRepository;
import com.beez.backend.task.repository.TaskPlanRevisionRepository;
import com.beez.backend.task.repository.TaskRepository;
import com.beez.backend.task.service.ProjectDesignLookup;
import com.beez.backend.validation.config.ValidationProperties;
import com.beez.backend.validation.dto.ValidationResponse;
import com.beez.backend.validation.repository.ValidationRepository;
import com.beez.backend.validation.repository.ValidationRow;
import com.beez.backend.validation.service.RuleOutcome;
import com.beez.backend.validation.service.ValidationAcceptService;
import com.beez.backend.validation.service.ValidationActivities;
import com.beez.backend.validation.service.ValidationResponses;
import com.beez.backend.validation.service.ValidationRules;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.json.JsonMapper;

class ValidationRulesReuseTest {
    private final ProjectAccessChecker access = mock(ProjectAccessChecker.class);
    private final ProjectDesignLookup design = mock(ProjectDesignLookup.class);
    private final TaskRepository tasks = mock(TaskRepository.class);
    private final TaskPlanRevisionRepository revisions = mock(TaskPlanRevisionRepository.class);
    private final TaskPlanDesignRefRepository refs = mock(TaskPlanDesignRefRepository.class);
    private final ValidationRepository validations = mock(ValidationRepository.class);
    private final ValidationRules rules = mock(ValidationRules.class);
    private final ValidationResponses responses = mock(ValidationResponses.class);
    private final ValidationProperties properties = mock(ValidationProperties.class);
    private final ValidationActivities activities = mock(ValidationActivities.class);
    private final Task task = mock(Task.class);
    private final TaskPlanRevision plan = mock(TaskPlanRevision.class);
    private final CallerContext caller = CallerContext.human(1L);
    private final ValidationAcceptService service = new ValidationAcceptService(access, design, tasks, revisions, refs,
            validations, rules, responses, JsonMapper.builder().build(), properties, activities, mock(ApplicationEventPublisher.class));

    @BeforeEach void setUp() {
        when(access.requireMember(caller, 10L)).thenReturn(new ProjectActor(1, 2, 10, ProjectRole.MEMBER, ActorType.HUMAN, null));
        when(design.lockProject(10L)).thenReturn(true);
        when(design.requireCurrentBaseline(10L)).thenReturn(30L);
        when(tasks.findByIdAndProjectId(20L, 10L)).thenReturn(Optional.of(task));
        when(task.getId()).thenReturn(20L);
        when(task.getAssigneeMemberId()).thenReturn(2L);
        when(task.getStatus()).thenReturn(TaskStatus.READY);
        when(task.getCurrentPlanRevisionId()).thenReturn(40L);
        when(task.getLockVersion()).thenReturn(0);
        when(revisions.findByIdAndTaskId(40L, 20L)).thenReturn(Optional.of(plan));
        when(plan.getInputHash()).thenReturn("plan-hash");
        when(refs.findByPlanRevisionId(40L)).thenReturn(List.of());
        when(validations.openTasksSharingItems(10L, 20L, 40L)).thenReturn(List.of());
    }

    @Test void rulesPassReturnsPriorResultAfterRecheckingRulesAndAuthorization() {
        when(rules.evaluate(eq(10L), eq(30L), eq(plan), anyList(), anyList(), eq(false), eq(false)))
                .thenReturn(new RuleOutcome("PASS", List.of(), List.of()));
        var row = new ValidationRow(7L, 10L, 20L, 40L, 30L, "PASS", "AGENT", null, 0,
                "hash", "{}", Instant.now(), Instant.now(), 0);
        var response = mock(ValidationResponse.class);
        when(validations.reusableRulesOnlyPass(eq(10L), eq(20L), eq(40L), eq(30L), anyString(),
                eq(ValidationRules.RULE_VERSION))).thenReturn(Optional.of(row));
        when(responses.toResponse(row, true)).thenReturn(response);

        var result = service.accept(caller, 10L, 20L, 40L, 0);
        assertThat(result.finished()).isTrue();
        assertThat(result.validation()).isSameAs(response);
        verify(validations, never()).insertValidation(anyLong(), anyLong(), anyLong(), anyString(), anyString(),
                anyString(), anyString(), anyInt(), anyMap(), anyBoolean());
        verify(activities, never()).finishedBy(any(), any(), anyString());
    }

    @Test void deniedOwnerNeverLooksUpPriorResult() {
        when(task.getAssigneeMemberId()).thenReturn(3L);
        assertThatThrownBy(() -> service.accept(caller, 10L, 20L, 40L, 0)).isInstanceOf(ApiException.class);
        verify(validations, never()).reusableRulesOnlyPass(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), anyString());
    }

    @Test void freshRulesRequiringAnalysisNeverConsultRulesOnlyCache() {
        when(rules.evaluate(eq(10L), eq(30L), eq(plan), anyList(), anyList(), eq(false), eq(false)))
                .thenReturn(new RuleOutcome(null, List.of(), List.of(java.util.Map.of("key", "design"))));
        when(properties.maxModelAttempts()).thenReturn(3);
        when(validations.insertValidation(anyLong(), anyLong(), anyLong(), anyString(), anyString(),
                anyString(), anyString(), anyInt(), anyMap(), anyBoolean())).thenReturn(8L);
        var row = new ValidationRow(8L, 10L, 20L, 40L, 30L, "RUNNING", "AGENT", null, 1,
                "hash", "{}", Instant.now(), null, 0);
        when(validations.find(10L, 8L)).thenReturn(Optional.of(row));
        var result = service.accept(caller, 10L, 20L, 40L, 0);
        assertThat(result.finished()).isFalse();
        verify(validations, never()).reusableRulesOnlyPass(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), anyString());
        verify(validations).insertJob(8L);
    }

    @Test void policyModeFailsClosedEvenIfRulesUnexpectedlyReturnPass() {
        when(properties.policyAnalysisEnabled()).thenReturn(true);
        when(rules.evaluate(eq(10L), eq(30L), eq(plan), anyList(), anyList(), eq(false), eq(true)))
                .thenReturn(new RuleOutcome("PASS", List.of(), List.of()));
        when(validations.insertValidation(anyLong(), anyLong(), anyLong(), anyString(), anyString(),
                anyString(), anyString(), anyInt(), anyMap(), anyBoolean())).thenReturn(9L);
        var row = new ValidationRow(9L, 10L, 20L, 40L, 30L, "PASS", "AGENT", null, 0,
                "hash", "{}", Instant.now(), Instant.now(), 0);
        when(validations.find(10L, 9L)).thenReturn(Optional.of(row));
        assertThat(service.accept(caller, 10L, 20L, 40L, 0).finished()).isTrue();
        verify(validations, never()).reusableRulesOnlyPass(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), anyString());
    }
}
