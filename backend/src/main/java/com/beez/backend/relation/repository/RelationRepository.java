package com.beez.backend.relation.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 관계 표(design_item_dependency·이력·관계 집합 리비전) 읽기·쓰기와, 확인에 필요한 다른 영역 표 조회.
 * 다른 영역 표(project·baseline·design_item·change_proposal)는 엔티티 없이 필요한 칸만 읽는다 (AGENTS.md backend 규칙).
 */
@Repository
public class RelationRepository {

    private final JdbcClient jdbc;

    public RelationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---- 확인용 조회 ----

    /** 프로젝트의 가장 최근 기준 (버전 번호가 가장 큰 것). 발행 중이면 아직 현재 기준이 아닌 새 기준이다 */
    public Optional<Long> latestBaselineId(long projectId) {
        return jdbc.sql("select id from baseline where project_id = :p order by version_no desc limit 1")
                .param("p", projectId).query(Long.class).optional();
    }

    public boolean changeProposalInProject(long changeProposalId, long projectId) {
        return jdbc.sql("select id from change_proposal where id = :c and project_id = :p")
                .param("c", changeProposalId).param("p", projectId).query(Long.class).optional().isPresent();
    }

    public boolean itemInProject(long itemId, long projectId) {
        return jdbc.sql("select id from design_item where id = :i and project_id = :p")
                .param("i", itemId).param("p", projectId).query(Long.class).optional().isPresent();
    }

    /** ids 중 이 프로젝트 항목의 개수 (ids 는 중복 없이 넘긴다) */
    public int countItemsInProject(Collection<Long> itemIds, long projectId) {
        if (itemIds.isEmpty()) {
            return 0;
        }
        return jdbc.sql("select count(*) from design_item where id in (:ids) and project_id = :p")
                .param("ids", itemIds).param("p", projectId).query(Integer.class).single();
    }

    /** 기준 설계에 들어 있는 항목 → 그 기준의 항목 버전. 기준에 없는 항목은 결과에 없다 */
    public Map<Long, Long> versionsInBaseline(long projectId, long baselineId, Collection<Long> itemIds) {
        Map<Long, Long> out = new HashMap<>();
        if (itemIds.isEmpty()) {
            return out;
        }
        jdbc.sql("""
                        select design_item_id, design_item_version_id from baseline_item
                         where baseline_id = :b and project_id = :p and design_item_id in (:ids)
                        """)
                .param("b", baselineId).param("p", projectId).param("ids", itemIds)
                .query((rs, n) -> {
                    out.put(rs.getLong("design_item_id"), rs.getLong("design_item_version_id"));
                    return null;
                })
                .list();
        return out;
    }

    // ---- 현재 관계 ----

    /** 이 항목들이 한쪽 끝인 확정(CONFIRMED) 관계를 잠그고 읽는다. 잠금 순서를 일정하게 하려고 (from, to) 순 */
    public List<DependencyRow> lockConfirmedTouching(long projectId, Collection<Long> itemIds) {
        if (itemIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        select * from design_item_dependency
                         where project_id = :p and status = 'CONFIRMED'
                           and (from_item_id in (:ids) or to_item_id in (:ids))
                         order by from_item_id, to_item_id
                           for update
                        """)
                .param("p", projectId).param("ids", itemIds).query(RelationRepository::dependency).list();
    }

    /** 한 쌍을 잠그고 읽는다. 없으면 빈 값 */
    public Optional<DependencyRow> lockPair(long projectId, long fromItemId, long toItemId) {
        return jdbc.sql("""
                        select * from design_item_dependency
                         where project_id = :p and from_item_id = :f and to_item_id = :t
                           for update
                        """)
                .param("p", projectId).param("f", fromItemId).param("t", toItemId)
                .query(RelationRepository::dependency).optional();
    }

    /**
     * 한 쌍을 새로 넣거나 덮어쓴다. 확인자·시각은 confirmer 에 따라 지금 값으로 바꾸거나(확정·유지), 그대로 두거나(해제 —
     * 누가 확정했었는지 남김), 비운다(후보 — 후보에 예전 확정 흔적이 남으면 "확정 근거"로 오해할 수 있다).
     * on conflict … do update: 같은 쌍(PK)이 이미 있으면 insert 대신 update 한다 (PostgreSQL 문법).
     */
    public void upsert(long projectId, long fromItemId, long toItemId, String origin, String status, String reason,
                       Confirmer confirmer) {
        String insertAt = "null";
        String updateBy = "design_item_dependency.confirmed_by";
        String updateAt = "design_item_dependency.confirmed_at";
        if (confirmer.mode() == Confirmer.Mode.SET) {
            insertAt = "now()";
            updateBy = "excluded.confirmed_by";
            updateAt = "excluded.confirmed_at";
        } else if (confirmer.mode() == Confirmer.Mode.CLEAR) {
            updateBy = "null";
            updateAt = "null";
        }
        jdbc.sql("""
                        insert into design_item_dependency
                               (project_id, from_item_id, to_item_id, origin, status, reason, confirmed_by, confirmed_at)
                        values (:p, :f, :t, :o, :s, :r, :cb, %s)
                        on conflict (from_item_id, to_item_id) do update
                           set origin       = excluded.origin,
                               status       = excluded.status,
                               reason       = excluded.reason,
                               confirmed_by = %s,
                               confirmed_at = %s,
                               updated_at   = now()
                        """.formatted(insertAt, updateBy, updateAt))
                .param("p", projectId).param("f", fromItemId).param("t", toItemId).param("o", origin)
                .param("s", status).param("r", reason).param("cb", confirmer.accountId(), Types.BIGINT)
                .update();
    }

    /** 상태만 바꾼다 (재확인 표시). 근거는 확정 때의 것을 둔다 */
    public void updateStatus(long projectId, long fromItemId, long toItemId, String status) {
        jdbc.sql("""
                        update design_item_dependency set status = :s, updated_at = now()
                         where project_id = :p and from_item_id = :f and to_item_id = :t
                        """)
                .param("s", status).param("p", projectId).param("f", fromItemId).param("t", toItemId).update();
    }

    /** 이 항목이 한쪽 끝인 현재 관계 중 statuses 상태인 것 (from, to 순) */
    public List<DependencyRow> dependenciesOf(long projectId, long itemId, Collection<String> statuses) {
        return jdbc.sql("""
                        select * from design_item_dependency
                         where project_id = :p and (from_item_id = :i or to_item_id = :i) and status in (:s)
                         order by from_item_id, to_item_id
                        """)
                .param("p", projectId).param("i", itemId).param("s", statuses)
                .query(RelationRepository::dependency).list();
    }

    // ---- 관계 집합 리비전 ----

    /**
     * 리비전을 1 올리고 올린 값을 돌려준다. 행이 없으면 1 로 만든다.
     * 같은 행을 갱신하므로 동시에 두 요청이 와도 행 잠금으로 한 줄로 선다.
     */
    public long bumpRevision(long projectId) {
        return jdbc.sql("""
                        insert into design_relation_set (project_id, revision) values (:p, 1)
                        on conflict (project_id) do update
                           set revision = design_relation_set.revision + 1, updated_at = now()
                        returning revision
                        """)
                .param("p", projectId).query(Long.class).single();
    }

    /** 지금 리비전. 관계 변경이 한 번도 없으면 0 */
    public long currentRevision(long projectId) {
        return jdbc.sql("select revision from design_relation_set where project_id = :p")
                .param("p", projectId).query(Long.class).optional().orElse(0L);
    }

    // ---- 이력 ----

    public long insertHistory(HistoryRow h) {
        return jdbc.sql("""
                        insert into design_item_dependency_history
                               (project_id, from_item_id, to_item_id, action, origin_after, status_after, baseline_id,
                                from_version_id, to_version_id, change_proposal_id, relation_set_revision, reason,
                                actor_account_id)
                        values (:p, :f, :t, :a, :o, :s, :b, :fv, :tv, :cp, :rev, :r, :actor)
                        returning id
                        """)
                .param("p", h.projectId()).param("f", h.fromItemId()).param("t", h.toItemId())
                .param("a", h.action()).param("o", h.originAfter()).param("s", h.statusAfter())
                .param("b", h.baselineId(), Types.BIGINT).param("fv", h.fromVersionId(), Types.BIGINT)
                .param("tv", h.toVersionId(), Types.BIGINT).param("cp", h.changeProposalId(), Types.BIGINT)
                .param("rev", h.relationSetRevision(), Types.BIGINT).param("r", h.reason(), Types.VARCHAR)
                .param("actor", h.actorAccountId())
                .query(Long.class).single();
    }

    /** 이 항목이 한쪽 끝인 이력. id 내림차순, beforeId 보다 작은 것 limit 개 */
    public List<HistoryRow> historyOf(long projectId, long itemId, long beforeId, int limit) {
        return jdbc.sql("""
                        select * from design_item_dependency_history
                         where project_id = :p and (from_item_id = :i or to_item_id = :i) and id < :before
                         order by id desc
                         limit :limit
                        """)
                .param("p", projectId).param("i", itemId).param("before", beforeId).param("limit", limit)
                .query((rs, n) -> new HistoryRow(rs.getLong("id"), rs.getLong("project_id"),
                        rs.getLong("from_item_id"), rs.getLong("to_item_id"), rs.getString("action"),
                        rs.getString("origin_after"), rs.getString("status_after"), nullableLong(rs, "baseline_id"),
                        nullableLong(rs, "from_version_id"), nullableLong(rs, "to_version_id"),
                        nullableLong(rs, "change_proposal_id"), nullableLong(rs, "relation_set_revision"),
                        rs.getString("reason"), rs.getLong("actor_account_id"), instant(rs, "created_at")))
                .list();
    }

    // ---- 행 변환 ----

    private static DependencyRow dependency(ResultSet rs, int rowNum) throws SQLException {
        return new DependencyRow(rs.getLong("from_item_id"), rs.getLong("to_item_id"), rs.getString("origin"),
                rs.getString("status"), rs.getString("reason"), nullableLong(rs, "confirmed_by"),
                instant(rs, "confirmed_at"));
    }

    /** getObject 로 읽어 NULL 이면 null (getLong 은 NULL 을 0 으로 준다) */
    static Long nullableLong(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value == null) {
            return null;
        }
        return ((Number) value).longValue();
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        if (value == null) {
            return null;
        }
        return value.toInstant();
    }
}
