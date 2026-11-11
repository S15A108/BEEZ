package com.beez.backend.document.service;

import com.beez.backend.document.config.DocumentProperties;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 접수된 문서 비비 검토(DOC-A16)를 처리한다(S15P31A108-391). AI 응답을 오래(최대 2분) 기다리므로 문서 색인 워커와 스케줄 메서드를
 * 나눠 서로 막지 않는다. 켜기 설정은 문서 워커와 같다(시험은 끄고 {@link #runOnce()} 를 직접 부른다).
 */
@Component
public class DocumentReviewWorker {
    static final int BATCH = 5;
    private final DocumentReviewService reviews;
    private final DocumentProperties properties;

    public DocumentReviewWorker(DocumentReviewService reviews, DocumentProperties properties) {
        this.reviews = reviews; this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${beez.document.poll-millis:2000}")
    public void poll() {
        if (!properties.workerEnabled()) return;
        try {
            for (int i = 0; i < BATCH && reviews.runNextReview(); i++) { /* 한 번에 몇 건까지만 처리한다 */ }
        } catch (RuntimeException error) {
            LoggerFactory.getLogger(getClass()).error("문서 비비 검토 처리 중 오류가 발생했습니다. 예외 종류={}", error.getClass().getSimpleName());
        }
    }

    /** 처리할 검토 하나를 끝까지 처리한다. 없으면 false. */
    public boolean runOnce() { return reviews.runNextReview(); }
}
