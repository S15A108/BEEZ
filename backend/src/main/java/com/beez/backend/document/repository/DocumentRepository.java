package com.beez.backend.document.repository;

import com.beez.backend.document.DocumentKind;
import com.beez.backend.document.entity.Document;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DocumentRepository extends JpaRepository<Document, Long> {
    boolean existsByProjectIdAndKind(long projectId, DocumentKind kind);

    /** Serializes document commands and baseline changes without blocking project FK checks. */
    @Query(value = "SELECT id FROM project WHERE id = :projectId FOR NO KEY UPDATE", nativeQuery = true)
    Long lockProject(@Param("projectId") long projectId);
}
