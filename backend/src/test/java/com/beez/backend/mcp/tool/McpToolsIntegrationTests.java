package com.beez.backend.mcp.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.beez.backend.common.auth.SecretTokens;
import com.beez.backend.support.ApiClient;
import com.beez.backend.support.IntegrationTestSupport;
import com.beez.backend.support.McpTestClient;
import com.beez.backend.task.TaskApiSupport;
import com.beez.backend.validation.worker.ValidationWorker;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.McpHttpClientTransportAuthorizationException;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
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

    /** 02-mcp-tools 의 도구 중 서버에 붙인 것 */
    private static final List<String> TOOLS = List.of("get_development_context", "get_document", "get_design_changes",
            "propose_design_change", "find_context", "read_context", "write_context", "upsert_task_plan",
            "validate_task_plan", "get_validation", "retry_validation", "report_task_state", "check_connection", "get_task",
            "list_my_tasks");

    @Autowired
    DataSource dataSource;

    @Autowired
    Environment env;

    @Autowired
    ObjectMapper json;

    @Autowired
    ValidationWorker worker;

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

    @Test
    void listMyTasks_returnsOnlyOpenTasksAssignedToTheConnectedMember() {
        JsonNode first = ok(call(member, "upsert_task_plan", Map.of("plan", plan("첫 작업"), "idempotency_key", "mine-1")));
        JsonNode second = ok(call(member, "upsert_task_plan", Map.of("plan", plan("다음 작업"), "idempotency_key", "mine-2")));
        JsonNode closed = ok(call(member, "upsert_task_plan", Map.of("plan", plan("취소 작업"), "idempotency_key", "mine-3")));
        api.jdbc.sql("update task set status = 'CANCELLED' where id = :id")
                .param("id", closed.get("id").asLong()).update();
        var leaderTask = api.post("/api/projects/" + projectId + "/tasks",
                api.planJson("다른 담당자 작업", api.readsRef("API-01"), "[]"), api.accountId("leader@test"));
        assertThat(leaderTask.statusCode()).as(leaderTask.body()).isEqualTo(201);

        JsonNode page = ok(call(member, "list_my_tasks", Map.of("limit", 1)));
        assertThat(page.get("items")).hasSize(1);
        assertThat(page.get("items").get(0).get("id").asString()).isEqualTo(second.get("id").asString());
        assertThat(page.get("has_more").asBoolean()).isTrue();

        JsonNode next = ok(call(member, "list_my_tasks", Map.of("limit", 1, "cursor", page.get("next_cursor").asString())));
        assertThat(next.get("items")).hasSize(1);
        String taskId = next.get("items").get(0).get("id").asString();
        assertThat(taskId).isEqualTo(first.get("id").asString());
        assertThat(next.get("has_more").asBoolean()).isFalse();
        assertThat(ok(call(member, "get_task", Map.of("task_id", taskId))).get("id").asString()).isEqualTo(taskId);
        assertThat(error(call(member, "list_my_tasks", Map.of("assignee_member_id", "1"))))
                .containsEntry("code", "validation_failed");
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

        assertThat(error(typo)).containsEntry("code", "validation_failed").doesNotContainKey("details");
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
        // details 를 정하지 않은 오류는 REST 처럼 키가 빠진다 — "details": null 로 새지 않는다 (-267 완료 조건 2)
        assertThat(error(result)).containsEntry("code", "context_not_ready").doesNotContainKey("details");
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

    /**
     * 깨지면: 다른 프로젝트 문서를 노린 get_document 가 감사에서 일반 실패(ERROR)에 섞여 골라낼 수 없거나,
     * 반대로 자기 프로젝트의 업무 상태 404(기준에 승인본 없음·초안 버전)가 접근 거부(DENIED)로 잘못 잡힌다 (Jira -322, 설계 8절).
     */
    @Test
    void getDocument_otherProjectDocument_isAuditedAsDenied_butBaselineGapStaysError() {
        long leaderAccount = api.accountId("leader@test");
        long outsideBaseline = insertDocument("API_SPEC", leaderAccount);
        insertVersion(outsideBaseline, 1, "APPROVED", "# 기준에 없는 문서", leaderAccount);
        insertVersion(outsideBaseline, 2, "DRAFT", "# 초안", leaderAccount);

        // 자기 프로젝트: 숨길 존재가 없는 업무 상태 404 — 문구도 공통 거부 문구가 아니다
        assertThat(error(call(member, "get_document", Map.of("document_id", String.valueOf(outsideBaseline)))))
                .containsEntry("code", "not_found").containsEntry("message", "현재 기준 설계에 이 문서의 승인본이 없습니다.");
        assertThat(error(call(member, "get_document", Map.of("document_id", String.valueOf(outsideBaseline), "version_no", 2))))
                .containsEntry("code", "not_found");

        McpSyncClient other = connect(outsiderProjectToken());
        try {
            other.initialize();
            // 다른 프로젝트 문서는 없는 문서와 같은 공통 404 (05 참고 2)
            assertThat(error(call(other, "get_document", Map.of("document_id", String.valueOf(outsideBaseline)))))
                    .containsEntry("code", "not_found")
                    .containsEntry("message", "리소스가 없거나 프로젝트 소속·접근 권한이 없음");
        } finally {
            other.closeGracefully();
        }

        long outsiderTokenId = api.jdbc.sql("select id from agent_token where name = 'outsider-laptop'").query(Long.class).single();
        List<Map<String, Object>> denied = api.jdbc.sql("""
                select result, deny_reason, error_code from mcp_audit_log where agent_token_id = :t and tool = 'get_document'""")
                .param("t", outsiderTokenId).query().listOfRows();
        assertThat(denied).singleElement().satisfies(r -> {
            assertThat(r.get("result")).isEqualTo("DENIED");
            assertThat(r.get("deny_reason")).isEqualTo("document_not_in_project");
            assertThat(r.get("error_code")).isEqualTo("not_found");
        });
        assertThat(count("select count(*) from mcp_audit_log where project_id = " + projectId
                + " and tool = 'get_document' and result = 'ERROR' and error_code = 'not_found' and deny_reason is null")).isEqualTo(2);
    }

    /** 깨지면: 에이전트가 설계 변경을 제안할 수 없거나 같은 키 재시도가 제안을 두 번 만든다 (MCP-04, -195 서비스). */
    /**
     * 깨지면: 에이전트가 실행 오류로 끝난 검증을 MCP 로 다시 돌리지 못하거나, 재전송이 시도를 더 만들거나,
     * 없는·다른 프로젝트 검증과 폐기 토큰을 받는다 (Jira -271 완료 조건, MCP-14 Error 표). 재시도 규칙 자체는 ValidationRetryIntegrationTests.
     */
    @Test
    void retryValidation_reopensErroredValidation_once_andRefusesOutsideCallers() {
        // 의미 분석까지 가는 계획: 내용을 채운 API-01 을 구현한다 (ValidationRetryIntegrationTests.passableTask 와 같은 조건)
        publishCompleteApi01();
        JsonNode created = ok(call(member, "upsert_task_plan", Map.of("plan", plan("재시도", "IMPLEMENTS"),
                "idempotency_key", "k-plan")));
        String validationId = ok(call(member, "validate_task_plan", Map.of("task_id", created.get("id").asString(),
                "plan_revision_id", created.get("current_plan_revision").get("id").asString(),
                "expected_lock_version", created.get("lock_version").asInt(), "idempotency_key", "k-val")))
                .get("id").asString();
        // 시험 프로필의 AI 서버는 닫힌 포트라 실행이 ERROR(RETRY) 로 닫힌다
        assertThat(worker.runOnce()).isTrue();
        JsonNode failed = ok(call(member, "get_validation", Map.of("validation_id", validationId)));
        assertThat(failed.get("status").asString()).isEqualTo("ERROR");
        assertThat(failed.get("current_attempt").asInt()).isEqualTo(1);
        assertThat(failed.get("error_code")).isNotNull();   // ERROR 면 필수 (MCP-14 Response, 실패 정보 계약)

        Map<String, Object> args = Map.of("validation_id", validationId, "expected_attempt", 1, "idempotency_key", "k-retry");
        JsonNode retried = ok(call(member, "retry_validation", args));
        assertThat(retried.get("id").asString()).isEqualTo(validationId);
        assertThat(retried.get("status").asString()).isEqualTo("RUNNING");
        assertThat(retried.get("current_attempt").asInt()).isEqualTo(2);
        // ERROR 가 아니면 error_code 생략 — MCP 결과도 REST 와 같은 non_null 직렬화 (서비스 규칙은 ValidationRetryIntegrationTests)
        assertThat(retried.has("error_code")).isFalse();
        // 같은 키 재전송은 처음 결과, 새 시도 없음 (공통 5절)
        assertThat(ok(call(member, "retry_validation", args)).get("current_attempt").asInt()).isEqualTo(2);
        assertThat(count("select count(*) from validation_run where validation_id = " + validationId)).isEqualTo(2);

        // 중복 거부: 도는 중인 시도를 다시 / 같은 키에 다른 인수
        assertThat(error(call(member, "retry_validation", Map.of("validation_id", validationId, "expected_attempt", 2,
                "idempotency_key", "k-retry-2")))).containsEntry("code", "invalid_transition");
        assertThat(error(call(member, "retry_validation", Map.of("validation_id", validationId, "expected_attempt", 2,
                "idempotency_key", "k-retry")))).containsEntry("code", "idempotency_conflict");
        // 없는 검증, 키 누락
        assertThat(error(call(member, "retry_validation", Map.of("validation_id", "999999", "expected_attempt", 1,
                "idempotency_key", "k-missing")))).containsEntry("code", "not_found");
        assertThat(error(call(member, "retry_validation", Map.of("validation_id", validationId, "expected_attempt", 2))))
                .containsEntry("code", "validation_failed");

        // 다른 프로젝트: 그 프로젝트 토큰으로는 이 검증이 없는 것과 같다
        McpSyncClient other = connect(outsiderProjectToken());
        try {
            other.initialize();
            assertThat(error(call(other, "retry_validation", Map.of("validation_id", validationId, "expected_attempt", 2,
                    "idempotency_key", "k-other")))).containsEntry("code", "not_found");
        } finally {
            other.closeGracefully(); // tearDown 과 같은 이유 — 종료 DELETE 가 다음 시험의 truncate 와 겹치지 않게
        }
        assertThat(count("select count(*) from validation_run where validation_id = " + validationId)).isEqualTo(2);

        // 폐기한 토큰은 전송 인증에서 거부된다
        ApiClient web = client().login("member@test", com.beez.backend.support.AccessFixtures.PASSWORD);
        web.send(web.request("/api/agent-tokens/" + api.agentTokenId()).DELETE());
        assertThatThrownBy(() -> call(member, "retry_validation", Map.of("validation_id", validationId, "expected_attempt", 2,
                "idempotency_key", "k-revoked"))).isInstanceOf(McpHttpClientTransportAuthorizationException.class);
    }

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

    /**
     * 깨지면: 다른 프로젝트 토큰이 이 프로젝트의 작업·검증·기록 ID 를 임의로 넣어 읽거나 바꾸고, 그 시도가 감사에서 거부로 보이지 않거나,
     * 감사 행에 요청 문장·토큰이 남는다 (Jira -177 완료 조건 "다른 프로젝트 ID 접근 거부"·"감사 기록에 인증정보 미포함", 05 참고 2 상황표).
     */
    @Test
    void otherProjectIds_areDenied_andAuditedWithoutBodies() {
        JsonNode created = ok(call(member, "upsert_task_plan", Map.of("plan", plan("로그인"), "idempotency_key", "k-plan")));
        String taskId = created.get("id").asString();
        String revisionId = created.get("current_plan_revision").get("id").asString();
        String validationId = ok(call(member, "validate_task_plan", Map.of("task_id", taskId,
                "plan_revision_id", revisionId, "expected_lock_version", created.get("lock_version").asInt(),
                "idempotency_key", "k-val"))).get("id").asString();
        String recordId = ok(call(member, "write_context", Map.of("kind", "HANDOVER", "visibility", "SHARED",
                "summary", "로그인 UI 연결 완료", "detail", "비밀 메모 원문", "design_refs", List.of(),
                "idempotency_key", "k-w1"))).get("id").asString();
        int lock = count("select lock_version from task where id = " + taskId);
        call(member, "upsert_task_plan", Map.of("plan", plan("A"), "taskid", "1", "idempotency_key", "k-typo"));

        String outsiderToken = outsiderProjectToken();
        McpSyncClient other = connect(outsiderToken);
        try {
            other.initialize();
            assertThat(error(call(other, "get_validation", Map.of("validation_id", validationId)))).containsEntry("code", "not_found");
            // 작업 조회도 같다 — 다른 프로젝트 에이전트가 이 작업의 잠금 번호·계획을 읽지 못한다 (MCP-16, -335)
            assertThat(error(call(other, "get_task", Map.of("task_id", taskId)))).containsEntry("code", "not_found");
            assertThat(error(call(other, "read_context", Map.of("type", "RECORD", "id", recordId)))).containsEntry("code", "not_found");
            assertThat(error(call(other, "report_task_state", Map.of("task_id", taskId, "event", "CANCEL",
                    "plan_revision_id", revisionId, "expected_lock_version", lock, "idempotency_key", "k-x"))))
                    .containsEntry("code", "not_found");
            assertThat(error(call(other, "upsert_task_plan", Map.of("task_id", taskId, "plan", plan("빼앗기"),
                    "expected_revision_no", 1, "expected_lock_version", lock, "idempotency_key", "k-y"))))
                    .containsEntry("code", "not_found");
            // 인수의 project_id 는 대조만 한다 — 토큰 프로젝트와 다르면 없는 것과 같다
            assertThat(error(call(other, "get_development_context", Map.of("request", "로그인 화면 구현",
                    "project_id", String.valueOf(projectId))))).containsEntry("code", "not_found");
        } finally {
            other.closeGracefully(); // tearDown 과 같은 이유 — 종료 DELETE 가 다음 시험의 truncate 와 겹치지 않게
        }
        // 이 프로젝트의 작업은 그대로다
        assertThat(count("select lock_version from task where id = " + taskId)).isEqualTo(lock);
        assertThat(count("select count(*) from task where project_id = " + projectId + " and title = '빼앗기'")).isZero();

        long outsiderTokenId = api.jdbc.sql("select id from agent_token where name = 'outsider-laptop'").query(Long.class).single();
        List<Map<String, Object>> denied = api.jdbc.sql("""
                select tool, result, deny_reason, error_code, target_ids::text as targets, latency_ms
                  from mcp_audit_log where agent_token_id = :t order by id""")
                .param("t", outsiderTokenId).query().listOfRows();
        assertThat(denied).extracting(r -> r.get("tool")).containsExactly("get_validation", "get_task",
                "read_context", "report_task_state", "upsert_task_plan", "get_development_context");
        assertThat(denied).allSatisfy(r -> {
            assertThat(r.get("result")).isEqualTo("DENIED");
            assertThat(r.get("error_code")).isEqualTo("not_found");
            // 사유는 종류만 — 프로젝트·계정 번호가 섞이지 않는다
            assertThat((String) r.get("deny_reason")).matches("[a-z0-9_]+");
            assertThat(r.get("latency_ms")).isNotNull();
        });
        assertThat(denied.get(0).get("targets").toString()).contains("\"validation_id\"", "\"" + validationId + "\"");
        assertThat(denied.get(1).get("deny_reason")).isEqualTo("task_not_in_project");
        assertThat(denied.get(5).get("deny_reason")).isEqualTo("packet_project_mismatch");

        // 이 프로젝트 쪽: 성공은 OK(코드 없음), 인수 오류는 거부가 아닌 ERROR
        assertThat(count("select count(*) from mcp_audit_log where project_id = " + projectId
                + " and result = 'OK' and error_code is null and deny_reason is null")).isEqualTo(3);
        assertThat(count("select count(*) from mcp_audit_log where project_id = " + projectId
                + " and result = 'ERROR' and error_code = 'validation_failed' and deny_reason is null")).isEqualTo(1);
        // 감사 행 어디에도 요청 문장·기록 원문·토큰 원문이 없다
        for (String secret : List.of("로그인", "비밀 메모", memberToken, outsiderToken)) {
            assertThat(api.jdbc.sql("select count(*) from mcp_audit_log a where position(:s in cast(a as text)) > 0")
                    .param("s", secret).query(Integer.class).single()).as(secret).isZero();
        }
    }

    /**
     * 깨지면: 손으로 고친 result_cursor(서명 없는 Base64)로 다른 프로젝트의 영향 분석 결과를 받는다
     * (Jira -177 코멘트 1, -221 — cursor 는 진행점일 뿐 조회는 토큰 프로젝트 조건으로만 한다).
     */
    @Test
    void forgedResultCursor_fromAnotherProject_seesNoImpactUpdates() {
        long leader = api.accountId("leader@test");
        long baseline = api.jdbc.sql("insert into baseline (project_id, version_no, published_by) values (:p, 2, :a) returning id")
                .param("p", projectId).param("a", leader).query(Long.class).single();
        // 분석 대기 → 결과 확정 (DesignChangesImpactUpdatesIntegrationTests 의 pendingAnalysis·complete 와 같은 칸)
        long analysis = api.jdbc.sql("""
                insert into published_change_analysis (project_id, published_baseline_id, status)
                values (:p, :b, 'PENDING') returning id""")
                .param("p", projectId).param("b", baseline).query(Long.class).single();
        api.jdbc.sql("""
                update published_change_analysis
                   set status = 'COMPLETED', evaluation_status = 'COMPLETED', explicit_result = cast(:r as jsonb), finished_at = now()
                 where id = :id""")
                .param("id", analysis)
                .param("r", "[{\"task_id\":\"501\",\"design_item_id\":\"301\",\"member_id\":\"9\"}]").update();
        String fromStart = cursor("u1|0|");
        // 대조군: 이 프로젝트 멤버는 같은 cursor 로 결과를 받는다
        assertThat(ok(call(member, "get_design_changes", Map.of("since_version", 1, "result_cursor", fromStart)))
                .get("impact_updates")).hasSize(1);

        String outsiderToken = outsiderProjectToken();
        // 다른 프로젝트 Q 에도 기준 설계 v1 이 있어야 get_design_changes 가 영향 결과까지 간다(없으면 baseline_missing)
        api.jdbc.sql("""
                with b as (insert into baseline (project_id, version_no, published_by)
                           select p.id, 1, p.owner_account_id from project p where p.name = 'Q' returning id, project_id)
                update project set current_baseline_id = b.id from b where project.id = b.project_id""").update();
        McpSyncClient other = connect(outsiderToken);
        try {
            other.initialize();
            assertThat(ok(call(other, "get_design_changes", Map.of("since_version", 1, "result_cursor", fromStart)))
                    .get("impact_updates")).isEmpty();
            // 이 프로젝트 작업 범위로 위조해도 조회는 토큰 프로젝트 조건이라 결과가 없다
            assertThat(ok(call(other, "get_design_changes", Map.of("since_version", 1, "task_id", "501",
                    "result_cursor", cursor("u1|0|501")))).get("impact_updates")).isEmpty();
            // 범위를 바꿔 끼운 cursor 는 400
            assertThat(error(call(other, "get_design_changes", Map.of("since_version", 1, "result_cursor", cursor("u1|0|501")))))
                    .containsEntry("code", "validation_failed");
        } finally {
            other.closeGracefully(); // tearDown 과 같은 이유
        }
    }

    // ---- 도우미 ----

    private static String cursor(String raw) {
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** outsider@test 가 팀장인 다른 프로젝트 Q 와 그 토큰(이름 outsider-laptop)을 만들고 토큰 원문을 돌려준다 */
    private String outsiderProjectToken() {
        String token = SecretTokens.generate();
        api.jdbc.sql("insert into project (name, owner_account_id) select 'Q', id from account where email = 'outsider@test'").update();
        api.jdbc.sql("""
                insert into project_member (project_id, account_id, role)
                select p.id, a.id, 'LEADER' from project p, account a where p.name = 'Q' and a.email = 'outsider@test'""").update();
        api.jdbc.sql("""
                insert into agent_token (project_id, account_id, name, token_hash)
                select p.id, a.id, 'outsider-laptop', :h from project p, account a where p.name = 'Q' and a.email = 'outsider@test'""")
                .param("h", SecretTokens.hash(token)).update();
        return token;
    }

    private Map<String, Object> plan(String title) {
        return plan(title, "READS");
    }

    private Map<String, Object> plan(String title, String relation) {
        return Map.of("title", title, "goal", "목표", "included_scope", List.of("a"), "excluded_scope", List.of(),
                "approach", "방법", "completion_criteria", List.of("테스트 통과"), "dependency_task_ids", List.of(),
                "design_refs", List.of(Map.of("design_item_id", String.valueOf(api.designItemId("API-01")),
                        "design_item_version_id", String.valueOf(api.currentVersionId("API-01")), "relation", relation)),
                "unlinked", List.of(), "no_contract_change", true);
    }

    /** API-01 의 내용을 채운 버전 2 를 기준 2 로 발행한다 (ValidationRetryIntegrationTests.provideCompleteApiContent 와 같다) */
    private void publishCompleteApi01() {
        Long version = api.jdbc.sql("""
                insert into design_item_version (design_item_id, version_no, status, title, schema_version,
                    content, content_hash, origin, approved_at)
                values (:item, 2, 'APPROVED', 'API-01 complete', 1, cast(:content as jsonb), 'complete-API-01', 'HUMAN', now())
                returning id
                """).param("item", api.designItemId("API-01"))
                .param("content", "{\"request_fields\":[],\"responses\":[{\"fields\":[]}]}").query(Long.class).single();
        api.publish(2, Map.of("API-01", version, "API-02", api.currentVersionId("API-02")));
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
        return McpTestClient.connect(Integer.parseInt(env.getRequiredProperty("local.server.port")), token);
    }

    private static CallToolResult call(McpSyncClient client, String tool, Map<String, Object> args) {
        return McpTestClient.call(client, tool, args);
    }

    /** 성공 결과의 payload. 실패면 오류 본문을 보이며 깨진다 */
    private JsonNode ok(CallToolResult result) {
        return McpTestClient.ok(json, result);
    }

    private static Map<String, Object> error(CallToolResult result) {
        return McpTestClient.error(result);
    }
}
