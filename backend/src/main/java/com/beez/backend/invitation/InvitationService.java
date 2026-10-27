package com.beez.backend.invitation;

import com.beez.backend.account.Account;
import com.beez.backend.account.AccountRepository;
import com.beez.backend.auth.AuthService;
import com.beez.backend.common.access.AccessErrors;
import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.access.ProjectActor;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.api.Ids;
import com.beez.backend.common.api.PageResponse;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.common.auth.SecretTokens;
import com.beez.backend.config.BeezProperties;
import com.beez.backend.project.MemberResponse;
import com.beez.backend.project.Project;
import com.beez.backend.project.ProjectMember;
import com.beez.backend.project.ProjectMemberRepository;
import com.beez.backend.project.ProjectRepository;
import com.beez.backend.project.ProjectRole;
import com.beez.backend.project.ProjectService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * MEM-A01 ~ A05 (S15P31A108-174). 초대 관리는 사람 세션의 팀장, 미리보기는 PUBLIC, 수락은 USER.
 * <p>
 * 초대 판정 순서(미리보기·수락 공통): 없는 토큰·삭제된 프로젝트·폐기 → 404 → 수락됨 → 만료 410.
 * 수락됨은 미리보기에서는 무효(404), 수락에서는 409 {@code invitation_used} 다 (명세 오류표).
 * 메일은 보내지 않는다 — 응답의 invite_url 을 팀장이 전달한다.
 */
@Service
public class InvitationService {

    private final ProjectAccessChecker access;
    private final InvitationRepository invitations;
    private final ProjectRepository projects;
    private final ProjectMemberRepository members;
    private final AccountRepository accounts;
    private final ProjectService projectService;
    private final BeezProperties.Invitation config;

    public InvitationService(ProjectAccessChecker access, InvitationRepository invitations, ProjectRepository projects,
                             ProjectMemberRepository members, AccountRepository accounts, ProjectService projectService,
                             BeezProperties properties) {
        this.access = access;
        this.invitations = invitations;
        this.projects = projects;
        this.members = members;
        this.accounts = accounts;
        this.projectService = projectService;
        this.config = properties.invitation();
    }

    /** MEM-A01. 이메일을 지정했고 그 계정이 이미 활성 멤버면 409. 같은 이메일의 대기 초대가 있어도 새로 만든다. */
    @Transactional
    public CreatedInvitationResponse create(CallerContext caller, long projectId, CreateInvitationRequest req) {
        ProjectActor actor = access.requireHumanLeader(caller, projectId);
        String email = req.email() == null ? null : AuthService.normalizeEmail(req.email());
        if (email != null) {
            Optional<Account> invitee = accounts.findByEmailAndDeletedAtIsNull(email);
            if (invitee.isPresent() && members.findActiveMembership(projectId, invitee.get().getId()).isPresent()) {
                throw new ApiException(HttpStatus.CONFLICT, "already_member", "이미 활성 멤버");
            }
        }
        String token = SecretTokens.generate();
        Invitation invitation = invitations.save(Invitation.create(projectId, email, SecretTokens.hash(token),
                actor.accountId(), Instant.now().plus(config.ttl())));
        return new CreatedInvitationResponse(InvitationResponse.of(invitation), inviteUrl(token));
    }

    /** MEM-A02. 대기·수락·폐기·만료 모두, id 내림차순 커서. */
    @Transactional(readOnly = true)
    public PageResponse<InvitationResponse> list(CallerContext caller, long projectId, String cursor, Integer limit) {
        access.requireHumanLeader(caller, projectId);
        int size = PageResponse.normalizeLimit(limit);
        long beforeId = Long.MAX_VALUE;
        if (cursor != null && !cursor.isBlank()) {
            beforeId = Ids.parse(cursor, "cursor");
        }
        List<Invitation> rows = invitations.findPage(projectId, beforeId, PageRequest.of(0, size + 1));
        boolean hasMore = rows.size() > size;
        List<Invitation> page = rows;
        if (hasMore) {
            page = rows.subList(0, size);
        }
        List<InvitationResponse> items = new ArrayList<>();
        for (Invitation i : page) {
            items.add(InvitationResponse.of(i));
        }
        String nextCursor = null;
        if (hasMore) {
            nextCursor = Ids.str(page.get(page.size() - 1).getId());
        }
        return PageResponse.of(items, hasMore, nextCursor);
    }

    /**
     * MEM-A03. 이미 폐기된 초대는 그대로 204 (명세). 이미 수락된 초대도 되돌릴 것이 없어 바꾸지 않고 204 —
     * 들어온 팀원을 내보내는 건 팀원 제외(MEM-A09)다.
     */
    @Transactional
    public void revoke(CallerContext caller, long projectId, long invitationId) {
        access.requireHumanLeader(caller, projectId);
        Invitation invitation = invitations.findForUpdate(invitationId, projectId)
                .orElseThrow(() -> AccessErrors.notFound("invitation_missing project=" + projectId
                        + " invitation=" + invitationId));
        if (!invitation.isAccepted() && !invitation.isRevoked()) {
            invitation.revoke(Instant.now());
        }
    }

    /** MEM-A04. 초대를 소비하지 않는다. loggedIn 은 세션이 있는지 — 없으면 수락 전에 로그인이 필요하다. */
    @Transactional(readOnly = true)
    public InvitationPreviewResponse preview(String token, boolean loggedIn) {
        Invitation invitation = findByToken(token);
        Project project = activeProject(invitation);
        rejectRevoked(invitation);
        if (invitation.isAccepted()) {
            throw AccessErrors.notFound("invitation_used invitation=" + invitation.getId());
        }
        rejectExpired(invitation, Instant.now());
        return new InvitationPreviewResponse(project.getName(), invitation.getExpiresAt(), !loggedIn);
    }

    /**
     * MEM-A05. 초대 소비와 멤버십 생성을 한 트랜잭션에서 한다.
     * 초대 행 → 프로젝트 행 순으로 잠근다: 같은 초대를 두 번 수락하거나 폐기와 겹쳐도 한쪽만 성공하고,
     * 프로젝트 삭제(PRJ-A05)와 겹쳐도 삭제된 프로젝트에 들어가지 않는다. 같은 계정이 같은 프로젝트의 초대 둘을
     * 동시에 수락해도 프로젝트 잠금에서 줄을 서 멤버십이 하나만 생긴다.
     * 두 행을 다 잠그는 건 수락뿐이라(삭제는 프로젝트만, 폐기는 초대만) 잠금 순서가 엇갈리지 않는다.
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
        if (invitation.isAccepted()) {
            throw new ApiException(HttpStatus.CONFLICT, "invitation_used", "이미 소비된 초대");
        }
        Instant now = Instant.now();
        rejectExpired(invitation, now);
        // 이메일 지정 초대는 그 이메일 계정만 쓸 수 있다 (Q05 제안). 남의 초대 존재를 알리지 않게 무효와 같은 404
        if (invitation.getEmail() != null && !invitation.getEmail().equals(me.getEmail())) {
            throw AccessErrors.notFound("invitation_email_mismatch invitation=" + invitation.getId()
                    + " account=" + me.getId());
        }

        Optional<ProjectMember> existing = members.findByProjectIdAndAccountId(project.getId(), me.getId());
        if (existing.isPresent() && existing.get().isActive()) {
            throw new ApiException(HttpStatus.CONFLICT, "already_member", "이미 참여");
        }
        ProjectMember member;
        if (existing.isPresent()) {
            member = existing.get();
            member.rejoin();
        } else {
            member = ProjectMember.member(project.getId(), me.getId());
        }
        // updated_at 은 flush 때 채워진다
        member = members.saveAndFlush(member);
        invitation.accept(me.getId(), now);

        boolean agentConnected = !members.findAgentConnectedAccountIds(project.getId(), List.of(me.getId())).isEmpty();
        return new AcceptedInvitationResponse(projectService.toResponse(project, ProjectRole.MEMBER),
                MemberResponse.of(member, me, agentConnected));
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

    private static void rejectExpired(Invitation invitation, Instant now) {
        if (invitation.isExpired(now)) {
            throw new ApiException(HttpStatus.GONE, "invitation_expired", "만료");
        }
    }

    private String inviteUrl(String token) {
        String base = config.baseUrl();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/invite?token=" + token;
    }
}
