package com.beez.backend.invitation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.beez.backend.config.BeezProperties;
import com.beez.backend.project.ProjectRole;
import com.beez.backend.support.AccessFixtures;
import com.beez.backend.support.ApiClient;
import com.beez.backend.support.IntegrationTestSupport;
import com.beez.backend.support.RecordingMailGateway;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 초대 메일 발송 한도와 다시 보내기(MEM-A11)의 잠금 밖 발송 (Q05 결정 2026-11-09, S15P31A108-334).
 * 한도 값은 운영과 같은 설정(beez.invitation.send-limit)을 읽는다.
 */
class InvitationSendLimitIntegrationTests extends IntegrationTestSupport {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    RecordingMailGateway mail;

    @Autowired
    InvitationSendLimiter limiter;

    @Autowired
    BeezProperties properties;

    /**
     * 깨지면: 팀장이 임의 문구 메일을 임의 주소로 끝없이 보낼 수 있거나(프로젝트를 새로 만들어 한도를 피하는 것 포함),
     * 한도를 넘은 요청이 일부만 초대해 FE 가 모르는 결과가 생기거나, 거부한 요청이 초대를 남긴다.
     */
    @Test
    void 계정당_한도를_넘으면_요청_전체가_429이고_아무것도_저장하지_않으며_새_프로젝트로_피하지_못한다() {
        int limit = properties.invitation().sendLimit();
        var p = fixtures().projectWithLeader();
        ApiClient leader = client().login(p.leaderEmail(), AccessFixtures.PASSWORD);

        List<String> first = emails(limit - 1);
        for (int from = 0; from < first.size(); from += 20) {   // emails 는 요청당 20개까지
            List<String> chunk = first.subList(from, Math.min(from + 20, first.size()));
            assertThat(leader.post(invitations(p.projectId()), body(chunk)).statusCode()).isEqualTo(201);
        }

        // 다른 프로젝트를 만들어도 같은 계정의 몫이다. 남은 1통보다 많은 2통을 요청하면 하나도 초대하지 않는다
        long other = fixtures().project(p.leaderAccountId());
        fixtures().member(other, p.leaderAccountId(), ProjectRole.LEADER);
        List<String> over = emails(2);
        HttpResponse<String> rejected = leader.post(invitations(other), body(over));
        assertThat(rejected.statusCode()).isEqualTo(429);
        assertThat(rejected.body()).contains("rate_limited");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM invitation WHERE project_id = ?", Long.class, other))
                .isZero();

        // 메일을 보내지 않는 결과(ALREADY_INVITED)는 세지 않는다 — 한도가 찬 뒤에도 같은 응답을 받는다
        HttpResponse<String> already = leader.post(invitations(p.projectId()), body(first.subList(0, 1)));
        assertThat(already.statusCode()).isEqualTo(201);
        assertThat(already.body()).contains("ALREADY_INVITED");

        assertThat(leader.post(invitations(other), body(over.subList(0, 1))).statusCode()).isEqualTo(201);
        assertThat(leader.post(invitations(other), body(emails(1))).statusCode()).isEqualTo(429);

        // 보내는 중인 메일을 남기지 않는다 (다른 시험의 실패 예약을 가져가지 않게)
        await().atMost(Duration.ofSeconds(10)).until(() -> mail.countTo(over.get(0)) == 1);
        await().atMost(Duration.ofSeconds(10)).until(() -> first.stream().allMatch(e -> mail.countTo(e) == 1));
    }

    /**
     * 깨지면: SMTP 가 끊긴 동안 다시 보내기를 몇 번 누른 팀장이 실제로 보내지 못한 메일로 한도를 다 써 한 시간 동안 막힌다.
     * 다시 보내기가 한도를 쓰지 않아도 깨진다(같은 주소로 끝없이 보낼 수 있다).
     */
    @Test
    void 다시_보내기는_한_통씩_세고_발송이_실패하면_되돌린다() {
        var p = fixtures().projectWithLeader();
        ApiClient leader = client().login(p.leaderEmail(), AccessFixtures.PASSWORD);
        String email = AccessFixtures.uniqueEmail();
        String id = invite(leader, p.projectId(), email);
        await().atMost(Duration.ofSeconds(10)).until(() -> mail.countTo(email) == 1);
        assertThat(limiter.used(p.leaderAccountId())).isEqualTo(1);

        mail.failNextTo(email);
        assertThat(leader.post(resend(p.projectId(), id), null).statusCode()).isEqualTo(503);
        assertThat(limiter.used(p.leaderAccountId())).as("보내지 못한 메일은 세지 않는다").isEqualTo(1);

        assertThat(leader.post(resend(p.projectId(), id), null).statusCode()).isEqualTo(200);
        assertThat(limiter.used(p.leaderAccountId())).isEqualTo(2);
    }

    /**
     * 깨지면: 다시 보내기가 초대 행 잠금을 쥔 채 SMTP 를 기다려, 그동안 초대받은 사람의 로그인(자동 참여)이 멈춘다 (-220 구조).
     * 보내는 사이 수락된 초대에 새 토큰을 넣어 끝난 초대에 새 링크가 생겨도 깨진다.
     */
    @Test
    void 다시_보내는_사이_초대받은_사람이_로그인하면_기다리지_않고_들어오고_다시_보내기는_409() {
        var p = fixtures().projectWithLeader();
        ApiClient leader = client().login(p.leaderEmail(), AccessFixtures.PASSWORD);
        String email = AccessFixtures.uniqueEmail();
        fixtures().account(email);
        String id = invite(leader, p.projectId(), email);
        await().atMost(Duration.ofSeconds(10)).until(() -> mail.countTo(email) == 1);
        String tokenHash = tokenHash(id);

        AtomicBoolean joinedDuringSend = new AtomicBoolean();   // 서버 요청 스레드가 쓰고 시험 스레드가 읽는다
        mail.duringNextSendTo(email, () -> {
            // 다시 보내기가 SMTP 를 기다리는 중이다. 잠금을 쥐고 있으면 이 로그인이 시간 안에 끝나지 않는다
            CompletableFuture<Integer> login = CompletableFuture.supplyAsync(() ->
                    client().login(email, AccessFixtures.PASSWORD).get("/api/projects/" + p.projectId()).statusCode());
            try {
                joinedDuringSend.set(login.get(5, TimeUnit.SECONDS) == 200);
            } catch (Exception e) {
                joinedDuringSend.set(false);
            }
        });

        HttpResponse<String> res = leader.post(resend(p.projectId(), id), null);
        assertThat(joinedDuringSend.get()).as("발송 중 로그인 자동 참여가 기다리지 않음").isTrue();
        assertThat(res.statusCode()).isEqualTo(409);
        assertThat(res.body()).contains("invitation_not_pending");
        assertThat(tokenHash(id)).as("수락된 초대의 토큰은 그대로").isEqualTo(tokenHash);
    }

    // ---- helpers ----

    private String tokenHash(String invitationId) {
        return jdbc.queryForObject("SELECT token_hash FROM invitation WHERE id = ?", String.class,
                Long.parseLong(invitationId));
    }

    private String invite(ApiClient leader, long projectId, String email) {
        HttpResponse<String> res = leader.post(invitations(projectId), body(List.of(email)));
        assertThat(res.statusCode()).isEqualTo(201);
        String marker = "\"invitation_id\":\"";
        int at = res.body().indexOf(marker) + marker.length();
        return res.body().substring(at, res.body().indexOf('"', at));
    }

    private static List<String> emails(int n) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(AccessFixtures.uniqueEmail());
        }
        return out;
    }

    private static String body(List<String> emails) {
        return "{\"emails\":[\"" + String.join("\",\"", emails) + "\"]}";
    }

    private static String invitations(long projectId) {
        return "/api/projects/" + projectId + "/invitations";
    }

    private static String resend(long projectId, String invitationId) {
        return invitations(projectId) + "/" + invitationId + "/resend";
    }
}
