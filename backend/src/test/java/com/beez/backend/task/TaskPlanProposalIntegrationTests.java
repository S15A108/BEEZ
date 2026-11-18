package com.beez.backend.task;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.support.IntegrationTestSupport;
import com.beez.backend.task.service.TaskPlanDraftService;
import com.beez.backend.task.service.TaskPlanProposalService;
import com.beez.backend.validation.service.ValidationAcceptService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.ObjectMapper;

/** HTTP, real PostgreSQL transactions, AI generation boundary stubbed. */
class TaskPlanProposalIntegrationTests extends IntegrationTestSupport {
    @Autowired DataSource dataSource;
    @Autowired ObjectMapper json;
    @Autowired TaskPlanProposalService service;
    @MockitoBean TaskPlanDraftService drafts;
    @MockitoSpyBean ValidationAcceptService accepts;
    TaskApiSupport api;
    long project, member, leader, task, revision, validation, finding, baseline;

    @BeforeEach void prepare() {
        api = new TaskApiSupport(this::client, dataSource);
        api.resetAndLoadFixture();
        project = api.projectId(); member = api.accountId("member@test"); leader = api.accountId("leader@test");
        var created = api.post(tasks(), api.planJson("로그인", api.implementsRef("API-01"), "[]"), member);
        assertThat(created.statusCode()).isEqualTo(201);
        var body = json.readTree(created.body());
        task = body.get("id").asLong(); revision = body.at("/current_plan_revision/id").asLong();
        validation = api.passValidation(task);
        baseline = api.jdbc.sql("select current_baseline_id from project where id=:p").param("p", project).query(Long.class).single();
        api.jdbc.sql("update validation set status='NEEDS_INPUT', finished_at=now() where id=:v").param("v", validation).update();
        finding = api.jdbc.sql("""
                insert into validation_finding(validation_id,source,kind,severity,message,sources,resolution,next_action)
                values (:v,'RULE','MISSING_INPUT','BLOCKING','목표를 구체화하세요','[]','FIX_PLAN','목표를 수정하세요') returning id
                """).param("v", validation).query(Long.class).single();
        when(drafts.revision(any(), eq(project), any(), anyMap(), anyMap())).thenAnswer(invocation -> {
            Map<String,Object> plan = new LinkedHashMap<>(invocation.getArgument(3));
            plan.put("goal", "사용자는 승인된 로그인 API로 인증한다");
            // Even a compromised model cannot change references, dependencies or unlinked work.
            plan.put("dependency_task_ids", List.of("999999"));
            plan.put("design_refs", List.of());
            plan.put("unlinked", List.of(Map.of("text", "invented")));
            return Map.of("status", "PROPOSED", "baseline_id", Long.toString(baseline), "plan", plan,
                    "changes", List.of(Map.of("field", "goal", "reason", "검증에서 요구한 목표를 구체화", "finding_ids", List.of(Long.toString(finding)))),
                    "evidence", List.of(Map.of("field", "goal", "document_id", "1", "document_version_id", "1", "version_no", 1,
                            "location", "요구사항", "quote", "로그인 API로 인증한다")));
        });
    }

    String tasks() { return "/api/projects/" + project + "/tasks"; }
    String path() { return tasks() + "/" + task + "/plan-revision-proposals"; }
    long propose() {
        var r = api.post(path(), "{}", member);
        assertThat(r.statusCode()).withFailMessage(r.body()).isEqualTo(200);
        return json.readTree(r.body()).get("id").asLong();
    }
    long countRevisions() { return api.jdbc.sql("select count(*) from task_plan_revision where task_id=:t").param("t", task).query(Long.class).single(); }

    @Test void proposalDoesNotWritePlanAndApprovalRevalidatesExactlyOnceIncludingReplay() {
        long id = propose();
        assertThat(countRevisions()).isEqualTo(1);
        assertThat(api.jdbc.sql("select count(*) from validation where task_id=:t").param("t", task).query(Long.class).single()).isEqualTo(1);
        var generated = api.jdbc.sql("select content::text from task_plan_revision_proposal where id=:id").param("id", id).query(String.class).single();
        assertThat(json.readTree(generated).at("/before_plan/design_refs")).isEqualTo(json.readTree(generated).at("/proposed_plan/design_refs"));
        assertThat(json.readTree(generated).at("/before_plan/dependency_task_ids")).isEqualTo(json.readTree(generated).at("/proposed_plan/dependency_task_ids"));
        assertThat(json.readTree(generated).at("/before_plan/unlinked")).isEqualTo(json.readTree(generated).at("/proposed_plan/unlinked"));
        var applied = api.post(path() + "/" + id + "/apply", "{}", member);
        assertThat(applied.statusCode()).withFailMessage(applied.body()).isEqualTo(200);
        var result = json.readTree(applied.body());
        assertThat(result.at("/task/current_plan_revision/revision_no").asInt()).isEqualTo(2);
        assertThat(result.at("/task/validation_status").asText()).isEqualTo(result.at("/validation/status").asText());
        assertThat(result.at("/validation/plan_revision_id")).isEqualTo(result.at("/task/current_plan_revision/id"));
        assertThat(result.at("/validation/id").asLong()).isNotEqualTo(validation);
        var replay = api.post(path() + "/" + id + "/apply", "{}", member);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(json.readTree(replay.body()).at("/validation/id")).isEqualTo(result.at("/validation/id"));
        assertThat(countRevisions()).isEqualTo(2);
    }

    @Test void failedValidationAcceptanceRollsBackRevisionAndCanBeRetried() {
        long id = propose();
        doThrow(new ApiException(HttpStatus.CONFLICT, "validation_attempt_limit", "fixture"))
                .when(accepts).accept(any(), anyLong(), anyLong(), anyLong(), anyInt());
        var failed = api.post(path() + "/" + id + "/apply", "{}", member);
        assertThat(failed.statusCode()).isEqualTo(409);
        assertThat(countRevisions()).isEqualTo(1);
        assertThat(api.jdbc.sql("select current_plan_revision_id from task where id=:t").param("t", task).query(Long.class).single()).isEqualTo(revision);
        assertThat(api.jdbc.sql("select applied_validation_id from task_plan_revision_proposal where id=:id").param("id", id).query(Long.class).optional()).isEmpty();
        doCallRealMethod().when(accepts).accept(any(), anyLong(), anyLong(), anyLong(), anyInt());
        assertThat(api.post(path() + "/" + id + "/apply", "{}", member).statusCode()).isEqualTo(200);
        assertThat(countRevisions()).isEqualTo(2);
    }

    @Test void simultaneousApprovalRequestsCreateOnlyOneRevisionAndValidation() {
        long id = propose();
        var first = java.util.concurrent.CompletableFuture.supplyAsync(() -> api.post(path() + "/" + id + "/apply", "{}", member));
        var second = java.util.concurrent.CompletableFuture.supplyAsync(() -> api.post(path() + "/" + id + "/apply", "{}", member));
        var a = first.join(); var b = second.join();
        assertThat(a.statusCode()).withFailMessage(a.body()).isEqualTo(200);
        assertThat(b.statusCode()).withFailMessage(b.body()).isEqualTo(200);
        assertThat(json.readTree(a.body()).at("/validation/id")).isEqualTo(json.readTree(b.body()).at("/validation/id"));
        assertThat(countRevisions()).isEqualTo(2);
        assertThat(api.jdbc.sql("select count(*) from validation where task_id=:t").param("t", task).query(Long.class).single()).isEqualTo(2);
    }

    @Test void expiredProposalAndChangedSourceVersionAreRejected() {
        long id = propose();
        api.jdbc.sql("update validation set lock_version=lock_version+1 where id=:v").param("v", validation).update();
        var changed = api.post(path() + "/" + id + "/apply", "{}", member);
        assertThat(changed.statusCode()).isEqualTo(409); assertThat(changed.body()).contains("proposal_stale");
        long fresh = propose();
        api.jdbc.sql("update task_plan_revision_proposal set content=jsonb_set(content,'{expires_at}','\"2000-01-01T00:00:00Z\"'::jsonb) where id=:id")
                .param("id", fresh).update();
        var expired = api.post(path() + "/" + fresh + "/apply", "{}", member);
        assertThat(expired.statusCode()).isEqualTo(409); assertThat(expired.body()).contains("proposal_stale");
        assertThat(countRevisions()).isEqualTo(1);
    }

    @Test void newerValidationRejectsOldProposalWithoutWritingRevision() {
        long id = propose(); api.passValidation(task);
        var r = api.post(path() + "/" + id + "/apply", "{}", member);
        assertThat(r.statusCode()).isEqualTo(409); assertThat(r.body()).contains("proposal_stale");
        assertThat(countRevisions()).isEqualTo(1);
    }

    @Test void taskLockChangeRejectsOldProposal() {
        long id = propose();
        api.jdbc.sql("update task set lock_version=lock_version+1 where id=:t").param("t", task).update();
        var r = api.post(path() + "/" + id + "/apply", "{}", member);
        assertThat(r.statusCode()).isEqualTo(409); assertThat(r.body()).contains("proposal_stale");
        assertThat(countRevisions()).isEqualTo(1);
    }

    @Test void changedBaselineRejectsOldProposal() {
        long id = propose(); api.publish(2, Map.of("API-01", api.currentVersionId("API-01"), "API-02", api.currentVersionId("API-02")));
        var r = api.post(path() + "/" + id + "/apply", "{}", member);
        assertThat(r.statusCode()).isEqualTo(409); assertThat(r.body()).contains("proposal_stale");
        assertThat(countRevisions()).isEqualTo(1);
    }

    @Test void ownerAndHumanRequiredForGenerationAndApproval() {
        assertThat(api.post(path(), "{}", leader).statusCode()).isEqualTo(403);
        verifyNoInteractions(drafts);
        long id = propose();
        assertThat(api.post(path() + "/" + id + "/apply", "{}", leader).statusCode()).isEqualTo(403);
        assertThatThrownBy(() -> service.apply(CallerContext.agent(member, api.agentTokenId()), project, task, id))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(countRevisions()).isEqualTo(1);
    }

    @Test void executionFailureCannotGenerateProposalEvenWithFabricatedClientFindings() {
        api.jdbc.sql("update validation set status='ERROR',summary='{\"error_code\":\"evidence_collection_failed\"}' where id=:v")
                .param("v", validation).update();
        var r = api.post(path(), "{\"validation\":{\"status\":\"NEEDS_INPUT\"}}", member);
        assertThat(r.statusCode()).isEqualTo(409); assertThat(r.body()).contains("proposal_not_needed");
        verifyNoInteractions(drafts); assertThat(countRevisions()).isEqualTo(1);
    }

    @Test void unknownFindingCannotBeUsedAsRevisionReason() {
        when(drafts.revision(any(), eq(project), any(), anyMap(), anyMap())).thenReturn(Map.of(
                "status", "PROPOSED", "baseline_id", Long.toString(baseline), "plan", Map.of("goal", "수정목표", "included_scope", List.of("a"),
                        "excluded_scope", List.of(), "approach", "방법", "completion_criteria", List.of("테스트 통과")),
                "changes", List.of(Map.of("field", "goal", "reason", "위조", "finding_ids", List.of("99999"))), "evidence", List.of()));
        assertThat(api.post(path(), "{}", member).statusCode()).isEqualTo(502);
        assertThat(countRevisions()).isEqualTo(1);
    }
}
