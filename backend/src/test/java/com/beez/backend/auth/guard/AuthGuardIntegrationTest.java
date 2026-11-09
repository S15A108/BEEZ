package com.beez.backend.auth.guard;

import static org.assertj.core.api.Assertions.assertThat;

import com.beez.backend.support.AccessFixtures;
import com.beez.backend.support.IntegrationTestSupport;
import com.beez.backend.support.RecordingMailGateway;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 가입 인증·비밀번호 찾기 남용 대책 (Q04 결정, S15P31A108-273): 프록시 뒤 IP 한도와 끝난 인증 행 정리.
 * 시험 프로필의 IP 한도는 넉넉하므로(127.0.0.1 로 부르는 다른 시험 보호) 한도 시험은 그 값만큼 직접 채운 뒤 HTTP 로 확인한다.
 */
class AuthGuardIntegrationTest extends IntegrationTestSupport {

    @Autowired
    ClientIpRateLimiter limiter;

    @Autowired
    AuthGuardProperties props;

    @Autowired
    AuthRowCleanupJob cleanup;

    @Autowired
    JdbcTemplate db;

    @Autowired
    RecordingMailGateway mail;

    /**
     * 실제 클라이언트 주소가 band(내부망이 아닌 대역) 안의 임의 값인 X-Forwarded-For. 앞의 203.0.113.x 는 클라이언트가 지어낸 값이고
     * 맨 오른쪽이 Nginx 가 붙인 실제 주소다. 시험·용도마다 대역을 달리 줘서 같은 컨텍스트 안에서 주소가 겹치지 않게 한다.
     */
    private static String forwardedFor(String band) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        return "203.0.113." + r.nextInt(1, 255) + "," + band + r.nextInt(1, 255);
    }

    private static String clientIpOf(String forwardedFor) {
        return forwardedFor.substring(forwardedFor.indexOf(',') + 1);
    }

    private HttpResponse<String> sendCode(String email, String forwardedFor) {
        return client().post("/api/auth/email-verifications", "{\"email\":\"" + email + "\"}",
                "X-Forwarded-For", forwardedFor);
    }

    private HttpResponse<String> forgot(String email, String forwardedFor) {
        return client().post("/api/auth/password/forgot", "{\"email\":\"" + email + "\",\"name\":\"tester\"}",
                "X-Forwarded-For", forwardedFor);
    }

    /**
     * 깨지면: 가입한 이메일의 409 가 IP 한도보다 먼저 나가 가입 여부를 무제한 조회할 수 있거나(Q04 2), 두 API 가 한도를 따로 세
     * 번갈아 부르면 한도가 두 배가 되거나(Q04 5), X-Forwarded-For 를 읽지 않아 모든 사용자가 Nginx 주소 하나로 묶여 세어진다(Q04 1).
     */
    @Test
    void 가입한_이메일_409도_세고_두_API가_한도를_같이_쓰고_넘으면_429() {
        String registered = fixtures().emailOf(fixtures().account());
        // Nginx 는 클라이언트가 보낸 값 뒤에 실제 주소를 붙인다 — Tomcat 은 맨 오른쪽의 내부망 아닌 주소를 쓴다
        String header = forwardedFor("198.51.100.");
        String clientIp = clientIpOf(header);
        Instant now = Instant.now();

        HttpResponse<String> conflict = sendCode(registered, header);
        assertThat(conflict.statusCode()).as(conflict.body()).isEqualTo(409);
        assertThat(limiter.used(clientIp, now)).as("409 도 센다, 실제 클라이언트 주소로").isEqualTo(1);
        assertThat(forgot(registered, header).statusCode()).isEqualTo(202);
        assertThat(limiter.used(clientIp, Instant.now())).as("ACC-A07 도 같은 카운터").isEqualTo(2);

        while (limiter.tryAcquire(clientIp, Instant.now())) {
            // 시험 프로필 한도(props.ipLimit())까지 채운다
        }
        assertThat(limiter.used(clientIp, Instant.now())).isEqualTo(props.ipLimit());

        HttpResponse<String> limitedCode = sendCode(registered, header);
        assertThat(limitedCode.statusCode()).as("한도가 409 보다 먼저").isEqualTo(429);
        assertThat(limitedCode.body()).contains("\"code\":\"rate_limited\"");
        HttpResponse<String> limitedForgot = forgot(AccessFixtures.uniqueEmail(), header);
        assertThat(limitedForgot.statusCode()).isEqualTo(429);
        assertThat(limitedForgot.body()).contains("\"code\":\"rate_limited\"");

        assertThat(sendCode(AccessFixtures.uniqueEmail(), forwardedFor("192.0.2.")).statusCode())
                .as("다른 IP 는 따로 센다").isEqualTo(201);
    }

    /**
     * 깨지면: SMTP 가 끊긴 동안 다시 시도한 요청이 IP 한도를 써서, 메일이 돌아온 뒤에도 그 IP(같은 공유기 뒤 사용자 모두)가 한 시간 동안
     * 가입 인증을 못 받는다. 명세 ACC-A11 503 "이 요청은 남지 않아 바로 다시 받을 수 있다".
     */
    @Test
    void 메일_발송_실패_503은_IP_한도를_쓰지_않는다() {
        String header = forwardedFor("198.18.0.");
        String clientIp = clientIpOf(header);
        String email = AccessFixtures.uniqueEmail();
        mail.failNextTo(email);

        HttpResponse<String> failed = sendCode(email, header);
        assertThat(failed.statusCode()).as(failed.body()).isEqualTo(503);
        assertThat(limiter.used(clientIp, Instant.now())).isZero();

        assertThat(sendCode(email, header).statusCode()).isEqualTo(201);
        assertThat(limiter.used(clientIp, Instant.now())).as("보낸 요청은 센다").isEqualTo(1);
    }

    private void insertVerification(String email, Instant createdAt, Instant codeExpiresAt, Instant tokenExpiresAt) {
        db.update("""
                INSERT INTO email_verification (public_id, email, code_hash, code_expires_at, closed_at,
                                                token_hash, token_expires_at, created_at, updated_at)
                VALUES (?, ?, 'hash', ?, ?, ?, ?, ?, ?)""",
                UUID.randomUUID().toString(), email, ts(codeExpiresAt), ts(codeExpiresAt),
                tokenExpiresAt == null ? null : UUID.randomUUID().toString(), ts(tokenExpiresAt), ts(createdAt),
                ts(createdAt));
    }

    private void insertUnmatchedRecovery(String email, Instant createdAt) {
        db.update("INSERT INTO temporary_password (email, expires_at, closed_at, created_at, updated_at)"
                + " VALUES (?, ?, ?, ?, ?)", email, ts(createdAt), ts(createdAt), ts(createdAt), ts(createdAt));
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private int count(String table, String email) {
        return db.queryForObject("SELECT count(*) FROM " + table + " WHERE email = ?", Integer.class, email);
    }

    /**
     * 깨지면: 끝난 지 7일이 지난 인증·임시 비밀번호 행(주소·해시)이 무기한 남거나, 7일이 안 된 행이나 아직 쓸 수 있는 가입 토큰이
     * 달린 행까지 지워진다 (Q04 3).
     */
    @Test
    void 끝난_지_7일이_지난_행만_지운다() {
        Instant now = Instant.now();
        Instant cutoff = now.minus(props.rowRetention());
        String oldEmail = AccessFixtures.uniqueEmail();
        String keptEmail = AccessFixtures.uniqueEmail();
        String tokenEmail = AccessFixtures.uniqueEmail();

        insertVerification(oldEmail, cutoff.minusSeconds(301), cutoff.minusSeconds(1), null);
        insertUnmatchedRecovery(oldEmail, cutoff.minusSeconds(1));
        insertVerification(keptEmail, cutoff.minusSeconds(299), cutoff.plusSeconds(1), null);
        insertUnmatchedRecovery(keptEmail, cutoff.plusSeconds(1));
        // 코드는 cutoff 전에 끝났지만 확인 뒤 받은 가입 토큰이 cutoff 뒤에 끝난다
        insertVerification(tokenEmail, cutoff.minus(Duration.ofMinutes(10)), cutoff.minus(Duration.ofMinutes(5)),
                cutoff.plusSeconds(1));

        cleanup.purge(now);

        assertThat(count("email_verification", oldEmail)).isZero();
        assertThat(count("temporary_password", oldEmail)).isZero();
        assertThat(count("email_verification", keptEmail)).isEqualTo(1);
        assertThat(count("temporary_password", keptEmail)).isEqualTo(1);
        assertThat(count("email_verification", tokenEmail)).as("토큰 만료 기준").isEqualTo(1);
    }
}
