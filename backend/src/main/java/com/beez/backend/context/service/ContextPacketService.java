package com.beez.backend.context.service;

import com.beez.backend.common.access.AccessErrors;
import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.access.ProjectActor;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.api.CanonicalJson;
import com.beez.backend.common.api.Ids;
import com.beez.backend.common.api.InputValidator;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.context.dto.ContextPacketResponse;
import com.beez.backend.context.dto.DesignItemVersionResponse;
import com.beez.backend.context.dto.RelatedTaskDto;
import com.beez.backend.context.dto.SavePacketArgs;
import com.beez.backend.context.dto.SourceDto;
import com.beez.backend.context.entity.DevContextPacket;
import com.beez.backend.context.repository.DevContextPacketRepository;
import com.beez.backend.contextaccess.service.ContextAccessRecorder;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 개발 컨텍스트 패킷 저장·조회 (MCP-01 업무 저장, MCP-06 PACKET, S15P31A108-166).
 * 내용 구성(승인 설계 검색·의존 확장·미확정 분류·근거)은 박상은 모듈(-131·-132)이 하고, 여기서는 그 결과를 불변 패킷으로 저장한다.
 *
 * <p>근거: MCP-01 처리 규칙 "불변 패킷과 참조 버전을 저장하고 반환한다", 오류표 404 baseline_not_published,
 * CTX-A01 "MCP 호출에서 생성한 불변 패킷을 조회한다. 웹에서 임의 패킷을 생성하지 않는다".
 */
@Service
public class ContextPacketService {

    private final ProjectAccessChecker access;
    private final InputValidator validator;
    private final DevContextPacketRepository packets;
    private final ContextLookup lookup;
    private final ContextAccessRecorder accessLog;
    private final ObjectMapper json;
    private final EntityManager entityManager;

    /** 명세 00-common RelatedTask.status */
    private static final java.util.Set<String> RELATED_TASK_STATUSES =
            java.util.Set.of("READY", "IN_PROGRESS", "WAITING", "COMPLETED", "CANCELLED");

    public ContextPacketService(ProjectAccessChecker access, InputValidator validator, DevContextPacketRepository packets,
                                ContextLookup lookup, ContextAccessRecorder accessLog, ObjectMapper json,
                                EntityManager entityManager) {
        this.access = access;
        this.validator = validator;
        this.packets = packets;
        this.lookup = lookup;
        this.accessLog = accessLog;
        this.json = json;
        this.entityManager = entityManager;
    }

    @Transactional
    public ContextPacketResponse save(CallerContext caller, Long projectId, SavePacketArgs args) {
        ProjectActor actor = access.requireMember(caller, projectId);
        if (!actor.isAgent()) {
            // CTX-A01: 웹에서 임의 패킷을 만들지 않는다
            throw AccessErrors.forbidden("packet_human_caller", "개발 컨텍스트 패킷은 에이전트 도구로만 만듭니다");
        }
        validator.check(args, "arguments");
        // 프로젝트는 토큰에서 정한다. 인수의 project_id 는 같은지만 본다 (02-mcp-tools "연결과 인증")
        if (args.projectId() != null && !Ids.parse(args.projectId(), "project_id").equals(projectId)) {
            throw AccessErrors.notFound("packet_project_mismatch project=" + projectId + " arg=" + args.projectId());
        }
        Long taskId = Ids.parse(args.taskId(), "task_id");
        if (taskId != null) {
            lookup.requireTaskInProject(projectId, taskId);
        }
        Long baselineId = lookup.lockCurrentBaselineId(projectId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "baseline_not_published", "현재 승인 기준 없음"));
        List<Long> versionIds = Ids.parseAll(args.designItemVersionIds(), "design_item_version_ids");
        lookup.requireVersionsInBaseline(projectId, baselineId, versionIds);

        // 구성 결과의 관련 작업·근거도 확인한다. 불변 패킷에 남의 ID 나 지난 값이 "현재"처럼 영구히 남지 않게 (리뷰 반영)
        for (RelatedTaskDto t : args.relatedTasks()) {
            Long relatedTaskId = Ids.parse(t.taskId(), "related_tasks.task_id");
            Long relatedRevisionId = Ids.parse(t.planRevisionId(), "related_tasks.plan_revision_id");
            if (!RELATED_TASK_STATUSES.contains(t.status())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed",
                        "related_tasks.status 는 READY·IN_PROGRESS·WAITING·COMPLETED·CANCELLED 중 하나입니다: " + t.status());
            }
            lookup.requireRelatedTaskCurrent(projectId, relatedTaskId, relatedRevisionId, t.status());
        }
        for (SourceDto src : args.sources()) {
            lookup.requireSourceInBaseline(projectId, baselineId, Ids.parse(src.documentId(), "sources.document_id"),
                    Ids.parse(src.documentVersionId(), "sources.document_version_id"), src.versionNo());
        }

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("design_item_version_ids", versionIds);
        // 반환할 승인 설계를 저장 시점 그대로 넣는다. 나중에 그 버전이 SUPERSEDED 가 되어도 같은 패킷은 같은 내용을 돌려준다 (리뷰 반영)
        // convertValue: 응답 객체를 JSON 과 같은 Map 으로 바꾼다
        content.put("design_items", json.convertValue(lookup.designItemVersions(versionIds), List.class));
        content.put("constraints", args.constraints());
        content.put("unresolved", args.unresolved());
        content.put("related_tasks", relatedTasksToMaps(args.relatedTasks()));
        content.put("sources", sourcesToMaps(args.sources()));

        DevContextPacket packet = packets.saveAndFlush(DevContextPacket.create(projectId, taskId, actor.agentTokenId(),
                args.request(), baselineId, content, hash(projectId, taskId, args.request(), baselineId, content)));
        // ↑ 해시는 프로젝트·작업·요청·기준·content(설계 스냅샷 포함)의 정규 JSON
        // refresh: DB 에 실제 저장된 값(created_at 은 마이크로초)으로 다시 읽는다. 저장 응답과 나중 조회 응답이 같아야 한다
        entityManager.refresh(packet);
        lookup.insertPacketRefs(packet.getId(), versionIds);
        accessLog.record(actor, taskId, "get_development_context", ContextAccessRecorder.KIND_PACKET,
                packet.getId(), baselineId, packet.getId());
        return toResponse(packet);
    }

    /** read_context(PACKET) 와 CTX-A01. 권한 검사를 끝낸 actor 를 받는다. 다른 프로젝트 패킷은 없는 것과 같은 404 */
    @Transactional
    public ContextPacketResponse read(ProjectActor actor, Long projectId, Long packetId, String tool) {
        DevContextPacket packet = packets.findByIdAndProjectId(packetId, projectId)
                .orElseThrow(() -> AccessErrors.notFound("packet_missing project=" + projectId + " packet=" + packetId));
        accessLog.record(actor, packet.getTaskId(), tool, ContextAccessRecorder.KIND_PACKET, packet.getId(),
                packet.getBaselineId(), packet.getId());
        return toResponse(packet);
    }

    // ---- 변환 ----

    private ContextPacketResponse toResponse(DevContextPacket p) {
        Map<String, Object> c = p.getContent();
        // 저장 시점 스냅샷을 그대로 돌려준다 (다시 조회하지 않는다)
        List<DesignItemVersionResponse> designItems = new ArrayList<>();
        for (Object o : listOf(c.get("design_items"))) {
            designItems.add(json.convertValue(o, DesignItemVersionResponse.class));
        }
        List<RelatedTaskDto> related = new ArrayList<>();
        for (Object o : listOf(c.get("related_tasks"))) {
            Map<?, ?> m = (Map<?, ?>) o;
            related.add(new RelatedTaskDto((String) m.get("task_id"), (String) m.get("plan_revision_id"), (String) m.get("status")));
        }
        List<SourceDto> sources = new ArrayList<>();
        for (Object o : listOf(c.get("sources"))) {
            Map<?, ?> m = (Map<?, ?>) o;
            sources.add(new SourceDto((String) m.get("document_id"), (String) m.get("document_version_id"),
                    ((Number) m.get("version_no")).intValue(), (String) m.get("location")));
        }
        return new ContextPacketResponse(Ids.str(p.getId()), Ids.str(p.getProjectId()), Ids.str(p.getTaskId()),
                Ids.str(p.getBaselineId()), p.getRequest(), designItems,
                stringsOf(c.get("constraints")), stringsOf(c.get("unresolved")), related, sources, p.getCreatedAt(),
                p.getContentHash());
    }

    private static List<Object> listOf(Object value) {
        List<Object> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            out.addAll(list);
        }
        return out;
    }

    private static List<String> stringsOf(Object value) {
        List<String> out = new ArrayList<>();
        for (Object o : listOf(value)) {
            out.add(String.valueOf(o));
        }
        return out;
    }

    private static List<Map<String, Object>> relatedTasksToMaps(List<RelatedTaskDto> tasks) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (RelatedTaskDto t : tasks) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("task_id", t.taskId());
            m.put("plan_revision_id", t.planRevisionId());
            m.put("status", t.status());
            out.add(m);
        }
        return out;
    }

    private static List<Map<String, Object>> sourcesToMaps(List<SourceDto> sources) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (SourceDto s : sources) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("document_id", s.documentId());
            m.put("document_version_id", s.documentVersionId());
            m.put("version_no", s.versionNo());
            m.put("location", s.location());
            out.add(m);
        }
        return out;
    }

    /**
     * 패킷 해시 = {project_id, task_id, request, baseline_id, content} 의 정규 JSON SHA-256 ({@link CanonicalJson}).
     * Map.toString() 을 쓰면 constraints=["a, b"] 와 ["a", "b"] 가 같은 문자열이 되어 다른 패킷이 같은 해시를 갖는다 (리뷰 반영).
     */
    private String hash(Long projectId, Long taskId, String request, Long baselineId, Map<String, Object> content) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("project_id", projectId);
        input.put("task_id", taskId);
        input.put("request", request);
        input.put("baseline_id", baselineId);
        input.put("content", content);
        return CanonicalJson.sha256(json, input);
    }
}
