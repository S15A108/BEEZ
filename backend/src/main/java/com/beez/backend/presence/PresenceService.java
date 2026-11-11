package com.beez.backend.presence;

import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.access.ProjectActor;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.project.MemberRow;
import com.beez.backend.project.ProjectMemberRepository;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 팀원 접속 상태·위치 (MON-A06·A07, S15P31A108-386).
 * 활동 기록·알림·변경 이벤트를 만들지 않는다 — 위치 보고는 감사 대상이 아니고 15초마다 오는 신호다.
 * 사람 세션만 쓴다. 에이전트 요청은 requireHuman 이 막는다.
 */
@Service
public class PresenceService {

    /** FE 사무실의 Zone (frontend/src/three/types.ts) 과 같다. */
    static final Set<String> ZONES =
            Set.of("meeting", "lead", "dev", "review", "board", "lobby", "humandesk", "control", "hr");

    /** 열어 둔 메뉴 (FE workspaceStore 의 WorkspacePane). */
    static final Set<String> PANELS = Set.of("documents", "planning", "changes", "tasks", "meetings", "relations",
            "dashboard", "settings", "inbox", "chat", "context");

    private static final Pattern SEAT = Pattern.compile("^[A-Za-z0-9_-]{1,16}$");

    private final ProjectAccessChecker access;
    private final ProjectMemberRepository members;
    private final PresenceStore store;
    private final PresenceReportLimiter limiter;

    public PresenceService(ProjectAccessChecker access, ProjectMemberRepository members, PresenceStore store,
                           PresenceReportLimiter limiter) {
        this.access = access;
        this.members = members;
        this.store = store;
        this.limiter = limiter;
    }

    /** MON-A06. 권한 → 빈도 제한 → 입력 검증 → 저장. 잘못된 값을 반복해 보내도 한도를 쓴다. */
    public void report(CallerContext caller, long projectId, PresenceReportRequest req) {
        ProjectActor actor = access.requireMember(caller, projectId);
        actor.requireHuman();
        limiter.acquire(actor.accountId(), projectId);
        PresenceReportRequest body = req == null ? new PresenceReportRequest(null, null, null) : req;
        if (body.zone() != null && !ZONES.contains(body.zone())) {
            throw invalid("zone: 허용되지 않은 값입니다");
        }
        if (body.seat() != null && !SEAT.matcher(body.seat()).matches()) {
            throw invalid("seat: 영문·숫자·-·_ 로 1~16자여야 합니다");
        }
        if (body.panel() != null && !PANELS.contains(body.panel())) {
            throw invalid("panel: 허용되지 않은 값입니다");
        }
        store.report(projectId, actor.memberId(), body.zone(), body.seat(), body.panel());
    }

    /** MON-A07. 활성 멤버마다 하나. 접속한 적 없는 사람도 online=false 로 넣는다. */
    @Transactional(readOnly = true)
    public PresenceResponse list(CallerContext caller, long projectId) {
        ProjectActor actor = access.requireMember(caller, projectId);
        actor.requireHuman();
        List<MemberRow> rows = members.findActiveMembers(projectId, Long.MAX_VALUE, Pageable.unpaged());
        return new PresenceResponse(rows.stream().map(r -> {
            long memberId = r.member().getId();
            return store.find(projectId, memberId)
                    .map(e -> PresenceResponse.Item.of(memberId, store.isOnline(e), e))
                    .orElseGet(() -> PresenceResponse.Item.never(memberId));
        }).toList());
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", message);
    }
}
