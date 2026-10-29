package com.beez.backend.relation.service;

import com.beez.backend.common.access.AccessErrors;
import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.access.ProjectActor;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.designitem.repository.DesignProjectRepository;
import com.beez.backend.relation.dto.RelationAction;
import com.beez.backend.relation.dto.RelationChange;
import com.beez.backend.relation.dto.RelationChangeRequest;
import com.beez.backend.relation.dto.RelationChangeResult;
import com.beez.backend.relation.dto.RelationCommand;
import com.beez.backend.relation.repository.Confirmer;
import com.beez.backend.relation.repository.DependencyRow;
import com.beez.backend.relation.repository.HistoryRow;
import com.beez.backend.relation.repository.RelationRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 설계 항목 의존관계 저장 서비스 (S15P31A108-167). 진입점(최초 발행 전 관계 초안 검토, 변경 제안 승인·발행)은 오서현 담당이고,
 * 그 트랜잭션 안에서 이 서비스를 한 번 부른다. 그래서 {@link Propagation#MANDATORY}: 호출자의 트랜잭션이 없으면 바로 실패한다.
 * 발행이 롤백되면 관계·이력·리비전도 함께 롤백된다.
 *
 * <p>한 번 호출에서 하는 일
 * <ol>
 *   <li>권한: 확정·유지·해제·재확인 표시가 하나라도 있으면 사람 팀장, 후보만 다루면 프로젝트 멤버</li>
 *   <li>프로젝트 행 잠금(FOR UPDATE) 뒤 역할을 DB 에서 다시 확인. 발행이 이미 같은 행을 잠갔다면 그대로 통과한다</li>
 *   <li>입력 확인 (아래 오류)</li>
 *   <li>재확인 표시 → 명령 순서대로 적용, 바뀐 쌍마다 이력 한 줄</li>
 *   <li>확정 관계 집합이 바뀌었으면 관계 집합 리비전을 <b>한 번만</b> 올리고, 그 값을 이력에 남긴다</li>
 * </ol>
 *
 * <p>오류 (호출한 API 의 응답이 된다)
 * <ul>
 *   <li>400 validation_failed — 기준이 이 프로젝트의 가장 최근 기준이 아님, 변경 제안·항목이 이 프로젝트 것이 아님, 같은 항목끼리, 같은 쌍 중복, 근거 없음·2,000자 초과,
 *       확정·유지하려는 항목이 유효 기준에 없음, 필요한 기준 없음</li>
 *   <li>400 invalid_transition — 지금 상태에서 할 수 없는 변경 (예: 이미 확정된 관계를 다시 확정, 재확인 필요가 아닌 관계를 유지,
 *       팀장이 해제한 관계를 후보로 되돌림)</li>
 *   <li>401·403·404 — {@link ProjectAccessChecker} 규칙 그대로</li>
 * </ul>
 */
@Service
public class RelationStorageService {

    static final String EXPLICIT = "EXPLICIT";
    static final String AI_CANDIDATE = "AI_CANDIDATE";
    static final String CONFIRMED = "CONFIRMED";
    static final String NEEDS_RECHECK = "NEEDS_RECHECK";
    static final String RELEASED = "RELEASED";
    static final String CANDIDATE = "CANDIDATE";
    static final String REJECTED = "REJECTED";

    /** 근거 최대 길이 (Unicode 코드 포인트). 자바 String.length() 는 이모지 같은 글자를 2 로 세므로 따로 센다 */
    static final int MAX_REASON = 2000;

    private final ProjectAccessChecker access;
    private final RelationRepository relations;
    private final DesignProjectRepository projects;

    public RelationStorageService(ProjectAccessChecker access, RelationRepository relations,
                                  DesignProjectRepository projects) {
        this.access = access;
        this.relations = relations;
        this.projects = projects;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public RelationChangeResult apply(CallerContext caller, long projectId, RelationChangeRequest request) {
        if (request == null) {
            throw invalid("관계 변경 요청이 없습니다");
        }
        boolean candidatesOnly = request.recheckItemIds().isEmpty();
        for (RelationCommand command : request.commands()) {
            if (command == null || command.action() == null || !command.action().isCandidateAction()) {
                candidatesOnly = false;
            }
        }
        ProjectActor actor = requireActor(caller, projectId, candidatesOnly);

        validate(projectId, request, candidatesOnly);

        // ① 바뀐 항목에 걸린 확정 관계를 먼저 잠근다. 상대편 항목도 이력에 버전을 남겨야 하므로 버전을 읽기 전에 모은다
        List<DependencyRow> toRecheck = relations.lockConfirmedTouching(projectId, new TreeSet<>(request.recheckItemIds()));

        Set<Long> allItems = new HashSet<>(request.recheckItemIds());
        for (DependencyRow row : toRecheck) {
            allItems.add(row.fromItemId());
            allItems.add(row.toItemId());
        }
        for (RelationCommand command : request.commands()) {
            allItems.add(command.fromItemId());
            allItems.add(command.toItemId());
        }
        Map<Long, Long> versions = Map.of();
        if (request.baselineId() != null) {
            versions = relations.versionsInBaseline(projectId, request.baselineId(), allItems);
        }

        List<HistoryRow> pending = new ArrayList<>();
        boolean setChanged = false;

        // 재확인 필요로 표시한다. 자동 해제하지 않는다 (#40 3.5절 7번)
        for (DependencyRow row : toRecheck) {
            relations.updateStatus(projectId, row.fromItemId(), row.toItemId(), NEEDS_RECHECK);
            pending.add(history(projectId, row.fromItemId(), row.toItemId(), "MARK_RECHECK", EXPLICIT, NEEDS_RECHECK,
                    request, versions, request.recheckReason(), actor));
            setChanged = true;
        }

        // ② 쌍 단위 명령
        for (RelationCommand command : request.commands()) {
            long from = command.fromItemId();
            long to = command.toItemId();
            Optional<DependencyRow> current = relations.lockPair(projectId, from, to);
            String status = null;
            if (current.isPresent()) {
                status = current.get().status();
            }
            RelationAction action = command.action();
            if (action == RelationAction.CONFIRM) {
                requireState(command, status, null, RELEASED, CANDIDATE, REJECTED);
                requireInBaseline(command, versions);
                relations.upsert(projectId, from, to, EXPLICIT, CONFIRMED, command.reason(), Confirmer.set(actor.accountId()));
                pending.add(history(projectId, from, to, "CONFIRM", EXPLICIT, CONFIRMED, request, versions,
                        command.reason(), actor));
                setChanged = true;
            } else if (action == RelationAction.KEEP) {
                requireState(command, status, NEEDS_RECHECK);
                requireInBaseline(command, versions);
                relations.upsert(projectId, from, to, EXPLICIT, CONFIRMED, command.reason(), Confirmer.set(actor.accountId()));
                pending.add(history(projectId, from, to, "KEEP", EXPLICIT, CONFIRMED, request, versions,
                        command.reason(), actor));
                setChanged = true;
            } else if (action == RelationAction.RELEASE) {
                requireState(command, status, CONFIRMED, NEEDS_RECHECK);
                relations.upsert(projectId, from, to, EXPLICIT, RELEASED, command.reason(), Confirmer.keep());
                pending.add(history(projectId, from, to, "RELEASE", EXPLICIT, RELEASED, request, versions,
                        command.reason(), actor));
                setChanged = true;
            } else if (action == RelationAction.ADD_CANDIDATE) {
                // 사람이 해제한 관계(RELEASED)는 후보로 되돌리지 않는다. 다시 쓰려면 팀장이 CONFIRM 한다
                requireState(command, status, null, REJECTED);
                relations.upsert(projectId, from, to, AI_CANDIDATE, CANDIDATE, command.reason(), Confirmer.clear());
                pending.add(history(projectId, from, to, "ADD_CANDIDATE", AI_CANDIDATE, CANDIDATE, request, versions,
                        command.reason(), actor));
            } else {
                requireState(command, status, CANDIDATE);
                relations.upsert(projectId, from, to, AI_CANDIDATE, REJECTED, command.reason(), Confirmer.clear());
                pending.add(history(projectId, from, to, "REJECT_CANDIDATE", AI_CANDIDATE, REJECTED, request, versions,
                        command.reason(), actor));
            }
        }

        // ③ 확정 관계 집합이 바뀌었으면 리비전을 한 번만 올린다
        long revision;
        if (setChanged) {
            revision = relations.bumpRevision(projectId);
        } else {
            revision = relations.currentRevision(projectId);
        }

        List<RelationChange> changes = new ArrayList<>();
        for (HistoryRow row : pending) {
            HistoryRow saved = row;
            if (!isCandidateAction(row.action())) {
                saved = row.withRevision(revision);
            }
            long historyId = relations.insertHistory(saved);
            changes.add(new RelationChange(historyId, row.fromItemId(), row.toItemId(), row.action(),
                    row.originAfter(), row.statusAfter()));
        }
        return new RelationChangeResult(revision, changes);
    }

    // ---- 권한 ----

    /**
     * 확정 관계를 바꾸면 사람 팀장, 후보만이면 멤버. 프로젝트 행을 잠근 뒤 역할을 DB 에서 다시 읽는다 —
     * 잠금을 기다리는 동안 팀장이 바뀌었을 수 있기 때문이다 (설계 항목 발행의 DesignProjectLock 과 같은 방식).
     */
    private ProjectActor requireActor(CallerContext caller, long projectId, boolean candidatesOnly) {
        ProjectActor actor;
        if (candidatesOnly) {
            actor = access.requireMember(caller, projectId);
        } else {
            actor = access.requireHumanLeader(caller, projectId);
        }
        // 설계 항목 발행과 같은 잠금·역할 조회를 쓴다 (DesignProjectRepository) — 두 곳이 어긋나지 않게
        if (!projects.lockActiveProject(projectId)) {
            throw AccessErrors.notFound("relation_project_missing project=" + projectId);
        }
        Optional<String> role = projects.findCurrentRole(projectId, actor.accountId());
        if (role.isEmpty()) {
            throw AccessErrors.notFound("relation_membership_changed project=" + projectId);
        }
        if (!candidatesOnly && !"LEADER".equals(role.get())) {
            throw AccessErrors.forbidden("relation_leader_changed project=" + projectId, "현재 사람 팀장만 관계를 확정할 수 있습니다.");
        }
        return actor;
    }

    // ---- 입력 확인 ----

    private void validate(long projectId, RelationChangeRequest request, boolean candidatesOnly) {
        if (request.commands().isEmpty() && request.recheckItemIds().isEmpty()) {
            throw invalid("바꿀 관계가 없습니다");
        }
        // 기준이 필요한 것은 확정·유지(양 끝 버전을 이력에 남김)와 재확인 표시뿐이다.
        // 해제만 하는 호출은 기준 없이도 된다 — 관계는 있지만 기준이 없는 프로젝트에서도 해제할 수 있어야 한다
        boolean needsBaseline = !request.recheckItemIds().isEmpty();
        for (RelationCommand command : request.commands()) {
            if (command != null && (command.action() == RelationAction.CONFIRM || command.action() == RelationAction.KEEP)) {
                needsBaseline = true;
            }
        }
        if (needsBaseline && request.baselineId() == null) {
            throw invalid("관계를 확정·유지하거나 재확인 표시하려면 유효 기준(baseline_id)이 필요합니다");
        }
        // 유효 기준은 이 프로젝트의 가장 최근 기준이어야 한다 = 지금 현재 기준이거나, 이번 발행에서 막 만든 기준.
        // 지난 기준을 넘기면 오래된 항목 버전을 근거로 관계를 다시 확정하게 되므로 받지 않는다 (프로젝트 잠금 안에서 읽는다)
        if (request.baselineId() != null) {
            Optional<Long> latest = relations.latestBaselineId(projectId);
            if (latest.isEmpty() || !latest.get().equals(request.baselineId())) {
                throw invalid("baseline_id 는 이 프로젝트의 현재 기준이거나 이번 발행에서 만든 기준이어야 합니다: "
                        + request.baselineId());
            }
        }
        if (request.changeProposalId() != null
                && !relations.changeProposalInProject(request.changeProposalId(), projectId)) {
            throw invalid("change_proposal_id 가 이 프로젝트의 변경 제안이 아닙니다: " + request.changeProposalId());
        }
        if (request.recheckReason() != null && tooLong(request.recheckReason())) {
            throw invalid("재확인 사유는 " + MAX_REASON + "자 이하여야 합니다");
        }

        Set<Long> items = new LinkedHashSet<>();
        for (Long itemId : request.recheckItemIds()) {
            if (itemId == null) {
                throw invalid("재확인할 항목에 빈 값이 있습니다");
            }
            items.add(itemId);
        }
        Set<String> pairs = new HashSet<>();
        for (RelationCommand command : request.commands()) {
            if (command == null || command.action() == null || command.fromItemId() == null || command.toItemId() == null) {
                throw invalid("관계 변경에 빈 값이 있습니다");
            }
            if (command.fromItemId().equals(command.toItemId())) {
                throw invalid("같은 항목끼리는 관계를 둘 수 없습니다: " + command.fromItemId());
            }
            if (!pairs.add(command.fromItemId() + ">" + command.toItemId())) {
                throw invalid("같은 관계를 한 요청에서 두 번 바꿀 수 없습니다: " + command.fromItemId() + " → " + command.toItemId());
            }
            if (command.reason() == null || command.reason().isBlank()) {
                throw invalid("관계 변경에는 근거가 필요합니다: " + command.fromItemId() + " → " + command.toItemId());
            }
            if (tooLong(command.reason())) {
                throw invalid("근거는 " + MAX_REASON + "자 이하여야 합니다");
            }
            items.add(command.fromItemId());
            items.add(command.toItemId());
        }
        if (relations.countItemsInProject(items, projectId) != items.size()) {
            throw invalid("이 프로젝트의 설계 항목이 아닌 것이 있습니다");
        }
    }

    /** 지금 상태가 allowed 중 하나인지. null 은 "관계 없음" */
    private static void requireState(RelationCommand command, String status, String... allowed) {
        for (String state : allowed) {
            if (state == null) {
                if (status == null) {
                    return;
                }
            } else if (state.equals(status)) {
                return;
            }
        }
        String now = "없음";
        if (status != null) {
            now = status;
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_transition",
                "지금 상태(" + now + ")에서는 " + command.action() + " 할 수 없습니다: "
                        + command.fromItemId() + " → " + command.toItemId());
    }

    /** 확정·유지는 양 끝 항목이 유효 기준에 들어 있어야 한다 (이력에 그 기준의 버전을 남긴다) */
    private static void requireInBaseline(RelationCommand command, Map<Long, Long> versions) {
        if (!versions.containsKey(command.fromItemId()) || !versions.containsKey(command.toItemId())) {
            throw invalid("확정·유지하려는 관계의 항목이 유효 기준에 없습니다: "
                    + command.fromItemId() + " → " + command.toItemId());
        }
    }

    private static HistoryRow history(long projectId, long from, long to, String action, String origin, String status,
                                      RelationChangeRequest request, Map<Long, Long> versions, String reason,
                                      ProjectActor actor) {
        Long fromVersion = null;
        if (versions.containsKey(from)) {
            fromVersion = versions.get(from);
        }
        Long toVersion = null;
        if (versions.containsKey(to)) {
            toVersion = versions.get(to);
        }
        return new HistoryRow(null, projectId, from, to, action, origin, status, request.baselineId(), fromVersion,
                toVersion, request.changeProposalId(), null, reason, actor.accountId(), null);
    }

    private static boolean isCandidateAction(String action) {
        return "ADD_CANDIDATE".equals(action) || "REJECT_CANDIDATE".equals(action);
    }

    /** codePointCount: 문자열의 실제 글자(Unicode 코드 포인트) 수를 센다 */
    private static boolean tooLong(String text) {
        return text.codePointCount(0, text.length()) > MAX_REASON;
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", message);
    }
}
