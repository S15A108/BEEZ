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
import com.beez.backend.changeproposal.dto.ProposalDtos.RejectRequest;
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
 * 변경 제안 알림 연결 (S15P31A108-217·237). 알림은 도메인 변경과 같은 트랜잭션에서 저장되고,
 * 관련 작업 담당자는 직접 후보만 쓰며, 행위자 본인은 빠지고, 재생은 새 알림을 만들지 않는다.
 */
class ChangeNotificationsIntegrationTest extends IntegrationTestSupport {
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
        api.jdbc.sql("DELETE FROM notification WHERE project_id=:p").param("p", project).update();
    }

    long task(String title, String designKey, long account) {
        var result = api.post("/api/projects/" + project + "/tasks", api.planJson(title, api.readsRef(designKey), "[]"), account);
        assertThat(result.statusCode()).withFailMessage(result.body()).isEqualTo(201);
        return TaskApiSupport.longField(result.body(), "id");
    }

    com.beez.backend.changeproposal.dto.ProposalDtos.Proposal propose(String key) {
        return proposals.create(memberCaller, project, new Input(Long.toString(baseline), "API 폐기", "더 이상 쓰지 않음", null, null,
                List.of(new Item("RETIRE", api.designItemId("API-01").toString(), api.currentVersionId("API-01").toString(),
                        null, null, null)), List.of(), null, null, null), key);
    }

    List<Map<String, Object>> notifications(String type) {
        return api.jdbc.sql("SELECT * FROM notification WHERE project_id=:p AND type=:t ORDER BY id")
                .param("p", project).param("t", type).query().listOfRows();
    }

    long count() {
        return api.jdbc.sql("SELECT count(*) FROM notification WHERE project_id=:p").param("p", project).query(Long.class).single();
    }

    @Test void submissionNotifiesOnlyDirectlyRelatedOwnersAndNeverTheProposer() {
        task("proposer's own task", "API-01", member);
        task("leader's task", "API-01", leader);
        task("unrelated", "API-02", leader);

        var created = propose("submit-1");

        assertThat(notifications("CHANGE_PROPOSAL_SUBMITTED")).singleElement().satisfies(row -> {
            assertThat(row.get("recipient_account_id")).isEqualTo(leader);
            assertThat(row.get("dedupe_key")).isEqualTo("change-proposal:" + created.id() + ":submitted:1:to:" + leader);
            assertThat(row.get("ref_kind")).isEqualTo("CHANGE_PROPOSAL");
            assertThat(row.get("ref_id")).isEqualTo(Long.parseLong(created.id()));
            assertThat(row.get("title").toString()).contains("API 폐기");
        });
        // 같은 키의 재요청은 저장 전에 멱등 응답으로 끝나 새 알림이 없다
        assertThat(propose("submit-1")).isEqualTo(created);
        assertThat(notifications("CHANGE_PROPOSAL_SUBMITTED")).hasSize(1);
    }

    @Test void noRelatedTasksMeansNoNotificationAndNoTeamBroadcast() {
        propose("lonely");

        assertThat(count()).isZero();
    }

    @Test void rejectionNotifiesTheProposerOnceWithoutTheReason() {
        var created = propose("reject-1");
        var request = new RejectRequest("민감한 사유 원문", created.revision(), created.snapshotHash());

        proposals.reject(leaderCaller, project, Long.parseLong(created.id()), request, "rej-1");
        proposals.reject(leaderCaller, project, Long.parseLong(created.id()), request, "rej-1");

        assertThat(notifications("CHANGE_PROPOSAL_REJECTED")).singleElement().satisfies(row -> {
            assertThat(row.get("recipient_account_id")).isEqualTo(member);
            assertThat(row.get("dedupe_key")).isEqualTo("change-proposal:" + created.id() + ":rejected:1:to:" + member);
            assertThat(row.get("title").toString() + row.get("body")).doesNotContain("민감한 사유 원문");
        });
    }

    @Test void startingAReviewSendsNothing() {
        var created = propose("review-1");

        proposals.startReview(leaderCaller, project, Long.parseLong(created.id()),
                new com.beez.backend.changeproposal.dto.ProposalDtos.WithdrawRequest(created.revision(), created.snapshotHash()), "start-1");

        assertThat(count()).isZero();
    }

    @Test void publicationNotifiesProposerAndOwnersAndRepeatingTheApprovalAddsNothing() {
        task("proposer's own task", "API-01", member);
        task("leader's task", "API-01", leader);
        var created = propose("publish-1");
        long proposal = Long.parseLong(created.id());
        var approval = new ApproveRequest(Long.toString(baseline), created.revision(), created.snapshotHash(), "직접 연결을 확인했습니다.");

        publications.approve(leaderCaller, project, proposal, approval, "approve-1");

        // 승인은 제안자에게, 발행은 관련 담당자 중 행위자(팀장)를 뺀 사람에게
        assertThat(notifications("CHANGE_PROPOSAL_APPROVED")).singleElement()
                .satisfies(row -> assertThat(row.get("recipient_account_id")).isEqualTo(member));
        assertThat(notifications("CHANGE_PROPOSAL_PUBLISHED")).singleElement().satisfies(row -> {
            assertThat(row.get("recipient_account_id")).isEqualTo(member);
            assertThat(row.get("dedupe_key")).isEqualTo("change-proposal:" + proposal + ":published:to:" + member);
        });
        long afterFirst = count();

        publications.approve(leaderCaller, project, proposal, approval, "approve-1");
        publications.approve(leaderCaller, project, proposal, approval, "approve-2");

        assertThat(count()).isEqualTo(afterFirst);
    }

    @Test void reviewRequestAndMentionTargetTheChosenMembersExceptTheAuthor() {
        var created = propose("discuss-1");
        long leaderMember = api.memberId("leader@test");
        long authorMember = api.memberId("member@test");

        discussions.create(memberCaller, project, created.id(), new CreateChangeCommentRequest("확인 부탁드립니다 민감한 원문", "REVIEW_REQUEST",
                List.of(Long.toString(leaderMember), Long.toString(authorMember))), "c-1");
        discussions.create(memberCaller, project, created.id(), new CreateChangeCommentRequest("참고하세요", "COMMENT",
                List.of(Long.toString(leaderMember))), "c-2");
        discussions.create(memberCaller, project, created.id(), new CreateChangeCommentRequest("지정 없는 의견", null, List.of()), "c-3");

        assertThat(notifications("CHANGE_PROPOSAL_REVIEW_REQUESTED")).singleElement().satisfies(row -> {
            assertThat(row.get("recipient_account_id")).isEqualTo(leader);
            assertThat(row.get("title").toString() + row.get("body")).doesNotContain("민감한 원문");
        });
        assertThat(notifications("CHANGE_PROPOSAL_COMMENTED")).singleElement()
                .satisfies(row -> assertThat(row.get("recipient_account_id")).isEqualTo(leader));
        assertThat(count()).isEqualTo(2);
    }

    @Test void aFailureAfterTheNotificationRollsBackTheNotificationToo() {
        task("leader's task", "API-01", leader);
        var created = propose("rollback-seed");
        api.jdbc.sql("DELETE FROM notification WHERE project_id=:p").param("p", project).update();
        var request = new RejectRequest("롤백 확인", created.revision(), created.snapshotHash());

        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            proposals.reject(leaderCaller, project, Long.parseLong(created.id()), request, "rej-rollback");
            throw new IllegalStateException("failure injection");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(count()).isZero();
    }
}
