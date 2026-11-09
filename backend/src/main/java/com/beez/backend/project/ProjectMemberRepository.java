package com.beez.backend.project;

import com.beez.backend.agenttoken.AgentConnectionRule;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProjectMemberRepository extends JpaRepository<ProjectMember, Long> {

    /**
     * 활성 멤버십: 멤버 active, 프로젝트 삭제 안 됨, 계정 탈퇴 안 됨.
     * 권한 검사가 매 요청 부르는 쿼리다 (캐시 없음, -172 합의 4).
     */
    @Query("""
            select m from ProjectMember m
              join Project p on p.id = m.projectId
              join Account a on a.id = m.accountId
             where m.projectId = :projectId
               and m.accountId = :accountId
               and m.active = true
               and p.archivedAt is null
               and a.deletedAt is null
            """)
    Optional<ProjectMember> findActiveMembership(@Param("projectId") long projectId,
                                                 @Param("accountId") long accountId);

    /** 멤버 번호가 이 프로젝트의 활성 멤버인지 (담당자 지정·변경). 조건은 {@link #findActiveMembership} 과 같다. */
    default boolean isActiveMember(long memberId, long projectId) {
        return findActiveRole(memberId, projectId).isPresent();
    }

    /**
     * 멤버 번호가 이 프로젝트의 활성 팀장인지. 팀원 관리가 프로젝트 행을 잠근 뒤 요청자의 팀장 권한을 다시 볼 때 쓴다 (MEM-A08 처리 규칙).
     */
    default boolean isActiveLeader(long memberId, long projectId) {
        return findActiveRole(memberId, projectId).filter(r -> r == ProjectRole.LEADER).isPresent();
    }

    /**
     * 활성 멤버의 역할 ({@link #isActiveMember}·{@link #isActiveLeader} 로만 부른다). 조건은 {@link #findActiveMembership} 과 같다.
     * 엔티티가 아니라 값을 읽으므로, 권한 검사가 먼저 읽어 둔 옛 상태가 아니라 DB 의 지금 값을 본다.
     */
    @Query("""
            select m.role from ProjectMember m
              join Project p on p.id = m.projectId
              join Account a on a.id = m.accountId
             where m.id = :memberId
               and m.projectId = :projectId
               and m.active = true
               and p.archivedAt is null
               and a.deletedAt is null
            """)
    Optional<ProjectRole> findActiveRole(@Param("memberId") long memberId, @Param("projectId") long projectId);

    /**
     * 팀원 목록 (MEM-A06). 활성 조건은 {@link #findActiveMembership} 과 같다. id 내림차순 커서 — 프로젝트·초대 목록과 같은 방식.
     */
    @Query("""
            select new com.beez.backend.project.MemberRow(m, a)
              from ProjectMember m
              join Project p on p.id = m.projectId
              join Account a on a.id = m.accountId
             where m.projectId = :projectId
               and m.active = true
               and p.archivedAt is null
               and a.deletedAt is null
               and m.id < :beforeId
             order by m.id desc
            """)
    List<MemberRow> findActiveMembers(@Param("projectId") long projectId, @Param("beforeId") long beforeId,
                                      Pageable pageable);

    /** 활성 멤버 수 (PRJ-A06 member_count). 조건은 {@link #findActiveMembers} 와 같아 팀원 목록 건수와 맞는다. */
    @Query("""
            select count(m) from ProjectMember m
              join Project p on p.id = m.projectId
              join Account a on a.id = m.accountId
             where m.projectId = :projectId
               and m.active = true
               and p.archivedAt is null
               and a.deletedAt is null
            """)
    long countActiveMembers(@Param("projectId") long projectId);

    /**
     * accountIds 중 이 프로젝트의 활성 멤버인 계정 (알림 수신자 거르기, -181). 조건은 {@link #findActiveMembership} 과 같다.
     * 수신자가 몇 명이든 조회 한 번이다 — 발행처럼 project 행 잠금을 쥔 트랜잭션에서도 불린다.
     * Hibernate 를 거치는 쿼리라 실행 전에 자동 flush 되어, 같은 트랜잭션에서 방금 바꾼(아직 flush 전) 멤버십도 반영된다.
     * 빈 목록이면 쿼리하지 않는다 — {@code in ()} 은 SQL 문법 오류다.
     */
    default List<Long> activeAccountIdsAmong(long projectId, Collection<Long> accountIds) {
        if (accountIds.isEmpty()) {
            return List.of();
        }
        return findActiveAccountIdsAmong(projectId, accountIds);
    }

    /** {@link #activeAccountIdsAmong} 로만 부른다. */
    @Query("""
            select m.accountId from ProjectMember m
              join Project p on p.id = m.projectId
              join Account a on a.id = m.accountId
             where m.projectId = :projectId
               and m.accountId in (:accountIds)
               and m.active = true
               and p.archivedAt is null
               and a.deletedAt is null
            """)
    List<Long> findActiveAccountIdsAmong(@Param("projectId") long projectId,
                                         @Param("accountIds") Collection<Long> accountIds);

    /**
     * 멤버십 행을 잠그고 읽는다 (SELECT … FOR UPDATE). 토큰 발급(AGT-A01)이 이걸로 줄을 세워 동시 발급이 한도를 넘지 않게 한다.
     * 이미 영속성 컨텍스트에 있는 엔티티는 옛 값 그대로 돌아오므로, 잠근 뒤 상태는 값 조회({@link #isActiveMember})로 다시 본다.
     * 팀원 관리(-212)는 이 메서드 대신 {@code ProjectMemberService.lockMemberRow} 의 {@code em.refresh(m, PESSIMISTIC_WRITE)} 로
     * 같은 행을 잠근다 — 잠그면서 지금 값으로 다시 읽어야 해서다. 두 경로가 같은 행을 잠가, 발급과 제외·나가기가 겹쳐도
     * 제외 뒤에 폐기 안 된 토큰이 남지 않는다. 한쪽 잠금을 바꾸면 다른 쪽도 함께 본다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from ProjectMember m where m.id = :id")
    Optional<ProjectMember> findForUpdate(@Param("id") long id);

    /** 활성이든 아니든 이 프로젝트에서 이 계정의 멤버십 행 (초대 수락 시 되살릴지 판단). */
    Optional<ProjectMember> findByProjectIdAndAccountId(long projectId, long accountId);

    /**
     * accountIds 중 에이전트가 연결된 계정 (Member.agent_connected). 폐기 안 된 토큰에 끝나지 않고 최근 2시간 안에 통신한
     * agent_session 이 있으면 연결로 본다 (-173 A06 합의, -178 재조정). 닫히거나 새 연결에 밀려난 세션은 세지 않는다.
     * 2시간: DELETE 없이 꺼진 클라이언트·배포 재시작으로 사라진 세션은 끝남이 찍히지 않는다. MCP 서버는 마지막 요청 후 1~2시간에
     * 유휴 세션을 지우므로(McpServerConfig sessionSweepInterval) 그보다 오래 조용하면 세션이 남아 있지 않다.
     * 기준은 연결 현황(AGT-A05)·작업 현황(MON-A05)과 같은 {@link AgentConnectionRule#LIVE_SESSION_SQL} 이다 (-75).
     * 빈 목록이면 쿼리하지 않는다 — {@code in ()} 은 SQL 문법 오류다.
     */
    default List<Long> agentConnectedAccountIds(long projectId, Collection<Long> accountIds) {
        if (accountIds.isEmpty()) {
            return List.of();
        }
        return findAgentConnectedAccountIds(projectId, accountIds);
    }

    /**
     * {@link #agentConnectedAccountIds} 로만 부른다. 프로젝트 쪽에서 토큰·세션을 함께 보려고 SQL 로 읽는다.
     * 애너테이션 값은 상수식이어야 해서 formatted() 대신 연결 판정 조각을 이어 붙이고, 조각 뒤 줄바꿈을 "\n" 으로 적는다.
     */
    @Query(value = """
            select distinct t.account_id
              from agent_token t
              join agent_session s on s.agent_token_id = t.id
                                  and """ + AgentConnectionRule.LIVE_SESSION_SQL + "\n" + """
             where t.project_id = :projectId
               and t.revoked_at is null
               and t.account_id in (:accountIds)
            """, nativeQuery = true)
    List<Long> findAgentConnectedAccountIds(@Param("projectId") long projectId,
                                            @Param("accountIds") Collection<Long> accountIds);

    /**
     * 회원 탈퇴 판정(ACC-A10·A13, -171): 이 계정의 활성 멤버십마다 프로젝트와 본인 말고 활성 팀원 수. 프로젝트 id 순서.
     * 활성 조건은 {@link #findActiveMembership} 과 같다 — 탈퇴한 팀원은 세지 않는다.
     */
    @Query("""
            select new com.beez.backend.project.WithdrawalMembershipRow(m.id, p.id, p.name, m.role,
                   (select count(o) from ProjectMember o
                      join Account oa on oa.id = o.accountId
                     where o.projectId = m.projectId
                       and o.active = true
                       and oa.deletedAt is null
                       and o.id <> m.id))
              from ProjectMember m
              join Project p on p.id = m.projectId
             where m.accountId = :accountId
               and m.active = true
               and p.archivedAt is null
             order by p.id
            """)
    List<WithdrawalMembershipRow> findMembershipsForWithdrawal(@Param("accountId") long accountId);
}
