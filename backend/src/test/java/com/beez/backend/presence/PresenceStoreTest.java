package com.beez.backend.presence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/** 접속 상태 저장소의 TTL·보존·정리·동시성 (S15P31A108-386). DB 없이 돈다. */
class PresenceStoreTest {

    private static final PresenceProperties PROPS =
            new PresenceProperties(Duration.ofSeconds(45), 10, Duration.ofSeconds(30), Duration.ofHours(24));

    /** 시험이 직접 돌리는 시계. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-11T03:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** 깨지면: TTL 이 지나도 계속 online 이거나, 끊겼다고 마지막 위치·접속 시각까지 지워 HUD 가 '오프라인 · 1시간 전' 을 못 보인다. */
    @Test
    void ttl이_지나면_offline이_되고_마지막_위치와_시각은_남는다() {
        MutableClock clock = new MutableClock();
        PresenceStore store = new PresenceStore(PROPS, clock);
        store.report(1, 10, "dev", "A-3", "tasks");

        PresenceEntry entry = store.find(1, 10).orElseThrow();
        assertThat(store.isOnline(entry)).isTrue();

        clock.advance(Duration.ofSeconds(44));
        assertThat(store.isOnline(store.find(1, 10).orElseThrow())).isTrue();

        clock.advance(Duration.ofSeconds(1));   // 정확히 TTL — 안이 아니다
        PresenceEntry stale = store.find(1, 10).orElseThrow();
        assertThat(store.isOnline(stale)).isFalse();
        assertThat(stale.zone()).isEqualTo("dev");
        assertThat(stale.seat()).isEqualTo("A-3");
        assertThat(stale.lastSeenAt()).isEqualTo(Instant.parse("2026-11-11T03:00:00Z"));
    }

    /** 깨지면: 한 번 더 보고해도 시각이 갱신되지 않아 계속 보고하는 사람이 TTL 뒤 offline 이 된다. */
    @Test
    void 다시_보고하면_시각과_위치가_마지막_값으로_바뀐다() {
        MutableClock clock = new MutableClock();
        PresenceStore store = new PresenceStore(PROPS, clock);
        store.report(1, 10, "dev", "A-3", "tasks");
        clock.advance(Duration.ofSeconds(40));
        store.report(1, 10, "lobby", null, null);
        clock.advance(Duration.ofSeconds(40));

        PresenceEntry entry = store.find(1, 10).orElseThrow();
        assertThat(store.isOnline(entry)).isTrue();
        assertThat(entry.zone()).isEqualTo("lobby");
        assertThat(entry.seat()).isNull();
    }

    /** 깨지면: 다른 프로젝트의 같은 멤버 번호·같은 계정 위치가 섞인다. */
    @Test
    void 프로젝트가_다르면_같은_멤버_번호도_섞이지_않는다() {
        PresenceStore store = new PresenceStore(PROPS, new MutableClock());
        store.report(1, 10, "dev", null, null);

        assertThat(store.find(2, 10)).isEmpty();
        store.report(2, 10, "hr", null, null);
        assertThat(store.find(1, 10).orElseThrow().zone()).isEqualTo("dev");
    }

    @Test
    void 멤버_정리는_그_멤버만_지우고_프로젝트_정리는_그_프로젝트만_지운다() {
        PresenceStore store = new PresenceStore(PROPS, new MutableClock());
        store.report(1, 10, "dev", null, null);
        store.report(1, 11, "dev", null, null);
        store.report(2, 10, "dev", null, null);

        store.removeMemberAfterCommit(1, 10);   // 트랜잭션 밖이라 바로 실행된다
        assertThat(store.find(1, 10)).isEmpty();
        assertThat(store.find(1, 11)).isPresent();

        store.removeProjectAfterCommit(1);
        assertThat(store.find(1, 11)).isEmpty();
        assertThat(store.find(2, 10)).isPresent();
    }

    /** 깨지면: 접속하지 않는 사람의 항목이 메모리에 영원히 쌓이거나, 보존 기간 안의 항목이 지워진다. */
    @Test
    void 보존_기간이_지난_항목만_purge가_지운다() {
        MutableClock clock = new MutableClock();
        PresenceStore store = new PresenceStore(PROPS, clock);
        store.report(1, 10, "dev", null, null);
        clock.advance(Duration.ofHours(23));
        store.report(1, 11, "dev", null, null);
        clock.advance(Duration.ofHours(2));   // 10 은 25시간, 11 은 2시간

        store.purge();

        assertThat(store.find(1, 10)).isEmpty();
        assertThat(store.find(1, 11)).isPresent();
    }

    /** 깨지면: 위치 정보(zone·seat·panel)가 로그 문자열로 새 나간다. */
    @Test
    void 항목의_문자열에는_위치가_들어_있지_않다() {
        PresenceEntry entry = new PresenceEntry(Instant.parse("2026-11-11T03:00:00Z"), "secret-zone", "S-9", "panel");

        assertThat(entry.toString()).doesNotContain("secret-zone").doesNotContain("S-9").doesNotContain("panel");
    }

    /** 깨지면: 동시 보고에서 예외가 나거나 항목이 사라지거나 서로 다른 멤버가 섞인다. */
    @Test
    void 동시에_보고해도_멤버마다_하나씩_남는다() throws Exception {
        PresenceStore store = new PresenceStore(PROPS, Clock.systemUTC());
        int members = 20;
        int rounds = 200;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int m = 0; m < members; m++) {
                long memberId = m;
                for (int t = 0; t < 4; t++) {   // 같은 멤버가 탭 4개로 동시에 보고
                    futures.add(pool.submit(() -> {
                        for (int i = 0; i < rounds; i++) {
                            store.report(1, memberId, "dev", "S-" + memberId, null);
                        }
                    }));
                }
            }
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(store.size()).isEqualTo(members);
        for (long m = 0; m < members; m++) {
            assertThat(store.find(1, m).orElseThrow().seat()).isEqualTo("S-" + m);
        }
    }
}
