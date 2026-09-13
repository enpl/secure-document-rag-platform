package com.sdv.source.infrastructure.google;

/**
 * M08 MVP OAuth ({@code docs/spec/SDV_M08_TOKEN_CONTRACT.md}) - {@link GoogleOAuthClient}
 * 안에서만 쓰이는(외부로 새어나가지 않는) OAuth 프로토콜 호출 실패의 안전한 분류.
 * {@link GoogleApiException}과 동일한 이유로 메시지는 항상 고정된 짧은 문자열이다 -
 * Google이 실제로 반환한 원본 오류 본문(때로는 Authorization Code/State/Client Secret
 * 관련 세부사항을 반영할 수 있는)을 그대로 옮기지 않는다.
 */
public class GoogleOAuthException extends RuntimeException {

    public enum Category {
        /** Authorization Code/Refresh Token이 거부됐다(만료/취소/재사용 등) - 재인증이 필요하다. */
        INVALID_GRANT,
        /** Network/Timeout 등 - Google 응답 자체를 신뢰 가능하게 받지 못했다. */
        NETWORK_OR_TIMEOUT,
        /** 응답을 받았지만 해석할 수 없다(Malformed). */
        MALFORMED_RESPONSE,
        /** 분류할 수 없는 실패. */
        UNKNOWN
    }

    private final Category category;

    public GoogleOAuthException(Category category, String safeMessage) {
        super(safeMessage);
        this.category = category;
    }

    public GoogleOAuthException(Category category, String safeMessage, Throwable cause) {
        super(safeMessage, cause);
        this.category = category;
    }

    public Category getCategory() {
        return category;
    }
}
