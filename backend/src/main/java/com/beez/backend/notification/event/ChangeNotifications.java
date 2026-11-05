package com.beez.backend.notification.event;

import com.beez.backend.changeproposal.dto.PublicationDtos.ImpactResult;
import com.beez.backend.changeproposal.dto.PublicationDtos.WaitingTask;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.impactanalysis.OwnerRecommendationDtos.DirectResult;
import com.beez.backend.impactanalysis.OwnerRecommendationService;
import com.beez.backend.notification.NotificationRequest;
import com.beez.backend.notification.NotificationRequester;
import com.beez.backend.notification.NotificationTargetType;
import com.beez.backend.notification.event.NotificationMessages.Message;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 변경 제안·의견·기준 알림 요청 (S15P31A108-217·237, 계약 10-notification-activity-contract.md).
 *
 * <p>모든 메서드는 도메인 쓰기 트랜잭션 <b>안에서</b> 부른다(MANDATORY). 알림 저장은 도메인 변경과 함께 커밋·롤백된다.
 * 수신자 선정 규칙:
 * <ul>
 *   <li>"관련 작업 담당자"는 {@link OwnerRecommendationService#forNotification} 의 직접 후보(COMPLETE)만 쓴다.
 *       AI 후보나 화면용 추천 목록은 쓰지 않고, 직접 후보가 미확인(INCOMPLETE·STALE·NOT_APPLICABLE)이면
 *       전체 팀으로 넓히지 않고 아무에게도 보내지 않는다.</li>
 *   <li>그 ID 는 프로젝트 멤버 ID 라서 계정 ID 로 바꿔 넘긴다.</li>
 *   <li>행위 자체를 알리는 알림은 행위자 본인을 뺀다. {@code REVALIDATION_REQUIRED} 는 후속 조치가 필요해 본인도 받는다.</li>
 * </ul>
 * 활동 이벤트(ActivityRecorder) 호출은 S15P31A108-237 에서 같은 지점에 추가한다.
 */
@Service
public class ChangeNotifications {

    private static final Logger log = LoggerFactory.getLogger(ChangeNotifications.class);

    private final OwnerRecommendationService owners;
    private final NotificationRequester requester;
    private final JdbcClient jdbc;

    public ChangeNotifications(OwnerRecommendationService owners, NotificationRequester requester, JdbcClient jdbc) {
        this.owners = owners;
        this.requester = requester;
        this.jdbc = jdbc;
    }

    /** 제안 올림·보완 제출 → 관련 작업 담당자. 키에 리비전이 들어가 보완 제출은 새 알림이 된다 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void submitted(CallerContext caller, long project, long proposal, int revision, String snapshotHash) {
        Message message = NotificationMessages.proposalSubmitted(title(project, proposal), revision);
        requester.requestAll(proposalRequests(project, proposal, "change-proposal:" + proposal + ":submitted:" + revision,
                "CHANGE_PROPOSAL_SUBMITTED", message,
                withoutActor(caller, relatedOwners(caller, project, proposal, revision, snapshotHash))));
    }

    /** 반려 → 제안자 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void rejected(CallerContext caller, long project, long proposal, long proposerAccount, int revision) {
        Message message = NotificationMessages.proposalRejected(title(project, proposal));
        requester.requestAll(proposalRequests(project, proposal, "change-proposal:" + proposal + ":rejected:" + revision,
                "CHANGE_PROPOSAL_REJECTED", message, withoutActor(caller, List.of(proposerAccount))));
    }

    /**
     * 승인·발행. 승인은 제안자에게, 발행은 관련 작업 담당자에게, 재검증 대기가 된 작업은 그 담당자에게(본인 포함).
     * 발행 상태와 새 기준이 조회에 보이는 시점에 부른다 — 이미 발행된 제안의 재생·재요청 분기에서는 부르지 않는다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void published(CallerContext caller, long project, long proposal, long proposerAccount, int revision,
                          String snapshotHash, ImpactResult impact) {
        String title = title(project, proposal);
        requester.requestAll(proposalRequests(project, proposal, "change-proposal:" + proposal + ":approved",
                "CHANGE_PROPOSAL_APPROVED", NotificationMessages.proposalApproved(title),
                withoutActor(caller, List.of(proposerAccount))));
        requester.requestAll(proposalRequests(project, proposal, "change-proposal:" + proposal + ":published",
                "CHANGE_PROPOSAL_PUBLISHED", NotificationMessages.proposalPublished(title),
                withoutActor(caller, relatedOwners(caller, project, proposal, revision, snapshotHash))));
        revalidationRequired(project, proposal, impact);
    }

    /** 의견에서 멤버를 지정했을 때. 검토 요청이면 REVIEW_REQUESTED, 그냥 의견이면 COMMENTED. 의견 원문은 넣지 않는다 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void discussed(CallerContext caller, long project, long proposal, long commentId, boolean reviewRequest,
                          Collection<Long> mentionedAccounts) {
        String title = title(project, proposal);
        String type = reviewRequest ? "CHANGE_PROPOSAL_REVIEW_REQUESTED" : "CHANGE_PROPOSAL_COMMENTED";
        Message message = reviewRequest ? NotificationMessages.proposalReviewRequested(title)
                : NotificationMessages.proposalCommented(title);
        String action = reviewRequest ? "review-requested" : "mentioned";
        requester.requestAll(proposalRequests(project, proposal, "change-discussion:" + commentId + ":" + action, type,
                message, withoutActor(caller, mentionedAccounts)));
    }

    /** 최초 기준 v1 → 활성 팀원(행위자 제외) */
    @Transactional(propagation = Propagation.MANDATORY)
    public void baselineCreated(CallerContext caller, long project, long baseline) {
        Message message = NotificationMessages.baselineCreated();
        Set<Long> members = new LinkedHashSet<>(jdbc.sql("""
                select m.account_id from project_member m
                  join account a on a.id = m.account_id
                 where m.project_id = :project and m.active and a.deleted_at is null
                 order by m.id
                """).param("project", project).query(Long.class).list());
        List<NotificationRequest> requests = new ArrayList<>();
        for (long account : withoutActor(caller, members)) {
            requests.add(new NotificationRequest("baseline:" + baseline + ":created:to:" + account, project, account,
                    "BASELINE_CREATED", message.title(), message.body(), NotificationTargetType.BASELINE, baseline, null));
        }
        requester.requestAll(requests);
    }

    /** 발행으로 WAITING(재검증)이 된 작업의 담당자에게 작업마다 한 건. 담당자가 없는 작업은 건너뛴다 */
    private void revalidationRequired(long project, long proposal, ImpactResult impact) {
        if (impact == null || impact.waitingTasks().isEmpty()) {
            return;
        }
        Map<Long, Long> accounts = accountsByMember(project, impact.waitingTasks().stream()
                .map(WaitingTask::assigneeMemberId).filter(id -> id != null).map(Long::parseLong).toList());
        List<NotificationRequest> requests = new ArrayList<>();
        for (WaitingTask task : impact.waitingTasks()) {
            Long account = task.assigneeMemberId() == null ? null : accounts.get(Long.parseLong(task.assigneeMemberId()));
            if (account == null) {
                continue;
            }
            Message message = NotificationMessages.revalidationRequired(task.title());
            long taskId = Long.parseLong(task.taskId());
            requests.add(new NotificationRequest("task:" + taskId + ":revalidation:" + proposal + ":to:" + account, project,
                    account, "REVALIDATION_REQUIRED", message.title(), message.body(), NotificationTargetType.TASK, taskId, null));
        }
        requester.requestAll(requests);
    }

    private Collection<Long> relatedOwners(CallerContext caller, long project, long proposal, int revision, String hash) {
        DirectResult direct = owners.forNotification(caller, project, proposal, revision, hash);
        if (!"COMPLETE".equals(direct.directReferenceStatus())) {
            // 관련자 0명이 아니라 미확인이다. 전체 팀·AI 후보로 바꾸지 않고 사유만 남긴다
            log.info("related task owners unknown, no notification: project={} proposal={} status={} reasons={}", project,
                    proposal, direct.directReferenceStatus(), direct.directReferenceReasons());
            return List.of();
        }
        List<Long> memberIds = direct.notificationCandidateMemberIds().stream().map(Long::parseLong).toList();
        return accountsByMember(project, memberIds).values();
    }

    /** 멤버 ID → 계정 ID. 이 프로젝트의 활성 멤버만. 입력 순서를 유지한다 */
    private Map<Long, Long> accountsByMember(long project, Collection<Long> memberIds) {
        Map<Long, Long> found = new LinkedHashMap<>();
        if (memberIds.isEmpty()) {
            return found;
        }
        jdbc.sql("""
                select m.id, m.account_id from project_member m
                  join project p on p.id = m.project_id
                  join account a on a.id = m.account_id
                 where m.project_id = :project and m.id in (:ids)
                   and m.active and p.archived_at is null and a.deleted_at is null
                 order by m.id
                """).param("project", project).param("ids", memberIds)
                .query((rs, n) -> found.put(rs.getLong("id"), rs.getLong("account_id"))).list();
        return found;
    }

    private static List<Long> withoutActor(CallerContext caller, Collection<Long> accounts) {
        return new LinkedHashSet<>(accounts).stream().filter(account -> !account.equals(caller.accountId())).toList();
    }

    private List<NotificationRequest> proposalRequests(long project, long proposal, String keyBase, String type,
                                                       Message message, Collection<Long> recipients) {
        List<NotificationRequest> requests = new ArrayList<>();
        for (long account : recipients) {
            requests.add(new NotificationRequest(keyBase + ":to:" + account, project, account, type, message.title(),
                    message.body(), NotificationTargetType.CHANGE_PROPOSAL, proposal, null));
        }
        return requests;
    }

    private String title(long project, long proposal) {
        return jdbc.sql("select title from change_proposal where id = :id and project_id = :project")
                .param("id", proposal).param("project", project).query(String.class).optional().orElse("");
    }
}
