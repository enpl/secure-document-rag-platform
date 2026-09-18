package com.sdv.rag.application;

import java.util.Objects;

/**
 * M17 진단 교정 - {@link IndexOrchestrator#processWithReasonCode}가 반환하는, 종결
 * {@link IndexProcessingOutcome}과 그 판정 시점에 결정된 고정 사유 코드의 쌍.
 * {@link IndexRequestedConsumer}가 {@code processed_events.reason_code}에 그대로
 * 옮겨 적는다({@link com.sdv.rag.infrastructure.event.IndexRequestedConsumer} 참고).
 *
 * <p>{@code reasonCode}는 항상 이 값을 만든 판정 지점 자신이 채운, 작고 고정된
 * 허용 값 중 하나다({@link IneligibilityStage#reasonCode()} 또는 기존 AI
 * 서비스/사전 분류 사유 코드) - 원시 예외 메시지, Google 응답 본문, 파일명, 토큰,
 * 원문은 어디에도 담기지 않는다. {@code null}이면 이 outcome에 대해 기록할 추가
 * 사유가 없다는 뜻이다(예: {@code INDEXED}).</p>
 */
public record IndexProcessingResult(IndexProcessingOutcome outcome, String reasonCode) {

    public IndexProcessingResult {
        Objects.requireNonNull(outcome, "outcome must not be null");
    }
}
