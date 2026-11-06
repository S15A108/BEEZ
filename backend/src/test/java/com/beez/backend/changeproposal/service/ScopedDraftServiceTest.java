package com.beez.backend.changeproposal.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.beez.backend.ai.AiClient;
import com.beez.backend.ai.AiUsageRecorder;
import com.beez.backend.changeproposal.repository.DraftContextRepository;
import com.beez.backend.changeproposal.repository.ProposalRepository;
import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.designitem.repository.DesignProjectRepository;
import com.beez.backend.designitem.service.DesignCommandService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.client.ResourceAccessException;

class ScopedDraftServiceTest {
    final ProjectAccessChecker access = mock(ProjectAccessChecker.class);
    final DesignProjectRepository projects = mock(DesignProjectRepository.class);
    final ProposalRepository proposals = mock(ProposalRepository.class);
    final DraftContextRepository context = mock(DraftContextRepository.class);
    final DesignCommandService idempotency = mock(DesignCommandService.class);
    final AiClient ai = mock(AiClient.class);
    final AiUsageRecorder usage = mock(AiUsageRecorder.class);
    final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    final CallerContext caller = CallerContext.human(5L);
    final String blockId = "blk_" + "b".repeat(64);
    ScopedDraftService service;

    @BeforeEach void setUp() {
        when(manager.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
        when(context.projectActive(1)).thenReturn(true);
        when(context.documentInProject(1, 2)).thenReturn(true);
        when(context.currentBaseline(1)).thenReturn(3L);
        when(context.baselineVersion(3, 2)).thenReturn(Optional.of(Map.of("id", 4L, "type", "PLAN",
                "storage_key", "document.txt", "content_hash", "sha256:" + "a".repeat(64), "content_text", "원문\n")));
        service = new ScopedDraftService(access, projects, proposals, context, idempotency, ai, usage, manager);
        when(projects.lockActiveProject(1)).thenReturn(true);
    }

    @Test void rejectsEmptySelectionBeforeAiCall() {
        var input = new ScopedDraftService.ScopedRequest("2", "4", List.of(), "수정", null);
        assertThatThrownBy(() -> service.generate(caller, 1, input, "key")).isInstanceOf(ApiException.class);
        verifyNoInteractions(ai);
    }

    @Test void readsPublicSnakeCaseFieldsAndRejectsInstructionOverLimit() {
        var input = com.beez.backend.designitem.support.DesignJson.JSON.readValue(
                "{\"document_id\":\"2\",\"base_document_version_id\":\"4\",\"selected_block_ids\":[\"" + blockId + "\"],\"instruction\":\"수정\"}",
                ScopedDraftService.ScopedRequest.class);
        assertThat(input.documentId()).isEqualTo("2");
        assertThat(input.baseDocumentVersionId()).isEqualTo("4");
        assertThat(input.selectedBlockIds()).containsExactly(blockId);
        assertThatThrownBy(() -> service.generate(caller, 1,
                new ScopedDraftService.ScopedRequest("2", "4", List.of(blockId), "x".repeat(4001), null), "key"))
                .isInstanceOf(ApiException.class);
        verifyNoInteractions(ai);
    }

    @Test void refusesAnOutdatedVersionBeforeAiCall() {
        var input = new ScopedDraftService.BlockRequest("2", "9");
        assertThatThrownBy(() -> service.blocks(caller, 1, input)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo("version_conflict"));
        verifyNoInteractions(ai);
    }

    @Test void generatesOnlySelectedTextAndRecordsUsage() {
        when(ai.revisionBlocks(any())).thenAnswer(call -> {
            Map<?, ?> source = call.getArgument(0);
            return Map.of("document_version_id", "4", "text_hash", source.get("text_hash"), "parser_profile", "plain-v1",
                    "normalization_version", "utf8-lf-v1", "parser_version", "v1", "blocks", List.of(Map.of(
                            "block_id", blockId, "block_type", "paragraph", "locator", Map.of("type", "text_lines", "start_line", 1, "end_line", 1), "text", "원문\n")));
        });
        when(ai.documentRevision(any())).thenReturn(Map.of("origin", "MODEL", "model", "test", "usage", List.of(), "result", Map.of(
                "document_version_id", "4", "after_content", "수정\n", "changes", List.of(Map.of("block_id", blockId,
                        "before", "원문\n", "after", "수정\n", "reason", "수정", "evidence", List.of())), "unresolved", List.of())));
        var result = service.generate(caller, 1, new ScopedDraftService.ScopedRequest("2", "4", List.of(blockId), "수정", null), "key");
        assertThat(result).containsEntry("after_content", "수정\n");
        verify(usage).record(eq("BEE_DRAFT"), eq(1L), isNull(), anyString(), anyMap(), anyLong());
        verify(idempotency).save(eq(1L), eq(5L), anyString(), eq("key"), any(), any());
    }

    @Test void rejectsAStoredReplayWhenTheBaseVersionIsNoLongerCurrent() {
        when(idempotency.replay(eq(1L), eq(5L), anyString(), eq("key"), any(), eq(Map.class)))
                .thenReturn(Optional.of(Map.of("after_content", "old")));
        var input = new ScopedDraftService.ScopedRequest("2", "9", List.of(blockId), "수정", null);
        assertThatThrownBy(() -> service.generate(caller, 1, input, "key"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("version_conflict"));
        verifyNoInteractions(ai);
    }

    @Test void rejectsNonMemberBeforeLoadingDocumentOrCallingAi() {
        doThrow(new ApiException(org.springframework.http.HttpStatus.NOT_FOUND, "not_member", "not found"))
                .when(access).requireMember(caller, 1);
        assertThatThrownBy(() -> service.blocks(caller, 1, new ScopedDraftService.BlockRequest("2", "4")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("not_member"));
        verifyNoInteractions(context, ai);
    }

    @Test void recordsFailedUsageWhenModelConnectionFails() {
        when(ai.revisionBlocks(any())).thenAnswer(call -> {
            Map<?, ?> source = call.getArgument(0);
            return Map.of("document_version_id", "4", "text_hash", source.get("text_hash"), "parser_profile", "plain-v1",
                    "normalization_version", "utf8-lf-v1", "parser_version", "v1", "blocks", List.of(Map.of(
                            "block_id", blockId, "block_type", "paragraph", "locator", Map.of("type", "text_lines", "start_line", 1, "end_line", 1), "text", "원문\n")));
        });
        when(ai.documentRevision(any())).thenThrow(new ResourceAccessException("connection failed"));
        var input = new ScopedDraftService.ScopedRequest("2", "4", List.of(blockId), "수정", null);
        assertThatThrownBy(() -> service.generate(caller, 1, input, "key"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("ai_unavailable"));
        verify(usage).recordFailure(eq("BEE_DRAFT"), eq(1L), isNull(), anyString(), anyLong());
        verify(idempotency, never()).save(anyLong(), anyLong(), anyString(), anyString(), any(), any());
    }

    @Test void recordsUnknownUsageForUnexpectedModelClientFailure() {
        when(ai.revisionBlocks(any())).thenAnswer(call -> {
            Map<?, ?> source = call.getArgument(0);
            return Map.of("document_version_id", "4", "text_hash", source.get("text_hash"), "parser_profile", "plain-v1",
                    "normalization_version", "utf8-lf-v1", "parser_version", "v1", "blocks", List.of(Map.of(
                            "block_id", blockId, "block_type", "paragraph", "locator", Map.of("type", "text_lines", "start_line", 1, "end_line", 1), "text", "원문\n")));
        });
        when(ai.documentRevision(any())).thenThrow(new IllegalStateException("bad client"));
        var input = new ScopedDraftService.ScopedRequest("2", "4", List.of(blockId), "수정", null);
        assertThatThrownBy(() -> service.generate(caller, 1, input, "key"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("ai_unavailable"));
        verify(usage).recordFailure(eq("BEE_DRAFT"), eq(1L), isNull(), anyString(), anyLong());
    }

    @Test void rejectsVersionChangedWhileBlocksWereRead() {
        when(ai.revisionBlocks(any())).thenAnswer(call -> {
            Map<?, ?> source = call.getArgument(0);
            when(context.currentBaseline(1)).thenReturn(10L);
            return Map.of("document_version_id", "4", "text_hash", source.get("text_hash"), "parser_profile", "plain-v1",
                    "normalization_version", "utf8-lf-v1", "parser_version", "v1", "blocks", List.of(Map.of(
                            "block_id", blockId, "block_type", "paragraph", "locator", Map.of("type", "text_lines", "start_line", 1, "end_line", 1), "text", "원문\n")));
        });
        assertThatThrownBy(() -> service.blocks(caller, 1, new ScopedDraftService.BlockRequest("2", "4")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("version_conflict"));
    }

    @Test void sendsUserProvidedContextAndKeepsValidEvidence() {
        when(ai.revisionBlocks(any())).thenAnswer(call -> {
            Map<?, ?> source = call.getArgument(0);
            return Map.of("document_version_id", "4", "text_hash", source.get("text_hash"), "parser_profile", "plain-v1",
                    "normalization_version", "utf8-lf-v1", "parser_version", "v1", "blocks", List.of(Map.of(
                            "block_id", blockId, "block_type", "paragraph", "locator", Map.of("type", "text_lines", "start_line", 1, "end_line", 1), "text", "원문\n")));
        });
        when(ai.documentRevision(any())).thenAnswer(call -> {
            Map<?, ?> body = call.getArgument(0);
            assertThat(body.get("agreements")).isEqualTo(List.of("팀장만 삭제"));
            assertThat(body.get("related_proposals")).isEqualTo(List.of("변경 제안 메모"));
            return Map.of("origin", "MODEL", "model", "test", "usage", List.of(), "result", Map.of(
                    "document_version_id", "4", "after_content", "수정\n", "changes", List.of(Map.of("block_id", blockId,
                            "before", "원문\n", "after", "수정\n", "reason", "수정", "evidence", List.of("팀장만 삭제"))), "unresolved", List.of()));
        });
        var input = new ScopedDraftService.ScopedRequest("2", "4", List.of(blockId), "수정", null,
                List.of("팀장만 삭제"), List.of("변경 제안 메모"));
        assertThat(service.generate(caller, 1, input, "key")).containsKey("changes");
    }

    @Test void invalidControlCharactersInDraftAreMappedToBadGateway() {
        when(ai.revisionBlocks(any())).thenAnswer(call -> {
            Map<?, ?> source = call.getArgument(0);
            return Map.of("document_version_id", "4", "text_hash", source.get("text_hash"), "parser_profile", "plain-v1",
                    "normalization_version", "utf8-lf-v1", "parser_version", "v1", "blocks", List.of(Map.of(
                            "block_id", blockId, "block_type", "paragraph", "locator", Map.of("type", "text_lines", "start_line", 1, "end_line", 1), "text", "원문\n")));
        });
        when(ai.documentRevision(any())).thenReturn(Map.of("origin", "MODEL", "model", "test", "usage", List.of(), "result", Map.of(
                "document_version_id", "4", "after_content", "수정\u0001\n", "changes", List.of(Map.of("block_id", blockId,
                        "before", "원문\n", "after", "수정\u0001\n", "reason", "수정", "evidence", List.of())), "unresolved", List.of())));
        var input = new ScopedDraftService.ScopedRequest("2", "4", List.of(blockId), "수정", null);
        assertThatThrownBy(() -> service.generate(caller, 1, input, "key"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("ai_unavailable"));
    }

    @Test void meetingBridgeRejectsMismatchedContextAfterRecordingUsage() {
        when(ai.revisionBlocks(any())).thenAnswer(call -> {
            Map<?, ?> source = call.getArgument(0);
            return Map.of("document_version_id", "4", "text_hash", source.get("text_hash"), "parser_profile", "plain-v1",
                    "normalization_version", "utf8-lf-v1", "parser_version", "v1", "blocks", List.of(Map.of(
                            "block_id", blockId, "block_type", "paragraph", "locator", Map.of("type", "text_lines", "start_line", 1, "end_line", 1), "text", "원문\n")));
        });
        var draft = Map.<String,Object>of("origin", "MODEL", "model", "test", "usage", List.of(Map.of("tokens", 1)),
                "result", Map.of());
        when(ai.meetingRevision(any())).thenReturn(Map.of("meeting", Map.of("meeting_id", "other"), "draft", draft));
        when(usage.record(anyString(), anyLong(), any(), anyString(), anyMap(), anyLong())).thenReturn(1);
        var request = new ScopedDraftService.ScopedRequest("2", "4", List.of(blockId), "수정", null, List.of("결정"), List.of());
        assertThatThrownBy(() -> service.generateMeeting(caller, 1, request, "key", Map.of("meeting_id", "3")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("ai_unavailable"));
        verify(usage).record(eq("BEE_DRAFT"), eq(1L), isNull(), anyString(), eq(draft), anyLong());
        verify(usage, never()).recordFailure(anyString(), anyLong(), any(), anyString(), anyLong());
        verify(idempotency, never()).save(anyLong(), anyLong(), anyString(), anyString(), any(), any());
    }

    @Test void meetingBridgeCallsSharedEndpointAndChecksScopedResult() {
        when(ai.revisionBlocks(any())).thenAnswer(call -> {
            Map<?, ?> source = call.getArgument(0);
            return Map.of("document_version_id", "4", "text_hash", source.get("text_hash"), "parser_profile", "plain-v1",
                    "normalization_version", "utf8-lf-v1", "parser_version", "v1", "blocks", List.of(Map.of(
                            "block_id", blockId, "block_type", "paragraph", "locator", Map.of("type", "text_lines", "start_line", 1, "end_line", 1), "text", "원문\n")));
        });
        Map<String,Object> meeting = Map.of("meeting_id", "3");
        var draft = Map.<String,Object>of("origin", "MODEL", "model", "test", "usage", List.of(), "result", Map.of(
                "document_version_id", "4", "after_content", "수정\n", "changes", List.of(Map.of("block_id", blockId,
                        "before", "원문\n", "after", "수정\n", "reason", "결정", "evidence", List.of("결정"))), "unresolved", List.of()));
        when(ai.meetingRevision(any())).thenReturn(Map.of("meeting", meeting, "draft", draft));
        var request = new ScopedDraftService.ScopedRequest("2", "4", List.of(blockId), "수정", null, List.of("결정"), List.of());
        assertThat(service.generateMeeting(caller, 1, request, "key", meeting)).containsEntry("after_content", "수정\n");
        verify(ai, never()).documentRevision(any());
    }
}
