package com.beez.backend.bee;

import static org.assertj.core.api.Assertions.assertThat;

import com.beez.backend.project.ProjectRole;
import com.beez.backend.support.AccessFixtures;
import com.beez.backend.support.ApiClient;
import com.beez.backend.support.IntegrationTestSupport;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** BEE-A03 답변 피드백, BEE-A04 현재 상태 (S15P31A108-234). AI 호출이 없어 대역 없이 DB 와 API 만 쓴다 */
class BeeIntegrationTest extends IntegrationTestSupport {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String ORIGIN = "http://localhost:5173";

    @Autowired
    JdbcTemplate db;

    static final String[] TYPES = {"PLAN", "ERD", "REQ_SPEC", "API_SPEC"};
    int typeCursor;

    AccessFixtures.ProjectWithLeader project;
    ApiClient leader;
    long memberAccount;
    ApiClient member;

    @BeforeEach
    void setUp() {
        project = fixtures().projectWithLeader();
        leader = client().login(project.leaderEmail(), AccessFixtures.PASSWORD);
        String email = AccessFixtures.uniqueEmail();
        memberAccount = fixtures().account(email);
        fixtures().member(project.projectId(), memberAccount, ProjectRole.MEMBER);
        member = client().login(email, AccessFixtures.PASSWORD);
    }

    // ---- 도우미 ----

    private long answer(long projectId, long accountId, String versionIds) {
        return db.queryForObject("""
                insert into bee_answer(project_id, account_id, mode, document_version_ids)
                values (?, ?, 'APPROVED', cast(? as jsonb)) returning id
                """, Long.class, projectId, accountId, versionIds);
    }

    private String feedbackPath(long projectId, long answerId) {
        return "/api/projects/" + projectId + "/bee/answers/" + answerId + "/feedback";
    }

    private HttpResponse<String> feedback(ApiClient who, long projectId, long answerId, String json) {
        return who.post(feedbackPath(projectId, answerId), json, "Origin", ORIGIN);
    }

    private JsonNode status(ApiClient who, long projectId) {
        HttpResponse<String> res = who.get("/api/projects/" + projectId + "/bee/status");
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        return JSON.readTree(res.body());
    }

    private long document(long projectId, String title) {
        return db.queryForObject("insert into document(project_id, type, title, created_by) values (?, ?, ?, ?) returning id",
                Long.class, projectId, TYPES[typeCursor++ % TYPES.length], title, project.leaderAccountId());
    }

    private long version(long document) {
        return db.queryForObject("""
                insert into document_version(document_id, version_no, status, storage_key, content_hash, content_text, created_by)
                values (?, 1, 'DRAFT', 'k', 'h' || ?, 'text', ?) returning id
                """, Long.class, document, document, project.leaderAccountId());
    }

    /** 작성 사본과 비비 검토 요청 행. minutesAgo 분 전에 시작한 요청 */
    private long review(long projectId, String title, String status, int minutesAgo) {
        long document = document(projectId, title);
        long version = version(document);
        db.update("""
                insert into document_working_copy(document_id, owner_account_id, initial_version_id, status, content_text, file_name)
                values (?, ?, ?, 'EDITING', 'text', 'f.md')
                """, document, project.leaderAccountId(), version);
        db.update("""
                insert into document_bee_review_request(document_id, account_id, request_key, fingerprint, status, created_at)
                values (?, ?, 'k', 'f', ?, now() - make_interval(mins => ?))
                """, document, project.leaderAccountId(), status, minutesAgo);
        return version;
    }

    private long meeting(long projectId, String title, String status, int minutesAgo) {
        return db.queryForObject("""
                insert into meeting(project_id, title, status, created_by, created_at, closed_at, closed_by, task_set_key)
                values (?, ?, ?, ?, now() - make_interval(mins => ?), case when ? = 'CLOSED' then now() end,
                        case when ? = 'CLOSED' then ? end, 'set-' || ?) returning id
                """, Long.class, projectId, title, status, project.leaderAccountId(), minutesAgo, status, status,
                project.leaderAccountId(), title);
    }

    // ---- BEE-A03 ----

    @Test
    void feedback_savesRatingAndCopiesSourceVersions() {
        long answer = answer(project.projectId(), memberAccount, "[11,12]");

        HttpResponse<String> res = feedback(member, project.projectId(), answer, "{\"rating\":\"WRONG_SOURCE\",\"comment\":\" 근거가 달라요 \"}");

        assertThat(res.statusCode()).as(res.body()).isEqualTo(204);
        assertThat(db.queryForMap("select rating, comment, document_version_ids::text as ids from bee_answer_feedback where answer_id = ?", answer))
                .containsEntry("rating", "WRONG_SOURCE").containsEntry("comment", "근거가 달라요")
                .containsEntry("ids", "[11, 12]");
    }

    @Test
    void feedback_resend_overwritesWithoutDuplicate() {
        long answer = answer(project.projectId(), memberAccount, "[]");
        feedback(member, project.projectId(), answer, "{\"rating\":\"WRONG_SOURCE\",\"comment\":\"틀림\"}");

        HttpResponse<String> res = feedback(member, project.projectId(), answer, "{\"rating\":\"HELPFUL\"}");

        assertThat(res.statusCode()).isEqualTo(204);
        assertThat(db.queryForObject("select count(*) from bee_answer_feedback where answer_id = ?", Integer.class, answer)).isEqualTo(1);
        assertThat(db.queryForMap("select rating, comment from bee_answer_feedback where answer_id = ?", answer))
                .containsEntry("rating", "HELPFUL").containsEntry("comment", null);
    }

    @Test
    void feedback_otherAccountsAnswer_is404AndNothingSaved() {
        long answer = answer(project.projectId(), memberAccount, "[]");

        HttpResponse<String> res = feedback(leader, project.projectId(), answer, "{\"rating\":\"HELPFUL\"}");

        assertThat(res.statusCode()).isEqualTo(404);
        assertThat(db.queryForObject("select count(*) from bee_answer_feedback where answer_id = ?", Integer.class, answer)).isZero();
    }

    @Test
    void feedback_answerOfAnotherProject_is404() {
        AccessFixtures.ProjectWithLeader other = fixtures().projectWithLeader();
        long answer = answer(other.projectId(), project.leaderAccountId(), "[]");

        assertThat(feedback(leader, project.projectId(), answer, "{\"rating\":\"HELPFUL\"}").statusCode()).isEqualTo(404);
    }

    @Test
    void feedback_nonMember_is404_andUnknownAnswer_is404() {
        AccessFixtures.ProjectWithLeader other = fixtures().projectWithLeader();
        ApiClient outsider = client().login(other.leaderEmail(), AccessFixtures.PASSWORD);
        long answer = answer(project.projectId(), memberAccount, "[]");

        assertThat(feedback(outsider, project.projectId(), answer, "{\"rating\":\"HELPFUL\"}").statusCode()).isEqualTo(404);
        assertThat(feedback(member, project.projectId(), answer + 1000, "{\"rating\":\"HELPFUL\"}").statusCode()).isEqualTo(404);
    }

    @Test
    void feedback_invalidBody_is400() {
        long answer = answer(project.projectId(), memberAccount, "[]");

        assertThat(feedback(member, project.projectId(), answer, "{\"rating\":\"GOOD\"}").statusCode()).isEqualTo(400);
        assertThat(feedback(member, project.projectId(), answer, "{}").statusCode()).isEqualTo(400);
        assertThat(feedback(member, project.projectId(), answer,
                "{\"rating\":\"HELPFUL\",\"comment\":\"" + "가".repeat(1001) + "\"}").statusCode()).isEqualTo(400);
        assertThat(member.post(feedbackPath(project.projectId(), answer) .replace("/" + answer + "/", "/abc/"),
                "{\"rating\":\"HELPFUL\"}", "Origin", ORIGIN).statusCode()).isEqualTo(400);
        assertThat(db.queryForObject("select count(*) from bee_answer_feedback where answer_id = ?", Integer.class, answer)).isZero();
    }

    @Test
    void feedback_untrustedOrigin_isRejected() {
        long answer = answer(project.projectId(), memberAccount, "[]");

        HttpResponse<String> res = member.post(feedbackPath(project.projectId(), answer), "{\"rating\":\"HELPFUL\"}",
                "Origin", "http://evil.example");

        assertThat(res.statusCode()).isEqualTo(403);
        assertThat(db.queryForObject("select count(*) from bee_answer_feedback where answer_id = ?", Integer.class, answer)).isZero();
    }

    // ---- BEE-A04 ----

    @Test
    void status_nothingRunning_isIdleWithoutTarget() {
        JsonNode body = status(member, project.projectId());

        assertThat(body.get("activity").asText()).isEqualTo("IDLE");
        assertThat(body.has("target_type")).isFalse();
        assertThat(body.has("target_id")).isFalse();
        assertThat(body.has("label")).isFalse();
    }

    @Test
    void status_runningReview_showsDocumentVersionAndLabel() {
        long version = review(project.projectId(), "알림 API 명세서", "RUNNING", 1);

        JsonNode body = status(member, project.projectId());

        assertThat(body.get("activity").asText()).isEqualTo("DOCUMENT_REVIEW");
        assertThat(body.get("target_type").asText()).isEqualTo("DOCUMENT_VERSION");
        assertThat(body.get("target_id").asText()).isEqualTo(Long.toString(version));
        assertThat(body.get("label").asText()).isEqualTo("알림 API 명세서 검토 중");
    }

    @Test
    void status_finishedOrStalledReview_isIgnored() {
        review(project.projectId(), "끝난 문서", "COMPLETED", 1);
        review(project.projectId(), "멈춘 문서", "RUNNING", 60);

        assertThat(status(member, project.projectId()).get("activity").asText()).isEqualTo("IDLE");
    }

    @Test
    void status_openMeeting_andMostRecentWins() {
        long meeting = meeting(project.projectId(), "로그인 충돌", "OPEN", 5);
        meeting(project.projectId(), "끝난 회의", "CLOSED", 1);
        JsonNode meetingOnly = status(member, project.projectId());
        assertThat(meetingOnly.get("activity").asText()).isEqualTo("MEETING");
        assertThat(meetingOnly.get("target_type").asText()).isEqualTo("MEETING");
        assertThat(meetingOnly.get("target_id").asText()).isEqualTo(Long.toString(meeting));
        assertThat(meetingOnly.get("label").asText()).isEqualTo("로그인 충돌 회의 중");

        review(project.projectId(), "최근 문서", "RUNNING", 1);
        assertThat(status(member, project.projectId()).get("activity").asText()).isEqualTo("DOCUMENT_REVIEW");
    }

    @Test
    void status_otherProjectActivityIsNotShown_andNonMemberIs404() {
        AccessFixtures.ProjectWithLeader other = fixtures().projectWithLeader();
        review(other.projectId(), "남의 문서", "RUNNING", 1);

        assertThat(status(member, project.projectId()).get("activity").asText()).isEqualTo("IDLE");
        ApiClient outsider = client().login(other.leaderEmail(), AccessFixtures.PASSWORD);
        assertThat(outsider.get("/api/projects/" + project.projectId() + "/bee/status").statusCode()).isEqualTo(404);
    }
}
