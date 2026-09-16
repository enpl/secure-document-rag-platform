package com.sdv.rag.infrastructure.ai;

import com.sdv.rag.domain.EmbeddingChunk;
import com.sdv.rag.domain.ExtractedLocation;
import com.sdv.rag.domain.ExtractedChunk;
import com.sdv.rag.domain.IndexOutcome;
import com.sdv.rag.domain.LocatorType;
import com.sdv.rag.domain.ParseOutcome;
import com.sdv.rag.domain.ParseOutcomeKind;
import com.sdv.rag.domain.QueryEmbeddingOutcome;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

/**
 * F-BE-108(경로/이름은 Manifest 확정, M06이 구현, M11이 {@code /index}를 추가).
 * Python AI Service(F-AI-001/002/003/M11 신규 F-AI-index)의 {@code /parse}와
 * {@code /index}를 호출한다.
 *
 * <p>고정된 운영자 설정 내부 주소만 사용한다({@code sdv.ai-service.url} - 이미
 * {@code application-local.yml}/{@code application-compose.yml}에 존재하는 설정,
 * 새로 추가하지 않았다, {@link AiServiceRestClientConfig} 참고) - 요청이 URL을
 * 고르지 않는다. Parser는 Byte/Metadata만 받는다 - 로컬 경로나 임의 URL을 절대
 * 넘기지 않는다.</p>
 *
 * <p>Java는 Parsing을 직접 하지 않는다(병렬 구현 금지) - 이 Client는 순수 HTTP
 * 왕복과 응답을 {@link ParseOutcome}으로 매핑하는 얇은 계층이다. 형식 검증/MIME
 * 대조/파싱/정규화는 전부 Python 쪽 책임이다.</p>
 */
@Component
public class DocumentParsingClient {

    private static final int MAX_PARSE_RESPONSE_BYTES = 30_000_000;
    private static final ScheduledThreadPoolExecutor BODY_DEADLINE_SCHEDULER = bodyDeadlineScheduler();
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final URI serviceUri;
    private final HttpClient boundedHttpClient;

    /**
     * M08 후속 교정 - {@code @Qualifier}: {@code GoogleDriveRestClientConfig}가
     * 두 번째 {@link RestClient} Bean({@code googleDriveRestClient})을 추가하면서,
     * 이 생성자의 원래 Qualifier 없는 주입이 더 이상 결정적이지 않게 됐다(Bean이
     * 2개면 Spring이 고를 수 없다) - 동작은 그대로 두고 어떤 Bean을 원하는지만
     * 명시한다.
     */
    public DocumentParsingClient(@Qualifier("aiServiceRestClient") RestClient restClient) {
        this.restClient = restClient;
        this.objectMapper = null;
        this.serviceUri = null;
        this.boundedHttpClient = null;
    }

    /** M12 uses a request-scoped transport; the shared indexing client remains unchanged. */
    @Autowired
    public DocumentParsingClient(@Qualifier("aiServiceRestClient") RestClient restClient, ObjectMapper objectMapper,
            @Value("${sdv.ai-service.url:http://localhost:8000}") String serviceUrl) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.serviceUri = URI.create(serviceUrl);
        this.boundedHttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    /**
     * 이미 Fetch된 원본 Byte를 Python {@code /parse}로 전송한다. {@code declaredMimeType}은
     * Source가 보고한 값일 뿐 증거가 아니다(Python이 실제 내용과 대조한다).
     * 네트워크/Timeout/5xx 등 호출 자체가 실패하면 {@link ParseOutcomeKind#FAILED}로
     * 변환해 반환한다(예외를 호출자에게 그대로 노출하지 않는다) - 원본 예외 메시지는
     * 로그/감사에 남기지 않는다.
     */
    public ParseOutcome parse(byte[] content, String fileName, String declaredMimeType) {
        return invokeParse(content, fileName, declaredMimeType, 0L);
    }

    /** M12-only bounded overload; indexing keeps its existing independent contract. */
    public ParseOutcome parse(byte[] content, String fileName, String declaredMimeType, long deadlineMs) {
        if (deadlineMs <= 0) {
            return ParseOutcome.failure(ParseOutcomeKind.FAILED, "request deadline expired");
        }
        return invokeParse(content, fileName, declaredMimeType, deadlineMs);
    }

    private ParseOutcome invokeParse(byte[] content, String fileName, String declaredMimeType, long timeoutMs) {
        if (timeoutMs > 0 && serviceUri != null) {
            return invokeBoundedParse(content, fileName, declaredMimeType, timeoutMs);
        }
        long startedNanos = timeoutMs == 0 ? 0 : System.nanoTime();
        try {
            ParseResponse response = restClient.post()
                    .uri("/parse")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(multipartBody(content, fileName, declaredMimeType))
                    .retrieve()
                    .body(ParseResponse.class);
            return timeoutMs == 0 || elapsedMillis(startedNanos) < timeoutMs ? toOutcome(response)
                    : ParseOutcome.failure(ParseOutcomeKind.FAILED, "request deadline expired");
        } catch (RestClientException e) {
            return ParseOutcome.failure(ParseOutcomeKind.FAILED, "AI service call failed");
        }
    }

    private ParseOutcome invokeBoundedParse(byte[] content, String fileName, String declaredMimeType, long timeoutMs) {
        long startedNanos = System.nanoTime();
        try {
            String boundary = "M12-" + UUID.randomUUID();
            byte[] request = multipartBytes(content, fileName, declaredMimeType, boundary);
            long remainingMs = remainingMillis(startedNanos, timeoutMs);
            if (remainingMs <= 0) {
                return ParseOutcome.failure(ParseOutcomeKind.TIMEOUT, "request deadline expired");
            }
            HttpRequest httpRequest = HttpRequest.newBuilder(serviceUri.resolve("/parse"))
                    .timeout(Duration.ofMillis(remainingMs))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(request))
                    .build();
            HttpResponse<byte[]> response = boundedHttpClient.send(httpRequest,
                    limitedByteArrayHandler(MAX_PARSE_RESPONSE_BYTES, startedNanos, timeoutMs));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return ParseOutcome.failure(ParseOutcomeKind.FAILED, "AI service call failed");
            }
            if (remainingMillis(startedNanos, timeoutMs) <= 0) {
                return ParseOutcome.failure(ParseOutcomeKind.TIMEOUT, "request deadline expired");
            }
            return toOutcome(objectMapper.readValue(response.body(), ParseResponse.class));
        } catch (HttpTimeoutException e) {
            return ParseOutcome.failure(ParseOutcomeKind.TIMEOUT, "request deadline expired");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ParseOutcome.failure(ParseOutcomeKind.FAILED, "AI service call failed");
        } catch (IOException | RuntimeException e) {
            if (remainingMillis(startedNanos, timeoutMs) <= 0) {
                return ParseOutcome.failure(ParseOutcomeKind.TIMEOUT, "request deadline expired");
            }
            return ParseOutcome.failure(ParseOutcomeKind.FAILED, "AI service call failed");
        }
    }

    private static HttpResponse.BodyHandler<byte[]> limitedByteArrayHandler(int maxBytes, long startedNanos,
            long timeoutMs) {
        return ignored -> limitedByteArraySubscriber(maxBytes, startedNanos, timeoutMs, BODY_DEADLINE_SCHEDULER);
    }

    static HttpResponse.BodySubscriber<byte[]> limitedByteArraySubscriber(int maxBytes, long startedNanos,
            long timeoutMs, ScheduledExecutorService scheduler) {
        return new DeadlineLimitedBodySubscriber(maxBytes, startedNanos, timeoutMs, scheduler);
    }

    private static ScheduledThreadPoolExecutor bodyDeadlineScheduler() {
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "sdv-parser-body-deadline");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    private static final class DeadlineLimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final int maxBytes;
        private final long startedNanos;
        private final long timeoutNanos;
        private final ScheduledExecutorService scheduler;
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
        private final AtomicReference<ScheduledFuture<?>> deadlineTask = new AtomicReference<>();
        private final AtomicBoolean terminal = new AtomicBoolean();

        private DeadlineLimitedBodySubscriber(int maxBytes, long startedNanos, long timeoutMs,
                ScheduledExecutorService scheduler) {
            this.maxBytes = maxBytes;
            this.startedNanos = startedNanos;
            this.timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            this.scheduler = scheduler;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription nextSubscription) {
            if (!subscription.compareAndSet(null, nextSubscription)) {
                nextSubscription.cancel();
                return;
            }
            long remainingNanos = timeoutNanos - (System.nanoTime() - startedNanos);
            if (remainingNanos <= 0) {
                timeout();
                return;
            }
            ScheduledFuture<?> task = scheduler.schedule(this::timeout, remainingNanos, TimeUnit.NANOSECONDS);
            deadlineTask.set(task);
            if (terminal.get()) {
                cancelDeadlineTask();
                return;
            }
            nextSubscription.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (terminal.get()) {
                return;
            }
            try {
                for (ByteBuffer buffer : buffers) {
                    int length = buffer.remaining();
                    if (output.size() > maxBytes - length) {
                        fail(new IOException("parse response exceeded configured limit"), true);
                        return;
                    }
                    byte[] bytes = new byte[length];
                    buffer.get(bytes);
                    output.write(bytes, 0, bytes.length);
                }
                if (!terminal.get()) {
                    subscription.get().request(1);
                }
            } catch (RuntimeException e) {
                fail(e, true);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            fail(throwable, false);
        }

        @Override
        public void onComplete() {
            if (terminal.compareAndSet(false, true)) {
                cancelDeadlineTask();
                result.complete(output.toByteArray());
            }
        }

        private void timeout() {
            fail(new HttpTimeoutException("request deadline expired"), true);
        }

        private void fail(Throwable failure, boolean cancelSubscription) {
            if (!terminal.compareAndSet(false, true)) {
                return;
            }
            if (cancelSubscription) {
                Flow.Subscription current = subscription.get();
                if (current != null) {
                    current.cancel();
                }
            }
            cancelDeadlineTask();
            result.completeExceptionally(failure);
        }

        private void cancelDeadlineTask() {
            ScheduledFuture<?> task = deadlineTask.getAndSet(null);
            if (task != null) {
                task.cancel(false);
            }
        }
    }

    private static byte[] multipartBytes(byte[] content, String fileName, String declaredMimeType, String boundary)
            throws IOException {
        try (ByteArrayOutputStream body = new ByteArrayOutputStream()) {
            String prefix = "--" + boundary + "\r\n";
            body.write((prefix + "Content-Disposition: form-data; name=\"file\"; filename=\""
                    + safeFilename(fileName) + "\"\r\nContent-Type: application/octet-stream\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            body.write(content);
            body.write(("\r\n" + prefix + "Content-Disposition: form-data; name=\"declaredMimeType\"\r\n\r\n"
                    + (declaredMimeType == null ? "" : declaredMimeType) + "\r\n--" + boundary + "--\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            return body.toByteArray();
        }
    }

    private static String safeFilename(String value) {
        return value == null ? "file" : value.replace("\"", "_").replace("\r", "_").replace("\n", "_");
    }

    private static long remainingMillis(long startedNanos, long timeoutMs) {
        long elapsed = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
        return timeoutMs - elapsed;
    }

    /**
     * {@code MultipartBodyBuilder}(Reactive Stack, {@code reactor-core} 필요)
     * 대신 {@link RestClient}(Servlet Stack)와 함께 쓰는 표준 비Reactive
     * 방식인 {@link MultiValueMap}을 직접 구성한다 - 등록된
     * {@code FormHttpMessageConverter}가 이 형태를 {@code multipart/form-data}로
     * 직렬화한다.
     */
    private static MultiValueMap<String, Object> multipartBody(byte[] content, String fileName,
            String declaredMimeType) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return fileName;
            }
        });
        parts.add("declaredMimeType", declaredMimeType == null ? "" : declaredMimeType);
        return parts;
    }

    private static ParseOutcome toOutcome(ParseResponse response) {
        if (response == null || response.outcome() == null) {
            return ParseOutcome.failure(ParseOutcomeKind.FAILED, "empty parse response");
        }
        ParseOutcomeKind kind = parseKind(response.outcome());
        if (kind == ParseOutcomeKind.SUCCESS) {
            List<ExtractedLocation> locations = response.locations() == null
                    ? List.of()
                    : response.locations().stream().map(ParsedLocation::toDomain).toList();
            List<ExtractedChunk> chunks = response.chunks() == null
                    ? List.of()
                    : response.chunks().stream().map(ParsedChunk::toDomain).toList();
            if (response.chunkingVersion() == null || response.embeddingModel() == null) {
                return ParseOutcome.failure(ParseOutcomeKind.FAILED, "malformed successful parse response");
            }
            return ParseOutcome.success(response.parserName(), response.parserVersion(),
                    response.normalizationVersion(), response.normalizedText(), locations, response.chunkingVersion(),
                    response.embeddingModel(), chunks);
        }
        String reason = response.reason() == null ? "unspecified" : response.reason();
        return ParseOutcome.failure(kind, reason);
    }

    private static ParseOutcomeKind parseKind(String rawOutcome) {
        try {
            return ParseOutcomeKind.valueOf(rawOutcome);
        } catch (IllegalArgumentException e) {
            // 인식 불가 값 - Fail Closed로 실패 취급한다.
            return ParseOutcomeKind.FAILED;
        }
    }

    /**
     * M11 신규 - 이미 Fetch된 원본 Byte를 Python {@code /index}로 전송해 Parse+Chunk+
     * Embed 전체 Pipeline을 한 번의 호출로 수행한다. 응답에는 평문 Chunk Text가 전혀
     * 담기지 않는다 - Embedding Vector/일반화된 Locator/Content HMAC/Version 메타데이터
     * 뿐이다({@link IndexOutcome} Class Javadoc 참고). {@code /parse}와 동일하게
     * 네트워크/Timeout/5xx 등 호출 자체의 실패는 {@link ParseOutcomeKind#FAILED}로
     * 변환한다 - 원본 예외 메시지는 절대 옮기지 않는다.
     */
    public IndexOutcome index(byte[] content, String fileName, String declaredMimeType) {
        try {
            IndexResponse response = restClient.post()
                    .uri("/index")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(multipartBody(content, fileName, declaredMimeType))
                    .retrieve()
                    .body(IndexResponse.class);
            return toIndexOutcome(response);
        } catch (RestClientException e) {
            return IndexOutcome.failure(ParseOutcomeKind.FAILED, "AI service call failed");
        }
    }

    private static IndexOutcome toIndexOutcome(IndexResponse response) {
        if (response == null || response.outcome() == null) {
            return IndexOutcome.failure(ParseOutcomeKind.FAILED, "empty index response");
        }
        ParseOutcomeKind kind = parseKind(response.outcome());
        if (kind != ParseOutcomeKind.SUCCESS) {
            String reason = response.reason() == null ? "unspecified" : response.reason();
            return IndexOutcome.failure(kind, reason);
        }
        if (response.chunks() == null || response.chunks().isEmpty() || response.parserVersion() == null
                || response.chunkingVersion() == null || response.embeddingModel() == null) {
            return IndexOutcome.failure(ParseOutcomeKind.FAILED, "malformed successful index response");
        }
        List<EmbeddingChunk> chunks;
        try {
            chunks = response.chunks().stream().map(IndexChunkResponse::toDomain).toList();
        } catch (RuntimeException malformed) {
            // 예: 인식 불가 locatorType, 빈 embedding - 원본 예외 메시지를 옮기지 않는다.
            return IndexOutcome.failure(ParseOutcomeKind.FAILED, "malformed chunk in index response");
        }
        return IndexOutcome.success(response.parserVersion(), response.chunkingVersion(), response.embeddingModel(),
                chunks);
    }

    /**
     * M12 신규(F-AI-embed-query) - Vector Candidate 검색을 위한 질의 문장 하나를
     * 실제 Local Embedding Provider(Ollama, {@code bge-m3:567m})로 변환한다
     * (Placeholder Vector 없음 - 운영 호출자가 임의 Vector를 직접 만들어 넘길
     * 필요가 없다). 질의 원문은 이 호출의 요청 Body로만 전송되며, 응답/예외
     * 어디에도 남기지 않는다 - 네트워크/Timeout/5xx 등 호출 자체의 실패는
     * {@link QueryEmbeddingOutcome#failure(String)}로 변환한다(원본 예외
     * 메시지를 옮기지 않는다).
     */
    public QueryEmbeddingOutcome embedQuery(String text) {
        try {
            EmbedQueryResponse response = restClient.post()
                    .uri("/embed-query")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new EmbedQueryRequest(text))
                    .retrieve()
                    .body(EmbedQueryResponse.class);
            return toQueryEmbeddingOutcome(response);
        } catch (RestClientException e) {
            return QueryEmbeddingOutcome.failure("AI service call failed");
        }
    }

    /** Budget-aware M13/M14 overload. The same timer covers upload, headers and body consumption. */
    public QueryEmbeddingOutcome embedQuery(String text, long deadlineMs) {
        if (deadlineMs <= 0 || serviceUri == null || objectMapper == null || boundedHttpClient == null) {
            return QueryEmbeddingOutcome.failure("request deadline expired");
        }
        long startedNanos = System.nanoTime();
        try {
            byte[] request = objectMapper.writeValueAsBytes(new EmbedQueryRequest(text));
            long remainingMs = remainingMillis(startedNanos, deadlineMs);
            if (remainingMs <= 0) return QueryEmbeddingOutcome.failure("request deadline expired");
            HttpRequest httpRequest = HttpRequest.newBuilder(serviceUri.resolve("/embed-query"))
                    .timeout(Duration.ofMillis(remainingMs))
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(request)).build();
            HttpResponse<byte[]> response = boundedHttpClient.send(httpRequest,
                    limitedByteArrayHandler(1_000_000, startedNanos, deadlineMs));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return QueryEmbeddingOutcome.failure("AI service call failed");
            }
            if (remainingMillis(startedNanos, deadlineMs) <= 0) {
                return QueryEmbeddingOutcome.failure("request deadline expired");
            }
            return toQueryEmbeddingOutcome(objectMapper.readValue(response.body(), EmbedQueryResponse.class));
        } catch (HttpTimeoutException e) {
            return QueryEmbeddingOutcome.failure("request deadline expired");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return QueryEmbeddingOutcome.failure("request deadline expired");
        } catch (IOException | RuntimeException e) {
            return remainingMillis(startedNanos, deadlineMs) <= 0
                    ? QueryEmbeddingOutcome.failure("request deadline expired")
                    : QueryEmbeddingOutcome.failure("AI service call failed");
        }
    }

    private static QueryEmbeddingOutcome toQueryEmbeddingOutcome(EmbedQueryResponse response) {
        if (response == null || !"SUCCESS".equals(response.outcome())) {
            return QueryEmbeddingOutcome.failure(
                    response == null || response.reason() == null ? "empty embed-query response" : response.reason());
        }
        if (response.embedding() == null || response.embedding().isEmpty()) {
            return QueryEmbeddingOutcome.failure("malformed successful embed-query response");
        }
        float[] embedding = new float[response.embedding().size()];
        for (int i = 0; i < embedding.length; i++) {
            Double value = response.embedding().get(i);
            if (value == null || !Double.isFinite(value)) {
                return QueryEmbeddingOutcome.failure("malformed successful embed-query response");
            }
            embedding[i] = value.floatValue();
        }
        return QueryEmbeddingOutcome.success(embedding);
    }

    /** {@code /embed-query} 요청 JSON 매핑 전용 - 패키지 밖으로 노출하지 않는다. */
    record EmbedQueryRequest(String text) {
    }

    /** {@code /embed-query} 응답 JSON 매핑 전용 - 패키지 밖으로 노출하지 않는다. */
    record EmbedQueryResponse(String outcome, List<Double> embedding, String embeddingModel,
            Integer embeddingDimensions, String reason) {
    }

    /** {@code /index} 응답 JSON 매핑 전용 - 패키지 밖으로 노출하지 않는다. */
    record IndexResponse(String outcome, String parserVersion, String chunkingVersion, String embeddingModel,
            Integer embeddingDimensions, List<IndexChunkResponse> chunks, String reason) {
    }

    record IndexChunkResponse(int chunkIndex, String locatorType, String locatorValue, List<Double> embedding,
            String contentHmac) {
        EmbeddingChunk toDomain() {
            List<Float> floats = embedding == null ? List.of()
                    : embedding.stream().map(Double::floatValue).toList();
            return new EmbeddingChunk(chunkIndex, LocatorType.valueOf(locatorType), locatorValue, floats,
                    contentHmac);
        }
    }

    /** {@code /parse} 응답 JSON 매핑 전용 - 패키지 밖으로 노출하지 않는다. */
    record ParseResponse(String outcome, String parserName, String parserVersion, String normalizationVersion,
            String normalizedText, List<ParsedLocation> locations, String chunkingVersion, String embeddingModel,
            List<ParsedChunk> chunks, String reason) {
    }

    record ParsedLocation(String locatorType, String locatorValue, int startOffset, int endOffset) {
        ExtractedLocation toDomain() {
            return new ExtractedLocation(LocatorType.valueOf(locatorType), locatorValue, startOffset, endOffset);
        }
    }

    record ParsedChunk(int chunkIndex, String locatorType, String locatorValue, int startOffset, int endOffset) {
        ExtractedChunk toDomain() {
            return new ExtractedChunk(chunkIndex, LocatorType.valueOf(locatorType), locatorValue, startOffset,
                    endOffset);
        }
    }

    private static long elapsedMillis(long startedNanos) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }
}
