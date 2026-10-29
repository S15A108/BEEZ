package com.beez.backend.context.service;

import com.beez.backend.common.access.AccessErrors;
import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.access.ProjectActor;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.api.Ids;
import com.beez.backend.common.api.InputValidator;
import com.beez.backend.common.api.PageResponse;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.context.dto.ContextRecordResponse;
import com.beez.backend.context.dto.ContextRecordSummaryResponse;
import com.beez.backend.context.dto.FindContextArgs;
import com.beez.backend.context.dto.WriteContextArgs;
import com.beez.backend.context.entity.ContextRecord;
import com.beez.backend.context.entity.ContextRecordKind;
import com.beez.backend.context.repository.ContextRecordRepository;
import com.beez.backend.context.repository.DevContextPacketRepository;
import com.beez.backend.contextaccess.service.ContextAccessRecorder;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 공유 작업 기록 쓰기·상세·검색 (MCP-07·06·05, S15P31A108-166). REST CTX-A02~A04 도 같은 서비스를 쓴다.
 *
 * <p>근거
 * <ul>
 *   <li>공통 5절 · MCP-07: 수정은 previous_id 를 잇는 새 불변 리비전, 같은 이전 판의 두 번째 수정은 409 revision_conflict</li>
 *   <li>MCP-07: "이전 본인 리비전을 참조" → 남의 기록 수정은 403 (보이는 기록일 때), 사용자·프로젝트·토큰은 인증 주체에서</li>
 *   <li>00-common 2절 RECORD_AUTHOR · -172 합의 3: PRIVATE 는 작성 계정만. 남의 PRIVATE 는 403 이 아니라 404, 검색 건수에도 없음.
 *       작성자 비교는 계정 ID (-172 bbec28d)</li>
 *   <li>MCP-05: 권한 필터를 검색 전에. 최신 여부(is_latest_revision)와 참조 설계 현재성(references_current)을 따로 표시</li>
 * </ul>
 * 의미 검색(임베딩)은 박상은 모듈이 붙을 때 추가한다. 지금 검색은 요약 글자 포함 + 작업·종류 필터다.
 */
@Service
public class ContextRecordService {

    private final ProjectAccessChecker access;
    private final InputValidator validator;
    private final ContextRecordRepository records;
    private final DevContextPacketRepository packets;
    private final ContextLookup lookup;
    private final ContextAccessRecorder accessLog;
    private final JdbcClient jdbc;
    private final EntityManager entityManager;

    public ContextRecordService(ProjectAccessChecker access, InputValidator validator, ContextRecordRepository records,
                                DevContextPacketRepository packets, ContextLookup lookup, ContextAccessRecorder accessLog,
                                JdbcClient jdbc, EntityManager entityManager) {
        this.access = access;
        this.validator = validator;
        this.records = records;
        this.packets = packets;
        this.lookup = lookup;
        this.accessLog = accessLog;
        this.jdbc = jdbc;
        this.entityManager = entityManager;
    }

    // ---- MCP-07 write_context ----

    @Transactional
    public ContextRecordResponse write(CallerContext caller, Long projectId, WriteContextArgs args) {
        ProjectActor actor = access.requireMember(caller, projectId);
        validator.check(args, "arguments");
        validator.requireIdempotencyKey(args.idempotencyKey());

        Long taskId = Ids.parse(args.taskId(), "task_id");
        Long planRevisionId = Ids.parse(args.planRevisionId(), "plan_revision_id");
        Long sourcePacketId = Ids.parse(args.sourcePacketId(), "source_packet_id");
        if (taskId != null) {
            lookup.requireTaskInProject(projectId, taskId);
        }
        if (planRevisionId != null) {
            if (taskId == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", "plan_revision_id 는 task_id 와 함께 보내야 합니다");
            }
            lookup.requirePlanRevisionOfTask(taskId, planRevisionId);
        }
        if (sourcePacketId != null && packets.findByIdAndProjectId(sourcePacketId, projectId).isEmpty()) {
            throw AccessErrors.notFound("packet_missing project=" + projectId + " packet=" + sourcePacketId);
        }
        Map<Long, Long> refs = lookup.requireVersionRefs(projectId, args.designRefs());

        ContextRecord record;
        Long previousId = Ids.parse(args.previousId(), "previous_id");
        if (previousId == null) {
            record = ContextRecord.first(projectId, taskId, planRevisionId, sourcePacketId, actor.accountId(),
                    actor.agentTokenId(), args.kind(), args.visibility(), args.summary(), args.detail());
        } else {
            // 이전 판을 잠근다: 같은 이전 판을 동시에 고치면 두 번째는 여기서 기다렸다가 아래 "후속 판 있음"으로 409
            ContextRecord previous = records.findForUpdate(previousId, projectId)
                    .filter(p -> p.isVisibleTo(actor.accountId()))
                    .orElseThrow(() -> AccessErrors.notFound("record_missing_or_private project=" + projectId
                            + " record=" + previousId));
            if (!previous.getAuthorAccountId().equals(actor.accountId())) {
                throw AccessErrors.forbidden("not_record_author record=" + previousId + " account=" + actor.accountId(),
                        "작성자만 기록을 고칠 수 있습니다");
            }
            if (records.existsByPreviousId(previousId)) {
                throw new ApiException(HttpStatus.CONFLICT, "revision_conflict", "같은 이전 리비전의 후속 기록 존재");
            }
            record = ContextRecord.next(previous, taskId, planRevisionId, sourcePacketId, actor.accountId(),
                    actor.agentTokenId(), args.kind(), args.visibility(), args.summary(), args.detail());
        }
        try {
            // previous_id UNIQUE 가 잠금을 빠져나간 경합도 막는다 (마지막 방어)
            record = records.saveAndFlush(record);
        } catch (DataIntegrityViolationException e) {
            if (previousId == null) {
                // 첫 판의 제약 위반은 "후속 기록 존재"가 아니다 — 그대로 올려 원인을 숨기지 않는다
                throw e;
            }
            throw new ApiException(HttpStatus.CONFLICT, "revision_conflict", "같은 이전 리비전의 후속 기록 존재");
        }
        // refresh: DB 에 실제 저장된 값(created_at 은 마이크로초)으로 다시 읽는다. 저장 응답과 나중 조회 응답이 같아야 한다
        entityManager.refresh(record);
        lookup.insertRecordRefs(record.getId(), refs);
        Long baselineId = lookup.currentBaselineId(projectId).orElse(null);
        boolean current = !lookup.notCurrent(baselineId, List.of(record.getId())).contains(record.getId());
        return ContextRecordResponse.of(record, true, current, lookup.recordRefs(record.getId()));
    }

    // ---- MCP-06 read_context(RECORD) / CTX-A03 ----

    /** 권한 검사를 끝낸 actor 를 받는다. 다른 프로젝트·남의 PRIVATE 는 없는 것과 같은 404. 조회 이력을 남긴다 */
    @Transactional
    public ContextRecordResponse read(ProjectActor actor, Long projectId, Long recordId, String tool) {
        ContextRecord record = records.findByIdAndProjectId(recordId, projectId)
                .filter(r -> r.isVisibleTo(actor.accountId()))
                .orElseThrow(() -> AccessErrors.notFound("record_missing_or_private project=" + projectId
                        + " record=" + recordId));
        List<Long> one = List.of(record.getId());
        // 기준을 한 번만 읽어 응답의 현재성과 조회 이력에 같은 값을 쓴다
        Long baselineId = lookup.currentBaselineId(projectId).orElse(null);
        boolean latest = !lookup.superseded(one, actor.accountId()).contains(record.getId());
        boolean current = !lookup.notCurrent(baselineId, one).contains(record.getId());
        accessLog.record(actor, record.getTaskId(), tool, ContextAccessRecorder.KIND_CONTEXT_RECORD, record.getId(),
                baselineId, record.getSourcePacketId());
        return ContextRecordResponse.of(record, latest, current, lookup.recordRefs(record.getId()));
    }

    // ---- MCP-05 find_context / CTX-A02 ----

    /**
     * 요약에 검색어가 들어간 기록. 권한(공유 또는 내 것)·프로젝트·작업·종류 조건을 페이지를 자르기 전에 건다 (공통 3절).
     * 다른 프로젝트 task_id 는 오류 없이 0건 (-172 합의 3). 모든 리비전을 돌려주고 최신 여부를 표시한다.
     */
    @Transactional(readOnly = true)
    public PageResponse<ContextRecordSummaryResponse> find(CallerContext caller, Long projectId, FindContextArgs args) {
        ProjectActor actor = access.requireMember(caller, projectId);
        if (args == null || args.query() == null || args.query().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", "query 는 필수입니다");
        }
        Long taskId = Ids.parse(args.taskId(), "task_id");
        List<String> kinds = parseKinds(args.kinds());
        int size = PageResponse.normalizeLimit(args.limit());
        long before = PageResponse.beforeId(args.cursor());

        StringBuilder sql = new StringBuilder("""
                select id from context_record
                 where project_id = :p
                   and (visibility = 'SHARED' or author_account_id = :me)
                   and position(lower(:q) in lower(summary)) > 0
                   and id < :before
                """);
        if (taskId != null) {
            sql.append(" and task_id = :task ");
        }
        if (!kinds.isEmpty()) {
            sql.append(" and kind in (:kinds) ");
        }
        sql.append(" order by id desc limit :fetch ");
        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString())
                .param("p", projectId).param("me", actor.accountId()).param("q", args.query().strip())
                .param("before", before).param("fetch", size + 1);
        if (taskId != null) {
            spec = spec.param("task", taskId);
        }
        if (!kinds.isEmpty()) {
            spec = spec.param("kinds", kinds);
        }
        List<Long> ids = spec.query(Long.class).list();

        Map<Long, ContextRecord> byId = new HashMap<>();
        for (ContextRecord r : records.findAllById(ids)) {
            byId.put(r.getId(), r);
        }
        List<ContextRecord> rows = new ArrayList<>();
        for (Long id : ids) {
            rows.add(byId.get(id));
        }
        return PageResponse.fromRows(rows, size, ContextRecord::getId, page -> {
            List<Long> pageIds = new ArrayList<>();
            for (ContextRecord r : page) {
                pageIds.add(r.getId());
            }
            Set<Long> superseded = lookup.superseded(pageIds, actor.accountId());
            Set<Long> notCurrent = lookup.notCurrent(lookup.currentBaselineId(projectId).orElse(null), pageIds);
            List<ContextRecordSummaryResponse> items = new ArrayList<>();
            for (ContextRecord r : page) {
                items.add(ContextRecordSummaryResponse.of(r, !superseded.contains(r.getId()), !notCurrent.contains(r.getId())));
            }
            return items;
        });
    }

    private static List<String> parseKinds(List<String> raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (String k : raw) {
            try {
                out.add(ContextRecordKind.valueOf(k).name());
            } catch (IllegalArgumentException | NullPointerException e) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", "kinds 값이 올바르지 않습니다: " + k);
            }
        }
        return out;
    }
}
