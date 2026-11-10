package com.beez.backend.meeting.repository;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class MeetingRevisionDraftRepository {
    public record Row(long id, long summaryId, String payload) { }
    private final JdbcClient jdbc;
    public MeetingRevisionDraftRepository(JdbcClient jdbc) { this.jdbc = jdbc; }
    public long insert(long project, long meeting, long summary, long creator, String payload) {
        return jdbc.sql("""
                insert into meeting_revision_draft(project_id, meeting_id, summary_id, created_by, payload)
                values (:p,:m,:s,:by,cast(:payload as jsonb)) returning id
                """).param("p",project).param("m",meeting).param("s",summary).param("by",creator)
                .param("payload",payload).query(Long.class).single();
    }
    public Optional<Row> find(long project, long meeting, Long id) {
        return jdbc.sql("""
                select id, summary_id, payload::text as payload from meeting_revision_draft
                where project_id=:p and meeting_id=:m and (cast(:id as bigint) is null or id=:id)
                order by id desc limit 1
                """).param("p",project).param("m",meeting).param("id",id)
                .query((rs,n) -> new Row(rs.getLong("id"),rs.getLong("summary_id"),rs.getString("payload"))).optional();
    }
}
