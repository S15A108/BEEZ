package com.beez.backend.context.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * MCP-01 get_development_context 의 "업무 저장" 입력. 도구 인수(request, project_id, task_id)에
 * 패킷 구성 결과(박상은 -131·-132: 승인 설계 버전·제약·미확정·관련 작업·근거)를 더한 것이다.
 * 어댑터가 아니라 업무 서비스(ContextMcpService)가 만든다. 구성 모듈이 붙기 전까지 구성 결과 자리는 빈 목록이다.
 */
public record SavePacketArgs(
        @NotBlank String request,
        String projectId,
        String taskId,
        @NotNull List<@NotBlank String> designItemVersionIds,
        @NotNull List<@NotNull String> constraints,
        @NotNull List<@NotNull String> unresolved,
        @NotNull List<@NotNull @Valid RelatedTaskDto> relatedTasks,
        @NotNull List<@NotNull @Valid SourceDto> sources
) {
}
