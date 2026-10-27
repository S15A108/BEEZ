package com.beez.backend.support;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * 통합 테스트용 HTTP 클라이언트. 인스턴스마다 쿠키 저장소가 따로라 "로그인한 사용자 한 명"처럼 쓴다.
 * Boot 4 에서 테스트용 웹 클라이언트 패키지가 많이 바뀌어 JDK HttpClient 로 실제 포트를 호출한다.
 */
public final class ApiClient {

    private final HttpClient http;
    private final String baseUrl;

    ApiClient(int port) {
        this.http = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .build();
        this.baseUrl = "http://localhost:" + port;
    }

    /** 로그인하고 자신을 돌려준다. 실패하면 예외. */
    public ApiClient login(String email, String password) {
        HttpResponse<String> res = post("/api/auth/login",
                "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
        if (res.statusCode() != 200) {
            throw new IllegalStateException("login failed: " + res.statusCode() + " " + res.body());
        }
        return this;
    }

    public HttpResponse<String> get(String path, String... headers) {
        return send(request(path, headers).GET());
    }

    public HttpResponse<String> post(String path, String json, String... headers) {
        return send(request(path, headers)
                .header("Content-Type", "application/json")
                .POST(json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json)));
    }

    public HttpResponse<String> patch(String path, String json, String... headers) {
        return send(request(path, headers)
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(json)));
    }

    /** 본문이 있는 DELETE (PRJ-A05 confirm_name 처럼). */
    public HttpResponse<String> delete(String path, String json, String... headers) {
        return send(request(path, headers)
                .header("Content-Type", "application/json")
                .method("DELETE", HttpRequest.BodyPublishers.ofString(json)));
    }

    public HttpResponse<String> send(HttpRequest.Builder builder) {
        try {
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** headers 는 이름, 값, 이름, 값 … 순서. */
    public HttpRequest.Builder request(String path, String... headers) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path));
        for (int i = 0; i + 1 < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return b;
    }
}
