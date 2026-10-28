package com.beez.backend.project;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
