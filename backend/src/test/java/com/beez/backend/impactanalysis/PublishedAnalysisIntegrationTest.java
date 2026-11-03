package com.beez.backend.impactanalysis;

import static org.assertj.core.api.Assertions.*;
import com.beez.backend.changeproposal.port.PostPublishAnalysisIntake;
import com.beez.backend.impactanalysis.intake.PublishedAnalysisRepository;
import com.beez.backend.support.IntegrationTestSupport;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class PublishedAnalysisIntegrationTest extends IntegrationTestSupport {
    @Autowired JdbcTemplate jdbc;
    @Autowired PublishedAnalysisRepository repository;
    @Autowired PostPublishAnalysisIntake intake;
    @Autowired PlatformTransactionManager transactions;
    long project,account;
    TransactionTemplate tx;
    @BeforeEach void setup() {
        var fixture=fixtures().projectWithLeader(); project=fixture.projectId(); account=fixture.leaderAccountId();
        tx=new TransactionTemplate(transactions);
    }
    long baseline(int version) {
        return jdbc.queryForObject("INSERT INTO baseline(project_id,version_no,published_by) VALUES(?,?,?) RETURNING id",Long.class,project,version,account);
    }
    long pending(long baseline) {
        // No proposal FK is needed for isolated consumer data-operation fixtures.
        return jdbc.queryForObject("INSERT INTO published_change_analysis(project_id,published_baseline_id,status) VALUES(?,?,'PENDING') RETURNING id",Long.class,project,baseline);
    }
    @Test void publicationAdapterIsDurableIdempotentAndRollsBackWithPublication() {
        long b=baseline(1);
        tx.executeWithoutResult(s -> { intake.accept(project,b,null); intake.accept(project,b,null); });
        assertThat(jdbc.queryForObject("SELECT status FROM published_change_analysis WHERE published_baseline_id=?",String.class,b)).isEqualTo("NOT_EVALUATED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM published_change_analysis WHERE published_baseline_id=?",Integer.class,b)).isEqualTo(1);
        long second=baseline(2);
        tx.executeWithoutResult(s -> { intake.accept(project,second,null); s.setRollbackOnly(); });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM published_change_analysis WHERE published_baseline_id=?",Integer.class,second)).isZero();
    }
    @Test void projectOrderingFencingAndRetryBudgetAreEnforced() {
        long first=pending(baseline(1)),second=pending(baseline(2)); Instant now=Instant.now();
        assertThat(repository.claim(second,now,60)).isEmpty();
        var claim=repository.claim(first,now,60).orElseThrow();
        assertThat(repository.claim(first,now,60)).isEmpty();
        var reclaimed=repository.claim(first,now.plusSeconds(61),60).orElseThrow();
        assertThat(reclaimed.attempt()).isEqualTo(2);
        assertThat(repository.complete(claim,now.plusSeconds(62))).isFalse();
        assertThat(repository.fail(reclaimed,now.plusSeconds(62),"model_timeout",true)).isTrue();
        assertThat(repository.claim(first,now.plusSeconds(66),60)).isEmpty(); // attempt 2 waits five seconds
        var third=repository.claim(first,now.plusSeconds(67),60).orElseThrow();
        repository.fail(third,now.plusSeconds(68),"model_timeout",true);
        assertThat(repository.claim(first,now.plusSeconds(97),60)).isEmpty();
        var fourth=repository.claim(first,now.plusSeconds(98),60).orElseThrow();
        repository.fail(fourth,now.plusSeconds(99),"model_timeout",true);
        assertThat(jdbc.queryForObject("SELECT status FROM published_change_analysis WHERE id=?",String.class,first)).isEqualTo("FAILED");
        assertThat(repository.claim(first,now.plusSeconds(100),60)).isEmpty();
        assertThat(repository.claim(second,now.plusSeconds(100),60)).isEmpty();
    }
    @Test void concurrentClaimsHaveExactlyOneOwnerAndOneSecondRetryWorks() throws Exception {
        long id=pending(baseline(1)); Instant now=Instant.now();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);
            Callable<Optional<PublishedAnalysisRepository.Claim>> work=() -> { start.await(); return repository.claim(id,now,60); };
            var a=pool.submit(work); var b=pool.submit(work); start.countDown();
            var winners=List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS)).stream().flatMap(Optional::stream).toList();
            assertThat(winners).hasSize(1);
            repository.fail(winners.getFirst(),now.plusSeconds(1),"model_timeout",true);
            assertThat(repository.claim(id,now.plusMillis(1999),60)).isEmpty();
            assertThat(repository.claim(id,now.plusSeconds(2),60).orElseThrow().attempt()).isEqualTo(2);
        }
    }
    @Test void recoveryUsesKeysetIncludesExpiredLeasesAndIgnoresFutureRetries() {
        long a=pending(baseline(1)),b=pending(baseline(2)),c=pending(baseline(3));
        Instant now=Instant.now().plusSeconds(60);
        jdbc.update("UPDATE published_change_analysis SET status='RUNNING',attempt=1,lease_until=? WHERE id=?",Timestamp.from(now.minusSeconds(1)),b);
        jdbc.update("UPDATE published_change_analysis SET next_attempt_at=? WHERE id=?",Timestamp.from(now.plusSeconds(1)),c);
        // Other test projects may have work; traverse all bounded pages, retaining only this project's IDs.
        Set<String> found=new HashSet<>(); String cursor=null;
        do {
            var page=repository.findCandidates(now,1,cursor);
            page.candidates().forEach(value -> found.add(value.intakeReference())); cursor=page.nextCursor();
        } while(cursor!=null);
        assertThat(found).contains(""+a,""+b).doesNotContain(""+c);
        repository.ensureSafeToAcknowledge(""+a);
        assertThatThrownBy(() -> repository.ensureSafeToAcknowledge("9223372036854775807"))
                .isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class).hasMessageContaining("Analysis intake missing");
    }

    @Test void missingBaselinesAndUnexplainedNotEvaluatedBlockLaterVersions() {
        long firstBaseline=baseline(1), thirdBaseline=baseline(3);
        tx.executeWithoutResult(s -> intake.accept(project,firstBaseline,null));
        long third=pending(thirdBaseline);
        Instant now=Instant.now();
        assertThat(repository.claim(third,now,60)).isEmpty(); // version 2 is absent
        long secondBaseline=baseline(2);
        long second=jdbc.queryForObject("INSERT INTO published_change_analysis(project_id,published_baseline_id,status) VALUES(?,?,'NOT_EVALUATED') RETURNING id",
                Long.class,project,secondBaseline);
        assertThat(repository.claim(third,now,60)).isEmpty(); // not a known initial/backfill skip
        jdbc.update("UPDATE published_change_analysis SET error_code='published_before_intake' WHERE id=?",second);
        assertThat(repository.claim(third,now,60)).isPresent();
    }

    @Test void dueRetryIsRecoverableWithoutWaitingThirtySecondsFromOriginalRequest() {
        long id=pending(baseline(1));
        Instant now=Instant.now();
        jdbc.update("UPDATE published_change_analysis SET next_attempt_at=? WHERE id=?",Timestamp.from(now.minusSeconds(1)),id);
        Set<String> found=new HashSet<>(); String cursor=null;
        do {
            var page=repository.findCandidates(now,10,cursor);
            page.candidates().forEach(value -> found.add(value.intakeReference())); cursor=page.nextCursor();
        } while(cursor!=null);
        assertThat(found).contains(""+id);
    }
}
