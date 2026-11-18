package com.beez.backend.notification;

import com.beez.backend.common.access.AccessErrors;
import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.api.Ids;
import com.beez.backend.common.api.PageResponse;
import com.beez.backend.common.auth.CallerContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 내 알림함(NTF-A01)·읽음 처리(NTF-A02) (S15P31A108-276).
 *
 * <p><b>보이는 알림.</b> 세션 계정이 받은 알림 중, 그 프로젝트의 활성 멤버로 남아 있는 것만이다. 조건은
 * {@code ProjectMemberRepository.findActiveMembership} 에서 계정 탈퇴 조건만 뺀 것이다(멤버십 active, 프로젝트 보관 전).
 * 탈퇴 계정은 두 API 모두 맨 앞의 {@link ProjectAccessChecker#requireUser} 가 401 로 막는다 — 탈퇴 뒤 남은 세션도 마찬가지다.
 * 나갔거나 제외됐거나
 * 보관된 프로젝트의 알림은 목록·배지·읽음 처리 어디서도 보이지 않는다 — 다시 들어오면 다시 보인다.
 * 남의 알림과 안 보이는 내 알림은 읽음 처리에서 똑같이 404 다.
 *
 * <p>입구({@link NotificationRequester})와 달리 같은 트랜잭션의 미flush JPA 변경을 볼 일이 없는 독립 요청이라
 * JdbcTemplate 으로 읽고 쓴다. NamedParameterJdbcTemplate 은 Boot 가 자동 등록하며 {@code in (:ids)} 를 목록 길이만큼 펼친다.
 */
@Service
public class NotificationInboxService {

    /** NTF-A02 ids 한 번에 최대 개수. 명세에 없음 — 화면은 한 건 또는 all=true 를 쓰고, 끝없는 IN 목록을 막는다 */
    static final int MAX_READ_IDS = 100;

    /** n = notification 별칭. 보이는 알림 조건(클래스 설명). :me 는 세션 계정 */
    private static final String VISIBLE = """
            n.recipient_account_id = :me
            and exists (select 1 from project_member m
                          join project p on p.id = m.project_id
                         where m.project_id = n.project_id
                           and m.account_id = :me
                           and m.active
                           and p.archived_at is null)
            """;

    private final ProjectAccessChecker access;
    private final NamedParameterJdbcTemplate sql;

    public NotificationInboxService(ProjectAccessChecker access, NamedParameterJdbcTemplate sql) {
        this.access = access;
        this.sql = sql;
    }

    /** NTF-A01. id 내림차순(최신 먼저), cursor = 이전 페이지 마지막 id. projectId·unreadOnly 필터는 페이지를 자르기 전에 건다. */
    @Transactional(readOnly = true)
    public NotificationInboxResponse inbox(CallerContext caller, String projectId, Boolean unreadOnly, String cursor,
                                           Integer limit) {
        // ?project_id= (빈 값)은 필터 없음 — '전체' 필터 화면이 빈 값을 그대로 붙여 보내도 열리게. 읽기라 범위가 넓어져도 해가 없다.
        // NTF-A02 의 project_id 는 읽음 범위라 빈 값을 '전체'로 넓히지 않는다(ReadTarget 은 그대로 400)
        access.requireUser(caller);
        Long projectFilter = projectId == null || projectId.isBlank() ? null : Ids.parse(projectId, "project_id");
        long beforeId = PageResponse.beforeId(cursor);
        int size = PageResponse.normalizeLimit(limit);

        MapSqlParameterSource params = new MapSqlParameterSource("me", caller.accountId())
                .addValue("projectId", projectFilter)
                .addValue("beforeId", beforeId)
                .addValue("limit", size + 1);
        String scope = VISIBLE + (projectFilter == null ? "" : " and n.project_id = :projectId");

        String listSql = """
                select n.id, n.project_id, n.type, n.title, n.body, n.ref_kind, n.ref_id, n.document_id,
                       b.version_no as baseline_version_no, n.created_at, n.read_at
                  from notification n
                  left join baseline b on n.ref_kind = 'BASELINE' and b.id = n.ref_id and b.project_id = n.project_id
                 where %s %s and n.id < :beforeId
                 order by n.id desc
                 limit :limit
                """.formatted(scope, Boolean.TRUE.equals(unreadOnly) ? "and n.read_at is null" : "");
        List<NotificationResponse> rows = sql.query(listSql, params, (rs, i) -> toResponse(rs));
        PageResponse<NotificationResponse> page = PageResponse.fromRows(rows, size, r -> Long.parseLong(r.id()), p -> p);

        Long unread = sql.queryForObject(
                "select count(*) from notification n where " + scope + " and n.read_at is null", params, Long.class);
        return NotificationInboxResponse.of(page, unread == null ? 0 : unread);
    }

    /**
     * NTF-A02. ids 는 모두 보이는 내 알림이어야 하고, 하나라도 아니면 404 로 아무것도 바꾸지 않는다(부분 처리 없음).
     * all=true 는 보이는 내 알림 전체(또는 projectId 범위). 이미 읽은 알림은 그대로 두어(read_at 유지) 다시 요청해도 0 건이다.
     * Idempotency-Key 는 받지 않는다 — 결과 상태가 요청 횟수와 무관해 재시도해도 같은 상태가 된다.
     */
    @Transactional
    public NotificationReadResponse markRead(CallerContext caller, NotificationReadRequest request) {
        access.requireUser(caller);
        ReadTarget target = ReadTarget.of(request);
        MapSqlParameterSource params = new MapSqlParameterSource("me", caller.accountId());

        if (target.all()) {
            params.addValue("projectId", target.projectId());
            String scope = VISIBLE + (target.projectId() == null ? "" : " and n.project_id = :projectId");
            int updated = sql.update("update notification n set read_at = now(), updated_at = now() where "
                    + scope + " and n.read_at is null", params);
            return new NotificationReadResponse(updated);
        }

        params.addValue("ids", target.ids());
        Integer owned = sql.queryForObject(
                "select count(*) from notification n where n.id in (:ids) and " + VISIBLE, params, Integer.class);
        if (owned == null || owned != target.ids().size()) {
            throw AccessErrors.notFound("notification_not_owned account=" + caller.accountId());
        }
        // 동시에 같은 알림을 읽음 처리해도 read_at is null 조건이 행 잠금 뒤 다시 평가되어 한쪽만 센다
        int updated = sql.update("update notification n set read_at = now(), updated_at = now()"
                + " where n.id in (:ids) and n.recipient_account_id = :me and n.read_at is null", params);
        return new NotificationReadResponse(updated);
    }

    private static NotificationResponse toResponse(ResultSet rs) throws SQLException {
        return new NotificationResponse(
                Ids.str(rs.getLong("id")),
                Ids.str(rs.getLong("project_id")),
                rs.getString("type"),
                rs.getString("title"),
                rs.getString("body"),
                rs.getString("ref_kind"),
                Ids.str(nullableLong(rs, "ref_id")),
                Ids.str(nullableLong(rs, "document_id")),
                nullableInt(rs, "baseline_version_no"),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("read_at")));
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    /**
     * 요청 본문을 검사한 결과. ids 와 all=true 는 둘 중 하나만(빈 ids 는 없는 것), project_id 는 all=true 일 때만 받는다.
     * ids 는 중복을 없앤다 — 같은 ID 를 두 번 보내도 한 건이고, 소유 확인 건수와 맞게 센다.
     */
    record ReadTarget(boolean all, Long projectId, List<Long> ids) {

        static ReadTarget of(NotificationReadRequest request) {
            if (request == null) {
                throw invalid("요청 본문이 필요합니다");
            }
            boolean all = Boolean.TRUE.equals(request.all());
            if (all) {
                // 빈 ids 는 '없음'으로 본다 — 폼 상태를 그대로 직렬화한 {"ids":[],"all":true} 도 '모두 읽음'이 되게
                if (request.ids() != null && !request.ids().isEmpty()) {
                    throw invalid("ids 와 all 은 함께 보낼 수 없습니다");
                }
                return new ReadTarget(true, Ids.parse(request.projectId(), "project_id"), List.of());
            }
            if (request.projectId() != null) {
                throw invalid("project_id 는 all=true 일 때만 보낼 수 있습니다");
            }
            if (request.ids() == null || request.ids().isEmpty()) {
                throw invalid("ids 또는 all=true 중 하나가 필요합니다");
            }
            Set<Long> distinct = new LinkedHashSet<>(Ids.parseAll(request.ids(), "ids"));
            if (distinct.size() > MAX_READ_IDS) {
                throw invalid("ids 는 한 번에 " + MAX_READ_IDS + "개까지 보낼 수 있습니다");
            }
            return new ReadTarget(false, null, List.copyOf(distinct));
        }

        private static ApiException invalid(String message) {
            return new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", message);
        }
    }
}
