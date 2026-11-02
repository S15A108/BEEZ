package com.beez.backend.impactanalysis;

import static com.beez.backend.designitem.support.DesignJson.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** All writes run in a short service transaction holding the project row lock. */
@Repository
public class ImpactRunRepository {
    private final JdbcClient jdbc;
    public ImpactRunRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public Map<String, Object> proposal(long project, long proposal) {
        return jdbc.sql("""
                SELECT c.revision_no,r.snapshot_hash FROM change_proposal c
                JOIN change_proposal_revision r ON r.change_proposal_id=c.id AND r.revision_no=c.revision_no
                WHERE c.project_id=:project AND c.id=:proposal
                """).param("project", project).param("proposal", proposal).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> new com.beez.backend.common.api.ApiException(org.springframework.http.HttpStatus.NOT_FOUND,
                        "not_found", "변경 제안을 찾을 수 없습니다"));
    }

    public void expire(long project, long proposal) {
        jdbc.sql("""
                UPDATE change_impact_run SET status='FAILED',error_code='analysis_interrupted',finished_at=clock_timestamp()
                WHERE project_id=:project AND change_proposal_id=:proposal AND status='RUNNING' AND lease_until<=clock_timestamp()
                """).param("project", project).param("proposal", proposal).update();
    }

    public long insert(ImpactContextQuery.Snapshot input, Object explicit, long account, long leaseSeconds) {
        var p = input.proposal();
        // Allocate the run ID once so the durable request key always identifies exactly one run.
        long run = jdbc.sql("SELECT nextval(pg_get_serial_sequence('change_impact_run','id'))").query(Long.class).single();
        jdbc.sql("""
                INSERT INTO change_impact_run(id,project_id,change_proposal_id,revision_no,snapshot_hash,base_baseline_id,
                  relation_set_revision,input_fingerprint,status,explicit_result,usage_status,request_key,requested_by,lease_until)
                OVERRIDING SYSTEM VALUE VALUES(:id,:project,:proposal,:revision,:hash,:baseline,:relations,:fingerprint,'RUNNING',
                  cast(:explicit AS jsonb),'NOT_STARTED',:key,:account,clock_timestamp()+:seconds*interval '1 second')
                """).param("id", run).param("project", p.projectId()).param("proposal", p.id()).param("revision", p.revisionNo())
                .param("hash", p.snapshotHash()).param("baseline", p.baselineId()).param("relations", input.relationSetRevision())
                .param("fingerprint", hash(input)).param("explicit", canonical(explicit)).param("key", "impact-" + run)
                .param("account", account).param("seconds", leaseSeconds).update();
        return run;
    }

    public boolean running(long proposal, int revision) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM change_impact_run WHERE change_proposal_id=:proposal AND revision_no=:revision AND status='RUNNING')")
                .param("proposal", proposal).param("revision", revision).query(Boolean.class).single();
    }

    public Map<String, Object> run(long id) {
        return jdbc.sql("SELECT * FROM change_impact_run WHERE id=:id").param("id", id).query().singleRow();
    }

    public Optional<Map<String, Object>> latest(long project, long proposal, int revision) {
        // A finished result remains visible while a retry runs. Fall back to the latest historical revision.
        return jdbc.sql("""
                SELECT * FROM change_impact_run WHERE project_id=:project AND change_proposal_id=:proposal
                 ORDER BY (revision_no=:revision) DESC,revision_no DESC,
                   (finished_at IS NOT NULL) DESC,finished_at DESC NULLS LAST,id DESC LIMIT 1
                """).param("project", project).param("proposal", proposal).param("revision", revision).query().listOfRows().stream().findFirst();
    }

    public void started(long id) {
        jdbc.sql("UPDATE change_impact_run SET started_at=clock_timestamp(),usage_status='UNKNOWN' WHERE id=:id AND status='RUNNING' AND lease_until>clock_timestamp()")
                .param("id", id).update();
    }

    public boolean finish(long id, String status, String code, Object aiResult, Object generation, Object usage, String usageStatus) {
        return jdbc.sql("""
                UPDATE change_impact_run SET status=:status,error_code=:code,ai_result=cast(:ai AS jsonb),generation=cast(:generation AS jsonb),
                  usage=cast(:usage AS jsonb),usage_status=:usageStatus,finished_at=clock_timestamp()
                 WHERE id=:id AND status='RUNNING' AND lease_until>clock_timestamp()
                """).param("id", id).param("status", status).param("code", code, java.sql.Types.VARCHAR)
                .param("ai", canonical(aiResult)).param("generation", canonical(generation)).param("usage", canonical(usage))
                .param("usageStatus", usageStatus).update() == 1;
    }
}
