package com.beez.backend.document.service;

import com.beez.backend.document.storage.DocumentStorage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** PUT without a DB transaction, followed by a short atomic DB publication. */
public final class DocumentOriginalWriter {
    private static final Logger log = LoggerFactory.getLogger(DocumentOriginalWriter.class);
    private final DocumentStorage storage;
    private final TransactionTemplate transaction;

    public DocumentOriginalWriter(DocumentStorage storage, PlatformTransactionManager manager) {
        this.storage = Objects.requireNonNull(storage);
        transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public static void requireNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("원본 업로드는 DB 트랜잭션 밖에서 시작해야 합니다.");
        }
    }

    public record Payload(byte[] bytes, String extension) { }

    public <T> T write(byte[] bytes, String extension, Function<Original, T> publish) {
        return writeAll(List.of(new Payload(bytes, extension)), originals -> publish.apply(originals.getFirst()));
    }

    /**
     * Stores every payload outside any DB transaction, then publishes them in one short transaction.
     * An original that the transaction did not reference, or that was rolled back, is deleted afterwards.
     */
    public <T> T writeAll(List<Payload> payloads, Function<List<Original>, T> publish) {
        requireNoTransaction();
        var originals = new ArrayList<Original>();
        try {
            for (Payload payload : payloads) originals.add(new Original(storage.store(payload.bytes(), payload.extension())));
        } catch (IOException error) {
            originals.forEach(original -> cleanup(original.key));
            throw new UncheckedIOException("문서 원본 저장에 실패했습니다.", error);
        } catch (RuntimeException error) {
            originals.forEach(original -> cleanup(original.key));
            throw error;
        }
        try {
            return transaction.execute(status -> {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCompletion(int outcome) {
                        originals.forEach(original -> original.outcome = outcome);
                    }
                });
                originals.forEach(original -> original.observingOutcome = true);
                return publish.apply(List.copyOf(originals));
            });
        } finally {
            // Release transaction resources before any compensating S3 call as well.
            originals.forEach(this::finish);
        }
    }

    private void finish(Original original) {
        if (!original.observingOutcome || original.outcome == TransactionSynchronization.STATUS_ROLLED_BACK
                || (original.outcome == TransactionSynchronization.STATUS_COMMITTED && !original.referenced)) {
            cleanup(original.key);
        } else if (original.outcome == TransactionSynchronization.STATUS_UNKNOWN) {
            log.error("문서 커밋 결과를 확인할 수 없습니다. 원본 참조를 대조해 주세요. original_key={}", original.key);
        }
    }

    private void cleanup(String key) {
        try { storage.delete(key); }
        catch (IOException | RuntimeException error) {
            log.error("문서 원본 보상 삭제에 실패했습니다. orphan_key={}, cause={}", key, error.getClass().getSimpleName());
        }
    }

    public static final class Original {
        private final String key;
        private boolean referenced;
        private boolean observingOutcome;
        private int outcome = TransactionSynchronization.STATUS_UNKNOWN;
        private Original(String key) { this.key = key; }
        /** Call only when this transaction creates a DB reference to the original. */
        public String reference() { referenced = true; return key; }
    }
}
