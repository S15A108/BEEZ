package com.beez.backend.document.repository;

import com.beez.backend.document.DocumentKind;
import com.beez.backend.document.entity.Document;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DocumentRepository extends JpaRepository<Document, Long> {
    /** 등록 취소한 문서는 세지 않아 같은 종류를 다시 올릴 수 있다. */
    @Query("select count(d) > 0 from Document d where d.projectId = :projectId and d.kind = :kind and d.discardedAt is null")
    boolean existsByProjectIdAndKind(@Param("projectId") long projectId, @Param("kind") DocumentKind kind);

    /** Serializes document commands and baseline changes without blocking project FK checks. */
    @Query(value = "SELECT id FROM project WHERE id = :projectId FOR NO KEY UPDATE", nativeQuery = true)
    Long lockProject(@Param("projectId") long projectId);
}
