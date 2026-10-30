package com.beez.backend.changediscussion;

import static org.assertj.core.api.Assertions.assertThat;

import com.beez.backend.project.ProjectRole;
import com.beez.backend.support.AccessFixtures;
import com.beez.backend.support.ApiClient;
import com.beez.backend.support.IntegrationTestSupport;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 변경 제안 의견·검토 요청 (CHG-A12·A13, S15P31A108-196).
 * 의견은 승인이 아니다(제안 상태 불변, DECISION 입력 불가), 지정 대상은 현재 활성 멤버만, 프로젝트 격리, 멱등 재요청.
 * 알림 전달은 공통 서비스 준비 전이라 여기서 검증하지 않는다.
 */
class ChangeDiscussionIntegrationTests extends IntegrationTestSupport {

    /** 쓰기 요청은 허용된 화면(Origin)에서만 받는다 (195 와 같은 검증) */
    static final String ORIGIN = "http://localhost:5173";

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper json;

    AccessFixtures.ProjectWithLeader project;
    long memberAccount;
    long memberId;
    long proposalId;
    ApiClient leader;
    ApiClient member;

    @BeforeEach
    void setUp() {
        project = fixtures().projectWithLeader();
        memberAccount = fixtures().account();
        memberId = fixtures().member(project.projectId(), memberAccount, ProjectRole.MEMBER);
        proposalId = proposal(project.projectId(), project.leaderAccountId());
        leader = client().login(project.leaderEmail(), AccessFixtures.PASSWORD);
        member = client().login(fixtures().emailOf(memberAccount), AccessFixtures.PASSWORD);
    }

    /** 변경 제안 한 건을 SQL 로 만든다 (제안 생성 API 는 195) */
    private long proposal(long projectId, long account) {
        long baseline = jdbc.queryForObject(
                "INSERT INTO baseline (project_id, version_no, published_by) VALUES (?, 1, ?) RETURNING id",
                Long.class, projectId, account);
        long id = jdbc.queryForObject("""
                INSERT INTO change_proposal (project_id, key, status, base_baseline_id, proposer_kind, proposer_account_id, reason)
                VALUES (?, 'CP-1', 'IN_REVIEW', ?, 'HUMAN', ?, '사유') RETURNING id
                """, Long.class, projectId, baseline, account);
        revision(id, projectId, baseline, 1, account);
        return id;
    }

    /** 195 의 불변 제출 리비전 한 줄 (V10). 제출 API 는 195 라 SQL 로 만든다 */
    private void revision(long proposal, long projectId, long baseline, int no, long account) {
        jdbc.update("""
                INSERT INTO change_proposal_revision (change_proposal_id, project_id, revision_no, base_baseline_id,
                                                      snapshot, snapshot_hash, submitted_by)
                VALUES (?, ?, ?, ?, '{}'::jsonb, 'sha256:' || repeat('0', 64), ?)
                """, proposal, projectId, no, baseline, account);
    }

    /** 반려 뒤 재제출을 SQL 로 흉내 낸다: 새 리비전을 저장하고 제안을 PROPOSED/다음 번호로 */
    private void resubmit(int nextNo) {
        long baseline = jdbc.queryForObject("SELECT base_baseline_id FROM change_proposal WHERE id = ?", Long.class, proposalId);
        jdbc.update("UPDATE change_proposal SET status = 'REJECTED' WHERE id = ?", proposalId);
        revision(proposalId, project.projectId(), baseline, nextNo, project.leaderAccountId());
        jdbc.update("UPDATE change_proposal SET status = 'PROPOSED', revision_no = ? WHERE id = ?", nextNo, proposalId);
    }

    private HttpResponse<String> post(ApiClient who, String path, String json, String... headers) {
        String[] all = new String[headers.length + 2];
        all[0] = "Origin";
        all[1] = ORIGIN;
        System.arraycopy(headers, 0, all, 2, headers.length);
        return who.post(path, json, all);
    }

    private String path() {
        return path(project.projectId(), proposalId);
    }

    private String path(long projectId, long proposal) {
        return "/api/projects/" + projectId + "/change-proposals/" + proposal + "/comments";
    }

    private JsonNode body(HttpResponse<String> res) throws Exception {
        return json.readTree(res.body());
    }

    private String comment(String kind, String text, Long... mentionedMembers) {
        StringBuilder ids = new StringBuilder();
        for (Long m : mentionedMembers) {
            ids.append(ids.length() == 0 ? "" : ",").append('"').append(m).append('"');
        }
        return "{\"body\":\"" + text + "\",\"kind\":\"" + kind + "\",\"mentioned_member_ids\":[" + ids + "]}";
    }

    private long commentCount() {
        return jdbc.queryForObject("SELECT count(*) FROM change_comment WHERE change_proposal_id = ?", Long.class, proposalId);
    }

    // ---- 등록 ----

    @Test
    void member_postsComment_andListShowsIt() throws Exception {
        HttpResponse<String> created = post(member, path(), "{\"body\":\"프론트도 사용합니다\"}");

        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode c = body(created);
        assertThat(c.get("kind").asString()).isEqualTo("COMMENT");
        assertThat(c.get("author_account_id").asString()).isEqualTo(String.valueOf(memberAccount));
        assertThat(c.get("proposal_id").asString()).isEqualTo(String.valueOf(proposalId));
        assertThat(c.get("mentioned_member_ids")).isEmpty();

        JsonNode list = body(leader.get(path()));
        assertThat(list.get("items")).hasSize(1);
        assertThat(list.get("items").get(0).get("id").asString()).isEqualTo(c.get("id").asString());
    }

    @Test
    void reviewRequest_savesActiveMemberTargets() throws Exception {
        HttpResponse<String> created = post(member, path(), comment("REVIEW_REQUEST", "검토 부탁드립니다", project.leaderMemberId()));

        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode c = body(created);
        assertThat(c.get("kind").asString()).isEqualTo("REVIEW_REQUEST");
        assertThat(c.get("mentioned_member_ids").get(0).asString()).isEqualTo(String.valueOf(project.leaderMemberId()));
    }

    @Test
    void comment_doesNotChangeProposalOrCreateApproval() {
        post(member, path(), comment("COMMENT", "의견", project.leaderMemberId()));
        post(member, path(), comment("REVIEW_REQUEST", "검토", project.leaderMemberId()));

        assertThat(jdbc.queryForMap("SELECT status, lock_version FROM change_proposal WHERE id = ?", proposalId))
                .containsEntry("status", "IN_REVIEW").containsEntry("lock_version", 0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM approval WHERE change_proposal_id = ?", Long.class, proposalId)).isZero();
    }

    @Test
    void invalidInput_isRejected_andNothingSaved() {
        assertThat(post(member, path(), "{\"body\":\"   \"}").statusCode()).isEqualTo(400);
        assertThat(post(member, path(), "{\"body\":\"x\",\"kind\":\"DECISION\"}").statusCode()).isEqualTo(400);
        assertThat(post(member, path(), "{\"body\":\"x\",\"kind\":\"APPROVED\"}").statusCode()).isEqualTo(400);
        // 검토 요청은 대상이 있어야 한다
        assertThat(post(member, path(), comment("REVIEW_REQUEST", "검토", new Long[0])).statusCode()).isEqualTo(400);
        assertThat(post(member, path(), "{\"body\":\"x\",\"mentioned_member_ids\":[\"abc\"]}").statusCode()).isEqualTo(400);

        assertThat(commentCount()).isZero();
    }

    @Test
    void targets_mustBeCurrentActiveMembersOfThisProject() {
        long gone = fixtures().account();
        long goneMember = fixtures().member(project.projectId(), gone, ProjectRole.MEMBER);
        fixtures().removeMember(goneMember);
        var other = fixtures().projectWithLeader();

        // 제외된 멤버, 다른 프로젝트의 멤버, 없는 멤버
        assertThat(post(member, path(), comment("REVIEW_REQUEST", "검토", goneMember)).statusCode()).isEqualTo(400);
        assertThat(post(member, path(), comment("REVIEW_REQUEST", "검토", other.leaderMemberId())).statusCode()).isEqualTo(400);
        assertThat(post(member, path(), comment("COMMENT", "의견", 999999L)).statusCode()).isEqualTo(400);
        // 유효한 대상과 섞어도 전체가 거절된다
        assertThat(post(member, path(), comment("REVIEW_REQUEST", "검토", project.leaderMemberId(), goneMember)).statusCode())
                .isEqualTo(400);

        assertThat(commentCount()).isZero();
    }

    // ---- 리비전·상태 ----

    @Test
    void comment_isBoundToCurrentRevision_andOldRevisionCommentsStayAfterResubmit() throws Exception {
        JsonNode first = body(post(member, path(), "{\"body\":\"리비전1 의견\"}"));
        resubmit(2);
        JsonNode second = body(post(member, path(), "{\"body\":\"리비전2 의견\"}"));

        assertThat(first.get("revision").asInt()).isEqualTo(1);
        assertThat(second.get("revision").asInt()).isEqualTo(2);
        // 전체 목록에는 둘 다, revision 필터는 해당 리비전만
        assertThat(body(leader.get(path())).get("items")).hasSize(2);
        JsonNode only1 = body(leader.get(path() + "?revision=1"));
        assertThat(only1.get("items")).hasSize(1);
        assertThat(only1.get("items").get(0).get("body").asString()).isEqualTo("리비전1 의견");
        assertThat(body(leader.get(path() + "?revision=3")).get("items")).isEmpty();
        assertThat(leader.get(path() + "?revision=0").statusCode()).isEqualTo(400);
    }

    @Test
    void write_withoutTrustedOrigin_isRejected() {
        assertThat(member.post(path(), "{\"body\":\"x\"}").statusCode()).isEqualTo(403);
        assertThat(member.post(path(), "{\"body\":\"x\"}", "Origin", "https://evil.example").statusCode()).isEqualTo(403);
        assertThat(commentCount()).isZero();
    }

    @Test
    void sameKeyRetryAfterResubmit_returnsOriginalComment() throws Exception {
        JsonNode first = body(post(member, path(), "{\"body\":\"재시도\"}", "Idempotency-Key", "k-rev"));
        resubmit(2);

        JsonNode again = body(post(member, path(), "{\"body\":\"재시도\"}", "Idempotency-Key", "k-rev"));

        assertThat(again.get("id").asString()).isEqualTo(first.get("id").asString());
        assertThat(again.get("revision").asInt()).isEqualTo(1);
        assertThat(commentCount()).isEqualTo(1);
    }

    @Test
    void openStatuses_acceptComments_closedStatuses_rejectButStillReadable() throws Exception {
        for (String status : List.of("PROPOSED", "APPROVED", "REJECTED")) {
            jdbc.update("UPDATE change_proposal SET status = ? WHERE id = ?", status, proposalId);
            assertThat(post(member, path(), "{\"body\":\"" + status + "\"}").statusCode()).as(status).isEqualTo(201);
        }
        long before = commentCount();

        // 거둔 제안 (V10: 제출·검토 중인 제안만 거둘 수 있다)
        jdbc.update("UPDATE change_proposal SET status = 'IN_REVIEW' WHERE id = ?", proposalId);
        jdbc.update("UPDATE change_proposal SET status = 'WITHDRAWN', withdrawn_by = proposer_account_id, withdrawn_at = now() WHERE id = ?", proposalId);
        HttpResponse<String> withdrawn = post(member, path(), "{\"body\":\"거둔 뒤\"}");
        assertThat(withdrawn.statusCode()).isEqualTo(409);
        assertThat(body(withdrawn).get("error").get("code").asString()).isEqualTo("invalid_transition");
        assertThat(leader.get(path()).statusCode()).isEqualTo(200);
        assertThat(commentCount()).isEqualTo(before);
    }

    @Test
    void publishedProposal_rejectsComments() throws Exception {
        long baseline = jdbc.queryForObject("SELECT base_baseline_id FROM change_proposal WHERE id = ?", Long.class, proposalId);
        jdbc.update("UPDATE change_proposal SET status = 'PUBLISHED', published_baseline_id = ? WHERE id = ?", baseline, proposalId);

        HttpResponse<String> res = post(member, path(), "{\"body\":\"발행 뒤\"}");

        assertThat(res.statusCode()).isEqualTo(409);
        assertThat(body(res).get("error").get("code").asString()).isEqualTo("invalid_transition");
        assertThat(commentCount()).isZero();
    }

    // ---- 격리·권한 ----

    @Test
    void nonMember_otherProjectProposal_andAnonymous_areDenied() {
        var other = fixtures().projectWithLeader();
        ApiClient outsider = client().login(other.leaderEmail(), AccessFixtures.PASSWORD);
        long otherProposal = proposal(other.projectId(), other.leaderAccountId());

        // 비멤버
        assertThat(post(outsider, path(), "{\"body\":\"x\"}").statusCode()).isEqualTo(404);
        assertThat(outsider.get(path()).statusCode()).isEqualTo(404);
        // 내 프로젝트 경로에 다른 프로젝트의 제안 번호
        assertThat(post(member, path(project.projectId(), otherProposal), "{\"body\":\"x\"}").statusCode()).isEqualTo(404);
        assertThat(member.get(path(project.projectId(), otherProposal)).statusCode()).isEqualTo(404);
        // 없는 제안
        assertThat(member.get(path(project.projectId(), 999999L)).statusCode()).isEqualTo(404);
        // 비로그인
        assertThat(client().post(path(), "{\"body\":\"x\"}").statusCode()).isEqualTo(401);
        assertThat(client().get(path()).statusCode()).isEqualTo(401);

        assertThat(commentCount()).isZero();
    }

    @Test
    void removedMember_cannotCommentAnymore() {
        fixtures().removeMember(memberId);

        assertThat(post(member, path(), "{\"body\":\"x\"}").statusCode()).isEqualTo(404);
        assertThat(member.get(path()).statusCode()).isEqualTo(404);
    }

    // ---- 멱등 ----

    @Test
    void sameKeySameInput_returnsFirstComment_withoutDuplicate() throws Exception {
        String req = comment("REVIEW_REQUEST", "검토", project.leaderMemberId());

        JsonNode first = body(post(member, path(), req, "Idempotency-Key", "k-1"));
        HttpResponse<String> again = post(member, path(), req, "Idempotency-Key", "k-1");

        assertThat(again.statusCode()).isEqualTo(201);
        assertThat(body(again).get("id").asString()).isEqualTo(first.get("id").asString());
        assertThat(commentCount()).isEqualTo(1);
    }

    @Test
    void sameKeyDifferentInput_conflicts_andKeyIsPerAuthor() throws Exception {
        post(member, path(), "{\"body\":\"처음\"}", "Idempotency-Key", "k-2");

        HttpResponse<String> changed = post(member, path(), "{\"body\":\"다른 내용\"}", "Idempotency-Key", "k-2");
        assertThat(changed.statusCode()).isEqualTo(409);
        assertThat(body(changed).get("error").get("code").asString()).isEqualTo("idempotency_conflict");

        // 다른 작성자의 같은 키는 별개 요청
        assertThat(post(leader, path(), "{\"body\":\"다른 내용\"}", "Idempotency-Key", "k-2").statusCode()).isEqualTo(201);
        assertThat(commentCount()).isEqualTo(2);
    }

    @Test
    void concurrentRetriesWithSameKey_saveOnce() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Integer>> calls = java.util.stream.IntStream.range(0, 4)
                    .<Callable<Integer>>mapToObj(i -> () -> post(member, path(), "{\"body\":\"동시\"}", "Idempotency-Key", "k-3").statusCode())
                    .toList();
            for (Future<Integer> f : pool.invokeAll(calls)) {
                assertThat(f.get()).isEqualTo(201);
            }
        } finally {
            pool.shutdown();
        }

        assertThat(commentCount()).isEqualTo(1);
    }

    @Test
    void sameKeyRetryAfterPublished_returnsOriginalComment() throws Exception {
        JsonNode first = body(post(member, path(), "{\"body\":\"발행 전\"}", "Idempotency-Key", "k-pub"));
        long baseline = jdbc.queryForObject("SELECT base_baseline_id FROM change_proposal WHERE id = ?", Long.class, proposalId);
        jdbc.update("UPDATE change_proposal SET status = 'PUBLISHED', published_baseline_id = ? WHERE id = ?", baseline, proposalId);

        HttpResponse<String> again = post(member, path(), "{\"body\":\"발행 전\"}", "Idempotency-Key", "k-pub");

        assertThat(again.statusCode()).isEqualTo(201);
        assertThat(body(again).get("id").asString()).isEqualTo(first.get("id").asString());
        assertThat(commentCount()).isEqualTo(1);
        // 새 키는 종결 상태 검사를 받는다
        assertThat(post(member, path(), "{\"body\":\"발행 전\"}", "Idempotency-Key", "k-pub-2").statusCode()).isEqualTo(409);
    }

    @Test
    void sameKeyRetryAfterWithdrawn_returnsOriginalComment() throws Exception {
        JsonNode first = body(post(member, path(), "{\"body\":\"거두기 전\"}", "Idempotency-Key", "k-wd"));
        jdbc.update("UPDATE change_proposal SET status = 'WITHDRAWN', withdrawn_by = proposer_account_id, withdrawn_at = now() WHERE id = ?", proposalId);

        HttpResponse<String> again = post(member, path(), "{\"body\":\"거두기 전\"}", "Idempotency-Key", "k-wd");

        assertThat(again.statusCode()).isEqualTo(201);
        assertThat(body(again).get("id").asString()).isEqualTo(first.get("id").asString());
        assertThat(commentCount()).isEqualTo(1);
    }

    @Test
    void sameKeyWithReorderedMentions_isSameInput() throws Exception {
        long leaderMember = project.leaderMemberId();
        JsonNode first = body(post(member, path(), comment("REVIEW_REQUEST", "순서", leaderMember, memberId), "Idempotency-Key", "k-ord"));

        HttpResponse<String> again = post(member, path(), comment("REVIEW_REQUEST", "순서", memberId, leaderMember), "Idempotency-Key", "k-ord");

        assertThat(again.statusCode()).isEqualTo(201);
        assertThat(body(again).get("id").asString()).isEqualTo(first.get("id").asString());
        assertThat(commentCount()).isEqualTo(1);
    }

    @Test
    void sameKeyOnAnotherProposal_isSeparateRequest() throws Exception {
        long baseline = jdbc.queryForObject("SELECT base_baseline_id FROM change_proposal WHERE id = ?", Long.class, proposalId);
        long other = jdbc.queryForObject("""
                INSERT INTO change_proposal (project_id, key, status, base_baseline_id, proposer_kind, proposer_account_id, reason)
                VALUES (?, 'CP-2', 'IN_REVIEW', ?, 'HUMAN', ?, '사유') RETURNING id
                """, Long.class, project.projectId(), baseline, project.leaderAccountId());
        revision(other, project.projectId(), baseline, 1, project.leaderAccountId());

        assertThat(post(member, path(), "{\"body\":\"같은 키\"}", "Idempotency-Key", "k-sep").statusCode()).isEqualTo(201);
        assertThat(post(member, path(project.projectId(), other), "{\"body\":\"같은 키\"}", "Idempotency-Key", "k-sep").statusCode())
                .isEqualTo(201);
        assertThat(commentCount()).isEqualTo(1);
    }

    @Test
    void keyIsStoredInSharedCommandStore_notInCommentTable() {
        post(member, path(), "{\"body\":\"저장\"}", "Idempotency-Key", "k-store");

        long stored = jdbc.queryForObject(
                "SELECT count(*) FROM design_http_command WHERE request_key = 'k-store' AND operation LIKE ?", Long.class,
                "proposal:comment:" + proposalId + ":%");
        long columns = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                 WHERE table_name = 'change_comment' AND column_name IN ('idempotency_key', 'request_hash')
                """, Long.class);
        assertThat(stored).isEqualTo(1);
        assertThat(columns).isZero();
    }

    @Test
    void invalidIdempotencyKey_isRejectedWith400_notServerError() {
        assertThat(post(member, path(), "{\"body\":\"x\"}", "Idempotency-Key", "k".repeat(129)).statusCode()).isEqualTo(400);
        assertThat(post(member, path(), "{\"body\":\"x\"}", "Idempotency-Key", "k".repeat(3000)).statusCode()).isEqualTo(400);
        assertThat(commentCount()).isZero();
    }

    @Test
    void untrustedOrigin_winsOverInvalidBody() {
        // 본문이 잘못되어도 Origin 검사가 먼저라 403 이다
        assertThat(member.post(path(), "{\"body\":\"\"}", "Origin", "https://evil.example").statusCode()).isEqualTo(403);
        assertThat(member.post(path(), "{}", "Origin", "https://evil.example").statusCode()).isEqualTo(403);
        // 신뢰하는 Origin 이면 같은 본문은 400
        assertThat(post(member, path(), "{\"body\":\"\"}").statusCode()).isEqualTo(400);
        assertThat(post(member, path(), "{}").statusCode()).isEqualTo(400);
        assertThat(post(member, path(), "{\"body\":\"" + "가".repeat(4001) + "\"}").statusCode()).isEqualTo(400);
        assertThat(commentCount()).isZero();
    }

    // ---- 목록 ----

    @Test
    void list_isOrderedByWriteOrder_pagesStably_andHidesDecisionRows() throws Exception {
        post(member, path(), "{\"body\":\"하나\"}");
        // 승인·반려 결정은 approval 이 원본이다. 의견 목록에 섞여 나오지 않는다
        jdbc.update("INSERT INTO change_comment (change_proposal_id, proposal_revision_no, author_account_id, kind, body) VALUES (?, 1, ?, 'DECISION', '결정')",
                proposalId, project.leaderAccountId());
        post(leader, path(), "{\"body\":\"둘\"}");
        post(member, path(), "{\"body\":\"셋\"}");

        JsonNode page1 = body(leader.get(path() + "?limit=2"));
        assertThat(page1.get("items")).hasSize(2);
        assertThat(page1.get("items").get(0).get("body").asString()).isEqualTo("하나");
        assertThat(page1.get("items").get(1).get("body").asString()).isEqualTo("둘");
        assertThat(page1.get("has_more").asBoolean()).isTrue();

        // 첫 페이지를 읽은 뒤 새 의견이 들어와도 다음 페이지가 밀리거나 중복되지 않는다
        post(member, path(), "{\"body\":\"넷\"}");
        JsonNode page2 = body(leader.get(path() + "?limit=2&cursor=" + page1.get("next_cursor").asString()));
        assertThat(page2.get("items").get(0).get("body").asString()).isEqualTo("셋");
        assertThat(page2.get("items").get(1).get("body").asString()).isEqualTo("넷");
        assertThat(page2.get("has_more").asBoolean()).isFalse();
        assertThat(page2.has("next_cursor")).isFalse();

        assertThat(leader.get(path() + "?cursor=abc").statusCode()).isEqualTo(400);
    }
}
