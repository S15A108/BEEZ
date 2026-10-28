package com.beez.backend.mcp.service;

import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.access.ProjectActor;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.api.Ids;
import com.beez.backend.common.api.PageResponse;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.context.dto.ContextPacketResponse;
import com.beez.backend.context.dto.ContextRecordResponse;
import com.beez.backend.context.dto.ContextRecordSummaryResponse;
import com.beez.backend.context.dto.FindContextArgs;
import com.beez.backend.context.dto.ReadContextResult;
import com.beez.backend.context.dto.SavePacketArgs;
import com.beez.backend.context.dto.WriteContextArgs;
import com.beez.backend.context.service.ContextPacketService;
import com.beez.backend.context.service.ContextRecordService;
import com.beez.backend.mcp.dto.GetDevelopmentContextArgs;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * MCP 컨텍스트 도구(MCP-01·05·06·07)가 부르는 업무 서비스 (S15P31A108-166). 계약은 docs/design/08-mcp-service-contract.md.
 * 규칙은 ContextPacketService·ContextRecordService 에 있고, 여기서는 도구 인수 모양을 맞춘다.
 */
@Service
public class ContextMcpService {

    private final ProjectAccessChecker access;
    private final ContextPacketService packets;
    private final ContextRecordService records;

    public ContextMcpService(ProjectAccessChecker access, ContextPacketService packets, ContextRecordService records) {
        this.access = access;
        this.packets = packets;
        this.records = records;
    }

    /**
     * MCP-01 get_development_context. 어댑터는 도구 인수만 넘기고, 패킷 구성과 저장은 여기서 한다.
     *
     * <p>패킷 내용 구성(승인 설계 검색·의존 설계 확장·미확정·관련 작업·근거)은 박상은 모듈(-131·-132)이 맡는다.
     * 그 모듈이 아직 없어 지금은 구성 결과를 빈 목록으로 저장한다. 모듈이 붙으면 아래 "구성" 자리에서 호출하고,
     * 무엇을 주고받을지는 그때 함께 정한다 (계약 문서 3절).
     */
    public ContextPacketResponse getDevelopmentContext(CallerContext caller, Long projectId, GetDevelopmentContextArgs args) {
        if (args == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", "arguments 는 필수입니다");
        }
        // 구성: 박상은 모듈 연결 전까지 빈 결과
        SavePacketArgs composed = new SavePacketArgs(args.request(), args.projectId(), args.taskId(),
                List.of(), List.of(), List.of(), List.of(), List.of());
        // 권한·인수 검사(@NotBlank request 포함)·저장·조회 이력은 패킷 저장 서비스가 한다
        return packets.save(caller, projectId, composed);
    }

    /** MCP-05 find_context */
    public PageResponse<ContextRecordSummaryResponse> findContext(CallerContext caller, Long projectId, FindContextArgs args) {
        return records.find(caller, projectId, args);
    }

    /** MCP-06 read_context. type 과 맞는 쪽 하나만 채운다 */
    @Transactional
    public ReadContextResult readContext(CallerContext caller, Long projectId, String type, String id) {
        ProjectActor actor = access.requireMember(caller, projectId);
        if (id == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", "id 는 필수입니다");
        }
        Long targetId = Ids.parse(id, "id");
        if ("PACKET".equals(type)) {
            ContextPacketResponse packet = packets.read(actor, projectId, targetId, "read_context");
            return new ReadContextResult("PACKET", packet, null);
        }
        if ("RECORD".equals(type)) {
            ContextRecordResponse record = records.read(actor, projectId, targetId, "read_context");
            return new ReadContextResult("RECORD", null, record);
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", "type 은 PACKET 또는 RECORD 입니다");
    }

    /** MCP-07 write_context. idempotency_key 는 필수 확인만 한다 — 같은 키로 재시도하면 기록이 한 번 더 생긴다(Q12 전 알려진 한계) */
    public ContextRecordResponse writeContext(CallerContext caller, Long projectId, WriteContextArgs args) {
        return records.write(caller, projectId, args);
    }
}
