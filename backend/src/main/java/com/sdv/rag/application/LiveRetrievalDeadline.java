package com.sdv.rag.application;

import java.util.concurrent.TimeUnit;

/** Monotonic, finite deadline shared by one live-retrieval operation and its retry. */
final class LiveRetrievalDeadline {
    private final long startedNanos;
    private final long timeoutMs;

    private LiveRetrievalDeadline(long timeoutMs) {
        this.startedNanos = System.nanoTime();
        this.timeoutMs = Math.max(1L, timeoutMs);
    }

    static LiveRetrievalDeadline startingNow(long timeoutMs) {
        return new LiveRetrievalDeadline(timeoutMs);
    }

    boolean expired() {
        return remainingMillis() <= 0;
    }

    long remainingMillis() {
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
        return Math.max(0L, timeoutMs - elapsed);
    }

    long fileBudgetMillis(long configuredFileBudgetMs) {
        return Math.min(Math.max(1L, configuredFileBudgetMs), remainingMillis());
    }
}
