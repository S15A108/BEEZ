package com.beez.backend.acceptance;

import static com.beez.backend.support.McpTestClient.call;
import static com.beez.backend.support.McpTestClient.error;
import static org.assertj.core.api.Assertions.assertThat;

import com.beez.backend.common.access.AccessErrors;
import com.beez.backend.common.auth.SecretTokens;
import com.beez.backend.support.IntegrationTestSupport;
import com.beez.backend.support.McpTestClient;
import com.beez.backend.task.TaskApiSupport;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 인수 기준 AC-10 (Jira -208): 다른 프로젝트의 작업·contextId·원문은 조회가 거부되고, 검색 결과·요약에도 나오지 않는다.
 *
 * <p>이미 다른 시험이 덮는 곳은 다시 쓰지 않는다 — 프로젝트 밖 사람이 <b>남의 프로젝트 경로</b>를 부르는 404
 * (AccessRules·TaskStatusApi·ValidationApi·DocumentApi), MCP 로 남의 작업·검증·기록·문서 ID 를 넣는 거부와 감사
 * (McpToolsIntegrationTests), 같은 프로젝트 안 남의 비공개 기록(ContextMcpServiceIntegrationTests), 문서 검색의 다른 프로젝트 제외
 * (DocumentApiIntegrationTest). 여기서는 그 사이의 빈 곳만 본다: <b>다른 프로젝트의 진짜 멤버</b>가 자기 프로젝트 경로·토큰에
 * 이 프로젝트의 ID 를 넣는 경우(05 참고 2 검사 순서 3번 "대상 객체가 그 프로젝트 소속이 아님"), 그리고 목록·요약·기록 검색.
 *
 * <p>프로젝트 P = 픽스처(leader·member), 프로젝트 Q = outsider 가 팀장인 다른 프로젝트.
 */
class AccessAcceptanceAc10IntegrationTests extends IntegrationTestSupport {

    private static final String MISSING = "999999";

    @Autowired
    DataSource dataSource;

    @Autowired
    Environment env;

    @Autowired
    ObjectMapper json;

    TaskApiSupport api;
    long projectId;
    long otherProjectId;
    Long member;
    Long outsider;
    String memberToken;
    String outsiderToken;
    /** MCP 시험에서만 연결한다 — REST 시험에 연결·종료 왕복과 truncate 교착 위험을 얹지 않는다 */
    final List<McpSyncClient> opened = new ArrayList<>();

    @BeforeEach
    void setUp() {
        api = new TaskApiSupport(this::client, dataSource);
        api.resetAndLoadFixture();
        projectId = api.projectId();
        member = api.accountId("member@test");
        outsider = api.accountId("outsider@test");

        otherProjectId = api.jdbc.sql("insert into project (name, owner_account_id) values ('Q', :a) returning id")
                .param("a", outsider).query(Long.class).single();
        api.jdbc.sql("insert into project_member (project_id, account_id, role) values (:p, :a, 'LEADER')")
                .param("p", otherProjectId).param("a", outsider).update();

        // 픽스처 토큰에 원문을 정해 둔다 (McpToolsIntegrationTests 와 같은 방식)
        memberToken = SecretTokens.generate();
        api.jdbc.sql("update agent_token set token_hash = :h where id = :id")
                .param("h", SecretTokens.hash(memberToken)).param("id", api.agentTokenId()).update();
        outsiderToken = SecretTokens.generate();
        api.jdbc.sql("""
                insert into agent_token (project_id, account_id, name, token_hash) values (:p, :a, 'outsider-laptop', :h)""")
                .param("p", otherProjectId).param("a", outsider).param("h", SecretTokens.hash(outsiderToken)).update();
    }

    @AfterEach
    void tearDown() {
        // 종료 DELETE 를 기다리지 않으면 다음 시험의 truncate 와 교착한다 (McpToolsIntegrationTests.tearDown 과 같은 이유)
        opened.forEach(McpSyncClient::closeGracefully);
    }

    // ---- REST: 상세 조회 ----

    /**
     * 깨지면: 다른 프로젝트 멤버가 자기 프로젝트 경로에 이 프로젝트의 작업·검증 번호를 넣어 내용을 읽거나,
     * 없는 번호와 다른 응답으로 그 번호가 어딘가에 있다는 사실을 알게 된다 (AC-10, 05 참고 2 검사 순서 3번·상황표 첫 줄).
     */
    @Test
    void rest_taskAndValidationIdsOfAnotherProject_lookMissing_inOwnProjectPath() {
        long taskId = createTaskInP();
        long validationId = api.passValidation(taskId);
        String q = "/api/projects/" + otherProjectId;

        assertSameAsMissing(q + "/tasks/" + taskId, q + "/tasks/" + MISSING);
        assertSameAsMissing(q + "/tasks/" + taskId + "/plan-revisions", q + "/tasks/" + MISSING + "/plan-revisions");
        assertSameAsMissing(q + "/tasks/" + taskId + "/validations", q + "/tasks/" + MISSING + "/validations");
        assertSameAsMissing(q + "/validations/" + validationId, q + "/validations/" + MISSING);

        // 대조군: 이 프로젝트 멤버는 같은 번호로 읽는다 — 위 404 가 번호 자체의 문제가 아니다
        String p = "/api/projects/" + projectId;
        assertThat(api.get(p + "/tasks/" + taskId, member).statusCode()).isEqualTo(200);
        assertThat(api.get(p + "/validations/" + validationId, member).statusCode()).isEqualTo(200);
    }

    /** 깨지면: 다른 프로젝트 멤버가 자기 프로젝트 경로로 이 프로젝트의 승인 문서 원문·버전 목록을 읽는다 (AC-10 "원문", DOC-A03·A05). */
    @Test
    void rest_documentIdOfAnotherProject_looksMissing_inOwnProjectPath() {
        long documentId = insertApprovedDocumentInP("# 비밀 요구사항 원문");
        String q = "/api/projects/" + otherProjectId + "/documents/";

        assertSameAsMissing(q + documentId + "?version_no=1", q + MISSING + "?version_no=1");
        assertSameAsMissing(q + documentId + "/versions", q + MISSING + "/versions");

        HttpResponse<String> own = api.get("/api/projects/" + projectId + "/documents/" + documentId + "?version_no=1", member);
        assertThat(own.statusCode()).as(own.body()).isEqualTo(200);
    }

    // ---- REST: 목록·요약 ----

    /**
     * 깨지면: 다른 프로젝트 화면의 작업·문서 목록이나 상태별 건수 요약에 이 프로젝트의 작업·문서가 섞인다
     * (AC-10 "요약에도 노출되지 않음", 05 참고 2 "권한 조건은 페이지·검색·집계 전에").
     */
    @Test
    void rest_listsAndSummariesOfAnotherProject_neverCountThisProjectsData() {
        long taskId = createTaskInP();
        long validationId = api.passValidation(taskId);
        // 대기 사유별 건수도 대조할 수 있게 입력 대기로 둔다 (상태 전이 규칙은 이 시험의 관심이 아니라 SQL 로.
        // WAITING 은 시작을 허용한 검증이 있어야 한다 — task CHECK 제약)
        api.jdbc.sql("""
                        update task set status = 'WAITING', waiting_reason = 'INPUT', started_validation_id = :v, started_at = now()
                         where id = :t""")
                .param("v", validationId).param("t", taskId).update();
        insertApprovedDocumentInP("# 원문");

        // 대조군: 이 프로젝트 쪽에는 잡힌다
        String p = "/api/projects/" + projectId;
        JsonNode ownTasks = okJson(p + "/tasks/summary", member);
        assertThat(total(ownTasks.get("by_status"))).isEqualTo(1);
        assertThat(total(ownTasks.get("by_waiting_reason"))).isEqualTo(1);
        assertThat(total(okJson(p + "/validations/summary", member).get("by_status"))).isEqualTo(1);
        assertThat(okJson(p + "/tasks", member).get("items")).hasSize(1);
        assertThat(okJson(p + "/documents", member).get("items")).isNotEmpty();

        String q = "/api/projects/" + otherProjectId;
        JsonNode otherTasks = okJson(q + "/tasks/summary", outsider);
        assertThat(total(otherTasks.get("by_status"))).isZero();
        assertThat(total(otherTasks.get("by_waiting_reason"))).isZero();
        assertThat(total(okJson(q + "/validations/summary", outsider).get("by_status"))).isZero();
        assertThat(okJson(q + "/tasks", outsider).get("items")).isEmpty();
        assertThat(okJson(q + "/documents", outsider).get("items")).isEmpty();
    }

    // ---- MCP ----

    /**
     * 깨지면: find_context 가 토큰 프로젝트 조건 없이 검색해 다른 프로젝트의 공유 작업 기록(요약 포함)을 돌려준다
     * (AC-10 "검색 결과에도 노출되지 않음", MCP-05). 남의 비공개 제외는 ContextMcpServiceIntegrationTests 가 본다.
     */
    @Test
    void mcp_findContext_neverReturnsRecordsOfAnotherProject() {
        McpSyncClient memberMcp = mcp(memberToken);
        McpSyncClient outsiderMcp = mcp(outsiderToken);
        String mine = ok(call(memberMcp, "write_context", record("AC10 결제 연동 인계", "P 원문"))).get("id").asString();
        String theirs = ok(call(outsiderMcp, "write_context", record("AC10 결제 연동 메모", "Q 원문"))).get("id").asString();

        assertThat(ok(call(outsiderMcp, "find_context", Map.of("query", "AC10 결제"))).get("items"))
                .extracting(n -> n.get("id").asString()).containsExactly(theirs);
        assertThat(ok(call(memberMcp, "find_context", Map.of("query", "AC10 결제"))).get("items"))
                .extracting(n -> n.get("id").asString()).containsExactly(mine);
    }

    /**
     * 깨지면: 다른 프로젝트 토큰으로 이 프로젝트의 개발 컨텍스트 패킷(contextId)을 read_context 로 읽거나,
     * 없는 번호와 다른 오류로 그 패킷이 있다는 사실을 알게 된다 (AC-10 "contextId", 05 상황표 둘째 줄).
     * 기록(RECORD) 쪽은 McpToolsIntegrationTests.otherProjectIds_areDenied_andAuditedWithoutBodies 가 본다.
     */
    @Test
    void mcp_readContext_packetOfAnotherProject_looksMissing() {
        McpSyncClient memberMcp = mcp(memberToken);
        McpSyncClient outsiderMcp = mcp(outsiderToken);
        String packetId = String.valueOf(api.jdbc.sql("""
                        insert into dev_context_packet (project_id, request, baseline_id, content, content_hash)
                        select id, '로그인 화면 구현', current_baseline_id, '{}', 'h-ac10' from project where id = :p
                        returning id""")
                .param("p", projectId).query(Long.class).single());

        Map<String, Object> foreign = error(call(outsiderMcp, "read_context", Map.of("type", "PACKET", "id", packetId)));
        Map<String, Object> missing = error(call(outsiderMcp, "read_context", Map.of("type", "PACKET", "id", MISSING)));
        assertThat(foreign).containsEntry("code", "not_found").containsEntry("message", AccessErrors.NOT_FOUND_MESSAGE)
                .isEqualTo(missing);

        // 대조군: 이 프로젝트 토큰은 같은 번호로 읽는다
        assertThat(ok(call(memberMcp, "read_context", Map.of("type", "PACKET", "id", packetId))).get("packet").get("id")
                .asString()).isEqualTo(packetId);
    }

    // ---- 도우미 ----

    private long createTaskInP() {
        HttpResponse<String> created = api.post("/api/projects/" + projectId + "/tasks",
                api.planJson("AC10 작업", api.readsRef("API-01"), "[]"), member);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        return TaskApiSupport.longField(created.body(), "id");
    }

    /** P 의 승인 문서 하나 (McpToolsIntegrationTests.insertDocument·insertVersion 과 같은 칸) */
    private long insertApprovedDocumentInP(String content) {
        long leader = api.accountId("leader@test");
        long document = api.jdbc.sql("""
                        insert into document (project_id, type, title, created_by) values (:p, 'REQ_SPEC', '요구사항', :a) returning id""")
                .param("p", projectId).param("a", leader).query(Long.class).single();
        api.jdbc.sql("""
                        insert into document_version (document_id, version_no, status, storage_key, content_hash, content_text,
                                                      created_by, approved_by, approved_at)
                        values (:d, 1, 'APPROVED', 'fixture.md', 'h-ac10-v1', :c, :a, :a, now())""")
                .param("d", document).param("c", content).param("a", leader).update();
        return document;
    }

    /** outsider(Q 팀장)가 Q 경로로 부른 두 응답이 둘 다 404 이고 본문까지 같다 */
    private void assertSameAsMissing(String foreignPath, String missingPath) {
        HttpResponse<String> foreign = api.get(foreignPath, outsider);
        HttpResponse<String> missing = api.get(missingPath, outsider);
        assertThat(foreign.statusCode()).as(foreignPath + " → " + foreign.body()).isEqualTo(404);
        assertThat(missing.statusCode()).as(missingPath).isEqualTo(404);
        assertThat(foreign.body()).as(foreignPath).isEqualTo(missing.body()).contains(AccessErrors.NOT_FOUND_MESSAGE);
    }

    private JsonNode okJson(String path, Long account) {
        HttpResponse<String> res = api.get(path, account);
        assertThat(res.statusCode()).as(path + " → " + res.body()).isEqualTo(200);
        return json.readTree(res.body());
    }

    private static int total(JsonNode counts) {
        int sum = 0;
        for (JsonNode n : counts) {
            sum += n.get("count").asInt();
        }
        return sum;
    }

    private static Map<String, Object> record(String summary, String detail) {
        return Map.of("kind", "HANDOVER", "visibility", "SHARED", "summary", summary, "detail", detail,
                "design_refs", List.of(), "idempotency_key", "k-" + summary.hashCode());
    }

    /** 연결하고 tearDown 에서 닫도록 등록한다 */
    private McpSyncClient mcp(String token) {
        McpSyncClient client = McpTestClient.connect(Integer.parseInt(env.getRequiredProperty("local.server.port")), token);
        opened.add(client);
        client.initialize();
        return client;
    }

    private JsonNode ok(CallToolResult result) {
        return McpTestClient.ok(json, result);
    }
}
