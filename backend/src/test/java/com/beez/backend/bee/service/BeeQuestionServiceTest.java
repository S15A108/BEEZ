package com.beez.backend.bee.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.beez.backend.ai.AiClient;
import com.beez.backend.ai.AiUsageRecorder;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.document.service.DocumentIndexWorker;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class BeeQuestionServiceTest {
    private final BeeQuestionSnapshot snapshots = mock(BeeQuestionSnapshot.class);
    private final DocumentIndexWorker search = mock(DocumentIndexWorker.class);
    private final AiClient ai = mock(AiClient.class);
    private final AiUsageRecorder usage = mock(AiUsageRecorder.class);
    private final CallerContext caller = CallerContext.human(1L);
    private final BeeQuestionService service = new BeeQuestionService(snapshots, search, ai, usage);
    private final BeeQuestionSnapshot.Snapshot snapshot = new BeeQuestionSnapshot.Snapshot(10L, "profile", List.of("31"), List.of(), List.of(), List.of(), List.of());

    @BeforeEach void setup() {
        when(snapshots.capture(caller, 2L, null)).thenReturn(snapshot);
        when(search.search(eq(2L), anyString(), isNull(), isNull(), isNull(), eq(12))).thenReturn(Map.of("items", List.of()));
        when(snapshots.evidence(caller, 2L, snapshot, List.of())).thenReturn(List.of());
        when(snapshots.issue(eq(caller), eq(2L), isNull(), eq(snapshot), eq(List.of()), eq(List.of()), eq(List.of()))).thenReturn(71L);
        when(usage.record(eq("BEE_CHAT"), eq(2L), isNull(), anyString(), anyMap(), anyLong())).thenReturn(1);
        when(ai.beeChat(anyMap())).thenAnswer(call -> emptyAnswer(call.getArgument(0)));
    }

    private Map<String, Object> emptyAnswer(Map<String, Object> input) {
        var result = new LinkedHashMap<String, Object>();
        result.put("answer_status", "INSUFFICIENT_EVIDENCE"); result.put("grounded", false);
        result.put("text", "찾지 못했어요. 지금 승인된 문서와 설계 항목에서는 이 질문에 답할 근거를 찾지 못했습니다. 짐작해서 답하지 않습니다.");
        result.put("citations", List.of()); result.put("sources", List.of()); result.put("design_refs", List.of());
        result.put("task_refs", List.of()); result.put("unresolved", List.of()); result.put("scope", input.get("scope"));
        result.put("embedding_profile_id", input.get("embedding_profile_id"));
        result.put("verification", Map.of("level", "NONE", "claims", 0));
        return Map.of("origin", "MODEL", "model", "test-model", "result", result,
                "usage", List.of(Map.of("feature", "BEE_CHAT", "request_key", "actual-call", "model", "test-model", "latency_ms", 1, "tokens_known", false, "failed", false)));
    }

    @Test void approvedSearchUsesTheEntireQuestionAndReturnsAnIssuedAnswer() {
        String question = "질".repeat(2000);
        var result = service.answer(caller, 2L, question, null, "APPROVED", "unique-key");
        assertThat(result).containsEntry("answer_id", "71").containsEntry("grounded", false);
        verify(search).search(2L, question, null, null, null, 12);
        org.mockito.ArgumentCaptor<Map<String, Object>> input = org.mockito.ArgumentCaptor.captor();
        verify(ai).beeChat(input.capture());
        assertThat(input.getValue()).containsEntry("question", question).containsEntry("embedding_profile_id", "profile");
        assertThat(input.getValue().get("scope")).isEqualTo(Map.of("mode", "APPROVED", "baseline_id", "10", "document_version_ids", List.of("31")));
        verify(snapshots).reserve(eq(caller), eq(2L), argThat(hash -> hash.matches("[0-9a-f]{64}") && !hash.equals("unique-key")));
    }

    @Test void draftScopeIsNotSilentlyChangedToApproved() {
        assertThatThrownBy(() -> service.answer(caller, 2L, "질문", null, "INCLUDE_DRAFTS", "key"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("draft_search_not_supported"));
        verifyNoInteractions(search, ai);
    }

    @Test void duplicateKeyNeverCallsSearchOrModel() {
        doThrow(new ApiException(HttpStatus.CONFLICT, "idempotency_conflict", "이미 사용한 키"))
                .when(snapshots).reserve(eq(caller), eq(2L), anyString());
        assertThatThrownBy(() -> service.answer(caller, 2L, "질문", null, null, "key"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("idempotency_conflict"));
        verifyNoInteractions(search, ai);
    }

    @Test void authorizationFailureNeverCallsSearchOrModel() {
        doThrow(new ApiException(HttpStatus.NOT_FOUND, "not_found", "not found")).when(snapshots).authorize(caller, 2L);
        assertThatThrownBy(() -> service.answer(caller, 2L, "질문", null, "INCLUDE_DRAFTS", "key"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("not_found"));
        verifyNoInteractions(search, ai);
    }

    @Test void changedBaselineAfterModelCallDoesNotIssueAnAnswer() {
        when(snapshots.issue(eq(caller), eq(2L), isNull(), eq(snapshot), eq(List.of()), eq(List.of()), eq(List.of())))
                .thenThrow(new ApiException(HttpStatus.CONFLICT, "baseline_changed", "변경됨"));
        assertThatThrownBy(() -> service.answer(caller, 2L, "질문", null, null, "key"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("baseline_changed"));
        verify(usage).record(eq("BEE_CHAT"), eq(2L), isNull(), anyString(), anyMap(), anyLong());
    }

    @Test void malformedSearchResultReturnsAiUnavailable() {
        when(search.search(eq(2L), anyString(), isNull(), isNull(), isNull(), eq(12))).thenReturn(null);
        assertThatThrownBy(() -> service.answer(caller, 2L, "질문", null, null, "key"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("ai_unavailable"));
        verifyNoInteractions(ai);
    }

    @Test void malformedModelOutputIsNotIssuedAndFailedUsageIsRecorded() {
        when(ai.beeChat(anyMap())).thenReturn(Map.of("origin", "MODEL", "result", Map.of()));
        assertThatThrownBy(() -> service.answer(caller, 2L, "질문", null, null, "key"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("ai_unavailable"));
        verify(snapshots, never()).issue(any(), anyLong(), any(), any(), anyList(), anyList(), anyList());
        verify(usage).recordFailure(eq("BEE_CHAT"), eq(2L), isNull(), anyString(), anyLong());
    }
}
