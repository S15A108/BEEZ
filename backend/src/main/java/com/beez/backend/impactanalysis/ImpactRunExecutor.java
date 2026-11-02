package com.beez.backend.impactanalysis;

import java.util.concurrent.*;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

/** Bounded, non-durable dispatch. Durable RUNNING leases recover process interruption on read/retry. */
@Component
public class ImpactRunExecutor implements DisposableBean {
    private final ThreadPoolExecutor pool = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), runnable -> {
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
