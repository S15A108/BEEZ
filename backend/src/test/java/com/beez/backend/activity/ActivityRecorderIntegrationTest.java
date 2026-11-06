package com.beez.backend.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.beez.backend.common.api.GlobalExceptionHandler;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.support.AccessFixtures.ProjectWithLeader;
import com.beez.backend.support.IntegrationTestSupport;
import com.beez.backend.task.repository.TaskRepository;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;

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

    @Autowired
    TaskRepository tasks;

    @Autowired
    GlobalExceptionHandler exceptionHandler;

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
    void domainTransactionRolledBack_leavesNoActivity() {
        // 계약 1절: 같은 트랜잭션이라 롤백된 처리의 활동은 남지 않는다. 입구가 자기 트랜잭션(REQUIRES_NEW 등)을 열면 여기서 깨진다
        ProjectWithLeader p = fixtures().projectWithLeader();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            recorder.record(CallerContext.human(p.leaderAccountId()), ActivityEvent.of("project:" + p.projectId() + ":created",
                    p.projectId(), "PROJECT_CREATED", ActivityTargetType.PROJECT, p.projectId()));
            status.setRollbackOnly();
        });

        assertThat(rowsOf(p.projectId())).isEmpty();
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

        Throwable thrown = catchThrowable(() -> inTransaction(() -> recorder.recordBy(SystemActor.SYSTEM,
                ActivityEvent.of("validation:89:finished:1", p.projectId(), "VALIDATION_FINISHED",
                        ActivityTargetType.VALIDATION, 89L).withTaskId(otherTask))));
        assertThat(thrown).rootCause().hasMessageContaining("fk_activity_task");
        // -308: 입구 INSERT 자체의 위반은 호출한 서비스의 버그다 — 400(요청 잘못)으로 바뀌지 않고 500 으로 드러나야 한다
        var handler = new ExceptionHandlerMethodResolver(GlobalExceptionHandler.class).resolveMethod((Exception) thrown);
        assertThat(handler).isNotNull().extracting(Method::getName).isEqualTo("handleUnexpected");
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

    @Test
    void versionConflictFlushedByEntry_isRevisionConflict409() {
        // 함정(-288 실측): 입구는 네이티브 쿼리라 실행 전에 auto-flush 한다. 그때 난 @Version 충돌도 409 revision_conflict 여야 한다
        ProjectWithLeader p = fixtures().projectWithLeader();
        long taskId = draftTask(p.projectId());
        TransactionTemplate other = new TransactionTemplate(transactionManager);
        other.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        Throwable thrown = catchThrowable(() -> inTransaction(() -> {
            var task = tasks.findById(taskId).orElseThrow();
            // 읽은 뒤 다른 요청이 먼저 바꾸고 커밋했다
            other.executeWithoutResult(s -> sql.update("update task set lock_version = lock_version + 1 where id = ?", taskId));
            task.changeAssignee(null);
            task.applyNewPlanRevision(null, "바뀐 제목");
            recorder.record(CallerContext.human(p.leaderAccountId()), ActivityEvent.of("task:" + taskId + ":created",
                    p.projectId(), "TASK_CREATED", ActivityTargetType.TASK, taskId));
        }));

        // -308: 입구가 저장 전에 @Repository(PersistenceContextFlusher)로 flush 해 Spring 변환을 거친다. 변환 전(-300 때)에는 jakarta OptimisticLockException 이었다
        assertThat(thrown).isInstanceOf(ObjectOptimisticLockingFailureException.class);
        // 실제 요청처럼 @ExceptionHandler 목록으로 처리기를 고른다 — 목록에서 빠지면 handleUnexpected(500)로 간다
        var handler = new ExceptionHandlerMethodResolver(GlobalExceptionHandler.class).resolveMethod((Exception) thrown);
        assertThat(handler).isNotNull().extracting(Method::getName).isEqualTo("handleOptimisticLock");
        var response = exceptionHandler.handleOptimisticLock((Exception) thrown);
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().error().code()).isEqualTo("revision_conflict");
        assertThat(rowsOf(p.projectId())).isEmpty();
    }

    @Test
    void constraintViolationFlushedByEntry_isValidationFailed400() {
        // -308 실측: 입구의 auto-flush 에서 난 FK 위반이 Spring 변환 없이 오면 DataIntegrityViolationException 처리기(400)를
        // 비켜 가 handleUnexpected(500)로 간다
        ProjectWithLeader p = fixtures().projectWithLeader();
        long taskId = draftTask(p.projectId());

        Throwable thrown = catchThrowable(() -> inTransaction(() -> {
            tasks.findById(taskId).orElseThrow().changeAssignee(Long.MAX_VALUE);   // 없는 멤버 → (assignee, project) FK 위반
            recorder.record(CallerContext.human(p.leaderAccountId()), ActivityEvent.of("task:" + taskId + ":assigned",
                    p.projectId(), "TASK_ASSIGNED", ActivityTargetType.TASK, taskId));
        }));

        assertThat(thrown).isInstanceOf(DataIntegrityViolationException.class);
        var handler = new ExceptionHandlerMethodResolver(GlobalExceptionHandler.class).resolveMethod((Exception) thrown);
        assertThat(handler).isNotNull().extracting(Method::getName).isEqualTo("handleIntegrity");
        var response = exceptionHandler.handleIntegrity((DataIntegrityViolationException) thrown);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().error().code()).isEqualTo("validation_failed");
        assertThat(rowsOf(p.projectId())).isEmpty();
    }
}
