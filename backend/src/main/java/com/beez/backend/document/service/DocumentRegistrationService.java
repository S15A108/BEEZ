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
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Registration core; one logical document per project/kind (DOC-A01). */
public final class DocumentRegistrationService {
    public record Input(long projectId, DocumentKind kind, String title, String domain,
                        String filename, byte[] bytes) { }
    public record StoredDraft(long documentId, long documentVersionId, int versionNo, String textHash) { }

    private final DocumentInputValidator validator;
    private final DocumentOriginalWriter originals;
    private final ProjectDocumentAccess access;
    private final DocumentRepository documents;
    private final DocumentVersionRepository versions;
    private final TransactionTemplate transaction;

    public DocumentRegistrationService(DocumentInputValidator validator, DocumentStorage storage,
            ProjectDocumentAccess access, DocumentRepository documents, DocumentVersionRepository versions,
            PlatformTransactionManager manager) {
        this.validator = Objects.requireNonNull(validator);
        this.originals = new DocumentOriginalWriter(storage, manager);
        this.access = Objects.requireNonNull(access);
        this.documents = Objects.requireNonNull(documents);
        this.versions = Objects.requireNonNull(versions);
        transaction = new TransactionTemplate(manager);
    }

    public StoredDraft register(Input input) {
        DocumentOriginalWriter.requireNoTransaction();
        validateInput(input);
        byte[] bytes = input.bytes().clone();
        NormalizedDocument source = validator.validate(input.filename(), bytes);
        transaction.executeWithoutResult(tx -> checkRegistration(input));
        return originals.write(bytes, source.extension(), original -> persist(input, source, original));
    }

    static void validateInput(Input input) {
        if (input == null || input.projectId() <= 0 || input.kind() == null
                || input.title() == null || input.title().isBlank() || input.bytes() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", "프로젝트, 문서 종류, 제목과 파일은 필수입니다.");
        }
    }

    long checkRegistration(Input input) {
        long actor = access.requireCurrentHumanMember(input.projectId());
        if (actor <= 0) throw new IllegalStateException("권한 검사 어댑터는 인증된 계정 ID를 반환해야 합니다.");
        if (documents.existsByProjectIdAndKind(input.projectId(), input.kind())) {
            throw new ApiException(HttpStatus.CONFLICT, "document_kind_exists", "이미 등록된 문서 종류입니다.");
        }
        return actor;
    }

    /** Shares the publication transaction with job and idempotency response storage. */
    StoredDraft persist(Input input, NormalizedDocument source, DocumentOriginalWriter.Original original) {
        if (documents.lockProject(input.projectId()) == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "not_found", "프로젝트를 찾을 수 없습니다.");
        }
        long actor = checkRegistration(input);
        Document document = documents.saveAndFlush(new Document(input.projectId(), input.kind(), input.title(), input.domain(), actor));
        DocumentVersion version = versions.saveAndFlush(DocumentVersion.initialDraft(document.getId(),
                original.reference(), source.contentHash(), source.text(), actor));
        return new StoredDraft(document.getId(), version.getId(), version.getVersionNo(), source.textHash());
    }
}
