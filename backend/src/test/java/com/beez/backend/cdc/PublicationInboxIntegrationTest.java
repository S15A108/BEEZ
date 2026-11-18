package com.beez.backend.cdc;

import static org.assertj.core.api.Assertions.*;
import com.beez.backend.support.IntegrationTestSupport;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class PublicationInboxIntegrationTest extends IntegrationTestSupport {
    @Autowired JdbcTemplate jdbc;
    @Autowired PublicationInboxService inbox;
    @Autowired PublicationEventParser parser;

    @Test void initialAndChangeArePendingAndDuplicateIsIdempotent() throws Exception {
        var p = fixtures().projectWithLeader();
        long baseline = jdbc.queryForObject("INSERT INTO baseline(project_id,version_no,published_by) VALUES(?,1,?) RETURNING id", Long.class, p.projectId(), p.leaderAccountId());
        UUID initialId = UUID.randomUUID();
        var initial = parser.parse(PublicationEventParserTest.envelope(initialId, "INITIAL", p.projectId(), baseline, false)).orElseThrow();
        inbox.accept(initial);
        assertThat(jdbc.queryForObject("SELECT status FROM cdc_publication_inbox WHERE event_id=?", String.class, initialId)).isEqualTo("PENDING");

        UUID changeId = UUID.randomUUID();
        var event = parser.parse(PublicationEventParserTest.envelope(changeId, "CHANGE", p.projectId(), baseline, true)).orElseThrow();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var a = pool.submit(() -> { start.await(); inbox.accept(event); return null; });
            var b = pool.submit(() -> { start.await(); inbox.accept(event); return null; });
            start.countDown(); a.get(20, TimeUnit.SECONDS); b.get(20, TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cdc_publication_inbox WHERE event_id=?", Integer.class, changeId)).isEqualTo(1);
        // PENDING is a receipt only: proposal and old-version references are not authoritative yet.
        assertThat(jdbc.queryForObject("SELECT status FROM cdc_publication_inbox WHERE event_id=?", String.class, changeId)).isEqualTo("PENDING");
        var altered = parser.parse(PublicationEventParserTest.envelope(changeId, "INITIAL", p.projectId(), baseline, false)).orElseThrow();
        assertThatThrownBy(() -> inbox.accept(altered)).isInstanceOf(CdcEventException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM cdc_publication_inbox WHERE event_id=?", String.class, changeId)).isEqualTo("PENDING");
    }

    @Test void nonInitialBaselineCannotBeSilentlySkippedAsInitial() {
        var p = fixtures().projectWithLeader();
        long baseline = jdbc.queryForObject("INSERT INTO baseline(project_id,version_no,published_by) VALUES(?,2,?) RETURNING id", Long.class, p.projectId(), p.leaderAccountId());
        UUID id = UUID.randomUUID();
        var event = parser.parse(PublicationEventParserTest.envelope(id, "INITIAL", p.projectId(), baseline, false)).orElseThrow();
        assertThatThrownBy(() -> inbox.accept(event)).isInstanceOf(CdcEventException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cdc_publication_inbox WHERE event_id=?", Integer.class, id)).isZero();
    }

    @Test void foreignBaselineIsNotAccepted() {
        var p = fixtures().projectWithLeader();
        var other = fixtures().projectWithLeader();
        long baseline = jdbc.queryForObject("INSERT INTO baseline(project_id,version_no,published_by) VALUES(?,1,?) RETURNING id", Long.class, other.projectId(), other.leaderAccountId());
        UUID id = UUID.randomUUID();
        var event = parser.parse(PublicationEventParserTest.envelope(id, "CHANGE", p.projectId(), baseline, false)).orElseThrow();
        assertThatThrownBy(() -> inbox.accept(event)).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cdc_publication_inbox WHERE event_id=?", Integer.class, id)).isZero();
    }
}
