package com.sdv.source.application;

import com.sdv.common.model.UserContext;
import com.sdv.policy.application.EffectivePermissionService;
import com.sdv.source.application.port.DocumentSourceConnector;
import com.sdv.source.domain.ShareAction;
import com.sdv.source.domain.SourceAccessContext;
import com.sdv.source.domain.SourceContentOutcome;
import com.sdv.source.domain.SourceDownloadResult;
import com.sdv.source.domain.SourceMetadataVerificationResult;
import com.sdv.source.infrastructure.persistence.entity.DocumentShareEntity;
import com.sdv.source.infrastructure.persistence.entity.SourceConnectionEntity;
import com.sdv.source.infrastructure.persistence.entity.SourceDocumentEntity;
import com.sdv.source.infrastructure.persistence.repository.DocumentShareJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourceConnectionJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourceDocumentJpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Arrays;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * SHR-004 orchestration.  Provider I/O is deliberately outside database transactions; every
 * authorization snapshot is reloaded in a separate read-only transaction before byte release.
 */
@Service
public class SharedFileDownloadService {

    private final DocumentShareJpaRepository documentShareJpaRepository;
    private final SourceDocumentJpaRepository sourceDocumentJpaRepository;
    private final SourceConnectionJpaRepository sourceConnectionJpaRepository;
    private final EffectivePermissionService effectivePermissionService;
    private final SourceConnectorRegistry sourceConnectorRegistry;
    private final SharedFileDownloadProperties properties;
    private final TransactionTemplate freshRead;
    private final Semaphore capacity;

    public SharedFileDownloadService(DocumentShareJpaRepository documentShareJpaRepository,
            SourceDocumentJpaRepository sourceDocumentJpaRepository,
            SourceConnectionJpaRepository sourceConnectionJpaRepository,
            EffectivePermissionService effectivePermissionService, SourceConnectorRegistry sourceConnectorRegistry,
            SharedFileDownloadProperties properties, PlatformTransactionManager transactionManager) {
        this.documentShareJpaRepository = documentShareJpaRepository;
        this.sourceDocumentJpaRepository = sourceDocumentJpaRepository;
        this.sourceConnectionJpaRepository = sourceConnectionJpaRepository;
        this.effectivePermissionService = effectivePermissionService;
        this.sourceConnectorRegistry = sourceConnectorRegistry;
        this.properties = properties;
        this.capacity = new Semaphore(properties.maxConcurrentDownloads(), true);
        this.freshRead = new TransactionTemplate(transactionManager);
        this.freshRead.setReadOnly(true);
        this.freshRead.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public DownloadLease download(UserContext requester, Long shareId) {
        if (requester == null || requester.subject() == null || requester.subject().isBlank() || shareId == null) {
            throw new SharedFileDownloadException(SharedFileDownloadException.Reason.NOT_AUTHORIZED);
        }
        DownloadDeadline deadline = DownloadDeadline.start(properties.requestTimeoutMs());
        Attempt attempt = verifyBefore(requester, shareId, deadline);
        acquireCapacity(deadline);
        boolean leased = true;
        SourceDownloadResult result = null;
        try {
            result = attempt.connector().fetchDownload(attempt.snapshot().context(), attempt.liveVersion(),
                    properties.maxBytes(), deadline.remainingMillis());
            if (result.outcome() != SourceContentOutcome.VERIFIED) {
                throw outcomeFailure(result.outcome());
            }
            if (result.content().length > properties.maxBytes()) {
                discard(result.content());
                throw new SharedFileDownloadException(SharedFileDownloadException.Reason.FILE_TOO_LARGE);
            }
            // The release check intentionally happens in prepareForRelease(), immediately before
            // the controller commits a success response.  A check here would become stale while
            // an asynchronous response waits to start.
            DownloadLease lease = new DownloadLease(result.content(), result.filename(), result.mimeType(), attempt, capacity);
            leased = false;
            return lease;
        } catch (RuntimeException failure) {
            if (result != null) {
                discard(result.content());
            }
            throw failure;
        } finally {
            if (leased) {
                capacity.release();
            }
        }
    }

    /** Resolves an immutable server-side context and validates the live publisher file before bytes are fetched. */
    Attempt verifyBefore(UserContext requester, Long shareId, DownloadDeadline deadline) {
        Snapshot snapshot = readFreshSnapshot(requester, shareId);
        authorize(requester, snapshot.context());
        DocumentSourceConnector connector = sourceConnectorRegistry.getConnector(snapshot.sourceType())
                .orElseThrow(() -> new SharedFileDownloadException(SharedFileDownloadException.Reason.NOT_AVAILABLE));
        SourceMetadataVerificationResult live = connector.verifyDownload(snapshot.context(), deadline.remainingMillis());
        if (live.outcome() != com.sdv.source.domain.SourceMetadataVerificationOutcome.VERIFIED
                || !Boolean.TRUE.equals(live.downloadable())) {
            throw new SharedFileDownloadException(SharedFileDownloadException.Reason.NOT_AVAILABLE);
        }
        return new Attempt(snapshot, connector, live.sourceVersion(), deadline);
    }

    /**
     * The explicit byte-release boundary.  This is called by the servlet thread before it prepares
     * a 200/attachment response.  The provider request deliberately precedes a brand-new DB read:
     * the latter therefore observes revocation/block/epoch changes made while the provider request
     * was in flight.  The original snapshot must still match; a new generation or reconnect never
     * rehabilitates an old operation.
     */
    public void prepareForRelease(UserContext requester, DownloadLease lease) {
        if (requester == null || !lease.belongsTo(requester.subject())) {
            lease.close();
            throw new SharedFileDownloadException(SharedFileDownloadException.Reason.NOT_AUTHORIZED);
        }
        try {
            verifyAfter(requester, lease.attempt(), lease, lease.attempt().deadline());
            lease.authorizeRelease();
        } catch (RuntimeException failure) {
            lease.close();
            throw failure;
        }
    }

    /** Performs the final provider/version check, then reloads SDV state with a fresh persistence context. */
    void verifyAfter(UserContext requester, Attempt before, DownloadLease lease, DownloadDeadline deadline) {
        SourceMetadataVerificationResult live = before.connector().verifyDownload(before.snapshot().context(), deadline.remainingMillis());
        if (live.outcome() != com.sdv.source.domain.SourceMetadataVerificationOutcome.VERIFIED
                || !Boolean.TRUE.equals(live.downloadable())
                || !lease.verifiedSourceVersion().equals(live.sourceVersion())) {
            throw new SharedFileDownloadException(SharedFileDownloadException.Reason.DOCUMENT_CHANGED);
        }
        Snapshot current = readFreshSnapshot(requester, before.snapshot().context().shareId());
        if (!sameBinding(before.snapshot(), current)) {
            throw new SharedFileDownloadException(SharedFileDownloadException.Reason.NOT_AUTHORIZED);
        }
        authorize(requester, current.context());
    }

    private Snapshot readFreshSnapshot(UserContext requester, Long shareId) {
        long authorizationRevision = effectivePermissionService.currentSharedAuthorization(requester)
                .map(com.sdv.identity.domain.UserAuthorizationSnapshot::authorizationRevision)
                .orElseThrow(() -> new SharedFileDownloadException(SharedFileDownloadException.Reason.NOT_AUTHORIZED));
        Snapshot snapshot = freshRead.execute(status -> {
            DocumentShareEntity share = documentShareJpaRepository.findById(shareId).orElse(null);
            if (share == null) {
                return null;
            }
            SourceDocumentEntity document = sourceDocumentJpaRepository
                    .findByIdAndOwnerSubject(share.getDocumentId(), share.getPublisherSubject()).orElse(null);
            SourceConnectionEntity connection = sourceConnectionJpaRepository
                    .findByIdAndOwnerSubject(share.getSourceId(), share.getPublisherSubject()).orElse(null);
            if (document == null || connection == null || !share.getSourceId().equals(document.getSourceId())) {
                return null;
            }
            try {
                return new Snapshot(new SourceAccessContext(requester.subject(), share.getPublisherSubject(), share.getSourceId(),
                        share.getDocumentId(), share.getId(), ShareAction.DOWNLOAD, share.getGeneration(),
                        connection.getConnectionEpoch(), authorizationRevision), com.sdv.source.domain.SourceType.valueOf(connection.getType()),
                        document.getSourceDocumentId());
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        });
        if (snapshot == null) {
            throw new SharedFileDownloadException(SharedFileDownloadException.Reason.NOT_AUTHORIZED);
        }
        return snapshot;
    }

    private void authorize(UserContext requester, SourceAccessContext context) {
        if (effectivePermissionService.evaluateSharedAccess(requester, context, null).isDenied()) {
            throw new SharedFileDownloadException(SharedFileDownloadException.Reason.NOT_AUTHORIZED);
        }
    }

    private void acquireCapacity(DownloadDeadline deadline) {
        try {
            if (!capacity.tryAcquire(deadline.remainingMillis(), TimeUnit.MILLISECONDS)) {
                throw new SharedFileDownloadException(SharedFileDownloadException.Reason.CAPACITY_EXHAUSTED);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SharedFileDownloadException(SharedFileDownloadException.Reason.REQUEST_TIMEOUT);
        }
    }

    private static boolean sameBinding(Snapshot before, Snapshot after) {
        return before.context().equals(after.context())
                && before.sourceType() == after.sourceType()
                && before.providerFileId().equals(after.providerFileId());
    }

    private static SharedFileDownloadException outcomeFailure(SourceContentOutcome outcome) {
        return switch (outcome) {
            case DOCUMENT_CHANGED, VERSION_MISMATCH ->
                    new SharedFileDownloadException(SharedFileDownloadException.Reason.DOCUMENT_CHANGED);
            case EXPORT_LIMIT_EXCEEDED ->
                    new SharedFileDownloadException(SharedFileDownloadException.Reason.EXPORT_LIMIT_EXCEEDED);
            default -> new SharedFileDownloadException(SharedFileDownloadException.Reason.NOT_AVAILABLE);
        };
    }

    private static void discard(byte[] bytes) {
        if (bytes != null) {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    /** Writes in bounded chunks so the request deadline is checked between servlet writes. */
    public void writeReleased(DownloadLease lease, java.io.OutputStream outputStream) throws java.io.IOException {
        lease.writeTo(outputStream, lease.attempt().deadline());
    }

    record Attempt(Snapshot snapshot, DocumentSourceConnector connector, String liveVersion, DownloadDeadline deadline) {
    }

    record Snapshot(SourceAccessContext context, com.sdv.source.domain.SourceType sourceType, String providerFileId) {
    }

    public static final class DownloadLease implements AutoCloseable {
        private byte[] bytes;
        private final String filename;
        private final String mimeType;
        private final Attempt attempt;
        private final Semaphore capacity;
        private boolean releaseAuthorized;
        private boolean writing;
        private boolean closeRequested;

        DownloadLease(byte[] bytes, String filename, String mimeType, Attempt attempt, Semaphore capacity) {
            this.bytes = bytes;
            this.filename = filename;
            this.mimeType = mimeType;
            this.attempt = attempt;
            this.capacity = capacity;
        }

        private synchronized boolean belongsTo(String subject) {
            return bytes != null && attempt.snapshot().context().requesterSubject().equals(subject);
        }

        private synchronized Attempt attempt() { return attempt; }
        private synchronized String verifiedSourceVersion() { return attempt.liveVersion(); }
        private synchronized void authorizeRelease() {
            if (bytes == null) {
                throw new IllegalStateException("download lease is closed");
            }
            releaseAuthorized = true;
        }

        /** Writes only after prepareForRelease has completed.  The lease remains owned until this returns. */
        public void writeTo(java.io.OutputStream outputStream) throws java.io.IOException {
            writeTo(outputStream, null);
        }

        private void writeTo(java.io.OutputStream outputStream, DownloadDeadline deadline) throws java.io.IOException {
            byte[] owned;
            synchronized (this) {
                if (!releaseAuthorized || bytes == null) {
                    throw new IllegalStateException("download lease has not passed final authorization");
                }
                writing = true;
                owned = bytes;
            }
            try {
                for (int offset = 0; offset < owned.length; ) {
                    if (deadline != null) {
                        deadline.remainingMillis();
                    }
                    int length = Math.min(8192, owned.length - offset);
                    outputStream.write(owned, offset, length);
                    offset += length;
                }
                if (deadline != null) {
                    deadline.remainingMillis();
                }
                outputStream.flush();
            } finally {
                synchronized (this) {
                    writing = false;
                    if (closeRequested) {
                        releaseOwnedBuffer();
                    }
                }
            }
        }

        public synchronized long contentLength() {
            if (!releaseAuthorized || bytes == null) {
                throw new IllegalStateException("download lease is closed");
            }
            return bytes.length;
        }

        public String filename() { return filename; }
        public String mimeType() { return mimeType; }

        @Override
        public synchronized void close() {
            closeRequested = true;
            if (!writing) {
                releaseOwnedBuffer();
            }
        }

        private void releaseOwnedBuffer() {
            if (bytes != null) {
                discard(bytes); // best effort only; Java cannot promise physical-memory erasure.
                bytes = null;
                capacity.release();
            }
        }
    }

    static final class DownloadDeadline {
        private final long deadlineNanos;

        private DownloadDeadline(long deadlineNanos) { this.deadlineNanos = deadlineNanos; }

        static DownloadDeadline start(long timeoutMs) {
            return new DownloadDeadline(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs));
        }

        long remainingMillis() {
            long nanos = deadlineNanos - System.nanoTime();
            if (nanos <= 0) {
                throw new SharedFileDownloadException(SharedFileDownloadException.Reason.REQUEST_TIMEOUT);
            }
            return Math.max(1L, TimeUnit.NANOSECONDS.toMillis(nanos));
        }
    }
}
