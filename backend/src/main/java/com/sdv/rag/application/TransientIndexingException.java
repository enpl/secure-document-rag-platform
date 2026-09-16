package com.sdv.rag.application;

/**
 * M11 신규 - {@link IndexOrchestrator#process}가 이번 시도는 실패했지만 나중에
 * 성공할 수도 있다고 판단할 때 던진다({@code IndexRequestedConsumer}가 이를
 * Spring Kafka의 {@code DefaultErrorHandler}(경계 있는 재시도+Backoff)로 넘겨
 * 재전달을 유도한다 - 이 Class 자체는 재시도 횟수를 세지 않는다). 메시지는
 * 항상 안전한 고정 사유여야 한다 - 원본 예외의 Stack Trace/메시지를 그대로
 * 옮기지 않는다(CLAUDE.md "예외 본문 로그 금지" 원칙).
 */
public class TransientIndexingException extends RuntimeException {

    public TransientIndexingException(String safeReason) {
        super(safeReason);
    }
}
