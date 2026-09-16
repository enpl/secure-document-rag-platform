package com.sdv.rag.application;

import com.sdv.common.model.UserContext;
import com.sdv.rag.application.port.out.EphemeralEvidenceStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Process-local ownership fence for volatile M12 evidence.  A conversation id is
 * minted here; a caller supplied string is never treated as an active session.
 * This intentionally keeps no transcript or durable conversation state.
 */
@Service
public class EvidenceConversationLifecycle {

    private static final Duration CLOSED_RETENTION = Duration.ofMinutes(5);

    private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();
    private final EphemeralEvidenceStore evidenceStore;
    private final Clock clock;

    @Autowired
    public EvidenceConversationLifecycle(EphemeralEvidenceStore evidenceStore) {
        this(evidenceStore, Clock.systemUTC());
    }

    EvidenceConversationLifecycle(EphemeralEvidenceStore evidenceStore, Clock clock) {
        this.evidenceStore = Objects.requireNonNull(evidenceStore, "evidenceStore must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public String open(UserContext requester) {
        Objects.requireNonNull(requester, "requester must not be null");
        pruneClosed();
        String id = UUID.randomUUID().toString();
        states.put(id, new State(requester.subject(), clock.instant()));
        return id;
    }

    public Lease requireActive(UserContext requester, String conversationId) {
        Objects.requireNonNull(requester, "requester must not be null");
        State state = states.get(conversationId);
        if (state == null || !state.ownerSubject.equals(requester.subject()) || !state.active.get()) {
            throw new LiveRetrievalException(LiveRetrievalException.Reason.NOT_AUTHORIZED);
        }
        return new Lease(conversationId, requester.subject(), state);
    }

    public boolean isActive(Lease lease) {
        return lease != null && lease.state.active.get() && states.get(lease.conversationId) == lease.state;
    }

    public void close(UserContext requester, String conversationId) {
        terminate(requester, conversationId);
    }

    public void cancel(UserContext requester, String conversationId) {
        terminate(requester, conversationId);
    }

    public void terminalError(UserContext requester, String conversationId) {
        terminate(requester, conversationId);
    }

    private void terminate(UserContext requester, String conversationId) {
        Lease lease = requireActive(requester, conversationId);
        if (lease.state.active.compareAndSet(true, false)) {
            lease.state.closedAt = clock.instant();
            evidenceStore.evictByConversation(requester.subject(), conversationId);
        }
        pruneClosed();
    }

    private void pruneClosed() {
        Instant cutoff = clock.instant().minus(CLOSED_RETENTION);
        states.entrySet().removeIf(entry -> !entry.getValue().active.get()
                && entry.getValue().closedAt != null && entry.getValue().closedAt.isBefore(cutoff));
    }

    public static final class Lease {
        private final String conversationId;
        private final String ownerSubject;
        private final State state;

        private Lease(String conversationId, String ownerSubject, State state) {
            this.conversationId = conversationId;
            this.ownerSubject = ownerSubject;
            this.state = state;
        }

        public String conversationId() { return conversationId; }
        public String ownerSubject() { return ownerSubject; }
    }

    private static final class State {
        private final String ownerSubject;
        private final Instant openedAt;
        private final AtomicBoolean active = new AtomicBoolean(true);
        private volatile Instant closedAt;

        private State(String ownerSubject, Instant openedAt) {
            this.ownerSubject = ownerSubject;
            this.openedAt = openedAt;
        }
    }
}
