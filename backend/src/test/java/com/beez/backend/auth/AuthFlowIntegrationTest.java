package com.beez.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.beez.backend.support.AccessFixtures;
import com.beez.backend.support.ApiClient;
import com.beez.backend.support.IntegrationTestSupport;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

/** ACC-A01~A04 와 /api 세션 체인. Jira -170 완료 조건 "세션 만료·무권한 테스트"의 세션 쪽. */
class AuthFlowIntegrationTest extends IntegrationTestSupport {

    private static String registerJson(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\",\"name\":\"김개발\"}";
    }

    @Test
    void protectedApi_withoutSession_is401_andDoesNotCreateSession() {
        HttpResponse<String> res = client().get("/api/me");

        assertThat(res.statusCode()).isEqualTo(401);
        assertThat(res.body()).contains("\"code\":\"unauthenticated\"");
        assertThat(res.headers().allValues("Set-Cookie")).isEmpty();
    }

    @Test
    void register_login_me_logout_thenSessionNoLongerWorks() {
        String email = AccessFixtures.uniqueEmail();
        ApiClient user = client();

        HttpResponse<String> registered = user.post("/api/auth/register", registerJson(email, "secret-pass-1"));
        assertThat(registered.statusCode()).isEqualTo(201);
        assertThat(registered.body()).contains("\"email\":\"" + email + "\"").contains("\"created_at\"")
                .doesNotContain("password");
        assertThat(registered.headers().allValues("Set-Cookie")).as("가입은 세션을 만들지 않는다").isEmpty();

        HttpResponse<String> login = user.post("/api/auth/login",
                "{\"email\":\"" + email.toUpperCase() + "\",\"password\":\"secret-pass-1\"}");
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(login.headers().allValues("Set-Cookie")).anySatisfy(c -> assertThat(c)
                .startsWith("BEEZ_SESSION=").containsIgnoringCase("HttpOnly").containsIgnoringCase("SameSite=Lax"));

        HttpResponse<String> me = user.get("/api/me");
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(me.body()).contains("\"email\":\"" + email + "\"").contains("\"name\":\"김개발\"");

        assertThat(user.post("/api/auth/logout", null).statusCode()).isEqualTo(204);
        assertThat(user.get("/api/me").statusCode()).isEqualTo(401);
        assertThat(user.post("/api/auth/logout", null).statusCode()).as("만료된 세션의 재요청도 204").isEqualTo(204);
    }

    @Test
    void register_sameEmailInOtherCase_is409() {
        String email = AccessFixtures.uniqueEmail();
        assertThat(client().post("/api/auth/register", registerJson(email, "secret-pass-1")).statusCode())
                .isEqualTo(201);

        // 대소문자·앞뒤 공백만 다른 이메일 → 같은 계정으로 보고 409 (409 는 email UNIQUE 제약 이름으로 판정한다)
        HttpResponse<String> dup = client().post("/api/auth/register",
                registerJson("  " + email.toUpperCase() + " ", "secret-pass-1"));
        assertThat(dup.statusCode()).as(dup.body()).isEqualTo(409);
        assertThat(dup.body()).contains("\"code\":\"email_already_exists\"");
    }

    @Test
    void register_weakPassword_is400() {
        HttpResponse<String> res = client().post("/api/auth/register",
                registerJson(AccessFixtures.uniqueEmail(), "short1"));
        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(res.body()).contains("\"code\":\"validation_failed\"");
    }

    @Test
    void login_wrongPassword_andUnknownEmail_lookTheSame() {
        String email = fixtures().emailOf(fixtures().account());

        HttpResponse<String> wrongPassword = client().post("/api/auth/login",
                "{\"email\":\"" + email + "\",\"password\":\"wrong-pass-1\"}");
        HttpResponse<String> unknownEmail = client().post("/api/auth/login",
                "{\"email\":\"" + AccessFixtures.uniqueEmail() + "\",\"password\":\"wrong-pass-1\"}");

        assertThat(wrongPassword.statusCode()).isEqualTo(401);
        assertThat(wrongPassword.body()).contains("\"code\":\"invalid_credentials\"").isEqualTo(unknownEmail.body());
    }

    @Test
    void corsPreflight_fromFrontendOrigin_isNotBlockedBySecurity() {
        HttpResponse<String> res = client().send(client().request("/api/me",
                        "Origin", "http://localhost:5173",
                        "Access-Control-Request-Method", "GET")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()));

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.headers().firstValue("Access-Control-Allow-Origin")).hasValue("http://localhost:5173");
        assertThat(res.headers().firstValue("Access-Control-Allow-Credentials")).hasValue("true");
    }
}
