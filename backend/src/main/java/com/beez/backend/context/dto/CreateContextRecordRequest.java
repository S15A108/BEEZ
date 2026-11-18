package com.beez.backend.context.dto;

import com.beez.backend.context.entity.ContextRecordKind;
import com.beez.backend.context.entity.Visibility;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * CTX-A04 공유 작업 기록 생성·리비전 추가 요청 본문 (S15P31A108-415). MCP-07 write_context 인수({@link WriteContextArgs})와
 * 같은 칸이고, 재요청 키만 본문이 아니라 Idempotency-Key 헤더로 받는다. 작성자·프로젝트는 로그인한 사람에서 정한다.
 */
public record CreateContextRecordRequest(
        String previousId,
        String taskId,
        String planRevisionId,
        String sourcePacketId,
        @NotNull ContextRecordKind kind,
        @NotNull Visibility visibility,
        @NotBlank String summary,
        @NotBlank String detail,
        @NotNull List<@NotNull @Valid VersionRefDto> designRefs
) {
}
