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

    private void baseline() {
        long baseline = jdbc.queryForObject(
                "INSERT INTO baseline (project_id, version_no, published_by) VALUES (?, 1, ?) RETURNING id",
                Long.class, project.projectId(), project.leaderAccountId());
        jdbc.update("UPDATE project SET current_baseline_id = ? WHERE id = ?", baseline, project.projectId());
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
    }

    @Test void sameConflictAgainDoesNotOpenAnotherMeeting() {
        baseline();
        long a = task("T-1", memberA, "DRAFT");
        long b = task("T-2", memberB, "DRAFT");
        publishCommitted(a, b);
        publishCommitted(a, b);
        assertThat(meetings()).isEqualTo(1);
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
