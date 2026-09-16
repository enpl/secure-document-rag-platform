package com.sdv.rag.application;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sdv.common.model.Role;
import com.sdv.common.model.UserContext;
import com.sdv.rag.application.port.out.EphemeralEvidenceStore;
import com.sdv.rag.domain.LiveRetrievalStatus;
import com.sdv.rag.infrastructure.ai.DocumentParsingClient;
import com.sdv.source.application.port.DocumentSourceConnector;
import com.sdv.source.domain.SourceAccessContext;
import com.sdv.source.domain.SourceChangePage;
import com.sdv.source.domain.SourceContentResult;
import com.sdv.source.domain.SourceDocument;
import com.sdv.source.domain.SourceMetadataPage;
import com.sdv.source.domain.SourceMetadataVerificationResult;
import com.sdv.source.domain.SourcePermissionsResult;
import com.sdv.source.domain.SourceType;
import com.sdv.source.infrastructure.google.GoogleDriveClient;
import com.sdv.source.infrastructure.google.GoogleDriveContentAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LiveEvidenceGoogleRetryIntegrationTest {

    @Test
    void oneWholeAttemptRetryUsesTheRealGoogleTransportAndStopsAfterTheSecondVersionChange() throws Exception {
        AtomicInteger metadataCalls = new AtomicInteger();
        AtomicInteger mediaCalls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/drive/v3/files/file-1", exchange -> {
            try {
                if (isMetadata(exchange)) {
                    int call = metadataCalls.incrementAndGet();
                    String version = call == 1 ? "v1" : call <= 3 ? "v2" : "v3";
                    send(exchange, fileJson(version));
                } else {
                    mediaCalls.incrementAndGet();
                    send(exchange, "hello");
                }
            } catch (IOException ignored) {
                exchange.close();
            }
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            GoogleDriveClient client = new GoogleDriveClient(RestClient.builder().build(), new ObjectMapper(), 30_000,
                    baseUrl);
            TestConnector connector = new TestConnector(new GoogleDriveContentAdapter(client));
            SourceConsistencyGuard guard = mock(SourceConsistencyGuard.class);
            DocumentParsingClient parser = mock(DocumentParsingClient.class);
            EphemeralEvidenceStore store = mock(EphemeralEvidenceStore.class);
            EvidenceConversationLifecycle lifecycle = mock(EvidenceConversationLifecycle.class);
            EvidenceConversationLifecycle.Lease lease = mock(EvidenceConversationLifecycle.Lease.class);
            UserContext requester = new UserContext("requester", "requester@example.com", Set.of(Role.USER), Set.of());
            SourceAccessContext access = new SourceAccessContext("requester", "publisher", 1L, 42L, 9L,
                    com.sdv.source.domain.ShareAction.VIEW, 1L, 1L);
            SourceConsistencyGuard.Snapshot snapshot = new SourceConsistencyGuard.Snapshot(access,
                    SourceType.GOOGLE_DRIVE);
            when(lifecycle.requireActive(any(), any())).thenReturn(lease);
            when(lifecycle.isActive(lease)).thenReturn(true);
            when(guard.verifyBefore(any(), eq(42L), anyLong()))
                    .thenReturn(new SourceConsistencyGuard.LiveIdentity(requester, snapshot, connector, "v1",
                                    "text/plain", "note.txt"),
                            new SourceConsistencyGuard.LiveIdentity(requester, snapshot, connector, "v2",
                                    "text/plain", "note.txt"));
            LiveRetrievalProperties properties = new LiveRetrievalProperties(5, 1_000, 1_000, 4_000, 10, 500);
            LiveEvidenceRetrievalService service = new LiveEvidenceRetrievalService(guard, parser, store, properties,
                    lifecycle, new LiveContentAdmission(1, 25L * 1024 * 1024));

            var result = service.retrieveLive(requester, 42L, "conversation", null);

            assertThat(result.status()).isEqualTo(LiveRetrievalStatus.DOCUMENT_CHANGED);
            assertThat(metadataCalls).hasValue(4);
            assertThat(mediaCalls).hasValue(2);
            verify(parser, never()).parse(any(), any(), any(), anyLong());
        } finally {
            server.stop(0);
        }
    }

    private static boolean isMetadata(HttpExchange exchange) {
        String query = exchange.getRequestURI().getRawQuery();
        return query != null && query.contains("fields=");
    }

    private static String fileJson(String version) {
        return "{\"id\":\"file-1\",\"name\":\"note.txt\",\"mimeType\":\"text/plain\","
                + "\"version\":\"" + version + "\",\"modifiedTime\":\"2026-09-16T00:00:00Z\","
                + "\"trashed\":false,\"size\":\"5\",\"capabilities\":{\"canDownload\":true}}";
    }

    private static void send(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static final class TestConnector implements DocumentSourceConnector {
        private final GoogleDriveContentAdapter adapter;

        private TestConnector(GoogleDriveContentAdapter adapter) {
            this.adapter = adapter;
        }

        @Override
        public SourceContentResult fetchForAi(SourceAccessContext context, String expectedSourceVersion,
                long deadlineMs) {
            return adapter.fetchVerifiedForAi("token", "file-1", expectedSourceVersion, deadlineMs);
        }

        @Override public SourceType supportedType() { return SourceType.GOOGLE_DRIVE; }
        @Override public SourceDocument getMetadata(Long sourceId, String sourceDocumentId) { throw unsupported(); }
        @Override public SourceMetadataPage listMetadata(Long sourceId, String pageToken) { throw unsupported(); }
        @Override public SourceContentResult fetchContent(UserContext user, Long sourceId, String documentId,
                String version) { throw unsupported(); }
        @Override public SourcePermissionsResult getPermissions(Long sourceId, String documentId) { throw unsupported(); }
        @Override public SourceChangePage findChanges(Long sourceId, String pageToken) { throw unsupported(); }
        @Override public SourceMetadataVerificationResult verifyCurrentMetadata(UserContext user, Long sourceId,
                String documentId) { throw unsupported(); }

        private static UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("not used by this focused test");
        }
    }
}
