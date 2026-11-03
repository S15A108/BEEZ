package com.beez.backend.meeting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.beez.backend.common.api.ApiException;
import com.beez.backend.meeting.dto.MeetingRef;
import com.beez.backend.meeting.service.MeetingOpenService;
import com.beez.backend.meeting.service.MeetingOpenService.ConflictMeetingCommand;
import com.beez.backend.meeting.service.MeetingOpenService.OpenResult;
import com.beez.backend.meeting.service.MeetingOpenService.ParticipantSeed;
import com.beez.backend.project.ProjectRole;
import com.beez.backend.support.AccessFixtures;
import com.beez.backend.support.ApiClient;
import com.beez.backend.support.IntegrationTestSupport;
import java.net.http.HttpResponse;
import java.util.ArrayList;
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
 * 회의 조회·참여자·기록과 충돌 회의 내부 개설 (MTG-A02·A03·A04·A06·A10·A14, S15P31A108-205).
 * 충돌 감지 연결과 알림 전달은 후속 티켓이라 여기서 검증하지 않는다.
 */
class MeetingIntegrationTests extends IntegrationTestSupport {

    static final String ORIGIN = "http://localhost:5173";

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper json;

    @Autowired
    MeetingOpenService opener;

    AccessFixtures.ProjectWithLeader project;
    long ownerAccount;
    long ownerMember;
    long otherAccount;
    long otherMember;
    long taskA;
    long taskB;
    ApiClient leader;
    ApiClient owner;
    ApiClient other;

    @BeforeEach
    void setUp() {
        project = fixtures().projectWithLeader();
        publishBaseline(project.projectId(), project.leaderAccountId());
        ownerAccount = fixtures().account();
        ownerMember = fixtures().member(project.projectId(), ownerAccount, ProjectRole.MEMBER);
        otherAccount = fixtures().account();
        otherMember = fixtures().member(project.projectId(), otherAccount, ProjectRole.MEMBER);
        taskA = task(project.projectId(), "T-1", ownerMember);
        taskB = task(project.projectId(), "T-2", null);
        leader = client().login(project.leaderEmail(), AccessFixtures.PASSWORD);
        owner = client().login(fixtures().emailOf(ownerAccount), AccessFixtures.PASSWORD);
        other = client().login(fixtures().emailOf(otherAccount), AccessFixtures.PASSWORD);
    }

    private void publishBaseline(long projectId, long account) {
        long baseline = jdbc.queryForObject(
                "INSERT INTO baseline (project_id, version_no, published_by) VALUES (?, 1, ?) RETURNING id",
                Long.class, projectId, account);
        jdbc.update("UPDATE project SET current_baseline_id = ? WHERE id = ?", baseline, projectId);
    }

    private long task(long projectId, String key, Long assigneeMember) {
        return jdbc.queryForObject("""
                INSERT INTO task (project_id, key, title, assignee_member_id, status)
                VALUES (?, ?, '작업', ?, 'DRAFT') RETURNING id
                """, Long.class, projectId, key, assigneeMember);
    }

    private OpenResult open(long projectId, List<Long> tasks, ParticipantSeed... seeds) {
        return opener.openForConflict(new ConflictMeetingCommand(projectId, tasks, "로그인 응답 계약이 두 작업에서 충돌",
                "두 작업이 같은 응답 필드를 다르게 바꾸려 합니다.", List.of(new MeetingRef("TASK", String.valueOf(taskA), "T-1")),
                List.of(seeds)));
    }

    private long openMeeting() {
        return Long.parseLong(open(project.projectId(), List.of(taskB, taskA)).meeting().id());
    }

    private String base() {
        return "/api/projects/" + project.projectId() + "/meetings";
    }

    private HttpResponse<String> post(ApiClient who, String path, String body, String... headers) {
        String[] all = new String[headers.length + 2];
        all[0] = "Origin";
        all[1] = ORIGIN;
        System.arraycopy(headers, 0, all, 2, headers.length);
        return who.post(path, body, all);
    }

    private HttpResponse<String> remove(ApiClient who, String path) {
        return who.delete(path, "", "Origin", ORIGIN);
    }

    private JsonNode body(HttpResponse<String> res) throws Exception {
        return json.readTree(res.body());
    }

    private String add(Long... members) {
        StringBuilder ids = new StringBuilder();
        for (Long m : members) {
            ids.append(ids.length() == 0 ? "" : ",").append('"').append(m).append('"');
        }
        return "{\"member_ids\":[" + ids + "],\"reason\":\"같이 봐 주세요\"}";
    }

    private long participantCount(long meeting) {
        return jdbc.queryForObject("SELECT count(*) FROM meeting_participant WHERE meeting_id = ?", Long.class, meeting);
    }

    private void close(long meeting) {
        jdbc.update("UPDATE meeting SET status = 'CLOSED', closed_at = now(), closed_by = ? WHERE id = ?",
                project.leaderAccountId(), meeting);
    }

    // ---- 개설 ----

    @Test
    void open_addsLeaderAndTaskOwners_andWritesBriefAsFirstEntry() throws Exception {
        OpenResult result = open(project.projectId(), List.of(taskB, taskA));

        assertThat(result.created()).isTrue();
        var meeting = result.meeting();
        assertThat(meeting.origin()).isEqualTo("BEE_CONFLICT");
        assertThat(meeting.status()).isEqualTo("OPEN");
        assertThat(meeting.taskIds()).containsExactly(String.valueOf(taskA), String.valueOf(taskB));
        assertThat(meeting.participants()).extracting(p -> p.memberId() + ":" + p.role() + ":" + p.status())
                .containsExactlyInAnyOrder(project.leaderMemberId() + ":LEADER:JOINED", ownerMember + ":TASK_OWNER:JOINED");

        JsonNode entries = body(leader.get(base() + "/" + meeting.id() + "/entries"));
        assertThat(entries.get("items")).hasSize(1);
        JsonNode brief = entries.get("items").get(0);
        assertThat(brief.get("kind").asString()).isEqualTo("CONFLICT_BRIEF");
        assertThat(brief.get("author_kind").asString()).isEqualTo("BEE");
        assertThat(brief.has("author_account_id")).isFalse();
        assertThat(brief.get("refs").get(0).get("type").asString()).isEqualTo("TASK");
    }

    @Test
    void open_sameTaskSet_returnsExistingMeeting_differentSetCreatesNew_closedAllowsNew() {
        OpenResult first = open(project.projectId(), List.of(taskA, taskB));
        OpenResult again = open(project.projectId(), List.of(taskB, taskA));
        long taskC = task(project.projectId(), "T-3", null);
        OpenResult subset = open(project.projectId(), List.of(taskA, taskC));

        assertThat(again.created()).isFalse();
        assertThat(again.meeting().id()).isEqualTo(first.meeting().id());
        assertThat(subset.created()).isTrue();
        assertThat(subset.meeting().id()).isNotEqualTo(first.meeting().id());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM meeting_entry WHERE meeting_id = ?", Long.class,
                Long.parseLong(first.meeting().id()))).isEqualTo(1L);

        close(Long.parseLong(first.meeting().id()));
        OpenResult reopened = open(project.projectId(), List.of(taskA, taskB));
        assertThat(reopened.created()).isTrue();
        assertThat(reopened.meeting().id()).isNotEqualTo(first.meeting().id());
    }

    @Test
    void open_extraParticipants_dedupedAndOnlyActiveMembers() {
        long gone = fixtures().account();
        long goneMember = fixtures().member(project.projectId(), gone, ProjectRole.MEMBER);
        fixtures().removeMember(goneMember);

        OpenResult result = open(project.projectId(), List.of(taskA),
                new ParticipantSeed(otherMember, MeetingOpenService.ROLE_DOMAIN_OWNER),
                new ParticipantSeed(ownerMember, MeetingOpenService.ROLE_DOMAIN_OWNER),
                new ParticipantSeed(goneMember, MeetingOpenService.ROLE_DOMAIN_OWNER));

        assertThat(result.meeting().participants()).extracting(p -> p.memberId() + ":" + p.role())
                .containsExactlyInAnyOrder(project.leaderMemberId() + ":LEADER", ownerMember + ":TASK_OWNER",
                        otherMember + ":DOMAIN_OWNER");
    }

    @Test
    void open_invalidInput_isRejected() {
        var otherProject = fixtures().projectWithLeader();
        long foreignTask = task(otherProject.projectId(), "T-9", null);
        long noBaselineProject = fixtures().projectWithLeader().projectId();

        assertThatThrownBy(() -> open(project.projectId(), List.of()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("validation_failed"));
        assertThatThrownBy(() -> open(project.projectId(), List.of(taskA, foreignTask)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("validation_failed"));
        assertThatThrownBy(() -> open(project.projectId(), List.of(999999L)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("validation_failed"));
        assertThatThrownBy(() -> open(noBaselineProject, List.of(taskA)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("baseline_missing"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM meeting WHERE project_id = ?", Long.class, project.projectId()))
                .isZero();
    }

    @Test
    void open_concurrentSameTaskSet_createsOneMeeting() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<OpenResult>> calls = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                calls.add(() -> open(project.projectId(), List.of(taskA, taskB)));
            }
            int created = 0;
            for (Future<OpenResult> f : pool.invokeAll(calls)) {
                created += f.get().created() ? 1 : 0;
            }
            assertThat(created).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM meeting WHERE project_id = ?", Long.class,
                    project.projectId())).isEqualTo(1L);
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- 기준 v1 전 ----

    @Test
    void beforeBaseline_everyApiIs409_andNonMemberStays404() {
        var fresh = fixtures().projectWithLeader();
        ApiClient freshLeader = client().login(fresh.leaderEmail(), AccessFixtures.PASSWORD);
        String path = "/api/projects/" + fresh.projectId() + "/meetings";

        assertThat(freshLeader.get(path).statusCode()).isEqualTo(409);
        assertThat(freshLeader.get(path + "/1").statusCode()).isEqualTo(409);
        assertThat(freshLeader.get(path + "/1/entries").statusCode()).isEqualTo(409);
        assertThat(post(freshLeader, path + "/1/entries", "{\"text\":\"x\"}").statusCode()).isEqualTo(409);
        assertThat(post(freshLeader, path + "/1/participants", add(fresh.leaderMemberId())).statusCode()).isEqualTo(409);
        assertThat(remove(freshLeader, path + "/1/participants/" + fresh.leaderMemberId()).statusCode()).isEqualTo(409);
        // 비멤버는 상태를 알 수 없다
        assertThat(other.get(path).statusCode()).isEqualTo(404);
    }

    // ---- 목록·상세 ----

    @Test
    void list_filtersByStatus_pagesWithoutGapsOrDuplicates() throws Exception {
        long taskC = task(project.projectId(), "T-3", null);
        long m1 = Long.parseLong(open(project.projectId(), List.of(taskA)).meeting().id());
        long m2 = Long.parseLong(open(project.projectId(), List.of(taskB)).meeting().id());
        long m3 = Long.parseLong(open(project.projectId(), List.of(taskC)).meeting().id());
        close(m2);

        JsonNode closed = body(other.get(base() + "?status=CLOSED"));
        assertThat(closed.get("items")).hasSize(1);
        assertThat(closed.get("items").get(0).get("id").asString()).isEqualTo(String.valueOf(m2));
        assertThat(closed.get("items").get(0).get("closed_by").asString()).isEqualTo(String.valueOf(project.leaderAccountId()));
        assertThat(body(other.get(base() + "?status=OPEN")).get("items")).hasSize(2);
        assertThat(other.get(base() + "?status=ALL").statusCode()).isEqualTo(400);

        List<String> seen = new ArrayList<>();
        String cursor = null;
        do {
            JsonNode page = body(other.get(base() + "?limit=1" + (cursor == null ? "" : "&cursor=" + cursor)));
            assertThat(page.get("items")).hasSize(1);
            seen.add(page.get("items").get(0).get("id").asString());
            cursor = page.get("has_more").asBoolean() ? page.get("next_cursor").asString() : null;
        } while (cursor != null);
        assertThat(seen).containsExactly(String.valueOf(m3), String.valueOf(m2), String.valueOf(m1));
    }

    @Test
    void get_returnsMeeting_andOtherProjectMeetingIs404() throws Exception {
        long meeting = openMeeting();
        JsonNode got = body(other.get(base() + "/" + meeting));

        assertThat(got.get("origin").asString()).isEqualTo("BEE_CONFLICT");
        assertThat(got.get("task_ids")).hasSize(2);
        assertThat(got.get("conflict_summary").asString()).startsWith("로그인 응답");
        assertThat(got.get("participants")).hasSize(2);

        var elsewhere = fixtures().projectWithLeader();
        publishBaseline(elsewhere.projectId(), elsewhere.leaderAccountId());
        ApiClient foreign = client().login(elsewhere.leaderEmail(), AccessFixtures.PASSWORD);
        assertThat(foreign.get("/api/projects/" + elsewhere.projectId() + "/meetings/" + meeting).statusCode()).isEqualTo(404);
        assertThat(leader.get("/api/projects/" + elsewhere.projectId() + "/meetings/" + meeting).statusCode()).isEqualTo(404);
        assertThat(leader.get(base() + "/abc").statusCode()).isEqualTo(400);
    }

    // ---- 참여자 추가 ----

    @Test
    void addParticipants_leaderAndParticipantAllowed_nonParticipantForbidden() throws Exception {
        long meeting = openMeeting();

        // 참여하지 않은 팀원
        assertThat(post(other, base() + "/" + meeting + "/participants", add(otherMember)).statusCode()).isEqualTo(403);
        // 참여자(작업 담당자)가 다른 팀원을 부른다
        HttpResponse<String> byOwner = post(owner, base() + "/" + meeting + "/participants", add(otherMember));
        assertThat(byOwner.statusCode()).isEqualTo(200);
        JsonNode added = null;
        for (JsonNode p : body(byOwner).get("participants")) {
            if (p.get("member_id").asString().equals(String.valueOf(otherMember))) {
                added = p;
            }
        }
        assertThat(added).isNotNull();
        assertThat(added.get("status").asString()).isEqualTo("JOINED");
        assertThat(added.has("role")).isFalse();
        assertThat(added.get("added_by_member_id").asString()).isEqualTo(String.valueOf(ownerMember));
        // 이제 참여자가 된 팀원이 기록을 남길 수 있다
        assertThat(post(other, base() + "/" + meeting + "/entries", "{\"text\":\"합류했습니다\"}").statusCode()).isEqualTo(201);
    }

    @Test
    void addParticipants_duplicatesSkipped_invalidMembersRejected_closedMeetingConflict() {
        long meeting = openMeeting();
        long before = participantCount(meeting);
        long gone = fixtures().account();
        long goneMember = fixtures().member(project.projectId(), gone, ProjectRole.MEMBER);
        fixtures().removeMember(goneMember);
        var elsewhere = fixtures().projectWithLeader();

        // 이미 참여 중인 사람과 새 사람 — 중복 행 없이 새 사람만 늘어난다
        assertThat(post(leader, base() + "/" + meeting + "/participants", add(ownerMember, otherMember, otherMember)).statusCode())
                .isEqualTo(200);
        assertThat(participantCount(meeting)).isEqualTo(before + 1);
        // 제외된 멤버, 다른 프로젝트 멤버, 없는 멤버, 빈 목록, 형식 오류는 전체 거절
        assertThat(post(leader, base() + "/" + meeting + "/participants", add(goneMember)).statusCode()).isEqualTo(400);
        assertThat(post(leader, base() + "/" + meeting + "/participants", add(elsewhere.leaderMemberId())).statusCode()).isEqualTo(400);
        assertThat(post(leader, base() + "/" + meeting + "/participants", add(999999L)).statusCode()).isEqualTo(400);
        assertThat(post(leader, base() + "/" + meeting + "/participants", "{\"member_ids\":[]}").statusCode()).isEqualTo(400);
        assertThat(post(leader, base() + "/" + meeting + "/participants", "{\"member_ids\":[\"x\"]}").statusCode()).isEqualTo(400);

        close(meeting);
        assertThat(post(leader, base() + "/" + meeting + "/participants", add(otherMember)).statusCode()).isEqualTo(409);
    }

    @Test
    void addParticipants_securityChecks() {
        long meeting = openMeeting();

        assertThat(leader.post(base() + "/" + meeting + "/participants", add(otherMember)).statusCode()).isEqualTo(403);
        assertThat(leader.post(base() + "/" + meeting + "/participants", add(otherMember), "Origin", "http://evil.example")
                .statusCode()).isEqualTo(403);
        assertThat(participantCount(meeting)).isEqualTo(2);
    }

    // ---- 참여자 제외 ----

    @Test
    void removeParticipant_leaderOnly_idempotent_leaderProtected() {
        long meeting = openMeeting();
        String path = base() + "/" + meeting + "/participants/" + ownerMember;

        assertThat(remove(owner, path).statusCode()).isEqualTo(403);
        assertThat(remove(leader, base() + "/" + meeting + "/participants/" + project.leaderMemberId()).statusCode()).isEqualTo(409);
        assertThat(remove(leader, path).statusCode()).isEqualTo(204);
        assertThat(participantCount(meeting)).isEqualTo(1);
        // 이미 빠진 사람, 처음부터 참여자가 아닌 멤버는 204
        assertThat(remove(leader, path).statusCode()).isEqualTo(204);
        assertThat(remove(leader, base() + "/" + meeting + "/participants/" + otherMember).statusCode()).isEqualTo(204);
        // 이 프로젝트의 멤버가 아니면 404
        var elsewhere = fixtures().projectWithLeader();
        assertThat(remove(leader, base() + "/" + meeting + "/participants/" + elsewhere.leaderMemberId()).statusCode()).isEqualTo(404);
        assertThat(remove(other, path).statusCode()).isEqualTo(403);
        assertThat(leader.delete(path, "").statusCode()).isEqualTo(403);

        close(meeting);
        assertThat(remove(leader, base() + "/" + meeting + "/participants/" + ownerMember).statusCode()).isEqualTo(409);
    }

    @Test
    void removedParticipant_cannotWrite_butCanReadAndOldEntriesStay() throws Exception {
        long meeting = openMeeting();
        assertThat(post(owner, base() + "/" + meeting + "/entries", "{\"text\":\"빠지기 전 의견\"}").statusCode()).isEqualTo(201);

        assertThat(remove(leader, base() + "/" + meeting + "/participants/" + ownerMember).statusCode()).isEqualTo(204);

        assertThat(post(owner, base() + "/" + meeting + "/entries", "{\"text\":\"빠진 뒤\"}").statusCode()).isEqualTo(403);
        assertThat(owner.get(base() + "/" + meeting).statusCode()).isEqualTo(200);
        JsonNode entries = body(owner.get(base() + "/" + meeting + "/entries"));
        assertThat(entries.get("items")).hasSize(2);
        // 다시 부르면 기록할 수 있다
        assertThat(post(leader, base() + "/" + meeting + "/participants", add(ownerMember)).statusCode()).isEqualTo(200);
        assertThat(post(owner, base() + "/" + meeting + "/entries", "{\"text\":\"돌아왔습니다\"}").statusCode()).isEqualTo(201);
    }

    // ---- 기록 ----

    @Test
    void createEntry_participantSavesAuthorFields_validationAndClosed() throws Exception {
        long meeting = openMeeting();

        HttpResponse<String> created = post(owner, base() + "/" + meeting + "/entries", "{\"text\":\"  응답 필드는 유지합니다  \"}");
        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode e = body(created);
        assertThat(e.get("kind").asString()).isEqualTo("TEXT");
        assertThat(e.get("text").asString()).isEqualTo("응답 필드는 유지합니다");
        assertThat(e.get("author_kind").asString()).isEqualTo("HUMAN");
        assertThat(e.get("author_account_id").asString()).isEqualTo(String.valueOf(ownerAccount));
        assertThat(e.get("author_member_id").asString()).isEqualTo(String.valueOf(ownerMember));
        assertThat(e.has("author_name")).isTrue();
        assertThat(e.has("refs")).isFalse();

        // 참여하지 않은 팀원 403, 본문 오류 400, Origin 없음 403
        assertThat(post(other, base() + "/" + meeting + "/entries", "{\"text\":\"x\"}").statusCode()).isEqualTo(403);
        assertThat(post(owner, base() + "/" + meeting + "/entries", "{\"text\":\"   \"}").statusCode()).isEqualTo(400);
        assertThat(post(owner, base() + "/" + meeting + "/entries", "{\"text\":\"" + "가".repeat(4001) + "\"}").statusCode()).isEqualTo(400);
        assertThat(post(owner, base() + "/" + meeting + "/entries", "{}").statusCode()).isEqualTo(400);
        assertThat(owner.post(base() + "/" + meeting + "/entries", "{\"text\":\"x\"}").statusCode()).isEqualTo(403);

        close(meeting);
        assertThat(post(owner, base() + "/" + meeting + "/entries", "{\"text\":\"끝난 뒤\"}").statusCode()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM meeting_entry WHERE meeting_id = ? AND kind = 'TEXT'", Long.class,
                meeting)).isEqualTo(1L);
    }

    @Test
    void createEntry_idempotencyKey_replaysSameInput_conflictsOnDifferentInput() throws Exception {
        long meeting = openMeeting();
        String path = base() + "/" + meeting + "/entries";

        HttpResponse<String> first = post(owner, path, "{\"text\":\"같은 입력\"}", "Idempotency-Key", "k-1");
        HttpResponse<String> replay = post(owner, path, "{\"text\":\"같은 입력\"}", "Idempotency-Key", "k-1");
        HttpResponse<String> different = post(owner, path, "{\"text\":\"다른 입력\"}", "Idempotency-Key", "k-1");

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(body(replay).get("id").asString()).isEqualTo(body(first).get("id").asString());
        assertThat(different.statusCode()).isEqualTo(409);
        assertThat(body(different).get("error").get("code").asString()).isEqualTo("idempotency_conflict");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM meeting_entry WHERE meeting_id = ? AND kind = 'TEXT'", Long.class,
                meeting)).isEqualTo(1L);
        // 끝난 회의여도 같은 키의 재요청은 처음 응답을 돌려준다
        close(meeting);
        assertThat(post(owner, path, "{\"text\":\"같은 입력\"}", "Idempotency-Key", "k-1").statusCode()).isEqualTo(201);
    }

    @Test
    void listEntries_distinguishesKinds_incrementalAndCursor() throws Exception {
        long meeting = openMeeting();
        String path = base() + "/" + meeting + "/entries";
        long e1 = body(post(owner, path, "{\"text\":\"하나\"}")).get("id").asLong();
        long e2 = body(post(owner, path, "{\"text\":\"둘\"}")).get("id").asLong();

        JsonNode all = body(other.get(path));
        assertThat(all.get("items")).extracting(n -> n.get("kind").asString()).containsExactly("CONFLICT_BRIEF", "TEXT", "TEXT");

        JsonNode after = body(other.get(path + "?after_entry_id=" + e1));
        assertThat(after.get("items")).extracting(n -> n.get("id").asLong()).containsExactly(e2);
        assertThat(body(other.get(path + "?after_entry_id=" + e2)).get("items")).isEmpty();

        List<String> seen = new ArrayList<>();
        String cursor = null;
        do {
            JsonNode page = body(other.get(path + "?limit=2" + (cursor == null ? "" : "&cursor=" + cursor)));
            page.get("items").forEach(n -> seen.add(n.get("kind").asString()));
            cursor = page.get("has_more").asBoolean() ? page.get("next_cursor").asString() : null;
        } while (cursor != null);
        assertThat(seen).containsExactly("CONFLICT_BRIEF", "TEXT", "TEXT");
        assertThat(other.get(path + "?after_entry_id=abc").statusCode()).isEqualTo(400);
    }
}
