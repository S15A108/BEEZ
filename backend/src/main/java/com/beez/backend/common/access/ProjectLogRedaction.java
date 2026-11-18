package com.beez.backend.common.access;

/** Limits project-ID redaction to a CDC analysis request or worker on the current thread. */
public final class ProjectLogRedaction implements AutoCloseable {
    private static final ThreadLocal<Boolean> ACTIVE = new ThreadLocal<>();

    private final Boolean previous;
    private boolean closed;

    private ProjectLogRedaction() {
        previous = ACTIVE.get();
        ACTIVE.set(true);
    }

    public static ProjectLogRedaction open() {
        return new ProjectLogRedaction();
    }

    static boolean isActive() {
        return Boolean.TRUE.equals(ACTIVE.get());
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (previous == null) ACTIVE.remove();
        else ACTIVE.set(previous);
    }
}
