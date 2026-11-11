package com.beez.backend.document.service;

import com.beez.backend.document.config.DocumentProperties;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 접수된 문서 비비 검토(DOC-A16)를 처리한다(S15P31A108-391).
 *
 * <p>검토 한 건이 AI 응답을 30초~2분 기다리므로, 한 스레드에서 차례로 돌리면 필수 문서 4종을 연달아 올릴 때 마지막 검토가 몇 분씩
 * 줄을 선다(동기 호출 때는 요청 스레드마다 동시에 돌았다). 그래서 전용 스레드 {@code concurrency}개에서 동시에 처리한다.
 * 스케줄 메서드는 빈 자리만큼 일을 넘기고 바로 돌아온다(다른 @Scheduled 워커를 막지 않는다). 같은 작업을 둘이 잡지 않는 것은
 * DB 의 {@code FOR UPDATE SKIP LOCKED} 와 울타리 토큰이 맡는다.
 *
 * <p>종료(배포 재시작) 때는 처리 중인 스레드를 interrupt 한다. 끊긴 검토는 실패로 남기지 않고, lease 가 지나면 새 서버의 워커가
 * 다시 잡는다({@link DocumentReviewService#runNextReview()}). 켜기 설정은 문서 워커와 같다(시험은 끄고 {@link #runOnce()} 를 직접 부른다).
 */
@Component
public class DocumentReviewWorker {
    private static final Logger log = LoggerFactory.getLogger(DocumentReviewWorker.class);
    private final DocumentReviewService reviews;
    private final DocumentProperties properties;
    private final ExecutorService pool;
    private final Semaphore slots;

    public DocumentReviewWorker(DocumentReviewService reviews, DocumentProperties properties,
                                @Value("${beez.document-review.worker-concurrency:4}") int concurrency) {
        if (concurrency < 1) throw new IllegalArgumentException("beez.document-review.worker-concurrency 는 1 이상이어야 합니다.");
        this.reviews = reviews; this.properties = properties;
        this.slots = new Semaphore(concurrency);
        this.pool = Executors.newFixedThreadPool(concurrency, Thread.ofPlatform().name("document-review-", 0).daemon(true).factory());
    }

    @Scheduled(fixedDelayString = "${beez.document.poll-millis:2000}")
    public void poll() {
        if (!properties.workerEnabled()) return;
        while (slots.tryAcquire()) {
            try {
                pool.execute(this::drain);
            } catch (RuntimeException rejected) {
                // 종료 중이라 더 받지 않는다.
                slots.release();
                return;
            }
        }
    }

    /** 처리할 검토가 없을 때까지 하나씩 처리하고 자리를 돌려준다. */
    private void drain() {
        try {
            while (!Thread.currentThread().isInterrupted() && reviews.runNextReview()) { /* 다음 검토 */ }
        } catch (RuntimeException error) {
            log.error("문서 비비 검토 처리 중 오류가 발생했습니다. 예외 종류={}", error.getClass().getSimpleName());
        } finally {
            slots.release();
        }
    }

    /** 처리할 검토 하나를 끝까지 처리한다. 없으면 false. */
    public boolean runOnce() { return reviews.runNextReview(); }

    @PreDestroy
    void stop() { pool.shutdownNow(); }
}
