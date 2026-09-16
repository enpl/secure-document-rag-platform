package com.sdv.ai.infrastructure;

import com.sdv.ai.application.AssistantIntent;
import com.sdv.ai.application.AssistantProperties;
import com.sdv.ai.application.StructuredOutputContract;
import com.sdv.ai.application.port.LlmPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Local Ollama HTTP adapter. No call is made unless the assistant feature is explicitly enabled. */
@Component
public class OllamaLlmAdapter implements LlmPort {
    private static final int MAX_RESPONSE_BYTES = 2_000_000;
    private static final ScheduledThreadPoolExecutor DEADLINES = scheduler();
    private final ObjectMapper mapper;
    private final URI endpoint;
    private final HttpClient client;
    private final int contextTokens;
    private final int outputTokens;
    private final int maxClaims;

    @Autowired
    public OllamaLlmAdapter(ObjectMapper mapper, AssistantProperties properties,
            @Value("${sdv.rag.assistant.ollama-url:${sdv.ollama.base-url:http://localhost:11434}}") String baseUrl) {
        this(mapper, URI.create(baseUrl), HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                properties.contextTokens(), properties.reservedOutputTokens(), properties.maxEvidenceSpans());
    }

    OllamaLlmAdapter(ObjectMapper mapper, URI baseUri, HttpClient client) {
        this(mapper, baseUri, client, 8192, 1024, 10);
    }

    OllamaLlmAdapter(ObjectMapper mapper, URI baseUri, HttpClient client, int contextTokens, int outputTokens,
            int maxClaims) {
        this.mapper = mapper;
        this.endpoint = baseUri.resolve("/api/generate");
        this.client = client;
        this.contextTokens = contextTokens;
        this.outputTokens = outputTokens;
        this.maxClaims = maxClaims;
    }

    @Override
    public ClassificationResult classify(String question, String model, long deadlineMillis) {
        try {
            String prompt = mapper.writeValueAsString(Map.of("question", question));
            OllamaRequest payload = new OllamaRequest(model, StructuredOutputContract.CLASSIFICATION_SYSTEM, prompt,
                    false, StructuredOutputContract.classificationSchema(), new Options(contextTokens, outputTokens));
            TransportResult result = invoke(payload, deadlineMillis);
            if (result.kind() == TransportKind.TIMEOUT)
                return new ClassificationResult(ClassificationResult.Kind.TIMEOUT, null);
            if (result.kind() != TransportKind.SUCCESS)
                return new ClassificationResult(ClassificationResult.Kind.FAILED, null);
            String inner = completedResponse(result.response());
            if (inner == null) return new ClassificationResult(ClassificationResult.Kind.FAILED, null);
            Map<?, ?> object = mapper.readValue(inner, Map.class);
            if (!exactKeys(object, Set.of("intent")) || !(object.get("intent") instanceof String value))
                return new ClassificationResult(ClassificationResult.Kind.FAILED, null);
            AssistantIntent intent = AssistantIntent.valueOf(value);
            if (!Set.of(AssistantIntent.FIND_FILE, AssistantIntent.FIND_CONTENT, AssistantIntent.SUMMARIZE,
                    AssistantIntent.COMPARE, AssistantIntent.GROUNDED_ANALYSIS, AssistantIntent.OUT_OF_SCOPE,
                    AssistantIntent.POLICY_BYPASS, AssistantIntent.CLARIFICATION_REQUIRED).contains(intent))
                return new ClassificationResult(ClassificationResult.Kind.FAILED, null);
            return new ClassificationResult(ClassificationResult.Kind.SUCCESS, intent);
        } catch (RuntimeException failure) {
            return new ClassificationResult(ClassificationResult.Kind.FAILED, null);
        }
    }

    @Override
    public GenerationResult generate(GenerationRequest request, String model, long deadlineMillis) {
        try {
            String system = request.systemInstruction() == null
                    ? StructuredOutputContract.GENERATION_SYSTEM : request.systemInstruction();
            OllamaRequest payload = new OllamaRequest(model, system, request.prompt(), false,
                    StructuredOutputContract.generationSchema(maxClaims), new Options(contextTokens, outputTokens));
            TransportResult result = invoke(payload, deadlineMillis);
            if (result.kind() == TransportKind.TIMEOUT)
                return new GenerationResult(GenerationResult.Kind.TIMEOUT, List.of(), null);
            if (result.kind() != TransportKind.SUCCESS)
                return new GenerationResult(GenerationResult.Kind.FAILED, List.of(), null);
            String inner = completedResponse(result.response());
            if (inner == null) return new GenerationResult(GenerationResult.Kind.FAILED, List.of(), null);
            Map<?, ?> object = mapper.readValue(inner, Map.class);
            if (!exactKeys(object, Set.of("claims", "generatedAnalysis"))
                    || !(object.get("claims") instanceof List<?> rawClaims)
                    || rawClaims.isEmpty() || rawClaims.size() > maxClaims
                    || !(object.get("generatedAnalysis") instanceof String analysis)
                    || analysis.length() > StructuredOutputContract.MAX_ANALYSIS_CHARS) {
                return new GenerationResult(GenerationResult.Kind.FAILED, List.of(), null);
            }
            List<Claim> claims = new ArrayList<>();
            for (Object raw : rawClaims) {
                Claim claim = parseClaim(raw);
                if (claim == null) return new GenerationResult(GenerationResult.Kind.FAILED, List.of(), null);
                claims.add(claim);
            }
            return new GenerationResult(GenerationResult.Kind.SUCCESS, claims, analysis);
        } catch (RuntimeException failure) {
            return new GenerationResult(GenerationResult.Kind.FAILED, List.of(), null);
        }
    }

    private Claim parseClaim(Object raw) {
        if (!(raw instanceof Map<?, ?> claim)
                || !exactKeys(claim, Set.of("text", "evidenceLabels", "supportingQuotes"))
                || !(claim.get("text") instanceof String text) || text.isBlank()
                || text.length() > StructuredOutputContract.MAX_CLAIM_TEXT_CHARS) return null;
        List<String> labels = stringList(claim.get("evidenceLabels"), maxClaims, 12, true);
        List<String> quotes = stringList(claim.get("supportingQuotes"), maxClaims,
                StructuredOutputContract.MAX_QUOTE_CHARS, false);
        if (labels == null || quotes == null) return null;
        return new Claim(text, labels, quotes);
    }

    private static List<String> stringList(Object raw, int maxItems, int maxLength, boolean label) {
        if (!(raw instanceof List<?> values) || values.isEmpty() || values.size() > maxItems) return null;
        List<String> result = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof String text) || text.isBlank() || text.length() > maxLength
                    || (label && !text.matches("E[1-9][0-9]*"))) return null;
            result.add(text);
        }
        return List.copyOf(result);
    }

    private String completedResponse(byte[] response) {
        Map<?, ?> envelope = mapper.readValue(response, Map.class);
        if (!(envelope.get("done") instanceof Boolean done) || !done
                || !(envelope.get("response") instanceof String inner)) return null;
        Object reason = envelope.get("done_reason");
        if (reason != null && (!(reason instanceof String text) || !"stop".equals(text))) return null;
        return inner;
    }

    private static boolean exactKeys(Map<?, ?> value, Set<String> keys) {
        return value.size() == keys.size() && value.keySet().stream().allMatch(keys::contains);
    }

    private TransportResult invoke(OllamaRequest payload, long deadlineMillis) {
        if (deadlineMillis <= 0) return new TransportResult(TransportKind.TIMEOUT, null);
        long started = System.nanoTime();
        try {
            byte[] body = mapper.writeValueAsBytes(payload);
            long remaining = remaining(started, deadlineMillis);
            if (remaining <= 0) return new TransportResult(TransportKind.TIMEOUT, null);
            HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofMillis(remaining))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            HttpResponse<byte[]> response = client.send(request,
                    ignored -> deadlineSubscriber(MAX_RESPONSE_BYTES, started, deadlineMillis, DEADLINES));
            if (response.statusCode() < 200 || response.statusCode() >= 300 || remaining(started, deadlineMillis) <= 0)
                return new TransportResult(remaining(started, deadlineMillis) <= 0
                        ? TransportKind.TIMEOUT : TransportKind.FAILED, null);
            return new TransportResult(TransportKind.SUCCESS, response.body());
        } catch (HttpTimeoutException e) {
            return new TransportResult(TransportKind.TIMEOUT, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new TransportResult(TransportKind.TIMEOUT, null);
        } catch (IOException | RuntimeException e) {
            return new TransportResult(remaining(started, deadlineMillis) <= 0
                    ? TransportKind.TIMEOUT : TransportKind.FAILED, null);
        }
    }

    static HttpResponse.BodySubscriber<byte[]> deadlineSubscriber(int maxBytes, long started, long timeoutMillis,
            java.util.concurrent.ScheduledExecutorService scheduler) {
        return new DeadlineSubscriber(maxBytes, started, timeoutMillis, scheduler);
    }

    private static final class DeadlineSubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final int maxBytes;
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
        private final AtomicReference<ScheduledFuture<?>> timeoutTask = new AtomicReference<>();
        private final AtomicBoolean terminal = new AtomicBoolean();
        private final long delayMillis;
        private final java.util.concurrent.ScheduledExecutorService scheduler;

        DeadlineSubscriber(int maxBytes, long started, long timeoutMillis,
                java.util.concurrent.ScheduledExecutorService scheduler) {
            this.maxBytes = maxBytes;
            this.delayMillis = remaining(started, timeoutMillis);
            this.scheduler = scheduler;
        }
        @Override public CompletionStage<byte[]> getBody() { return body; }
        @Override public void onSubscribe(Flow.Subscription next) {
            if (!subscription.compareAndSet(null, next)) { next.cancel(); return; }
            if (delayMillis <= 0) { fail(new HttpTimeoutException("deadline"), true); return; }
            timeoutTask.set(scheduler.schedule(() -> fail(new HttpTimeoutException("deadline"), true),
                    delayMillis, TimeUnit.MILLISECONDS));
            if (!terminal.get()) next.request(1);
        }
        @Override public void onNext(List<ByteBuffer> items) {
            if (terminal.get()) return;
            try {
                for (ByteBuffer item : items) {
                    if (output.size() + item.remaining() > maxBytes) {
                        fail(new IOException("response too large"), true);
                        return;
                    }
                    byte[] bytes = new byte[item.remaining()];
                    item.get(bytes);
                    output.write(bytes, 0, bytes.length);
                }
                Flow.Subscription current = subscription.get();
                if (current != null && !terminal.get()) current.request(1);
            } catch (RuntimeException e) {
                fail(e, true);
            }
        }
        @Override public void onError(Throwable error) { fail(error, false); }
        @Override public void onComplete() {
            if (terminal.compareAndSet(false, true)) {
                cancelTimer();
                body.complete(output.toByteArray());
            }
        }
        private void fail(Throwable error, boolean cancel) {
            if (!terminal.compareAndSet(false, true)) return;
            cancelTimer();
            Flow.Subscription current = subscription.get();
            if (cancel && current != null) current.cancel();
            body.completeExceptionally(error);
        }
        private void cancelTimer() {
            ScheduledFuture<?> task = timeoutTask.getAndSet(null);
            if (task != null) task.cancel(false);
        }
    }

    private static ScheduledThreadPoolExecutor scheduler() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "sdv-ollama-body-deadline");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    private static long remaining(long started, long timeout) {
        return Math.max(0, timeout - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }

    private enum TransportKind { SUCCESS, TIMEOUT, FAILED }
    private record TransportResult(TransportKind kind, byte[] response) { }
    private record Options(int num_ctx, int num_predict) { }
    private record OllamaRequest(String model, String system, String prompt, boolean stream, Object format,
            Options options) { }
}
