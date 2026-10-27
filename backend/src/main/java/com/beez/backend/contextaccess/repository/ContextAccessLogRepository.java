package com.beez.backend.contextaccess.repository;

import com.beez.backend.contextaccess.entity.ContextAccessLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** 저장과 목록 조회만. 수정·삭제는 쓰지 않는다 (append-only) */
public interface ContextAccessLogRepository extends JpaRepository<ContextAccessLog, Long>,
        JpaSpecificationExecutor<ContextAccessLog> {
}
