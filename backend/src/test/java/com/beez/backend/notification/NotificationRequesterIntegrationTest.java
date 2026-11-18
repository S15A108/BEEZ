package com.beez.backend.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.beez.backend.project.MembershipEndReason;
import com.beez.backend.project.ProjectMemberRepository;
import com.beez.backend.project.ProjectRole;
import com.beez.backend.support.AccessFixtures.ProjectWithLeader;
import com.beez.backend.support.IntegrationTestSupport;
import com.beez.backend.task.repository.TaskRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 알림 입구 (S15P31A108-181, 계약 10-notification-activity-contract.md 2절).
 * 같은 키는 한 건, 비멤버는 건너뜀(아직 flush 전 JPA 변경 포함), 도메인 트랜잭션 밖·readOnly 호출은 예외.
 */
class NotificationRequesterIntegrationTest extends IntegrationTestSupport {

    @Autowired
    NotificationRequester requester;

    @Autowired
    ProjectMemberRepository members;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    JdbcTemplate sql;

    @Autowired
    TaskRepository tasks;

    private static NotificationRequest request(String key, long projectId, long recipient, String title) {
        return new NotificationRequest(key, projectId, recipient, "TASK_ASSIGNED", title, "작업이 배정되었습니다",
                NotificationTargetType.TASK, 501L, null);
    }

    private void inTransaction(Runnable work) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> work.run());
    }

    private List<Map<String, Object>> rowsOf(long projectId) {
        return sql.queryForList("select * from notification where project_id = ? order by id", projectId);
    }

    @Test
    void sameKeyTwice_keepsOneRow_withFirstContent() {
        ProjectWithLeader p = fixtures().projectWithLeader();
        String key = "task:501:lock:3:ASSIGNEE_CHANGED:to:" + p.leaderAccountId();

        inTransaction(() -> requester.request(request(key, p.projectId(), p.leaderAccountId(), "처음")));
        // 재요청(재시도·멱등 재처리)은 내용이 달라도 오류 없이 건너뛴다. 같은 목록 안의 중복도 마찬가지
        inTransaction(() -> requester.requestAll(List.of(
                request(key, p.projectId(), p.leaderAccountId(), "두번째"),
                request(key, p.projectId(), p.leaderAccountId(), "세번째"))));

        List<Map<String, Object>> rows = rowsOf(p.projectId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("title", "처음").containsEntry("ref_kind", "TASK")
                .containsEntry("dedupe_key", key).containsEntry("type", "TASK_ASSIGNED");
        assertThat(rows.get(0).get("read_at")).isNull();
        assertThat(rows.get(0).get("document_id")).isNull();
    }

    @Test
    void requestAll_skipsNonMembers_includingMembershipEndedButNotYetFlushed() {
        ProjectWithLeader p = fixtures().projectWithLeader();
        long stays = fixtures().account();
        fixtures().member(p.projectId(), stays, ProjectRole.MEMBER);
        long removedInSameTx = fixtures().account();
        long removedMemberId = fixtures().member(p.projectId(), removedInSameTx, ProjectRole.MEMBER);
        long outsider = fixtures().account();

        inTransaction(() -> {
            // 도메인이 JPA 로 제외하고 아직 flush 하지 않은 상태 — JdbcTemplate 이었다면 활성으로 보였을 것
            members.findById(removedMemberId).orElseThrow().end(MembershipEndReason.REMOVED, Instant.now());
            requester.requestAll(List.of(
                    request("change-proposal:71:published:to:" + stays, p.projectId(), stays, "발행"),
                    request("change-proposal:71:published:to:" + removedInSameTx, p.projectId(), removedInSameTx, "발행"),
                    request("change-proposal:71:published:to:" + outsider, p.projectId(), outsider, "발행")));
        });

        assertThat(rowsOf(p.projectId())).extracting(r -> ((Number) r.get("recipient_account_id")).longValue())
                .containsExactly(stays);
    }

    @Test
    void outsideTransaction_orInReadOnlyTransaction_isRejected() {
        ProjectWithLeader p = fixtures().projectWithLeader();
        NotificationRequest r = request("task:1:assigned:to:" + p.leaderAccountId(), p.projectId(), p.leaderAccountId(), "x");

        assertThatThrownBy(() -> requester.request(r)).isInstanceOf(IllegalTransactionStateException.class);

        TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);
        assertThatThrownBy(() -> readOnly.executeWithoutResult(status -> requester.request(r)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("readOnly");
        assertThat(rowsOf(p.projectId())).isEmpty();
    }

    @Test
    void constraintViolationFlushedByEntry_isDataIntegrityViolation() {
        // -308: 입구 INSERT 직전 auto-flush 에서 난 FK 위반도 Spring 변환을 거쳐야 공용 처리기가 400 validation_failed 로 받는다
        ProjectWithLeader p = fixtures().projectWithLeader();
        long taskId = sql.queryForObject(
                "insert into task (project_id, key, title, status) values (?, ?, '작업', 'DRAFT') returning id",
                Long.class, p.projectId(), "T-" + System.nanoTime());

        assertThatThrownBy(() -> inTransaction(() -> {
            tasks.findById(taskId).orElseThrow().changeAssignee(Long.MAX_VALUE);   // 없는 멤버 → (assignee, project) FK 위반
            requester.request(request("task:" + taskId + ":assigned:to:" + p.leaderAccountId(), p.projectId(),
                    p.leaderAccountId(), "배정"));
        })).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(rowsOf(p.projectId())).isEmpty();
    }
}
