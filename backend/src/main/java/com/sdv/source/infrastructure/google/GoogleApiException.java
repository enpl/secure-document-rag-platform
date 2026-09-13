package com.sdv.source.infrastructure.google;

/**
 * M08 신규 - {@link GoogleDriveClient} 안에서만 쓰이는(외부로 새어나가지
 * 않는) Google Drive API 호출 실패의 안전한 분류. 메시지는 항상 고정된
 * 짧은 문자열이다 - Google이 실제로 반환한 원본 오류 본문(때로는 요청
 * 세부사항을 반영하는)을 그대로 옮기지 않는다(로그/예외에 민감정보가
 * 남지 않도록).
 *
 * <p>{@code https://developers.google.com/workspace/drive/api/guides/handle-errors}
 * (2026-09-13 확인)가 문서화한 재시도 정책을 그대로 반영한다: 429/5xx와
 * 403의 Quota/Rate-limit 계열(reason: {@code rateLimitExceeded},
 * {@code userRateLimitExceeded}, {@code dailyLimitExceeded},
 * {@code storageQuotaExceeded})만 재시도 대상이고, 403의 권한 계열
 * (reason: {@code insufficientFilePermissions}, {@code appNotAuthorizedToFile},
 * {@code domainPolicy})과 401/404/400은 재시도하지 않는다.</p>
 */
public class GoogleApiException extends RuntimeException {

    public enum Category {
        /** 401 - Token이 없거나 Google이 거부했다. Refresh는 이 Layer의 책임이 아니다(Class Javadoc의 상위 문서 참고). */
        UNAUTHORIZED,
        /** 403 권한 계열 - 재시도하지 않는다. */
        PERMISSION_DENIED,
        /** 403 Quota/Rate-limit 계열 또는 429 - Bounded Backoff로 재시도한다. */
        QUOTA_OR_RATE_LIMIT,
        /** 404 - 이 사용자 기준 존재하지 않거나 접근할 수 없다(구분하지 않는다). */
        NOT_FOUND,
        /** 5xx - Bounded Backoff로 재시도한다. */
        RETRYABLE_SERVER_ERROR,
        /** 400 등 그 밖의 Client 오류 - 재시도하지 않는다. */
        BAD_REQUEST,
        /** 분류할 수 없는 실패(Network/Timeout/알 수 없는 응답 등). */
        UNKNOWN
    }

    private final Category category;

    public GoogleApiException(Category category, String safeMessage) {
        super(safeMessage);
        this.category = category;
    }

    public GoogleApiException(Category category, String safeMessage, Throwable cause) {
        super(safeMessage, cause);
        this.category = category;
    }

    public Category getCategory() {
        return category;
    }

    public boolean isRetryable() {
        return category == Category.QUOTA_OR_RATE_LIMIT || category == Category.RETRYABLE_SERVER_ERROR;
    }
}
