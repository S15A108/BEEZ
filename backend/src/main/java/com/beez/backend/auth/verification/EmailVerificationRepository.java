package com.beez.backend.auth.verification;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmailVerificationRepository extends JpaRepository<EmailVerification, Long> {

    /** 이 이메일의 가장 최근 발송 (다시 받기 간격). */
    Optional<EmailVerification> findFirstByEmailOrderByIdDesc(String email);

    /** 한도 창 안의 발송 수 (시간당 발송 한도). 발송에 실패한 요청은 지우므로 세지 않는다. */
    long countByEmailAndCreatedAtAfter(String email, Instant since);

    /** 한도 창 안의 서버 전체 발송 수 (주소를 바꿔 가며 보내는 남용 방지). */
    long countByCreatedAtAfter(Instant since);

    /**
     * 다시 받기: 이 이메일의 열린 코드를 모두 닫는다. 새 행을 넣기 전에 바로 실행해야 한다 —
     * JPA 는 flush 때 INSERT 를 UPDATE 보다 먼저 보내므로 엔티티 변경으로 닫으면 ux_email_verification_open 에 걸린다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update EmailVerification v set v.closedAt = :now where v.email = :email and v.closedAt is null")
    int closeOpen(@Param("email") String email, @Param("now") Instant now);

    /** 회원 탈퇴(ACC-A10, -277): 이 이메일의 인증 요청 행을 모두 지운다. 가입 때 쓴 행도 함께다. */
    @Modifying(flushAutomatically = true)
    @Query("delete from EmailVerification v where v.email = :email")
    int deleteByEmail(@Param("email") String email);

    /** 코드 확인: 같은 요청에 동시에 들어온 시도가 틀린 횟수를 함께 세도록 행을 잠그고 읽는다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from EmailVerification v where v.publicId = :publicId")
    Optional<EmailVerification> findByPublicIdForUpdate(@Param("publicId") String publicId);

    /** 가입: 같은 토큰으로 동시에 가입해도 한 번만 쓰이도록 잠그고 읽는다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from EmailVerification v where v.tokenHash = :tokenHash")
    Optional<EmailVerification> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);
}
