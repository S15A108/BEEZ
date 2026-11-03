package com.beez.backend.impactanalysis;

import static com.beez.backend.designitem.support.DesignJson.*;
import java.util.*;
import com.beez.backend.usage.repository.LlmUsageRecorder;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** All writes run in a short service transaction holding the project row lock. */
@Repository
public class ImpactRunRepository {
    private final JdbcClient jdbc;
    private final LlmUsageRecorder usageRecorder;
    public ImpactRunRepository(JdbcClient jdbc, LlmUsageRecorder usage) { this.jdbc = jdbc; this.usageRecorder = usage; }

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
                .param("fingerprint", ImpactFingerprint.of(input)).param("explicit", canonical(explicit)).param("key", "impact-" + run)
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
        // A RUNNING run past its lease is read as FAILED(analysis_interrupted) here, so the verdict never depends on
        // the persisting expire() write (which a rolled-back approval would undo). It sorts as finished at lease_until.
        return jdbc.sql("""
                SELECT *,(status='RUNNING' AND lease_until<=clock_timestamp()) AS lease_expired
                  FROM change_impact_run WHERE project_id=:project AND change_proposal_id=:proposal
                 ORDER BY (revision_no=:revision) DESC,revision_no DESC,
                   (finished_at IS NOT NULL OR (status='RUNNING' AND lease_until<=clock_timestamp())) DESC,
                   COALESCE(finished_at,CASE WHEN status='RUNNING' AND lease_until<=clock_timestamp() THEN lease_until END) DESC NULLS LAST,
                   id DESC LIMIT 1
                """).param("project", project).param("proposal", proposal).param("revision", revision).query().listOfRows().stream()
                .findFirst().map(ImpactRunRepository::effective);
    }

    private static Map<String, Object> effective(Map<String, Object> row) {
        if (!Boolean.TRUE.equals(row.get("lease_expired"))) return row;
        var view = new LinkedHashMap<>(row);
        view.put("status", "FAILED"); view.put("error_code", "analysis_interrupted");
        return view;
    }

    /** Starts the execution lease only when the queued run is still live; false means it expired or was closed. */
    public boolean started(long id, long leaseSeconds) {
        return jdbc.sql("""
                UPDATE change_impact_run SET started_at=clock_timestamp(),usage_status='UNKNOWN',
                  lease_until=clock_timestamp()+:seconds*interval '1 second'
                 WHERE id=:id AND status='RUNNING' AND lease_until>clock_timestamp()
                """).param("id", id).param("seconds", leaseSeconds).update() == 1;
    }

    public Optional<Map<String, Object>> pending(long proposal, int revision) {
        return jdbc.sql("SELECT id,requested_at FROM change_impact_run WHERE change_proposal_id=:proposal AND revision_no=:revision AND status='RUNNING' AND lease_until>clock_timestamp()")
                .param("proposal", proposal).param("revision", revision).query().listOfRows().stream().findFirst();
    }

    public boolean finish(long id, String status, String code, Object aiResult, Object generation, Object usage, String usageStatus) {
        return finish(id, status, code, aiResult, generation, usage, usageStatus, null);
    }

    /**
     * @param model 응답 최상위 model. 사용량 항목에 model 이 없을 때 공통 사용량 표에 쓴다 (없으면 "unknown")
     */
    public boolean finish(long id, String status, String code, Object aiResult, Object generation, Object usage, String usageStatus,
                          String model) {
        boolean finished = jdbc.sql("""
                UPDATE change_impact_run SET status=:status,error_code=:code,ai_result=cast(:ai AS jsonb),generation=cast(:generation AS jsonb),
                  usage=cast(:usage AS jsonb),usage_status=:usageStatus,finished_at=clock_timestamp()
                 WHERE id=:id AND status='RUNNING' AND lease_until>clock_timestamp()
                """).param("id", id).param("status", status).param("code", code, java.sql.Types.VARCHAR)
                .param("ai", canonical(aiResult)).param("generation", canonical(generation)).param("usage", canonical(usage))
                .param("usageStatus", usageStatus).update() == 1;
        recordUsage(id, usage, model);
        return finished;
    }

    /**
     * 모델을 부른 사용량을 공통 사용량 표(llm_usage)에도 남긴다 — 비용 조회(MON-A02)는 그 표만 읽는다 (-169).
     * 실행 행을 닫지 못한 경우(리스 만료 뒤 늦은 결과)에도 실제 호출 비용이므로 남긴다. 같은 호출은 request_key 로 한 번만
     */
    private void recordUsage(long id, Object entries, String model) {
        var project = jdbc.sql("SELECT project_id FROM change_impact_run WHERE id=:id").param("id", id).query(Long.class).optional();
        if (project.isEmpty()) return;
        String fallback = model == null || model.isBlank() ? "unknown" : model;
        usageRecorder.record(new LlmUsageRecorder.Owner(project.get(), "CHANGE_IMPACT", null, null, fallback, "impact run " + id), entries, 0);
    }
}
