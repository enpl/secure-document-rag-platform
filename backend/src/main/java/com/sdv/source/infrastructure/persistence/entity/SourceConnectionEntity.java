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

    // 인증된 Keycloak sub(F-BE-008 UserContext.subject) - 계정 격리(Account
    // Isolation)의 신뢰 기준. API 요청에서 받지 않는다(V004).
    @Column(name = "owner_subject", nullable = false, length = 255)
    private String ownerSubject;

    // M10B(V010) - Google `about.user.permissionId` 같은 안정적 Provider 계정
    // 식별자. 최초 성공적 인증이 채택하고, 이후 재연결 시도는 새로 확인한
    // 값과 이 값을 비교한다(GoogleDriveOAuthService) - 이메일/표시 이름이
    // 아니라 Provider가 반환하는 값만 신뢰한다. 이 Migration 이전 기존 행은
    // null이다.
    @Column(name = "provider_account_id", length = 255)
    private String providerAccountId;

    // M10B 보안 교정(V011) - Disconnect마다 증가하는 연결 인가 세대(Connection/
    // Authorization Epoch). source_oauth_tokens의 Token Refresh 버전과는 완전히 다른
    // 개념이다 - 일상적인 Refresh로는 절대 증가하지 않는다(GoogleDriveOAuthService
    // Class Javadoc, SourceConnectionService.disconnect 참고). Disconnect 이전에
    // 시작된 OAuth 시도나 공유 접근 판단이 나중에 완료/재확인돼도, 이 값이 그 시작
    // 시점과 다르면 안전하게 거부된다.
    @Column(name = "connection_epoch", nullable = false)
    private long connectionEpoch = 1L;

    protected SourceConnectionEntity() {
        // JPA
    }

    public SourceConnectionEntity(String type, String displayName, String status, String syncMode,
            String ownerSubject) {
        this.type = type;
        this.displayName = displayName;
        this.status = status;
        this.syncMode = syncMode;
        this.ownerSubject = ownerSubject;
    }

    public Long getId() {
        return id;
    }

    public String getOwnerSubject() {
        return ownerSubject;
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

    public String getProviderAccountId() {
        return providerAccountId;
    }

    /** M10B - 최초 성공적 인증에서 확인한 안정적 Provider 계정 식별자를 채택한다(추측하지 않는다). */
    public void adoptProviderAccountId(String providerAccountId) {
        this.providerAccountId = providerAccountId;
    }

    public long getConnectionEpoch() {
        return connectionEpoch;
    }

    /**
     * M10B 보안 교정(V011) - 연결 인가 세대를 1 올린다. 이 시점 이전에 시작된 OAuth
     * 시도/공유 접근 판단을 전부 무효화하려는 의도다.
     *
     * <h2>M10B 후속 교정 - 이 Method 자체를 {@code disconnect()}가 직접 쓰지 않는다</h2>
     * <p>이 Method는 이미 관리 중인(Managed) Java 객체의 현재 필드 값에 +1만 할 뿐,
     * DB의 지금 실제 값을 다시 확인하지 않는다 - 두 Disconnect가 겹치면 두 Transaction
     * 모두 같은(낡은) 시작 값을 기준으로 각자 +1을 계산해, 한쪽의 증가가 그대로
     * 유실될 수 있었다(Lost Increment, 두 번째 Disconnect가 실제로는 세대를 전혀
     * 전진시키지 못함). {@code SourceConnectionService.disconnect()}는 이제 이 Method
     * 대신 {@code SourceConnectionJpaRepository.incrementConnectionEpoch}(원자적 DB
     * 단 증가) + {@link #syncConnectionEpoch(long)}(그 결과를 이 Managed 객체에 반영)
     * 조합을 쓴다 - 자세한 이유는 그 Repository Method의 Javadoc 참고. 이 Method는
     * 동시성이 없는 단순 시나리오(Test에서 "Disconnect가 일어났다"를 직접 흉내내는
     * 경우 등)를 위해 그대로 남겨둔다.</p>
     */
    public void bumpConnectionEpoch() {
        this.connectionEpoch++;
    }

    /**
     * M10B 후속 교정(연결 인가 세대 손실 방지) - DB에서 원자적으로 증가시킨 뒤 다시
     * 읽은 "지금 실제 값"을 이 Managed 객체에 그대로 대입한다({@link
     * #bumpConnectionEpoch()}처럼 현재 Java 값에 +1을 가정하지 않는다). 이렇게 동기화해
     * 둬야, 이 Transaction이 나중에 {@code status}/{@code tokenRef} 변경을 Flush할 때
     * Hibernate가 이 Entity의 전체 Column을 다시 쓰면서(기본적으로 Dynamic Update가
     * 아니므로) 방금 원자적으로 반영한 증가분을 낡은 값으로 덮어쓰지 않는다.
     */
    public void syncConnectionEpoch(long currentPersistedValue) {
        this.connectionEpoch = currentPersistedValue;
    }
}
