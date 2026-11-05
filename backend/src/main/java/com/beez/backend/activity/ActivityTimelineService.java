package com.beez.backend.activity;

import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.api.Ids;
import com.beez.backend.common.api.PageResponse;
import com.beez.backend.common.auth.CallerContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 프로젝트 활동 타임라인 조회 MON-A04 (S15P31A108-183). 저장 입구는 {@link ActivityRecorder}(-182)이고 여기서는 읽기만 한다.
 *
 * <p><b>권한.</b> 프로젝트 활성 멤버 전원(계약 4절 "타임라인은 멤버 전원이 본다"). 비멤버·없는 프로젝트는 404.
 * <p><b>정렬·cursor.</b> id 내림차순, cursor 는 이전 페이지 마지막 id (계약 4절 — 시각 정렬은 늦게 커밋된 활동이 이어 보기에서 빠진다).
 * <p>독립 요청이라 같은 트랜잭션의 미flush JPA 변경을 볼 일이 없어 JdbcTemplate 으로 읽는다(알림함과 같은 이유).
 */
@Service
public class ActivityTimelineService {

    private final ProjectAccessChecker access;
    private final NamedParameterJdbcTemplate sql;

    public ActivityTimelineService(ProjectAccessChecker access, NamedParameterJdbcTemplate sql) {
        this.access = access;
        this.sql = sql;
    }

    /** filter 는 권한 확인 뒤에 해석한다 — 비로그인·비멤버가 잘못된 필터를 보내도 400 이 아니라 401·404 가 먼저다. */
    @Transactional(readOnly = true)
    public PageResponse<ActivityResponse> timeline(CallerContext caller, long projectId, Supplier<TimelineFilter> filterInput,
                                                   String cursor, Integer limit) {
        access.requireMember(caller, projectId);
        TimelineFilter filter = filterInput.get();
        long beforeId = PageResponse.beforeId(cursor);
        int size = PageResponse.normalizeLimit(limit);

        MapSqlParameterSource params = new MapSqlParameterSource("projectId", projectId)
                .addValue("beforeId", beforeId)
                .addValue("limit", size + 1);
        // 행위자 이름: 사람은 계정 이름, 에이전트는 세션의 토큰 이름(이름 없는 토큰·세션 기록 전이면 계정 이름)
        String listSql = """
                select a.id, a.event_type, a.actor_kind, a.actor_id, a.agent_session_id,
                       case when a.actor_kind in ('HUMAN', 'AGENT') then coalesce(t.name, acc.display_name) end as actor_name,
                       a.task_id, a.change_proposal_id, a.baseline_id, a.ref_kind, a.ref_id, a.result, a.occurred_at
                  from activity_event a
                  left join account acc on acc.id = a.actor_id
                  left join agent_session s on s.id = a.agent_session_id
                  left join agent_token t on t.id = s.agent_token_id
                 where a.project_id = :projectId and a.id < :beforeId %s
                 order by a.id desc
                 limit :limit
                """.formatted(filter.where(params));
        List<ActivityResponse> rows = sql.query(listSql, params, (rs, i) -> toResponse(rs));
        return PageResponse.fromRows(rows, size, r -> Long.parseLong(r.id()), p -> p);
    }

    private static ActivityResponse toResponse(ResultSet rs) throws SQLException {
        return new ActivityResponse(
                Ids.str(rs.getLong("id")),
                rs.getString("event_type"),
                rs.getString("actor_kind"),
                Ids.str(nullableLong(rs, "actor_id")),
                Ids.str(nullableLong(rs, "agent_session_id")),
                rs.getString("actor_name"),
                Ids.str(nullableLong(rs, "task_id")),
                Ids.str(nullableLong(rs, "change_proposal_id")),
                Ids.str(nullableLong(rs, "baseline_id")),
                rs.getString("ref_kind"),
                Ids.str(nullableLong(rs, "ref_id")),
                rs.getString("result"),
                rs.getTimestamp("occurred_at").toInstant());
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
