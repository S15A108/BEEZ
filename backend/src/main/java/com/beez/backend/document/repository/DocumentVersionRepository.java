package com.beez.backend.document.repository;

import com.beez.backend.document.entity.DocumentVersion;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentVersionRepository extends JpaRepository<DocumentVersion, Long> {
}
