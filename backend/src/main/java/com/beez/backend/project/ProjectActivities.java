package com.beez.backend.project;

import com.beez.backend.activity.ActivityEvent;
import com.beez.backend.activity.ActivityRecorder;
import com.beez.backend.activity.ActivityTargetType;
import com.beez.backend.common.auth.CallerContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 프로젝트·멤버 활동 (S15P31A108-289, 계약 docs/design/10-notification-activity-contract.md 5절·6.2). 유형 상수와 키를 여기 모아
 * 생성(PRJ-A01)·참여(MEM-A05·자동 참여)·위임(MEM-A08·A10)·제외(MEM-A09)·나가기(MEM-A10·탈퇴 ACC-A10)가 같은 규칙으로 남긴다.
 *
 * <p><b>부르는 때.</b> 도메인 변경을 저장(flush)한 <b>뒤</b>, 같은 트랜잭션 안에서. 입구가 저장 전에 남은 변경을 flush 하지만,
 * 바꾼 쪽이 먼저 flush 해 두면 제약 위반이 그 도메인 코드 자리에서 난다(-288). 롤백되면 활동도 함께 사라진다.
 *
 * <p><b>키.</b> {@code project_member} 는 (프로젝트, 계정)마다 한 행이고 나갔다 돌아오면 그 행을 되살린다(버전 컬럼 없음). 그래서
 * 같은 멤버에게 같은 동작이 여러 번 정당하게 일어난다.
 * <ul>
 *   <li>참여: 소비한 초대 id 로 구분한다 — 참여는 매번 다른 초대를 수락으로 닫는다(수락한 초대는 다시 못 쓴다).</li>
 *   <li>나가기·제외·위임받음: 그 멤버에게 그 동작이 몇 번째인지(이미 남은 같은 활동 수 + 1)로 구분한다. 셈이 겹치지 않는 근거는
 *       <b>그 멤버십 행의 잠금</b>이다 — 부르는 쪽은 모두 대상 멤버십 행을 FOR UPDATE 로 잠근 뒤에 부른다(제외·나가기·위임은 프로젝트 행도
 *       먼저 잠그지만, 탈퇴 중 새로 참여한 프로젝트는 멤버십 행만 잠근다). 새 호출부도 이 잠금을 지켜야 한다 — 어기면 같은 키가 나와
 *       뒤의 활동이 조용히 건너뛰어진다. 커밋된 동작을 다시 보내면 도메인이 먼저 거절한다(404·403·409).</li>
 * </ul>
 */
@Component
public class ProjectActivities {

    static final String PROJECT_CREATED = "PROJECT_CREATED";
    static final String MEMBER_JOINED = "MEMBER_JOINED";
    static final String MEMBER_LEFT = "MEMBER_LEFT";
    static final String MEMBER_REMOVED = "MEMBER_REMOVED";
    static final String LEADER_TRANSFERRED = "LEADER_TRANSFERRED";

    private final ActivityRecorder activities;
    private final JdbcClient jdbc;

    public ProjectActivities(ActivityRecorder activities, JdbcClient jdbc) {
        this.activities = activities;
        this.jdbc = jdbc;
    }

    /** PRJ-A01. {@code project:{id}:created} */
    @Transactional(propagation = Propagation.MANDATORY)
    public void projectCreated(CallerContext caller, long projectId) {
        activities.record(caller, ActivityEvent.of("project:" + projectId + ":created", projectId, PROJECT_CREATED,
                ActivityTargetType.PROJECT, projectId));
    }

    /** 초대 수락(MEM-A05)·자동 참여(JOIN·REVIVE). {@code member:{id}:joined:{초대 id}} */
    @Transactional(propagation = Propagation.MANDATORY)
    public void memberJoined(CallerContext caller, ProjectMember member, long invitationId) {
        record(caller, member, MEMBER_JOINED, "joined:" + invitationId);
    }

    /** 제외(MEM-A09)·나가기(MEM-A10·탈퇴). {@code member:{id}:removed:{n}}·{@code member:{id}:left:{n}} */
    @Transactional(propagation = Propagation.MANDATORY)
    public void membershipEnded(CallerContext caller, ProjectMember member, MembershipEndReason reason) {
        String type = reason == MembershipEndReason.REMOVED ? MEMBER_REMOVED : MEMBER_LEFT;
        String action = reason == MembershipEndReason.REMOVED ? "removed" : "left";
        record(caller, member, type, action + ":" + nth(member, type));
    }

    /** 위임(MEM-A08·위임하고 나가기). 대상은 새 팀장. {@code member:{새 팀장 id}:leader-transferred:{n}} */
    @Transactional(propagation = Propagation.MANDATORY)
    public void leaderTransferred(CallerContext caller, ProjectMember newLeader) {
        record(caller, newLeader, LEADER_TRANSFERRED, "leader-transferred:" + nth(newLeader, LEADER_TRANSFERRED));
    }

    private void record(CallerContext caller, ProjectMember member, String type, String suffix) {
        activities.record(caller, ActivityEvent.of("member:" + member.getId() + ":" + suffix, member.getProjectId(), type,
                ActivityTargetType.MEMBER, member.getId()));
    }

    /**
     * 이 멤버에게 이 유형이 몇 번째인지. JdbcClient 라 영속성 컨텍스트를 flush 하지 않는다(입구가 저장 직전에 한다).
     * 같은 트랜잭션에서 입구가 넣은 행도 같은 연결이라 보인다.
     */
    private long nth(ProjectMember member, String type) {
        long before = jdbc.sql("""
                        select count(*) from activity_event
                         where project_id = :p and ref_kind = 'MEMBER' and ref_id = :m and event_type = :t
                        """)
                .param("p", member.getProjectId())
                .param("m", member.getId())
                .param("t", type)
                .query(Long.class).single();
        return before + 1;
    }
}
