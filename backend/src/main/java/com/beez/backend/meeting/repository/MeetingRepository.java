package com.beez.backend.meeting.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** meeting · meeting_task · meeting_participant · meeting_entry SQL. 권한과 업무 조건은 서비스가 본다 */
@Repository
public class MeetingRepository {

    public record MeetingRow(long id, long projectId, String title, Long changeProposalId, String origin,
                             String conflictSummary, String status, Instant closedAt, Long closedByAccountId,
                             Instant createdAt) {
    }

    /** memberId 는 이 프로젝트의 멤버 번호. addedByMemberId 는 추가한 멤버(비비가 부른 사람은 null) */
    public record ParticipantRow(long meetingId, long memberId, long accountId, String status, String role,
                                 Long addedByMemberId) {
    }

    public record EntryRow(long id, long meetingId, String kind, String body, String authorKind,
                           Long authorAccountId, Long authorMemberId, String authorName, String refs,
                           Instant createdAt) {
    }

    private static final String MEETING_COLUMNS = "id, project_id, title, change_proposal_id, origin, conflict_summary,"
            + " status, closed_at, closed_by, created_at";

    private final JdbcClient jdbc;

    public MeetingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---- 프로젝트·멤버 ----

    public boolean hasBaseline(long projectId) {
        return jdbc.sql("select current_baseline_id is not null from project where id = :p")
                .param("p", projectId).query(Boolean.class).optional().orElse(false);
    }

    /** 멤버 번호 → 계정. 이 프로젝트의 활성 멤버(탈퇴한 계정·삭제된 프로젝트 제외)만 담는다 */
    public Map<Long, Long> activeAccountsByMember(long projectId, Collection<Long> memberIds) {
        Map<Long, Long> out = new HashMap<>();
        if (memberIds.isEmpty()) {
            return out;
        }
        jdbc.sql("""
                select m.id, m.account_id
                  from project_member m
                  join project p on p.id = m.project_id
                  join account a on a.id = m.account_id
                 where m.project_id = :project and m.id in (:ids)
                   and m.active and p.archived_at is null and a.deleted_at is null
                """).param("project", projectId).param("ids", memberIds)
                .query((rs, n) -> out.put(rs.getLong("id"), rs.getLong("account_id"))).list();
        return out;
    }

    /** 활성 여부와 관계없이 이 프로젝트의 멤버 계정. 이미 나간 멤버의 참여 행도 정리할 수 있게 한다 */
    public Optional<Long> memberAccount(long projectId, long memberId) {
        return jdbc.sql("select account_id from project_member where id = :m and project_id = :p")
                .param("m", memberId).param("p", projectId).query(Long.class).optional();
    }

    public Optional<Long> activeLeaderAccount(long projectId) {
        return jdbc.sql("select account_id from project_member where project_id = :p and role = 'LEADER' and active")
                .param("p", projectId).query(Long.class).optional();
    }

    /**
     * 이 프로젝트 작업의 현재 담당자 계정(작업 ID 오름차순). 이 프로젝트의 작업이 아니면 나오지 않는다.
     * 담당자가 없거나 비활성·탈퇴면 값이 null.
     */
    public Map<Long, Long> taskOwnerAccounts(long projectId, Collection<Long> taskIds) {
        Map<Long, Long> out = new LinkedHashMap<>();
        if (taskIds.isEmpty()) {
            return out;
        }
        jdbc.sql("""
                select t.id, a.id as account_id
                  from task t
                  left join project_member m on m.id = t.assignee_member_id and m.project_id = t.project_id
                                            and m.active
                  left join account a on a.id = m.account_id and a.deleted_at is null
                 where t.project_id = :p and t.id in (:ids)
                 order by t.id
                """).param("p", projectId).param("ids", taskIds)
                .query((rs, n) -> {
                    out.put(rs.getLong("id"), nullableLong(rs, "account_id"));
                    return null;
                }).list();
        return out;
    }

    // ---- 회의 ----

    public Optional<MeetingRow> findMeeting(long projectId, long meetingId) {
        return jdbc.sql("select " + MEETING_COLUMNS + " from meeting where id = :id and project_id = :p")
                .param("id", meetingId).param("p", projectId).query(MeetingRepository::mapMeeting).optional();
    }

    /** 참여자 변경·기록·종료를 한 줄로 세우기 위해 회의 행을 잠그고 읽는다 */
    public Optional<MeetingRow> lockMeeting(long projectId, long meetingId) {
        return jdbc.sql("select " + MEETING_COLUMNS + " from meeting where id = :id and project_id = :p for update")
                .param("id", meetingId).param("p", projectId).query(MeetingRepository::mapMeeting).optional();
    }

    public Optional<MeetingRow> findOpenByTaskSet(long projectId, String taskSetKey) {
        return jdbc.sql("select " + MEETING_COLUMNS
                        + " from meeting where project_id = :p and task_set_key = :k and status = 'OPEN'")
                .param("p", projectId).param("k", taskSetKey).query(MeetingRepository::mapMeeting).optional();
    }

    /** id 내림차순. beforeId 보다 작은 회의를 limit 개 (호출하는 쪽이 limit+1 을 넘긴다) */
    public List<MeetingRow> list(long projectId, String status, long beforeId, int limit) {
        return jdbc.sql("select " + MEETING_COLUMNS + " from meeting where project_id = :p and id < :before"
                        + " and (cast(:status as text) is null or status = :status) order by id desc limit :limit")
                .param("p", projectId).param("before", beforeId).param("status", status).param("limit", limit)
                .query(MeetingRepository::mapMeeting).list();
    }

    /**
     * 같은 작업 묶음의 열린 회의가 이미 있으면 만들지 않고 empty. 동시 호출은 부분 유니크 인덱스가 하나만 통과시킨다
     * (on conflict do nothing 은 먼저 들어간 트랜잭션의 커밋을 기다린 뒤 판정한다).
     */
    public Optional<Long> insertMeeting(long projectId, String title, String conflictSummary, String taskSetKey,
                                        List<Long> taskIds) {
        Optional<Long> id = jdbc.sql("""
                insert into meeting (project_id, title, status, origin, conflict_summary, task_set_key)
                values (:p, :title, 'OPEN', 'BEE_CONFLICT', :summary, :key)
                on conflict (project_id, task_set_key) where status = 'OPEN' do nothing
                returning id
                """).param("p", projectId).param("title", title).param("summary", conflictSummary)
                .param("key", taskSetKey).query(Long.class).optional();
        if (id.isPresent()) {
            for (Long taskId : taskIds) {
                jdbc.sql("insert into meeting_task (meeting_id, project_id, task_id) values (:m, :p, :t)")
                        .param("m", id.get()).param("p", projectId).param("t", taskId).update();
            }
        }
        return id;
    }

    /** 회의별 작업 ID(오름차순) */
    public Map<Long, List<Long>> taskIds(Collection<Long> meetingIds) {
        Map<Long, List<Long>> out = new HashMap<>();
        if (meetingIds.isEmpty()) {
            return out;
        }
        jdbc.sql("select meeting_id, task_id from meeting_task where meeting_id in (:ids) order by meeting_id, task_id")
                .param("ids", meetingIds)
                .query((rs, n) -> out.computeIfAbsent(rs.getLong("meeting_id"), k -> new ArrayList<>())
                        .add(rs.getLong("task_id"))).list();
        return out;
    }

    // ---- 참여자 ----

    public boolean isParticipant(long meetingId, long accountId) {
        return jdbc.sql("select exists (select 1 from meeting_participant where meeting_id = :m and account_id = :a)")
                .param("m", meetingId).param("a", accountId).query(Boolean.class).single();
    }

    /** 이미 참여 중인 계정은 건너뛴다. 새로 넣었으면 1 */
    public int addParticipant(long meetingId, long accountId, String role, Long invitedByAccountId) {
        return jdbc.sql("""
                insert into meeting_participant (meeting_id, account_id, status, role, invited_by)
                values (:m, :a, 'JOINED', :role, :by)
                on conflict (meeting_id, account_id) do nothing
                """).param("m", meetingId).param("a", accountId).param("role", role).param("by", invitedByAccountId)
                .update();
    }

    public int removeParticipant(long meetingId, long accountId) {
        return jdbc.sql("delete from meeting_participant where meeting_id = :m and account_id = :a")
                .param("m", meetingId).param("a", accountId).update();
    }

    /** 회의별 참여자(멤버 번호 오름차순). 멤버 번호와 추가한 멤버는 이 프로젝트 기준으로 바꾼다 */
    public Map<Long, List<ParticipantRow>> participants(long projectId, Collection<Long> meetingIds) {
        Map<Long, List<ParticipantRow>> out = new HashMap<>();
        if (meetingIds.isEmpty()) {
            return out;
        }
        jdbc.sql("""
                select mp.meeting_id, m.id as member_id, mp.account_id, mp.status, mp.role,
                       adder.id as added_by_member_id
                  from meeting_participant mp
                  join project_member m on m.account_id = mp.account_id and m.project_id = :p
                  left join project_member adder on adder.account_id = mp.invited_by and adder.project_id = :p
                 where mp.meeting_id in (:ids)
                 order by mp.meeting_id, m.id
                """).param("p", projectId).param("ids", meetingIds)
                .query((rs, n) -> {
                    Long addedBy = nullableLong(rs, "added_by_member_id");
                    return out.computeIfAbsent(rs.getLong("meeting_id"), k -> new ArrayList<>())
                            .add(new ParticipantRow(rs.getLong("meeting_id"), rs.getLong("member_id"),
                                    rs.getLong("account_id"), rs.getString("status"), rs.getString("role"), addedBy));
                }).list();
        return out;
    }

    // ---- 기록 ----

    public long insertEntry(long meetingId, String kind, String authorKind, Long authorAccountId, String body,
                            String refsJson) {
        return jdbc.sql("""
                insert into meeting_entry (meeting_id, kind, author_kind, author_account_id, body, refs)
                values (:m, :kind, :ak, :a, :body, cast(:refs as jsonb)) returning id
                """).param("m", meetingId).param("kind", kind).param("ak", authorKind).param("a", authorAccountId)
                .param("body", body).param("refs", refsJson).query(Long.class).single();
    }

    public Optional<EntryRow> findEntry(long projectId, long entryId) {
        return jdbc.sql(entrySelect() + " where e.id = :id").param("p", projectId).param("id", entryId)
                .query(MeetingRepository::mapEntry).optional();
    }

    /** 작성 순서(id 오름차순)로 afterId 뒤를 limit 개. 호출하는 쪽이 limit+1 을 넘긴다 */
    public List<EntryRow> listEntries(long projectId, long meetingId, long afterId, int limit) {
        return jdbc.sql(entrySelect() + " where e.meeting_id = :m and e.id > :after order by e.id limit :limit")
                .param("p", projectId).param("m", meetingId).param("after", afterId).param("limit", limit)
                .query(MeetingRepository::mapEntry).list();
    }

    /** 같은 키의 동시 요청을 한 줄로 세운다. 트랜잭션이 끝나면 풀린다 */
    public void lockIdempotency(long meetingId, long accountId, String key) {
        jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:k, 0))")
                .param("k", "meeting_entry:" + meetingId + ":" + accountId + ":" + key).query().singleRow();
    }

    private static String entrySelect() {
        return """
                select e.id, e.meeting_id, e.kind, e.body, e.author_kind, e.author_account_id, e.refs::text as refs,
                       e.created_at, m.id as author_member_id, a.display_name as author_name
                  from meeting_entry e
                  left join account a on a.id = e.author_account_id
                  left join project_member m on m.account_id = e.author_account_id and m.project_id = :p
                """;
    }

    private static MeetingRow mapMeeting(ResultSet rs, int rowNum) throws SQLException {
        return new MeetingRow(rs.getLong("id"), rs.getLong("project_id"), rs.getString("title"),
                nullableLong(rs, "change_proposal_id"), rs.getString("origin"), rs.getString("conflict_summary"),
                rs.getString("status"), instant(rs, "closed_at"), nullableLong(rs, "closed_by"),
                rs.getTimestamp("created_at").toInstant());
    }

    private static EntryRow mapEntry(ResultSet rs, int rowNum) throws SQLException {
        return new EntryRow(rs.getLong("id"), rs.getLong("meeting_id"), rs.getString("kind"), rs.getString("body"),
                rs.getString("author_kind"), nullableLong(rs, "author_account_id"), nullableLong(rs, "author_member_id"),
                rs.getString("author_name"), rs.getString("refs"), rs.getTimestamp("created_at").toInstant());
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }
}
