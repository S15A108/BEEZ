package com.beez.backend.document.service;

import com.beez.backend.common.api.ApiException;
import com.beez.backend.document.DocumentKind;
import com.beez.backend.document.entity.Document;
import com.beez.backend.document.entity.DocumentVersion;
import com.beez.backend.document.port.ProjectDocumentAccess;
import com.beez.backend.document.repository.DocumentRepository;
import com.beez.backend.document.repository.DocumentVersionRepository;
import com.beez.backend.document.storage.DocumentStorage;
import com.beez.backend.document.validation.DocumentInputValidator;
import com.beez.backend.document.validation.NormalizedDocument;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Registration core; the application service supplies its authenticated project access adapter. */
public final class DocumentRegistrationService {
    public enum KindPolicy { SINGLE_PER_KIND, ALLOW_MULTIPLE }

    public record Input(long projectId, DocumentKind kind, String title, String domain,
                        String filename, byte[] bytes) { }
    public record StoredDraft(long documentId, long documentVersionId, int versionNo, String textHash) { }

    private static final Logger log = LoggerFactory.getLogger(DocumentRegistrationService.class);
    private final DocumentInputValidator validator;
    private final DocumentStorage storage;
    private final ProjectDocumentAccess access;
    private final DocumentRepository documents;
    private final DocumentVersionRepository versions;
    private final TransactionTemplate transaction;
    private final KindPolicy policy;

    public DocumentRegistrationService(DocumentInputValidator validator, DocumentStorage storage,
            ProjectDocumentAccess access, DocumentRepository documents, DocumentVersionRepository versions,
            PlatformTransactionManager manager, KindPolicy policy) {
        this.validator = Objects.requireNonNull(validator);
        this.storage = Objects.requireNonNull(storage);
        this.access = Objects.requireNonNull(access);
        this.documents = Objects.requireNonNull(documents);
        this.versions = Objects.requireNonNull(versions);
        this.policy = Objects.requireNonNull(policy);
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public StoredDraft register(Input input) {
        if (input == null || input.projectId() <= 0 || input.kind() == null
                || input.title() == null || input.title().isBlank() || input.bytes() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", "Project, kind, title and file required");
        }
        // Defensive copy binds validation, hashes and storage to the same bytes.
        byte[] bytes = input.bytes().clone();
        return Objects.requireNonNull(transaction.execute(tx -> {
            if (documents.lockProject(input.projectId()) == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "not_found", "Project unavailable");
            }
            long actor = access.requireCurrentLeader(input.projectId());
            if (actor <= 0) {
                throw new IllegalStateException("Access adapter must return an authenticated account");
            }
            if (policy == KindPolicy.SINGLE_PER_KIND
                    && documents.existsByProjectIdAndKind(input.projectId(), input.kind())) {
                throw new ApiException(HttpStatus.CONFLICT, "document_kind_exists", "Document kind already registered");
            }
            NormalizedDocument source = validator.validate(input.filename(), bytes);
            String key;
            try {
                key = storage.store(bytes, source.extension());
            } catch (IOException e) {
                throw new UncheckedIOException("Original file storage failed", e);
            }
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_ROLLED_BACK) {
                        try {
                            storage.delete(key);
                        } catch (IOException | RuntimeException cleanupError) {
                            // No raw path/content in logs; correlation ID is attached by RequestIdFilter.
                            log.error("Document rollback cleanup failed; orphan_key={}, cause={}",
                                    key, cleanupError.getClass().getSimpleName());
                        }
                    } else if (status == STATUS_UNKNOWN) {
                        // Commit outcome uncertain: preserve original until DB references are reconciled.
                        log.error("Document commit outcome unknown; reconcile original_key={}", key);
                    }
                }
            });
            Document document = documents.saveAndFlush(new Document(input.projectId(), input.kind(),
                    input.title(), input.domain(), actor));
            DocumentVersion version = versions.saveAndFlush(DocumentVersion.initialDraft(document.getId(),
                    key, source.contentHash(), source.text(), actor));
            return new StoredDraft(document.getId(), version.getId(), version.getVersionNo(), source.textHash());
        }));
    }
}
