package com.beez.backend.mcp.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.beez.backend.ai.AiClient;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.common.auth.SecretTokens;
import com.beez.backend.relation.dto.RelationAction;
import com.beez.backend.relation.dto.RelationChangeRequest;
import com.beez.backend.relation.dto.RelationCommand;
import com.beez.backend.relation.service.RelationStorageService;
import com.beez.backend.support.IntegrationTestSupport;
import com.beez.backend.support.McpTestClient;
import com.beez.backend.task.TaskApiSupport;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * MCP-01 get_development_context 를 AI 컨텍스트 선택에 연결한 경로 (S15P31A108-399). 실제 MCP SDK 클라이언트 → /mcp → 실제 DB(pgvector).
 * AI 는 대역이다: 질의 임베딩은 고정 벡터, 선택 응답은 -132 계약 모양을 흉내 낸 가짜라 이 시험이 통과해도
 * 실제 AI 선택 품질·AI 쪽 스키마 수용은 확인한 것이 아니다 (그건 AI tests/test_context_packet_api.py 와 dev 실제 호출로 본다).
 * 여기서는 BE 가 보낸 스냅샷·저장·재조회·실패 구분만 본다.
 */
@TestPropertySource(properties = {"beez.document.worker-enabled=false", "beez.document.embedding-profile=test-profile",
        "beez.document.embedding-model=test-model"})
class DevelopmentContextMcpIntegrationTests extends IntegrationTestSupport {

    static final String CHUNK = "로그인은 이메일과 비밀번호로 한다.\n";

    @Autowired
    DataSource dataSource;
    @Autowired
    Environment env;
    @Autowired
    ObjectMapper json;
    @Autowired
    RelationStorageService relations;
    @Autowired
    PlatformTransactionManager transactions;
    @MockitoBean
    AiClient ai;

    TaskApiSupport api;
    long projectId;
    long baselineId;
    long docVersion;
    long policyVersion;
    McpSyncClient member;
    /** 가짜 AI 가 받은 마지막 선택 요청 본문 */
    final AtomicReference<JsonNode> sent = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        api = new TaskApiSupport(this::client, dataSource);
        api.resetAndLoadFixture();
        projectId = api.projectId();
        baselineId = api.jdbc.sql("select current_baseline_id from project where id = :p").param("p", projectId)
                .query(Long.class).single();
        long leader = api.accountId("leader@test");

        // 기준에 POLICY 한 건(설명이 constraints 가 된다)과 API-01 → POLICY 확정 의존을 더한다
        long policyItem = api.jdbc.sql("insert into design_item(project_id,key,kind) values (:p,'POL-01','POLICY') returning id")
                .param("p", projectId).query(Long.class).single();
        policyVersion = api.jdbc.sql("""
                insert into design_item_version(design_item_id,version_no,status,title,schema_version,content,content_hash,origin,approved_at)
                values (:i,1,'APPROVED','인증 정책',1,'{"description":"로그인 실패 5회면 잠근다"}','pol-hash','HUMAN',now()) returning id
                """).param("i", policyItem).query(Long.class).single();
        api.jdbc.sql("insert into baseline_item(baseline_id,project_id,design_item_id,design_item_version_id) values (:b,:p,:i,:v)")
                .params(Map.of("b", baselineId, "p", projectId, "i", policyItem, "v", policyVersion)).update();
        new TransactionTemplate(transactions).executeWithoutResult(status -> relations.apply(CallerContext.human(leader), projectId,
                new RelationChangeRequest(baselineId, null, List.of(),
                        null, List.of(new RelationCommand(RelationAction.CONFIRM, api.designItemId("API-01"), policyItem, "로그인 정책")))));

        docVersion = indexedDocument(leader);

        String token = SecretTokens.generate();
        api.jdbc.sql("update agent_token set token_hash = :h where id = :id")
                .param("h", SecretTokens.hash(token)).param("id", api.agentTokenId()).update();
        member = McpTestClient.connect(Integer.parseInt(env.getRequiredProperty("local.server.port")), token);
        member.initialize();

        when(ai.documentEmbeddings(anyMap())).thenAnswer(invocation -> queryVector());
        when(ai.developmentContextSelection(any())).thenAnswer(invocation -> fakeSelection(invocation.getArgument(0)));
    }

    @AfterEach
    void tearDown() {
        if (member != null) {
            member.closeGracefully();
        }
    }

    /**
     * 깨지면: 에이전트 작업 절차 1단계(get_development_context → read_context → 계획 등록 → 검증)가 MCP 로 이어지지 않는다
     * (-399 완료 조건 1·2). 패킷이 BE 가 모은 현재 기준 스냅샷으로 만들어지고, 다시 읽어도 같은지 본다.
     */
    @Test
    void developmentContext_savesPacket_readBack_andPlanValidates_overMcp() {
        JsonNode packet = ok(call("get_development_context", Map.of("request", "로그인 화면 구현",
                "project_id", String.valueOf(projectId))));

        assertThat(packet.get("baseline_id").asString()).isEqualTo(String.valueOf(baselineId));
        assertThat(packet.get("design_items")).extracting(n -> n.get("key").asString()).containsExactly("API-01", "POL-01");
        assertThat(packet.get("constraints")).extracting(JsonNode::asString).containsExactly("로그인 실패 5회면 잠근다");
        assertThat(packet.get("unresolved")).isEmpty();
        assertThat(packet.get("sources")).hasSize(1);
        assertThat(packet.get("sources").get(0).get("document_version_id").asString()).isEqualTo(String.valueOf(docVersion));
        assertThat(packet.get("sources").get(0).get("location").asString()).isEqualTo("1~1행");

        // BE 가 보낸 스냅샷: 현재 기준 설계 전부, 색인된 근거, 확정 의존(확정 당시 버전), 검색 완료
        JsonNode body = sent.get();
        assertThat(body.get("search_status").asString()).isEqualTo("COMPLETE");
        assertThat(body.get("items")).hasSize(3);
        assertThat(body.get("metadata").get("design_items")).hasSize(3);
        assertThat(body.get("evidence")).hasSize(1);
        assertThat(body.get("relations")).hasSize(1);
        JsonNode relation = body.get("relations").get(0);
        assertThat(relation.get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(relation.get("to_design_item_version_id").asString()).isEqualTo(String.valueOf(policyVersion));
        // 선택 응답의 사용량이 공통 사용량 표에 남는다 (요청 키로 구분)
        assertThat(count("select count(*) from llm_usage where feature = 'EMBEDDING' and request_key like 'fake-selection-%'"))
                .isEqualTo(1);

        String packetId = packet.get("id").asString();
        JsonNode read = ok(call("read_context", Map.of("type", "PACKET", "id", packetId)));
        assertThat(read.get("packet").get("content_hash").asString()).isEqualTo(packet.get("content_hash").asString());
        assertThat(read.get("packet").get("design_items")).isEqualTo(packet.get("design_items"));

        Map<String, Object> plan = new LinkedHashMap<>(plan("로그인 화면"));
        plan.put("context_packet_id", packetId);
        JsonNode created = ok(call("upsert_task_plan", Map.of("plan", plan, "idempotency_key", "k-plan")));
        assertThat(count("select count(*) from task_plan_revision where context_packet_id = " + packetId)).isEqualTo(1);
        JsonNode validation = ok(call("validate_task_plan", Map.of("task_id", created.get("id").asString(),
                "plan_revision_id", created.get("current_plan_revision").get("id").asString(),
                "expected_lock_version", created.get("lock_version").asInt(), "idempotency_key", "k-val")));
        assertThat(validation.get("status").asString()).isEqualTo("PASS");
    }

    /**
     * 깨지면: 작업을 지정한 조회가 그 작업의 현재 계획 참조·상태를 AI 에 넘기지 않거나, 관련 작업이 패킷에 남지 않는다
     * (-132 2절 metadata.task, 08 계약 3절 관련 작업 재확인).
     */
    @Test
    void developmentContext_withTask_sendsCurrentPlanRefs_andKeepsRelatedTask() {
        JsonNode created = ok(call("upsert_task_plan", Map.of("plan", plan("로그인 화면"), "idempotency_key", "k-task")));
        String taskId = created.get("id").asString();

        JsonNode packet = ok(call("get_development_context", Map.of("request", "로그인 화면 구현", "task_id", taskId)));

        JsonNode body = sent.get();
        assertThat(body.get("task_id").asString()).isEqualTo(taskId);
        assertThat(body.get("task_design_refs")).extracting(n -> n.get("design_item_version_id").asString())
                .containsExactly(String.valueOf(api.currentVersionId("API-01")));
        assertThat(packet.get("task_id").asString()).isEqualTo(taskId);
        String status = created.get("status").asString();
        if ("DRAFT".equals(status)) {
            assertThat(packet.get("related_tasks")).isEmpty();
        } else {
            assertThat(packet.get("related_tasks").get(0).get("plan_revision_id").asString())
                    .isEqualTo(created.get("current_plan_revision").get("id").asString());
        }
    }

    /**
     * 깨지면: 검색을 마쳤는데 근거가 없는 경우를 실패로 끊거나(에이전트가 계획을 못 세운다), 반대로 빈 성공을 "근거 있음"처럼 준다
     * (MCP-01 오류표 "검색을 마쳤는데 근거가 0건이면 정상 응답의 unresolved"). 여기서는 기준에 문서가 없어 검색 결과가 0건이다
     * (문서가 있을 때 유사도 미달로 0건이 되는 판단은 AI 몫이고 같은 NO_MATCH 로 온다).
     */
    @Test
    void searchedButNoEvidence_isNormalPacket_withNoMatch() {
        api.jdbc.sql("delete from baseline_document where document_version_id = :v").param("v", docVersion).update();

        JsonNode packet = ok(call("get_development_context", Map.of("request", "결제 화면 구현")));

        assertThat(sent.get().get("evidence")).isEmpty();
        assertThat(packet.get("unresolved")).extracting(JsonNode::asString).containsExactly("NO_MATCH");
        assertThat(packet.get("sources")).isEmpty();
        assertThat(count("select count(*) from dev_context_packet")).isEqualTo(1);
    }

    /**
     * 깨지면: 색인 미준비를 "근거 없음"으로 저장하거나, 쓸 수 없는 검색으로 유료 선택 호출을 보낸다
     * (MCP-01 오류표 503 context_not_ready, -399 완료 조건 3).
     */
    @Test
    void indexNotReady_is503_withoutSelectionCall_andSavesNothing() {
        api.jdbc.sql("update document_processing set status='FAILED', index_status='FAILED' where document_version_id = :v")
                .param("v", docVersion).update();

        CallToolResult result = call("get_development_context", Map.of("request", "로그인 화면 구현"));

        assertThat(error(result)).containsEntry("code", "context_not_ready");
        verify(ai, never()).developmentContextSelection(any());
        assertThat(count("select count(*) from dev_context_packet")).isZero();
    }

    /**
     * 깨지면: AI 선택 실패·시간 초과가 빈 패킷으로 저장되거나 다른 오류로 새고, 실패한 호출의 비용이 기록에서 빠진다
     * (MCP-01 503 context_not_ready, AiUsageRecorder "미확인" 한 줄).
     */
    @Test
    void selectionFailureOrTimeout_is503_savesNothing_andRecordsUsage() {
        String errorBody = """
                {"error":{"code":"embedding_timeout","message":"Selection processing failed."},
                 "usage":[{"request_key":"fake-selection-error","model":"m","tokens_known":false,"failed":true}]}""";
        doThrow(HttpServerErrorException.create(HttpStatus.GATEWAY_TIMEOUT, "Gateway Timeout", new HttpHeaders(),
                errorBody.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8)).when(ai).developmentContextSelection(any());

        assertThat(error(call("get_development_context", Map.of("request", "로그인 화면 구현"))))
                .containsEntry("code", "context_not_ready");
        assertThat(count("select count(*) from llm_usage where request_key = 'fake-selection-error'")).isEqualTo(1);

        doThrow(new ResourceAccessException("read timed out")).when(ai).developmentContextSelection(any());
        assertThat(error(call("get_development_context", Map.of("request", "로그인 화면 구현"))))
                .containsEntry("code", "context_not_ready");
        assertThat(count("select count(*) from llm_usage where request_key like 'dev-context-%:failed' and failed")).isEqualTo(1);

        assertThat(count("select count(*) from dev_context_packet")).isZero();
    }

    /** 깨지면: AI 가 거부할 긴 요청이 유료 호출까지 가거나 잘려 저장된다 (-110 MVP 기준 request 2,000 코드 포인트, 400). */
    @Test
    void requestOverLimit_is400_beforeAnyAiCall() {
        String tooLong = "가".repeat(2001);

        assertThat(error(call("get_development_context", Map.of("request", tooLong)))).containsEntry("code", "validation_failed");
        verify(ai, never()).documentEmbeddings(anyMap());
        verify(ai, never()).developmentContextSelection(any());
    }

    // ---- 가짜 AI ----

    private static Map<String, Object> queryVector() {
        var vector = new ArrayList<Double>(Collections.nCopies(1024, 0.0));
        vector.set(0, 1.0);
        return Map.of("origin", "MODEL", "usage", List.of(), "result", Map.of(
                "model", "test-model", "dimension", 1024, "embedding_profile_id", "test-profile",
                "purpose", "QUERY", "vectors", List.of(Map.of("key", "query", "embedding", vector))));
    }

    /**
     * -132 3절 응답 모양을 흉내 낸다: API-01 을 직접 일치로 고르고 확정 의존(CONFIRMED·같은 버전)을 한 단계 펼친다.
     * 근거는 받은 것 전부를 고른다. 아무것도 고르지 못하면 NO_MATCH. 실제 AI 의 선택 규칙을 시험하려는 것이 아니다.
     */
    private Map<String, Object> fakeSelection(byte[] raw) {
        JsonNode body = json.readTree(raw);
        sent.set(body);
        JsonNode meta = body.get("metadata");
        List<String> chosenItems = new ArrayList<>();
        for (JsonNode item : meta.get("design_items")) {
            if ("API-01".equals(item.get("key").asString())) {
                chosenItems.add(item.get("design_item_id").asString());
            }
        }
        for (JsonNode r : body.get("relations")) {
            if ("CONFIRMED".equals(r.get("status").asString()) && chosenItems.contains(r.get("from_design_item_id").asString())) {
                chosenItems.add(r.get("to_design_item_id").asString());
            }
        }
        List<Map<String, Object>> designItems = new ArrayList<>();
        List<String> versionIds = new ArrayList<>();
        List<String> constraints = new ArrayList<>();
        for (JsonNode item : meta.get("design_items")) {
            if (!chosenItems.contains(item.get("design_item_id").asString())) {
                continue;
            }
            versionIds.add(item.get("design_item_version_id").asString());
            designItems.add(Map.of("design_item_version_id", item.get("design_item_version_id").asString(),
                    "design_item_id", item.get("design_item_id").asString(), "key", item.get("key").asString(),
                    "kind", item.get("kind").asString(), "version_no", item.get("version_no").asInt(),
                    "reasons", List.of("MATCHED")));
            if ("POLICY".equals(item.get("kind").asString())) {
                constraints.add(item.get("description").asString());
            }
        }
        List<Map<String, Object>> sources = new ArrayList<>();
        for (JsonNode e : meta.get("evidence")) {
            sources.add(Map.of("document_id", e.get("document_id").asString(),
                    "document_version_id", e.get("document_version_id").asString(),
                    "version_no", e.get("version_no").asInt(), "location", e.get("location").asString()));
        }
        boolean noMatch = body.get("evidence").isEmpty() && !body.get("request").asString().contains("로그인");
        if (noMatch) {
            designItems.clear();
            versionIds.clear();
            constraints.clear();
        }
        List<Object> related = new ArrayList<>();
        if (meta.hasNonNull("task")) {
            JsonNode t = meta.get("task");
            related.add(Map.of("task_id", t.get("task_id").asString(), "plan_revision_id", t.get("plan_revision_id").asString(),
                    "status", t.get("status").asString()));
        }
        Map<String, Object> packet = new LinkedHashMap<>();
        packet.put("project_id", body.get("project_id").asString());
        // 앱 ObjectMapper 는 null 을 빼고 쓴다 — task_id 가 없으면 키도 없다 (AI 모델은 기본값 None)
        packet.put("task_id", body.hasNonNull("task_id") ? body.get("task_id").asString() : null);
        packet.put("baseline_id", body.get("baseline_id").asString());
        packet.put("request", body.get("request").asString());
        packet.put("search_status", "COMPLETE");
        packet.put("design_items", designItems);
        packet.put("design_item_version_ids", versionIds);
        packet.put("constraints", constraints);
        packet.put("unresolved", noMatch ? List.of("NO_MATCH") : List.of());
        packet.put("unresolved_details", noMatch ? List.of(Map.of("kind", "NO_MATCH", "description", "NO_MATCH")) : List.of());
        packet.put("related_tasks", related);
        packet.put("sources", sources);
        packet.put("direct_match_truncation", null);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("result", Map.of());
        response.put("usage", List.of(Map.of("request_key", "fake-selection-" + UUID.randomUUID(), "model", "test-model",
                "tokens_known", true, "input_tokens", 10, "output_tokens", 0, "latency_ms", 5)));
        response.put("packet", packet);
        return response;
    }

    // ---- 데이터 ----

    /** 현재 기준에 든 승인 문서 한 건 + READY 색인 + 질의와 같은 방향의 청크 하나. 반환은 문서 버전 ID */
    private long indexedDocument(long author) {
        long doc = api.jdbc.sql("insert into document(project_id,type,title,created_by) values (:p,'REQ_SPEC','요구사항',:a) returning id")
                .params(Map.of("p", projectId, "a", author)).query(Long.class).single();
        long version = api.jdbc.sql("""
                insert into document_version(document_id,version_no,status,storage_key,content_hash,content_text,created_by,approved_at)
                values (:d,1,'APPROVED','k.md','doc-hash',:text,:a,now()) returning id
                """).params(Map.of("d", doc, "text", CHUNK, "a", author)).query(Long.class).single();
        api.jdbc.sql("insert into baseline_document(baseline_id,project_id,document_id,document_version_id) values (:b,:p,:d,:v)")
                .params(Map.of("b", baselineId, "p", projectId, "d", doc, "v", version)).update();
        api.jdbc.sql("""
                insert into document_processing(document_version_id,attempt,status,source_status,index_status,text_hash)
                values (:v,1,'COMPLETED','READY','READY','th')
                """).param("v", version).update();
        api.jdbc.sql("""
                insert into document_index_manifest(document_version_id,attempt,text_hash,embedding_profile_id,chunking_profile_id,parser_version,chunk_count)
                values (:v,1,'th','test-profile','c','p',1)
                """).param("v", version).update();
        api.jdbc.sql("""
                insert into document_chunk(document_version_id,chunk_no,chunk_key,text,locator,source_spans,context_refs,embedding)
                values (:v,0,'chk-1',:text,'{"type":"text_lines","start_line":1,"end_line":1}'::jsonb,
                        '[{"block_id":"b","start_offset":0,"end_offset":1}]'::jsonb,'[]'::jsonb,
                        (ARRAY[1.0]::double precision[] || array_fill(0.0::double precision,ARRAY[1023]))::vector)
                """).params(Map.of("v", version, "text", CHUNK)).update();
        return version;
    }

    private Map<String, Object> plan(String title) {
        return Map.of("title", title, "goal", "목표", "included_scope", List.of("a"), "excluded_scope", List.of(),
                "approach", "방법", "completion_criteria", List.of("테스트 통과"), "dependency_task_ids", List.of(),
                "design_refs", List.of(Map.of("design_item_id", String.valueOf(api.designItemId("API-01")),
                        "design_item_version_id", String.valueOf(api.currentVersionId("API-01")), "relation", "READS")),
                "unlinked", List.of(), "no_contract_change", true);
    }

    private int count(String sql) {
        return api.jdbc.sql(sql).query(Integer.class).single();
    }

    private CallToolResult call(String tool, Map<String, Object> args) {
        return McpTestClient.call(member, tool, args);
    }

    private JsonNode ok(CallToolResult result) {
        return McpTestClient.ok(json, result);
    }

    private static Map<String, Object> error(CallToolResult result) {
        return McpTestClient.error(result);
    }
}
