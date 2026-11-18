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
import com.beez.backend.document.storage.DocumentStorage;
import com.beez.backend.document.validation.DocumentInputValidator;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
        when(access.requireCurrentHumanMember(101)).thenReturn(1L);
        when(storage.store(any(), eq("md"))).thenReturn("opaque-key.md");
        service = new DocumentRegistrationService(new DocumentInputValidator(1024, 1024), storage,
                access, documents, versions, transactions);
    }

    DocumentRegistrationService.Input input() {
        return new DocumentRegistrationService.Input(101, DocumentKind.REQUIREMENTS, "Requirements",
                null, "input.md", new byte[] {65});
    }

    @Test
    void deniesUnauthorizedBeforeAnyFileOrDocumentWrite() {
        when(access.requireCurrentHumanMember(101)).thenThrow(new ApiException(HttpStatus.FORBIDDEN, "forbidden", "팀장 권한이 필요합니다."));
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
    void putRunsOutsideTransactionAndFinalChecksRunAfterPut() throws Exception {
        when(storage.store(any(), eq("md"))).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            verify(documents, never()).lockProject(anyLong());
            // Another request registered the kind after the preflight.
            when(documents.existsByProjectIdAndKind(101, DocumentKind.REQUIREMENTS)).thenReturn(true);
            return "opaque-key.md";
        });
        assertThatThrownBy(() -> service.register(input())).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo("document_kind_exists"));
        verify(storage).delete("opaque-key.md");
        verify(versions, never()).saveAndFlush(any());
    }

    @Test
    void rollsBackAndCleansFileAfterDatabaseFailure() throws Exception {
        when(documents.saveAndFlush(any())).thenThrow(new IllegalStateException("DB 처리에 실패했습니다."));
        assertThatThrownBy(() -> service.register(input())).hasMessage("DB 처리에 실패했습니다.");
        verify(storage).delete("opaque-key.md");
        assertThat(transactions.rolledBack).isTrue();
    }

    @Test
    void fileStorageFailureDoesNotCreateDatabaseRecords() throws Exception {
        when(storage.store(any(), eq("md"))).thenThrow(new IOException("저장소를 사용할 수 없습니다."));
        assertThatThrownBy(() -> service.register(input())).isInstanceOf(java.io.UncheckedIOException.class);
        verify(documents, never()).saveAndFlush(any());
        verifyNoInteractions(versions);
    }

    @Test
    void preservesPrimaryDatabaseFailureWhenCleanupAlsoFails() throws Exception {
        when(documents.saveAndFlush(any())).thenThrow(new IllegalStateException("DB 처리에 실패했습니다."));
        doThrow(new IOException("보상 삭제에 실패했습니다.")).when(storage).delete(any());
        assertThatThrownBy(() -> service.register(input())).hasMessage("DB 처리에 실패했습니다.");
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
        // The preflight commits first; fail only the publication commit after PUT.
        transactions.failOnCommit = 2;
        assertThatThrownBy(() -> service.register(input())).hasMessage("커밋 결과를 확인할 수 없습니다.");
        verify(storage).store(any(), eq("md"));
        verify(storage, never()).delete(any());
    }

    static class TestTransactions extends AbstractPlatformTransactionManager {
        boolean rolledBack;
        int commits;
        int failOnCommit = Integer.MAX_VALUE;
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object tx, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) {
            if (++commits == failOnCommit) throw new org.springframework.transaction.TransactionSystemException("커밋 결과를 확인할 수 없습니다.");
        }
        @Override protected void doRollback(DefaultTransactionStatus status) { rolledBack = true; }
    }
}
