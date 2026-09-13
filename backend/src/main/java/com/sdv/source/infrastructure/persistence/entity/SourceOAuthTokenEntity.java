package com.sdv.source.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * M08 MVP OAuth ({@code docs/spec/SDV_M08_TOKEN_CONTRACT.md}) - {@code source_oauth_tokens}
 * (V007)의 JPA Persistence 매핑. {@link com.sdv.source.infrastructure.google.GoogleTokenService}만
 * 이 Entity를 다룬다 - 암호화되지 않은 Token 값은 어떤 필드에도 존재하지 않는다
 * ({@link #ciphertext}는 직렬화된 TokenEnvelope의 AES/GCM 암호문일 뿐이다).
 *
 * <p>{@link #rowVersion}은 JPA {@link Version}(낙관적 잠금) - Refresh/재연결/Disconnect가
 * 경합하면 나중에 Commit을 시도하는 Transaction이 {@link jakarta.persistence.OptimisticLockException}으로
 * 실패한다(V007 Migration 주석 참고).</p>
 */
@Entity
@Table(name = "source_oauth_tokens")
public class SourceOAuthTokenEntity {

    @Id
    @Column(name = "token_ref", nullable = false, updatable = false)
    private UUID tokenRef;

    @Column(name = "source_id", nullable = false, unique = true, updatable = false)
    private Long sourceId;

    @Column(name = "owner_subject", nullable = false, length = 255)
    private String ownerSubject;

    @Column(name = "format_version", nullable = false)
    private int formatVersion;

    @Column(name = "key_id", nullable = false, length = 100)
    private String keyId;

    @Column(name = "nonce", nullable = false)
    private byte[] nonce;

    @Column(name = "ciphertext", nullable = false)
    private byte[] ciphertext;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SourceOAuthTokenEntity() {
        // JPA
    }

    public SourceOAuthTokenEntity(UUID tokenRef, Long sourceId, String ownerSubject, int formatVersion, String keyId,
            byte[] nonce, byte[] ciphertext, Instant now) {
        this.tokenRef = tokenRef;
        this.sourceId = sourceId;
        this.ownerSubject = ownerSubject;
        this.formatVersion = formatVersion;
        this.keyId = keyId;
        this.nonce = nonce;
        this.ciphertext = ciphertext;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** 재연결/Refresh 시 같은 행을 교체(In-place Update)한다 - {@link #tokenRef}/{@link #sourceId}는 바꾸지 않는다. */
    public void replacePayload(String ownerSubject, int formatVersion, String keyId, byte[] nonce, byte[] ciphertext,
            Instant now) {
        this.ownerSubject = ownerSubject;
        this.formatVersion = formatVersion;
        this.keyId = keyId;
        this.nonce = nonce;
        this.ciphertext = ciphertext;
        this.updatedAt = now;
    }

    public UUID getTokenRef() {
        return tokenRef;
    }

    public Long getSourceId() {
        return sourceId;
    }

    public String getOwnerSubject() {
        return ownerSubject;
    }

    public int getFormatVersion() {
        return formatVersion;
    }

    public String getKeyId() {
        return keyId;
    }

    public byte[] getNonce() {
        return nonce;
    }

    public byte[] getCiphertext() {
        return ciphertext;
    }

    public long getRowVersion() {
        return rowVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
