package com.beez.backend.document;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.beez.backend.document.service.DocumentOriginalWriter;
import com.beez.backend.document.storage.DocumentStorage;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class DocumentOriginalWriterTest {
    final DocumentStorage storage = mock(DocumentStorage.class);
    final DocumentRegistrationServiceTest.TestTransactions transactions = new DocumentRegistrationServiceTest.TestTransactions();
    final DocumentOriginalWriter writer = new DocumentOriginalWriter(storage, transactions);

    @Test void deletesUnreferencedPutAfterConcurrentIdempotentReplay() throws IOException {
        when(storage.store(any(), eq("md"))).thenReturn("unused.md");
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return null;
        }).when(storage).delete("unused.md");
        String response = writer.write(new byte[] {65}, "md", original -> "previous-response");
        assertThat(response).isEqualTo("previous-response");
        verify(storage).delete("unused.md");
    }

    @Test void rollbackCleanupRunsAfterDatabaseResourcesAreReleased() throws IOException {
        when(storage.store(any(), eq("md"))).thenReturn("unused.md");
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return null;
        }).when(storage).delete("unused.md");
        assertThatThrownBy(() -> writer.write(new byte[] {65}, "md", original -> {
            original.reference();
            throw new IllegalStateException("DB 처리에 실패했습니다.");
        })).hasMessage("DB 처리에 실패했습니다.");
        verify(storage).delete("unused.md");
    }

    @Test void cleansOriginalWhenDatabaseTransactionCannotBegin() throws IOException {
        when(storage.store(any(), eq("md"))).thenReturn("unused.md");
        var failing = new AbstractPlatformTransactionManager() {
            @Override protected Object doGetTransaction() { return new Object(); }
            @Override protected void doBegin(Object tx, TransactionDefinition definition) { throw new IllegalStateException("DB에 연결할 수 없습니다."); }
            @Override protected void doCommit(DefaultTransactionStatus status) { }
            @Override protected void doRollback(DefaultTransactionStatus status) { }
        };
        assertThatThrownBy(() -> new DocumentOriginalWriter(storage, failing).write(new byte[] {65}, "md", original -> original.reference()))
                .hasMessage("DB에 연결할 수 없습니다.");
        verify(storage).delete("unused.md");
    }

    @Test void rejectsOuterTransactionBeforePut() {
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            assertThatThrownBy(() -> writer.write(new byte[] {65}, "md", original -> original.reference()))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("DB 트랜잭션 밖에서");
        });
        verifyNoInteractions(storage);
    }
}
