package com.beez.backend.mcp.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.beez.backend.common.auth.SecretTokens;
import com.beez.backend.support.ApiClient;
import com.beez.backend.support.IntegrationTestSupport;
import com.beez.backend.task.TaskApiSupport;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.McpHttpClientTransportAuthorizationException;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.time.Duration;
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
 * MCP 도구 연결 (-179). 실제 MCP SDK 클라이언트가 /mcp 에 붙어 도구를 부른다 — Jira -166 완료 조건 "MCP 경유 호출 테스트".
 * 서비스 규칙은 각 서비스 시험(TaskMcpService·ContextMcpService·Proposal… IntegrationTest)에 있으므로,
 * 여기서는 인수가 서비스까지 제대로 가고 결과·오류가 도구 결과로 돌아오는지와 -179 완료 조건의 경계(미준비·실패·정상 0건, 권한·폐기 토큰)만 본다.
 */
class McpToolsIntegrationTests extends IntegrationTestSupport {

    /** 02-mcp-tools 의 도구 중 이 PR 이 붙이는 것. MCP-14 retry_validation 은 명칭·분리 검토 중이라 빠진다 */
    private static final List<String> TOOLS = List.of("get_development_context", "get_document", "get_design_changes",
            "propose_design_change", "find_context", "read_context", "write_context", "upsert_task_plan",
            "validate_task_plan", "get_validation", "report_task_state");

    @Autowired
    DataSource dataSource;

    @Autowired
    Environment env;

    @Autowired
    ObjectMapper json;

    TaskApiSupport api;
    long projectId;
    String memberToken;
    String leaderToken;
    McpSyncClient member;

    @BeforeEach
    void setUp() {
        api = new TaskApiSupport(this::client, dataSource);
        api.resetAndLoadFixture();
        projectId = api.projectId();
        // 픽스처 토큰에 원문을 정해 둔다 (발급 API 는 활성 토큰 수 상한이 있어 픽스처 토큰을 그대로 쓴다)
        memberToken = SecretTokens.generate();
        leaderToken = SecretTokens.generate();
        api.jdbc.sql("update agent_token set token_hash = :h where id = :id")
                .param("h", SecretTokens.hash(memberToken)).param("id", api.agentTokenId()).update();
        api.jdbc.sql("update agent_token set token_hash = :h where id = :id")
                .param("h", SecretTokens.hash(leaderToken)).param("id", api.leaderAgentTokenId()).update();
        member = connect(memberToken);
        member.initialize();
    }

    @AfterEach
    void tearDown() {
        // setUp 이 연결 전에 실패했으면 member 가 없다 — NPE 로 원래 실패를 가리지 않는다
        // close() 는 세션 종료 DELETE 를 보내기만 하고 기다리지 않는다(SDK 2.1.0). 그 요청의 토큰 확인 트랜잭션이
        // 다음 시험 setUp 의 truncate … cascade 와 교착하므로(CI 40P01) 응답까지 기다리는 closeGracefully 를 쓴다
        if (member != null) {
            member.closeGracefully();
        }
    }

    /** 깨지면: 에이전트가 명세의 도구 이름으로 도구를 찾지 못하거나, 스키마가 모르는 키를 허용한다고 안내한다 (02-mcp-tools 탐색 목록). */
    @Test
    void toolsList_hasSpecToolNames_withClosedInputSchemas() {
        List<McpSchema.Tool> tools = member.listTools().tools();

        assertThat(tools).extracting(McpSchema.Tool::name).containsExactlyInAnyOrderElementsOf(TOOLS);
        assertThat(tools).allSatisfy(t -> assertThat(t.inputSchema()).containsEntry("additionalProperties", false));
    }

    /**
     * 깨지면: 선택 인수 오타가 조용히 무시돼 뜻이 바뀐 채 성공한다 — task_id 오타면 계획 수정 대신 새 작업이 생긴다
     * (-179 결정 (a): 모르는 키는 400 validation_failed).
     */
    @Test
    void unknownArgumentKey_isRejected_beforeService() {
        int before = count("select count(*) from task");

        CallToolResult typo = call(member, "upsert_task_plan", Map.of("plan", plan("A"), "taskid", "1",
                "idempotency_key", "k-typo"));
        CallToolResult nested = call(member, "upsert_task_plan", Map.of("plan", withExtra(plan("A"), "titel", "x"),
                "idempotency_key", "k-nested"));

        assertThat(error(typo)).containsEntry("code", "validation_failed");
        assertThat(error(typo).get("message").toString()).contains("taskid");
        assertThat(error(nested)).containsEntry("code", "validation_failed");
        assertThat(error(nested).get("message").toString()).contains("plan.titel");
        assertThat(count("select count(*) from task")).isEqualTo(before);
    }

    /** 깨지면: 구성 모듈 미연결을 "근거 없음"으로 읽게 하거나 빈 패킷을 남긴다 (08 계약 3절 MCP-01 503 context_not_ready). */
    @Test
    void getDevelopmentContext_notReady_isFailureAndSavesNothing() {
        CallToolResult result = call(member, "get_development_context",
                Map.of("request", "로그인 화면 구현", "project_id", String.valueOf(projectId)));

        assertThat(result.isError()).isTrue();
        assertThat(error(result)).containsEntry("code", "context_not_ready");
        assertThat(count("select count(*) from dev_context_packet")).isZero();
    }

    /**
     * 깨지면: 계획 → 검증 → 결과 조회 → 시작 보고의 작업 호출 순서(02-mcp-tools)가 MCP 로 이어지지 않는다
     * (Jira -166 완료 조건 "MCP 경유 호출", MCP-10·11·13·12).
     */
    @Test
    void taskFlow_planValidateStart_overMcp() {
        JsonNode created = ok(call(member, "upsert_task_plan", Map.of("plan", plan("로그인"), "idempotency_key", "k-plan")));
        String taskId = created.get("id").asString();
        String revisionId = created.get("current_plan_revision").get("id").asString();
        // 같은 키 재시도는 처음 결과 (공통 5절)
        assertThat(ok(call(member, "upsert_task_plan", Map.of("plan", plan("로그인"), "idempotency_key", "k-plan")))
                .get("id").asString()).isEqualTo(taskId);

        JsonNode validation = ok(call(member, "validate_task_plan", Map.of("task_id", taskId,
                "plan_revision_id", revisionId, "expected_lock_version", created.get("lock_version").asInt(),
                "idempotency_key", "k-val")));
        JsonNode fetched = ok(call(member, "get_validation", Map.of("validation_id", validation.get("id").asString())));
        assertThat(fetched.get("id").asString()).isEqualTo(validation.get("id").asString());
        assertThat(fetched.get("task_id").asString()).isEqualTo(taskId);

        long passed = api.passValidation(Long.parseLong(taskId));
        int lock = count("select lock_version from task where id = " + taskId);
        JsonNode started = ok(call(member, "report_task_state", Map.of("task_id", taskId, "event", "START",
                "plan_revision_id", revisionId, "expected_lock_version", lock,
                "validation_id", String.valueOf(passed), "idempotency_key", "k-start")));
        assertThat(started.get("task").get("status").asString()).isEqualTo("IN_PROGRESS");
        // 기준 설계가 있는 프로젝트라 changes 와 이어 보기 기준 번호가 붙는다 (08 계약 2절)
        assertThat(started.get("changes")).isNotNull();
        assertThat(started.get("changes_since_version").asInt()).isEqualTo(1);

        // 실패: 시작으로 잠금 번호가 바뀐 작업을 옛 번호로 보고 → 서비스의 409 code 가 그대로 도구 실패로 (TaskStateService 충돌 검사)
        CallToolResult stale = call(member, "report_task_state", Map.of("task_id", taskId, "event", "WAIT",
                "plan_revision_id", revisionId, "expected_lock_version", lock, "waiting_reason", "INPUT",
                "idempotency_key", "k-wait"));
        assertThat(error(stale)).containsEntry("code", "revision_conflict");

        // 완료 보고에 실제로 바꾼 설계 항목을 실으면 결과에 계획과 대조한 범위 이탈 후보가 붙는다 (-269, 표시만).
        // 이 계획은 API-01 을 읽기만 하고 "계약 변경 없음"을 선언했다
        JsonNode completed = ok(call(member, "report_task_state", Map.of("task_id", taskId, "event", "COMPLETED",
                "plan_revision_id", revisionId, "expected_lock_version", started.get("task").get("lock_version").asInt(),
                "changed_design_item_ids", List.of(String.valueOf(api.designItemId("API-01"))),
                "idempotency_key", "k-done")));
        assertThat(completed.get("task").get("status").asString()).isEqualTo("COMPLETED");
        JsonNode review = completed.get("task").get("completion_review");
        assertThat(review.get("deviation_count").asInt()).isEqualTo(1);
        assertThat(review.get("scope_deviations").get(0).get("reason").asString()).isEqualTo("NO_CONTRACT_CHANGE_DECLARED");
    }

    /** 깨지면: 변경 없음(정상 0건)과 실패를 구분하지 못한다 (02-mcp-tools MCP-03 "실패를 변경 없음으로 처리하지 않는다"). */
    @Test
    void getDesignChanges_zeroIsSuccess_missingSinceIsFailure() {
        JsonNode none = ok(call(member, "get_design_changes", Map.of("since_version", 1)));
        assertThat(none.get("items")).isEmpty();
        assertThat(none.get("has_more").asBoolean()).isFalse();
        // 영향 분석 결과(-221): 처음엔 시작점만 받고, 그 값을 result_cursor 로 보내 이어 본다
        assertThat(none.get("impact_updates")).isEmpty();
        String resultCursor = none.get("next_result_cursor").asString();
        assertThat(resultCursor).isNotBlank();
        JsonNode next = ok(call(member, "get_design_changes", Map.of("since_version", 1, "result_cursor", resultCursor)));
        assertThat(next.get("impact_updates")).isEmpty();
        assertThat(next.get("result_has_more").asBoolean()).isFalse();

        assertThat(error(call(member, "get_design_changes", Map.of()))).containsEntry("code", "validation_failed");
    }

    /**
     * 깨지면: 남의 비공개 기록·다른 프로젝트 객체가 도구로 보이거나, 폐기한 토큰으로 계속 읽는다
     * (Jira -179 완료 조건 "MCP-06 권한·폐기 토큰", MCP-05·07 연결).
     */
    @Test
    void contextRecords_writeFindRead_withPermissionAndRevokedToken() {
        JsonNode written = ok(call(member, "write_context", Map.of("kind", "HANDOVER", "visibility", "SHARED",
                "summary", "로그인 UI 연결 완료", "detail", "오류는 message 로 표시", "design_refs", List.of(),
                "idempotency_key", "k-w1")));
        String recordId = written.get("id").asString();

        JsonNode found = ok(call(member, "find_context", Map.of("query", "로그인 ui")));
        assertThat(found.get("items")).extracting(n -> n.get("id").asString()).containsExactly(recordId);
        assertThat(ok(call(member, "find_context", Map.of("query", "없는 검색어"))).get("items")).isEmpty();
        assertThat(ok(call(member, "read_context", Map.of("type", "RECORD", "id", recordId)))
                .get("record").get("summary").asString()).isEqualTo("로그인 UI 연결 완료");

        String privateId;
        McpSyncClient leader = connect(leaderToken);
        try {
            leader.initialize();
            privateId = ok(call(leader, "write_context", Map.of("kind", "DECISION", "visibility", "PRIVATE",
                    "summary", "팀장 메모", "detail", "비공개", "design_refs", List.of(), "idempotency_key", "k-w2")))
                    .get("id").asString();
        } finally {
            leader.closeGracefully(); // tearDown 과 같은 이유 — 종료 DELETE 가 다음 시험의 truncate 와 겹치지 않게
        }
        assertThat(error(call(member, "read_context", Map.of("type", "RECORD", "id", privateId))))
                .containsEntry("code", "not_found");
        assertThat(error(call(member, "read_context", Map.of("type", "PACKET", "id", "999999"))))
                .containsEntry("code", "not_found");

        ApiClient web = client().login("member@test", com.beez.backend.support.AccessFixtures.PASSWORD);
        web.send(web.request("/api/agent-tokens/" + api.agentTokenId()).DELETE());
        assertThatThrownBy(() -> call(member, "read_context", Map.of("type", "RECORD", "id", recordId)))
                .isInstanceOf(McpHttpClientTransportAuthorizationException.class);
    }

    /**
     * 깨지면: 에이전트가 현재 기준 밖의 승인본·승인 전 원문을 근거로 받거나, 문서 원문 조회가 조회 이력에 남지 않는다
     * (MCP-02 처리 규칙 "생략 시 현재 baseline_document 의 승인본", 08 계약 1절 조회 이력). 기준 버전이 SUPERSEDED 가 된 경우가 함정이다.
     */
    @Test
    void getDocument_servesCurrentBaselineVersion_rejectsDrafts_andRecordsAccess() {
        long leaderAccount = api.accountId("leader@test");
        long baseline = api.jdbc.sql("select current_baseline_id from project where id = :p").param("p", projectId)
                .query(Long.class).single();
        long document = insertDocument("REQ_SPEC", leaderAccount);
        long v1 = insertVersion(document, 1, "SUPERSEDED", "# v1 기준", leaderAccount);
        insertVersion(document, 2, "APPROVED", "# v2 기준 밖 승인본", leaderAccount);
        insertVersion(document, 3, "DRAFT", "# v3 초안", leaderAccount);
        api.jdbc.sql("insert into baseline_document(baseline_id,project_id,document_id,document_version_id) values(:b,:p,:d,:v)")
                .param("b", baseline).param("p", projectId).param("d", document).param("v", v1).update();
        long outsideBaseline = insertDocument("API_SPEC", leaderAccount);
        insertVersion(outsideBaseline, 1, "APPROVED", "# 기준에 없는 문서", leaderAccount);

        JsonNode current = ok(call(member, "get_document", Map.of("document_id", String.valueOf(document))));
        assertThat(current.get("version").get("version_no").asInt()).isEqualTo(1);
        assertThat(current.get("version").get("content").asString()).isEqualTo("# v1 기준");
        assertThat(current.get("version").get("is_current_baseline").asBoolean()).isTrue();
        assertThat(current.get("document").has("preview_text")).isFalse();
        assertThat(current.get("document").has("latest_draft")).isFalse();
        // 문서의 승인본 칸도 최신 APPROVED(v2)가 아니라 현재 기준 버전(v1)
        assertThat(current.get("document").get("approved_version").get("version_no").asInt()).isEqualTo(1);

        // 기준 버전은 SUPERSEDED 여도 번호로 지정해 읽을 수 있다
        JsonNode pinned = ok(call(member, "get_document", Map.of("document_id", String.valueOf(document), "version_no", 1)));
        assertThat(pinned.get("version").get("status").asString()).isEqualTo("SUPERSEDED");

        JsonNode later = ok(call(member, "get_document", Map.of("document_id", String.valueOf(document), "version_no", 2)));
        assertThat(later.get("version").get("is_current_baseline").asBoolean()).isFalse();

        assertThat(error(call(member, "get_document", Map.of("document_id", String.valueOf(document), "version_no", 3))))
                .containsEntry("code", "not_found");
        assertThat(error(call(member, "get_document", Map.of("document_id", String.valueOf(outsideBaseline)))))
                .containsEntry("code", "not_found");
        assertThat(error(call(member, "get_document", Map.of()))).containsEntry("code", "validation_failed");
        assertThat(error(call(member, "get_document", Map.of("document_id", "999999")))).containsEntry("code", "not_found");

        // 성공 3건만 남는다. 실패 조회는 남기지 않는다(감사는 -177)
        assertThat(count("select count(*) from context_access_log where tool = 'get_document'")).isEqualTo(3);
        assertThat(count("select count(*) from context_access_log where tool = 'get_document' and target_kind = 'DOCUMENT_VERSION'"
                + " and target_id = " + v1 + " and baseline_id = " + baseline + " and agent_token_id is not null")).isEqualTo(2);
    }

    /** 프로젝트당 종류별 문서 1개(ux_document_project_type)라 종류를 받는다 */
    private long insertDocument(String type, long author) {
        return api.jdbc.sql("insert into document(project_id,type,title,created_by) values(:p,:type,'요구사항',:a) returning id")
                .param("p", projectId).param("type", type).param("a", author).query(Long.class).single();
    }

    private long insertVersion(long document, int no, String status, String content, long author) {
        boolean approved = !"DRAFT".equals(status);
        return api.jdbc.sql("""
                insert into document_version(document_id,version_no,status,storage_key,content_hash,content_text,created_by,approved_by,approved_at)
                values(:d,:no,:status,'fixture.md',:hash,:content,:a,:approver,:approvedAt) returning id
                """).param("d", document).param("no", no).param("status", status).param("hash", "hash-v" + no)
                .param("content", content).param("a", author).param("approver", approved ? author : null)
                .param("approvedAt", approved ? java.sql.Timestamp.from(java.time.Instant.now()) : null)
                .query(Long.class).single();
    }

    /** 깨지면: 에이전트가 설계 변경을 제안할 수 없거나 같은 키 재시도가 제안을 두 번 만든다 (MCP-04, -195 서비스). */
    @Test
    void proposeDesignChange_createsProposal_once() {
        Map<String, Object> args = Map.of(
                "base_baseline_id", String.valueOf(api.jdbc.sql("select current_baseline_id from project where id = :p")
                        .param("p", projectId).query(Long.class).single()),
                "title", "사용자 목록 설명 보완", "reason", "응답 설명을 명확히 한다",
                "items", List.of(Map.of("operation", "UPDATE",
                        "design_item_id", String.valueOf(api.designItemId("API-01")),
                        "base_design_item_version_id", String.valueOf(api.currentVersionId("API-01")),
                        "schema_version", 1,
                        "proposed_content", Map.of("description", "사용자 목록 조회", "method", "GET", "path", "/users"))),
                "documents", List.of(), "idempotency_key", "k-prop");

        JsonNode proposal = ok(call(member, "propose_design_change", args));
        assertThat(proposal.get("status").asString()).isEqualTo("PROPOSED");
        assertThat(ok(call(member, "propose_design_change", args)).get("id").asString())
                .isEqualTo(proposal.get("id").asString());
        assertThat(count("select count(*) from change_proposal where project_id = " + projectId
                + " and title = '사용자 목록 설명 보완'")).isEqualTo(1);
    }

    // ---- 도우미 ----

    private Map<String, Object> plan(String title) {
        return Map.of("title", title, "goal", "목표", "included_scope", List.of("a"), "excluded_scope", List.of(),
                "approach", "방법", "completion_criteria", List.of("테스트 통과"), "dependency_task_ids", List.of(),
                "design_refs", List.of(Map.of("design_item_id", String.valueOf(api.designItemId("API-01")),
                        "design_item_version_id", String.valueOf(api.currentVersionId("API-01")), "relation", "READS")),
                "unlinked", List.of(), "no_contract_change", true);
    }

    private static Map<String, Object> withExtra(Map<String, Object> base, String key, Object value) {
        var copy = new java.util.HashMap<>(base);
        copy.put(key, value);
        return copy;
    }

    private int count(String sql) {
        return api.jdbc.sql(sql).query(Integer.class).single();
    }

    private McpSyncClient connect(String token) {
        var transport = HttpClientStreamableHttpTransport
                .builder("http://localhost:" + env.getRequiredProperty("local.server.port"))
                .endpoint("/mcp")
                .httpRequestCustomizer((builder, method, uri, body, context) ->
                        builder.header("Authorization", "Bearer " + token))
                .build();
        return McpClient.sync(transport).requestTimeout(Duration.ofSeconds(10)).build();
    }

    private static CallToolResult call(McpSyncClient client, String tool, Map<String, Object> args) {
        return client.callTool(McpSchema.CallToolRequest.builder().name(tool).arguments(args).build());
    }

    /** 성공 결과의 payload. 실패면 오류 본문을 보이며 깨진다 */
    private JsonNode ok(CallToolResult result) {
        assertThat(result.isError()).as("도구 실패: %s", ((McpSchema.TextContent) result.content().get(0)).text()).isFalse();
        return json.valueToTree(result.structuredContent());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> error(CallToolResult result) {
        assertThat(result.isError()).isTrue();
        return (Map<String, Object>) ((Map<String, Object>) result.structuredContent()).get("error");
    }
}
