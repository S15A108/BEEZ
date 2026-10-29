package com.beez.backend.project;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
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
    @Query("""
            select count(m) > 0 from ProjectMember m
              join Project p on p.id = m.projectId
              join Account a on a.id = m.accountId
             where m.id = :memberId
               and m.projectId = :projectId
               and m.active = true
               and p.archivedAt is null
               and a.deletedAt is null
            """)
    boolean isActiveMember(@Param("memberId") long memberId, @Param("projectId") long projectId);

    /**
     * 멤버십 행을 잠그고 읽는다 (SELECT … FOR UPDATE). 토큰 발급(AGT-A01)이 이걸로 줄을 세워 동시 발급이 한도를 넘지 않게 한다.
     * 제외·나가기(-212)도 같은 행을 잠그면, 발급과 겹쳐도 제외 뒤에 폐기 안 된 토큰이 남지 않는다.
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
