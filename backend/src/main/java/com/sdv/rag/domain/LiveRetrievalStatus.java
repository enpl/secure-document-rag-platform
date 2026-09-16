package com.sdv.rag.domain;

/**
 * M12 신규 - {@code LiveEvidenceRetrievalService.retrieveLive}/{@code
 * retryOnVersionChange} 한 파일 시도의 결과. 이 값 자체는 Provider/Parser 응답
 * 본문이나 원본 예외 메시지를 담지 않는다({@code LiveRetrievalException.Reason}과
 * 1:1로 대응).
 */
public enum LiveRetrievalStatus {
    /** 검증된 근거가 생성되어 {@link EvidenceHandle}로 노출됐다. */
    VERIFIED,
    /** 요청자 SDV 공유 인가가 없거나 재확인 시점에 더 이상 유효하지 않다. */
    NOT_AUTHORIZED,
    /** Provider 접근/버전/연결 문제로 콘텐츠를 신뢰 가능하게 가져올 수 없다. */
    NOT_AVAILABLE,
    /** 1회 재시도 후에도 Source Version이 계속 바뀌었다. */
    DOCUMENT_CHANGED,
    /** 검증된 근거를 하나도 만들 수 없었다(텍스트 없음/추출 실패). */
    NO_EVIDENCE,
    /** Core AI 처리 대상 형식이 아니다. */
    UNSUPPORTED_FORMAT,
    /** 이 요청/Store에 허용된 유한 자원 상한을 넘었다. */
    CAPACITY_EXHAUSTED,
    /** Google Workspace synchronous export exceeded the provider's supported bound. */
    EXPORT_LIMIT_EXCEEDED,
    /** The shared request deadline expired; this is distinct from finite capacity admission. */
    REQUEST_TIMEOUT
}
