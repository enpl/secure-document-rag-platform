package com.sdv.ai.application;

import com.sdv.ai.application.port.LlmPort;
import org.springframework.stereotype.Service;

import java.util.concurrent.Semaphore;

/** Fail-fast finite admission around both classification and business generation calls. */
@Service
public class PolicyEnforcedLlmGateway {
    public enum Failure { NONE, TIMEOUT, CAPACITY_EXHAUSTED, MODEL_UNAVAILABLE }
    public record Result<T>(T value, Failure failure) {
        static <T> Result<T> success(T value) { return new Result<>(value, Failure.NONE); }
        static <T> Result<T> failure(Failure failure) { return new Result<>(null, failure); }
    }

    private final LlmPort port;
    private final AssistantProperties properties;
    private final Semaphore admission;

    public PolicyEnforcedLlmGateway(LlmPort port, AssistantProperties properties) {
        this.port = port;
        this.properties = properties;
        this.admission = new Semaphore(properties.maxConcurrentModelCalls(), true);
    }

    public Result<AssistantIntent> classify(String question, AskDeadline deadline) {
        if (!properties.enabled() || "disabled".equals(properties.model())) return Result.failure(Failure.MODEL_UNAVAILABLE);
        if (deadline.expired()) return Result.failure(Failure.TIMEOUT);
        if (!admission.tryAcquire()) return Result.failure(Failure.CAPACITY_EXHAUSTED);
        try {
            var result = port.classify(question, properties.model(), deadline.phaseBudget(properties.classificationDeadlineMs()));
            return switch (result.kind()) {
                case SUCCESS -> Result.success(result.intent());
                case TIMEOUT -> Result.failure(Failure.TIMEOUT);
                case FAILED -> Result.failure(Failure.MODEL_UNAVAILABLE);
            };
        } finally {
            admission.release();
        }
    }

    public Result<LlmPort.GenerationResult> generate(LlmPort.GenerationRequest request, AskDeadline deadline) {
        if (!properties.enabled() || "disabled".equals(properties.model())) return Result.failure(Failure.MODEL_UNAVAILABLE);
        if (deadline.expired()) return Result.failure(Failure.TIMEOUT);
        if (!admission.tryAcquire()) return Result.failure(Failure.CAPACITY_EXHAUSTED);
        try {
            var result = port.generate(request, properties.model(), deadline.phaseBudget(properties.generationDeadlineMs()));
            if (result.kind() == LlmPort.GenerationResult.Kind.TIMEOUT) return Result.failure(Failure.TIMEOUT);
            if (result.kind() != LlmPort.GenerationResult.Kind.SUCCESS) return Result.failure(Failure.MODEL_UNAVAILABLE);
            return Result.success(result);
        } finally {
            admission.release();
        }
    }
}
