package com.beez.backend.status;

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

/** MON-D01 문서 승인 현황, MON-C01 변경 제안 현황 (S15P31A108-104) */
class StatusIntegrationTest extends IntegrationTestSupport {

    static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    JdbcTemplate db;

    AccessFixtures.ProjectWithLeader project;
    ApiClient leader;
    long leaderId;

    @BeforeEach
    void setUp() {
        project = fixtures().projectWithLeader();
        leaderId = project.leaderAccountId();
        leader = client().login(project.leaderEmail(), AccessFixtures.PASSWORD);
    }

    // ---- 도우미 ----

    private long baseline(long projectId, int versionNo) {
        return db.queryForObject("insert into baseline(project_id, version_no, published_by) values (?, ?, ?) returning id",
                Long.class, projectId, versionNo, leaderId);
    }

    private long document(long projectId, String type) {
        return db.queryForObject("insert into document(project_id, type, title, created_by) values (?, ?, ?, ?) returning id",
                Long.class, projectId, type, type, leaderId);
    }

    private long version(long document, int no, String status) {
        return db.queryForObject("""
                insert into document_version(document_id, version_no, status, storage_key, content_hash, content_text, created_by,
                                             approved_by, approved_at)
                values (?, ?, ?, 'k', 'h' || ?, 'text', ?, case when ? = 'APPROVED' then ? end,
                        case when ? = 'APPROVED' then now() end) returning id
                """, Long.class, document, no, status, document + "-" + no, leaderId, status, leaderId, status);
    }

    private void putInBaseline(long baseline, long projectId, long document, long version) {
        db.update("insert into baseline_document values (?, ?, ?, ?)", baseline, projectId, document, version);
    }

    private void currentBaseline(long projectId, long baseline) {
        db.update("update project set current_baseline_id = ? where id = ?", baseline, projectId);
    }

    private void workingCopy(long document, long initialVersion, String status) {
        db.update("""
                insert into document_working_copy(document_id, owner_account_id, initial_version_id, status, content_text, file_name)
                values (?, ?, ?, ?, 'text', 'f.md')
                """, document, leaderId, initialVersion, status);
    }

    private void proposal(long projectId, long baseline, String key, String status) {
        db.update("""
                insert into change_proposal(project_id, key, status, base_baseline_id, proposer_kind, proposer_account_id, reason,
                                            withdrawn_by, withdrawn_at)
                values (?, ?, ?, ?, 'HUMAN', ?, '사유', case when ? = 'WITHDRAWN' then ? end,
                        case when ? = 'WITHDRAWN' then now() end)
                """, projectId, key, status, baseline, leaderId, status, leaderId, status);
    }

    private JsonNode get(ApiClient client, String path) {
        HttpResponse<String> res = client.get(path);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        return JSON.readTree(res.body());
    }

    private String documentsPath(long projectId) {
        return "/api/projects/" + projectId + "/documents/summary";
    }

    private String proposalsPath(long projectId) {
        return "/api/projects/" + projectId + "/change-proposals/summary";
    }

    private static JsonNode kind(JsonNode body, String kind) {
        for (JsonNode n : body.get("by_kind")) {
            if (kind.equals(n.get("kind").asText())) {
                return n;
            }
        }
        throw new AssertionError("종류 없음: " + kind);
    }

    private static int count(JsonNode body, String status) {
        for (JsonNode n : body.get("by_status")) {
            if (status.equals(n.get("status").asText())) {
                return n.get("count").asInt();
            }
        }
        throw new AssertionError("상태 없음: " + status);
    }

    // ---- MON-D01 ----

    @Test
    void documents_emptyProject_returnsAllKindsZeroAndOmitsBaseline() {
        JsonNode body = get(leader, documentsPath(project.projectId()));

        assertThat(body.has("baseline")).isFalse();
        assertThat(body.get("by_kind")).hasSize(4);
        assertThat(body.get("by_kind").get(0).get("kind").asText()).isEqualTo("PLAN");
        assertThat(body.get("by_kind").get(1).get("kind").asText()).isEqualTo("REQUIREMENTS");
        assertThat(body.get("by_kind").get(2).get("kind").asText()).isEqualTo("API_SPEC");
        assertThat(body.get("by_kind").get(3).get("kind").asText()).isEqualTo("ERD");
        for (JsonNode n : body.get("by_kind")) {
            assertThat(n.get("draft_count").asInt()).isZero();
            assertThat(n.get("approved_count").asInt()).isZero();
        }
    }

    @Test
    void documents_uploadedDraftOnly_countsDraft() {
        long plan = document(project.projectId(), "PLAN");
        version(plan, 1, "DRAFT");

        JsonNode body = get(leader, documentsPath(project.projectId()));

        assertThat(kind(body, "PLAN").get("draft_count").asInt()).isEqualTo(1);
        assertThat(kind(body, "PLAN").get("approved_count").asInt()).isZero();
        assertThat(kind(body, "ERD").get("draft_count").asInt()).isZero();
        assertThat(body.has("baseline")).isFalse();
    }

    @Test
    void documents_approvedInCurrentBaseline_countsApprovedAndShowsBaseline() {
        long projectId = project.projectId();
        long base = baseline(projectId, 1);
        long req = document(projectId, "REQ_SPEC");
        putInBaseline(base, projectId, req, version(req, 1, "APPROVED"));
        currentBaseline(projectId, base);

        JsonNode body = get(leader, documentsPath(projectId));

        // DB 값 REQ_SPEC 은 API 값 REQUIREMENTS 로 나간다
        assertThat(kind(body, "REQUIREMENTS").get("approved_count").asInt()).isEqualTo(1);
        assertThat(kind(body, "REQUIREMENTS").get("draft_count").asInt()).isZero();
        assertThat(body.get("baseline").get("id").asText()).isEqualTo(Long.toString(base));
        assertThat(body.get("baseline").get("version_no").asInt()).isEqualTo(1);
        assertThat(body.get("baseline").get("created_at").asText()).isNotBlank();
    }

    @Test
    void documents_approvedBeforeFirstBaseline_countsApproved() {
        // 시작 전: 팀장이 DOC-A21 로 문서를 승인해도 아직 기준(baseline)에는 들어가지 않는다
        long projectId = project.projectId();
        for (String type : new String[] {"PLAN", "REQ_SPEC", "API_SPEC"}) {
            long doc = document(projectId, type);
            version(doc, 1, "APPROVED");
        }
        long erd = document(projectId, "ERD");
        version(erd, 1, "DRAFT");

        JsonNode body = get(leader, documentsPath(projectId));

        assertThat(body.has("baseline")).isFalse();
        assertThat(kind(body, "PLAN").get("approved_count").asInt()).isEqualTo(1);
        assertThat(kind(body, "REQUIREMENTS").get("approved_count").asInt()).isEqualTo(1);
        assertThat(kind(body, "API_SPEC").get("approved_count").asInt()).isEqualTo(1);
        assertThat(kind(body, "ERD").get("approved_count").asInt()).isZero();
        assertThat(kind(body, "ERD").get("draft_count").asInt()).isEqualTo(1);
    }

    @Test
    void documents_approvedPlusNewDraft_countsBoth() {
        long projectId = project.projectId();
        long base = baseline(projectId, 1);
        long api = document(projectId, "API_SPEC");
        putInBaseline(base, projectId, api, version(api, 1, "APPROVED"));
        currentBaseline(projectId, base);
        version(api, 2, "DRAFT");

        JsonNode body = get(leader, documentsPath(projectId));

        assertThat(kind(body, "API_SPEC").get("approved_count").asInt()).isEqualTo(1);
        assertThat(kind(body, "API_SPEC").get("draft_count").asInt()).isEqualTo(1);
    }

    @Test
    void documents_workingCopyStates_countAsDraftOnce() {
        long projectId = project.projectId();
        long base = baseline(projectId, 1);
        long erd = document(projectId, "ERD");
        long v1 = version(erd, 1, "APPROVED");
        putInBaseline(base, projectId, erd, v1);
        currentBaseline(projectId, base);
        workingCopy(erd, v1, "SUBMITTED");
        // 같은 문서에 DRAFT 버전도 있으면 한 번만 센다
        version(erd, 2, "DRAFT");

        assertThat(kind(get(leader, documentsPath(projectId)), "ERD").get("draft_count").asInt()).isEqualTo(1);

        // 작성 건이 승인된 뒤에는 미승인 작업이 아니다
        db.update("delete from document_version where document_id = ? and status = 'DRAFT'", erd);
        db.update("update document_working_copy set status = 'APPROVED' where document_id = ?", erd);
        assertThat(kind(get(leader, documentsPath(projectId)), "ERD").get("draft_count").asInt()).isZero();

        for (String status : new String[] {"EDITING", "REJECTED"}) {
            db.update("update document_working_copy set status = ? where document_id = ?", status, erd);
            assertThat(kind(get(leader, documentsPath(projectId)), "ERD").get("draft_count").asInt()).as(status).isEqualTo(1);
        }
    }

    @Test
    void documents_oldBaselineDocument_isNotApproved() {
        long projectId = project.projectId();
        long old = baseline(projectId, 1);
        long current = baseline(projectId, 2);
        long plan = document(projectId, "PLAN");
        putInBaseline(old, projectId, plan, version(plan, 1, "SUPERSEDED"));
        currentBaseline(projectId, current);

        JsonNode body = get(leader, documentsPath(projectId));

        assertThat(kind(body, "PLAN").get("approved_count").asInt()).isZero();
        assertThat(body.get("baseline").get("version_no").asInt()).isEqualTo(2);
    }

    @Test
    void documents_ignoresOtherProjects() {
        AccessFixtures.ProjectWithLeader other = fixtures().projectWithLeader();
        long otherBase = baseline(other.projectId(), 1);
        long otherDoc = document(other.projectId(), "PLAN");
        putInBaseline(otherBase, other.projectId(), otherDoc, version(otherDoc, 1, "APPROVED"));
        currentBaseline(other.projectId(), otherBase);

        JsonNode body = get(leader, documentsPath(project.projectId()));

        assertThat(body.has("baseline")).isFalse();
        assertThat(kind(body, "PLAN").get("approved_count").asInt()).isZero();
    }

    // ---- MON-C01 ----

    @Test
    void proposals_noProposals_returnsAllStatusesZero() {
        JsonNode body = get(leader, proposalsPath(project.projectId()));

        assertThat(body.get("by_status")).hasSize(6);
        assertThat(body.get("by_status").get(0).get("status").asText()).isEqualTo("PROPOSED");
        assertThat(body.get("by_status").get(5).get("status").asText()).isEqualTo("WITHDRAWN");
        assertThat(count(body, "PROPOSED")).isZero();
        assertThat(body.get("review_required_count").asInt()).isZero();
    }

    @Test
    void proposals_countsByStatus_reviewRequiredIsProposedPlusInReview() {
        long projectId = project.projectId();
        long base = baseline(projectId, 1);
        proposal(projectId, base, "CP-1", "PROPOSED");
        proposal(projectId, base, "CP-2", "PROPOSED");
        proposal(projectId, base, "CP-3", "IN_REVIEW");
        proposal(projectId, base, "CP-4", "APPROVED");
        proposal(projectId, base, "CP-5", "REJECTED");
        proposal(projectId, base, "CP-6", "WITHDRAWN");

        JsonNode body = get(leader, proposalsPath(projectId));

        assertThat(count(body, "PROPOSED")).isEqualTo(2);
        assertThat(count(body, "IN_REVIEW")).isEqualTo(1);
        assertThat(count(body, "APPROVED")).isEqualTo(1);
        assertThat(count(body, "REJECTED")).isEqualTo(1);
        assertThat(count(body, "PUBLISHED")).isZero();
        // 거둔 제안은 반려에 합치지 않고 따로 센다
        assertThat(count(body, "WITHDRAWN")).isEqualTo(1);
        // 발행 대기(APPROVED)는 팀장 검토가 끝난 건이라 제외
        assertThat(body.get("review_required_count").asInt()).isEqualTo(3);
    }

    @Test
    void proposals_followStatusTransitions() {
        long projectId = project.projectId();
        long base = baseline(projectId, 1);
        proposal(projectId, base, "CP-1", "PROPOSED");
        assertThat(get(leader, proposalsPath(projectId)).get("review_required_count").asInt()).isEqualTo(1);

        db.update("update change_proposal set status = 'IN_REVIEW' where project_id = ?", projectId);
        JsonNode inReview = get(leader, proposalsPath(projectId));
        assertThat(count(inReview, "PROPOSED")).isZero();
        assertThat(count(inReview, "IN_REVIEW")).isEqualTo(1);
        assertThat(inReview.get("review_required_count").asInt()).isEqualTo(1);

        db.update("update change_proposal set status = 'REJECTED' where project_id = ?", projectId);
        JsonNode rejected = get(leader, proposalsPath(projectId));
        assertThat(count(rejected, "REJECTED")).isEqualTo(1);
        assertThat(rejected.get("review_required_count").asInt()).isZero();
    }

    @Test
    void proposals_ignoresOtherProjects() {
        AccessFixtures.ProjectWithLeader other = fixtures().projectWithLeader();
        long otherBase = baseline(other.projectId(), 1);
        db.update("""
                insert into change_proposal(project_id, key, status, base_baseline_id, proposer_kind, proposer_account_id, reason)
                values (?, 'CP-X', 'PROPOSED', ?, 'HUMAN', ?, '사유')
                """, other.projectId(), otherBase, other.leaderAccountId());

        JsonNode body = get(leader, proposalsPath(project.projectId()));

        assertThat(count(body, "PROPOSED")).isZero();
        assertThat(body.get("review_required_count").asInt()).isZero();
    }

    // ---- 권한 ----

    @Test
    void member_canRead() {
        AccessFixtures f = fixtures();
        String email = AccessFixtures.uniqueEmail();
        long account = f.account(email);
        f.member(project.projectId(), account, ProjectRole.MEMBER);
        ApiClient member = client().login(email, AccessFixtures.PASSWORD);

        assertThat(member.get(documentsPath(project.projectId())).statusCode()).isEqualTo(200);
        assertThat(member.get(proposalsPath(project.projectId())).statusCode()).isEqualTo(200);
    }

    @Test
    void outsider_getsNotFound_andAnonymousUnauthenticated() {
        String email = AccessFixtures.uniqueEmail();
        fixtures().account(email);
        ApiClient outsider = client().login(email, AccessFixtures.PASSWORD);

        for (String path : new String[] {documentsPath(project.projectId()), proposalsPath(project.projectId())}) {
            HttpResponse<String> denied = outsider.get(path);
            assertThat(denied.statusCode()).as(path).isEqualTo(404);
            assertThat(JSON.readTree(denied.body()).get("error").get("code").asText()).isEqualTo("not_found");
            assertThat(client().get(path).statusCode()).as(path).isEqualTo(401);
        }
    }

    @Test
    void summaryPath_isNotRoutedAsDocumentId() {
        // /documents/summary 가 /documents/{documentId} 로 가면 400(숫자 아님)이 난다
        HttpResponse<String> res = leader.get(documentsPath(project.projectId()));

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(res.body()).has("by_kind")).isTrue();
    }
}
