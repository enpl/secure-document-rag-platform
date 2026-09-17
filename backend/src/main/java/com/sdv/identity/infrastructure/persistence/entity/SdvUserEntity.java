package com.sdv.identity.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

@Entity
@Table(name = "sdv_users")
public class SdvUserEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 500)
    private String issuer;
    @Column(nullable = false, length = 255)
    private String subject;
    @Column(name = "login_id", nullable = false, length = 100)
    private String loginId;
    @Column(name = "normalized_login_id", nullable = false, length = 100)
    private String normalizedLoginId;
    @Column(name = "display_name", length = 200)
    private String displayName;
    @Column(name = "max_classification", length = 30)
    private String maxClassification;
    @Column(nullable = false)
    private boolean active;
    @Column(name = "authorization_revision", nullable = false)
    private long authorizationRevision;
    @Version
    private long version;
    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;
    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    protected SdvUserEntity() { }

    public SdvUserEntity(String issuer, String subject, String loginId, String normalizedLoginId,
            String displayName, Instant now) {
        this.issuer = issuer;
        this.subject = subject;
        this.loginId = loginId;
        this.normalizedLoginId = normalizedLoginId;
        this.displayName = displayName;
        this.active = true;
        this.authorizationRevision = 1L;
        this.firstSeenAt = now;
        this.lastSeenAt = now;
    }

    public void observe(String loginId, String normalizedLoginId, String displayName, Instant now) {
        this.loginId = loginId;
        this.normalizedLoginId = normalizedLoginId;
        this.displayName = displayName;
        this.lastSeenAt = now;
    }

    public boolean changeAccess(String maximumClassification, boolean active) {
        if (java.util.Objects.equals(this.maxClassification, maximumClassification) && this.active == active) {
            return false;
        }
        this.maxClassification = maximumClassification;
        this.active = active;
        this.authorizationRevision++;
        return true;
    }

    public Long getId() { return id; }
    public String getIssuer() { return issuer; }
    public String getSubject() { return subject; }
    public String getLoginId() { return loginId; }
    public String getNormalizedLoginId() { return normalizedLoginId; }
    public String getDisplayName() { return displayName; }
    public String getMaxClassification() { return maxClassification; }
    public boolean isActive() { return active; }
    public long getAuthorizationRevision() { return authorizationRevision; }
    public long getVersion() { return version; }
    public Instant getLastSeenAt() { return lastSeenAt; }
}
