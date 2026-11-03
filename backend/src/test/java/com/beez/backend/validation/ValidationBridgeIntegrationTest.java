package com.beez.backend.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.beez.backend.document.service.HybridDocumentCandidateSearch;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.support.IntegrationTestSupport;
import com.beez.backend.task.TaskApiSupport;
import com.beez.backend.validation.service.ValidationAcceptService;
import com.beez.backend.validation.service.ValidationAnalyzer;
import com.beez.backend.validation.worker.ValidationWorker;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

/** Actual DB and unified worker; search and AI are controlled test doubles. */
class ValidationBridgeIntegrationTest extends IntegrationTestSupport {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @Autowired DataSource dataSource;
    @Autowired ValidationWorker worker;
    @Autowired ValidationAcceptService acceptance;
    @MockitoBean HybridDocumentCandidateSearch search;
    @MockitoBean ValidationAnalyzer analyzer;
    private TaskApiSupport api;
    private long project, owner, baseline;

    @BeforeEach void setup() {
        api = new TaskApiSupport(this::client, dataSource);
        api.resetAndLoadFixture();
        project = api.projectId(); owner = api.accountId("member@test");
        baseline = api.jdbc.sql("select current_baseline_id from project where id=:p")
                .param("p", project).query(Long.class).single();
        when(search.search(any(), eq(project), anyString(), isNull(), isNull(), eq(100)))
                .thenReturn(new HybridDocumentCandidateSearch.Result(Long.toString(baseline), List.of(), false, false));
    }

    @Test void acceptedJobUsesExistingRunAndAppliesIncompleteAiResultSafely() throws Exception {
        var task = api.post("/api/projects/" + project + "/tasks",
                api.planJson("회원 확인", api.implementsRef("API-01"), "[]"), owner);
        assertThat(task.statusCode()).as(task.body()).isEqualTo(201);
        long taskId = TaskApiSupport.longField(task.body(), "id");
        long revision = api.jdbc.sql("select current_plan_revision_id from task where id=:t")
                .param("t", taskId).query(Long.class).single();
        long version = TaskApiSupport.longField(task.body(), "lock_version");
        String path = "/api/projects/" + project + "/tasks/" + taskId + "/validations";
        String body = "{\"plan_revision_id\":\"" + revision + "\",\"expected_lock_version\":" + version + "}";
        var accepted = api.postWithHeaders(path, body, owner, "Idempotency-Key", "bridge-test");
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(202);
        long validation = JSON.readTree(accepted.body()).get("id").asLong();
        assertThat(api.jdbc.sql("select count(*) from validation where task_id=:t")
                .param("t", taskId).query(Integer.class).single()).isEqualTo(1);
        AtomicReference<Map<String, Object>> sent = new AtomicReference<>();
        when(analyzer.analyze(any())).thenAnswer(call -> {
            Map<String, Object> request = call.getArgument(0);
            sent.set(request);
            List<Map<String, Object>> unassessed = new ArrayList<>();
            for (Object check : (List<?>) request.get("checks")) {
                unassessed.add(Map.of("check_key", ((Map<?, ?>) check).get("key"),
                        "reason", "EVIDENCE_INSUFFICIENT", "message", "근거 부족"));
            }
            return Map.of("origin", "MODEL", "model", "test-model", "usage", List.of(Map.of(
                    "request_key", "llm-validate-" + validation + "-attempt-1-call-1", "feature", "TASK_VALIDATION",
                    "model", "test-model", "failed", false, "tokens_known", false, "latency_ms", 10)),
                    "result", Map.of("analysis_status", "PARTIAL", "checked_keys", List.of(),
                            "unassessed", unassessed, "findings", List.of()));
        });

        assertThat(worker.runOnce()).isTrue();
        assertThat(sent.get()).as("AI analyzer was invoked after approved context assembly").isNotNull();
        assertThat(api.jdbc.sql("select count(*) from validation_run where validation_id=:v")
                .param("v", validation).query(Integer.class).single()).isEqualTo(1);
        assertThat(api.jdbc.sql("select status from validation where id=:v")
                .param("v", validation).query(String.class).single()).isEqualTo("ERROR");
        assertThat(api.jdbc.sql("select resolution from validation_finding where validation_id=:v and kind='ERROR'")
                .param("v", validation).query(String.class).single()).isEqualTo("HUMAN_REVIEW");
        assertThat(api.jdbc.sql("select usage::text from validation_run where validation_id=:v")
                .param("v", validation).query(String.class).single())
                .contains("llm-validate-" + validation + "-attempt-1-call-1");
    }

    @Test void truncatedSearchClosesRunForHumanReviewWithoutCallingAi() throws Exception {
        when(search.search(any(), eq(project), anyString(), isNull(), isNull(), eq(100)))
                .thenReturn(new HybridDocumentCandidateSearch.Result(Long.toString(baseline), List.of(), true, false));
        var task = api.post("/api/projects/" + project + "/tasks",
                api.planJson("검색 범위 초과", api.implementsRef("API-01"), "[]"), owner);
        assertThat(task.statusCode()).as(task.body()).isEqualTo(201);
        long taskId = TaskApiSupport.longField(task.body(), "id");
        long revision = api.jdbc.sql("select current_plan_revision_id from task where id=:t")
                .param("t", taskId).query(Long.class).single();
        var accepted = api.post("/api/projects/" + project + "/tasks/" + taskId + "/validations",
                "{\"plan_revision_id\":\"" + revision + "\",\"expected_lock_version\":"
                        + TaskApiSupport.longField(task.body(), "lock_version") + "}", owner);
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(202);
        long validation = JSON.readTree(accepted.body()).get("id").asLong();

        assertThat(worker.runOnce()).isTrue();
        verifyNoInteractions(analyzer);
        assertThat(api.jdbc.sql("select status from validation where id=:v")
                .param("v", validation).query(String.class).single()).isEqualTo("ERROR");
        assertThat(api.jdbc.sql("select resolution from validation_finding where validation_id=:v and kind='ERROR'")
                .param("v", validation).query(String.class).single()).isEqualTo("HUMAN_REVIEW");
        assertThat(api.jdbc.sql("select usage::text from validation_run where validation_id=:v")
                .param("v", validation).query(String.class).optional()).isEmpty();
    }

    @Test void realModeStillRequiresSearchAndDoesNotPassWhenSearchFails() {
        when(search.search(any(), eq(project), anyString(), isNull(), isNull(), eq(100)))
                .thenThrow(new IllegalStateException("search unavailable"));
        long[] task = queuedTask();
        var accepted = acceptance.accept(CallerContext.human(owner), project, task[0], task[1], (int) task[2]);
        assertThat(accepted.finished()).isFalse();
        long validation = Long.parseLong(accepted.validation().id());

        assertThat(worker.runOnce()).isTrue();
        verify(search, atLeastOnce()).search(any(), eq(project), anyString(), isNull(), isNull(), eq(100));
        verifyNoInteractions(analyzer);
        assertThat(api.jdbc.sql("select status from validation where id=:v")
                .param("v", validation).query(String.class).single()).isEqualTo("ERROR");
    }

    @Test void searchedDocumentWithFiveUnreferencedDesignsCanPass() throws Exception {
        List<Long> versions = searchDocumentWithFiveDesigns();
        long[] task = queuedTask();
        long validation = Long.parseLong(acceptance.accept(CallerContext.human(owner), project, task[0], task[1],
                (int) task[2]).validation().id());
        AtomicReference<Map<String, Object>> sent = new AtomicReference<>();
        when(analyzer.analyze(any())).thenAnswer(call -> {
            Map<String, Object> request = call.getArgument(0);
            sent.set(request);
            return completeResponse(validation, request, List.of());
        });

        assertThat(worker.runOnce()).isTrue();
        assertThat((List<?>) sent.get().get("approved_items")).hasSize(6);
        assertThat((List<?>) sent.get().get("checks")).hasSize(7);
        assertThat(((List<Map<String, Object>>) sent.get().get("checks")))
                .extracting(c -> c.get("key")).contains("candidate-design-" + versions.getFirst());
        assertThat(api.jdbc.sql("select status from validation where id=:v").param("v", validation)
                .query(String.class).single()).isEqualTo("PASS");
    }

    @Test void blockingFindingOnUnreferencedSearchCandidateStillRequiresReview() throws Exception {
        List<Long> versions = searchDocumentWithFiveDesigns();
        long item = api.jdbc.sql("select design_item_id from design_item_version where id=:v")
                .param("v", versions.getFirst()).query(Long.class).single();
        long[] task = queuedTask();
        long validation = Long.parseLong(acceptance.accept(CallerContext.human(owner), project, task[0], task[1],
                (int) task[2]).validation().id());
        when(analyzer.analyze(any())).thenAnswer(call -> {
            Map<String, Object> request = call.getArgument(0);
            var finding = Map.<String, Object>of("check_key", "candidate-design-" + versions.getFirst(),
                    "kind", "MISMATCH", "severity", "BLOCKING", "design_item_id", Long.toString(item),
                    "design_item_version_id", Long.toString(versions.getFirst()), "message", "설계와 충돌",
                    "suggested_action", "계획을 검토하세요", "sources", List.of(), "evidence_ids", List.of());
            return completeResponse(validation, request, List.of(finding));
        });

        assertThat(worker.runOnce()).isTrue();
        assertThat(api.jdbc.sql("select status from validation where id=:v").param("v", validation)
                .query(String.class).single()).isEqualTo("NEEDS_REVIEW");
        assertThat(api.jdbc.sql("select severity from validation_finding where validation_id=:v and kind='MISMATCH'")
                .param("v", validation).query(String.class).single()).isEqualTo("BLOCKING");
    }

    @Test void changesWithEmptyCompleteResultRequiresHumanReview() throws Exception {
        prepareCompleteApiForCoverage();
        String plan = api.planJson("계약 변경", api.changesRef("API-01"), "[]")
                .replace("\"no_contract_change\": true", "\"no_contract_change\": false");
        assertEmptyResultRequiresHumanReview(plan);
    }

    @Test void unlinkedWithEmptyCompleteResultRequiresHumanReview() throws Exception {
        prepareCompleteApiForCoverage();
        String plan = api.planJson("미연결 범위", api.implementsRef("API-01"), "[]")
                .replace("\"no_contract_change\": true", "\"no_contract_change\": false")
                .replace("\"unlinked\": []", "\"unlinked\": [{\"text\":\"새 응답 필드\"}]");
        assertEmptyResultRequiresHumanReview(plan);
    }

    private void prepareCompleteApiForCoverage() {
        long version = api.jdbc.sql("""
                insert into design_item_version(design_item_id,version_no,status,title,schema_version,content,content_hash,origin,approved_at)
                values (:i,2,'APPROVED','complete API',1,cast(:content as jsonb),'coverage-api','HUMAN',now()) returning id
                """).param("i", api.designItemId("API-01"))
                .param("content", "{\"request_fields\":[],\"responses\":[{\"fields\":[]}]}")
                .query(Long.class).single();
        api.publish(2, Map.of("API-01", version, "API-02", api.currentVersionId("API-02")));
        baseline = api.jdbc.sql("select current_baseline_id from project where id=:p").param("p", project)
                .query(Long.class).single();
        when(search.search(any(), eq(project), anyString(), isNull(), isNull(), eq(100)))
                .thenReturn(new HybridDocumentCandidateSearch.Result(Long.toString(baseline), List.of(), false, false));
    }

    private void assertEmptyResultRequiresHumanReview(String plan) throws Exception {
        var created = api.post("/api/projects/" + project + "/tasks", plan, owner);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        long taskId = TaskApiSupport.longField(created.body(), "id");
        long revision = api.jdbc.sql("select current_plan_revision_id from task where id=:t")
                .param("t", taskId).query(Long.class).single();
        var accepted = acceptance.accept(CallerContext.human(owner), project, taskId, revision,
                (int) TaskApiSupport.longField(created.body(), "lock_version"));
        assertThat(accepted.finished()).isFalse();
        long validation = Long.parseLong(accepted.validation().id());
        when(analyzer.analyze(any())).thenAnswer(call -> completeResponse(validation, call.getArgument(0), List.of()));

        assertThat(worker.runOnce()).isTrue();
        verify(analyzer).analyze(any());
        assertThat(api.jdbc.sql("select status from validation where id=:v").param("v", validation)
                .query(String.class).single()).isEqualTo("ERROR");
        assertThat(api.jdbc.sql("select resolution from validation_finding where validation_id=:v and kind='ERROR'")
                .param("v", validation).query(String.class).single()).isEqualTo("HUMAN_REVIEW");
    }

    @Test void currentContextPacketWithConstraintsCanPass() throws Exception {
        long[] task = queuedTaskWithPacket("{\"constraints\":[\"접근 로그 기록\"],\"unresolved\":[]}");
        long validation = Long.parseLong(acceptance.accept(CallerContext.human(owner), project, task[0], task[1],
                (int) task[2]).validation().id());
        AtomicReference<Map<String, Object>> sent = new AtomicReference<>();
        when(analyzer.analyze(any())).thenAnswer(call -> {
            Map<String, Object> request = call.getArgument(0);
            sent.set(request);
            return completeResponse(validation, request, List.of());
        });

        assertThat(worker.runOnce()).isTrue();
        assertThat(sent.get()).isNotNull();
        assertThat(sent.get().get("context_packet_constraints")).isEqualTo(List.of("접근 로그 기록"));
        assertThat(sent.get().get("unresolved")).isEqualTo(List.of());
        assertThat(api.jdbc.sql("select status from validation where id=:v").param("v", validation)
                .query(String.class).single()).isEqualTo("PASS");
    }

    @Test void contextPacketUnresolvedCannotPassEvenIfAnalyzerClaimsComplete() throws Exception {
        long[] task = queuedTaskWithPacket("{\"constraints\":[],\"unresolved\":[\"접근 정책 확인\"]}");
        long validation = Long.parseLong(acceptance.accept(CallerContext.human(owner), project, task[0], task[1],
                (int) task[2]).validation().id());
        AtomicReference<Map<String, Object>> sent = new AtomicReference<>();
        when(analyzer.analyze(any())).thenAnswer(call -> {
            Map<String, Object> request = call.getArgument(0);
            sent.set(request);
            return completeResponse(validation, request, List.of());
        });

        assertThat(worker.runOnce()).isTrue();
        assertThat(sent.get()).isNotNull();
        assertThat((List<String>) sent.get().get("unresolved"))
                .singleElement().asString().contains("접근 정책 확인");
        assertThat(api.jdbc.sql("select status from validation where id=:v").param("v", validation)
                .query(String.class).single()).isEqualTo("ERROR");
        assertThat(api.jdbc.sql("select resolution from validation_finding where validation_id=:v and kind='ERROR'")
                .param("v", validation).query(String.class).single()).isEqualTo("HUMAN_REVIEW");
    }

    /** Seed a current packet and create a plan through the public API, as an MCP workflow does. */
    private long[] queuedTaskWithPacket(String packetContent) {
        long version = api.jdbc.sql("""
                insert into design_item_version(design_item_id,version_no,status,title,schema_version,content,content_hash,origin,approved_at)
                values (:i,2,'APPROVED','complete API',1,cast(:content as jsonb),'packet-api','HUMAN',now()) returning id
                """).param("i", api.designItemId("API-01"))
                .param("content", "{\"request_fields\":[],\"responses\":[{\"fields\":[]}]}")
                .query(Long.class).single();
        api.publish(2, Map.of("API-01", version, "API-02", api.currentVersionId("API-02")));
        baseline = api.jdbc.sql("select current_baseline_id from project where id=:p").param("p", project)
                .query(Long.class).single();
        when(search.search(any(), eq(project), anyString(), isNull(), isNull(), eq(100)))
                .thenReturn(new HybridDocumentCandidateSearch.Result(Long.toString(baseline), List.of(), false, false));
        long packet = api.jdbc.sql("""
                insert into dev_context_packet(project_id,request,baseline_id,content,content_hash)
                values (:p,'검증 패킷',:b,cast(:content as jsonb),'packet-hash') returning id
                """).param("p", project).param("b", baseline).param("content", packetContent)
                .query(Long.class).single();
        String plan = api.planJson("패킷 기반 검증", api.implementsRef("API-01"), "[]");
        plan = plan.substring(0, plan.lastIndexOf('}')) + ", \"context_packet_id\": \"" + packet + "\"}";
        var response = api.post("/api/projects/" + project + "/tasks", plan, owner);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        long taskId = TaskApiSupport.longField(response.body(), "id");
        long revision = api.jdbc.sql("select current_plan_revision_id from task where id=:t").param("t", taskId)
                .query(Long.class).single();
        return new long[] {taskId, revision, TaskApiSupport.longField(response.body(), "lock_version")};
    }

    /** Six approved items, five discovered from the same document and absent from the plan. */
    private List<Long> searchDocumentWithFiveDesigns() {
        long apiVersion = api.jdbc.sql("""
                insert into design_item_version(design_item_id,version_no,status,title,schema_version,content,content_hash,origin,approved_at)
                values (:i,2,'APPROVED','complete API',1,cast(:content as jsonb),'complete-api','HUMAN',now()) returning id
                """).param("i", api.designItemId("API-01"))
                .param("content", "{\"request_fields\":[],\"responses\":[{\"fields\":[]}]}")
                .query(Long.class).single();
        api.publish(2, Map.of("API-01", apiVersion, "API-02", api.currentVersionId("API-02")));
        baseline = api.jdbc.sql("select current_baseline_id from project where id=:p").param("p", project).query(Long.class).single();
        long doc = api.jdbc.sql("insert into document(project_id,type,title,created_by) values (:p,'API_SPEC','설계 문서',:a) returning id")
                .param("p", project).param("a", owner).query(Long.class).single();
        String text = "설계 요구 사항\n";
        String hash;
        try {
            hash = "sha256:" + java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
        long docVersion = api.jdbc.sql("""
                insert into document_version(document_id,version_no,status,storage_key,content_hash,content_text,approved_at)
                values (:d,1,'APPROVED',:key,:hash,:text,now()) returning id
                """).param("d", doc).param("key", "candidate-doc-" + doc).param("hash", hash).param("text", text)
                .query(Long.class).single();
        api.jdbc.sql("insert into document_processing(document_version_id,status,text_hash) values (:v,'COMPLETED',:h)")
                .param("v", docVersion).param("h", hash).update();
        api.jdbc.sql("insert into baseline_document(baseline_id,project_id,document_id,document_version_id) values (:b,:p,:d,:v)")
                .param("b", baseline).param("p", project).param("d", doc).param("v", docVersion).update();
        List<Long> versions = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            // A candidate API with incomplete structured content is left to the analyzer;
            // only an explicitly referenced API has a mandatory structural coverage guard.
            long item = api.jdbc.sql("insert into design_item(project_id,key,kind) values (:p,:k,:kind) returning id")
                    .param("p", project).param("k", "REQ-CANDIDATE-" + i)
                    .param("kind", i == 0 ? "API_CONTRACT" : "REQUIREMENT").query(Long.class).single();
            long version = api.jdbc.sql("""
                    insert into design_item_version(design_item_id,version_no,status,title,schema_version,content,content_hash,
                            origin,approved_at,source_document_version_id,source_locator)
                    values (:i,1,'APPROVED',:title,1,'{}',:hash,'HUMAN',now(),:d,cast(:locator as jsonb)) returning id
                    """).param("i", item).param("title", "요구 사항 " + i).param("hash", "requirement-" + i)
                    .param("d", docVersion).param("locator", "{\"location\":\"1행\"}").query(Long.class).single();
            api.jdbc.sql("insert into baseline_item(baseline_id,project_id,design_item_id,design_item_version_id) values (:b,:p,:i,:v)")
                    .param("b", baseline).param("p", project).param("i", item).param("v", version).update();
            versions.add(version);
        }
        var hit = new HybridDocumentCandidateSearch.Candidate(Map.of("type", "DOCUMENT", "id", Long.toString(doc),
                "source", Map.of("document_version_id", Long.toString(docVersion))), Set.of("KEYWORD"));
        when(search.search(any(), eq(project), anyString(), isNull(), isNull(), eq(100)))
                .thenReturn(new HybridDocumentCandidateSearch.Result(Long.toString(baseline), List.of(hit), false, false));
        return versions;
    }

    private Map<String, Object> completeResponse(long validation, Map<String, Object> request,
                                                  List<Map<String, Object>> findings) {
        List<Map<String, Object>> checks = (List<Map<String, Object>>) request.get("checks");
        return Map.of("origin", "MODEL", "model", "test-model", "usage", List.of(Map.of(
                "request_key", "llm-validate-" + validation + "-attempt-1-call-1", "feature", "TASK_VALIDATION",
                "model", "test-model", "failed", false, "tokens_known", false, "latency_ms", 10)),
                "result", Map.of("analysis_status", "COMPLETE", "checked_keys", checks.stream().map(c -> c.get("key")).toList(),
                        "unassessed", List.of(), "findings", findings));
    }

    private long[] queuedTask() {
        var task = api.post("/api/projects/" + project + "/tasks",
                api.planJson("검증 요청자 확인", api.implementsRef("API-01"), "[]"), owner);
        assertThat(task.statusCode()).as(task.body()).isEqualTo(201);
        long taskId = TaskApiSupport.longField(task.body(), "id");
        long revision = api.jdbc.sql("select current_plan_revision_id from task where id=:t")
                .param("t", taskId).query(Long.class).single();
        return new long[] { taskId, revision, TaskApiSupport.longField(task.body(), "lock_version") };
    }

    private void assertClosedWithoutSearchOrAi(long validation) {
        assertThat(worker.runOnce()).isTrue();
        verifyNoInteractions(search, analyzer);
        assertThat(api.jdbc.sql("select status from validation where id=:v")
                .param("v", validation).query(String.class).single()).isEqualTo("ERROR");
        assertThat(api.jdbc.sql("select status from validation_run where validation_id=:v")
                .param("v", validation).query(String.class).single()).isEqualTo("ERROR");
    }

    /** Use #77's real completion report producer, rather than mutating the analyzer request. */
    private long[] queuedDelta() {
        long[] task = queuedTask();
        long pass = api.passValidation(task[0]);
        String path = "/api/projects/" + project + "/tasks/" + task[0] + "/state-events";
        var started = api.post(path, "{\"event\":\"START\",\"plan_revision_id\":\"" + task[1]
                + "\",\"expected_lock_version\":" + task[2] + ",\"validation_id\":\"" + pass + "\"}", owner);
        assertThat(started.statusCode()).as(started.body()).isEqualTo(200);
        var done = api.post(path, "{\"event\":\"COMPLETED\",\"plan_revision_id\":\"" + task[1]
                + "\",\"expected_lock_version\":" + TaskApiSupport.longField(started.body(), "lock_version")
                + ",\"delta\":[{\"kind\":\"CHANGE\",\"text\":\"완료 기준과 다르게 구현\"}]}", owner);
        assertThat(done.statusCode()).as(done.body()).isEqualTo(202);
        return new long[] {task[0], task[1], TaskApiSupport.longField(done.body(), "delta_validation_id")};
    }

    @Test void completionDeltaMismatchWithoutDesignReferenceIsStoredAndBlocksPass() throws Exception {
        long[] task = queuedDelta();
        long validation = task[2];
        AtomicReference<Map<String, Object>> sent = new AtomicReference<>();
        when(analyzer.analyze(any())).thenAnswer(call -> {
            Map<String, Object> request = call.getArgument(0);
            sent.set(request);
            List<Map<String, Object>> checks = (List<Map<String, Object>>) request.get("checks");
            return Map.of("origin", "MODEL", "model", "test-model", "usage", List.of(Map.of(
                    "request_key", "llm-validate-" + validation + "-attempt-1-call-1", "feature", "TASK_VALIDATION",
                    "model", "test-model", "failed", false, "tokens_known", false, "latency_ms", 10)),
                    "result", Map.of("analysis_status", "COMPLETE", "checked_keys", checks.stream().map(c -> c.get("key")).toList(),
                            "unassessed", List.of(), "findings", List.of(Map.of(
                                    "check_key", "completion-delta", "kind", "MISMATCH", "severity", "BLOCKING",
                                    "message", "완료 보고가 계획과 다릅니다", "suggested_action", "완료 내용을 검토하세요",
                                    "sources", List.of(), "evidence_ids", List.of()))));
        });

        assertThat(worker.runOnce()).isTrue();
        assertThat(sent.get()).as("actual DELTA producer reached the analyzer").isNotNull();
        assertThat(sent.get().get("completion_delta"))
                .isEqualTo(List.of(Map.of("kind", "CHANGE", "text", "완료 기준과 다르게 구현")));
        assertThat((List<Map<String, Object>>) sent.get().get("checks")).anySatisfy(check ->
                assertThat(check).containsEntry("key", "completion-delta").containsEntry("kind", "PLAN_COMPLETENESS"));
        assertThat(api.jdbc.sql("select status from validation where id=:v").param("v", validation)
                .query(String.class).single()).isEqualTo("NEEDS_REVIEW");
        var finding = api.jdbc.sql("select kind,severity,design_item_id,design_item_version_id,related_task_id,check_key "
                + "from validation_finding where validation_id=:v and source='MODEL'")
                .param("v", validation).query().singleRow();
        assertThat(finding).containsEntry("kind", "MISMATCH").containsEntry("severity", "BLOCKING")
                .containsEntry("check_key", "completion-delta")
                .containsEntry("design_item_id", null).containsEntry("design_item_version_id", null)
                .containsEntry("related_task_id", null);
        assertThat(api.jdbc.sql("select status from task where id=:t").param("t", task[0])
                .query(String.class).single()).isEqualTo("WAITING");
    }

    @Test void completionDeltaCanCiteApprovedSearchCandidateAndItsEvidence() throws Exception {
        long candidateVersion = searchDocumentWithFiveDesigns().getFirst();
        long candidateItem = api.jdbc.sql("select design_item_id from design_item_version where id=:v")
                .param("v", candidateVersion).query(Long.class).single();
        long[] task = queuedDelta();
        long validation = task[2];
        AtomicReference<Map<String, Object>> sent = new AtomicReference<>();
        when(analyzer.analyze(any())).thenAnswer(call -> {
            Map<String, Object> request = call.getArgument(0);
            sent.set(request);
            var evidence = ((List<Map<String, Object>>) request.get("evidence")).stream()
                    .filter(entry -> Long.toString(candidateVersion).equals(entry.get("design_item_version_id")))
                    .findFirst().orElseThrow();
            var finding = Map.<String, Object>of("check_key", "completion-delta", "kind", "MISMATCH",
                    "severity", "BLOCKING", "design_item_id", Long.toString(candidateItem),
                    "design_item_version_id", Long.toString(candidateVersion), "message", "완료 보고가 승인 설계와 다릅니다",
                    "suggested_action", "승인 설계와 비교하세요", "sources", List.of(evidence.get("source")),
                    "evidence_ids", List.of(evidence.get("evidence_id")));
            return completeResponse(validation, request, List.of(finding));
        });

        assertThat(worker.runOnce()).isTrue();
        assertThat((List<Map<String, Object>>) sent.get().get("checks")).anySatisfy(check -> {
            assertThat(check).containsEntry("key", "completion-delta");
            assertThat((List<String>) check.get("design_item_version_ids")).contains(Long.toString(candidateVersion));
        });
        assertThat(api.jdbc.sql("select status from validation where id=:v").param("v", validation)
                .query(String.class).single()).isEqualTo("NEEDS_REVIEW");
        var finding = api.jdbc.sql("""
                select design_item_id,design_item_version_id,check_key,evidence_ids::text as evidence_ids,sources::text as sources
                from validation_finding where validation_id=:v and source='MODEL'
                """).param("v", validation).query().singleRow();
        assertThat(finding).containsEntry("design_item_id", candidateItem)
                .containsEntry("design_item_version_id", candidateVersion).containsEntry("check_key", "completion-delta");
        assertThat(finding.get("evidence_ids").toString()).contains("source-" + candidateVersion + "-1-1");
        assertThat(finding.get("sources").toString()).contains("document_version_id");
        assertThat(api.jdbc.sql("select status from task where id=:t").param("t", task[0])
                .query(String.class).single()).isEqualTo("WAITING");
    }

    @Test void removedCompletionReporterCannotSearchOrCallAiFromDeltaQueue() {
        long[] task = queuedDelta();
        api.jdbc.sql("update project_member set active=false where project_id=:p and account_id=:a")
                .param("p", project).param("a", owner).update();
        assertClosedWithoutSearchOrAi(task[2]);
    }

    @Test void removedMemberCannotSearchOrCallAiFromQueuedWork() {
        long[] task = queuedTask();
        var accepted = acceptance.accept(CallerContext.human(owner), project, task[0], task[1], (int) task[2]);
        assertThat(accepted.finished()).isFalse();
        long validation = Long.parseLong(accepted.validation().id());
        api.jdbc.sql("update project_member set active=false where project_id=:p and account_id=:a")
                .param("p", project).param("a", owner).update();
        assertClosedWithoutSearchOrAi(validation);
    }

    @Test void revokedAgentCannotSearchOrCallAiFromQueuedWork() {
        long[] task = queuedTask();
        long token = api.agentTokenId();
        var accepted = acceptance.accept(CallerContext.agent(owner, token), project, task[0], task[1], (int) task[2]);
        assertThat(accepted.finished()).isFalse();
        long validation = Long.parseLong(accepted.validation().id());
        api.jdbc.sql("update agent_token set revoked_at=now() where id=:id").param("id", token).update();
        assertClosedWithoutSearchOrAi(validation);
    }

    @Test void oldQueueWithoutRequesterCannotSearchOrCallAi() {
        long[] task = queuedTask();
        var accepted = acceptance.accept(CallerContext.human(owner), project, task[0], task[1], (int) task[2]);
        assertThat(accepted.finished()).isFalse();
        long validation = Long.parseLong(accepted.validation().id());
        api.jdbc.sql("update validation set summary=summary - 'request_actor' where id=:id")
                .param("id", validation).update();
        assertClosedWithoutSearchOrAi(validation);
    }

    @Test void leaseExpiringDuringSearchDoesNotCallAiAndClosesForRetry() {
        long[] task = queuedTask();
        var accepted = acceptance.accept(CallerContext.human(owner), project, task[0], task[1], (int) task[2]);
        assertThat(accepted.finished()).isFalse();
        long validation = Long.parseLong(accepted.validation().id());
        when(search.search(any(), eq(project), anyString(), isNull(), isNull(), eq(100))).thenAnswer(call -> {
            // The AI input search takes long enough for the worker's lease to expire;
            // the separate recovery sweep has not yet run.
            api.jdbc.sql("update validation_job set lease_until=now()-interval '1 second' where validation_id=:v")
                    .param("v", validation).update();
            return new HybridDocumentCandidateSearch.Result(Long.toString(baseline), List.of(), false, false);
        });

        assertThat(worker.runOnce()).isTrue();
        verifyNoInteractions(analyzer);
        assertThat(api.jdbc.sql("select status from validation where id=:v")
                .param("v", validation).query(String.class).single()).isEqualTo("ERROR");
        assertThat(api.jdbc.sql("select status from validation_run where validation_id=:v")
                .param("v", validation).query(String.class).single()).isEqualTo("ERROR");
        assertThat(api.jdbc.sql("select resolution from validation_finding where validation_id=:v and kind='ERROR'")
                .param("v", validation).query(String.class).single()).isEqualTo("RETRY");
    }

    @Test void relatedTaskAddedDuringAiCallMakesResultStale() throws Exception {
        long[] task = queuedTask();
        var accepted = acceptance.accept(CallerContext.human(owner), project, task[0], task[1], (int) task[2]);
        assertThat(accepted.finished()).isFalse();
        long validation = Long.parseLong(accepted.validation().id());
        when(analyzer.analyze(any())).thenAnswer(call -> {
            var related = api.post("/api/projects/" + project + "/tasks",
                    api.planJson("새 관련 작업", api.readsRef("API-01"), "[]"), owner);
            assertThat(related.statusCode()).as(related.body()).isEqualTo(201);
            return Map.of("origin", "MODEL", "model", "test-model", "usage", List.of(),
                    "result", Map.of("analysis_status", "COMPLETE", "checked_keys", List.of(),
                            "unassessed", List.of(), "findings", List.of()));
        });
        assertThat(worker.runOnce()).isTrue();
        verify(analyzer).analyze(any());
        assertThat(api.jdbc.sql("select status from validation where id=:v")
                .param("v", validation).query(String.class).single()).isEqualTo("STALE");
        assertThat(api.jdbc.sql("select status from validation_run where validation_id=:v")
                .param("v", validation).query(String.class).single()).isEqualTo("DISCARDED");
    }

    @Test void unrelatedBaselinePublishedDuringAiCallDiscardsResultAndPreservesUsage() throws Exception {
        long[] task = queuedTask();
        var accepted = acceptance.accept(CallerContext.human(owner), project, task[0], task[1], (int) task[2]);
        assertThat(accepted.finished()).isFalse();
        long validation = Long.parseLong(accepted.validation().id());
        when(analyzer.analyze(any())).thenAnswer(call -> {
            Map<String, Object> request = call.getArgument(0);
            // Publish a new baseline with the same approved versions while the provider is in flight.
            // The already-assembled search result still belongs to the previous baseline.
            api.publish(2, Map.of("API-01", api.currentVersionId("API-01"),
                    "API-02", api.currentVersionId("API-02")));
            List<String> checked = ((List<?>) request.get("checks")).stream()
                    .map(check -> ((Map<?, ?>) check).get("key").toString()).toList();
            return Map.of("origin", "MODEL", "model", "test-model", "usage", List.of(Map.of(
                    "request_key", "llm-validate-" + validation + "-attempt-1-call-1", "feature", "TASK_VALIDATION",
                    "model", "test-model", "failed", false, "tokens_known", true,
                    "input_tokens", 10, "output_tokens", 5, "latency_ms", 10)),
                    "result", Map.of("analysis_status", "COMPLETE", "checked_keys", checked,
                            "unassessed", List.of(), "findings", List.of()));
        });

        assertThat(worker.runOnce()).isTrue();
        verify(analyzer).analyze(any());
        assertThat(api.jdbc.sql("select status from validation where id=:v")
                .param("v", validation).query(String.class).single()).isEqualTo("STALE");
        assertThat(api.jdbc.sql("select status from validation_run where validation_id=:v")
                .param("v", validation).query(String.class).single()).isEqualTo("DISCARDED");
        assertThat(api.jdbc.sql("select usage::text from validation_run where validation_id=:v")
                .param("v", validation).query(String.class).single())
                .contains("llm-validate-" + validation + "-attempt-1-call-1");
    }

}
