package com.sdv.rag.domain;

import java.util.Objects;

/**
 * M12 신규 - 파일 하나에 대한 Live Retrieval 시도 결과. {@code status ==
 * VERIFIED}일 때만 {@code evidenceHandle}/{@code locatorType}/{@code
 * locatorValue}가 존재한다. {@code partialCoverage}는 이 근거가 문서의 일부만
 * 담고 있음을 정직하게 알린다(문서가 여러 위치(Page/Section 등)로 구성돼 있는데
 * 그 중 하나만 선택됐거나, 선택된 발췌가 크기 상한으로 잘렸을 때) - 이런 경우를
 * "완전한 요약/비교"인 것처럼 절대 제시하지 않는다.
 */
public record LiveRetrievalResult(Long documentId, LiveRetrievalStatus status, EvidenceHandle evidenceHandle,
        LocatorType locatorType, String locatorValue, boolean partialCoverage) {

    public LiveRetrievalResult {
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        if (status == LiveRetrievalStatus.VERIFIED) {
            Objects.requireNonNull(evidenceHandle, "evidenceHandle must not be null when VERIFIED");
            Objects.requireNonNull(locatorType, "locatorType must not be null when VERIFIED");
            Objects.requireNonNull(locatorValue, "locatorValue must not be null when VERIFIED");
        } else if (evidenceHandle != null || locatorType != null || locatorValue != null) {
            throw new IllegalArgumentException("evidence fields must be null unless status is VERIFIED");
        }
    }

    public static LiveRetrievalResult verified(Long documentId, EvidenceHandle evidenceHandle,
            LocatorType locatorType, String locatorValue, boolean partialCoverage) {
        return new LiveRetrievalResult(documentId, LiveRetrievalStatus.VERIFIED, evidenceHandle, locatorType,
                locatorValue, partialCoverage);
    }

    public static LiveRetrievalResult failed(Long documentId, LiveRetrievalStatus status) {
        if (status == LiveRetrievalStatus.VERIFIED) {
            throw new IllegalArgumentException("use verified(...) for LiveRetrievalStatus.VERIFIED");
        }
        return new LiveRetrievalResult(documentId, status, null, null, null, false);
    }
}
