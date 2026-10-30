package com.beez.backend.project;

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
     * accountIds 중 에이전트가 연결된 계정 (Member.agent_connected). 폐기 안 된 토큰에 agent_session 이 있으면 연결로 본다
     * (-173 A06 합의, -178 에서 재조정). 빈 목록이면 쿼리하지 않는다 — {@code in ()} 은 SQL 문법 오류다.
     */
    default List<Long> agentConnectedAccountIds(long projectId, Collection<Long> accountIds) {
        if (accountIds.isEmpty()) {
            return List.of();
        }
        return findAgentConnectedAccountIds(projectId, accountIds);
    }

    /** {@link #agentConnectedAccountIds} 로만 부른다. agent_session 엔티티가 아직 없어 SQL 로 읽는다. */
    @Query(value = """
            select distinct t.account_id
              from agent_token t
              join agent_session s on s.agent_token_id = t.id
             where t.project_id = :projectId
               and t.revoked_at is null
               and t.account_id in (:accountIds)
            """, nativeQuery = true)
    List<Long> findAgentConnectedAccountIds(@Param("projectId") long projectId,
                                            @Param("accountIds") Collection<Long> accountIds);
}
