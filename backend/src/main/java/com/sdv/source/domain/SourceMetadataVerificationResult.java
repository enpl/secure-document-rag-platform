package com.sdv.source.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * M10 신규 - {@link com.sdv.source.application.port.DocumentSourceConnector#verifyCurrentMetadata}
 * 의 반환값. {@link SourceMetadataVerificationOutcome#VERIFIED}일 때만 이름/타입/
 * Version이 존재한다(생성자가 강제한다) - {@link SourceContentResult}와 같은
 * 원칙이다(검증 전 값이 노출되는 것을 Type 수준에서 막는다).
 *
 * <p>{@code downloadable}은 Google {@code capabilities.canDownload}를 있는 그대로
 * 옮긴 값이다 - 이 값이 {@code true}라는 것이 이 문서의 Content를 실제로 읽어도
 * 된다는 뜻은 아니다(v1.4 §2A.4/§2A.5: 메타데이터 가시성과 콘텐츠 접근은 서로
 * 다른 결정이며, 이 메서드는 Content 접근권한을 최종 판단하지 않는다).</p>
 *
 * <p><b>M10 후속 교정</b> - {@code modifiedAt}도 이제 {@link
 * SourceMetadataVerificationOutcome#VERIFIED}일 때 필수(non-null)다. 이전에는
 * Google이 {@code modifiedTime}을 누락하거나 파싱할 수 없게 보내도 {@code
 * VERIFIED}가 만들어질 수 있어, "필요한 수정 시각 메타데이터를 확인하지 못했다"는
 * 사실이 노출된 결과에서 조용히 사라졌다 - 호출자({@code GoogleDriveConnector})는
 * 이제 유효한 수정 시각을 확인하지 못하면 {@code VERIFIED}를 만들지 않고 안전한
 * 실패로 대체해야 한다.</p>
 */
public record SourceMetadataVerificationResult(SourceMetadataVerificationOutcome outcome, String name,
        String mimeType, String sourceVersion, Instant modifiedAt, Boolean downloadable, String reason) {

    public SourceMetadataVerificationResult {
        Objects.requireNonNull(outcome, "outcome must not be null");
        if (outcome == SourceMetadataVerificationOutcome.VERIFIED) {
            Objects.requireNonNull(name, "name must not be null for VERIFIED");
            Objects.requireNonNull(mimeType, "mimeType must not be null for VERIFIED");
            Objects.requireNonNull(sourceVersion, "sourceVersion must not be null for VERIFIED");
            Objects.requireNonNull(modifiedAt, "modifiedAt must not be null for VERIFIED");
            Objects.requireNonNull(downloadable, "downloadable must not be null for VERIFIED");
        } else {
            if (name != null || mimeType != null || sourceVersion != null || modifiedAt != null
                    || downloadable != null) {
                throw new IllegalArgumentException("metadata fields must be null unless outcome is VERIFIED");
            }
            Objects.requireNonNull(reason, "reason must not be null for a non-VERIFIED outcome");
        }
    }

    public static SourceMetadataVerificationResult verified(String name, String mimeType, String sourceVersion,
            Instant modifiedAt, boolean downloadable) {
        return new SourceMetadataVerificationResult(SourceMetadataVerificationOutcome.VERIFIED, name, mimeType,
                sourceVersion, modifiedAt, downloadable, null);
    }

    public static SourceMetadataVerificationResult failed(SourceMetadataVerificationOutcome outcome, String reason) {
        if (outcome == SourceMetadataVerificationOutcome.VERIFIED) {
            throw new IllegalArgumentException("use verified(...) for SourceMetadataVerificationOutcome.VERIFIED");
        }
        return new SourceMetadataVerificationResult(outcome, null, null, null, null, null, reason);
    }
}
