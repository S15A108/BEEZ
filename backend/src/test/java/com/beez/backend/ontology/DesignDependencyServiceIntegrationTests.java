package com.beez.backend.ontology;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.support.IntegrationTestSupport;
import com.beez.backend.task.TaskApiSupport;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

/** Real-PostgreSQL checks of version pinning, access and immutable relation history. */
@TestPropertySource(properties = "spring.flyway.locations=classpath:db/migration,classpath:db/ontology-draft")
class DesignDependencyServiceIntegrationTests extends IntegrationTestSupport {
    @Autowired DataSource dataSource;
    @Autowired DesignDependencyService service;
    TaskApiSupport api;
    long projectId;
    long baselineId;
    long from;
    long to;
    CallerContext leader;
    CallerContext member;
    CallerContext outsider;

    @BeforeEach
    void setUp() {
        api = new TaskApiSupport(this::client, dataSource);
        api.resetAndLoadFixture();
        projectId = api.projectId();
        baselineId = api.jdbc.sql("select current_baseline_id from project where id = :p")
                .param("p", projectId).query(Long.class).single();
        from = api.designItemId("API-01");
        to = api.designItemId("API-02");
        leader = CallerContext.human(api.accountId("leader@test"));
        member = CallerContext.human(api.accountId("member@test"));
        outsider = CallerContext.human(api.accountId("outsider@test"));
    }

    @Test
    void confirmsAndRevokesWithoutDeletingEvidence() {
        long confirmation = service.confirm(leader, projectId, baselineId, from, to, "API 의존성 확인");
        var found = service.listConfirmed(member, projectId, baselineId);
        assertThat(found).hasSize(1);
        assertThat(found.getFirst().eventId()).isEqualTo(confirmation);
        assertThat(found.getFirst().fromItemId()).isEqualTo(from);
        assertThat(found.getFirst().toItemId()).isEqualTo(to);
        service.revoke(leader, projectId, baselineId, from, to, "의존성 해제");
        assertThat(service.listConfirmed(member, projectId, baselineId)).isEmpty();
        assertThat(api.jdbc.sql("select count(*) from design_dependency_confirmation_event")
                .query(Long.class).single()).isEqualTo(2L);
        assertThatThrownBy(() -> api.jdbc.sql("delete from design_dependency_confirmation_event where id = :id")
                .param("id", confirmation).update()).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void doesNotPromoteLegacyRowsAndRequiresLeader() {
        api.jdbc.sql("""
                insert into design_item_dependency(project_id, from_item_id, to_item_id, origin)
                values (:p, :f, :t, 'EXPLICIT')
                """).param("p", projectId).param("f", from).param("t", to).update();
        assertThat(service.listConfirmed(member, projectId, baselineId)).isEmpty();
        assertThatThrownBy(() -> service.confirm(member, projectId, baselineId, from, to, "확인"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> service.listConfirmed(outsider, projectId, baselineId))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void requiresReconfirmationForNewBaselineAndRejectsStaleReads() {
        service.confirm(leader, projectId, baselineId, from, to, "기준 1 관계 확인");
        api.jdbc.sql("""
                insert into baseline(project_id, version_no, published_by)
                values (:p, 2, :actor)
                """).param("p", projectId).param("actor", leader.accountId()).update();
        long next = api.jdbc.sql("select id from baseline where project_id = :p and version_no = 2")
                .param("p", projectId).query(Long.class).single();
        api.jdbc.sql("""
                insert into baseline_item(baseline_id, project_id, design_item_id, design_item_version_id)
                select :next, project_id, design_item_id, design_item_version_id
                  from baseline_item where baseline_id = :old
                """).param("next", next).param("old", baselineId).update();
        api.jdbc.sql("update project set current_baseline_id = :next where id = :p")
                .param("next", next).param("p", projectId).update();
        assertThatThrownBy(() -> service.listConfirmed(member, projectId, baselineId))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("baseline_changed"));
        assertThat(service.listConfirmed(member, projectId, next)).isEmpty();
        service.confirm(leader, projectId, next, from, to, "기준 2에서 재확인");
        assertThat(service.listConfirmed(member, projectId, next)).hasSize(1);
    }

    @Test
    void rejectsArchiveCommittedWhileWaitingForProjectLock() throws Exception {
        assertConcurrentChangeDenied("update project set archived_at = now() where id = ?", HttpStatus.NOT_FOUND);
    }

    @Test
    void rejectsLeaderRoleRemovalCommittedWhileWaitingForProjectLock() throws Exception {
        assertConcurrentChangeDenied(
                "update project_member set role = 'MEMBER' where project_id = ? and role = 'LEADER'",
                HttpStatus.FORBIDDEN);
    }

    @Test
    void rejectsMembershipDeactivationCommittedWhileWaitingForProjectLock() throws Exception {
        assertConcurrentChangeDenied(
                "update project_member set active = false where project_id = ? and role = 'LEADER'",
                HttpStatus.NOT_FOUND);
    }

    @Test
    void rejectsAccountDeletionCommittedWhileWaitingForProjectLock() throws Exception {
        assertConcurrentChangeDenied("""
                update account set deleted_at = now() where id =
                    (select account_id from project_member where project_id = ? and role = 'LEADER')
                """, HttpStatus.UNAUTHORIZED);
    }

    private void assertConcurrentChangeDenied(String mutation, HttpStatus expected) throws Exception {
        try (Connection blocker = dataSource.getConnection(); var pool = Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            try {
                // Hold the same project lock used by publication, then modify a permission
                // without committing. The first access check can only see the old value.
                try (PreparedStatement lock = blocker.prepareStatement("select id from project where id = ? for update")) {
                    lock.setLong(1, projectId);
                    lock.executeQuery().close();
                }
                try (PreparedStatement change = blocker.prepareStatement(mutation)) {
                    change.setLong(1, projectId);
                    assertThat(change.executeUpdate()).isEqualTo(1);
                }
                Future<Long> attempt = pool.submit(() -> service.confirm(leader, projectId, baselineId, from, to, "확인"));
                waitUntilConfirmationIsBlocked();
                blocker.commit();
                ExecutionException failure = assertThrows(ExecutionException.class,
                        () -> attempt.get(10, TimeUnit.SECONDS));
                assertThat(failure.getCause()).isInstanceOf(ApiException.class);
                assertThat(((ApiException) failure.getCause()).getStatus()).isEqualTo(expected);
                assertThat(api.jdbc.sql("select count(*) from design_dependency_confirmation_event")
                        .query(Long.class).single()).isZero();
            } finally {
                blocker.rollback();
                pool.shutdownNow();
            }
        }
    }

    private void waitUntilConfirmationIsBlocked() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            try (Connection observer = dataSource.getConnection();
                 PreparedStatement statement = observer.prepareStatement("""
                         select exists(select 1 from pg_stat_activity
                          where wait_event_type = 'Lock' and pid <> pg_backend_pid()
                            and query like '%current_baseline_id, archived_at from project%for update%')
                         """);
                 ResultSet result = statement.executeQuery()) {
                result.next();
                if (result.getBoolean(1)) {
                    return;
                }
            }
            Thread.sleep(25);
        }
        throw new AssertionError("confirmation did not reach the project row lock");
    }
}
