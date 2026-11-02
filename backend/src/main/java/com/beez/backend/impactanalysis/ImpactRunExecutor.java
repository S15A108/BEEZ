package com.beez.backend.impactanalysis;

import java.util.concurrent.*;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

/** Bounded, non-durable dispatch. Durable RUNNING leases recover process interruption on read/retry. */
@Component
public class ImpactRunExecutor implements DisposableBean {
    private static final int THREADS = 2, QUEUE = 8;
    /** Upper bound of AI-call durations a queued run can wait behind: the queue drains THREADS at a time, plus one in flight. */
    public static final int WAIT_ROUNDS = QUEUE / THREADS + 1;
    private final ThreadPoolExecutor pool = new ThreadPoolExecutor(THREADS, THREADS, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(QUEUE), runnable -> {
                var thread = new Thread(runnable, "impact-analysis");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    public boolean submit(Runnable work) {
        try { pool.execute(work); return true; }
        catch (RejectedExecutionException full) { return false; }
    }

    @Override public void destroy() { pool.shutdownNow(); }
}
