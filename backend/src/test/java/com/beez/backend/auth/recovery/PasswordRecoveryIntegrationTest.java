package com.beez.backend.auth.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.beez.backend.common.mail.MailMessage;
import com.beez.backend.support.AccessFixtures;
import com.beez.backend.support.ApiClient;
import com.beez.backend.support.IntegrationTestSupport;
import com.beez.backend.support.RecordingMailGateway;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 비밀번호 찾기 — 임시 비밀번호 (ACC-A07 B안, S15P31A108-171). Jira 완료 조건 "재설정 재사용·만료 거부"를 임시 비밀번호로 덮는다.
 * 메일은 비동기로 나가므로 RecordingMailGateway 에 도착할 때까지 기다린다.
 */
class PasswordRecoveryIntegrationTest extends IntegrationTestSupport {

    private static final Pattern TEMPORARY = Pattern.compile("^([A-Za-z0-9]{12})$", Pattern.MULTILINE);
    private static final String NEW_PASSWORD = "changed-pass-2";

    @Autowired
    RecordingMailGateway mail;

    @Autowired
    JdbcTemplate db;

    private HttpResponse<String> forgot(String email, String name) {
        return client().post("/api/auth/password/forgot", "{\"email\":\"" + email + "\",\"name\":\"" + name + "\"}");
    }

    private HttpResponse<String> login(ApiClient c, String email, String password) {
        return c.post("/api/auth/login", "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
    }

    /** 이 주소로 새로 온 임시 비밀번호. before 는 요청 전 마지막 메일(없으면 null) — 그보다 새 메일을 기다린다. */
    private String awaitTemporary(String email, MailMessage before) {
        MailMessage m = await().atMost(Duration.ofSeconds(10))
                .until(() -> mail.lastTo(email), got -> got != null && got != before);
        Matcher matcher = TEMPORARY.matcher(m.text());
        assertThat(matcher.find()).as(m.text()).isTrue();
        return matcher.group(1);
    }

    /** 다시 요청 간격(60초)을 건너뛰려고 이 이메일의 요청을 과거로 민다. */
    private void ageRequests(String email) {
        db.update("UPDATE temporary_password SET created_at = created_at - interval '2 minutes' WHERE email = ?", email);
    }

    /**
     * 깨지면: 임시 비밀번호로 로그인할 수 없거나, 로그인해도 바꾸라는 표시가 없거나, 비밀번호를 바꾼 뒤에도 그 임시 비밀번호가 통한다
     * (완료 조건 "재사용 거부", ACC-A02 must_change_password, ACC-A06 "성공하면 must_change_password 가 풀린다").
     * 발급만으로는 기존 비밀번호가 그대로여야 한다 — 이메일·이름만 아는 사람이 계정 주인을 잠그지 못하게.
     */
    @Test
    void temporaryPassword_logsInOnce_forcesChange_andStopsWorkingAfterChange() {
        long id = fixtures().account();
        String email = fixtures().emailOf(id);

        HttpResponse<String> accepted = forgot(email.toUpperCase(), " tester ");
        assertThat(accepted.statusCode()).isEqualTo(202);
        String temporary = awaitTemporary(email, null);
        assertThat(db.queryForObject("SELECT password_hash FROM temporary_password WHERE account_id = ?", String.class,
                id)).as("원문을 저장하지 않는다").doesNotContain(temporary);

        assertThat(login(client(), email, AccessFixtures.PASSWORD).statusCode()).as("발급만으로 기존 비밀번호는 안 바뀐다")
                .isEqualTo(200);

        ApiClient user = client();
        HttpResponse<String> byTemporary = login(user, email, temporary);
        assertThat(byTemporary.statusCode()).as(byTemporary.body()).isEqualTo(200);
        assertThat(byTemporary.body()).contains("\"must_change_password\":true");
        assertThat(user.get("/api/me").body()).contains("\"must_change_password\":true");
        assertThat(login(client(), email, AccessFixtures.PASSWORD).statusCode()).as("임시 비밀번호가 비밀번호가 됐다")
                .isEqualTo(401);

        HttpResponse<String> changed = user.send(user.request("/api/me/password")
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"current_password\":\"" + temporary
                        + "\",\"new_password\":\"" + NEW_PASSWORD + "\"}")));
        assertThat(changed.statusCode()).as(changed.body()).isEqualTo(204);
        assertThat(user.get("/api/me").body()).contains("\"must_change_password\":false");

        assertThat(login(client(), email, temporary).statusCode()).as("바꾼 뒤 임시 비밀번호 재사용").isEqualTo(401);
        assertThat(login(client(), email, NEW_PASSWORD).statusCode()).isEqualTo(200);
    }

    /** 깨지면: 유효 시간이 지난 임시 비밀번호나, 새로 받아 무효가 된 이전 임시 비밀번호로 로그인된다 (완료 조건 "만료 거부"). */
    @Test
    void expiredOrSupersededTemporaryPassword_isRejected() {
        long id = fixtures().account();
        String email = fixtures().emailOf(id);

        forgot(email, "tester");
        String first = awaitTemporary(email, null);
        ageRequests(email);
        MailMessage firstMail = mail.lastTo(email);
        forgot(email, "tester");
        String second = awaitTemporary(email, firstMail);

        assertThat(login(client(), email, first).statusCode()).as("새로 받으면 이전 것은 무효").isEqualTo(401);

        db.update("UPDATE temporary_password SET expires_at = now() - interval '1 second'"
                + " WHERE account_id = ? AND closed_at IS NULL", id);
        assertThat(login(client(), email, second).statusCode()).as("만료").isEqualTo(401);
        assertThat(login(client(), email, AccessFixtures.PASSWORD).statusCode()).isEqualTo(200);
    }

    /**
     * 깨지면: 응답·발송 한도로 그 이메일의 가입 여부나 이름이 맞는지 알아낼 수 있거나, 이름이 틀려도 메일이 나간다
     * (ACC-A07 "이메일 존재 여부에 관계없이 같은 202", "이름과 이메일이 모두 맞을 때만 발급").
     */
    @Test
    void unknownEmailAndWrongName_lookTheSame_includingRateLimit() {
        long id = fixtures().account();
        String email = fixtures().emailOf(id);
        String unknown = AccessFixtures.uniqueEmail();

        HttpResponse<String> wrongName = forgot(email, "someone-else");
        HttpResponse<String> noAccount = forgot(unknown, "tester");
        assertThat(wrongName.statusCode()).isEqualTo(202);
        assertThat(noAccount.statusCode()).isEqualTo(202);
        assertThat(wrongName.body()).isEqualTo(noAccount.body());

        assertThat(forgot(email, "someone-else").body()).contains("\"code\":\"rate_limited\"");
        assertThat(forgot(unknown, "tester").body()).contains("\"code\":\"rate_limited\"");
        assertThat(mail.countTo(email)).isZero();
        assertThat(mail.countTo(unknown)).isZero();
    }

    /**
     * 깨지면: 메일을 못 보냈는데 받지 못한 임시 비밀번호가 열린 채 남거나, 발송 실패가 응답으로 드러나 가입 여부가 샌다
     * (Jira -171 작업 6 메일 발송 실패 처리).
     */
    @Test
    void mailFailure_stillAccepted_andClosesTheIssuedPassword() {
        long id = fixtures().account();
        String email = fixtures().emailOf(id);

        mail.failNext();
        assertThat(forgot(email, "tester").statusCode()).isEqualTo(202);

        await().atMost(Duration.ofSeconds(10)).until(() -> db.queryForObject(
                "SELECT count(*) FROM temporary_password WHERE account_id = ? AND closed_at IS NULL", Integer.class,
                id) == 0);
        assertThat(mail.countTo(email)).isZero();
    }
}
