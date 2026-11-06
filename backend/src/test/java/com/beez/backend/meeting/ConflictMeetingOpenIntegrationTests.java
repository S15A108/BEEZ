package com.beez.backend.meeting;

import static org.assertj.core.api.Assertions.assertThat;

import com.beez.backend.project.ProjectRole;
import com.beez.backend.support.AccessFixtures;
import com.beez.backend.support.IntegrationTestSupport;
import com.beez.backend.validation.event.ConflictFindingsSaved;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 충돌 이벤트로 회의가 자동 개설된다 (S15P31A108-248). 리스너는 AFTER_COMMIT 이라 실제 커밋을 거쳐야 하므로
 * 이벤트를 트랜잭션 안에서 발행하고 커밋한 뒤 DB 행을 직접 본다 — 리스너를 직접 부르면 트랜잭션 동기화 때문에 저장 누락이 가려진다.
 */
class ConflictMeetingOpenIntegrationTests extends IntegrationTestSupport {

    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired TransactionTemplate tx;

    AccessFixtures.ProjectWithLeader project;
    long memberA;
    long memberB;

    @BeforeEach
    void setUp() {
        project = fixtures().projectWithLeader();
        memberA = fixtures().member(project.projectId(), fixtures().account(), ProjectRole.MEMBER);
        memberB = fixtures().member(project.projectId(), fixtures().account(), ProjectRole.MEMBER);
    }

    private long baseline() {
        long baseline = jdbc.queryForObject(
                "INSERT INTO baseline (project_id, version_no, published_by) VALUES (?, 1, ?) RETURNING id",
                Long.class, project.projectId(), project.leaderAccountId());
        jdbc.update("UPDATE project SET current_baseline_id = ? WHERE id = ?", baseline, project.projectId());
        return baseline;
    }

    /** 작업 a 의 검증 하나와 모델 실행 하나를 만들고 (검증 ID, 실행 ID) 를 돌려준다 */
    private long[] validationOf(long taskId, long baselineId) {
        long revision = jdbc.queryForObject("""
                INSERT INTO task_plan_revision (task_id, revision_no, title, goal, input_hash, created_by_account_id)
                VALUES (?, 1, 't', 'g', 'h', ?) RETURNING id
                """, Long.class, taskId, project.leaderAccountId());
        long validation = jdbc.queryForObject("""
                INSERT INTO validation (task_id, plan_revision_id, baseline_id, status, trigger, input_snapshot_hash, rule_version)
                VALUES (?, ?, ?, 'NEEDS_REVIEW', 'AGENT', 'h', 'r') RETURNING id
                """, Long.class, taskId, revision, baselineId);
        long run = jdbc.queryForObject("""
                INSERT INTO validation_run (validation_id, attempt, kind, status) VALUES (?, 1, 'MODEL', 'OK') RETURNING id
                """, Long.class, validation);
        return new long[] {validation, run};
    }

    private void conflictFinding(long[] validation, long relatedTaskId, String message) {
        jdbc.update("""
                INSERT INTO validation_finding (validation_id, validation_run_id, source, kind, severity, related_task_id, message, resolution)
                VALUES (?, ?, 'MODEL', 'CONFLICT', 'BLOCKING', ?, ?, 'HUMAN_REVIEW')
                """, validation[0], validation[1], relatedTaskId, message);
    }

    private long task(String key, Long assignee, String status) {
        return jdbc.queryForObject("""
                INSERT INTO task (project_id, key, title, assignee_member_id, status)
                VALUES (?, ?, ?, ?, ?) RETURNING id
                """, Long.class, project.projectId(), key, key, assignee, status);
    }

    private void publishCommitted(long taskId, long relatedId) {
        tx.executeWithoutResult(s -> publisher.publishEvent(
                new ConflictFindingsSaved(project.projectId(), 1L, taskId, List.of(relatedId))));
    }

    private int meetings() {
        return jdbc.queryForObject("SELECT count(*) FROM meeting WHERE project_id = ?", Integer.class, project.projectId());
    }

    @Test void committedEventOpensMeetingWithBriefAndParticipants() {
        baseline();
        long a = task("T-1", memberA, "DRAFT");
        long b = task("T-2", memberB, "DRAFT");
        publishCommitted(a, b);
        assertThat(meetings()).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM meeting_entry e JOIN meeting m ON m.id = e.meeting_id
                 WHERE m.project_id = ? AND e.kind = 'CONFLICT_BRIEF'
                """, Integer.class, project.projectId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM meeting_participant p JOIN meeting m ON m.id = p.meeting_id WHERE m.project_id = ?
                """, Integer.class, project.projectId())).isEqualTo(3);   // 팀장 + 두 담당자
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification WHERE project_id = ? AND type = 'MEETING_OPENED'",
                Integer.class, project.projectId())).isEqualTo(3);   // 참여자 전원
    }

    @Test void twoConflictsOnOnePairAreBothInTheBrief() {
        long baseline = baseline();
        long a = task("T-1", memberA, "DRAFT");
        long b = task("T-2", memberB, "DRAFT");
        long[] validation = validationOf(a, baseline);
        conflictFinding(validation, b, "응답 형식이 달라요");
        conflictFinding(validation, b, "만료 정책이 달라요");
        tx.executeWithoutResult(s -> publisher.publishEvent(
                new ConflictFindingsSaved(project.projectId(), validation[0], a, List.of(b))));
        assertThat(meetings()).isEqualTo(1);
        String brief = jdbc.queryForObject("""
                SELECT e.body FROM meeting_entry e JOIN meeting m ON m.id = e.meeting_id
                 WHERE m.project_id = ? AND e.kind = 'CONFLICT_BRIEF'
                """, String.class, project.projectId());
        assertThat(brief).contains("(2가지)", "1. 응답 형식이 달라요", "2. 만료 정책이 달라요");
    }

    @Test void sameConflictAgainDoesNotOpenAnotherMeeting() {
        baseline();
        long a = task("T-1", memberA, "DRAFT");
        long b = task("T-2", memberB, "DRAFT");
        publishCommitted(a, b);
        publishCommitted(a, b);
        assertThat(meetings()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification WHERE project_id = ? AND type = 'MEETING_OPENED'",
                Integer.class, project.projectId())).isEqualTo(3);   // 재검증으로 알림이 늘지 않는다
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM meeting_entry e JOIN meeting m ON m.id = e.meeting_id WHERE m.project_id = ?
                """, Integer.class, project.projectId())).isEqualTo(1);
    }

    @Test void sameAssigneeOrClosedTaskOpensNothing() {
        baseline();
        long a = task("T-1", memberA, "DRAFT");
        long sameOwner = task("T-2", memberA, "DRAFT");
        long cancelled = task("T-3", memberB, "CANCELLED");
        publishCommitted(a, sameOwner);
        publishCommitted(a, cancelled);
        assertThat(meetings()).isZero();
    }

    @Test void missingBaselineIsSwallowedAndOpensNothing() {
        long a = task("T-1", memberA, "DRAFT");
        long b = task("T-2", memberB, "DRAFT");
        publishCommitted(a, b);   // 예외가 호출자에게 번지지 않는다
        assertThat(meetings()).isZero();
    }

    @Test void rolledBackPublisherOpensNothing() {
        baseline();
        long a = task("T-1", memberA, "DRAFT");
        long b = task("T-2", memberB, "DRAFT");
        tx.executeWithoutResult(s -> {
            publisher.publishEvent(new ConflictFindingsSaved(project.projectId(), 1L, a, List.of(b)));
            s.setRollbackOnly();
        });
        assertThat(meetings()).isZero();
    }
}
