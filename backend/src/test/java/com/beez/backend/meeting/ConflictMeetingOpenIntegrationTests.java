package com.beez.backend.meeting;

import static org.assertj.core.api.Assertions.assertThat;
import static com.beez.backend.designitem.support.DesignJson.JSON;

import com.beez.backend.project.ProjectRole;
import com.beez.backend.support.AccessFixtures;
import com.beez.backend.support.ApiClient;
import com.beez.backend.support.IntegrationTestSupport;
import com.beez.backend.validation.event.ConflictFindingsSaved;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * 충돌 이벤트로 회의가 자동 개설된다 (S15P31A108-248). 리스너는 AFTER_COMMIT 이라 실제 커밋을 거쳐야 하므로
 * 이벤트를 트랜잭션 안에서 발행하고 커밋한 뒤 DB 행을 직접 본다 — 리스너를 직접 부르면 트랜잭션 동기화 때문에 저장 누락이 가려진다.
 */
class ConflictMeetingOpenIntegrationTests extends IntegrationTestSupport {

    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired TransactionTemplate tx;

    AccessFixtures.ProjectWithLeader project;
    long accountA;
    long accountB;
    long memberA;
    long memberB;

    @BeforeEach
    void setUp() {
        project = fixtures().projectWithLeader();
        accountA = fixtures().account();
        accountB = fixtures().account();
        memberA = fixtures().member(project.projectId(), accountA, ProjectRole.MEMBER);
        memberB = fixtures().member(project.projectId(), accountB, ProjectRole.MEMBER);
    }

    private long baseline() {
        long baseline = jdbc.queryForObject(
                "INSERT INTO baseline (project_id, version_no, published_by) VALUES (?, 1, ?) RETURNING id",
                Long.class, project.projectId(), project.leaderAccountId());
        jdbc.update("UPDATE project SET current_baseline_id = ? WHERE id = ?", baseline, project.projectId());
        return baseline;
    }

    /** 작업 a 의 검증 하나와 모델 실행 하나를 만들고 (검증 ID, 실행 ID) 를 돌려준다 */
    private long[] validationOf(long taskId, long baselineId) {
        long revision = jdbc.queryForObject("""
                INSERT INTO task_plan_revision (task_id, revision_no, title, goal, input_hash, created_by_account_id)
                VALUES (?, 1, 't', 'g', 'h', ?) RETURNING id
                """, Long.class, taskId, project.leaderAccountId());
        long validation = jdbc.queryForObject("""
                INSERT INTO validation (task_id, plan_revision_id, baseline_id, status, trigger, input_snapshot_hash, rule_version)
                VALUES (?, ?, ?, 'NEEDS_REVIEW', 'AGENT', 'h', 'r') RETURNING id
                """, Long.class, taskId, revision, baselineId);
        long run = jdbc.queryForObject("""
                INSERT INTO validation_run (validation_id, attempt, kind, status) VALUES (?, 1, 'MODEL', 'OK') RETURNING id
                """, Long.class, validation);
        return new long[] {validation, run};
    }

    private void conflictFinding(long[] validation, long relatedTaskId, String message) {
        jdbc.update("""
                INSERT INTO validation_finding (validation_id, validation_run_id, source, kind, severity, related_task_id, message, resolution)
                VALUES (?, ?, 'MODEL', 'CONFLICT', 'BLOCKING', ?, ?, 'HUMAN_REVIEW')
                """, validation[0], validation[1], relatedTaskId, message);
    }

    private long task(String key, Long assignee, String status) {
        return jdbc.queryForObject("""
                INSERT INTO task (project_id, key, title, assignee_member_id, status)
                VALUES (?, ?, ?, ?, ?) RETURNING id
                """, Long.class, project.projectId(), key, key, assignee, status);
    }

    private void publishCommitted(long taskId, long relatedId) {
        tx.executeWithoutResult(s -> publisher.publishEvent(
                new ConflictFindingsSaved(project.projectId(), 1L, taskId, List.of(relatedId))));
    }

    private int meetings() {
        return jdbc.queryForObject("SELECT count(*) FROM meeting WHERE project_id = ?", Integer.class, project.projectId());
    }

    @Test void committedEventOpensMeetingWithBriefAndParticipants() {
        baseline();
        long a = task("T-1", memberA, "DRAFT");
        long b = task("T-2", memberB, "DRAFT");
        publishCommitted(a, b);
        assertThat(meetings()).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM meeting_entry e JOIN meeting m ON m.id = e.meeting_id
                 WHERE m.project_id = ? AND e.kind = 'CONFLICT_BRIEF'
                """, Integer.class, project.projectId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM meeting_participant p JOIN meeting m ON m.id = p.meeting_id WHERE m.project_id = ?
                """, Integer.class, project.projectId())).isEqualTo(3);   // 팀장 + 두 담당자
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification WHERE project_id = ? AND type = 'MEETING_OPENED'",
                Integer.class, project.projectId())).isEqualTo(3);   // 참여자 전원
    }

    @Test void twoConflictsOnOnePairAreBothInTheBrief() {
        long baseline = baseline();
        long a = task("T-1", memberA, "DRAFT");
        long b = task("T-2", memberB, "DRAFT");
        long[] validation = validationOf(a, baseline);
        conflictFinding(validation, b, "응답 형식이 달라요");
        conflictFinding(validation, b, "만료 정책이 달라요");
        tx.executeWithoutResult(s -> publisher.publishEvent(
                new ConflictFindingsSaved(project.projectId(), validation[0], a, List.of(b))));
        assertThat(meetings()).isEqualTo(1);
        String brief = jdbc.queryForObject("""
                SELECT e.body FROM meeting_entry e JOIN meeting m ON m.id = e.meeting_id
                 WHERE m.project_id = ? AND e.kind = 'CONFLICT_BRIEF'
                """, String.class, project.projectId());
        assertThat(brief).contains("(2가지)", "1. 응답 형식이 달라요", "2. 만료 정책이 달라요");
    }

    @Test void validationOpensMeetingWithPinnedPlanDifferencesAndApprovedSource() {
        long baseline = baseline();
        long document = jdbc.queryForObject("""
                INSERT INTO document (project_id, type, title, created_by)
                VALUES (?, 'API_SPEC', 'Users API', ?) RETURNING id
                """, Long.class, project.projectId(), project.leaderAccountId());
        long documentVersion = jdbc.queryForObject("""
                INSERT INTO document_version (document_id, version_no, status, storage_key, content_hash, approved_at)
                VALUES (?, 1, 'APPROVED', 'meeting-guidance-fixture', 'source', now()) RETURNING id
                """, Long.class, document);
        long item = jdbc.queryForObject("""
                INSERT INTO design_item (project_id, key, kind) VALUES (?, 'API-01', 'API_CONTRACT') RETURNING id
                """, Long.class, project.projectId());
        long version = jdbc.queryForObject("""
                INSERT INTO design_item_version (design_item_id, version_no, status, title, schema_version, content,
                                                 content_hash, source_document_version_id, source_locator, origin, approved_at)
                VALUES (?, 1, 'APPROVED', 'GET /users', 1, '{"method":"GET","path":"/users"}', 'design', ?,
                        '{"start_line":4,"end_line":7}', 'HUMAN', now()) RETURNING id
                """, Long.class, item, documentVersion);
        jdbc.update("""
                INSERT INTO baseline_item (baseline_id, project_id, design_item_id, design_item_version_id)
                VALUES (?, ?, ?, ?)
                """, baseline, project.projectId(), item, version);

        String projectPath = "/api/projects/" + project.projectId();
        ApiClient firstOwner = client().login(fixtures().emailOf(accountA), AccessFixtures.PASSWORD);
        ApiClient secondOwner = client().login(fixtures().emailOf(accountB), AccessFixtures.PASSWORD);
        JsonNode running = body(firstOwner.post(projectPath + "/tasks",
                changingPlan("Users query", item, version, "integer")), 201);
        long runningId = running.path("id").asLong();
        long runningRevision = running.at("/current_plan_revision/id").asLong();
        long pass = jdbc.queryForObject("""
                INSERT INTO validation (task_id, plan_revision_id, baseline_id, status, trigger, input_snapshot_hash, rule_version)
                VALUES (?, ?, ?, 'PASS', 'AGENT', 'fixture', 'r1') RETURNING id
                """, Long.class, runningId, runningRevision, baseline);
        jdbc.update("""
                INSERT INTO validation_design_ref (validation_id, design_item_id, design_item_version_id, relation)
                VALUES (?, ?, ?, 'CHANGES')
                """, pass, item, version);
        JsonNode started = body(firstOwner.post(projectPath + "/tasks/" + runningId + "/state-events", """
                {"event":"START","plan_revision_id":"%d","expected_lock_version":%d,"validation_id":"%d"}
                """.formatted(runningRevision, running.path("lock_version").asInt(), pass)), 200);
        assertThat(started.path("status").asText()).isEqualTo("IN_PROGRESS");

        JsonNode validating = body(secondOwner.post(projectPath + "/tasks",
                changingPlan("Users response", item, version, "string")), 201);
        long validatingId = validating.path("id").asLong();
        long validatingRevision = validating.at("/current_plan_revision/id").asLong();
        JsonNode validation = body(secondOwner.post(projectPath + "/tasks/" + validatingId + "/validations", """
                {"plan_revision_id":"%d","expected_lock_version":%d}
                """.formatted(validatingRevision, validating.path("lock_version").asInt())), 200);
        assertThat(validation.path("status").asText()).isEqualTo("NEEDS_REVIEW");
        long meeting = jdbc.queryForObject("SELECT id FROM meeting WHERE project_id = ?", Long.class, project.projectId());
        String meetingPath = projectPath + "/meetings/" + meeting;
        JsonNode guidance = body(secondOwner.get(meetingPath), 200).path("conflict_guidance");
        assertThat(guidance.path("validation_id").asText()).isEqualTo(validation.path("id").asText());
        assertThat(guidance.at("/tasks/0/task_id").asLong()).isEqualTo(validatingId);
        assertThat(guidance.at("/tasks/0/plan_revision_id").asLong()).isEqualTo(validatingRevision);
        assertThat(guidance.at("/tasks/1/plan_revision_id").asLong()).isEqualTo(runningRevision);
        assertThat(guidance.at("/tasks/1/revision_no").asInt()).isEqualTo(1);
        assertThat(guidance.at("/conflicts/0/message").asText()).contains("같은 설계 항목");
        assertThat(guidance.at("/conflicts/0/design_item/title").asText()).isEqualTo("GET /users");
        assertThat(guidance.at("/conflicts/0/design_item/document_id").asLong()).isEqualTo(document);
        assertThat(guidance.at("/conflicts/0/design_item/document_version_id").asLong()).isEqualTo(documentVersion);
        assertThat(guidance.at("/conflicts/0/design_item/location").asText()).isEqualTo("L4-L7");
        assertThat(guidance.at("/conflicts/0/differences").size()).isEqualTo(1);
        assertThat(guidance.at("/conflicts/0/differences/0/path").asText()).isEqualTo("response.status");
        assertThat(guidance.at("/conflicts/0/differences/0/first_value").asText()).isEqualTo("string");
        assertThat(guidance.at("/conflicts/0/differences/0/second_value").asText()).isEqualTo("integer");

        // The comparison remains attached to the meeting when the current plan moves on.
        jdbc.update("UPDATE task SET title = 'Later title' WHERE id = ?", runningId);
        assertThat(body(secondOwner.get(meetingPath), 200).path("conflict_guidance")).isEqualTo(guidance);
    }

    private static String changingPlan(String title, long item, long version, String statusType) {
        return """
                {"title":"%s","goal":"Update the users endpoint","included_scope":["Users API"],"excluded_scope":[],
                 "approach":"Update the response","completion_criteria":["Response contract is verified"],
                 "dependency_task_ids":[],"unlinked":[],"no_contract_change":false,
                 "design_refs":[{"design_item_id":"%d","design_item_version_id":"%d","relation":"CHANGES",
                   "proposed_content":{"method":"GET","path":"/users","response":{"status":"%s"}}}]}
                """.formatted(title, item, version, statusType);
    }

    private static JsonNode body(HttpResponse<String> response, int expectedStatus) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(expectedStatus);
        return JSON.readTree(response.body());
    }

    @Test void sameConflictAgainDoesNotOpenAnotherMeeting() {
        baseline();
        long a = task("T-1", memberA, "DRAFT");
        long b = task("T-2", memberB, "DRAFT");
        publishCommitted(a, b);
        publishCommitted(a, b);
        assertThat(meetings()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification WHERE project_id = ? AND type = 'MEETING_OPENED'",
                Integer.class, project.projectId())).isEqualTo(3);   // 재검증으로 알림이 늘지 않는다
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM meeting_entry e JOIN meeting m ON m.id = e.meeting_id WHERE m.project_id = ?
                """, Integer.class, project.projectId())).isEqualTo(1);
    }

    @Test void sameAssigneeOrClosedTaskOpensNothing() {
        baseline();
        long a = task("T-1", memberA, "DRAFT");
        long sameOwner = task("T-2", memberA, "DRAFT");
        long cancelled = task("T-3", memberB, "CANCELLED");
        publishCommitted(a, sameOwner);
        publishCommitted(a, cancelled);
        assertThat(meetings()).isZero();
    }

    @Test void missingBaselineIsSwallowedAndOpensNothing() {
        long a = task("T-1", memberA, "DRAFT");
        long b = task("T-2", memberB, "DRAFT");
        publishCommitted(a, b);   // 예외가 호출자에게 번지지 않는다
        assertThat(meetings()).isZero();
    }

    @Test void rolledBackPublisherOpensNothing() {
        baseline();
        long a = task("T-1", memberA, "DRAFT");
        long b = task("T-2", memberB, "DRAFT");
        tx.executeWithoutResult(s -> {
            publisher.publishEvent(new ConflictFindingsSaved(project.projectId(), 1L, a, List.of(b)));
            s.setRollbackOnly();
        });
        assertThat(meetings()).isZero();
    }
}
