package com.beez.backend.invitation;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InvitationRepository extends JpaRepository<Invitation, Long> {

    Optional<Invitation> findByTokenHash(String tokenHash);

    /**
     * 수락용: 토큰으로 바로 잠그고 읽는다. 잠금 없이 먼저 읽어 두면 같은 트랜잭션의 잠금 조회가
     * 영속성 컨텍스트의 옛 상태를 돌려줘 동시 수락을 못 막는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Invitation i where i.tokenHash = :tokenHash")
    Optional<Invitation> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    /**
     * 초대 행을 잠그고 읽는다. 수락·폐기가 같은 초대를 동시에 바꾸면 "한 번만 수락", "폐기 후 수락 거부"가 깨져서 둘 다 이걸로 읽는다.
     * 프로젝트 ID 를 함께 걸어 다른 프로젝트 초대는 없는 것으로 본다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Invitation i where i.id = :id and i.projectId = :projectId")
    Optional<Invitation> findForUpdate(@Param("id") long id, @Param("projectId") long projectId);

    /** 초대 목록 (MEM-A02). id 내림차순 커서 — 프로젝트 목록과 같은 방식. */
    @Query("""
            select i from Invitation i
             where i.projectId = :projectId
               and i.id < :beforeId
             order by i.id desc
            """)
    List<Invitation> findPage(@Param("projectId") long projectId, @Param("beforeId") long beforeId, Pageable pageable);
}
