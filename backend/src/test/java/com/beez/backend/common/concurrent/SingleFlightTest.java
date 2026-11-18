package com.beez.backend.common.concurrent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.beez.backend.common.api.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class SingleFlightTest {

    /** 리더가 일하는 동안 같은 키로 들어온 요청을 모아 두기 위해, 리더 안에서 나머지가 대기를 시작할 때까지 기다린다. */
    private static void awaitFollowers(CountDownLatch started) throws InterruptedException {
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(300);
    }

    @Test
    void onlyTheLeaderWorksAndFollowersShareTheResult() throws Exception {
        SingleFlight<String> flight = new SingleFlight<>();
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch leaderStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            Future<String> leader = pool.submit(() -> flight.run("k", "f", () -> {
                calls.incrementAndGet();
                leaderStarted.countDown();
                try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return "result";
            }));
            awaitFollowers(leaderStarted);
            List<Future<String>> followers = new ArrayList<>();
            for (int i = 0; i < 3; i++) followers.add(pool.submit(() -> flight.run("k", "f", () -> { calls.incrementAndGet(); return "other"; })));
            Thread.sleep(300);
            release.countDown();
            assertThat(leader.get(5, TimeUnit.SECONDS)).isEqualTo("result");
            for (Future<String> follower : followers) assertThat(follower.get(5, TimeUnit.SECONDS)).isEqualTo("result");
            assertThat(calls.get()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void followersShareTheLeadersFailure() throws Exception {
        SingleFlight<String> flight = new SingleFlight<>();
        CountDownLatch leaderStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> leader = pool.submit(() -> flight.run("k", "f", () -> {
                leaderStarted.countDown();
                try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                throw new ApiException(HttpStatus.BAD_GATEWAY, "ai_unavailable", "실패");
            }));
            awaitFollowers(leaderStarted);
            Future<String> follower = pool.submit(() -> flight.run("k", "f", () -> "never"));
            Thread.sleep(300);
            release.countDown();
            assertThatThrownBy(() -> leader.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(ApiException.class);
            assertThatThrownBy(() -> follower.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(ApiException.class)
                    .cause().hasFieldOrPropertyWithValue("code", "ai_unavailable");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void sameKeyWithDifferentBodyIsAnIdempotencyConflict() throws Exception {
        SingleFlight<String> flight = new SingleFlight<>();
        CountDownLatch leaderStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> leader = pool.submit(() -> flight.run("k", "body-1", () -> {
                leaderStarted.countDown();
                try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return "result";
            }));
            awaitFollowers(leaderStarted);
            assertThatThrownBy(() -> flight.run("k", "body-2", () -> "never"))
                    .isInstanceOf(ApiException.class).hasFieldOrPropertyWithValue("code", "idempotency_conflict");
            release.countDown();
            assertThat(leader.get(5, TimeUnit.SECONDS)).isEqualTo("result");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aFailedFlightIsForgottenSoARetryRunsAgain() {
        SingleFlight<String> flight = new SingleFlight<>();
        assertThatThrownBy(() -> flight.run("k", "f", () -> { throw new IllegalStateException("boom"); }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(flight.run("k", "f", () -> "second")).isEqualTo("second");
        assertThat(flight.run("k", "f", () -> "third")).isEqualTo("third");
    }

    @Test
    void differentKeysRunIndependently() {
        SingleFlight<String> flight = new SingleFlight<>();
        assertThat(flight.run("a", "f", () -> "A")).isEqualTo("A");
        assertThat(flight.run("b", "f", () -> "B")).isEqualTo("B");
    }
}
