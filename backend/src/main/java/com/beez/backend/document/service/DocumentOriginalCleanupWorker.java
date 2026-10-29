package com.beez.backend.document.service;

import com.beez.backend.document.config.DocumentProperties;
import com.beez.backend.document.repository.DocumentReviewRepository;
import com.beez.backend.document.storage.DocumentStorage;
import java.io.IOException;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Committed, superseded working files only. Submission/version originals are retained. */
@Component
public class DocumentOriginalCleanupWorker {
    private final DocumentReviewRepository repository;
    private final DocumentStorage storage;
    private final DocumentProperties properties;
    public DocumentOriginalCleanupWorker(DocumentReviewRepository repository, DocumentStorage storage, DocumentProperties properties) {
        this.repository = repository; this.storage = storage; this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${beez.document.poll-millis:2000}")
    public void poll() { if (properties.workerEnabled()) runOnce(); }

    public void runOnce() {
        for (String key : repository.cleanupKeys()) {
            try {
                // Keys are server-generated and cannot be reattached by API callers.
                if (!repository.referencedOriginal(key)) storage.delete(key);
                repository.cleanedOriginal(key);
            } catch (IOException | RuntimeException e) {
                LoggerFactory.getLogger(getClass()).warn("작성 원본 정리를 재시도합니다. cause={}", e.getClass().getSimpleName());
            }
        }
    }
}
