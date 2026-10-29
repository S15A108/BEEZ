package com.beez.backend.validation.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.document.service.HybridDocumentCandidateSearch;
import com.beez.backend.task.dto.DesignRefInput;
import com.beez.backend.task.dto.TaskDetailResponse;
import com.beez.backend.task.dto.TaskPlanInput;
import com.beez.backend.task.dto.TaskPlanRevisionResponse;
import com.beez.backend.task.entity.PlanRelation;
import com.beez.backend.task.entity.TaskStatus;
import com.beez.backend.task.service.RelatedTaskCandidateFinder;
import com.beez.backend.task.service.TaskQueryService;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.simple.JdbcClient;

class ValidationInputAssemblerTest {
    private final CallerContext caller = mock(CallerContext.class);
    private final TaskQueryService tasks = mock(TaskQueryService.class);
    private final HybridDocumentCandidateSearch search = mock(HybridDocumentCandidateSearch.class);
    private final RelatedTaskCandidateFinder related = mock(RelatedTaskCandidateFinder.class);
    private final JdbcClient jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
    private final ValidationEvidenceBuilder evidenceBuilder = mock(ValidationEvidenceBuilder.class);
    private final JsonMapper json = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();
    private ValidationInputAssembler assembler;

    @BeforeEach void setup() {
        assembler = new ValidationInputAssembler(tasks, search, related, jdbc, json, evidenceBuilder);
        when(evidenceBuilder.build(anyLong(), anyLong(), anyList()))
                .thenReturn(new ValidationEvidenceBuilder.Result(List.of(), List.of()));
        when(search.search(eq(caller), eq(1L), anyString(), isNull(), isNull(), eq(20)))
                .thenReturn(new HybridDocumentCandidateSearch.Result("2", List.of(), false, false));
        when(related.find(eq(caller), eq(1L), eq(3L), eq(4L), anyList(), eq(20)))
                .thenReturn(new RelatedTaskCandidateFinder.Result(2, 4, List.of(), false,
                        RelatedTaskCandidateFinder.Coverage.RECORDED_RELATIONSHIPS_ONLY));
    }

    private TaskDetailResponse task(String version) {
        var refs = version == null ? List.<DesignRefInput>of() : List.of(new DesignRefInput("10", version, PlanRelation.READS, null));
        var plan = new TaskPlanInput("회원 조회", "조회", List.of("API"), List.of(), "GET 구현", List.of("조회"), List.of(), refs, List.of(), true, null);
        var rev = new TaskPlanRevisionResponse("4", "3", 1, plan, Instant.parse("2026-01-01T00:00:00Z"), "1", null);
        return new TaskDetailResponse("3", "T-1", "1", "1", TaskStatus.DRAFT, null, 0, rev, "NOT_RUN", null, null, Instant.now(), Instant.now());
    }

    @Test void assemblesAndKeepsCoverageUnknownWithoutPromotingChunkToDesign() {
        when(tasks.getTask(caller, 1L, 3L)).thenReturn(task(null));
        var chunk = new HybridDocumentCandidateSearch.Candidate(Map.of("type", "DOCUMENT_CHUNK", "id", "11",
                "source", Map.of("document_version_id", "22")), Set.of("SEMANTIC"));
        when(jdbc.sql(contains("select distinct bi.design_item_id")).param(anyString(), any()).param(anyString(), any())
                .param(anyString(), any()).query().listOfRows()).thenReturn(List.of());
        when(search.search(eq(caller), eq(1L), anyString(), isNull(), isNull(), eq(20)))
                .thenReturn(new HybridDocumentCandidateSearch.Result("2", List.of(chunk), false, false));
        var out = assembler.assemble(caller, 1, 3, 4, 5, 1, 30000);
        assertEquals("UNKNOWN", out.coverage());
        assertTrue(out.searchComplete());
        assertEquals(List.of(), out.request().get("approved_items"));
        assertEquals(List.of(), out.request().get("evidence"));
        assertTrue(out.request().get("input_snapshot_hash").toString().startsWith("sha256:"));
        var plan = (Map<?, ?>) out.request().get("plan_revision");
        assertEquals("3", plan.get("task_id"));
    }

    @Test void semanticChunkFindsDesignsFromSameApprovedSourceButNeverBecomesEvidence() {
        when(tasks.getTask(caller, 1L, 3L)).thenReturn(task(null));
        var chunk = new HybridDocumentCandidateSearch.Candidate(Map.of("type", "DOCUMENT_CHUNK", "id", "11",
                "source", Map.of("document_version_id", "22")), Set.of("SEMANTIC"));
        when(search.search(eq(caller), eq(1L), anyString(), isNull(), isNull(), eq(20)))
                .thenReturn(new HybridDocumentCandidateSearch.Result("2", List.of(chunk), false, false));
        when(jdbc.sql(contains("select distinct bi.design_item_id")).param(anyString(), any()).param(anyString(), any())
                .param(anyString(), any()).query().listOfRows()).thenReturn(List.of(Map.of("design_item_id", 10L)));
        var row = new LinkedHashMap<String, Object>();
        row.put("item_id", 10L); row.put("key", "API-1"); row.put("kind", "API_CONTRACT");
        row.put("version_id", 101L); row.put("version_no", 1); row.put("status", "APPROVED");
        row.put("schema_version", 1); row.put("content", "{\"description\":\"회원\",\"method\":\"GET\",\"path\":\"/members\"}");
        row.put("content_hash", "hash");
        when(jdbc.sql(contains("select i.id as item_id")).param(anyString(), any()).param(anyString(), any())
                .param(anyString(), any()).query().listOfRows()).thenReturn(List.of(row));
        var out = assembler.assemble(caller, 1, 3, 4, 5, 1, 30000);
        assertEquals(1, ((List<?>) out.request().get("approved_items")).size());
        assertEquals(List.of(), out.request().get("evidence"));
        assertEquals("UNKNOWN", out.coverage());
        assertEquals("candidate-design-101", ((Map<?, ?>) ((List<?>) out.request().get("checks")).get(1)).get("key"));
    }

    @Test void moreThanFiftySourceDesignsMarksSearchIncomplete() {
        when(tasks.getTask(caller, 1L, 3L)).thenReturn(task(null));
        var chunk = new HybridDocumentCandidateSearch.Candidate(Map.of("type", "DOCUMENT_CHUNK", "id", "11",
                "source", Map.of("document_version_id", "22")), Set.of("SEMANTIC"));
        when(search.search(eq(caller), eq(1L), anyString(), isNull(), isNull(), eq(20)))
                .thenReturn(new HybridDocumentCandidateSearch.Result("2", List.of(chunk), false, false));
        List<Map<String, Object>> mappings = new ArrayList<>();
        List<Map<String, Object>> designs = new ArrayList<>();
        for (long id = 1; id <= 51; id++) {
            mappings.add(Map.of("design_item_id", id));
            if (id <= 50) {
                var row = new LinkedHashMap<String, Object>();
                row.put("item_id", id); row.put("key", "API-" + id); row.put("kind", "API_CONTRACT");
                row.put("version_id", id + 100); row.put("version_no", 1); row.put("status", "APPROVED");
                row.put("schema_version", 1); row.put("content", "{\"description\":\"회원\",\"method\":\"GET\",\"path\":\"/members\"}");
                row.put("content_hash", "hash");
                designs.add(row);
            }
        }
        when(jdbc.sql(contains("select distinct bi.design_item_id")).param(anyString(), any()).param(anyString(), any())
                .param(anyString(), any()).query().listOfRows()).thenReturn(mappings);
        when(jdbc.sql(contains("select i.id as item_id")).param(anyString(), any()).param(anyString(), any())
                .param(anyString(), any()).query().listOfRows()).thenReturn(designs);
        var out = assembler.assemble(caller, 1, 3, 4, 5, 1, 30000);
        assertFalse(out.searchComplete());
        assertEquals(50, ((List<?>) out.request().get("approved_items")).size());
    }

    @Test void searchTruncationIsNotReportedComplete() {
        when(tasks.getTask(caller, 1L, 3L)).thenReturn(task(null));
        when(search.search(eq(caller), eq(1L), anyString(), isNull(), isNull(), eq(20)))
                .thenReturn(new HybridDocumentCandidateSearch.Result("2", List.of(), true, false));
        assertFalse(assembler.assemble(caller, 1, 3, 4, 5, 1, 30000).searchComplete());
    }

    @Test void hashIsStableAcrossAttemptsAndTimeBudgets() {
        when(tasks.getTask(caller, 1L, 3L)).thenReturn(task(null));
        var one = assembler.assemble(caller, 1, 3, 4, 5, 1, 30000);
        var retry = assembler.assemble(caller, 1, 3, 4, 5, 2, 20000);
        assertEquals(one.request().get("input_snapshot_hash"), retry.request().get("input_snapshot_hash"));
        assertNotEquals(one.request().get("request_key"), retry.request().get("request_key"));
    }

    @Test void springBootMapperCreatesAssemblerAndProducesSnakeCasePlan() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .withPropertyValues("spring.jackson.property-naming-strategy=SNAKE_CASE")
                .withBean(TaskQueryService.class, () -> tasks)
                .withBean(HybridDocumentCandidateSearch.class, () -> search)
                .withBean(RelatedTaskCandidateFinder.class, () -> related)
                .withBean(JdbcClient.class, () -> jdbc)
                .withBean(ValidationEvidenceBuilder.class, () -> evidenceBuilder)
                .withBean(ValidationInputAssembler.class)
                .run(context -> {
                    assertNotNull(context.getBean(ValidationInputAssembler.class));
                    when(tasks.getTask(caller, 1L, 3L)).thenReturn(task(null));
                    var result = context.getBean(ValidationInputAssembler.class).assemble(caller, 1, 3, 4, 5, 1, 30000);
                    var plan = (Map<?, ?>) result.request().get("plan_revision");
                    assertEquals("3", plan.get("task_id"));
                    assertNotNull(plan.get("revision_no"));
                    assertInstanceOf(String.class, plan.get("created_at"));
                });
    }

    @Test void searchesTitleGoalScopeAndApiPathWithoutClaimingExhaustiveCoverage() {
        var plan = new TaskPlanInput("회원 조회", "회원 정보 확인", List.of("조회 화면"), List.of(),
                "GET /members/{memberId} 구현", List.of("조회"), List.of(), List.of(), List.of(), true, null);
        var revision = new TaskPlanRevisionResponse("4", "3", 1, plan,
                Instant.parse("2026-01-01T00:00:00Z"), "1", null);
        var selected = ValidationInputAssembler.queries(revision);
        assertTrue(selected.queries().contains("회원 조회"));
        assertTrue(selected.queries().contains("회원 정보 확인"));
        assertTrue(selected.queries().contains("조회 화면"));
        assertTrue(selected.queries().contains("/members/{memberId}"));
        assertTrue(selected.queries().contains("GET /members/{memberId} 구현"));
    }

    @Test void includesApprovedVersionAndRejectsStaleReference() {
        when(tasks.getTask(caller, 1L, 3L)).thenReturn(task("101"));
        var row = new LinkedHashMap<String, Object>();
        row.put("item_id", 10L); row.put("key", "API-1"); row.put("kind", "API_CONTRACT");
        row.put("version_id", 101L); row.put("version_no", 1); row.put("status", "APPROVED");
        row.put("schema_version", 1); row.put("content", "{\"description\":\"회원\",\"method\":\"GET\",\"path\":\"/members\"}");
        row.put("content_hash", "hash");
        when(jdbc.sql(contains("select i.id as item_id")).param(anyString(), any()).param(anyString(), any()).param(anyString(), any()).query().listOfRows())
                .thenReturn(List.of(row));
        var out = assembler.assemble(caller, 1, 3, 4, 5, 1, 30000);
        assertEquals(1, ((List<?>) out.request().get("approved_items")).size());
        assertEquals(2, ((List<?>) out.request().get("checks")).size());
        assertEquals("referenced-design-101", ((Map<?, ?>) ((List<?>) out.request().get("checks")).get(1)).get("key"));
        when(tasks.getTask(caller, 1L, 3L)).thenReturn(task("99"));
        assertEquals("stale_design_reference", assertThrows(ApiException.class,
                () -> assembler.assemble(caller, 1, 3, 4, 5, 1, 30000)).getCode());
    }

    @Test void restoredEvidenceIsIncludedAndUnavailableSourceBlocksCompletion() {
        when(tasks.getTask(caller, 1L, 3L)).thenReturn(task("101"));
        var row = new LinkedHashMap<String, Object>();
        row.put("item_id", 10L); row.put("key", "API-1"); row.put("kind", "API_CONTRACT");
        row.put("version_id", 101L); row.put("version_no", 1); row.put("status", "APPROVED");
        row.put("schema_version", 1); row.put("content", "{\"description\":\"회원\",\"method\":\"GET\",\"path\":\"/members\"}");
        row.put("content_hash", "hash");
        when(jdbc.sql(contains("select i.id as item_id")).param(anyString(), any()).param(anyString(), any())
                .param(anyString(), any()).query().listOfRows()).thenReturn(List.of(row));
        var restored = Map.<String, Object>of("evidence_id", "source-101-1-1", "text", "원문\n");
        when(evidenceBuilder.build(eq(1L), eq(2L), anyList()))
                .thenReturn(new ValidationEvidenceBuilder.Result(List.of(restored), List.of("101")));
        var result = assembler.assemble(caller, 1, 3, 4, 5, 1, 30000);
        assertEquals(List.of(restored), result.request().get("evidence"));
        assertEquals(List.of("101"), result.unavailableEvidenceVersionIds());
        assertFalse(result.searchComplete());
    }

    @Test void openQuestionsAndPacketUnresolvedAreIncludedAndBounded() {
        when(jdbc.sql(contains("select q.id,q.question")).param(anyString(), any()).param(anyString(), any())
                .param(anyString(), any()).query().listOfRows()).thenReturn(List.of(
                Map.of("id", 11L, "question", "접근 권한이 미정입니다")));
        when(jdbc.sql(contains("select baseline_id,content::text as content")).param(anyString(), any())
                .param(anyString(), any()).param(anyString(), any()).query().listOfRows()).thenReturn(List.of(
                Map.of("baseline_id", 2L, "content", "{\"unresolved\":[\"권한 정책 확인\"]}")));
        var unresolved = assembler.loadUnresolved(1, 3, 2, Set.of(10L), "22");
        assertEquals(List.of("planning_question:11 접근 권한이 미정입니다", "context_packet:22 권한 정책 확인"), unresolved);
        verify(jdbc).sql(argThat(sql -> sql.contains("q.project_id=:project") && sql.contains("q.status='OPEN'")
                && sql.contains("q.design_item_id in (:ids)") && sql.contains("baseline_document bd")
                && sql.contains("q.design_item_id is null and q.document_version_id is null")));
    }

    @Test void staleOrMalformedContextPacketCannotBecomeEmptyEvidence() {
        when(jdbc.sql(contains("select baseline_id,content::text as content")).param(anyString(), any())
                .param(anyString(), any()).param(anyString(), any()).query().listOfRows()).thenReturn(List.of(
                Map.of("baseline_id", 1L, "content", "{\"unresolved\":[]}")));
        assertEquals(List.of("context_packet:22의 기준 설계가 현재 기준과 달라 재확인이 필요합니다."),
                assembler.loadUnresolved(1, 3, 2, Set.of(10L), "22"));
        when(jdbc.sql(contains("select baseline_id,content::text as content")).param(anyString(), any())
                .param(anyString(), any()).param(anyString(), any()).query().listOfRows()).thenReturn(List.of(
                Map.of("baseline_id", 2L, "content", "{}")));
        assertEquals("invalid_context_packet", assertThrows(ApiException.class,
                () -> assembler.loadUnresolved(1, 3, 2, Set.of(10L), "22")).getCode());
    }

    @Test void tooManyOpenQuestionsAreNotSilentlyTruncated() {
        List<Map<String, Object>> questions = new ArrayList<>();
        for (long id = 1; id <= 101; id++) questions.add(Map.of("id", id, "question", "미확정"));
        when(jdbc.sql(contains("select q.id,q.question")).param(anyString(), any()).param(anyString(), any())
                .param(anyString(), any()).query().listOfRows()).thenReturn(questions);
        assertEquals("unresolved_limit", assertThrows(ApiException.class,
                () -> assembler.loadUnresolved(1, 3, 2, Set.of(), null)).getCode());
    }

    @Test void questionChangesAreIncludedInTheInputSnapshotHash() {
        when(tasks.getTask(caller, 1L, 3L)).thenReturn(task(null));
        when(jdbc.sql(contains("select q.id,q.question")).param(anyString(), any()).param(anyString(), any())
                .param(anyString(), any()).query().listOfRows()).thenReturn(List.of(),
                        List.of(Map.of("id", 11L, "question", "권한 미정")));
        var before = assembler.assemble(caller, 1, 3, 4, 5, 1, 30000);
        var after = assembler.assemble(caller, 1, 3, 4, 5, 2, 30000);
        assertNotEquals(before.request().get("input_snapshot_hash"), after.request().get("input_snapshot_hash"));
        assertEquals(List.of("planning_question:11 권한 미정"), after.request().get("unresolved"));
    }
}
