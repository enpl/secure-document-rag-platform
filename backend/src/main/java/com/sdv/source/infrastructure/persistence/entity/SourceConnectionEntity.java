package com.sdv.source.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * source_connections 테이블의 JPA persistence 매핑.
 *
 * 이 단계에서는 순수 영속성 매핑만 담당하며, type / status / sync_mode 값 목록은
 * 아직 확정되지 않아 String으로 매핑한다(추후 enum 도입 예정).
 */
@Entity
@Table(name = "source_connections")
public class SourceConnectionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    // GOOGLE_DRIVE, LOCAL_VAULT 등 Source 종류
    @Column(name = "type", nullable = false, length = 50)
    private String type;

    @Column(name = "display_name", nullable = false, length = 255)
    private String displayName;

    // ACTIVE, DISABLED, ERROR 등
    @Column(name = "status", nullable = false, length = 30)
    private String status;

    // FULL, INCREMENTAL 등 동기화 방식
    @Column(name = "sync_mode", nullable = false, length = 30)
    private String syncMode;

    // 실제 OAuth Token이 아닌, 안전하게 보관된 Token의 참조값
    @Column(name = "token_ref", length = 500)
    private String tokenRef;

    @Column(name = "last_sync_at")
    private Instant lastSyncAt;

    protected SourceConnectionEntity() {
        // JPA
    }

    public SourceConnectionEntity(String type, String displayName, String status, String syncMode) {
        this.type = type;
        this.displayName = displayName;
        this.status = status;
        this.syncMode = syncMode;
    }

    public Long getId() {
        return id;
    }

    public String getType() {
        return type;
    }

    public String getDisplayName() {
        return displayName;
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

    /** Source 연결 상태를 변경한다(예: ACTIVE -> DISABLED/ERROR). */
    public void changeStatus(String status) {
        this.status = status;
    }

    /** 동기화 완료 시각을 기록한다. */
    public void recordSync(Instant lastSyncAt) {
        this.lastSyncAt = lastSyncAt;
    }

    /** 보관 중인 Token 참조값을 갱신한다(재인증 등으로 참조가 바뀌는 경우). */
    public void updateTokenRef(String tokenRef) {
        this.tokenRef = tokenRef;
    }
}
