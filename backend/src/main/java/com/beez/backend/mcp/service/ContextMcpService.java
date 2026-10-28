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

    /** MCP-01 get_development_context 의 업무 저장. 패킷 내용 구성(박상은)이 끝난 결과를 받는다 */
    public ContextPacketResponse saveDevelopmentContext(CallerContext caller, Long projectId, SavePacketArgs args) {
        return packets.save(caller, projectId, args);
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

    /** MCP-07 write_context */
    public ContextRecordResponse writeContext(CallerContext caller, Long projectId, WriteContextArgs args) {
        return records.write(caller, projectId, args);
    }
}
