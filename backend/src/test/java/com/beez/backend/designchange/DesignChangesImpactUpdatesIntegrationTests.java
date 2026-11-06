package com.beez.backend.designchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.designchange.dto.DesignChangesQuery;
import com.beez.backend.designchange.dto.DesignChangesResponse;
import com.beez.backend.designchange.dto.PublishedImpactCandidateResponse;
import com.beez.backend.designchange.dto.ImpactUpdateResponse;
import com.beez.backend.designchange.service.DesignChangesService;
import com.beez.backend.impactanalysis.intake.PublishedAnalysisRepository;
import com.beez.backend.impactanalysis.intake.PublishedAnalysisRepository.Claim;
import com.beez.backend.support.IntegrationTestSupport;
import com.beez.backend.task.TaskApiSupport;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * S15P31A108-221 get_design_changes 의 impact_updates — 발행 후 영향 분석 결과를 결과 순번(result_cursor)으로 이어 받는다.
 * 분석 실행(오서현 -227)은 돌리지 않고 published_change_analysis 행을 SQL 로 바꿔 결과가 정해진 상태를 만든다.
 * 결과 순번은 V36 트리거가 매기므로 SQL 로 바꿔도 실제 실행과 같은 번호가 붙는다.
 */
class DesignChangesImpactUpdatesIntegrationTests extends IntegrationTestSupport {

    @Autowired
    DataSource dataSource;

    @Autowired
    DesignChangesService service;

    @Autowired
    PublishedAnalysisRepository analyses;

    @Autowired
    PlatformTransactionManager transactions;

    TaskApiSupport api;
    Long projectId;
    Long leader;
    CallerContext asMember;
    int nextVersion = 2;

    @BeforeEach
    void setUp() {
        api = new TaskApiSupport(this::client, dataSource);
        api.resetAndLoadFixture();
        projectId = api.projectId();
        leader = api.accountId("leader@test");
        asMember = CallerContext.human(api.accountId("member@test"));
    }

    // ---- 도우미 ----

    /** 발행 하나와 그 발행의 분석 대기 행(PENDING)을 만든다 → 분석 행 id. 발행 변경 목록은 이 시험의 관심이 아니다 */
    private long pendingAnalysis() {
        long baseline = api.jdbc.sql("insert into baseline (project_id, version_no, published_by) values (:p, :v, :a) returning id")
                .param("p", projectId).param("v", nextVersion).param("a", leader).query(Long.class).single();
        nextVersion++;
        return api.jdbc.sql("""
                        insert into published_change_analysis (project_id, published_baseline_id, status)
                        values (:p, :b, 'PENDING') returning id
                        """).param("p", projectId).param("b", baseline).query(Long.class).single();
    }

    /** 결과 저장(recordResult)과 같은 칸을 채워 COMPLETED 로 */
    private void complete(long analysis, String explicitJson, String aiJson) {
        api.jdbc.sql("""
                        update published_change_analysis
                           set status = 'COMPLETED', evaluation_status = 'COMPLETED',
                               explicit_result = cast(:explicit as jsonb), ai_result = cast(:ai as jsonb), finished_at = now()
                         where id = :id
                        """).param("id", analysis).param("explicit", explicitJson).param("ai", aiJson, java.sql.Types.VARCHAR).update();
    }

    private void failFinally(long analysis) {
        api.jdbc.sql("update published_change_analysis set status = 'FAILED', error_code = 'model_unavailable', finished_at = now() where id = :id")
                .param("id", analysis).update();
    }

    private Long resultSeq(long analysis) {
        return api.jdbc.sql("select result_seq from published_change_analysis where id = :id").param("id", analysis)
                .query(Long.class).optional().orElse(null);
    }

    private static String rule(String task, String item) {
        return "{\"task_id\":\"" + task + "\",\"design_item_id\":\"" + item + "\",\"member_id\":\"9\"}";
    }

    private static String ai(String task, String reason) {
        return "{\"task_id\":\"" + task + "\",\"reason\":\"" + reason + "\",\"sources\":[]}";
    }

    private static String array(List<String> elements) {
        return "[" + String.join(",", elements) + "]";
    }

    private DesignChangesResponse call(String taskId, String resultCursor, Integer limit) {
        return service.getChanges(asMember, projectId, new DesignChangesQuery(0, taskId, null, limit, resultCursor));
    }

    private static void assertBadRequest(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(e.getCode()).isEqualTo("validation_failed");
        });
    }

    // ------------------------------------------------------------------

    @Test
    void firstCallStartsFromNow_thenResultsArriveOnceInOrder() {
        long before = pendingAnalysis();
        complete(before, array(List.of(rule("501", "301"))), null);

        // 처음(result_cursor 없음): 이미 정해진 결과는 주지 않고 시작점만 준다
        DesignChangesResponse first = call(null, null, null);
        assertThat(first.impactUpdates()).isEmpty();
        assertThat(first.resultHasMore()).isFalse();
        assertThat(first.nextResultCursor()).isNotBlank();

        long later = pendingAnalysis();
        long running = pendingAnalysis();
        api.jdbc.sql("update published_change_analysis set status = 'RUNNING', attempt = 1, lease_until = now() + interval '1 minute' where id = :id")
                .param("id", running).update();
        complete(later, array(List.of(rule("501", "301"))),
                "{\"candidates\":" + array(List.of(ai("502", "응답 필드를 같이 씀"))) + ",\"evidence_status\":\"SUFFICIENT\"}");

        DesignChangesResponse second = call(null, first.nextResultCursor(), null);
        assertThat(second.impactUpdates()).hasSize(1);   // 진행 중(RUNNING) 결과는 아직 오지 않는다
        ImpactUpdateResponse update = second.impactUpdates().get(0);
        assertThat(update.resultSeq()).isEqualTo(resultSeq(later)).isGreaterThan(resultSeq(before));
        assertThat(update.analysisStatus()).isEqualTo("COMPLETED");
        assertThat(update.evaluationStatus()).isEqualTo("COMPLETED");
        assertThat(update.analyzedAt()).isNotNull();
        assertThat(update.candidates()).extracting(PublishedImpactCandidateResponse::origin, PublishedImpactCandidateResponse::taskId,
                        PublishedImpactCandidateResponse::designItemId, PublishedImpactCandidateResponse::reason)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("RULE", "501", "301", null),
                        org.assertj.core.groups.Tuple.tuple("AI", "502", null, "응답 필드를 같이 씀"));
        assertThat(update.candidatesTruncated()).isZero();

        // 받은 다음 cursor 로 다시 부르면 같은 결과를 또 주지 않는다
        DesignChangesResponse third = call(null, second.nextResultCursor(), null);
        assertThat(third.impactUpdates()).isEmpty();
        assertThat(third.nextResultCursor()).isEqualTo(second.nextResultCursor());
    }

    @Test
    void candidatesAreCappedAtTen_ruleFirst_duplicatesOnce_andTruncatedCounted() {
        String start = call(null, null, null).nextResultCursor();
        long analysis = pendingAnalysis();
        List<String> rules = new ArrayList<>();
        for (int task = 11; task >= 1; task--) {          // 일부러 거꾸로 넣는다 — 응답은 작업 번호 순
            rules.add(rule(String.valueOf(task), "301"));
        }
        rules.add(rule("1", "301"));                       // 같은 후보가 두 번
        complete(analysis, array(rules), "{\"candidates\":" + array(List.of(ai("1", "AI 도 같은 작업을 짚음"))) + "}");

        ImpactUpdateResponse update = call(null, start, null).impactUpdates().get(0);
        assertThat(update.candidates()).hasSize(10);
        assertThat(update.candidates()).extracting(PublishedImpactCandidateResponse::origin).containsOnly("RULE");
        assertThat(update.candidates()).extracting(PublishedImpactCandidateResponse::taskId)
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10");
        // 규칙 11개(중복 1개 제외) + AI 1개 = 12개 중 10개만 → 2개 빠짐. 출처가 다르면 같은 작업이어도 따로 센다
        assertThat(update.candidatesTruncated()).isEqualTo(2);
    }

    @Test
    void taskScope_showsOnlyResultsNamingThatTask_whileProjectScopeAlsoShowsFailures() {
        String projectStart = call(null, null, null).nextResultCursor();
        String taskStart = call("100", null, null).nextResultCursor();

        long named = pendingAnalysis();
        complete(named, array(List.of(rule("100", "301"), rule("200", "302"))),
                "{\"candidates\":" + array(List.of(ai("200", "다른 작업"))) + "}");
        long failed = pendingAnalysis();
        failFinally(failed);
        long aiOnly = pendingAnalysis();
        complete(aiOnly, "[]", "{\"candidates\":" + array(List.of(ai("100", "AI 만 짚음"))) + "}");

        // 작업 100 범위: 100 이 후보에 든 결과만, 후보도 100 것만
        DesignChangesResponse scoped = call("100", taskStart, null);
        assertThat(scoped.impactUpdates()).extracting(ImpactUpdateResponse::resultSeq)
                .containsExactly(resultSeq(named), resultSeq(aiOnly));
        assertThat(scoped.impactUpdates().get(0).candidates()).extracting(PublishedImpactCandidateResponse::taskId).containsExactly("100");
        assertThat(scoped.impactUpdates().get(1).candidates()).extracting(PublishedImpactCandidateResponse::origin).containsExactly("AI");

        // 아무 후보에도 없는 작업은 받을 것이 없다
        assertThat(call("300", call("300", null, null).nextResultCursor(), null).impactUpdates()).isEmpty();

        // 프로젝트 범위: 실패도 결과로 온다 — 영향 0건과 구분하라고
        DesignChangesResponse all = call(null, projectStart, null);
        assertThat(all.impactUpdates()).extracting(ImpactUpdateResponse::analysisStatus)
                .containsExactly("COMPLETED", "FAILED", "COMPLETED");
        ImpactUpdateResponse failure = all.impactUpdates().get(1);
        assertThat(failure.errorCode()).isEqualTo("model_unavailable");
        assertThat(failure.candidates()).isEmpty();
        assertThat(failure.evaluationStatus()).isNull();
    }

    @Test
    void paging_continuesWithNextResultCursor_untilNoMore() {
        String start = call(null, null, null).nextResultCursor();
        List<Long> seqs = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            long analysis = pendingAnalysis();
            complete(analysis, "[]", null);
            seqs.add(resultSeq(analysis));
        }

        DesignChangesResponse page1 = call(null, start, 2);
        assertThat(page1.impactUpdates()).extracting(ImpactUpdateResponse::resultSeq).containsExactly(seqs.get(0), seqs.get(1));
        assertThat(page1.resultHasMore()).isTrue();
        DesignChangesResponse page2 = call(null, page1.nextResultCursor(), 2);
        assertThat(page2.impactUpdates()).extracting(ImpactUpdateResponse::resultSeq).containsExactly(seqs.get(2));
        assertThat(page2.resultHasMore()).isFalse();
    }

    @Test
    void resultSeq_isGivenOnlyWhenTheResultIsDecided_andRetryWaitDoesNotCount() {
        long analysis = pendingAnalysis();
        assertThat(resultSeq(analysis)).isNull();
        // 재시도 대기(실패 뒤 PENDING) 는 결과가 아니다
        api.jdbc.sql("update published_change_analysis set status = 'PENDING', attempt = 1, error_code = 'model_timeout', "
                + "next_attempt_at = now() where id = :id").param("id", analysis).update();
        assertThat(resultSeq(analysis)).isNull();
        complete(analysis, "[]", null);
        Long decided = resultSeq(analysis);
        assertThat(decided).isNotNull();

        // 제안 없이 발행돼 바로 미평가로 들어오는 행도 결과다
        long baseline = api.jdbc.sql("insert into baseline (project_id, version_no, published_by) values (:p, 99, :a) returning id")
                .param("p", projectId).param("a", leader).query(Long.class).single();
        long notEvaluated = api.jdbc.sql("""
                        insert into published_change_analysis (project_id, published_baseline_id, status, finished_at)
                        values (:p, :b, 'NOT_EVALUATED', now()) returning id
                        """).param("p", projectId).param("b", baseline).query(Long.class).single();
        assertThat(resultSeq(notEvaluated)).isEqualTo(decided + 1);
    }

    /**
     * 깨지면: 프로젝트의 첫 결과를 실패로 확정하는 트랜잭션과, 프로젝트 행을 쥔 채 같은 분석 행을 바꾸려는 선점(claim)이 서로 기다려
     * DB 교착(40P01)으로 한쪽이 죽는다 (PR #167 오서현 리뷰 재현 순서). 실패 확정이 분석 행보다 프로젝트 행을 먼저 잠가야
     * 선점이 먼저 끝나고 실패 확정이 그 뒤에 이어진다.
     */
    @Test
    void failingTheFirstResultWhileAClaimHoldsTheProject_waitsInsteadOfDeadlocking() throws Exception {
        long analysis = pendingAnalysis();
        Instant lease = api.jdbc.sql("""
                        update published_change_analysis
                           set status = 'RUNNING', attempt = 1, started_at = now(), lease_until = clock_timestamp() + interval '5 minutes'
                         where id = :id returning lease_until
                        """).param("id", analysis).query(java.sql.Timestamp.class).single().toInstant();
        // 이 프로젝트에는 아직 결과 순번 카운터가 없다 — 첫 결과가 카운터 행을 만들며 외래 키로 프로젝트 행을 본다
        assertThat(api.jdbc.sql("select count(*) from published_analysis_result_counter where project_id = :p")
                .param("p", projectId).query(Integer.class).single()).isZero();

        TransactionTemplate tx = new TransactionTemplate(transactions);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection claimer = dataSource.getConnection()) {
            claimer.setAutoCommit(false);
            try (Statement st = claimer.createStatement()) {
                // 기다림이 길어지면 교착 대신 시간 초과로 실패하게 (교착 검출은 1초라 그보다 길게)
                st.execute("set local lock_timeout = '5s'");
                st.execute("select id from project where id = " + projectId + " for update");
            }
            // 실패 확정: 결과 저장소의 실제 경로 (Processor 의 실패 처리가 부르는 것)
            Future<Boolean> failure = pool.submit(() -> tx.execute(status ->
                    analyses.recordFailure(new Claim(analysis, 1, lease), "model_unavailable", false, List.of(), "UNKNOWN")));
            awaitLockWaiter();
            // 선점이 그 분석 행을 바꾼다 — 실패 확정이 이 행을 먼저 잡았다면 여기서 서로 기다리게 된다
            try (PreparedStatement ps = claimer.prepareStatement(
                    "update published_change_analysis set next_attempt_at = next_attempt_at where id = ?")) {
                ps.setLong(1, analysis);
                ps.executeUpdate();
            }
            claimer.commit();

            assertThat(failure.get(15, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
        assertThat(api.jdbc.sql("select status from published_change_analysis where id = :id").param("id", analysis)
                .query(String.class).single()).isEqualTo("FAILED");
        assertThat(resultSeq(analysis)).isEqualTo(1L);
    }

    /** 다른 트랜잭션이 행 잠금을 기다리기 시작할 때까지 (pg_stat_activity 의 대기 종류가 Lock) */
    private void awaitLockWaiter() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (api.jdbc.sql("select count(*) from pg_stat_activity where datname = current_database() and wait_event_type = 'Lock'")
                .query(Integer.class).single() == 0) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("실패 확정이 프로젝트 잠금을 기다리지 않았다");
            }
            Thread.sleep(20);
        }
    }

    @Test
    void malformedCursor_orCursorFromAnotherTaskScope_is400() {
        assertBadRequest(() -> call(null, "not-a-cursor", null));
        String taskCursor = call("100", null, null).nextResultCursor();
        assertBadRequest(() -> call(null, taskCursor, null));
        assertBadRequest(() -> call("200", taskCursor, null));
        // 음수 순번을 손으로 만든 cursor 도 400 (Base64 로 "u1|-1|")
        String negative = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("u1|-1|".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertBadRequest(() -> call(null, negative, null));
    }
}
