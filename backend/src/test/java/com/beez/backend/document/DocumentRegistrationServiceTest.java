package com.beez.backend.document;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.beez.backend.common.api.ApiException;
import com.beez.backend.document.entity.Document;
import com.beez.backend.document.entity.DocumentVersion;
import com.beez.backend.document.port.ProjectDocumentAccess;
import com.beez.backend.document.repository.DocumentRepository;
import com.beez.backend.document.repository.DocumentVersionRepository;
import com.beez.backend.document.service.DocumentRegistrationService;
import com.beez.backend.document.service.DocumentRegistrationService.KindPolicy;
import com.beez.backend.document.storage.DocumentStorage;
import com.beez.backend.document.validation.DocumentInputValidator;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

class DocumentRegistrationServiceTest {
    final DocumentStorage storage = mock(DocumentStorage.class);
    final DocumentRepository documents = mock(DocumentRepository.class);
    final DocumentVersionRepository versions = mock(DocumentVersionRepository.class);
    final ProjectDocumentAccess access = mock(ProjectDocumentAccess.class);
    final TestTransactions transactions = new TestTransactions();
    DocumentRegistrationService service;

    @BeforeEach
    void setUp() throws Exception {
        when(documents.lockProject(101)).thenReturn(101L);
        when(access.requireCurrentLeader(101)).thenReturn(1L);
        when(storage.store(any(), eq("md"))).thenReturn("opaque-key.md");
        service = new DocumentRegistrationService(new DocumentInputValidator(1024, 1024), storage,
                access, documents, versions, transactions, KindPolicy.SINGLE_PER_KIND);
    }

    DocumentRegistrationService.Input input() {
        return new DocumentRegistrationService.Input(101, DocumentKind.REQUIREMENTS, "Requirements",
                null, "input.md", new byte[] {65});
    }

    @Test
    void deniesUnauthorizedBeforeAnyFileOrDocumentWrite() {
        when(access.requireCurrentLeader(101)).thenThrow(new ApiException(HttpStatus.FORBIDDEN, "forbidden", "Leader required"));
        assertThatThrownBy(() -> service.register(input())).isInstanceOf(ApiException.class);
        verifyNoInteractions(storage, versions);
        verify(documents, never()).saveAndFlush(any());
    }

    @Test
    void rejectsExistingKindBeforeFileWrite() {
        when(documents.existsByProjectIdAndKind(101, DocumentKind.REQUIREMENTS)).thenReturn(true);
        assertThatThrownBy(() -> service.register(input())).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo("document_kind_exists"));
        verifyNoInteractions(storage, versions);
    }

    @Test
    void rollsBackAndCleansFileAfterDatabaseFailure() throws Exception {
        when(documents.saveAndFlush(any())).thenThrow(new IllegalStateException("DB failure"));
        assertThatThrownBy(() -> service.register(input())).hasMessage("DB failure");
        verify(storage).delete("opaque-key.md");
        assertThat(transactions.rolledBack).isTrue();
    }

    @Test
    void fileStorageFailureDoesNotCreateDatabaseRecords() throws Exception {
        when(storage.store(any(), eq("md"))).thenThrow(new IOException("disk unavailable"));
        assertThatThrownBy(() -> service.register(input())).isInstanceOf(java.io.UncheckedIOException.class);
        verify(documents, never()).saveAndFlush(any());
        verifyNoInteractions(versions);
    }

    @Test
    void preservesPrimaryDatabaseFailureWhenCleanupAlsoFails() throws Exception {
        when(documents.saveAndFlush(any())).thenThrow(new IllegalStateException("DB failure"));
        doThrow(new IOException("cleanup failed")).when(storage).delete(any());
        assertThatThrownBy(() -> service.register(input())).hasMessage("DB failure");
        assertThat(transactions.rolledBack).isTrue();
    }

    @Test
    void retainsFileWhenCommitOutcomeIsUnknown() throws Exception {
        var document = mock(Document.class);
        when(document.getId()).thenReturn(201L);
        var version = mock(DocumentVersion.class);
        when(version.getId()).thenReturn(211L);
        when(version.getVersionNo()).thenReturn(1);
        when(documents.saveAndFlush(any())).thenReturn(document);
        when(versions.saveAndFlush(any())).thenReturn(version);
        transactions.failCommit = true;
        assertThatThrownBy(() -> service.register(input())).hasMessage("Commit outcome unknown");
        verify(storage, never()).delete(any());
    }

    static class TestTransactions extends AbstractPlatformTransactionManager {
        boolean rolledBack;
        boolean failCommit;
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object tx, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) {
            if (failCommit) throw new org.springframework.transaction.TransactionSystemException("Commit outcome unknown");
        }
        @Override protected void doRollback(DefaultTransactionStatus status) { rolledBack = true; }
    }
}
