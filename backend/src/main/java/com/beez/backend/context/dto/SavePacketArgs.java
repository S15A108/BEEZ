package com.beez.backend.context.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * MCP-01 get_development_context 의 "업무 저장" 입력. 도구 인수(request, project_id, task_id)에
 * 패킷 구성 결과(박상은 -131·-132: 승인 설계 버전·제약·미확정·관련 작업·근거)를 더한 것이다.
 * 어댑터가 아니라 업무 서비스(ContextMcpService)가 DevelopmentContextComposer 의 AI 구성 결과로 만든다 (S15P31A108-399).
 */
public record SavePacketArgs(
        @NotBlank String request,
        String projectId,
        String taskId,
        @NotBlank String expectedBaselineId,
        @NotNull List<@NotBlank String> designItemVersionIds,
        @NotNull List<@NotNull String> constraints,
        @NotNull List<@NotNull String> unresolved,
        @NotNull List<@NotNull @Valid RelatedTaskDto> relatedTasks,
        @NotNull List<@NotNull @Valid SourceDto> sources
) {
}
