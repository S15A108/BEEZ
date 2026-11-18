package com.beez.backend.task.service;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs model calls on a dedicated thread so the scheduler can continue polling other workers. */
@Component
public class TaskBreakdownWorker {
    private static final Logger log = LoggerFactory.getLogger(TaskBreakdownWorker.class);
    private final TaskBreakdownService breakdown;
    private final boolean enabled;
    private final AtomicBoolean busy = new AtomicBoolean();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon().name("task-breakdown-worker").factory());

    public TaskBreakdownWorker(TaskBreakdownService breakdown,
                               @Value("${beez.task-breakdown.worker-enabled:true}") boolean enabled) {
        this.breakdown = breakdown;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${beez.task-breakdown.poll-millis:2000}")
    public void poll() {
        if (!enabled || !busy.compareAndSet(false, true)) return;
        try {
            executor.execute(this::drain);
        } catch (RejectedExecutionException stopped) {
            busy.set(false);
        }
    }

    private void drain() {
        try {
            while (!Thread.currentThread().isInterrupted() && breakdown.runNext()) {
                // Claim the next job only after this one has finished.
            }
        } catch (RuntimeException failure) {
            log.error("Task breakdown polling failed ({})", failure.getClass().getSimpleName());
        } finally {
            busy.set(false);
        }
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }
}
