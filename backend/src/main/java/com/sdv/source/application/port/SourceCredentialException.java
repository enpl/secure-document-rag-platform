package com.sdv.source.application.port;

/**
 * M08 신규 - {@link DocumentSourceConnector#getMetadata}/{@link DocumentSourceConnector#findChanges}
 * (Catalog Sync 성격의 메서드)가 이 Source에 대한 Credential을 신뢰 가능하게
 * 얻을 수 없을 때 던지는 Unchecked 예외. {@link #getReason()}은 안전한
 * 분류값일 뿐이다 - 원본 Token/Secret 값은 이 예외의 메시지/필드 어디에도
 * 담기지 않는다(로그/예외 메시지에 남아도 안전하다).
 */
public class SourceCredentialException extends RuntimeException {

    public enum Reason {
        MISSING_CREDENTIAL,
        CREDENTIAL_UNREADABLE,
        INSUFFICIENT_SCOPE,
        REAUTHORIZATION_REQUIRED
    }

    private final Reason reason;

    public SourceCredentialException(Reason reason, String safeMessage) {
        super(safeMessage);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
