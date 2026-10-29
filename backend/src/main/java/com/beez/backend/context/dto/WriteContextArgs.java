package com.beez.backend.context.dto;

import com.beez.backend.context.entity.ContextRecordKind;
import com.beez.backend.context.entity.Visibility;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/** MCP-07 write_context 인수 (= REST CTX-A04 요청 + idempotency_key). 작성자·토큰·프로젝트는 인증 주체에서 정한다 */
public record WriteContextArgs(
        String previousId,
        String taskId,
        String planRevisionId,
        String sourcePacketId,
        @NotNull ContextRecordKind kind,
        @NotNull Visibility visibility,
        @NotBlank String summary,
        @NotBlank String detail,
        @NotNull List<@NotNull @Valid VersionRefDto> designRefs,
        String idempotencyKey
) {
}
