package com.beez.backend.auth.recovery;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TemporaryPasswordRepository extends JpaRepository<TemporaryPassword, Long> {

    /** 이 이메일의 가장 최근 요청 (다시 받기 간격). */
    Optional<TemporaryPassword> findFirstByEmailOrderByIdDesc(String email);

    long countByEmailAndCreatedAtAfter(String email, Instant after);

    /** 서버 전체 발송 상한: 실제로 메일을 보낸 요청만 센다. */
    long countByAccountIdIsNotNullAndCreatedAtAfter(Instant after);

    /**
     * 이 계정의 열린 임시 비밀번호를 닫는다 (새로 발급·비밀번호 변경). 엔티티를 바꿔 닫으면 Hibernate 가 flush 때
     * INSERT 를 UPDATE 보다 먼저 보내 ux_temporary_password_open 에 걸리므로 벌크 UPDATE 로 먼저 닫는다 (-219 closeOpen 과 같음).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update TemporaryPassword t set t.closedAt = :now, t.updatedAt = :now"
            + " where t.accountId = :accountId and t.closedAt is null")
    int closeOpen(@Param("accountId") long accountId, @Param("now") Instant now);

    /** 로그인: 같은 임시 비밀번호로 동시에 로그인해도 한 번만 쓰이도록 잠그고 읽는다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TemporaryPassword t where t.accountId = :accountId and t.closedAt is null")
    Optional<TemporaryPassword> findOpenForUpdate(@Param("accountId") long accountId);
}
