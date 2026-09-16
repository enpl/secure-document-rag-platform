package com.sdv.rag.infrastructure.ai;

import com.sdv.rag.domain.ParseOutcome;
import com.sdv.rag.domain.ParseOutcomeKind;
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
