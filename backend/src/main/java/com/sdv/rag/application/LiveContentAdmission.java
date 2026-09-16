package com.sdv.rag.application;

import com.sdv.rag.application.port.out.EphemeralEvidenceCapacityExceededException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Bounded reservation for transient fetched bytes and parsing work; no I/O occurs under its lock. */
@Component
final class LiveContentAdmission {
    private final int maxConcurrent;
    private final long maxBytes;
    private int active;
    private long reservedBytes;

    LiveContentAdmission(@Value("${sdv.rag.live-retrieval.max-concurrent-content-operations:2}") int maxConcurrent,
            @Value("${sdv.rag.live-retrieval.max-in-flight-content-bytes:52428800}") long maxBytes) {
        this.maxConcurrent = maxConcurrent > 0 ? maxConcurrent : 2;
        this.maxBytes = maxBytes > 0 ? maxBytes : 50L * 1024 * 1024;
    }

    synchronized Reservation acquire(long worstCaseBytes, LiveRetrievalDeadline deadline) {
        if (deadline.expired()) {
            throw new LiveRetrievalException(LiveRetrievalException.Reason.REQUEST_TIMEOUT);
        }
        if (worstCaseBytes <= 0 || worstCaseBytes > maxBytes || active >= maxConcurrent
                || reservedBytes > maxBytes - worstCaseBytes) {
            throw new EphemeralEvidenceCapacityExceededException();
        }
        active++;
        reservedBytes += worstCaseBytes;
        return new Reservation(this, worstCaseBytes);
    }

    private synchronized void release(long bytes) {
        active--;
        reservedBytes -= bytes;
    }

    final class Reservation implements AutoCloseable {
        private final LiveContentAdmission owner;
        private final long bytes;
        private boolean released;

        private Reservation(LiveContentAdmission owner, long bytes) {
            this.owner = owner;
            this.bytes = bytes;
        }

        @Override
        public synchronized void close() {
            if (!released) {
                released = true;
                owner.release(bytes);
            }
        }
    }
}
