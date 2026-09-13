package com.sdv.source.application.port;

/**
 * M08 신규 - {@link DocumentSourceConnector#getMetadata}/{@link DocumentSourceConnector#findChanges}
 * (Catalog Sync 성격의 메서드)가 Credential 문제가 아닌 다른 이유로 실패했을
 * 때 던지는 Unchecked 예외({@link SourceCredentialException}은 Credential
 * 문제 전용이다 - 둘을 하나로 합치지 않는다). {@link #getReason()}은 안전한
 * 분류값일 뿐이다.
 */
public class SourceSyncException extends RuntimeException {

    public enum Reason {
        /** 이 사용자(Owner Credential) 기준 존재하지 않거나 접근할 수 없다(구분하지 않는다). */
        NOT_FOUND,
        /** 신뢰 가능한 답을 얻지 못했다(Quota/일시적 서버 오류 등 재시도 소진 후) - Fail Closed. */
        ACCESS_UNKNOWN,
        /** 그 밖의 예상치 못한 실패. */
        FAILED
    }

    private final Reason reason;

    public SourceSyncException(Reason reason, String safeMessage) {
        super(safeMessage);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
