package com.beez.backend.mcp.service;

import com.beez.backend.common.access.AccessErrors;
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
import com.beez.backend.context.service.ContextLookup;
import com.beez.backend.context.service.ContextPacketService;
import com.beez.backend.context.service.ContextRecordService;
import com.beez.backend.mcp.dto.GetDevelopmentContextArgs;
import java.util.Optional;
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

    private final McpCommandStore commands;
    private final ContextLookup lookup;

    private static final String OP_WRITE = "write_context";

    public ContextMcpService(ProjectAccessChecker access, ContextPacketService packets, ContextRecordService records,
                             McpCommandStore commands, ContextLookup lookup) {
        this.access = access;
        this.packets = packets;
        this.records = records;
        this.commands = commands;
        this.lookup = lookup;
    }

    /**
     * MCP-01 get_development_context. 어댑터는 도구 인수만 넘기고, 패킷 구성과 저장은 여기서 한다.
     *
     * <p>패킷 내용 구성(승인 설계 검색·의존 설계 확장·미확정·관련 작업·근거)은 박상은 모듈(-131·-132)이 맡는다.
     * 그 모듈이 붙기 전에는 <b>503 context_not_ready</b> (전진 MCP 오류표, #40 B4) 를 주고 패킷을 저장하지 않는다 (리뷰 반영).
     * 빈 구성으로 저장·반환하면 호출자가 "근거 없음"으로 읽는다 — 명세 MCP-01 "검색 실패와 근거 없음은 구분한다".
     * 모듈이 붙으면 아래 자리에서 구성 결과로 {@link SavePacketArgs} 를 만들어 {@link ContextPacketService#save} 를 부른다.
     * 권한(404·사람 세션 403)과 인수(400) 확인은 지금도 먼저 한다.
     */
    public ContextPacketResponse getDevelopmentContext(CallerContext caller, Long projectId, GetDevelopmentContextArgs args) {
        ProjectActor actor = access.requireMember(caller, projectId);
        if (!actor.isAgent()) {
            // CTX-A01: 웹에서 임의 패킷을 만들지 않는다
            throw AccessErrors.forbidden("packet_human_caller", "개발 컨텍스트 패킷은 에이전트 도구로만 만듭니다");
        }
        if (args == null || args.request() == null || args.request().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", "request 는 필수입니다");
        }
        // 인수의 project_id 는 토큰 프로젝트와 같은지만 본다 (05 참고 2 "MCP 인자 project_id 가 토큰 프로젝트와 다름 → not_found")
        if (args.projectId() != null && !Ids.parse(args.projectId(), "project_id").equals(projectId)) {
            throw AccessErrors.notFound("packet_project_mismatch project=" + projectId + " arg=" + args.projectId());
        }
        // task_id 는 형식(400)과 이 프로젝트 소속(404)을 본다 (MCP-01 오류표)
        Long taskId = Ids.parse(args.taskId(), "task_id");
        if (taskId != null) {
            lookup.requireTaskInProject(projectId, taskId);
        }
        // 구성: 박상은 모듈 연결 전. 연결되면 구성 결과로 packets.save(caller, projectId, composed) 를 부른다
        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "context_not_ready",
                "개발 컨텍스트 구성 모듈이 아직 연결되지 않았습니다");
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

    /** MCP-07 write_context. 같은 키·같은 입력은 처음 결과, 다른 입력은 409 idempotency_conflict (공통 5절) */
    @Transactional
    public ContextRecordResponse writeContext(CallerContext caller, Long projectId, WriteContextArgs args) {
        ProjectActor actor = access.requireMember(caller, projectId);
        if (args == null || args.idempotencyKey() == null || args.idempotencyKey().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", "idempotency_key 는 필수입니다");
        }
        Optional<ContextRecordResponse> previous = commands.replay(projectId, actor.accountId(), OP_WRITE,
                args.idempotencyKey(), args, ContextRecordResponse.class);
        if (previous.isPresent()) {
            return previous.get();
        }
        ContextRecordResponse result = records.write(caller, projectId, args);
        commands.save(projectId, actor.accountId(), OP_WRITE, args.idempotencyKey(), args, result);
        return result;
    }
}
