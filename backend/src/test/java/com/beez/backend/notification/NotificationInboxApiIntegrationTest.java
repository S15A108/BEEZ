package com.beez.backend.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.beez.backend.project.ProjectRole;
import com.beez.backend.support.AccessFixtures;
import com.beez.backend.support.AccessFixtures.ProjectWithLeader;
import com.beez.backend.support.ApiClient;
import com.beez.backend.support.IntegrationTestSupport;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** NTF-A01 내 알림함·NTF-A02 읽음 처리 (S15P31A108-276·-280). 실제 HTTP·세션·SQL 로 본다. */
class NotificationInboxApiIntegrationTest extends IntegrationTestSupport {

    @Autowired
    JdbcTemplate sql;

    @Autowired
    ObjectMapper json;

    @Autowired
    NotificationRequester requester;

    @Autowired
    PlatformTransactionManager transactionManager;

    /** 입구(-181)를 거치지 않고 바로 넣는다 — 조회·읽음만 본다. */
    private long notify(long projectId, long recipient) {
        return sql.queryForObject("""
                insert into notification (project_id, recipient_account_id, type, title, body, ref_kind, ref_id, dedupe_key)
                values (?, ?, 'TASK_ASSIGNED', '작업 배정', '작업이 배정되었습니다', 'TASK', 501, ?) returning id
                """, Long.class, projectId, recipient, "task:501:assigned:" + UUID.randomUUID() + ":to:" + recipient);
    }

    private JsonNode body(HttpResponse<String> res) {
        return json.readTree(res.body());
    }

    private static List<String> ids(JsonNode page) {
        List<String> out = new ArrayList<>();
        page.get("items").forEach(item -> out.add(item.get("id").asString()));
        return out;
    }

    /** 깨지면 커서 경계에서 알림이 빠지거나 겹치고, 배지 수가 페이지·필터에 따라 흔들린다. */
    @Test
    void pagesByCursor_newestFirst_withoutGapOrOverlap_andBadgeIgnoresPaging() {
        ProjectWithLeader p = fixtures().projectWithLeader();
        long first = notify(p.projectId(), p.leaderAccountId());
        long second = notify(p.projectId(), p.leaderAccountId());
        long third = notify(p.projectId(), p.leaderAccountId());
        sql.update("update notification set read_at = now() where id = ?", first);
        ApiClient me = client().login(p.leaderEmail(), AccessFixtures.PASSWORD);
        String base = "/api/me/notifications?project_id=" + p.projectId();

        JsonNode page1 = body(me.get(base + "&limit=2"));
        assertThat(ids(page1)).containsExactly(String.valueOf(third), String.valueOf(second));
        assertThat(page1.get("has_more").asBoolean()).isTrue();
        assertThat(page1.get("unread_count").asLong()).isEqualTo(2);

        JsonNode page2 = body(me.get(base + "&limit=2&cursor=" + page1.get("next_cursor").asString()));
        assertThat(ids(page2)).containsExactly(String.valueOf(first));
        assertThat(page2.get("has_more").asBoolean()).isFalse();
        assertThat(page2.has("next_cursor")).isFalse();
        assertThat(page2.get("unread_count").asLong()).isEqualTo(2);
        assertThat(page2.get("items").get(0).has("read_at")).isTrue();

        JsonNode unreadOnly = body(me.get(base + "&unread=true"));
        assertThat(ids(unreadOnly)).containsExactly(String.valueOf(third), String.valueOf(second));
        assertThat(unreadOnly.get("items").get(0).get("target_type").asString()).isEqualTo("TASK");
    }

    /**
     * 깨지면 남의 알림이 보이거나, 남의 ID 를 섞은 요청이 내 알림만 골라 부분 처리된다(명세: 타인 ID 404, 부분 처리 없음).
     * 같은 프로젝트 팀원의 알림이어도 마찬가지다.
     */
    @Test
    void othersNotifications_areInvisible_andMixedReadIs404WithoutPartialUpdate() {
        ProjectWithLeader p = fixtures().projectWithLeader();
        long teammate = fixtures().account();
        fixtures().member(p.projectId(), teammate, ProjectRole.MEMBER);
        long mine = notify(p.projectId(), p.leaderAccountId());
        long theirs = notify(p.projectId(), teammate);
        ApiClient me = client().login(p.leaderEmail(), AccessFixtures.PASSWORD);

        assertThat(ids(body(me.get("/api/me/notifications?project_id=" + p.projectId()))))
                .containsExactly(String.valueOf(mine));

        HttpResponse<String> res = me.post("/api/me/notifications/read",
                "{\"ids\":[\"" + mine + "\",\"" + theirs + "\"]}");
        assertThat(res.statusCode()).isEqualTo(404);
        assertThat(body(res).get("error").get("code").asString()).isEqualTo("not_found");
        assertThat(sql.queryForList("select read_at from notification where id in (?, ?)", mine, theirs))
                .allSatisfy(row -> assertThat(row.get("read_at")).isNull());
    }

    /**
     * 깨지면 입구(-181)의 document_id 저장이나 조회 매핑이 빠져, 화면이 DOCUMENT_VERSION 알림의 문서 상세 주소를 만들지 못한다
     * (계약 3절 document_id 필수). 다른 시험은 SQL 로 바로 넣어 document_id 가 늘 비어 있다 (S15P31A108-280).
     */
    @Test
    void documentVersionNotification_keepsDocumentId_fromRequesterToInbox() {
        ProjectWithLeader p = fixtures().projectWithLeader();
        long registrant = fixtures().account();
        fixtures().member(p.projectId(), registrant, ProjectRole.MEMBER);
        long document = sql.queryForObject(
                "insert into document (project_id, type, title, created_by) values (?, 'PLAN', '기획', ?) returning id",
                Long.class, p.projectId(), registrant);
        long version = sql.queryForObject("""
                insert into document_version (document_id, version_no, status, storage_key, content_hash)
                values (?, 1, 'DRAFT', 'fixture/plan-v1', 'fixture-hash') returning id
                """, Long.class, document);
        // 키 첫 조각은 문서 도메인 몫이라 계약 5절 예시(doc-submission)를 따른다 — 실제 키는 문서 도메인(-217)이 정한다
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> requester.request(new NotificationRequest(
                "doc-submission:" + version + ":approved:to:" + registrant, p.projectId(), registrant, "DOCUMENT_APPROVED",
                "문서 승인", "등록한 문서가 승인되었습니다", NotificationTargetType.DOCUMENT_VERSION, version, document)));
        ApiClient me = client().login(fixtures().emailOf(registrant), AccessFixtures.PASSWORD);

        JsonNode item = body(me.get("/api/me/notifications?project_id=" + p.projectId())).get("items").get(0);
        assertThat(item.get("target_type").asString()).isEqualTo("DOCUMENT_VERSION");
        assertThat(item.get("target_id").asString()).isEqualTo(String.valueOf(version));
        assertThat(item.get("document_id").asString()).isEqualTo(String.valueOf(document));
    }

    /** 깨지면 탈퇴 뒤 남은 세션으로 알림을 보고 읽음 처리까지 할 수 있다(PR #126 리뷰에서 재현된 회귀). */
    @Test
    void withdrawnAccount_leftoverSession_is401_andChangesNothing() {
        ProjectWithLeader p = fixtures().projectWithLeader();
        long n = notify(p.projectId(), p.leaderAccountId());
        ApiClient me = client().login(p.leaderEmail(), AccessFixtures.PASSWORD);
        fixtures().withdraw(p.leaderAccountId());   // 세션은 그대로 둔 채 계정만 탈퇴 상태로

        assertThat(me.get("/api/me/notifications").statusCode()).isEqualTo(401);
        assertThat(me.post("/api/me/notifications/read", "{\"all\":true}").statusCode()).isEqualTo(401);
        assertThat(me.post("/api/me/notifications/read", "{\"ids\":[\"" + n + "\"]}").statusCode()).isEqualTo(401);
        assertThat(sql.queryForObject("select read_at from notification where id = ?", Object.class, n)).isNull();
    }

    /** 깨지면 재시도·더블클릭에서 updated_count 가 다시 세지거나 처음 읽은 시각이 덮인다(명세: 중복 갱신하지 않음). */
    @Test
    void readingTwice_countsOnce_andKeepsFirstReadAt() {
        ProjectWithLeader p = fixtures().projectWithLeader();
        long n = notify(p.projectId(), p.leaderAccountId());
        ApiClient me = client().login(p.leaderEmail(), AccessFixtures.PASSWORD);
        String request = "{\"ids\":[\"" + n + "\"]}";

        assertThat(body(me.post("/api/me/notifications/read", request)).get("updated_count").asInt()).isEqualTo(1);
        Object firstReadAt = sql.queryForObject("select read_at from notification where id = ?", Object.class, n);

        assertThat(body(me.post("/api/me/notifications/read", request)).get("updated_count").asInt()).isZero();
        assertThat(sql.queryForObject("select read_at from notification where id = ?", Object.class, n))
                .isEqualTo(firstReadAt);
    }

    /**
     * 깨지면 '모두 읽음'이 project_id 범위를 넘거나, 나간 프로젝트의 알림이 배지에 남아 지울 수 없게 된다.
     * 나간 프로젝트 알림을 숨기는 것은 -276 결정(명세 인증 절 "활성 멤버십")이다.
     */
    @Test
    void readAll_staysInProjectScope_andLeftProjectsAreHidden() {
        ProjectWithLeader p = fixtures().projectWithLeader();
        long otherProject = fixtures().project(p.leaderAccountId());
        fixtures().member(otherProject, p.leaderAccountId(), ProjectRole.MEMBER);
        long leftProject = fixtures().project(p.leaderAccountId());
        long leftMembership = fixtures().member(leftProject, p.leaderAccountId(), ProjectRole.MEMBER);
        notify(p.projectId(), p.leaderAccountId());
        notify(p.projectId(), p.leaderAccountId());
        long elsewhere = notify(otherProject, p.leaderAccountId());
        long hidden = notify(leftProject, p.leaderAccountId());
        fixtures().removeMember(leftMembership);
        ApiClient me = client().login(p.leaderEmail(), AccessFixtures.PASSWORD);

        // 빈 project_id 는 필터 없음('전체' 필터 화면)
        assertThat(body(me.get("/api/me/notifications?project_id=")).get("unread_count").asLong()).isEqualTo(3);
        assertThat(me.post("/api/me/notifications/read", "{\"ids\":[\"" + hidden + "\"]}").statusCode()).isEqualTo(404);

        JsonNode res = body(me.post("/api/me/notifications/read",
                "{\"all\":true,\"project_id\":\"" + p.projectId() + "\"}"));
        assertThat(res.get("updated_count").asInt()).isEqualTo(2);
        assertThat(body(me.get("/api/me/notifications?project_id=" + p.projectId())).get("unread_count").asLong()).isZero();
        assertThat(ids(body(me.get("/api/me/notifications?unread=true")))).containsExactly(String.valueOf(elsewhere));
    }
}
