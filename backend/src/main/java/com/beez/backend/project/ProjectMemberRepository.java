package com.beez.backend.project;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProjectMemberRepository extends JpaRepository<ProjectMember, Long> {

    /** 삭제되지 않은 프로젝트의 활성 멤버십. 권한 검사가 매 요청 부르는 유일한 쿼리다 (캐시 없음, -172 합의 4). */
    @Query("""
            select m from ProjectMember m
              join Project p on p.id = m.projectId
             where m.projectId = :projectId
               and m.accountId = :accountId
               and m.active = true
               and p.archivedAt is null
            """)
    Optional<ProjectMember> findActiveMembership(@Param("projectId") long projectId,
                                                 @Param("accountId") long accountId);
}
