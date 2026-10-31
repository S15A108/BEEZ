package com.beez.backend.project;

import com.beez.backend.account.Account;
import com.beez.backend.account.AccountRepository;
import com.beez.backend.agenttoken.AgentTokenRepository;
import com.beez.backend.common.access.AccessErrors;
import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.access.ProjectActor;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.api.Ids;
import com.beez.backend.common.api.PageResponse;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.task.service.TaskHandoverService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * MEM-A06 ~ A10 팀원 목록·표시 수정·팀장 위임·제외·나가기 (S15P31A108-212). 권한 검사는 각 메서드 첫 줄.
 * <p>
 * 잠금 순서는 프로젝트 행 → 멤버십 행. 변경 요청은 모두 프로젝트 행을 잠가 서로 줄을 세우고(위임과 제외가 겹쳐 새 팀장이 제외되는 일),
 * 바꿀 멤버십 행도 잠근다. 토큰 발급(AGT-A01)이 멤버십 행을, 작업 생성·담당자 변경이 프로젝트 행을 잠그므로
 * 제외·나가기 뒤에 폐기 안 된 토큰이나 나간 사람이 담당인 열린 작업이 남지 않는다.
 * 잠근 뒤에는 {@link #lockMember} 로 행을 다시 읽는다 — 권한 검사가 먼저 읽어 둔 엔티티는 잠금 조회에서 옛 상태 그대로 돌아온다.
 */
@Service
public class ProjectMemberService {

    static final int DISPLAY_TEXT_MAX = 50;
    private static final Pattern COLOR = Pattern.compile("#[0-9A-Fa-f]{6}");

    private final ProjectAccessChecker access;
    private final ProjectService projectService;
    private final ProjectMemberRepository members;
    private final AccountRepository accounts;
    private final AgentTokenRepository tokens;
    private final TaskHandoverService handover;
    private final EntityManager em;

    public ProjectMemberService(ProjectAccessChecker access, ProjectService projectService,
                                ProjectMemberRepository members, AccountRepository accounts, AgentTokenRepository tokens,
                                TaskHandoverService handover, EntityManager em) {
        this.access = access;
        this.projectService = projectService;
        this.members = members;
        this.accounts = accounts;
        this.tokens = tokens;
        this.handover = handover;
        this.em = em;
    }

    /** MEM-A06. 활성 팀원만, id 내림차순 커서. 에이전트 연결 여부는 페이지 단위로 한 번에 읽는다. */
    @Transactional(readOnly = true)
    public PageResponse<MemberResponse> list(CallerContext caller, long projectId, String cursor, Integer limit) {
        access.requireMember(caller, projectId);
        int size = PageResponse.normalizeLimit(limit);
        long beforeId = PageResponse.beforeId(cursor);
        List<MemberRow> rows = members.findActiveMembers(projectId, beforeId, PageRequest.of(0, size + 1));
        return PageResponse.fromRows(rows, size, row -> row.member().getId(), page -> toItems(projectId, page));
    }

    /** MEM-A07. 담당 영역·라벨·색상만 바꾼다. 역할은 위임(MEM-A08)으로만 바뀐다. */
    @Transactional
    public MemberResponse update(CallerContext caller, long projectId, long memberId, UpdateMemberRequest req) {
        ProjectActor actor = access.requireHumanLeader(caller, projectId);
        if (req.domain() == null && req.roleLabel() == null && req.color() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", "변경할 필드가 하나 이상 필요합니다");
        }
        // 값 검사를 잠금 전에 끝낸다 — 400 이면 잠글 필요가 없다
        String domain = normalizeText(req.domain(), "domain");
        String roleLabel = normalizeText(req.roleLabel(), "role_label");
        String color = normalizeColor(req.color());

        // 위임과 줄을 세워 팀장 권한을 다시 본다 — 위임이 먼저 커밋되면 이전 팀장의 수정은 403 (완료 조건 "위임 직후 403")
        lockProjectAsLeader(projectId, actor);
        ProjectMember target = lockMember(memberId, projectId);
        target.changeDisplay(
                req.domain() == null ? target.getArea() : domain,
                req.roleLabel() == null ? target.getRoleLabel() : roleLabel,
                req.color() == null ? target.getColor() : color);
        members.saveAndFlush(target);
        return toResponse(projectId, target);
    }

    /**
     * MEM-A08. 현재 팀장을 MEMBER 로, 대상을 LEADER 로 한 트랜잭션에서 바꾼다.
     * 프로젝트 행을 잠근 뒤 요청자가 아직 팀장인지 다시 본다 — 같은 팀장의 위임 두 건이 겹치면 뒤의 것은 403 이다.
     */
    @Transactional
    public LeadershipTransferResponse transferLeadership(CallerContext caller, long projectId,
                                                         TransferLeadershipRequest req) {
        ProjectActor actor = access.requireHumanLeader(caller, projectId);
        long targetId = Ids.parse(req.targetMemberId(), "target_member_id");
        if (targetId == actor.memberId()) {
            throw invalidTransferTarget("본인에게는 위임할 수 없습니다");
        }
        lockProjectAsLeader(projectId, actor);

        // 두 멤버십 행을 id 순서로 잠근다 (잠금 순서를 고정해 교착을 피한다)
        boolean meFirst = actor.memberId() < targetId;
        ProjectMember first = lockMemberRow(meFirst ? actor.memberId() : targetId, projectId);
        ProjectMember second = lockMemberRow(meFirst ? targetId : actor.memberId(), projectId);
        ProjectMember current = meFirst ? first : second;
        ProjectMember target = meFirst ? second : first;
        if (target == null) {
            throw AccessErrors.notFound("member_not_in_project member=" + targetId + " project=" + projectId);
        }
        if (!members.isActiveMember(targetId, projectId)) {
            throw invalidTransferTarget("활성 팀원에게만 위임할 수 있습니다");
        }

        // 활성 팀장은 프로젝트당 하나(ux_member_one_leader, 지연 검사 아님)라 내리는 UPDATE 가 먼저 나가야 한다
        current.changeRole(ProjectRole.MEMBER);
        members.saveAndFlush(current);
        target.changeRole(ProjectRole.LEADER);
        members.saveAndFlush(target);

        List<MemberResponse> changed = toResponses(projectId, List.of(current, target));
        return new LeadershipTransferResponse(changed);
    }

    /** MEM-A09. 팀장이 팀원을 제외한다. 팀장 자신(현재 팀장)은 위임 전에 제외할 수 없다. */
    @Transactional
    public void remove(CallerContext caller, long projectId, long memberId, EndMembershipRequest req) {
        ProjectActor actor = access.requireHumanLeader(caller, projectId);
        Long reassignTo = reassignTarget(req);
        lockProjectAsLeader(projectId, actor);
        ProjectMember target = lockMember(memberId, projectId);
        end(target, reassignTo, MembershipEndReason.REMOVED);
    }

    /** MEM-A10. 본인 멤버십만 끝낸다. 원치 않는 초대로 들어온 사람도 이것으로 빠진다 (Q05). 팀장은 위임이 먼저다. */
    @Transactional
    public void leave(CallerContext caller, long projectId, EndMembershipRequest req) {
        ProjectActor actor = access.requireMember(caller, projectId);
        Long reassignTo = reassignTarget(req);
        projectService.lockActive(projectId);
        ProjectMember me = lockMember(actor.memberId(), projectId);
        end(me, reassignTo, MembershipEndReason.LEFT);
    }

    /**
     * 제외·나가기 공통. 프로젝트·멤버십 행을 잠근 뒤에 부른다. 열린 작업 인계 → 멤버십 종료 → 토큰 폐기를 한 트랜잭션에서 한다.
     * 권한 검사가 매 요청 멤버십을 읽으므로(캐시 없음) 커밋 직후 다음 요청부터 그 사람·토큰은 막힌다.
     */
    private void end(ProjectMember member, Long reassignTo, MembershipEndReason reason) {
        long projectId = member.getProjectId();
        if (member.getRole() == ProjectRole.LEADER) {
            throw new ApiException(HttpStatus.CONFLICT, "leader_transfer_required", reason == MembershipEndReason.LEFT
                    ? "팀장은 다른 팀원에게 위임한 뒤에 나갈 수 있습니다"
                    : "팀장은 위임하기 전에 제외할 수 없습니다");
        }
        if (reassignTo != null) {
            if (reassignTo.longValue() == member.getId()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", "떠나는 사람에게는 인계할 수 없습니다");
            }
            if (!members.isActiveMember(reassignTo, projectId)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed",
                        "reassign_to_member_id: 같은 프로젝트의 활성 멤버가 아닙니다");
            }
        }
        handover.handOver(projectId, member.getId(), reassignTo);
        Instant now = Instant.now();
        member.end(reason, now);
        members.saveAndFlush(member);
        tokens.revokeAllOfMember(projectId, member.getAccountId(), now);
    }

    // ------------------------------------------------------------------
    // 잠금
    // ------------------------------------------------------------------

    /** 프로젝트 행을 잠근 뒤 요청자가 아직 팀장인지 다시 본다 (MEM-A08 "요청 처리 시점의 팀장 권한 재확인"). */
    private void lockProjectAsLeader(long projectId, ProjectActor actor) {
        projectService.lockActive(projectId);
        access.requireLeaderAfterLock(actor);
    }

    /** 이 프로젝트의 활성 멤버십 행을 잠그고 지금 값으로 읽는다. 없거나 다른 프로젝트·비활성이면 404. */
    private ProjectMember lockMember(long memberId, long projectId) {
        ProjectMember m = lockMemberRow(memberId, projectId);
        if (m == null || !members.isActiveMember(memberId, projectId)) {
            throw AccessErrors.notFound("member_inactive_or_missing member=" + memberId + " project=" + projectId);
        }
        return m;
    }

    /**
     * 멤버십 행을 잠그면서 지금 값으로 다시 읽는다(refresh + FOR UPDATE 한 번). 이 프로젝트 소속이 아니면 null (잠그지 않는다).
     * 활성 여부는 보지 않는다 — 부르는 쪽이 {@link ProjectMemberRepository#isActiveMember} 로 본다(계정 탈퇴까지 포함).
     */
    private ProjectMember lockMemberRow(long memberId, long projectId) {
        // 권한 검사가 이미 읽은 행이면 쿼리 없이 돌아온다. project_id 는 바뀌지 않는 값이라 옛 상태로 봐도 된다
        ProjectMember m = em.find(ProjectMember.class, memberId);
        if (m == null || m.getProjectId() != projectId) {
            return null;
        }
        em.refresh(m, LockModeType.PESSIMISTIC_WRITE);
        return m;
    }

    // ------------------------------------------------------------------
    // 값 정리
    // ------------------------------------------------------------------

    /** 본문이 없거나 인계 대상을 안 줬으면 null. 숫자가 아니면 400. */
    private static Long reassignTarget(EndMembershipRequest req) {
        if (req == null) {
            return null;
        }
        return Ids.parse(req.reassignToMemberId(), "reassign_to_member_id");
    }

    /** 앞뒤 공백을 뺀 표시 문자열. 비었으면 null(지우기), 길면 400. 생략(null)은 null 로 돌려주고 부르는 쪽이 유지로 처리한다. */
    private static String normalizeText(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.strip();
        if (value.length() > DISPLAY_TEXT_MAX) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed",
                    field + ": " + DISPLAY_TEXT_MAX + "자 이하로 입력해야 합니다");
        }
        return value;
    }

    /** 색상은 #RRGGBB. 대문자로 맞춘다. 비었으면 null(지우기). */
    private static String normalizeColor(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.strip();
        if (!COLOR.matcher(value).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", "color: #RRGGBB 형식이어야 합니다");
        }
        return value.toUpperCase();
    }

    private static ApiException invalidTransferTarget(String message) {
        return new ApiException(HttpStatus.CONFLICT, "invalid_transfer_target", message);
    }

    // ------------------------------------------------------------------
    // 응답
    // ------------------------------------------------------------------

    private List<MemberResponse> toItems(long projectId, List<MemberRow> page) {
        List<Long> accountIds = page.stream().map(r -> r.account().getId()).toList();
        Set<Long> connected = new HashSet<>(members.agentConnectedAccountIds(projectId, accountIds));
        List<MemberResponse> items = new ArrayList<>();
        for (MemberRow row : page) {
            items.add(MemberResponse.of(row.member(), row.account(), connected.contains(row.account().getId())));
        }
        return items;
    }

    private MemberResponse toResponse(long projectId, ProjectMember member) {
        return toResponses(projectId, List.of(member)).get(0);
    }

    /** 멤버십 목록을 응답으로. 계정·에이전트 연결은 한 번에 읽는다. 순서는 넘긴 순서 그대로. */
    private List<MemberResponse> toResponses(long projectId, List<ProjectMember> list) {
        List<Long> accountIds = list.stream().map(ProjectMember::getAccountId).toList();
        Map<Long, Account> byId = accounts.findAllById(accountIds).stream()
                .collect(Collectors.toMap(Account::getId, Function.identity()));
        Set<Long> connected = new HashSet<>(members.agentConnectedAccountIds(projectId, accountIds));
        List<MemberResponse> out = new ArrayList<>();
        for (ProjectMember m : list) {
            out.add(MemberResponse.of(m, byId.get(m.getAccountId()), connected.contains(m.getAccountId())));
        }
        return out;
    }
}
