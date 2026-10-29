package com.beez.backend.designchange.service;

import com.beez.backend.common.access.ProjectActor;
import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.api.Ids;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.contextaccess.service.ContextAccessRecorder;
import com.beez.backend.designchange.dto.BaselineSummaryResponse;
import com.beez.backend.designchange.dto.ChangedItemResponse;
import com.beez.backend.designchange.dto.DesignChangesQuery;
import com.beez.backend.designchange.dto.DesignChangesResponse;
import com.beez.backend.designchange.dto.PlanChangesResponse;
import com.beez.backend.designchange.dto.PublishedChangeResponse;
import com.beez.backend.designchange.dto.ValidationValidityResponse;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * MCP-03 get_design_changes (S15P31A108-164). "마지막으로 본 기준 번호 이후에 발행된 설계 변경"과 내 작업 검증의 유효성을 돌려준다.
 * 도구 등록은 전진(-179)이 하고, 이 서비스는 REST 없이 MCP 어댑터와 report_task_state(MCP-12 응답 changes)에서 쓴다.
 *
 * <p>근거: 02-mcp-tools MCP-03 요청·응답·오류표·처리 규칙, 00-common 3절 페이지, ERD baseline·baseline_item.
 * 설계·기준 설계 테이블은 오서현 영역이라 엔티티 없이 JdbcClient 로 읽기만 한다 (-156 과 같은 방식).
 *
 * <p>정한 것 (문서에 없거나 서로 달라서 담당자 결정으로 적용. 04 생명주기 §6 처럼 명세 반영 요청 대상):
 * <ol>
 *   <li>since_version=0 은 "처음 연결"로 보고 현재 기준과 가장 최근 변경 한 건만 준다 (Jira "과거 전체 반환 안 함")</li>
 *   <li>첫 발행(v1)은 변경이 아니라 시작점이라 목록에서 뺀다. 제안 없이 발행된 기준은 proposal_id 를 생략한다</li>
 *   <li>폐기 = 새 기준의 baseline_item 에서 빠짐 (발행 -194 계약, {@link DesignChangeQueries}). retired_at 시각으로 추정하지 않는다</li>
 *   <li>기준 설계가 한 번도 없으면 409 baseline_missing (-156 ProjectDesignLookup 과 같은 코드)</li>
 *   <li>영향 작업 = 변경 전 버전 또는 그보다 옛 버전을 현재 계획이 참조하는(READS·IMPLEMENTS·CHANGES) 작업. affected_task_ids 는
 *       완료·취소를 빼고, task_id 를 지정한 목록은 상태와 무관하게 그 작업이 영향받은 변경을 준다. 기준은 {@link DesignChangeQueries}</li>
 *   <li>검증이 한 번도 없는 작업은 validation_validity 에 넣지 않는다 (명세 상태 목록에 NOT_RUN 이 없고 validation_id 가 필수)</li>
 *   <li>task_id 를 주면 그 작업이 영향받은 변경만, 검증 유효성도 그 작업만. 다른 프로젝트 작업이면 404 가 아니라 빈 결과(-172 참고 2)</li>
 * </ol>
 */
@Service
public class DesignChangesService {

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;

    /** 담당자의 "열린 작업": 끝나지 않은 상태 (04 생명주기 §1 상태 표에서 COMPLETED·CANCELLED 제외) */
    private static final List<String> OPEN_STATUSES = List.of("DRAFT", "READY", "IN_PROGRESS", "WAITING");

    /** 바뀐 항목·영향 작업 SQL 조각. -163 과 공유하므로 {@link DesignChangeQueries} 에 둔다 */
    private static final String CHANGED_AND_AFFECTED = DesignChangeQueries.CHANGED_AND_AFFECTED;

    private final ProjectAccessChecker access;
    private final JdbcClient jdbc;
    private final ContextAccessRecorder accessLog;

    public DesignChangesService(ProjectAccessChecker access, JdbcClient jdbc, ContextAccessRecorder accessLog) {
        this.access = access;
        this.jdbc = jdbc;
        this.accessLog = accessLog;
    }

    /** 읽기 전용이 아닌 이유: 조회 이력(-165)을 같은 트랜잭션에 남긴다 */
    @Transactional
    public DesignChangesResponse getChanges(CallerContext caller, Long projectId, DesignChangesQuery in) {
        ProjectActor actor = access.requireMember(caller, projectId);

        if (in.sinceVersion() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", "since_version 은 필수입니다");
        }
        Long taskId = Ids.parse(in.taskId(), "task_id");

        // 현재 기준. 없으면 발행이 한 번도 안 된 프로젝트
        BaselineRow latest = findCurrentBaseline(projectId)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "baseline_missing", "승인된 기준 설계가 아직 없습니다"));
        return build(actor, projectId, latest, in.sinceVersion(), true, taskId, in.cursor(), normalizeLimit(in.limit()),
                "get_design_changes");
    }

    /**
     * report_task_state(MCP-12) 응답의 changes. 그 작업의 <b>현재 계획을 세운 시점의 기준 설계 이후</b> 발행된 변경과
     * 그 작업 검증의 유효성 (S15P31A108-166 결정). 명세 MCP-12 는 기준점을 정하지 않는다.
     * 기준점은 계획 저장 당시 기준 설계(근거 패킷이 더 옛 기준이면 그 기준). 자세한 규칙은 planBaselineVersion.
     * 처음 연결 규칙(since=0 → 최근 1건)은 쓰지 않는다. 기준 설계가 한 번도 없으면 null (호출자가 changes 를 비운다).
     * 호출자는 이미 권한 검사를 통과한 actor 를 넘긴다. 조회 이력도 남긴다.
     *
     * <p>계산에 쓴 기준 번호(sinceVersion)도 함께 돌려준다. 다음 페이지가 있으면 에이전트가
     * get_design_changes(since_version=이 값, task_id=같은 작업, cursor=next_cursor)로 이어 본다 (리뷰 반영).
     */
    @Transactional
    public PlanChangesResponse changesSincePlan(ProjectActor actor, Long projectId, Long taskId) {
        Optional<BaselineRow> latest = findCurrentBaseline(projectId);
        if (latest.isEmpty()) {
            return null;
        }
        int since = planBaselineVersion(projectId, taskId);
        if (since > latest.get().versionNo()) {
            since = latest.get().versionNo();
        }
        DesignChangesResponse changes = build(actor, projectId, latest.get(), since, false, taskId, null, DEFAULT_LIMIT,
                "report_task_state");
        return new PlanChangesResponse(since, changes);
    }

    /**
     * 현재 계획의 기준 번호. 계획 저장 때 남긴 기준(task_plan_revision.base_baseline_id)과, 근거 패킷이 있으면 그 패킷의 기준 중
     * <b>더 옛 번호</b>를 쓴다 (계획이 옛 패킷을 보고 쓰였으면 그 이후 변경도 보여 준다). 둘 다 없으면 0 — 모든 변경.
     * 시각을 비교해 추정하지 않는다: 리비전 시각(앱)과 발행 시각(DB)의 시계가 어긋나면 변경이 빠진다 (리뷰 반영).
     */
    private int planBaselineVersion(Long projectId, Long taskId) {
        Optional<Integer> version = jdbc.sql("""
                        select least(
                                 coalesce((select b.version_no from baseline b
                                            where b.id = r.base_baseline_id and b.project_id = :projectId), 0),
                                 coalesce((select b.version_no from dev_context_packet k join baseline b on b.id = k.baseline_id
                                            where k.id = r.context_packet_id and k.project_id = :projectId), 2147483647))
                          from task t
                          join task_plan_revision r on r.id = t.current_plan_revision_id
                         where t.id = :taskId and t.project_id = :projectId
                        """)
                .param("projectId", projectId)
                .param("taskId", taskId)
                .query(Integer.class)
                .optional();
        if (version.isEmpty()) {
            return 0;
        }
        return version.get();
    }

    private DesignChangesResponse build(ProjectActor actor, Long projectId, BaselineRow latest, int since,
                                        boolean firstConnectRule, Long taskId, String cursorText, int limit,
                                        String tool) {

        // 오류표: 음수 또는 현재보다 큰 기준 번호 → 400 invalid_version
        if (since < 0 || since > latest.versionNo()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_version", "음수 또는 현재보다 큰 기준 번호");
        }

        // 첫 페이지 시점의 최신 번호(ceiling)를 cursor 에 고정한다. 페이지 도중 새 발행이 생겨도 목록이 흔들리지 않는다
        int ceiling = latest.versionNo();
        Integer before = null;
        boolean continuing = cursorText != null && !cursorText.isBlank();
        if (continuing) {
            ChangesCursor cursor = ChangesCursor.decode(cursorText);
            cursor.requireSameQuery(since, taskId);
            ceiling = cursor.ceiling();
            before = cursor.lastVersion();
        }
        // since=0 은 "처음 연결": 현재 기준과 가장 최근 변경 하나만 (정한 것 1).
        // 이어 보기(cursor)에는 적용하지 않는다. 처음 연결 결과는 1건이라 다음 페이지가 없고,
        // since=0 cursor 는 report_task_state(계획 당시 발행 없음 → 0)가 만든 것이라 전체 범위를 이어야 한다
        int effectiveSince = since;
        if (firstConnectRule && since == 0 && !continuing) {
            effectiveSince = ceiling - 1;
        }

        BaselineRow current = latest;
        if (ceiling != latest.versionNo()) {
            // cursor 가 가리키는 당시 기준으로 고정 (공통 3절)
            current = findBaselineByVersion(projectId, ceiling).orElse(latest);
        }

        List<BaselineRow> page = findBaselinePage(projectId, effectiveSince, ceiling, before, taskId, limit + 1);
        boolean hasMore = page.size() > limit;
        if (hasMore) {
            page = page.subList(0, limit);
        }

        List<Long> baselineIds = new ArrayList<>();
        for (BaselineRow b : page) {
            baselineIds.add(b.id());
        }
        Map<Long, List<ChangedItemResponse>> changedByBaseline = findChangedItems(projectId, baselineIds);
        Map<Long, List<String>> affectedByBaseline = findAffectedTasks(projectId, baselineIds);

        List<PublishedChangeResponse> items = new ArrayList<>();
        for (BaselineRow b : page) {
            List<ChangedItemResponse> changed = changedByBaseline.get(b.id());
            if (changed == null) {
                changed = List.of();
            }
            List<String> affected = affectedByBaseline.get(b.id());
            if (affected == null) {
                affected = List.of();
            }
            items.add(new PublishedChangeResponse(toSummary(b), Ids.str(b.changeProposalId()), changed, affected));
        }

        String nextCursor = null;
        if (hasMore) {
            int lastVersion = page.get(page.size() - 1).versionNo();
            nextCursor = new ChangesCursor(ceiling, lastVersion, since, taskId).encode();
        }

        List<ValidationValidityResponse> validity = findValidationValidity(projectId, actor.memberId(), taskId);

        // 조회 이력 (CTX-05·06): 어느 기준 설계를 기준으로 변경을 봤는지. 작업 소속 확인은 recorder 가 한다
        accessLog.record(actor, taskId, tool, ContextAccessRecorder.KIND_BASELINE,
                current.id(), current.id(), null);
        return new DesignChangesResponse(toSummary(current), items, validity, hasMore, nextCursor);
    }

    // ---- 조회 ----

    /** baseline 한 줄. publishedAt 이 응답의 created_at */
    private record BaselineRow(Long id, int versionNo, Timestamp publishedAt, Long changeProposalId) {
    }

    private static final String BASELINE_COLUMNS = "b.id, b.version_no, b.published_at, b.change_proposal_id";

    private Optional<BaselineRow> findCurrentBaseline(Long projectId) {
        return jdbc.sql("select " + BASELINE_COLUMNS + """
                          from project p
                          join baseline b on b.id = p.current_baseline_id
                         where p.id = :projectId
                           and p.archived_at is null
                        """)
                .param("projectId", projectId)
                .query(this::toBaselineRow)
                .optional();
    }

    private Optional<BaselineRow> findBaselineByVersion(Long projectId, int versionNo) {
        return jdbc.sql("select " + BASELINE_COLUMNS + """
                          from baseline b
                         where b.project_id = :projectId
                           and b.version_no = :versionNo
                        """)
                .param("projectId", projectId)
                .param("versionNo", versionNo)
                .query(this::toBaselineRow)
                .optional();
    }

    /**
     * 페이지에 넣을 기준들. since 초과 ~ ceiling 이하, v1 제외, 최신 번호부터.
     * task_id 가 있으면 "그 작업이 영향받은 발행"만 — 권한·필터는 페이지를 자르기 전에 건다(공통 3절).
     */
    private List<BaselineRow> findBaselinePage(Long projectId, int since, int ceiling, Integer before, Long taskId, int fetch) {
        StringBuilder sql = new StringBuilder("with " + CHANGED_AND_AFFECTED
                + " select " + BASELINE_COLUMNS + """
                  from baseline b
                 where b.project_id = :projectId
                   and b.version_no > :since
                   and b.version_no <= :ceiling
                   and b.version_no > 1
                """);
        if (before != null) {
            sql.append(" and b.version_no < :before ");
        }
        if (taskId != null) {
            sql.append(" and exists (select 1 from affected a where a.baseline_id = b.id and a.task_id = :taskId) ");
        }
        sql.append(" order by b.version_no desc limit :fetch ");

        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString())
                .param("projectId", projectId)
                .param("since", since)
                .param("ceiling", ceiling)
                .param("fetch", fetch);
        if (before != null) {
            spec = spec.param("before", before);
        }
        if (taskId != null) {
            spec = spec.param("taskId", taskId);
        }
        return spec.query(this::toBaselineRow).list();
    }

    /** 페이지 안 기준들의 바뀐 항목을 한 번에 읽어 기준별로 묶는다 (기준마다 따로 조회하지 않는다) */
    private Map<Long, List<ChangedItemResponse>> findChangedItems(Long projectId, List<Long> baselineIds) {
        Map<Long, List<ChangedItemResponse>> out = new HashMap<>();
        if (baselineIds.isEmpty()) {
            return out;
        }
        List<Map<String, Object>> rows = jdbc.sql("with " + CHANGED_AND_AFFECTED + """
                        select baseline_id, design_item_id, before_version_id, after_version_id
                          from changed
                         where baseline_id in (:ids)
                         order by version_no desc, design_item_id
                        """)
                .param("projectId", projectId)
                .param("ids", baselineIds)
                .query()
                .listOfRows();
        for (Map<String, Object> row : rows) {
            Long baselineId = ((Number) row.get("baseline_id")).longValue();
            if (!out.containsKey(baselineId)) {
                out.put(baselineId, new ArrayList<>());
            }
            out.get(baselineId).add(new ChangedItemResponse(
                    Ids.str(longOrNull(row.get("design_item_id"))),
                    Ids.str(longOrNull(row.get("before_version_id"))),
                    Ids.str(longOrNull(row.get("after_version_id")))));
        }
        return out;
    }

    private Map<Long, List<String>> findAffectedTasks(Long projectId, List<Long> baselineIds) {
        Map<Long, List<String>> out = new HashMap<>();
        if (baselineIds.isEmpty()) {
            return out;
        }
        List<Map<String, Object>> rows = jdbc.sql("with " + CHANGED_AND_AFFECTED + """
                        select distinct baseline_id, task_id
                          from affected
                         where baseline_id in (:ids)
                           and task_status not in ('COMPLETED', 'CANCELLED')
                         order by baseline_id, task_id
                        """)
                .param("projectId", projectId)
                .param("ids", baselineIds)
                .query()
                .listOfRows();
        for (Map<String, Object> row : rows) {
            Long baselineId = ((Number) row.get("baseline_id")).longValue();
            if (!out.containsKey(baselineId)) {
                out.put(baselineId, new ArrayList<>());
            }
            out.get(baselineId).add(Ids.str(((Number) row.get("task_id")).longValue()));
        }
        return out;
    }

    /**
     * 검증 유효성. task_id 가 있으면 그 작업(이 프로젝트 것일 때만), 없으면 호출자가 담당인 열린 작업 전부.
     * 작업마다 현재 계획 리비전의 가장 최근 검증 한 건. 검증이 없는 작업은 빠진다 (정한 것 5).
     */
    private List<ValidationValidityResponse> findValidationValidity(Long projectId, Long memberId, Long taskId) {
        String latestValidation = """
                join lateral (
                    select v.id, v.status, v.stale_reason
                      from validation v
                     where v.task_id = t.id
                       and v.plan_revision_id = t.current_plan_revision_id
                     order by v.id desc
                     limit 1
                ) v on true
                """;
        JdbcClient.StatementSpec spec;
        if (taskId != null) {
            spec = jdbc.sql("select v.id, v.status, v.stale_reason from task t " + latestValidation + """
                             where t.project_id = :projectId
                               and t.id = :taskId
                            """)
                    .param("projectId", projectId)
                    .param("taskId", taskId);
        } else {
            spec = jdbc.sql("select v.id, v.status, v.stale_reason from task t " + latestValidation + """
                             where t.project_id = :projectId
                               and t.assignee_member_id = :memberId
                               and t.status in (:open)
                             order by t.id
                            """)
                    .param("projectId", projectId)
                    .param("memberId", memberId)
                    .param("open", OPEN_STATUSES);
        }
        List<ValidationValidityResponse> out = new ArrayList<>();
        for (Map<String, Object> row : spec.query().listOfRows()) {
            String status = (String) row.get("status");
            String reason = null;
            if ("STALE".equals(status)) {
                reason = (String) row.get("stale_reason");
            }
            out.add(new ValidationValidityResponse(Ids.str(((Number) row.get("id")).longValue()), status, reason));
        }
        return out;
    }

    // ---- 변환 ----

    private BaselineRow toBaselineRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        Long proposal = rs.getObject("change_proposal_id", Long.class);
        return new BaselineRow(rs.getLong("id"), rs.getInt("version_no"), rs.getTimestamp("published_at"), proposal);
    }

    private static BaselineSummaryResponse toSummary(BaselineRow b) {
        return new BaselineSummaryResponse(Ids.str(b.id()), b.versionNo(), b.publishedAt().toInstant());
    }

    private static Long longOrNull(Object value) {
        if (value == null) {
            return null;
        }
        return ((Number) value).longValue();
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit < 1) {
            return DEFAULT_LIMIT;
        }
        if (limit > MAX_LIMIT) {
            return MAX_LIMIT;
        }
        return limit;
    }
}
