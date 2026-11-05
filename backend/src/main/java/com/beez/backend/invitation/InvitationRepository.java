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

    /**
     * 자동 참여(ACC-A01·A02, -220): 이 이메일의 대기 초대를 id 순으로 잠그고 읽는다 (V32 ix_invitation_pending_email).
     * 잠금 순서는 MEM-A05 수락과 같다 — 초대 행을 먼저, 프로젝트 행을 나중에. id 순이라 같은 계정의 동시 로그인끼리도 엇갈리지 않는다.
     * 다른 트랜잭션이 먼저 수락·폐기했으면 잠금을 기다린 뒤 조건을 다시 봐 빠진다 (Postgres FOR UPDATE 재평가).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select i from Invitation i
             where i.email = :email
               and i.acceptedAt is null
               and i.revokedAt is null
             order by i.id
            """)
    List<Invitation> findPendingByEmailForUpdate(@Param("email") String email);

    /**
     * 팀원 제외(MEM-A09) 때 그 사람 이메일의 이 프로젝트 대기 초대를 잠그고 읽는다. 자동 참여와 같이 id 순이라
     * 두 쪽이 같은 초대들을 잠가도 순서가 엇갈리지 않는다. 제외는 이것을 프로젝트 행보다 먼저 잠근다 (초대 → 프로젝트).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select i from Invitation i
             where i.projectId = :projectId
               and i.email = :email
               and i.acceptedAt is null
               and i.revokedAt is null
             order by i.id
            """)
    List<Invitation> findPendingByProjectAndEmailForUpdate(@Param("projectId") long projectId,
                                                           @Param("email") String email);

    /** 이메일 초대(MEM-A01 emails)의 ALREADY_INVITED 판정: 이 프로젝트에서 이 이메일의 대기 초대 중 가장 오래된 것. */
    Optional<Invitation> findFirstByProjectIdAndEmailAndAcceptedAtIsNullAndRevokedAtIsNullOrderByIdAsc(
            long projectId, String email);

    /** 초대 목록 (MEM-A02). id 내림차순 커서 — 프로젝트 목록과 같은 방식. */
    @Query("""
            select i from Invitation i
             where i.projectId = :projectId
               and i.id < :beforeId
             order by i.id desc
            """)
    List<Invitation> findPage(@Param("projectId") long projectId, @Param("beforeId") long beforeId, Pageable pageable);
}
