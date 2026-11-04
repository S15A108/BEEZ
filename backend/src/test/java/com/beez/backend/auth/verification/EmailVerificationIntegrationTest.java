package com.beez.backend.auth.verification;

import static org.assertj.core.api.Assertions.assertThat;

import com.beez.backend.support.AccessFixtures;
import com.beez.backend.support.ApiClient;
import com.beez.backend.support.IntegrationTestSupport;
import com.beez.backend.support.RecordingMailGateway;
import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 가입 이메일 인증 전체 흐름 (ACC-A11 → A12 → A01, -219). 실제 Postgres 로 잠금·부분 UNIQUE·트랜잭션 되돌림까지 본다.
 * 메일은 RecordingMailGateway 가 받아 두고 시험이 거기서 코드를 꺼낸다.
 */
class EmailVerificationIntegrationTest extends IntegrationTestSupport {

    private static final String PASSWORD = "secret-pass-1";

    @Autowired
    RecordingMailGateway mail;

    @Autowired
    JdbcTemplate db;

    private static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\":\"([^\"]*)\"").matcher(json);
        assertThat(m.find()).as(name + " in " + json).isTrue();
        return m.group(1);
    }

    private HttpResponse<String> send(String email) {
        return client().post("/api/auth/email-verifications", "{\"email\":\"" + email + "\"}");
    }

    private HttpResponse<String> confirm(String verificationId, String code) {
        return client().post("/api/auth/email-verifications/" + verificationId + "/confirm",
                "{\"code\":\"" + code + "\"}");
    }

    private HttpResponse<String> register(String email, String token) {
        return client().post("/api/auth/register", "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD
                + "\",\"name\":\"김개발\",\"verified_token\":\"" + token + "\"}");
    }

    private static String wrong(String code) {
        return code.equals("000000") ? "111111" : "000000";
    }

    // 깨지면: 화면이 따라가는 기본 흐름(코드 받기 → 확인 → 가입 → 로그인)이 끊기거나, 코드가 응답에 실려 나간다
    @Test
    void 코드_받기_확인_가입_로그인() {
        String email = AccessFixtures.uniqueEmail();

        HttpResponse<String> sent = send(email);
        assertThat(sent.statusCode()).as(sent.body()).isEqualTo(201);
        String code = mail.lastCodeTo(email);
        assertThat(sent.body()).contains("\"expires_at\"").contains("\"resend_available_at\"").doesNotContain(code);
        String id = field(sent.body(), "verification_id");
        assertThat(db.queryForObject("SELECT code_hash FROM email_verification WHERE id = ?", String.class,
                Long.parseLong(id))).as("코드는 해시로만 저장").doesNotContain(code);

        HttpResponse<String> confirmed = confirm(id, code);
        assertThat(confirmed.statusCode()).as(confirmed.body()).isEqualTo(200);
        assertThat(field(confirmed.body(), "email")).isEqualTo(email);
        String token = field(confirmed.body(), "verified_token");
        assertThat(token).startsWith("evt_");

        assertThat(register(email, token).statusCode()).isEqualTo(201);
        assertThat(client().login(email, PASSWORD)).isInstanceOf(ApiClient.class);
    }

    // 깨지면: 확인에 쓴 코드나 가입에 쓴 토큰을 다시 쓸 수 있다 (완료 조건 "재사용 코드 거부", 명세 "토큰은 가입 성공 때 소비")
    @Test
    void 코드와_토큰은_한_번만_쓴다() {
        String email = AccessFixtures.uniqueEmail();
        String id = field(send(email).body(), "verification_id");
        String code = mail.lastCodeTo(email);
        String token = field(confirm(id, code).body(), "verified_token");

        HttpResponse<String> again = confirm(id, code);
        assertThat(again.statusCode()).isEqualTo(410);
        assertThat(again.body()).contains("\"code\":\"verification_expired\"");

        assertThat(register(email, token).statusCode()).isEqualTo(201);
        db.update("DELETE FROM account WHERE email = ?", email);   // 409 가 아니라 토큰 소비로 거부되는지 보려고 계정을 지운다
        HttpResponse<String> reuse = register(email, token);
        assertThat(reuse.statusCode()).isEqualTo(400);
        assertThat(reuse.body()).contains("\"code\":\"email_not_verified\"");
    }

    // 깨지면: 다시 받은 뒤에도 옛 코드가 통한다 (명세 "다시 받으면 이전 코드는 바로 무효")
    @Test
    void 다시_받으면_이전_코드는_410() {
        String email = AccessFixtures.uniqueEmail();
        String firstId = field(send(email).body(), "verification_id");
        String firstCode = mail.lastCodeTo(email);

        HttpResponse<String> tooSoon = send(email);
        assertThat(tooSoon.statusCode()).as("다시 받기 간격 전").isEqualTo(429);
        assertThat(tooSoon.body()).contains("\"code\":\"rate_limited\"");

        db.update("UPDATE email_verification SET created_at = created_at - interval '2 minutes' WHERE id = ?",
                Long.parseLong(firstId));
        assertThat(send(email).statusCode()).isEqualTo(201);

        HttpResponse<String> old = confirm(firstId, firstCode);
        assertThat(old.statusCode()).isEqualTo(410);
        assertThat(old.body()).contains("\"code\":\"verification_expired\"");
    }

    // 깨지면: 5분이 지난 코드로 인증된다 (완료 조건 "만료 코드 거부")
    @Test
    void 만료된_코드는_410() {
        String email = AccessFixtures.uniqueEmail();
        String id = field(send(email).body(), "verification_id");
        db.update("UPDATE email_verification SET code_expires_at = now() - interval '1 second' WHERE id = ?",
                Long.parseLong(id));

        HttpResponse<String> res = confirm(id, mail.lastCodeTo(email));
        assertThat(res.statusCode()).isEqualTo(410);
        assertThat(res.body()).contains("\"code\":\"verification_expired\"");
    }

    // 깨지면: 틀린 횟수가 커밋되지 않아(오류 응답으로 되돌려짐) 무한히 맞혀 볼 수 있거나, 한도 뒤 맞는 코드가 통한다
    // (완료 조건 "시도 횟수 초과 거부")
    @Test
    void 다섯_번_틀리면_429_그_뒤엔_맞는_코드도_거부() {
        String email = AccessFixtures.uniqueEmail();
        String id = field(send(email).body(), "verification_id");
        String code = mail.lastCodeTo(email);

        for (int remaining = 4; remaining >= 1; remaining--) {
            HttpResponse<String> res = confirm(id, wrong(code));
            assertThat(res.statusCode()).isEqualTo(400);
            assertThat(res.body()).contains("\"code\":\"verification_code_mismatch\"")
                    .contains("\"remaining_attempts\":" + remaining);
        }
        HttpResponse<String> fifth = confirm(id, wrong(code));
        assertThat(fifth.statusCode()).isEqualTo(429);
        assertThat(fifth.body()).contains("\"code\":\"too_many_attempts\"");

        assertThat(confirm(id, code).statusCode()).isEqualTo(429);
    }

    // 깨지면: A 이메일로 인증하고 B 이메일로 가입할 수 있다 — 남의 이메일 계정을 만들어 초대 자동 참여(-220)를 가로챈다
    // (완료 조건 "토큰 이메일과 가입 이메일 불일치 거부")
    @Test
    void 다른_이메일의_토큰으로는_가입할_수_없고_토큰은_그대로_남는다() {
        String email = AccessFixtures.uniqueEmail();
        String id = field(send(email).body(), "verification_id");
        String token = field(confirm(id, mail.lastCodeTo(email)).body(), "verified_token");

        HttpResponse<String> other = register(AccessFixtures.uniqueEmail(), token);
        assertThat(other.statusCode()).isEqualTo(400);
        assertThat(other.body()).contains("\"code\":\"email_not_verified\"");

        HttpResponse<String> none = register(email, "evt_not-a-token");
        assertThat(none.statusCode()).isEqualTo(400);
        assertThat(none.body()).contains("\"code\":\"email_not_verified\"");

        // 대소문자만 다른 같은 이메일은 같은 사람이다 (Account.normalizeEmail)
        assertThat(register(email.toUpperCase(), token).statusCode()).isEqualTo(201);
    }

    // 깨지면: SMTP 실패가 500 이 되거나, 실패한 요청 때문에 바로 다시 받기가 1분 막힌다 (완료 조건 "메일 발송 실패 시 정해진 오류 응답")
    @Test
    void 메일_발송_실패는_503_이고_바로_다시_받을_수_있다() {
        String email = AccessFixtures.uniqueEmail();
        mail.failNext();

        HttpResponse<String> failed = send(email);
        assertThat(failed.statusCode()).isEqualTo(503);
        assertThat(failed.body()).contains("\"code\":\"mail_send_failed\"");

        assertThat(send(email).statusCode()).isEqualTo(201);
        assertThat(mail.countTo(email)).isEqualTo(1);
    }

    // 깨지면: 이미 가입한 이메일로 코드를 보내 메일함을 괴롭힐 수 있다 (ACC-A11 오류표 409)
    @Test
    void 가입한_이메일은_409() {
        String email = fixtures().emailOf(fixtures().account());

        HttpResponse<String> res = send(email);
        assertThat(res.statusCode()).isEqualTo(409);
        assertThat(res.body()).contains("\"code\":\"email_already_exists\"");
        assertThat(mail.countTo(email)).isZero();
    }
}
