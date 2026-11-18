package com.beez.backend.notification.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.beez.backend.ai.AiClient;
import com.beez.backend.changediscussion.dto.CreateChangeCommentRequest;
import com.beez.backend.changediscussion.service.ChangeDiscussionService;
import com.beez.backend.changeproposal.dto.ProposalDtos.Input;
import com.beez.backend.changeproposal.dto.ProposalDtos.Item;
import com.beez.backend.changeproposal.dto.ProposalDtos.Proposal;
import com.beez.backend.changeproposal.dto.ProposalDtos.RejectRequest;
import com.beez.backend.changeproposal.dto.ProposalDtos.WithdrawRequest;
import com.beez.backend.changeproposal.dto.PublicationDtos.ApproveRequest;
import com.beez.backend.changeproposal.service.ProposalCommandService;
import com.beez.backend.changeproposal.service.ProposalPublicationService;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.document.storage.DocumentStorage;
import com.beez.backend.impactanalysis.ImpactRunExecutor;
import com.beez.backend.support.IntegrationTestSupport;
import com.beez.backend.task.TaskApiSupport;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 변경 제안·의견 활동 연결 (S15P31A108-310, 계약 10 §4·§5·§6.2). 활동은 도메인 변경과 같은 트랜잭션에서 저장되고,
 * 실제 전이가 일어났을 때만 한 건이며(재생·이미 같은 상태의 재요청은 늘지 않음), 의견·반려 사유 원문은 담지 않는다.
 * {@code change_proposal_id} 로 MON-A04 가 한 제안의 지나온 길을 돌려준다(S15P31A108-303 이 이 조회를 쓴다).
 */
class ChangeActivityIntegrationTest extends IntegrationTestSupport {
    @Autowired DataSource dataSource;
    @Autowired ProposalCommandService proposals;
    @Autowired ProposalPublicationService publications;
    @Autowired ChangeDiscussionService discussions;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean AiClient ai;
    @MockitoBean ImpactRunExecutor executor;
    @MockitoBean DocumentStorage storage;

    TaskApiSupport api;
    long project, baseline, member, leader;
    CallerContext memberCaller, leaderCaller;

    @BeforeEach void setup() throws java.io.IOException {
        api = new TaskApiSupport(this::client, dataSource);
        api.resetAndLoadFixture();
        project = api.projectId();
        member = api.accountId("member@test");
        leader = api.accountId("leader@test");
        memberCaller = CallerContext.human(member);
        leaderCaller = CallerContext.human(leader);
        baseline = api.jdbc.sql("SELECT current_baseline_id FROM project WHERE id=:p").param("p", project).query(Long.class).single();
        when(executor.submit(any())).thenReturn(true);
        when(storage.store(any(), any())).thenReturn("fixture/stored.md");
        api.jdbc.sql("DELETE FROM activity_event WHERE project_id=:p").param("p", project).update();
    }

    Proposal propose(String key) {
        return proposals.create(memberCaller, project, new Input(Long.toString(baseline), "API 폐기", "더 이상 쓰지 않음", null, null,
                List.of(new Item("RETIRE", api.designItemId("API-01").toString(), api.currentVersionId("API-01").toString(),
                        null, null, null)), List.of(), null, null, null), key);
    }

    long id(Proposal proposal) {
        return Long.parseLong(proposal.id());
    }

    /** 한 제안의 활동을 id 오름차순(저장 순서)으로. */
    List<Map<String, Object>> activities(Proposal proposal) {
        return api.jdbc.sql("SELECT * FROM activity_event WHERE project_id=:p AND change_proposal_id=:c ORDER BY id")
                .param("p", project).param("c", id(proposal)).query().listOfRows();
    }

    List<String> types(Proposal proposal) {
        return activities(proposal).stream().map(row -> row.get("event_type").toString()).toList();
    }

    List<String> keys(Proposal proposal) {
        return activities(proposal).stream().map(row -> row.get("dedupe_key").toString()).toList();
    }

    long count() {
        return api.jdbc.sql("SELECT count(*) FROM activity_event WHERE project_id=:p").param("p", project).query(Long.class).single();
    }

    @Test void submissionLeavesOneActivityForTheProposerAndReplayAddsNone() {
        var created = propose("submit-1");

        assertThat(activities(created)).singleElement().satisfies(row -> {
            assertThat(row.get("event_type")).isEqualTo("CHANGE_PROPOSAL_SUBMITTED");
            assertThat(row.get("actor_kind")).isEqualTo("HUMAN");
            assertThat(row.get("actor_id")).isEqualTo(member);
            assertThat(row.get("ref_kind")).isEqualTo("CHANGE_PROPOSAL");
            assertThat(row.get("ref_id")).isEqualTo(id(created));
            assertThat(row.get("result")).isEqualTo("SUCCESS");
            assertThat(row.get("dedupe_key")).isEqualTo("change-proposal:" + created.id() + ":submitted:1");
        });
        // 같은 키의 재요청은 저장 전에 멱등 응답으로 끝난다
        assertThat(propose("submit-1")).isEqualTo(created);
        assertThat(activities(created)).hasSize(1);
    }

    @Test void reviewStartIsRecordedOnceAndRepeatingItAddsNothing() {
        var created = propose("review-1");
        var request = new WithdrawRequest(created.revision(), created.snapshotHash());

        proposals.startReview(leaderCaller, project, id(created), request, "start-1");
        proposals.startReview(leaderCaller, project, id(created), request, "start-1");   // 멱등 재생
        proposals.startReview(leaderCaller, project, id(created), request, "start-2");   // 이미 IN_REVIEW 인 새 요청

        assertThat(types(created)).containsExactly("CHANGE_PROPOSAL_SUBMITTED", "CHANGE_PROPOSAL_REVIEW_STARTED");
        assertThat(keys(created)).last().isEqualTo("change-proposal:" + created.id() + ":review-started:1");
        assertThat(activities(created).getLast().get("actor_id")).isEqualTo(leader);
    }

    @Test void rejectionAndResubmissionKeepEachRevisionOnTheProposalsTimeline() {
        var created = propose("reject-1");
        var reject = new RejectRequest("민감한 사유 원문", created.revision(), created.snapshotHash());

        var rejected = proposals.reject(leaderCaller, project, id(created), reject, "rej-1");
        proposals.reject(leaderCaller, project, id(created), reject, "rej-1");

        assertThat(types(created)).containsExactly("CHANGE_PROPOSAL_SUBMITTED", "CHANGE_PROPOSAL_REJECTED");
        assertThat(keys(created)).last().isEqualTo("change-proposal:" + created.id() + ":rejected:1");
        // 사유 원문은 타임라인에 들어가지 않는다(멤버 전원이 본다)
        assertThat(activities(created).toString()).doesNotContain("민감한 사유 원문");

        var next = new Input(Long.toString(baseline), "API 폐기", "근거 보완", null, null,
                List.of(new Item("RETIRE", api.designItemId("API-01").toString(), api.currentVersionId("API-01").toString(),
                        null, null, null)), List.of(), rejected.revision(), rejected.snapshotHash(), null);
        var second = proposals.resubmit(memberCaller, project, id(created), next, "resubmit-1");
        proposals.startReview(leaderCaller, project, id(created), new WithdrawRequest(second.revision(), second.snapshotHash()), "start-2");

        assertThat(keys(created)).containsExactly(
                "change-proposal:" + created.id() + ":submitted:1",
                "change-proposal:" + created.id() + ":rejected:1",
                "change-proposal:" + created.id() + ":submitted:2",
                "change-proposal:" + created.id() + ":review-started:2");
    }

    @Test void withdrawalIsRecordedOnceAndAFailedWithdrawalLeavesNothing() {
        var created = propose("withdraw-1");
        var request = new WithdrawRequest(created.revision(), created.snapshotHash());

        proposals.withdraw(memberCaller, project, id(created), request, "w-1");
        proposals.withdraw(memberCaller, project, id(created), request, "w-1");   // 멱등 재생
        assertThatThrownBy(() -> proposals.withdraw(memberCaller, project, id(created), request, "w-2"))
                .hasMessageContaining("거둘 수");                                    // 이미 거둔 제안은 invalid_transition

        assertThat(types(created)).containsExactly("CHANGE_PROPOSAL_SUBMITTED", "CHANGE_PROPOSAL_WITHDRAWN");
    }

    @Test void publicationLeavesApprovedThenPublishedAndRepeatingTheApprovalAddsNothing() {
        var created = propose("publish-1");
        var approval = new ApproveRequest(Long.toString(baseline), created.revision(), created.snapshotHash(), "직접 연결을 확인했습니다.");

        publications.approve(leaderCaller, project, id(created), approval, "approve-1");
        long afterFirst = count();
        publications.approve(leaderCaller, project, id(created), approval, "approve-1");   // 멱등 재생
        publications.approve(leaderCaller, project, id(created), approval, "approve-2");   // 이미 발행됨

        // APPROVED 가 먼저 저장돼 id 내림차순 타임라인에서는 발행이 위에 온다
        assertThat(types(created)).containsExactly("CHANGE_PROPOSAL_SUBMITTED", "CHANGE_PROPOSAL_APPROVED", "CHANGE_PROPOSAL_PUBLISHED");
        assertThat(keys(created)).containsExactly(
                "change-proposal:" + created.id() + ":submitted:1",
                "change-proposal:" + created.id() + ":approved",
                "change-proposal:" + created.id() + ":published");
        assertThat(activities(created).getLast().get("baseline_id"))
                .isEqualTo(api.jdbc.sql("SELECT published_baseline_id FROM change_proposal WHERE id=:c").param("c", id(created))
                        .query(Long.class).single());
        assertThat(activities(created)).allSatisfy(row -> assertThat(row.get("result")).isEqualTo("SUCCESS"));
        assertThat(count()).isEqualTo(afterFirst);
    }

    @Test void commentAndReviewRequestAreRecordedWithoutTheirTextAndReplayAddsNone() {
        var created = propose("discuss-1");
        long leaderMember = api.memberId("leader@test");

        var review = discussions.create(memberCaller, project, created.id(), new CreateChangeCommentRequest("확인 부탁드립니다 민감한 원문",
                "REVIEW_REQUEST", List.of(Long.toString(leaderMember))), "c-1");
        discussions.create(memberCaller, project, created.id(), new CreateChangeCommentRequest("확인 부탁드립니다 민감한 원문",
                "REVIEW_REQUEST", List.of(Long.toString(leaderMember))), "c-1");   // 멱등 재생
        var comment = discussions.create(memberCaller, project, created.id(), new CreateChangeCommentRequest("참고하세요", "COMMENT",
                List.of()), "c-2");

        assertThat(types(created)).containsExactly("CHANGE_PROPOSAL_SUBMITTED", "CHANGE_PROPOSAL_REVIEW_REQUESTED", "CHANGE_PROPOSAL_COMMENTED");
        assertThat(keys(created)).containsExactly(
                "change-proposal:" + created.id() + ":submitted:1",
                "change-discussion:" + review.id() + ":review-requested",
                "change-discussion:" + comment.id() + ":commented");
        assertThat(activities(created).toString()).doesNotContain("민감한 원문").doesNotContain("참고하세요");
        assertThat(activities(created).get(1).get("actor_id")).isEqualTo(member);
    }

    @Test void aFailureAfterTheActivityRollsBackTheActivityToo() {
        var created = propose("rollback-seed");
        long before = count();
        var request = new RejectRequest("롤백 확인", created.revision(), created.snapshotHash());

        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            proposals.reject(leaderCaller, project, id(created), request, "rej-rollback");
            throw new IllegalStateException("failure injection");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(count()).isEqualTo(before);
        assertThat(types(created)).containsExactly("CHANGE_PROPOSAL_SUBMITTED");
    }

    @Test void theProposalTimelineEndpointReturnsOnlyThatProposalNewestFirst() {
        var first = propose("api-1");
        var other = propose("api-2");
        proposals.startReview(leaderCaller, project, id(first), new WithdrawRequest(first.revision(), first.snapshotHash()), "api-start");

        var response = api.get("/api/projects/" + project + "/activities?change_proposal_id=" + first.id(), leader);

        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
        String body = response.body();
        assertThat(body).contains("CHANGE_PROPOSAL_REVIEW_STARTED", "CHANGE_PROPOSAL_SUBMITTED");
        assertThat(body.indexOf("CHANGE_PROPOSAL_REVIEW_STARTED")).isLessThan(body.indexOf("CHANGE_PROPOSAL_SUBMITTED"));
        assertThat(body).doesNotContain("\"target_id\":\"" + other.id() + "\"");
    }
}
