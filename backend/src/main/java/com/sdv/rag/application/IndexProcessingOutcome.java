package com.sdv.rag.application;

/**
 * M11 신규 - {@link IndexOrchestrator#process}가 정상적으로("재시도가 필요 없는
 * 방식으로") 끝났을 때의 종결 결과. {@link TransientIndexingException}이 대신
 * 던져지는 경우(재시도 대상)는 이 값으로 표현하지 않는다 - 둘은 서로 다른 신호
 * 채널이다(값 반환 = 종결, 예외 = 경계 있는 재시도 대상).
 */
public enum IndexProcessingOutcome {
    /** 새 Embedding Generation이 원자적으로 발행됐다. */
    INDEXED,
    /** 지금 이 순간 색인 자격이 없다(공유 없음/철회/관리자 차단/AI 정책 거부/연결 비활성 등) - 다음 관련 이벤트가 다시 판단하게 한다. */
    SKIPPED_INELIGIBLE,
    /** Core 지원 포맷이 아니다(Metadata 사전 분류 또는 Provider/Parser의 권위 있는 판단). */
    SKIPPED_UNSUPPORTED,
    /** 지원 포맷이지만 추출 가능한 텍스트가 없다. */
    SKIPPED_NO_TEXT
}
