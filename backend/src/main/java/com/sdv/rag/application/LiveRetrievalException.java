package com.sdv.rag.application;

/**
 * M12 신규 - Mandatory Live Retrieval(§2A.5) 도중의 Content-Free 실패. Provider/
 * Parser 응답 본문이나 원본 예외 메시지를 절대 담지 않는다 - 고정된 사유 코드만
 * 옮긴다({@code com.sdv.source.application.SharedFileDownloadException}과 동일한
 * 관례).
 */
public class LiveRetrievalException extends RuntimeException {

    public enum Reason {
        /** 요청자 SDV 공유 인가 자체가 없거나(위조/낡은 결합 포함) 재확인 시점에 더 이상 유효하지 않다. */
        NOT_AUTHORIZED,
        /** Provider 접근/버전/형식/연결 문제로 지금 이 콘텐츠를 신뢰 가능하게 가져올 수 없다. */
        NOT_AVAILABLE,
        /** 1회 재시도 후에도 Source Version이 계속 바뀌었다 - 이번 시도 전체를 폐기했다. */
        DOCUMENT_CHANGED,
        /** 검증된 근거를 하나도 만들 수 없었다(텍스트 없음/추출 실패 등). */
        NO_EVIDENCE,
        /** Core AI 처리 대상 형식이 아니다(PDF/DOCX/TXT/MD + Google Docs Export 외). */
        UNSUPPORTED_FORMAT,
        /** 이 요청/Store에 허용된 유한 자원 상한(파일 수/시간/근거 크기 등)을 넘었다. */
        CAPACITY_EXHAUSTED,
        /** 이 파일에 허용된 벽시계 예산을 넘었다. */
        REQUEST_TIMEOUT
    }

    private final Reason reason;

    public LiveRetrievalException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
