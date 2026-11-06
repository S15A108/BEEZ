package com.beez.backend.invitation;

import com.beez.backend.account.Account;
import com.beez.backend.account.AccountRepository;
import com.beez.backend.common.access.AccessErrors;
import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.access.ProjectActor;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.api.PageResponse;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.common.auth.SecretTokens;
import com.beez.backend.common.mail.MailDeliveryException;
import com.beez.backend.common.mail.MailGateway;
import com.beez.backend.common.mail.MailMessage;
import com.beez.backend.config.BeezProperties;
import com.beez.backend.invitation.CreatedInvitationResponse.Result;
import com.beez.backend.invitation.CreatedInvitationResponse.Status;
import com.beez.backend.project.MemberResponse;
import com.beez.backend.project.MembershipEndReason;
import com.beez.backend.project.Project;
import com.beez.backend.project.ProjectActivities;
import com.beez.backend.project.ProjectMember;
import com.beez.backend.project.ProjectMemberRepository;
import com.beez.backend.project.ProjectRepository;
import com.beez.backend.project.ProjectRole;
import com.beez.backend.project.ProjectService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * MEM-A01 ~ A05 (S15P31A108-174), 이메일 초대·자동 참여·다시 보내기 MEM-A11 (S15P31A108-220, Q05).
 * 초대 관리는 사람 세션의 팀장, 미리보기는 PUBLIC, 수락은 USER.
 *
 * <ul>
 *   <li><b>초대는 만료가 없다</b> (Q05 6). 대기 초대는 수락되거나 폐기(MEM-A03)될 때까지 유효하다.</li>
 *   <li><b>무효 초대는 404 하나다</b> (MEM-A04·A05 수정안). 없는 토큰·폐기·삭제된 프로젝트·이미 쓴 링크·다른 이메일이 모두
 *       같은 404 이고, 사유는 서버 로그에만 남긴다. 409 {@code already_member} 만 따로 둔다 — 화면이 그 프로젝트로 보낸다.</li>
 *   <li><b>자동 참여</b> ({@link #joinPendingInvitations}): 로그인·가입 성공 뒤 그 이메일의 대기 초대를 멤버십으로 바꾼다.
 *       팀장이 제외(REMOVED)한 멤버십은 되살리고, 스스로 나간(LEFT) 프로젝트의 초대는 대기로 둔다 — MEM-A05 로만 들어온다.</li>
 *   <li><b>메일 발송 실패</b>: 이메일 초대(MEM-A01)는 초대를 저장한 뒤 메일을 따로(비동기) 보내고, 실패해도 초대를 지우지 않는다.
 *       초대의 효력은 메일이 아니라 "그 이메일로 로그인하면 들어온다"에 있고, 팀장은 다시 보내기(MEM-A11)로 메일만 다시 보낼 수 있다.
 *       다시 보내기는 메일이 유일한 결과라 발송까지 한 트랜잭션에서 하고, 실패하면 503 으로 되돌린다 — 이전 링크가 그대로 산다.</li>
 * </ul>
 */
@Service
public class InvitationService implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(InvitationService.class);

    private final ProjectAccessChecker access;
    private final InvitationRepository invitations;
    private final ProjectRepository projects;
    private final ProjectMemberRepository members;
    private final AccountRepository accounts;
    private final ProjectService projectService;
    private final ProjectActivities activities;
    private final MailGateway mail;
    private final TransactionTemplate tx;
    private final BeezProperties.Invitation config;
    /** 이메일 초대 메일 발송용. SMTP 를 기다리는 동안 요청 스레드·DB 연결을 쥐지 않는다 (임시 비밀번호 메일과 같은 방식) */
    private final ExecutorService mailSender = Executors.newVirtualThreadPerTaskExecutor();

    public InvitationService(ProjectAccessChecker access, InvitationRepository invitations, ProjectRepository projects,
                             ProjectMemberRepository members, AccountRepository accounts, ProjectService projectService,
                             ProjectActivities activities, MailGateway mail, PlatformTransactionManager manager,
                             BeezProperties properties) {
        this.access = access;
        this.invitations = invitations;
        this.projects = projects;
        this.members = members;
        this.accounts = accounts;
        this.projectService = projectService;
        this.activities = activities;
        this.mail = mail;
        this.tx = new TransactionTemplate(manager);
        this.config = properties.invitation();
    }

    /**
     * MEM-A01. emails 가 있으면 이메일 초대, 없으면 링크 초대(기존 호환).
     * 트랜잭션은 안에서 연다 — 이메일 초대 메일은 커밋 뒤에 보내야 해서다.
     */
    public CreatedInvitationResponse create(CallerContext caller, long projectId, CreateInvitationRequest req) {
        if (req != null && req.emails() != null) {
            if (req.email() != null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", "email 과 emails 는 함께 쓸 수 없습니다");
            }
            return inviteByEmails(caller, projectId, req.emails(), req.message());
        }
        if (req != null && req.message() != null) {
            // 링크 초대는 메일을 보내지 않아 message 를 넣을 곳이 없다. 조용히 버리면 화면 실수가 묻힌다
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", "message 는 emails 와 함께만 쓸 수 있습니다");
        }
        return tx.execute(status -> inviteByLink(caller, projectId, req == null ? null : req.email()));
    }

    /** 링크 초대. 이메일을 지정했고 그 계정이 이미 활성 멤버면 409. 같은 이메일의 대기 초대가 있어도 새로 만든다 (기존 계약). */
    private CreatedInvitationResponse inviteByLink(CallerContext caller, long projectId, String rawEmail) {
        ProjectActor actor = access.requireHumanLeader(caller, projectId);
        String email = rawEmail == null ? null : Account.normalizeEmail(rawEmail);
        if (email != null && isActiveMemberByEmail(projectId, email)) {
            throw new ApiException(HttpStatus.CONFLICT, "already_member", "이미 활성 멤버");
        }
        String token = SecretTokens.generate();
        Invitation invitation = invitations.save(
                Invitation.create(projectId, email, SecretTokens.hash(token), actor.accountId()));
        return CreatedInvitationResponse.link(InvitationResponse.of(invitation), linkUrl(token));
    }

    /**
     * 이메일 초대 (-220). 프로젝트 행을 잠가 같은 프로젝트의 초대 요청을 한 줄로 세운다 — 같은 이메일을 동시에 초대해도
     * 대기 초대가 둘 생기지 않는다(뒤 요청은 ALREADY_INVITED). 메일은 커밋 뒤에 보낸다.
     */
    private CreatedInvitationResponse inviteByEmails(CallerContext caller, long projectId, List<String> rawEmails,
                                                     String message) {
        // 대소문자·공백만 다른 주소는 하나로 본다. 순서는 요청 순서를 따른다
        LinkedHashSet<String> emails = new LinkedHashSet<>();
        for (String raw : rawEmails) {
            emails.add(Account.normalizeEmail(raw));
        }
        record Outgoing(String email, String token) {
        }
        record Saved(List<Result> results, List<Outgoing> outgoing, String projectName, String inviterName) {
        }
        Saved saved = tx.execute(status -> {
            ProjectActor actor = access.requireHumanLeader(caller, projectId);
            Project project = projects.findForUpdate(projectId)
                    .filter(p -> p.getArchivedAt() == null)
                    .orElseThrow(() -> AccessErrors.notFound("project_archived project=" + projectId));
            access.requireLeaderAfterLock(actor);   // 잠금을 기다리는 사이 팀장을 넘겼으면 거절 (팀원 관리와 같은 규칙)
            List<Result> results = new ArrayList<>();
            List<Outgoing> outgoing = new ArrayList<>();
            for (String email : emails) {
                if (isActiveMemberByEmail(projectId, email)) {
                    results.add(Result.of(email, Status.ALREADY_MEMBER, null));
                    continue;
                }
                Optional<Invitation> pending = invitations
                        .findFirstByProjectIdAndEmailAndAcceptedAtIsNullAndRevokedAtIsNullOrderByIdAsc(projectId, email);
                if (pending.isPresent()) {
                    results.add(Result.of(email, Status.ALREADY_INVITED, pending.get().getId()));
                    continue;
                }
                String token = SecretTokens.generate();
                Invitation invitation = invitations.save(
                        Invitation.create(projectId, email, SecretTokens.hash(token), actor.accountId()));
                results.add(Result.of(email, Status.INVITED, invitation.getId()));
                outgoing.add(new Outgoing(email, token));
            }
            String inviterName = accounts.findById(actor.accountId()).map(Account::getDisplayName).orElse(null);
            return new Saved(results, outgoing, project.getName(), inviterName);
        });
        for (Outgoing o : saved.outgoing()) {
            MailMessage m = inviteMail(o.email(), projectId, o.token(), saved.projectName(), saved.inviterName(), message);
            mailSender.execute(() -> deliverAsync(projectId, m));
        }
        return CreatedInvitationResponse.emails(saved.results());
    }

    /** MEM-A02. 대기·수락·폐기 모두, id 내림차순 커서. */
    @Transactional(readOnly = true)
    public PageResponse<InvitationResponse> list(CallerContext caller, long projectId, String cursor, Integer limit) {
        access.requireHumanLeader(caller, projectId);
        int size = PageResponse.normalizeLimit(limit);
        List<Invitation> rows = invitations.findPage(projectId, PageResponse.beforeId(cursor),
                PageRequest.of(0, size + 1));
        return PageResponse.fromRows(rows, size, Invitation::getId, page -> {
            List<InvitationResponse> items = new ArrayList<>();
            for (Invitation i : page) {
                items.add(InvitationResponse.of(i));
            }
            return items;
        });
    }

    /**
     * MEM-A03. 이미 폐기된 초대는 그대로 204 (명세). 이미 수락된 초대도 되돌릴 것이 없어 바꾸지 않고 204 —
     * 들어온 팀원을 내보내는 건 팀원 제외(MEM-A09)다. 폐기한 초대는 자동 참여 대상에서 빠진다 (대기 조건이 revoked_at IS NULL).
     */
    @Transactional
    public void revoke(CallerContext caller, long projectId, long invitationId) {
        access.requireHumanLeader(caller, projectId);
        Invitation invitation = invitations.findForUpdate(invitationId, projectId)
                .orElseThrow(() -> AccessErrors.notFound("invitation_missing project=" + projectId
                        + " invitation=" + invitationId));
        if (invitation.isPending()) {
            invitation.revoke(Instant.now());
        }
    }

    /**
     * MEM-A11. 새 토큰을 만들어 메일을 다시 보낸다. 토큰 해시를 바꾸므로 이전 링크는 바로 무효다.
     * 초대 행을 잠근 채 메일까지 보낸다 — 발송이 실패하면 503 으로 트랜잭션이 되돌려져 이전 링크가 그대로 산다.
     * (발송 중 그 초대의 수락·폐기가 SMTP 시간 제한만큼 기다릴 수 있다. 행 하나라 받아들인다.)
     */
    @Transactional
    public InvitationResponse resend(CallerContext caller, long projectId, long invitationId) {
        ProjectActor actor = access.requireHumanLeader(caller, projectId);
        Invitation invitation = invitations.findForUpdate(invitationId, projectId)
                .orElseThrow(() -> AccessErrors.notFound("invitation_missing project=" + projectId
                        + " invitation=" + invitationId));
        if (invitation.getEmail() == null) {
            // 링크 초대는 보낼 주소가 없다. 명세에 따로 정한 코드가 없어 다시 보낼 수 있는 초대가 없는 것으로 본다
            throw AccessErrors.notFound("invitation_link_only invitation=" + invitationId);
        }
        if (!invitation.isPending()) {
            throw new ApiException(HttpStatus.CONFLICT, "invitation_not_pending", "이미 수락·폐기된 초대");
        }
        String token = SecretTokens.generate();
        invitation.rotateToken(SecretTokens.hash(token));
        invitations.flush();   // 토큰 해시 UNIQUE 위반은 메일을 보내기 전에 드러나게 한다
        Project project = projects.findById(projectId).orElseThrow();
        String inviterName = accounts.findById(actor.accountId()).map(Account::getDisplayName).orElse(null);
        try {
            mail.send(inviteMail(invitation.getEmail(), projectId, token, project.getName(), inviterName, null));
        } catch (MailDeliveryException e) {
            log.warn("초대 메일 다시 보내기 실패: invitation_id={} exception_type={}", invitationId,
                    e.getClass().getSimpleName());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "mail_send_failed",
                    "초대 메일을 보내지 못했습니다. 잠시 뒤에 다시 시도하세요");
        }
        log.info("초대 메일 다시 보냄: invitation_id={}", invitationId);
        return InvitationResponse.of(invitation);
    }

    /**
     * MEM-A04. 초대를 소비하지 않는다. callerAccountId 는 로그인 세션의 계정 — 없으면 로그인이 필요하다.
     * 이메일 초대는 이미 수락됐어도 그 사람이 지금 팀원이면 정상 응답이다: 로그아웃 상태로 링크를 연 사람이 로그인하는 순간 자동 참여로 수락되고,
     * 그 링크로 돌아왔을 때 404 가 아니라 "이미 팀원 → 프로젝트 열기"가 되어야 한다 (Q05). 링크 초대는 한 번 쓰면 무효다.
     */
    @Transactional(readOnly = true)
    public InvitationPreviewResponse preview(String token, Long callerAccountId) {
        Invitation invitation = findByToken(token);
        Project project = activeProject(invitation);
        rejectRevoked(invitation);
        // 수락된 초대는 그 이메일 계정이 지금도 활성 멤버일 때만 정상 응답 — 수락(MEM-A05)이 409 already_member 를 줄 때와 같은 기준이다.
        // 제외·나간 뒤 예전 링크를 열면 미리보기는 통과하는데 수락이 404 가 되는 어긋남을 막는다. 링크 초대는 한 번 쓰면 무효다
        if (invitation.isAccepted()
                && (invitation.getEmail() == null || !isActiveMemberByEmail(project.getId(), invitation.getEmail()))) {
            throw AccessErrors.notFound("invitation_used invitation=" + invitation.getId());
        }
        Boolean accountMatches = null;
        if (callerAccountId != null) {
            accountMatches = invitation.getEmail() == null || accounts.findById(callerAccountId)
                    .map(a -> invitation.getEmail().equals(a.getEmail()))
                    .orElse(false);
        }
        // 초대한 사람이 탈퇴했으면(팀장을 넘기고 탈퇴한 경우 등) 이메일이 지워져 있어 inviter 를 통째로 뺀다 — 빈 이메일을 보내지 않는다
        InvitationPreviewResponse.Inviter inviter = accounts.findById(invitation.getInvitedBy())
                .filter(a -> a.getDeletedAt() == null)
                .map(a -> new InvitationPreviewResponse.Inviter(a.getDisplayName(), a.getEmail()))
                .orElse(null);
        return new InvitationPreviewResponse(project.getName(), project.getDescription(), callerAccountId == null,
                inviter, invitation.getEmail(), accountMatches);
    }

    /**
     * MEM-A05. 초대 소비와 멤버십 생성을 한 트랜잭션에서 한다.
     * 초대 행 → 프로젝트 행 순으로 잠근다: 같은 초대를 두 번 수락하거나 폐기와 겹쳐도 한쪽만 성공하고,
     * 프로젝트 삭제(PRJ-A05)와 겹쳐도 삭제된 프로젝트에 들어가지 않는다. 같은 계정이 같은 프로젝트의 초대 둘을
     * 동시에 수락해도 프로젝트 잠금에서 줄을 서 멤버십이 하나만 생긴다.
     * 두 행을 다 잠그는 건 수락과 자동 참여뿐이고 둘 다 초대 → 프로젝트 순이라(삭제는 프로젝트만, 폐기는 초대만) 잠금 순서가 엇갈리지 않는다.
     * <p>
     * 스스로 나간(LEFT) 사람은 이것으로만 다시 들어온다 — 자동 참여는 그 초대를 건너뛴다.
     */
    @Transactional
    public AcceptedInvitationResponse accept(CallerContext caller, String token) {
        Account me = access.requireUser(caller);
        Invitation invitation = invitations.findByTokenHashForUpdate(SecretTokens.hash(token))
                .orElseThrow(() -> AccessErrors.notFound("invitation_token_unknown"));
        Project project = projects.findForUpdate(invitation.getProjectId())
                .filter(p -> p.getArchivedAt() == null)
                .orElseThrow(() -> AccessErrors.notFound("project_archived invitation=" + invitation.getId()));
        rejectRevoked(invitation);
        // 이메일 지정 초대는 그 이메일 계정만 쓸 수 있다 (Q05 제안). 남의 초대 존재를 알리지 않게 무효와 같은 404
        if (invitation.getEmail() != null && !invitation.getEmail().equals(me.getEmail())) {
            throw AccessErrors.notFound("invitation_email_mismatch invitation=" + invitation.getId()
                    + " account=" + me.getId());
        }
        Optional<ProjectMember> existing = members.findByProjectIdAndAccountId(project.getId(), me.getId());
        boolean activeMember = existing.isPresent() && existing.get().isActive();
        // 이미 팀원이면 초대를 소비하지 않고 409 — 로그인 때 자동 참여로 이미 들어온 사람이 링크로 돌아온 경우도 여기다
        if (activeMember) {
            throw new ApiException(HttpStatus.CONFLICT, "already_member", "이미 참여");
        }
        if (invitation.isAccepted()) {
            throw AccessErrors.notFound("invitation_used invitation=" + invitation.getId());
        }

        ProjectMember member = joinAsMember(project.getId(), me.getId(), existing);
        invitation.accept(me.getId(), Instant.now());
        activities.memberJoined(caller, member, invitation.getId());

        // 새 멤버십엔 토큰이 있을 수 없다 (agent_token 이 멤버십 행을 참조). 되살린 멤버십만 예전 토큰을 본다
        boolean agentConnected = existing.isPresent()
                && !members.agentConnectedAccountIds(project.getId(), List.of(me.getId())).isEmpty();
        return new AcceptedInvitationResponse(projectService.toResponse(project, ProjectRole.MEMBER),
                MemberResponse.of(member, me, agentConnected));
    }

    /**
     * 자동 참여 (ACC-A01 가입·ACC-A02 로그인 성공 뒤, Q05 1·4). 그 계정 이메일의 대기 초대를 멤버십으로 바꾸고 accepted_at 을 채운다.
     * 판정은 {@link #autoJoinAction}. 삭제된 프로젝트의 초대는 건드리지 않는다.
     * <p>
     * 로그인·가입이 커밋된 뒤 따로 부르고, 실패해도 예외를 밖으로 내지 않는다 — 초대 전환이 실패했다고 로그인까지 실패하면 안 된다.
     * 대기 초대는 그대로 남으므로 다음 로그인에서 다시 시도된다.
     *
     * @return 이번에 바꾼 초대 수 (실패하면 0)
     */
    public int joinPendingInvitations(Account account) {
        if (account.getEmail() == null) {
            return 0;
        }
        try {
            Integer joined = tx.execute(status -> joinPending(account.getId(), account.getEmail()));
            return joined == null ? 0 : joined;
        } catch (RuntimeException e) {
            log.warn("초대 자동 참여 실패(로그인은 그대로 성공): account_id={} exception_type={}", account.getId(),
                    e.getClass().getSimpleName(), e);
            return 0;
        }
    }

    private int joinPending(long accountId, String email) {
        List<Invitation> pending = invitations.findPendingByEmailForUpdate(email);
        int converted = 0;
        Instant now = Instant.now();
        for (Invitation invitation : pending) {
            Optional<Project> project = projects.findForUpdate(invitation.getProjectId())
                    .filter(p -> p.getArchivedAt() == null);
            if (project.isEmpty()) {
                continue;
            }
            Optional<ProjectMember> existing = members.findByProjectIdAndAccountId(invitation.getProjectId(), accountId);
            AutoJoin action = autoJoinAction(existing);
            switch (action) {
                case SKIP -> {
                    continue;
                }
                // 행위자는 로그인·가입한 본인이다(-289). ALREADY_ACTIVE 는 들어온 게 아니라 초대만 닫으므로 남기지 않는다
                case JOIN, REVIVE -> activities.memberJoined(CallerContext.human(accountId),
                        joinAsMember(invitation.getProjectId(), accountId, existing), invitation.getId());
                case ALREADY_ACTIVE -> {
                    // 이미 팀원이다(링크 초대 등으로 먼저 들어옴). 대기로 남겨 두면 설정 화면에 '아직 로그인 전'으로 보여 수락으로 닫는다
                }
            }
            invitation.accept(accountId, now);
            converted++;
            log.info("초대 자동 참여: invitation_id={} project_id={} account_id={} action={}", invitation.getId(),
                    invitation.getProjectId(), accountId, action);
        }
        return converted;
    }

    /**
     * 팀원 제외(MEM-A09)·나가기(MEM-A10)와 같은 트랜잭션에서, <b>프로젝트 행을 잠그기 전에</b> 부른다.
     * 그 사람 이메일로 이 프로젝트에 남은 대기 초대를 취소한다. 남겨 두면 멤버십이 끝나기 전에 받은 초대로 돌아온다 —
     * 제외된 사람은 다음 로그인(자동 참여 REVIVE)이나 예전 메일 링크로, 나간 사람은 예전 메일 링크(MEM-A05)로.
     * Q05 4 는 "다시 초대받았을 때"만 돌아오게 한다. 끝난 뒤 팀장이 새로 초대하면 그 초대로는 돌아온다.
     * 제외·나가기가 거절되면(403·409 등) 같은 트랜잭션이라 취소도 되돌려진다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void revokePendingOnMembershipEnd(long projectId, String email) {
        if (email == null) {
            return;
        }
        Instant now = Instant.now();
        for (Invitation invitation : invitations.findPendingByProjectAndEmailForUpdate(projectId, email)) {
            invitation.revoke(now);
            log.info("멤버십 종료로 대기 초대 취소: invitation_id={} project_id={}", invitation.getId(), projectId);
        }
    }

    /**
     * 회원 탈퇴(ACC-A10, -277)와 같은 트랜잭션에서, <b>프로젝트 행을 잠그기 전에</b> 부른다.
     * 그 이메일로 온 대기 초대는 취소하고, 모든 초대 행에서 주소를 지운다.
     * 취소하지 않고 주소만 지우면 대기 초대가 주소 없는 링크 초대가 되어 메일 링크를 가진 누구나 수락할 수 있다.
     * 취소해 두므로 같은 이메일로 새로 가입한 계정이 예전 초대로 자동 참여하지 않는다 — 팀장이 새로 초대하면 그 초대로 들어온다.
     *
     * @return 주소를 지운 초대 수
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int forgetOnAccountWithdrawal(String email) {
        if (email == null) {
            return 0;
        }
        Instant now = Instant.now();
        List<Invitation> addressed = invitations.findAllByEmailForUpdate(email);
        for (Invitation invitation : addressed) {
            if (invitation.isPending()) {
                invitation.revoke(now);
                log.info("회원 탈퇴로 대기 초대 취소: invitation_id={} project_id={}", invitation.getId(),
                        invitation.getProjectId());
            }
            invitation.forgetEmail();
        }
        return addressed.size();
    }

    /** 자동 참여에서 멤버십 상태별로 할 일. */
    enum AutoJoin {
        /** 멤버십이 없다 → 새 MEMBER */
        JOIN,
        /** 팀장이 제외(REMOVED)했다 → 그 행을 MEMBER 로 되살린다 (Q05 4) */
        REVIVE,
        /** 이미 활성 멤버 → 멤버십은 그대로, 초대만 수락으로 닫는다 */
        ALREADY_ACTIVE,
        /** 스스로 나갔다(LEFT) → 초대를 대기로 둔다. 메일 링크(MEM-A05)로만 들어온다 (Q05 4) */
        SKIP
    }

    /**
     * 끝난 이유가 없는 비활성 멤버십(V11 전에 끝난 행)은 나간 것으로 본다 — 제외인지 나감인지 모르면
     * 본인이 링크를 직접 여는 쪽(MEM-A05)이 안전하다. 로그인만으로 원치 않는 프로젝트에 다시 들어가지 않는다.
     */
    static AutoJoin autoJoinAction(Optional<ProjectMember> existing) {
        if (existing.isEmpty()) {
            return AutoJoin.JOIN;
        }
        ProjectMember m = existing.get();
        if (m.isActive()) {
            return AutoJoin.ALREADY_ACTIVE;
        }
        return m.getLeftReason() == MembershipEndReason.REMOVED ? AutoJoin.REVIVE : AutoJoin.SKIP;
    }

    /** 새 MEMBER 멤버십을 만들거나 끝난 멤버십을 되살린다 (MEM-A05·자동 참여 공통). updated_at 은 flush 때 채워진다. */
    private ProjectMember joinAsMember(long projectId, long accountId, Optional<ProjectMember> existing) {
        ProjectMember member;
        if (existing.isPresent()) {
            member = existing.get();
            member.rejoin();
        } else {
            member = ProjectMember.member(projectId, accountId);
        }
        return members.saveAndFlush(member);
    }

    private boolean isActiveMemberByEmail(long projectId, String email) {
        Optional<Account> invitee = accounts.findByEmailAndDeletedAtIsNull(email);
        return invitee.isPresent() && members.findActiveMembership(projectId, invitee.get().getId()).isPresent();
    }

    private Invitation findByToken(String token) {
        return invitations.findByTokenHash(SecretTokens.hash(token))
                .orElseThrow(() -> AccessErrors.notFound("invitation_token_unknown"));
    }

    /** 삭제된 프로젝트의 초대는 무효다 (-173 에서 넘어온 약속). */
    private Project activeProject(Invitation invitation) {
        return projects.findById(invitation.getProjectId())
                .filter(p -> p.getArchivedAt() == null)
                .orElseThrow(() -> AccessErrors.notFound("project_archived invitation=" + invitation.getId()));
    }

    private static void rejectRevoked(Invitation invitation) {
        if (invitation.isRevoked()) {
            throw AccessErrors.notFound("invitation_revoked invitation=" + invitation.getId());
        }
    }

    private void deliverAsync(long projectId, MailMessage message) {
        try {
            mail.send(message);
            log.info("초대 메일 발송: project_id={}", projectId);
        } catch (RuntimeException e) {
            // 초대는 남긴다 — 그 이메일로 로그인하면 들어오고, 팀장은 MEM-A11 로 다시 보낼 수 있다
            log.warn("초대 메일 발송 실패(초대는 유지): project_id={} exception_type={}", projectId,
                    e.getClass().getSimpleName());
        }
    }

    private MailMessage inviteMail(String to, long projectId, String token, String projectName, String inviterName,
                                   String message) {
        String inviter = inviterName == null || inviterName.isBlank() ? "팀장" : inviterName;
        StringBuilder text = new StringBuilder();
        text.append(inviter).append(" 님이 BEEZ 프로젝트 '").append(projectName).append("'에 초대했습니다.\n\n");
        if (message != null) {
            text.append(message).append("\n\n");
        }
        text.append("프로젝트로 가기: ").append(projectUrl(projectId, token)).append("\n\n")
                .append("이 이메일(").append(to).append(")로 로그인하거나 가입하면 바로 프로젝트에 들어갑니다.\n")
                .append("초대받을 일이 없다면 이 메일을 무시하세요.\n");
        return new MailMessage(to, "[BEEZ] '" + projectName + "' 프로젝트 초대", text.toString());
    }

    /** 링크 초대 주소 (기존 호환). */
    private String linkUrl(String token) {
        return baseUrl() + "/invite?token=" + token;
    }

    /** 초대 메일의 '프로젝트로 가기' 주소 (MEM-A01 수정안). */
    private String projectUrl(long projectId, String token) {
        return baseUrl() + "/projects/" + projectId + "?invite=" + token;
    }

    private String baseUrl() {
        String base = config.baseUrl();
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    @Override
    public void destroy() {
        mailSender.shutdown();
    }
}
