package com.sdv.rag.infrastructure.ai;

import com.sdv.rag.domain.ParseOutcome;
import com.sdv.rag.domain.ParseOutcomeKind;
import com.sdv.rag.domain.QueryEmbeddingOutcome;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import com.sun.net.httpserver.HttpServer;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * {@link DocumentParsingClient}의 순수 계약(Contract) 테스트 - 실제 AI Service를
 * 띄우지 않고 {@link MockRestServiceServer}로 HTTP Stub을 세운다(Postgres/실제
 * 네트워크 불필요, {@code AiServiceRestClientConfig} 없이 이 테스트에서 직접
 * {@link RestClient}를 구성한다).
 */
class DocumentParsingClientTest {

    private MockRestServiceServer server;
    private DocumentParsingClient client;

    private void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ai-service.internal");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new DocumentParsingClient(builder.build());
    }

    @Test
    void successResponseMapsToSuccessOutcome() {
        setUp();
        server.expect(requestTo("http://ai-service.internal/parse"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {
                          "outcome": "SUCCESS",
                          "parserName": "charset-normalizer",
                          "parserVersion": "decode-only",
                          "normalizationVersion": "1",
                          "normalizedText": "hello world",
                          "locations": [{"locatorType": "DOCUMENT", "locatorValue": "1", "startOffset": 0, "endOffset": 11}],
                          "chunkingVersion": "2",
                          "embeddingModel": "bge-m3:567m",
                          "chunks": [{"chunkIndex": 0, "locatorType": "DOCUMENT", "locatorValue": "1", "startOffset": 0, "endOffset": 11}]
                        }
                        """, MediaType.APPLICATION_JSON));

        ParseOutcome outcome = client.parse("hello world".getBytes(), "notes.txt", "text/plain");

        assertThat(outcome.kind()).isEqualTo(ParseOutcomeKind.SUCCESS);
        assertThat(outcome.normalizedText()).isEqualTo("hello world");
        assertThat(outcome.locations()).hasSize(1);
        assertThat(outcome.locations().get(0).locatorValue()).isEqualTo("1");
        assertThat(outcome.chunks()).hasSize(1);
        server.verify();
    }

    @Test
    void unsupportedFormatResponseMapsToFailureOutcome() {
        setUp();
        server.expect(requestTo("http://ai-service.internal/parse"))
                .andRespond(withSuccess("""
                        {"outcome": "UNSUPPORTED_FORMAT", "reason": "unsupported or mismatched format"}
                        """, MediaType.APPLICATION_JSON));

        ParseOutcome outcome = client.parse(new byte[] {1, 2, 3}, "legacy.doc", "application/msword");

        assertThat(outcome.kind()).isEqualTo(ParseOutcomeKind.UNSUPPORTED_FORMAT);
        assertThat(outcome.reason()).isEqualTo("unsupported or mismatched format");
    }

    @Test
    void boundedParseUsesAFiniteDeadlineWithoutChangingTheLegacyCall() {
        setUp();
        server.expect(requestTo("http://ai-service.internal/parse"))
                .andRespond(withSuccess("""
                        {"outcome":"UNSUPPORTED_FORMAT","reason":"unsupported"}
                        """, MediaType.APPLICATION_JSON));

        ParseOutcome outcome = client.parse(new byte[] {1}, "a.bin", "application/octet-stream", 1_000);

        assertThat(outcome.kind()).isEqualTo(ParseOutcomeKind.UNSUPPORTED_FORMAT);
        server.verify();
    }

    @Test
    void boundedParseStopsASlowRealHttpResponseAtTheOperationDeadline() throws Exception {
        HttpServer local = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        local.createContext("/parse", exchange -> {
            try {
                Thread.sleep(250);
                byte[] response = "{\"outcome\":\"UNSUPPORTED_FORMAT\",\"reason\":\"unsupported\"}"
                        .getBytes();
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        local.start();
        try {
            DocumentParsingClient bounded = new DocumentParsingClient(RestClient.builder().build(), new ObjectMapper(),
                    "http://127.0.0.1:" + local.getAddress().getPort());

            ParseOutcome outcome = bounded.parse(new byte[] {1}, "a.bin", "application/octet-stream", 80);

            assertThat(outcome.kind()).isEqualTo(ParseOutcomeKind.TIMEOUT);
        } finally {
            local.stop(0);
        }
    }

    @Test
    void boundedParseStopsWhenResponseBodyStallsAfterHeaders() throws Exception {
        CountDownLatch initialBodyFlushed = new CountDownLatch(1);
        CountDownLatch releaseServer = new CountDownLatch(1);
        AtomicBoolean serverReleased = new AtomicBoolean();
        HttpServer local = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        local.createContext("/parse", exchange -> {
            try {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write('{');
                exchange.getResponseBody().flush();
                initialBodyFlushed.countDown();
                releaseServer.await(1_500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                serverReleased.set(true);
                exchange.close();
            }
        });
        local.start();
        try {
            DocumentParsingClient bounded = new DocumentParsingClient(RestClient.builder().build(), new ObjectMapper(),
                    "http://127.0.0.1:" + local.getAddress().getPort());
            long startedNanos = System.nanoTime();

            ParseOutcome outcome = bounded.parse(new byte[] {1}, "a.bin", "application/octet-stream", 100);
            long invocationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);

            assertThat(initialBodyFlushed.await(0, TimeUnit.MILLISECONDS)).isTrue();
            assertThat(outcome.kind()).isEqualTo(ParseOutcomeKind.TIMEOUT);
            assertThat(serverReleased).as("parser must return while the server deliberately holds the body open")
                    .isFalse();
            assertThat(invocationMillis).as("parse invocation only").isLessThan(700);
        } finally {
            releaseServer.countDown();
            local.stop(0);
        }
    }

    @Test
    void boundedBodySubscriberCancelsTheUnderlyingSubscriptionAtTheDeadline() throws Exception {
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
        scheduler.setRemoveOnCancelPolicy(true);
        RecordingSubscription subscription = new RecordingSubscription();
        try {
            HttpResponse.BodySubscriber<byte[]> subscriber = DocumentParsingClient.limitedByteArraySubscriber(
                    1_024, System.nanoTime(), 80, scheduler);

            subscriber.onSubscribe(subscription);

            assertThat(subscription.cancelled.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(subscription.cancelCalls).isEqualTo(1);
            assertThat(subscriber.getBody().toCompletableFuture()).isCompletedExceptionally();
            assertThat(scheduler.getQueue()).isEmpty();
        } finally {
            scheduler.shutdownNow();
            assertThat(scheduler.awaitTermination(1, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void boundedBodySubscriberRemovesItsDeadlineWorkAfterNormalCompletion() throws Exception {
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
        scheduler.setRemoveOnCancelPolicy(true);
        RecordingSubscription subscription = new RecordingSubscription();
        try {
            HttpResponse.BodySubscriber<byte[]> subscriber = DocumentParsingClient.limitedByteArraySubscriber(
                    1_024, System.nanoTime(), 5_000, scheduler);
            subscriber.onSubscribe(subscription);
            subscriber.onNext(List.of(ByteBuffer.wrap(new byte[] {1, 2, 3})));
            subscriber.onComplete();

            assertThat(subscriber.getBody().toCompletableFuture().get(1, TimeUnit.SECONDS))
                    .containsExactly(1, 2, 3);
            assertThat(subscription.cancelCalls).isZero();
            assertThat(scheduler.getQueue()).isEmpty();
        } finally {
            scheduler.shutdownNow();
            assertThat(scheduler.awaitTermination(1, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void boundedParseCancelsAStalledMultipartUpload() throws Exception {
        HttpServer local = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        local.createContext("/parse", exchange -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        local.start();
        try {
            DocumentParsingClient bounded = new DocumentParsingClient(RestClient.builder().build(), new ObjectMapper(),
                    "http://127.0.0.1:" + local.getAddress().getPort());
            byte[] largeBody = new byte[20 * 1024 * 1024];
            Arrays.fill(largeBody, (byte) 'x');
            long started = System.nanoTime();

            ParseOutcome outcome = bounded.parse(largeBody, "large.txt", "text/plain", 120);

            assertThat(outcome.kind()).isEqualTo(ParseOutcomeKind.TIMEOUT);
            assertThat((System.nanoTime() - started) / 1_000_000L).isLessThan(800);
        } finally {
            local.stop(0);
        }
    }

    @Test
    void unrecognizedOutcomeValueFailsClosed() {
        setUp();
        server.expect(requestTo("http://ai-service.internal/parse"))
                .andRespond(withSuccess("""
                        {"outcome": "SOMETHING_NEW", "reason": "future value"}
                        """, MediaType.APPLICATION_JSON));

        ParseOutcome outcome = client.parse("x".getBytes(), "a.txt", "text/plain");

        assertThat(outcome.kind()).isEqualTo(ParseOutcomeKind.FAILED);
    }

    @Test
    void serverErrorResponseIsTranslatedToFailureWithoutExposingRawException() {
        setUp();
        server.expect(requestTo("http://ai-service.internal/parse"))
                .andRespond(withServerError());

        ParseOutcome outcome = client.parse("x".getBytes(), "a.txt", "text/plain");

        assertThat(outcome.kind()).isEqualTo(ParseOutcomeKind.FAILED);
        assertThat(outcome.reason()).isEqualTo("AI service call failed");
    }

    @Test
    void connectionFailureIsTranslatedToFailureOutcome() {
        setUp();
        server.expect(requestTo("http://ai-service.internal/parse"))
                .andRespond(request -> {
                    throw new IOException("simulated connection refused");
                });

        ParseOutcome outcome = client.parse("x".getBytes(), "a.txt", "text/plain");

        assertThat(outcome.kind()).isEqualTo(ParseOutcomeKind.FAILED);
        assertThat(outcome.reason()).isEqualTo("AI service call failed");
    }

    // ------------------------------------------------------------------
    // M17 재현 회귀 - 실제 감사(SUMMARIZE 분류 성공 -> RAG_CANDIDATE_RETRIEVAL
    // FAILURE/NOT_AVAILABLE)의 원인이 embedQuery(text, deadlineMs)(Budget-aware
    // 경로) 어느 단계인지 구분한다. 실제 운영 3-인자(@Autowired) 생성자, 실제
    // Jackson ObjectMapper(tools.jackson), 실제 boundedHttpClient/HTTP Body
    // Subscriber를 그대로 쓴다 - embedQuery 자체를 Mock 처리하지 않는다(위
    // /parse 테스트들과 동일한 기존 관례, 127.0.0.1 loopback 실제 소켓).
    // ------------------------------------------------------------------

    @Test
    void boundedEmbedQuerySuccessfullyRoundTripsARealLoopbackResponseWithDeclaredDimensions() throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> capturedRequestBody = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> capturedContentType = new java.util.concurrent.atomic.AtomicReference<>();
        HttpServer local = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        local.createContext("/embed-query", exchange -> {
            try {
                capturedRequestBody.set(new String(exchange.getRequestBody().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8));
                capturedContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
                byte[] response = ("{\"outcome\":\"SUCCESS\",\"embedding\":" + embeddingJsonArray(1024)
                        + ",\"embeddingModel\":\"bge-m3:567m\",\"embeddingDimensions\":1024}").getBytes();
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } finally {
                exchange.close();
            }
        });
        local.start();
        try {
            DocumentParsingClient bounded = new DocumentParsingClient(RestClient.builder().build(), new ObjectMapper(),
                    "http://127.0.0.1:" + local.getAddress().getPort());

            QueryEmbeddingOutcome outcome = bounded.embedQuery("synthetic diagnostic query", 5_000);

            assertThat(outcome.success()).isTrue();
            assertThat(outcome.embedding()).hasSize(1024);
            // 요청 직렬화 단계 - 실제 Java 요청 Body/Content-Type이 Python DTO(text 필드,
            // application/json)와 정확히 맞는지 확인한다(질의 원문 자체는 이 합성 문장뿐이다).
            assertThat(capturedContentType.get()).isEqualTo("application/json");
            assertThat(capturedRequestBody.get()).isEqualTo("{\"text\":\"synthetic diagnostic query\"}");
        } finally {
            local.stop(0);
        }
    }

    /**
     * M17 진단 교정 - 실제 비교 호출로 확인된 사실(추정이 아니다): 같은 합성 요청을
     * 이 Client와 같은 기본 설정(버전 미지정, HttpClient 기본값 HTTP_2)의
     * {@link java.net.http.HttpClient}로 실제 Python AI 서비스({@code
     * http://127.0.0.1:8000/embed-query})에 보내면 422가 돌아왔고, {@code
     * .version(HttpClient.Version.HTTP_1_1)}을 명시하면 200으로 성공했다 -
     * {@link DocumentParsingClient}의 운영용 생성자가 만드는 {@code
     * boundedHttpClient}에 정확히 이 설정을 고정했다(이 Class Javadoc 참고).
     *
     * <p>이 Test는 {@code com.sun.net.httpserver.HttpServer}(이 Test만의 최소
     * loopback 서버, 실제 Python AI 서비스와 다른 구현이다)를 쓰므로 실제 422
     * 자체를 재현하지 않는다 - 재현할 수 있는 것은 오직 "이 운영 Client가 실제로
     * 무엇을 내보내는가"뿐이다: 요청 Protocol 줄이 HTTP/1.1이고, {@code
     * Upgrade}/{@code HTTP2-Settings} 헤더나 {@code Connection: Upgrade}로
     * HTTP/2(h2c) 업그레이드를 시도하는 흔적이 전혀 없다는 것을 실제로 관찰해
     * 확인한다. 422가 정확히 왜 발생했는지(h2c 헤더를 Python/Uvicorn/Starlette
     * 스택의 어느 계층이 어떻게 처리해서인지)는 확인하지 않았다 - 과장하지
     * 않는다.</p>
     */
    @Test
    void boundedEmbedQuerySendsHttp1_1WithoutAttemptingAnHttp2Upgrade() throws Exception {
        AtomicReference<String> capturedProtocol = new AtomicReference<>();
        AtomicReference<String> capturedUpgradeHeader = new AtomicReference<>();
        AtomicReference<String> capturedHttp2SettingsHeader = new AtomicReference<>();
        AtomicReference<String> capturedConnectionHeader = new AtomicReference<>();
        HttpServer local = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        local.createContext("/embed-query", exchange -> {
            try {
                capturedProtocol.set(exchange.getProtocol());
                capturedUpgradeHeader.set(exchange.getRequestHeaders().getFirst("Upgrade"));
                capturedHttp2SettingsHeader.set(exchange.getRequestHeaders().getFirst("HTTP2-Settings"));
                capturedConnectionHeader.set(exchange.getRequestHeaders().getFirst("Connection"));
                exchange.getRequestBody().readAllBytes();
                byte[] response = ("{\"outcome\":\"SUCCESS\",\"embedding\":" + embeddingJsonArray(1024)
                        + ",\"embeddingModel\":\"bge-m3:567m\",\"embeddingDimensions\":1024}").getBytes();
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } finally {
                exchange.close();
            }
        });
        local.start();
        try {
            DocumentParsingClient bounded = new DocumentParsingClient(RestClient.builder().build(), new ObjectMapper(),
                    "http://127.0.0.1:" + local.getAddress().getPort());

            QueryEmbeddingOutcome outcome = bounded.embedQuery("q", 5_000);

            assertThat(outcome.success()).isTrue();
            assertThat(capturedProtocol.get()).isEqualTo("HTTP/1.1");
            assertThat(capturedUpgradeHeader.get()).as("no h2c Upgrade header").isNull();
            assertThat(capturedHttp2SettingsHeader.get()).as("no HTTP2-Settings header").isNull();
            assertThat(capturedConnectionHeader.get()).as("no Connection: Upgrade negotiation attempt")
                    .satisfies(value -> assertThat(value == null || !value.toLowerCase(java.util.Locale.ROOT).contains("upgrade"))
                            .isTrue());
        } finally {
            local.stop(0);
        }
    }

    /** 같은 {@code boundedHttpClient}를 쓰는 {@code /parse} 경로도 동일하게 HTTP/1.1을 보낸다. */
    @Test
    void boundedParseAlsoSendsHttp1_1WithoutAttemptingAnHttp2Upgrade() throws Exception {
        AtomicReference<String> capturedProtocol = new AtomicReference<>();
        AtomicReference<String> capturedUpgradeHeader = new AtomicReference<>();
        HttpServer local = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        local.createContext("/parse", exchange -> {
            try {
                capturedProtocol.set(exchange.getProtocol());
                capturedUpgradeHeader.set(exchange.getRequestHeaders().getFirst("Upgrade"));
                exchange.getRequestBody().readAllBytes();
                byte[] response = "{\"outcome\":\"UNSUPPORTED_FORMAT\",\"reason\":\"unsupported\"}".getBytes();
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } finally {
                exchange.close();
            }
        });
        local.start();
        try {
            DocumentParsingClient bounded = new DocumentParsingClient(RestClient.builder().build(), new ObjectMapper(),
                    "http://127.0.0.1:" + local.getAddress().getPort());

            ParseOutcome outcome = bounded.parse(new byte[] {1}, "a.bin", "application/octet-stream", 5_000);

            assertThat(outcome.kind()).isEqualTo(ParseOutcomeKind.UNSUPPORTED_FORMAT);
            assertThat(capturedProtocol.get()).isEqualTo("HTTP/1.1");
            assertThat(capturedUpgradeHeader.get()).as("no h2c Upgrade header").isNull();
        } finally {
            local.stop(0);
        }
    }

    @Test
    void boundedEmbedQueryTreatsANonTwoHundredStatusAsFailureWithoutExposingRawException() throws Exception {
        HttpServer local = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        local.createContext("/embed-query", exchange -> {
            try {
                byte[] response = "internal error".getBytes();
                exchange.sendResponseHeaders(500, response.length);
                exchange.getResponseBody().write(response);
            } finally {
                exchange.close();
            }
        });
        local.start();
        try {
            DocumentParsingClient bounded = new DocumentParsingClient(RestClient.builder().build(), new ObjectMapper(),
                    "http://127.0.0.1:" + local.getAddress().getPort());

            QueryEmbeddingOutcome outcome = bounded.embedQuery("q", 5_000);

            assertThat(outcome.success()).isFalse();
            assertThat(outcome.reason()).isEqualTo("AI service call failed");
        } finally {
            local.stop(0);
        }
    }

    /** Python이 실제로 반환하는 정확한 문구(app/api/routes.py의 embed_query)로 FAILED 단계를 재현한다. */
    @Test
    void boundedEmbedQueryTreatsAPythonDeclaredFailedOutcomeAsFailureNotAConnectionProblem() throws Exception {
        HttpServer local = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        local.createContext("/embed-query", exchange -> {
            try {
                byte[] response = ("{\"outcome\":\"FAILED\",\"reason\":"
                        + "\"embedding provider unavailable or returned a malformed response\"}").getBytes();
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } finally {
                exchange.close();
            }
        });
        local.start();
        try {
            DocumentParsingClient bounded = new DocumentParsingClient(RestClient.builder().build(), new ObjectMapper(),
                    "http://127.0.0.1:" + local.getAddress().getPort());

            QueryEmbeddingOutcome outcome = bounded.embedQuery("q", 5_000);

            assertThat(outcome.success()).isFalse();
            assertThat(outcome.reason())
                    .isEqualTo("embedding provider unavailable or returned a malformed response");
        } finally {
            local.stop(0);
        }
    }

    @Test
    void boundedEmbedQueryTreatsAMalformedResponseBodyAsFailureAtTheParsingStage() throws Exception {
        HttpServer local = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        local.createContext("/embed-query", exchange -> {
            try {
                byte[] response = "not valid json{{{".getBytes();
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } finally {
                exchange.close();
            }
        });
        local.start();
        try {
            DocumentParsingClient bounded = new DocumentParsingClient(RestClient.builder().build(), new ObjectMapper(),
                    "http://127.0.0.1:" + local.getAddress().getPort());

            QueryEmbeddingOutcome outcome = bounded.embedQuery("q", 5_000);

            assertThat(outcome.success()).isFalse();
            assertThat(outcome.reason()).isEqualTo("AI service call failed");
        } finally {
            local.stop(0);
        }
    }

    /** 연결 단계 - 실제로 아무것도 듣고 있지 않은 loopback 포트(방금 닫은 서버)로 접속을 시도한다. */
    @Test
    void boundedEmbedQueryTreatsAnUnreachableAddressAsFailureAtTheConnectionStage() throws Exception {
        HttpServer local = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = local.getAddress().getPort();
        local.start();
        local.stop(0);

        DocumentParsingClient bounded = new DocumentParsingClient(RestClient.builder().build(), new ObjectMapper(),
                "http://127.0.0.1:" + port);

        QueryEmbeddingOutcome outcome = bounded.embedQuery("q", 5_000);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.reason()).isEqualTo("AI service call failed");
    }

    @Test
    void boundedEmbedQueryRespectsTheOperationDeadlineForASlowServerAsATimeoutNotAGenericFailure() throws Exception {
        HttpServer local = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        local.createContext("/embed-query", exchange -> {
            try {
                Thread.sleep(250);
                byte[] response = ("{\"outcome\":\"SUCCESS\",\"embedding\":" + embeddingJsonArray(1024)
                        + ",\"embeddingModel\":\"bge-m3:567m\",\"embeddingDimensions\":1024}").getBytes();
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        local.start();
        try {
            DocumentParsingClient bounded = new DocumentParsingClient(RestClient.builder().build(), new ObjectMapper(),
                    "http://127.0.0.1:" + local.getAddress().getPort());

            QueryEmbeddingOutcome outcome = bounded.embedQuery("q", 80);

            assertThat(outcome.success()).isFalse();
            assertThat(outcome.reason()).as("a deadline expiry must be distinguishable from a generic call failure")
                    .isEqualTo("request deadline expired");
        } finally {
            local.stop(0);
        }
    }

    private static String embeddingJsonArray(int dimensions) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < dimensions; i++) {
            if (i > 0) builder.append(',');
            builder.append("0.01");
        }
        return builder.append(']').toString();
    }

    private static final class RecordingSubscription implements Flow.Subscription {
        private final CountDownLatch cancelled = new CountDownLatch(1);
        private volatile int cancelCalls;

        @Override
        public void request(long n) {
        }

        @Override
        public void cancel() {
            cancelCalls++;
            cancelled.countDown();
        }
    }
}
