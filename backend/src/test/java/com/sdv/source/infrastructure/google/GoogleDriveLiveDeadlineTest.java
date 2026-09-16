package com.sdv.source.infrastructure.google;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sdv.source.domain.SourceContentOutcome;
import com.sdv.source.domain.SourceContentResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class GoogleDriveLiveDeadlineTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void headersConsumeMostOfBudgetThenStalledBodyRemainsTimeout() throws Exception {
        server = start(exchange -> {
            if (isMetadata(exchange)) {
                sleep(90);
                sendJson(exchange, fileJson("v1"));
                return;
            }
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write('a');
            exchange.getResponseBody().flush();
            sleep(300);
            closeQuietly(exchange);
        });

        long started = System.nanoTime();
        SourceContentResult result = adapter().fetchVerifiedForAi("token", "file-1", "v1", 180);

        assertThat(result.outcome()).isEqualTo(SourceContentOutcome.TIMEOUT);
        assertThat(elapsedMillis(started)).isLessThan(600);
    }

    @Test
    void tricklingBodyCannotRefreshTheWholeOperationBudget() throws Exception {
        server = start(exchange -> {
            if (isMetadata(exchange)) {
                sendJson(exchange, fileJson("v1"));
                return;
            }
            exchange.sendResponseHeaders(200, 0);
            try {
                for (int i = 0; i < 10; i++) {
                    exchange.getResponseBody().write('a' + i);
                    exchange.getResponseBody().flush();
                    sleep(45);
                }
            } catch (IOException ignored) {
                // Expected when the bounded client closes the expired exchange.
            } finally {
                closeQuietly(exchange);
            }
        });

        SourceContentResult result = adapter().fetchVerifiedForAi("token", "file-1", "v1", 170);

        assertThat(result.outcome()).isEqualTo(SourceContentOutcome.TIMEOUT);
    }

    @Test
    void aiAdapterDoesNotHideANestedVersionChangeRetry() throws Exception {
        AtomicInteger metadataCalls = new AtomicInteger();
        AtomicInteger mediaCalls = new AtomicInteger();
        server = start(exchange -> {
            if (isMetadata(exchange)) {
                sendJson(exchange, fileJson(metadataCalls.incrementAndGet() == 1 ? "v1" : "v2"));
                return;
            }
            mediaCalls.incrementAndGet();
            byte[] body = "hello".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            closeQuietly(exchange);
        });

        SourceContentResult result = adapter().fetchVerifiedForAi("token", "file-1", "v1", 1_000);

        assertThat(result.outcome()).isEqualTo(SourceContentOutcome.DOCUMENT_CHANGED);
        assertThat(metadataCalls).hasValue(2);
        assertThat(mediaCalls).hasValue(1);
    }

    private GoogleDriveContentAdapter adapter() {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        GoogleDriveClient client = new GoogleDriveClient(RestClient.builder().build(), new ObjectMapper(), 30_000,
                baseUrl);
        return new GoogleDriveContentAdapter(client);
    }

    private HttpServer start(ThrowingHandler handler) throws IOException {
        HttpServer local = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        local.createContext("/drive/v3/files/file-1", exchange -> {
            try {
                handler.handle(exchange);
            } catch (Exception e) {
                closeQuietly(exchange);
            }
        });
        local.start();
        return local;
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

    private static void sendJson(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        closeQuietly(exchange);
    }

    private static void closeQuietly(HttpExchange exchange) {
        try {
            exchange.close();
        } catch (RuntimeException ignored) {
            // Test server cleanup only.
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    @FunctionalInterface
    private interface ThrowingHandler {
        void handle(HttpExchange exchange) throws Exception;
    }
}
