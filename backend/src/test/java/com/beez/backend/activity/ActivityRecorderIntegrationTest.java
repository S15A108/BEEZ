package com.beez.backend.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.support.AccessFixtures.ProjectWithLeader;
import com.beez.backend.support.IntegrationTestSupport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 활동 입구 (S15P31A108-182, 계약 10-notification-activity-contract.md 2·4절).
 * 같은 키는 한 건, 행위자 종류별 저장, 다른 프로젝트 보조 참조 거부, 도메인 트랜잭션 밖·readOnly 호출 거부.
 */
class ActivityRecorderIntegrationTest extends IntegrationTestSupport {

    @Autowired
    ActivityRecorder recorder;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    JdbcTemplate sql;

    private void inTransaction(Runnable work) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> work.run());
    }

    private List<Map<String, Object>> rowsOf(long projectId) {
        return sql.queryForList("select * from activity_event where project_id = ? order by id", projectId);
    }

    private long draftTask(long projectId) {
        return sql.queryForObject("insert into task (project_id, key, title, status) values (?, ?, '작업', 'DRAFT') returning id",
                Long.class, projectId, "T-" + System.nanoTime());
    }

    @Test
    void sameKeyResent_keepsOneRow_withFirstContent() {
        ProjectWithLeader p = fixtures().projectWithLeader();
        CallerContext leader = CallerContext.human(p.leaderAccountId());
        String key = "project:" + p.projectId() + ":created";

        inTransaction(() -> recorder.record(leader,
                ActivityEvent.of(key, p.projectId(), "PROJECT_CREATED", ActivityTargetType.PROJECT, p.projectId())));
        // 재전송(멱등 재요청·재시도)은 내용·행위자가 달라도 오류 없이 건너뛴다
        inTransaction(() -> recorder.recordBy(SystemActor.SYSTEM,
                ActivityEvent.of(key, p.projectId(), "PROJECT_RENAMED", ActivityTargetType.PROJECT, p.projectId())
                        .withResult(ActivityResult.FAILURE)));

        List<Map<String, Object>> rows = rowsOf(p.projectId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("event_type", "PROJECT_CREATED").containsEntry("actor_kind", "HUMAN")
                .containsEntry("result", "SUCCESS").containsEntry("dedupe_key", key);
    }

    @Test
    void eachActorKind_isStoredWithItsAccountAndSecondaryRefs() {
        ProjectWithLeader p = fixtures().projectWithLeader();
        long task = draftTask(p.projectId());
        long token = fixtures().agentToken(p.projectId(), p.leaderAccountId());

        inTransaction(() -> {
            recorder.record(CallerContext.human(p.leaderAccountId()),
                    ActivityEvent.of("task:" + task + ":created", p.projectId(), "TASK_CREATED", ActivityTargetType.TASK, task));
            recorder.record(CallerContext.agent(p.leaderAccountId(), token),
                    ActivityEvent.of("task:" + task + ":lock:1:START", p.projectId(), "TASK_STARTED", ActivityTargetType.TASK, task));
            recorder.recordBy(SystemActor.BEE,
                    ActivityEvent.of("meeting:3:opened", p.projectId(), "MEETING_OPENED", ActivityTargetType.MEETING, 3L));
            recorder.recordBy(SystemActor.SYSTEM,
                    ActivityEvent.of("validation:88:finished:1", p.projectId(), "VALIDATION_FINISHED",
                            ActivityTargetType.VALIDATION, 88L).withTaskId(task).withResult(ActivityResult.FAILURE));
        });

        List<Map<String, Object>> rows = rowsOf(p.projectId());
        assertThat(rows).extracting(r -> r.get("actor_kind")).containsExactly("HUMAN", "AGENT", "BEE", "SYSTEM");
        assertThat(rows).extracting(r -> r.get("actor_id") == null ? null : ((Number) r.get("actor_id")).longValue())
                .containsExactly(p.leaderAccountId(), p.leaderAccountId(), null, null);
        assertThat(rows).extracting(r -> r.get("task_id") == null ? null : ((Number) r.get("task_id")).longValue())
                .containsExactly(task, task, null, task);
        assertThat(rows.get(3)).containsEntry("ref_kind", "VALIDATION").containsEntry("result", "FAILURE");
        assertThat(rows).allSatisfy(r -> assertThat(r.get("agent_session_id")).isNull());
    }

    @Test
    void secondaryRefFromAnotherProject_isRejected() {
        // 보조 참조가 다른 프로젝트 행을 가리키면 그 프로젝트 타임라인 필터에 남의 작업이 섞인다 — (id, project_id) FK 로 막는다
        ProjectWithLeader p = fixtures().projectWithLeader();
        ProjectWithLeader other = fixtures().projectWithLeader();
        long otherTask = draftTask(other.projectId());

        assertThatThrownBy(() -> inTransaction(() -> recorder.recordBy(SystemActor.SYSTEM,
                ActivityEvent.of("validation:89:finished:1", p.projectId(), "VALIDATION_FINISHED",
                        ActivityTargetType.VALIDATION, 89L).withTaskId(otherTask))))
                .rootCause().hasMessageContaining("fk_activity_task");
        assertThat(rowsOf(p.projectId())).isEmpty();
    }

    @Test
    void outsideTransaction_orInReadOnlyTransaction_isRejected() {
        ProjectWithLeader p = fixtures().projectWithLeader();
        ActivityEvent e = ActivityEvent.of("project:" + p.projectId() + ":created", p.projectId(), "PROJECT_CREATED",
                ActivityTargetType.PROJECT, p.projectId());

        assertThatThrownBy(() -> recorder.recordBy(SystemActor.SYSTEM, e)).isInstanceOf(IllegalTransactionStateException.class);

        TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);
        assertThatThrownBy(() -> readOnly.executeWithoutResult(status -> recorder.recordBy(SystemActor.SYSTEM, e)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("readOnly");
        assertThat(rowsOf(p.projectId())).isEmpty();
    }
}
