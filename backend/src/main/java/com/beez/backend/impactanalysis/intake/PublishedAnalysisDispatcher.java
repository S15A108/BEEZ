package com.beez.backend.impactanalysis.intake;

import com.beez.backend.cdc.intake.AnalysisIntakeCheckPort;
import com.beez.backend.impactanalysis.PublishedAnalysisProcessor;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Durable DB recovery backs the optional Kafka wake-up hint and bounded local executor. */
@Component
@ConditionalOnProperty(prefix = "beez.cdc.intake", name = "enabled", havingValue = "true")
public class PublishedAnalysisDispatcher implements AnalysisIntakeCheckPort, DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(PublishedAnalysisDispatcher.class);
    private static final int PAGE_SIZE = 32;
    private final PublishedAnalysisRepository repository;
    private final PublishedAnalysisProcessor processor;
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean scanning = new AtomicBoolean();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), runnable -> {
                var thread = new Thread(runnable, "published-impact");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private String cursor;

    public PublishedAnalysisDispatcher(PublishedAnalysisRepository repository, PublishedAnalysisProcessor processor) {
        this.repository = repository;
        this.processor = processor;
    }

    @Override
    public void ensureSafeToAcknowledge(String reference) {
        // Database lookup must succeed before Kafka can acknowledge. A full queue is safe:
        // the enabled recurring recovery scan will revisit the durable PENDING/expired row.
        repository.ensureSafeToAcknowledge(reference);
        dispatch(Long.parseLong(reference));
    }

    private boolean dispatch(long id) {
        if (!inFlight.add(id)) return true;
        try {
            executor.execute(() -> {
                try { processor.process(id); }
                catch (RuntimeException failure) { log.warn("Publication analysis dispatch failed; recovery will retry"); }
                finally { inFlight.remove(id); }
            });
            return true;
        } catch (RejectedExecutionException full) {
            inFlight.remove(id);
            return false;
        }
    }

    @Scheduled(fixedDelayString = "${beez.cdc.intake.recovery-poll-millis:2000}")
    public void recover() {
        if (!scanning.compareAndSet(false, true)) return;
        try {
            var page = repository.findCandidates(repository.databaseTime(), PAGE_SIZE, cursor);
            String lastProcessed = cursor;
            for (var candidate : page.candidates()) {
                if (!dispatch(Long.parseLong(candidate.intakeReference()))) {
                    // Resume before the rejected row, rather than skipping work under load.
                    cursor = lastProcessed;
                    return;
                }
                lastProcessed = candidate.intakeReference();
            }
            cursor = page.nextCursor();
        } catch (RuntimeException failure) {
            // A later scan retries from the last safe cursor; no data or exception message is logged.
            log.warn("Publication analysis recovery scan failed; it will be retried");
        } finally {
            scanning.set(false);
        }
    }

    @Override public void destroy() { executor.shutdownNow(); }
}
