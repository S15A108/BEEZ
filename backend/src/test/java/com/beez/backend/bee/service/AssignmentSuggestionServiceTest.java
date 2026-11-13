package com.beez.backend.bee.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.beez.backend.ai.AiClient;
import com.beez.backend.ai.AiUsageRecorder;
import com.beez.backend.common.access.ActorType;
import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.access.ProjectActor;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.project.ProjectRole;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;

class AssignmentSuggestionServiceTest {
    private final ProjectAccessChecker access = mock(ProjectAccessChecker.class);
    private final AssignmentSnapshotService snapshots = mock(AssignmentSnapshotService.class);
    private final AiClient ai = mock(AiClient.class);
    private final AiUsageRecorder usage = mock(AiUsageRecorder.class);
    private final AssignmentSuggestionService service = new AssignmentSuggestionService(access, snapshots, ai, usage);
    private final CallerContext caller = CallerContext.human(11L);
    private final AssignmentSnapshotService.Snapshot snapshot = new AssignmentSnapshotService.Snapshot(
            Map.of("project_id", "101", "current_tasks_complete", true), Set.of("501"), Set.of("21", "22"));

    @BeforeEach void setup() {
        when(access.requireMember(caller, 101)).thenReturn(new ProjectActor(11, 21, 101, ProjectRole.MEMBER, ActorType.HUMAN, null));
        when(snapshots.capture(caller, 101, List.of(501L), true)).thenReturn(snapshot);
        when(snapshots.capture(caller, 101, List.of(501L), false)).thenReturn(snapshot);
        when(usage.record(anyString(), anyLong(), isNull(), anyString(), anyMap(), anyLong())).thenReturn(1);
    }

    private Map<String, Object> reply(String memberId) {
        return Map.of("origin", "MODEL", "model", "model", "usage", List.of(), "result", Map.of(
                "suggestions", List.of(Map.of("task_id", "501", "member_id", memberId, "reason", "담당 영역을 참고했습니다.",
                        "alternatives", List.of(Map.of("member_id", "22", "reason", "업무량을 참고했습니다.")))),
                "unresolved", List.of("선행 작업 확인 필요")));
    }

    @Test void returnsReasonsAndAlternativesAfterRecheckingSnapshot() {
        when(ai.assignmentRecommendations(anyMap())).thenReturn(reply("21"));
        Map<String, Object> response = service.suggest(caller, 101, List.of("501"));
        assertThat(response).containsKeys("suggestions", "generation");
        assertThat(response.get("suggestions").toString()).contains("담당 영역", "업무량", "22");
        verify(snapshots).capture(caller, 101, List.of(501L), false);
        verify(usage).record(eq("TASK_ASSIGNMENT"), eq(101L), isNull(), anyString(), anyMap(), anyLong());
    }

    @Test void refusesAResultWhenProjectDataChangedDuringTheCall() {
        when(ai.assignmentRecommendations(anyMap())).thenReturn(reply("21"));
        when(snapshots.capture(caller, 101, List.of(501L), false)).thenReturn(
                new AssignmentSnapshotService.Snapshot(Map.of("project_id", "101", "current_tasks_complete", false),
                        snapshot.taskIds(), snapshot.memberIds()));
        assertThatThrownBy(() -> service.suggest(caller, 101, List.of("501")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getCode()).isEqualTo("recommendation_stale");
                });
        verify(usage).record(eq("TASK_ASSIGNMENT"), eq(101L), isNull(), anyString(), anyMap(), anyLong());
    }

    @Test void refusesUnknownMemberFromAi() {
        Map<String, Object> invalid = new java.util.LinkedHashMap<>(reply("999"));
        invalid.put("usage", List.of(Map.of("tokens_known", true, "input_tokens", 5, "failed", false)));
        when(ai.assignmentRecommendations(anyMap())).thenReturn(invalid);
        assertThatThrownBy(() -> service.suggest(caller, 101, List.of("501")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("ai_unavailable"));
        verify(snapshots, never()).capture(caller, 101, List.of(501L), false);
        verify(usage).recordLines(eq("TASK_ASSIGNMENT"), eq(101L), isNull(), anyString(),
                argThat(lines -> lines.size() == 1 && Boolean.TRUE.equals(((Map<?, ?>) lines.getFirst()).get("failed"))),
                eq(false), eq("model"), anyLong());
    }

    private static HttpServerErrorException aiError(HttpStatus status, String code) {
        byte[] body = ("{\"error\":{\"code\":\"" + code + "\",\"message\":\"x\"}}").getBytes(StandardCharsets.UTF_8);
        return HttpServerErrorException.create(status, status.getReasonPhrase(), new HttpHeaders(), body, StandardCharsets.UTF_8);
    }

    @Test void switchedOffFeatureIsReportedAsDisabledAndLeavesNoFailedUsage() {
        when(ai.assignmentRecommendations(anyMap()))
                .thenThrow(aiError(HttpStatus.SERVICE_UNAVAILABLE, "assignment_recommendation_not_configured"));
        assertThatThrownBy(() -> service.suggest(caller, 101, List.of("501")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.getCode()).isEqualTo("assignment_suggestion_disabled");
                    assertThat(e.getMessage()).doesNotContain("다시");
                });
        // 모델을 부르지 않았으니 사용량·실패 기록을 남기지 않는다
        verify(usage, never()).recordFailure(anyString(), anyLong(), any(), anyString(), anyLong());
        verify(usage, never()).recordLines(anyString(), anyLong(), any(), anyString(), anyList(), anyBoolean(), any(), anyLong());
        verify(usage, never()).record(anyString(), anyLong(), any(), anyString(), anyMap(), anyLong());
        verify(snapshots, never()).capture(caller, 101, List.of(501L), false);
    }

    @Test void otherAiFailuresStayRetryableAndAreRecorded() {
        // 같은 503 이라도 꺼짐 코드가 아니면 일시 장애로 본다
        when(ai.assignmentRecommendations(anyMap()))
                .thenThrow(aiError(HttpStatus.SERVICE_UNAVAILABLE, "something_else"))
                .thenThrow(aiError(HttpStatus.BAD_GATEWAY, "model_unavailable"));
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> service.suggest(caller, 101, List.of("501")))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
                        assertThat(e.getCode()).isEqualTo("ai_unavailable");
                    });
        }
        verify(usage, times(2)).recordFailure(eq("TASK_ASSIGNMENT"), eq(101L), isNull(), anyString(), anyLong());
    }

    @Test void duplicateTargetsNeverCallAi() {
        assertThatThrownBy(() -> service.suggest(caller, 101, List.of("501", "501")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("validation_failed"));
        verifyNoInteractions(ai);
    }

    @Test void agentIsRejectedByHumanOnlyServiceRule() {
        CallerContext agent = CallerContext.agent(11L, 99L);
        when(access.requireMember(agent, 101)).thenReturn(new ProjectActor(11, 21, 101, ProjectRole.MEMBER, ActorType.AGENT, 99L));
        assertThatThrownBy(() -> service.suggest(agent, 101, List.of("501")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(e.getCode()).isEqualTo("forbidden");
                });
        verifyNoInteractions(ai, snapshots);
    }
}
