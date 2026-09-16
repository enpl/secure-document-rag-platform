package com.sdv.ai.infrastructure;

import com.sdv.ai.application.port.LlmPort;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class OllamaLlmAdapterTest {
    @Test
    void successfulCallsSendExactSchemasSystemBoundaryAndConfiguredOptions() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicReference<String> classificationRequest = new AtomicReference<>();
        AtomicReference<String> generationRequest = new AtomicReference<>();
        AtomicLong calls = new AtomicLong();
        HttpServer server = respondingServer(exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            long call = calls.incrementAndGet();
            if (call == 1) classificationRequest.set(request); else generationRequest.set(request);
            String inner = call == 1
                    ? "{\"intent\":\"FIND_FILE\"}"
                    : "{\"claims\":[{\"text\":\"정답\",\"evidenceLabels\":[\"E1\"],\"supportingQuotes\":[\"근거\"]}],\"generatedAnalysis\":\"분석\"}";
            sendJson(exchange, mapper.writeValueAsString(java.util.Map.of("response", inner, "done", true,
                    "done_reason", "stop")));
        });
        server.start();
        try {
            var adapter = new OllamaLlmAdapter(mapper,
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()), HttpClient.newHttpClient());

            assertThat(adapter.classify("파일 찾아줘", "test-model", 2_000).intent())
                    .isEqualTo(com.sdv.ai.application.AssistantIntent.FIND_FILE);
            var generated = adapter.generate(new LlmPort.GenerationRequest("structured-user-data",
                    List.of(new LlmPort.EvidenceInput("E1", "근거"))), "test-model", 2_000);
            assertThat(generated.kind()).isEqualTo(LlmPort.GenerationResult.Kind.SUCCESS);

            JsonNode classification = mapper.readTree(classificationRequest.get());
            JsonNode generation = mapper.readTree(generationRequest.get());
            for (JsonNode request : List.of(classification, generation)) {
                assertThat(request.get("model").asText()).isEqualTo("test-model");
                assertThat(request.get("stream").asBoolean()).isFalse();
                assertThat(request.get("format").isObject()).isTrue();
                assertThat(request.get("format").get("additionalProperties").asBoolean()).isFalse();
                assertThat(request.get("options").get("num_ctx").asInt()).isEqualTo(8192);
                assertThat(request.get("options").get("num_predict").asInt()).isEqualTo(1024);
                assertThat(request.get("system").asText()).contains("untrusted data");
            }
            assertThat(classification.get("format").toString()).contains("FIND_FILE", "POLICY_BYPASS");
            assertThat(generation.get("format").toString()).contains("evidenceLabels", "supportingQuotes",
                    "generatedAnalysis", "maxItems", "maxLength");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void malformedUnknownNullOversizedAndIncompleteGenerationAreRejectedSafely() throws Exception {
        List<String> invalid = List.of(
                "{\"claims\":[],\"generatedAnalysis\":\"x\",\"unknown\":1}",
                "{\"claims\":[null],\"generatedAnalysis\":\"x\"}",
                "{\"claims\":[{\"text\":\"x\",\"evidenceLabels\":[null],\"supportingQuotes\":[\"x\"]}],\"generatedAnalysis\":\"x\"}",
                "{\"claims\":[{\"text\":\"" + "x".repeat(5000) + "\",\"evidenceLabels\":[\"E1\"],\"supportingQuotes\":[\"x\"]}],\"generatedAnalysis\":\"x\"}",
                "{\"claims\":[{\"text\":\"x\"}]}",
                "{not-json");
        for (String inner : invalid) {
            ObjectMapper mapper = new ObjectMapper();
            HttpServer server = respondingServer(exchange -> sendJson(exchange,
                    mapper.writeValueAsString(java.util.Map.of("response", inner, "done", true, "done_reason", "stop"))));
            server.start();
            try {
                var adapter = new OllamaLlmAdapter(mapper,
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort()), HttpClient.newHttpClient());
                var result = adapter.generate(new LlmPort.GenerationRequest("prompt",
                        List.of(new LlmPort.EvidenceInput("E1", "x"))), "test", 2_000);
                assertThat(result.kind()).as(inner.substring(0, Math.min(80, inner.length())))
                        .isEqualTo(LlmPort.GenerationResult.Kind.FAILED);
            } finally {
                server.stop(0);
            }
        }

        ObjectMapper mapper = new ObjectMapper();
        HttpServer incompleteServer = respondingServer(exchange -> sendJson(exchange,
                mapper.writeValueAsString(java.util.Map.of("response", "{\"claims\":[]}", "done", false))));
        incompleteServer.start();
        try {
            var adapter = new OllamaLlmAdapter(mapper,
                    URI.create("http://127.0.0.1:" + incompleteServer.getAddress().getPort()), HttpClient.newHttpClient());
            assertThat(adapter.generate(new LlmPort.GenerationRequest("prompt", List.of()), "test", 2_000).kind())
                    .isEqualTo(LlmPort.GenerationResult.Kind.FAILED);
        } finally {
            incompleteServer.stop(0);
        }
    }

    @Test
    void invalidMissingAndUnknownClassificationFieldsFailClosed() throws Exception {
        for (String inner : List.of("{\"intent\":\"NOT_AN_INTENT\"}", "{}",
                "{\"intent\":\"FIND_CONTENT\",\"extra\":true}", "{\"intent\":null}")) {
            ObjectMapper mapper = new ObjectMapper();
            HttpServer server = respondingServer(exchange -> sendJson(exchange,
                    mapper.writeValueAsString(java.util.Map.of("response", inner, "done", true, "done_reason", "stop"))));
            server.start();
            try {
                var adapter = new OllamaLlmAdapter(mapper,
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort()), HttpClient.newHttpClient());
                assertThat(adapter.classify("question", "test", 2_000).kind())
                        .isEqualTo(LlmPort.ClassificationResult.Kind.FAILED);
            } finally {
                server.stop(0);
            }
        }
    }
    @Test
    void deadlineCancelsActualBodySubscription() throws Exception {
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
        try {
            RecordingSubscription subscription = new RecordingSubscription();
            HttpResponse.BodySubscriber<byte[]> subscriber = OllamaLlmAdapter.deadlineSubscriber(1000,
                    System.nanoTime(), 40, scheduler);
            subscriber.onSubscribe(subscription);
            subscriber.onNext(List.of(ByteBuffer.wrap(new byte[]{'{'})));
            assertThat(subscriber.getBody().toCompletableFuture().handle((v, e) -> e).get(1, TimeUnit.SECONDS))
                    .isNotNull();
            assertThat(subscription.cancelled).isTrue();
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void stalledResponseBodyReturnsTimeoutBeforeServerRelease() throws Exception {
        CountDownLatch bodyFlushed = new CountDownLatch(1);
        CountDownLatch releaseServer = new CountDownLatch(1);
        AtomicBoolean released = new AtomicBoolean();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/generate", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write('{'); exchange.getResponseBody().flush(); bodyFlushed.countDown();
            try { releaseServer.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            released.set(true); exchange.close();
        });
        server.start();
        try {
            var adapter = new OllamaLlmAdapter(new ObjectMapper(),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()), HttpClient.newHttpClient());
            AtomicReference<LlmPort.ClassificationResult> result = new AtomicReference<>();
            AtomicLong elapsed = new AtomicLong();
            Thread invocation = Thread.ofPlatform().start(() -> {
                long started = System.nanoTime();
                result.set(adapter.classify("question", "test", 1000));
                elapsed.set(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            });
            assertThat(bodyFlushed.await(2, TimeUnit.SECONDS)).isTrue();
            invocation.join(3000);
            assertThat(invocation.isAlive()).isFalse();
            assertThat(result.get().kind()).isEqualTo(LlmPort.ClassificationResult.Kind.TIMEOUT);
            assertThat(released).isFalse();
            assertThat(elapsed.get()).isLessThan(3000);
        } finally {
            releaseServer.countDown(); server.stop(0);
        }
    }

    private static final class RecordingSubscription implements Flow.Subscription {
        private volatile boolean cancelled;
        @Override public void request(long n) { }
        @Override public void cancel() { cancelled = true; }
    }

    private static HttpServer respondingServer(ExchangeHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/generate", exchange -> {
            try { handler.handle(exchange); } finally { exchange.close(); }
        });
        return server;
    }

    private static void sendJson(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException;
    }
}
