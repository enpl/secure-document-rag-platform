package com.sdv.ai.application;

import java.util.concurrent.TimeUnit;

/** One monotonic budget shared by all phases of a single ask operation. */
public final class AskDeadline {
    private final long startedNanos = System.nanoTime();
    private final long timeoutMillis;

    private AskDeadline(long timeoutMillis) {
        this.timeoutMillis = Math.max(1, timeoutMillis);
    }

    public static AskDeadline startingNow(long timeoutMillis) {
        return new AskDeadline(timeoutMillis);
    }

    public long remainingMillis() {
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
        return Math.max(0, timeoutMillis - elapsed);
    }

    public long phaseBudget(long phaseCapMillis) {
        return Math.min(Math.max(1, phaseCapMillis), remainingMillis());
    }

    public boolean expired() {
        return remainingMillis() <= 0;
    }
}
