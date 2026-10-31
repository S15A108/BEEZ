package com.beez.backend.context.repository;

import com.beez.backend.context.entity.ContextRecord;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ContextRecordRepository extends JpaRepository<ContextRecord, Long> {

    /** 번호와 프로젝트를 함께 조건으로 (-172 합의 5) */
    Optional<ContextRecord> findByIdAndProjectId(Long id, Long projectId);

    /** 이전 판을 잠그고 읽는다. 같은 이전 판을 동시에 고치면 두 번째가 기다렸다가 후속 판 존재로 409 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ContextRecord r where r.id = :id and r.projectId = :projectId")
    Optional<ContextRecord> findForUpdate(@Param("id") Long id, @Param("projectId") Long projectId);

    boolean existsByPreviousId(Long previousId);
}
