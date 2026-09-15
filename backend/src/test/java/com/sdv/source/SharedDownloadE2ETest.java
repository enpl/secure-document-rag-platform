package com.sdv.source;

import com.sdv.common.model.Role;
import com.sdv.common.model.UserContext;
import com.sdv.common.security.CurrentUserProvider;
import com.sdv.policy.application.EffectivePermissionService;
import com.sdv.source.api.SharedFileDownloadController;
import com.sdv.source.application.SharedFileDownloadException;
import com.sdv.source.application.SharedFileDownloadProperties;
import com.sdv.source.application.SharedFileDownloadService;
import com.sdv.source.application.SourceConnectorRegistry;
import com.sdv.source.application.SourceSharingService;
import com.sdv.source.application.port.DocumentSourceConnector;
import com.sdv.source.domain.DocumentShare;
import com.sdv.source.domain.SourceDownloadResult;
import com.sdv.source.domain.SourceMetadataVerificationResult;
import com.sdv.source.domain.SourceType;
import com.sdv.source.infrastructure.persistence.entity.SourceConnectionEntity;
import com.sdv.source.infrastructure.persistence.entity.SourceDocumentEntity;
import com.sdv.source.infrastructure.persistence.repository.SourceConnectionJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourceDocumentJpaRepository;
import com.sdv.testsupport.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SHR-004 application/integration coverage: PostgreSQL share policy is real; every provider
 * interaction is an in-process mock.  It is not a real Google or browser E2E claim.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "sdv.keycloak.issuer-uri=http://localhost:8180/realms/sdv",
        "sdv.keycloak.audience=sdv-backend",
        "sdv.shared-download.max-bytes=32",
        "sdv.shared-download.max-concurrent-downloads=1",
        "sdv.shared-download.request-timeout-ms=10000"
})
class SharedDownloadE2ETest {
    @Autowired private SourceSharingService sourceSharingService;
    @Autowired private EffectivePermissionService effectivePermissionService;
    @Autowired private SourceConnectionJpaRepository sourceConnectionJpaRepository;
    @Autowired private SourceDocumentJpaRepository sourceDocumentJpaRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    private DocumentSourceConnector connector;
    private SharedFileDownloadService downloadService;

    @BeforeEach
    void setUp() {
        connector = mock(DocumentSourceConnector.class);
        when(connector.supportedType()).thenReturn(SourceType.GOOGLE_DRIVE);
        downloadService = new SharedFileDownloadService(nullSafeShareRepository(), sourceDocumentJpaRepository,
                sourceConnectionJpaRepository, effectivePermissionService,
                new SourceConnectorRegistry(List.of(connector)), new SharedFileDownloadProperties(32, 1, 10_000),
                transactionManager);
    }

    @Autowired
    private com.sdv.source.infrastructure.persistence.repository.DocumentShareJpaRepository documentShareJpaRepository;

    private com.sdv.source.infrastructure.persistence.repository.DocumentShareJpaRepository nullSafeShareRepository() {
        return documentShareJpaRepository;
    }

    @Test
    void recipientWithDownloadGrantReceivesExactBytesWithoutNativeGooglePermissionOrOwnConnection() throws Exception {
        String a = "publisher-" + unique();
        String b = "recipient-" + unique();
        Doc doc = createDocument(createSource(a), "Quarterly.pdf", "application/pdf", "catalog-v1");
        DocumentShare share = sourceSharingService.createShare(a, doc.sourceId(), doc.documentId(), "INTERNAL",
                Set.of("VIEW", "DOWNLOAD"), Set.of(b));
        byte[] expected = "verified bytes".getBytes();
        stubDownload(doc.sourceDocumentId(), expected.clone(), "unsafe\r\nQuarterly.pdf", "application/pdf", "live-v2");

        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        when(currentUserProvider.getCurrentUser()).thenReturn(user(b));
        SharedFileDownloadController controller = new SharedFileDownloadController(downloadService, currentUserProvider);
        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.download(share.getId(), response);

        assertThat(response.getContentAsByteArray()).isEqualTo(expected);
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader("Content-Disposition")).contains("attachment");
        assertThat(response.getHeader("Content-Disposition")).doesNotContain("\r", "\n");
        verify(connector).fetchDownload(any(), eq("live-v2"), eq(32L), anyLong());
    }

    @Test
    void viewOnlyOutsiderAndAdminWithoutGrantCannotFetch() {
        String a = "publisher-" + unique();
        String b = "view-only-" + unique();
        String c = "outsider-" + unique();
        String admin = "admin-" + unique();
        Doc doc = createDocument(createSource(a), "Private.zip", "application/zip", "v1");
        DocumentShare share = sourceSharingService.createShare(a, doc.sourceId(), doc.documentId(), "INTERNAL",
                Set.of("VIEW"), Set.of(b));

        for (UserContext denied : List.of(user(b), user(c), new UserContext(admin, null, Set.of(Role.ADMIN), Set.of()))) {
            assertThatThrownBy(() -> downloadService.download(denied, share.getId()))
                    .isInstanceOf(SharedFileDownloadException.class)
                    .extracting(ex -> ((SharedFileDownloadException) ex).reason())
                    .isEqualTo(SharedFileDownloadException.Reason.NOT_AUTHORIZED);
        }
        verify(connector, never()).verifyDownload(any(), anyLong());
        verify(connector, never()).fetchDownload(any(), any(), anyLong(), anyLong());
    }

    @Test
    void revokeDuringFetchDiscardsBytesBeforeControllerCanReleaseThem() {
        String a = "publisher-" + unique();
        String b = "recipient-" + unique();
        Doc doc = createDocument(createSource(a), "Race.png", "image/png", "v1");
        DocumentShare share = sourceSharingService.createShare(a, doc.sourceId(), doc.documentId(), "INTERNAL",
                Set.of("DOWNLOAD"), Set.of(b));
        byte[] fetched = new byte[] { 1, 2, 3 };
        when(connector.verifyDownload(any(), anyLong()))
                .thenReturn(SourceMetadataVerificationResult.verified("Race.png", "image/png", "live-v1", Instant.now(), true));
        when(connector.fetchDownload(any(), eq("live-v1"), eq(32L), anyLong())).thenAnswer(invocation -> {
            sourceSharingService.unshare(a, share.getId());
            return SourceDownloadResult.verified(fetched, "Race.png", "image/png", "live-v1", false);
        });

        try (SharedFileDownloadService.DownloadLease lease = downloadService.download(user(b), share.getId())) {
            assertThatThrownBy(() -> downloadService.prepareForRelease(user(b), lease))
                    .isInstanceOf(SharedFileDownloadException.class);
        }
        assertThat(fetched).containsOnly((byte) 0);
    }

    @Test
    void unshareWhileFinalProviderVerificationWaitsReleasesNoBytesOrSuccessAttachment() throws Exception {
        String a = "publisher-" + unique();
        String b = "recipient-" + unique();
        Doc doc = createDocument(createSource(a), "Race.png", "image/png", "v1");
        DocumentShare share = sourceSharingService.createShare(a, doc.sourceId(), doc.documentId(), "INTERNAL",
                Set.of("DOWNLOAD"), Set.of(b));
        CountDownLatch finalProviderCheck = new CountDownLatch(1);
        CountDownLatch allowFinalProviderCheck = new CountDownLatch(1);
        AtomicInteger checks = new AtomicInteger();
        when(connector.verifyDownload(any(), anyLong())).thenAnswer(invocation -> {
            if (checks.incrementAndGet() == 2) {
                finalProviderCheck.countDown();
                assertThat(allowFinalProviderCheck.await(2, TimeUnit.SECONDS)).isTrue();
            }
            return SourceMetadataVerificationResult.verified("Race.png", "image/png", "live-v1", Instant.now(), true);
        });
        when(connector.fetchDownload(any(), eq("live-v1"), eq(32L), anyLong()))
                .thenReturn(SourceDownloadResult.verified(new byte[] { 1, 2, 3 }, "Race.png", "image/png", "live-v1", false));
        CurrentUserProvider current = mock(CurrentUserProvider.class);
        when(current.getCurrentUser()).thenReturn(user(b));
        MockHttpServletResponse response = new MockHttpServletResponse();
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> future = executor.submit(() -> {
                try {
                    new SharedFileDownloadController(downloadService, current).download(share.getId(), response);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            assertThat(finalProviderCheck.await(2, TimeUnit.SECONDS)).isTrue();
            sourceSharingService.unshare(a, share.getId());
            allowFinalProviderCheck.countDown();
            assertThatThrownBy(future::get).hasCauseInstanceOf(SharedFileDownloadException.class);
        }
        assertThat(response.getContentAsByteArray()).isEmpty();
        assertThat(response.getHeader("Content-Disposition")).isNull();
    }

    @Test
    void adminBlockAndSameAccountEpochChangeDuringFinalProviderCheckInvalidateOldLease() throws Exception {
        String a = "publisher-" + unique();
        String b = "recipient-" + unique();
        long sourceId = createSource(a);
        Doc doc = createDocument(sourceId, "Race.zip", "application/zip", "v1");
        DocumentShare share = sourceSharingService.createShare(a, doc.sourceId(), doc.documentId(), "INTERNAL",
                Set.of("DOWNLOAD"), Set.of(b));
        CountDownLatch finalProviderCheck = new CountDownLatch(1);
        CountDownLatch continueProviderCheck = new CountDownLatch(1);
        AtomicInteger checks = new AtomicInteger();
        when(connector.verifyDownload(any(), anyLong())).thenAnswer(invocation -> {
            if (checks.incrementAndGet() == 2) {
                finalProviderCheck.countDown();
                assertThat(continueProviderCheck.await(2, TimeUnit.SECONDS)).isTrue();
            }
            return SourceMetadataVerificationResult.verified("Race.zip", "application/zip", "live-v1", Instant.now(), true);
        });
        when(connector.fetchDownload(any(), eq("live-v1"), eq(32L), anyLong()))
                .thenReturn(SourceDownloadResult.verified(new byte[] { 4 }, "Race.zip", "application/zip", "live-v1", false));
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> future = executor.submit(() -> {
                try (SharedFileDownloadService.DownloadLease lease = downloadService.download(user(b), share.getId())) {
                    downloadService.prepareForRelease(user(b), lease);
                }
            });
            assertThat(finalProviderCheck.await(2, TimeUnit.SECONDS)).isTrue();
            sourceSharingService.adminSetBlocked("admin-" + unique(), share.getId(), true, "test");
            SourceConnectionEntity reconnected = sourceConnectionJpaRepository.findById(sourceId).orElseThrow();
            reconnected.bumpConnectionEpoch(); // disconnect
            reconnected.bumpConnectionEpoch(); // same verified account reconnect; old snapshot remains fenced
            sourceConnectionJpaRepository.saveAndFlush(reconnected);
            continueProviderCheck.countDown();
            assertThatThrownBy(future::get).hasCauseInstanceOf(SharedFileDownloadException.class);
        }
    }

    @Test
    void binaryDownloadDoesNotInvokeAiContentTransport() throws Exception {
        String a = "publisher-" + unique();
        String b = "recipient-" + unique();
        Doc doc = createDocument(createSource(a), "Archive.zip", "application/zip", "v1");
        DocumentShare share = sourceSharingService.createShare(a, doc.sourceId(), doc.documentId(), "INTERNAL",
                Set.of("DOWNLOAD"), Set.of(b));
        stubDownload(doc.sourceDocumentId(), new byte[] { 9 }, "unsafe\r\n.zip", "application/zip", "live-v1");

        try (SharedFileDownloadService.DownloadLease lease = downloadService.download(user(b), share.getId())) {
            downloadService.prepareForRelease(user(b), lease);
            assertThat(lease.contentLength()).isEqualTo(1);
        }
        verify(connector, never()).fetchContent(any(), any(), any(), any());
    }

    @Test
    void responsePreparationOrWriteFailureReleasesTheOnlyCapacityPermit() throws Exception {
        String a = "publisher-" + unique();
        String b = "recipient-" + unique();
        Doc doc = createDocument(createSource(a), "Bounded.bin", "application/octet-stream", "v1");
        DocumentShare share = sourceSharingService.createShare(a, doc.sourceId(), doc.documentId(), "INTERNAL",
                Set.of("DOWNLOAD"), Set.of(b));
        stubDownload(doc.sourceDocumentId(), new byte[] { 7 }, "Bounded.bin", "application/octet-stream", "live-v1");
        try (SharedFileDownloadService.DownloadLease lease = downloadService.download(user(b), share.getId())) {
            downloadService.prepareForRelease(user(b), lease);
            assertThatThrownBy(() -> lease.writeTo(new java.io.OutputStream() {
                @Override public void write(int ignored) throws IOException { throw new IOException("client cancelled"); }
            })).isInstanceOf(IOException.class);
        }
        try (SharedFileDownloadService.DownloadLease next = downloadService.download(user(b), share.getId())) {
            downloadService.prepareForRelease(user(b), next);
            assertThat(next.contentLength()).isEqualTo(1);
        }
    }

    private void stubDownload(String sourceDocumentId, byte[] bytes, String filename, String mime, String version) {
        when(connector.verifyDownload(any(), anyLong()))
                .thenReturn(SourceMetadataVerificationResult.verified(filename, mime, version, Instant.now(), true));
        when(connector.fetchDownload(any(), eq(version), eq(32L), anyLong()))
                .thenReturn(SourceDownloadResult.verified(bytes, filename, mime, version, false));
    }

    private long createSource(String owner) {
        SourceConnectionEntity source = new SourceConnectionEntity("GOOGLE_DRIVE", "Test", "ACTIVE", "FULL", owner);
        source.adoptProviderAccountId("verified-" + owner);
        sourceConnectionJpaRepository.saveAndFlush(source);
        return source.getId();
    }

    private Doc createDocument(long sourceId, String name, String mime, String version) {
        SourceDocumentEntity document = new SourceDocumentEntity(sourceId, "file-" + unique(), name, mime, version,
                Instant.now(), "ACTIVE", "PENDING", null);
        sourceDocumentJpaRepository.saveAndFlush(document);
        return new Doc(sourceId, document.getId(), document.getSourceDocumentId());
    }

    private static UserContext user(String subject) {
        return new UserContext(subject, subject + "@example.test", Set.of(Role.USER), Set.of());
    }

    private static String unique() { return UUID.randomUUID().toString(); }
    private record Doc(long sourceId, long documentId, String sourceDocumentId) { }
}
