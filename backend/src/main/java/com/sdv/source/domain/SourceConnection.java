package com.sdv.source.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * F-BE-019. 기술 독립적 Source 연결 모델(SRC-002, SRC-010).
 *
 * JPA Annotation을 갖지 않으며, Google 등 외부 API를 호출하지 않는다.
 *
 * <p>{@code status}/{@code syncMode}는 v3.2 명세가 canonical Enum을
 * 정의하지 않으므로 기존 문자열 저장 계약을 그대로 유지한다 - 임의로
 * 새 Enum을 만들지 않는다. {@code activate()}/{@code deactivate()}는
 * 기존 active/disabled Lifecycle 의미만 사용한다.</p>
 */
public final class SourceConnection {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISABLED = "DISABLED";

    private final Long id;
    private final SourceType type;
    private final String displayName;
    private final String ownerSubject;
    private String status;
    private final String syncMode;
    private String tokenRef;
    private final Instant lastSyncAt;

    public SourceConnection(Long id, SourceType type, String displayName, String ownerSubject, String status,
            String syncMode, String tokenRef, Instant lastSyncAt) {
        this.id = id;
        this.type = Objects.requireNonNull(type, "type must not be null");
        this.displayName = requireNonBlank(displayName, "displayName");
        this.ownerSubject = requireNonBlank(ownerSubject, "ownerSubject");
        this.status = requireNonBlank(status, "status");
        this.syncMode = requireNonBlank(syncMode, "syncMode");
        this.tokenRef = tokenRef;
        this.lastSyncAt = lastSyncAt;
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    public void activate() {
        this.status = STATUS_ACTIVE;
    }

    public void deactivate() {
        this.status = STATUS_DISABLED;
    }

    public void clearTokenRef() {
        this.tokenRef = null;
    }

    public Long getId() {
        return id;
    }

    public SourceType getType() {
        return type;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getOwnerSubject() {
        return ownerSubject;
    }

    public String getStatus() {
        return status;
    }

    public String getSyncMode() {
        return syncMode;
    }

    public String getTokenRef() {
        return tokenRef;
    }

    public Instant getLastSyncAt() {
        return lastSyncAt;
    }
}
