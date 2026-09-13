package com.sdv.source.domain;

import java.util.Objects;

/**
 * M08 신규 - {@link com.sdv.source.application.port.DocumentSourceConnector#fetchContent}
 * 의 반환값. {@link SourceContentOutcome#VERIFIED}일 때만 {@link #content()}가
 * 존재한다(생성자가 강제한다) - "검증 전 Byte가 이미 소비 가능한 형태로
 * 노출"되는 것을 Java Type 수준에서 막는다(v1.4 §2A.5: Fetch 전/후 재확인을
 * 모두 통과한 Version에서만 Evidence를 사용한다).
 *
 * <p>{@link #content()}는 Fetch 완료 후 즉시 소비하고 참조를 버려야 한다 -
 * 이 Record 자체가 이 Byte 배열을 어디에도 durable하게 보관하지 않는다(v1.4
 * §2A.3, 원본/평문 비보관).</p>
 */
public record SourceContentResult(SourceContentOutcome outcome, byte[] content, String mimeType,
        String verifiedSourceVersion, boolean isTransientExport, String reason) {

    public SourceContentResult {
        Objects.requireNonNull(outcome, "outcome must not be null");
        if (outcome == SourceContentOutcome.VERIFIED) {
            Objects.requireNonNull(content, "content must not be null for VERIFIED");
            Objects.requireNonNull(mimeType, "mimeType must not be null for VERIFIED");
            Objects.requireNonNull(verifiedSourceVersion, "verifiedSourceVersion must not be null for VERIFIED");
        } else {
            if (content != null) {
                throw new IllegalArgumentException("content must be null unless outcome is VERIFIED");
            }
            Objects.requireNonNull(reason, "reason must not be null for a non-VERIFIED outcome");
        }
    }

    public static SourceContentResult verified(byte[] content, String mimeType, String verifiedSourceVersion,
            boolean isTransientExport) {
        return new SourceContentResult(SourceContentOutcome.VERIFIED, content, mimeType, verifiedSourceVersion,
                isTransientExport, null);
    }

    public static SourceContentResult failed(SourceContentOutcome outcome, String reason) {
        if (outcome == SourceContentOutcome.VERIFIED) {
            throw new IllegalArgumentException("use verified(...) for SourceContentOutcome.VERIFIED");
        }
        return new SourceContentResult(outcome, null, null, null, false, reason);
    }
}
